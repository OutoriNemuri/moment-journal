package com.nemuri.journal.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nemuri.journal.data.db.Book
import com.nemuri.journal.data.db.Entry
import com.nemuri.journal.data.repo.MomentRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 当前选中的日记本。null = 全部日记。 */
class HomeViewModel(private val repo: MomentRepository) : ViewModel() {

    private val _selectedBookId = MutableStateFlow<Long?>(null)
    val selectedBookId: StateFlow<Long?> = _selectedBookId.asStateFlow()

    val books: StateFlow<List<Book>> = repo.observeBooks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @Suppress("OPT_IN_USAGE")
    val entries: StateFlow<List<Entry>> = combine(
        repo.observeEntries(),
        _selectedBookId,
    ) { all, bookId ->
        if (bookId == null) all else all.filter { it.bookId == bookId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _drawerOpen = MutableStateFlow(false)
    val drawerOpen: StateFlow<Boolean> = _drawerOpen.asStateFlow()

    fun openDrawer() { _drawerOpen.value = true }
    fun closeDrawer() { _drawerOpen.value = false }

    fun selectBook(id: Long?) {
        _selectedBookId.value = id
        _drawerOpen.value = false
    }

    fun createEntry(onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            onCreated(repo.createEntry(bookId = _selectedBookId.value))
        }
    }

    fun deleteEntry(id: Long) {
        viewModelScope.launch { repo.deleteEntry(id) }
    }

    fun createBook(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { repo.createBook(trimmed) }
    }

    /**
     * 删除日记本。日记本身不删 —— 数据库外键为 SET NULL，
     * 其中的日记会退回"未分类"。这符合直觉：删文件夹不该删文件。
     */
    fun deleteBook(id: Long) {
        viewModelScope.launch {
            repo.deleteBook(id)
            if (_selectedBookId.value == id) _selectedBookId.value = null
        }
    }

    companion object {
        fun factory(repo: MomentRepository) = viewModelFactory {
            initializer { HomeViewModel(repo) }
        }
    }
}
