package com.nemuri.journal.data.repo

import com.nemuri.journal.data.db.Book
import com.nemuri.journal.data.db.Entry
import com.nemuri.journal.data.db.MomentDao
import kotlinx.coroutines.flow.Flow

/**
 * 数据入口。UI 只与它对话，不直接触碰 DAO。
 *
 * 本类不做业务缓存：SQLite + WAL 下按 id 读单条足够快，
 * 缓存只会引入"内存里比库里新"的时序问题。
 */
class MomentRepository(private val dao: MomentDao) {

    fun observeEntries(): Flow<List<Entry>> = dao.observeEntries()

    fun observeEntriesInBook(bookId: Long?): Flow<List<Entry>> = dao.observeEntriesInBook(bookId)

    fun observeEntry(id: Long): Flow<Entry?> = dao.observeEntry(id)

    fun observeBooks(): Flow<List<Book>> = dao.observeBooks()

    suspend fun findEntry(id: Long): Entry? = dao.findEntry(id)

    /** 新建一篇日记并立即落库（"一旦修改即保存"的第一个落点）。 */
    suspend fun createEntry(bookId: Long? = null, now: Long = System.currentTimeMillis()): Long =
        dao.insertEntry(
            Entry(bookId = bookId, title = "", body = "", createdAt = now, updatedAt = now)
        )

    /**
     * 写回一条日记。
     *
     * 调用方需自行做输入防抖（见 [com.nemuri.journal.ui.screen.EditorViewModel]），
     * 避免逐按键 UPDATE。
     */
    suspend fun saveEntry(entry: Entry, now: Long = System.currentTimeMillis()) {
        dao.updateEntry(entry.copy(updatedAt = now))
    }

    suspend fun deleteEntry(id: Long) = dao.deleteEntry(id)

    suspend fun createBook(name: String, now: Long = System.currentTimeMillis()): Long =
        dao.insertBook(Book(name = name, createdAt = now))

    suspend fun deleteBook(id: Long) = dao.deleteBook(id)

    suspend fun countEntries(): Int = dao.countEntries()
}
