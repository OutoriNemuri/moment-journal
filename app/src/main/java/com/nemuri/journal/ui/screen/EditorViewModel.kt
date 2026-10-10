package com.nemuri.journal.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nemuri.journal.data.db.Entry
import com.nemuri.journal.data.repo.MomentRepository
import com.nemuri.journal.util.TimeFormat
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/**
 * 编辑界面状态机。
 *
 * ## 即刻保存
 * "一旦修改即保存"不等于"每个按键一条 UPDATE"。实现为：
 * 输入先写内存状态（保证 UI 零延迟），再经 [SAVE_DEBOUNCE_MS] 防抖落库。
 * 另外在 [flushNow] 中处理边界：退出编辑、切后台、进程被杀前，
 * 都强制把待写的草稿推一次，避免最后几百毫秒的输入丢失。
 *
 * ## 退出时不得丢字
 * `onCleared()` 无法安全地跑挂起函数，所以真正的兜底挂在 Activity 的 onStop
 * —— 见 [EditorViewModel.flushNow] 的调用点。
 */
@OptIn(FlowPreview::class)
class EditorViewModel(
    private val repo: MomentRepository,
    private val entryId: Long,
) : ViewModel() {

    companion object {
        /** 写库防抖窗口。太小则退化成逐键写入，太大则丢字窗口变长。 */
        const val SAVE_DEBOUNCE_MS = 400L
    }

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    private val _body = MutableStateFlow("")
    val body: StateFlow<String> = _body.asStateFlow()

    private val _createdAt = MutableStateFlow(0L)
    val createdAt: StateFlow<Long> = _createdAt.asStateFlow()

    /**
     * 本篇所属的日记本。
     *
     * 供「查找」确定范围用：本日记本范围需要知道当前在哪本。
     * -1 表示尚未加载出来，调用方需处理该情形（查找页会退回「本篇」）。
     */
    private val _bookId = MutableStateFlow(-1L)
    val bookId: StateFlow<Long> = _bookId.asStateFlow()

    private val _saveState = MutableStateFlow(SaveState.SAVED)
    val saveState: StateFlow<SaveState> = _saveState.asStateFlow()

    /** 待写盘的草稿。null 表示当前没有未落库的修改。 */
    private var pending: Draft? = null

    /** 触发防抖落库的信号。conflate 保证连续输入不会堆积任务。 */
    private val dirty = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    init {
        viewModelScope.launch {
            val loaded = repo.findEntry(entryId) ?: run {
                _saveState.value = SaveState.MISSING
                return@launch
            }
            _title.value = loaded.title
            _body.value = loaded.body
            _createdAt.value = loaded.createdAt
            _bookId.value = loaded.bookId ?: -1L
        }

        viewModelScope.launch {
            dirty.debounce(SAVE_DEBOUNCE_MS).collect { persist() }
        }
    }

    fun onTitleChange(value: String) {
        if (_title.value == value) return
        _title.value = value
        markDirty()
    }

    fun onBodyChange(value: String) {
        if (_body.value == value) return
        _body.value = value
        markDirty()
    }

    private fun markDirty() {
        pending = Draft(_title.value, _body.value)
        _saveState.value = SaveState.PENDING
        dirty.tryEmit(Unit)
    }

    /**
     * 立即落库，跳过防抖等待。
     * 退出编辑、切后台、以及用户手动触发时调用。
     */
    suspend fun flushNow() {
        persist()
    }

    private suspend fun persist() {
        val draft = pending ?: return
        val current = repo.findEntry(entryId)
        if (current == null) {
            // 条目在编辑期间被删除（例如从列表长按删除）。绝不能静默丢弃，
            // 否则用户会以为改动保存了，实际上整篇日记连同内容一起消失。
            _saveState.value = SaveState.MISSING
            pending = null
            return
        }
        repo.saveEntry(current.copy(title = draft.title, body = draft.body))
        // 只有在此期间没有新改动时才清空待写内容，否则会把新输入一起丢掉
        if (pending == draft) {
            pending = null
            _saveState.value = SaveState.SAVED
        }
    }

    // ---------------------------------------------------------------- 复制

    /**
     * 生成可复制的文本。
     *
     * 输出 **Markdown 源文本**，而不是渲染后的纯文本。理由：本应用的正文本身就是
     * Markdown，`![time]` / `![img]` 等嵌入都是文本形式。复制成纯文本会丢掉全部
     * 样式与嵌入，粘到别处就失去原貌；而 Markdown 源文本粘回本应用任意一篇日记
     * 都能还原。反过来（要纯文本时自行处理）是能做到的，反之则不行。
     */
    fun copyText(includeHeader: Boolean, untitledPlaceholder: String): String {
        val body = _body.value
        if (!includeHeader) return body
        return buildString {
            // 无标题时的占位文字由界面传入：ViewModel 不是 Composable，
            // 拿不到 stringResource，也不该为了显示文案而持有 Context。
            append(_title.value.ifBlank { untitledPlaceholder })
            append('\n')
            append(TimeFormat.isoFromEpoch(_createdAt.value))
            append('\n')
            append('\n')
            append(body)
        }
    }

    // ---------------------------------------------------------------- 时间

    /**
     * 修改创建时间。
     *
     * 注意：列表按 `createdAt` 倒序排列，因此改完之后日记会跳到列表的相应位置。
     * 这是预期行为而非缺陷，界面上需要提示，否则用户会以为日记丢了。
     */
    fun changeCreatedAt(millis: Long) {
        _createdAt.value = millis
        viewModelScope.launch {
            val current = repo.findEntry(entryId) ?: return@launch
            repo.saveEntry(current.copy(createdAt = millis))
            _saveState.value = SaveState.SAVED
        }
    }

    // ---------------------------------------------------------------- 导出

    /** 导出用的快照：标题、正文、创建时间，以及文件名建议。 */
    data class ExportPayload(
        val title: String,
        val body: String,
        val createdAt: Long,
        val suggestedName: String,
    )

    /**
     * 生成导出内容。
     *
     * [plainText] 为 true 时把嵌入标记剥离成可读文字（供 TXT）；
     * 为 false 时原样输出 Markdown 源文本（供 .md）。
     */
    fun exportPayload(plainText: Boolean): ExportPayload {
        // 标题保持原样（可能为空）。空标题的显示兜底由界面负责，
        // 因为「无标题」这三个字需要随语言变化。
        val title = _title.value
        val body = if (plainText) stripEmbeds(_body.value) else _body.value
        val stamp = TimeFormat.fileStamp(_createdAt.value)
        // 文件名不能含路径分隔符，标题里的 / 会破坏 SAF 的显示名
        val safeTitle = title.replace(Regex("""[/\\:*?"<>|]"""), "_").take(40)
        return ExportPayload(
            title = title,
            body = body,
            createdAt = _createdAt.value,
            suggestedName = if (safeTitle.isBlank()) stamp else "$stamp $safeTitle",
        )
    }

    /**
     * 把嵌入标记压成可读文字。
     *
     * 图片在纯文本里没有意义，替换为其文件名；时间戳替换为本地可读形式。
     * 这不是渲染，只是让 TXT 读起来不像一串语法。
     */
    private fun stripEmbeds(body: String): String =
        body
            .replace(Regex("""!\[time]\(([^)]*)\)""")) { m ->
                "[" + TimeFormat.displayFromIso(m.groupValues[1]) + "]"
            }
            .replace(Regex("""!\[loc]\(([^,]*),([^,]*),?([^)]*)\)""")) { m ->
                // 地点在纯文本里用坐标表示，不引入需要翻译的词
                "[" + m.groupValues[3].ifBlank { m.groupValues[1] + "," + m.groupValues[2] } + "]"
            }
            .replace(Regex("""!\[img]\(([^)]*)\)""")) { m ->
                "[" + m.groupValues[1].substringAfterLast('/') + "]"
            }

    private data class Draft(val title: String, val body: String)

    enum class SaveState {
        SAVED,
        PENDING,
        /** 条目不存在（已被删除，或 entryId 无效）。此时不应静默当空日记处理。 */
        MISSING,
    }
}

/** 供列表界面复用的摘要生成。 */
object EntrySummary {
    private val IMAGE_TOKEN = Regex("""!\[(?:img|time|loc)]\([^)]*\)""")

    fun of(entry: Entry, maxChars: Int = 80): String {
        val stripped = IMAGE_TOKEN.replace(entry.body, " ").replace('\n', ' ').trim()
        return if (stripped.length <= maxChars) stripped else stripped.take(maxChars) + "…"
    }
}
