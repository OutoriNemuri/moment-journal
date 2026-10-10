package com.nemuri.journal.ui.screen

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nemuri.journal.data.db.Entry
import com.nemuri.journal.data.repo.MomentRepository
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
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
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    companion object {
        /** 写库防抖窗口。太小则退化成逐键写入，太大则丢字窗口变长。 */
        const val SAVE_DEBOUNCE_MS = 400L
        const val ARG_ENTRY_ID = "entryId"
    }

    private val entryId: Long = savedStateHandle.get<Long>(ARG_ENTRY_ID) ?: -1L

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    private val _body = MutableStateFlow("")
    val body: StateFlow<String> = _body.asStateFlow()

    private val _createdAt = MutableStateFlow(0L)
    val createdAt: StateFlow<Long> = _createdAt.asStateFlow()

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
            val loaded = repo.findEntry(entryId) ?: return@launch
            _title.value = loaded.title
            _body.value = loaded.body
            _createdAt.value = loaded.createdAt
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
        val current = repo.findEntry(entryId) ?: return
        repo.saveEntry(current.copy(title = draft.title, body = draft.body))
        // 只有在此期间没有新改动时才清空待写内容，否则会把新输入一起丢掉
        if (pending == draft) {
            pending = null
            _saveState.value = SaveState.SAVED
        }
    }

    private data class Draft(val title: String, val body: String)

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
