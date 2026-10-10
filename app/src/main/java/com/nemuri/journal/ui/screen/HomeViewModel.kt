package com.nemuri.journal.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nemuri.journal.data.db.Book
import com.nemuri.journal.data.db.Entry
import com.nemuri.journal.data.prefs.JournalPrefs
import com.nemuri.journal.data.repo.MomentRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 主界面状态。
 *
 * ## 0.2 的信息架构
 * 日记本是**容器**而不是分类标签：
 * - 主界面 = 当前打开的日记本中的日记
 * - 侧栏上半段 = 同一个本的日记目录
 * - 侧栏下半段 = 日记本列表
 * - 没有当前日记本时**不能新建日记**
 *
 * ## 当前日记本从哪来
 * 偏好里记住上次打开的本；该本可能已被删除，所以启动时要做一次校验并回退。
 * 见 [resolveInitialBook]。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val repo: MomentRepository,
    private val prefs: JournalPrefs,
) : ViewModel() {

    /** 当前打开的日记本 id；[JournalPrefs.NO_BOOK] 表示尚未确定或一个都没有。 */
    private val _currentBookId = MutableStateFlow(prefs.currentBookId)
    val currentBookId: StateFlow<Long> = _currentBookId.asStateFlow()

    val books: StateFlow<List<Book>> = repo.observeBooks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 当前日记本本身，用于显示标题与删除确认文案。 */
    val currentBook: StateFlow<Book?> = _currentBookId
        .flatMapLatest { id -> if (id == JournalPrefs.NO_BOOK) flowOf(null) else repo.observeBook(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 当前日记本的日记。没有当前本时为空列表，而不是「全部日记」。 */
    val entries: StateFlow<List<Entry>> = _currentBookId
        .flatMapLatest { id ->
            if (id == JournalPrefs.NO_BOOK) flowOf(emptyList())
            else repo.observeEntriesInBook(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 是否允许新建日记。
     *
     * 用户明确要求：未打开日记本时不能新建。这里作为单一判据，
     * UI 据此禁用按钮并显示原因，而不是点击后无声失败。
     */
    val canCreateEntry: StateFlow<Boolean> = _currentBookId
        .map { it != JournalPrefs.NO_BOOK }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        viewModelScope.launch { resolveInitialBook() }
    }

    /**
     * 确定启动后打开哪个日记本。
     *
     * 规则：偏好中的 id 若仍存在就用它；否则退到最早创建的一个；
     * 一个日记本都没有时创建一个。任何一步失败都不应让应用停在「无本」状态 ——
     * 那会让新建按钮永久禁用，用户除了手动建本无路可走。
     */
    private suspend fun resolveInitialBook() {
        val remembered = prefs.currentBookId
        if (remembered != JournalPrefs.NO_BOOK && repo.findBook(remembered) != null) {
            _currentBookId.value = remembered
            return
        }

        // 偏好失效（首次启动，或记住的本已被删除）
        val fallback = books.value.firstOrNull()?.id
            ?: repo.ensureAtLeastOneBook()
        selectBook(fallback)
    }

    fun selectBook(id: Long) {
        if (id == JournalPrefs.NO_BOOK) return
        _currentBookId.value = id
        prefs.currentBookId = id
    }

    /** 新建日记。返回新条目的 id 供导航；没有当前本时不做事。 */
    fun createEntry(onCreated: (Long) -> Unit) {
        val bookId = _currentBookId.value
        if (bookId == JournalPrefs.NO_BOOK) return
        viewModelScope.launch { onCreated(repo.createEntry(bookId)) }
    }

    fun deleteEntry(id: Long) {
        viewModelScope.launch { repo.deleteEntry(id) }
    }

    /** 某本内的日记条数。删除确认框需要它来写明「将移走多少篇」。 */
    suspend fun countEntriesInBook(bookId: Long): Int = repo.countEntriesInBook(bookId)

    fun createBook(name: String, onCreated: ((Long) -> Unit)? = null) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val id = repo.createBook(trimmed)
            selectBook(id)
            onCreated?.invoke(id)
        }
    }

    /**
     * 删除日记本。
     *
     * 其中的日记会被迁入 [fallbackBookId]（调用方负责挑选，通常是默认本），
     * 而不是随之删除 —— 0.2 的外键仍是 `SET NULL`，不搬迁就会产生看不见的孤儿日记。
     * 若删除的正是当前本，则切换到 [fallbackBookId]。
     */
    fun deleteBook(id: Long, fallbackBookId: Long) {
        if (id == fallbackBookId) return
        viewModelScope.launch {
            repo.deleteBookMovingEntries(id, fallbackBookId)
            if (_currentBookId.value == id) selectBook(fallbackBookId)
        }
    }

    /** 挑选可作为删除日记本时的接收方：优先默认本，其次任意其他的本。 */
    fun fallbackBookId(excluding: Long): Long? =
        books.value.firstOrNull { it.id != excluding }?.id

    companion object {
        fun factory(repo: MomentRepository, prefs: JournalPrefs) = viewModelFactory {
            initializer { HomeViewModel(repo, prefs) }
        }
    }
}
