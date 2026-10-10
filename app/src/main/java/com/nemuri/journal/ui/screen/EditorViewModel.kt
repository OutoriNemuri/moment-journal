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
 * ## 加载状态必须显式
 * 此前以空字符串与 `0L` 作初值，而加载是异步的，于是打开日记时会先渲染一帧
 * 「空标题、空正文、1970 年」，数据到了才刷新。用一个"看起来正常"的默认时间
 * 去掩盖，只会让用户看到一段错得不明显的错误内容。
 *
 * 因此改为显式的 [LoadState]：**未加载完成就不渲染编辑区**。
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

    /**
     * 加载状态。
     *
     * 把「还没读到」与「读到了但没有」区分开 —— 这两者此前的表现形式都是
     * 空内容，用户无法分辨"正在加载"和"这篇日记是空的"。
     */
    sealed interface LoadState {
        /** 尚未从数据库取到。界面显示空白，不渲染编辑区。 */
        data object Loading : LoadState

        /** 已就绪，可以编辑。 */
        data object Ready : LoadState

        /** 条目不存在（已被删除，或 entryId 无效）。 */
        data object Missing : LoadState
    }

    private val _loadState = MutableStateFlow<LoadState>(LoadState.Loading)
    val loadState: StateFlow<LoadState> = _loadState.asStateFlow()

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    private val _body = MutableStateFlow("")
    val body: StateFlow<String> = _body.asStateFlow()

    private val _createdAt = MutableStateFlow(0L)
    val createdAt: StateFlow<Long> = _createdAt.asStateFlow()

    private val _saveState = MutableStateFlow(SaveState.SAVED)
    val saveState: StateFlow<SaveState> = _saveState.asStateFlow()

    /**
     * 本篇所属的日记本。供「查找」确定范围用。
     * -1 表示尚未加载出来，调用方需处理该情形（查找页会退回「本篇」）。
     */
    private val _bookId = MutableStateFlow(-1L)
    val bookId: StateFlow<Long> = _bookId.asStateFlow()

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
            val loaded = repo.findEntry(entryId)
            if (loaded == null) {
                _loadState.value = LoadState.Missing
                return@launch
            }
            // 先写数据、最后置为就绪：反过来的话，界面会在读到真实内容之前
            // 就解除"加载中"的遮蔽，等于把刚修掉的闪现又放回来。
            _title.value = loaded.title
            _body.value = loaded.body
            _createdAt.value = loaded.createdAt
            _bookId.value = loaded.bookId ?: -1L
            _loadState.value = LoadState.Ready
        }

        viewModelScope.launch {
            dirty.debounce(SAVE_DEBOUNCE_MS).collect { persist() }
        }
    }

    // ---------------------------------------------------------------- 编辑

    fun onTitleChange(value: String) {
        if (_loadState.value != LoadState.Ready) return
        if (_title.value == value) return
        _title.value = value
        markDirty()
    }

    fun onBodyChange(value: String) {
        if (_loadState.value != LoadState.Ready) return
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
            // 条目在编辑期间被删除。绝不能静默丢弃，
            // 否则用户会以为改动保存了，实际上整篇日记连同内容一起消失。
            _loadState.value = LoadState.Missing
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

    /** 修改创建时间。见 [EditorScreen] 的说明：列表顺序会随之变化。 */
    fun changeCreatedAt(millis: Long) {
        if (_loadState.value != LoadState.Ready) return
        _createdAt.value = millis
        viewModelScope.launch {
            val current = repo.findEntry(entryId) ?: return@launch
            repo.saveEntry(current.copy(createdAt = millis))
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
            append(_title.value.ifBlank { untitledPlaceholder })
            append('\n')
            append(TimeFormat.isoFromEpoch(_createdAt.value))
            append('\n')
            append('\n')
            append(body)
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

    /**
     * 保存状态。
     *
     * 注意「条目不存在」已由 [LoadState.Missing] 表达，此处不再重复一份 ——
     * 两套状态表示同一件事迟早会不同步。
     */
    enum class SaveState { SAVED, PENDING }
}

/** 供列表界面复用的摘要生成。 */
object EntrySummary {
    private val IMAGE_TOKEN = Regex("""!\[(?:img|time|loc)]\([^)]*\)""")

    fun of(entry: Entry, maxChars: Int = 80): String {
        val stripped = IMAGE_TOKEN.replace(entry.body, " ").replace('\n', ' ').trim()
        return if (stripped.length <= maxChars) stripped else stripped.take(maxChars) + "…"
    }
}
