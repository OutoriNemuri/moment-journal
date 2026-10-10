package com.nemuri.journal.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nemuri.journal.data.db.Entry
import com.nemuri.journal.data.db.EntryWithBookName
import com.nemuri.journal.data.repo.MomentRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 查找。
 *
 * ## 跨日记本查找不是恢复全局视图
 * 0.2 取消了「全部日记」入口，把日记本确立为容器。跨本查找与它并不冲突：
 * 查找是**以查询为驱动的临时视图**，结果是一次性列表，关闭即结束，
 * 不改变当前打开的日记本，也不提供可以常驻浏览的「所有日记」入口。
 *
 * ## 为什么用 LIKE 而不是 FTS
 * 日记规模是千条量级，`LIKE '%关键词%'` 在这个量级下毫秒级返回；
 * FTS 需要额外的虚拟表、分词配置和迁移负担。规模真的涨上去再引入不迟。
 */
class SearchViewModel(
    private val repo: MomentRepository,
) : ViewModel() {

    enum class Scope(val label: String) {
        CURRENT_ENTRY("本篇日记"),
        CURRENT_BOOK("本日记本"),
        ALL_BOOKS("所有日记本"),
    }

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _scope = MutableStateFlow(Scope.CURRENT_ENTRY)
    val scope: StateFlow<Scope> = _scope.asStateFlow()

    private val _results = MutableStateFlow<List<SearchHit>>(emptyList())
    val results: StateFlow<List<SearchHit>> = _results.asStateFlow()

    private val _searched = MutableStateFlow(false)
    val searched: StateFlow<Boolean> = _searched.asStateFlow()

    private var currentEntryId: Long = -1L
    private var currentBookId: Long = -1L

    /** 进入查找时告知上下文：正在编辑哪一篇、当前打开哪个日记本。 */
    fun prepare(entryId: Long, bookId: Long) {
        currentEntryId = entryId
        currentBookId = bookId
    }

    fun onQueryChange(value: String) {
        _query.value = value
        if (value.isBlank()) {
            _results.value = emptyList()
            _searched.value = false
        }
    }

    fun onScopeChange(value: Scope) {
        _scope.value = value
        if (_query.value.isNotBlank()) search()
    }

    fun search() {
        val q = _query.value.trim()
        if (q.isEmpty()) {
            _results.value = emptyList()
            _searched.value = false
            return
        }
        viewModelScope.launch {
            val hits = when (_scope.value) {
                // 本篇：只查当前正在编辑的这一条，不需要查库
                Scope.CURRENT_ENTRY -> {
                    val entry = repo.findEntry(currentEntryId)
                    if (entry != null && entry.matches(q)) {
                        // 当前编辑的日记已经属于某个本，bookId 理论上非空；
                        // 但实体类型仍是 Long?（非空约束推迟到 0.3），
                        // 这里用 -1 表示「尚未归属」，而不是硬转成非空。
                        listOf(SearchHit(entry, entry.bookId ?: -1L, null, snippet(entry, q)))
                    } else {
                        emptyList()
                    }
                }

                Scope.CURRENT_BOOK -> repo.searchInBook(currentBookId, q)
                    .map { row -> row.toHit(q) }

                Scope.ALL_BOOKS -> repo.searchAll(q)
                    .map { row -> row.toHit(q) }
            }
            _results.value = hits
            _searched.value = true
        }
    }

    companion object {
        /** 命中片段：关键词前后各取若干字，让用户看出命中在什么上下文里。 */
        fun snippet(entry: Entry, query: String, radius: Int = 24): String {
            val body = entry.body
            val at = body.indexOf(query, ignoreCase = true)
            if (at < 0) {
                // 命中在标题上
                return body.take(radius * 2).replace('\n', ' ')
            }
            val from = (at - radius).coerceAtLeast(0)
            val to = (at + query.length + radius).coerceAtMost(body.length)
            val prefix = if (from > 0) "…" else ""
            val suffix = if (to < body.length) "…" else ""
            return prefix + body.substring(from, to).replace('\n', ' ') + suffix
        }

        fun factory(repo: MomentRepository) = viewModelFactory {
            initializer { SearchViewModel(repo) }
        }
    }
}

/** 一条搜索结果。[bookName] 为 null 表示结果来自当前编辑的日记本身。 */
data class SearchHit(
    val entry: Entry,
    val bookId: Long,
    val bookName: String?,
    val snippet: String,
)

/** 标题或正文是否包含关键词。 */
private fun Entry.matches(query: String): Boolean =
    title.contains(query, ignoreCase = true) || body.contains(query, ignoreCase = true)

/**
 * 查库结果转为界面用的命中项。
 *
 * `Entry.bookId` 仍是 `Long?`（非空约束推迟到 0.3），而 [SearchHit.bookId]
 * 是非空 —— 用 -1 表示「尚未归属」，而不是 `!!` 硬转：
 * 强制解包在数据异常时会直接崩溃，而这里只用于展示，没有必要冒那个风险。
 */
private fun EntryWithBookName.toHit(query: String): SearchHit =
    SearchHit(
        entry = entry,
        bookId = entry.bookId ?: -1L,
        bookName = bookName,
        snippet = SearchViewModel.snippet(entry, query),
    )
