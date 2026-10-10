package com.nemuri.journal.data.repo

import com.nemuri.journal.data.db.Book
import com.nemuri.journal.data.db.Entry
import com.nemuri.journal.data.db.MomentDao
import com.nemuri.journal.data.db.DEFAULT_BOOK_NAME
import kotlinx.coroutines.flow.Flow

/**
 * 数据入口。UI 只与它对话，不直接触碰 DAO。
 *
 * 本类不做业务缓存：SQLite + WAL 下按 id 读单条足够快，
 * 缓存只会引入「内存里比库里新」的时序问题。
 */
class MomentRepository(private val dao: MomentDao) {

    // ---------------------------------------------------------------- 日记

    /** 某个日记本内的日记。0.2 起日记本即容器，不存在「全部日记」的视图。 */
    fun observeEntriesInBook(bookId: Long): Flow<List<Entry>> =
        dao.observeEntriesInBook(bookId)

    fun observeEntry(id: Long): Flow<Entry?> = dao.observeEntry(id)

    suspend fun findEntry(id: Long): Entry? = dao.findEntry(id)

    /**
     * 在指定日记本中新建一篇日记。
     *
     * [bookId] 故意是非空类型：0.2 的界面禁止在没有当前日记本时新建，
     * 类型层面直接堵死「无归属日记」的入口。
     */
    suspend fun createEntry(
        bookId: Long,
        now: Long = System.currentTimeMillis(),
    ): Long = dao.insertEntry(
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

    // ---------------------------------------------------------------- 日记本

    fun observeBooks(): Flow<List<Book>> = dao.observeBooks()

    fun observeBook(id: Long): Flow<Book?> = dao.observeBook(id)

    suspend fun findBook(id: Long): Book? = dao.findBook(id)

    suspend fun createBook(name: String, now: Long = System.currentTimeMillis()): Long =
        dao.insertBook(Book(name = name, createdAt = now))

    /**
     * 确保至少有一个日记本，返回其 id。
     * 首启、以及迁移后的兜底都会用到。
     */
    suspend fun ensureAtLeastOneBook(
        name: String = DEFAULT_BOOK_NAME,
        now: Long = System.currentTimeMillis(),
    ): Long = dao.ensureAtLeastOneBook(name, now)

    /**
     * 删除日记本，并把其中的日记迁入 [fallbackBookId]。
     *
     * 0.2 的外键仍是 `SET NULL`，直接删本会产生无归属的日记；
     * 而 0.2 的界面已经取消了「全部日记」入口，那些日记会彻底看不见。
     * 所以删除动作必须连同搬迁一起做，见 [MomentDao.deleteBookMovingEntriesTo]。
     */
    suspend fun deleteBookMovingEntries(bookId: Long, fallbackBookId: Long) =
        dao.deleteBookMovingEntriesTo(bookId, fallbackBookId)

    suspend fun countEntriesInBook(bookId: Long): Int = dao.countEntriesInBook(bookId)

    suspend fun countBooks(): Int = dao.countBooks()

    // ---------------------------------------------------------------- 维护

    suspend fun countEntries(): Int = dao.countEntries()
}
