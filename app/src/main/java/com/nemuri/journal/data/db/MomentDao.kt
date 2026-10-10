package com.nemuri.journal.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MomentDao {

    // ---------- 日记 ----------

    /**
     * 某个日记本内的日记，时间倒序。
     *
     * 0.2 起日记本即容器，[bookId] 不再接受 null：主界面永远属于某一个日记本。
     */
    @Query("SELECT * FROM entries WHERE book_id = :bookId ORDER BY created_at DESC")
    fun observeEntriesInBook(bookId: Long): Flow<List<Entry>>

    @Query("SELECT * FROM entries WHERE id = :id")
    fun observeEntry(id: Long): Flow<Entry?>

    @Query("SELECT * FROM entries WHERE id = :id")
    suspend fun findEntry(id: Long): Entry?

    @Insert
    suspend fun insertEntry(entry: Entry): Long

    @Update
    suspend fun updateEntry(entry: Entry)

    @Query("DELETE FROM entries WHERE id = :id")
    suspend fun deleteEntry(id: Long)

    /** 把某个日记本的全部日记迁到另一个本。用于删除日记本之前。 */
    @Query("UPDATE entries SET book_id = :toBookId WHERE book_id = :fromBookId")
    suspend fun moveEntries(fromBookId: Long, toBookId: Long)

    // ---------- 查找 ----------

    /**
     * 在指定日记本内查找。
     *
     * 用 LIKE 而非 FTS：日记规模是千条量级，LIKE 在这个量级下毫秒级返回，
     * 而 FTS 需要额外的虚拟表、分词配置与迁移负担。
     * `LIKE` 对 ASCII 大小写不敏感，但**对非 ASCII（中文）本就是子串匹配**，
     * 因此中日文的关键词同样能命中。
     */
    @Query(
        """
        SELECT e.*, b.name AS book_name FROM entries e
        LEFT JOIN books b ON b.id = e.book_id
        WHERE e.book_id = :bookId
          AND (e.title LIKE '%' || :query || '%' OR e.body LIKE '%' || :query || '%')
        ORDER BY e.created_at DESC
        """
    )
    suspend fun searchInBook(bookId: Long, query: String): List<EntryWithBookName>

    /** 跨全部日记本查找。结果带上所属日记本名，便于用户分辨来源。 */
    @Query(
        """
        SELECT e.*, b.name AS book_name FROM entries e
        LEFT JOIN books b ON b.id = e.book_id
        WHERE e.title LIKE '%' || :query || '%' OR e.body LIKE '%' || :query || '%'
        ORDER BY e.created_at DESC
        """
    )
    suspend fun searchAll(query: String): List<EntryWithBookName>

    // ---------- 日记本 ----------

    @Query("SELECT * FROM books ORDER BY sort_order ASC, created_at ASC")
    fun observeBooks(): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE id = :id")
    fun observeBook(id: Long): Flow<Book?>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun findBook(id: Long): Book?

    @Query("SELECT * FROM books ORDER BY created_at ASC LIMIT 1")
    suspend fun firstBook(): Book?

    @Insert
    suspend fun insertBook(book: Book): Long

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteBook(id: Long)

    @Query("SELECT COUNT(*) FROM entries WHERE book_id = :bookId")
    suspend fun countEntriesInBook(bookId: Long): Int

    // ---------- 维护 ----------

    @Query("SELECT COUNT(*) FROM entries")
    suspend fun countEntries(): Int

    @Query("SELECT COUNT(*) FROM books")
    suspend fun countBooks(): Int

    /**
     * 删除一个日记本，并把它的日记迁往 [fallbackBookId]。
     *
     * 这一步是必需的，不是防御性代码：0.2 的外键仍是 `SET NULL`（非空约束推迟到 0.3），
     * 直接删本会让其中的日记变成无归属 —— 而 0.2 的界面已经不再有「全部日记」入口，
     * 那些日记会彻底看不见。所以删本之前必须先搬走日记。
     *
     * 整个操作在一个事务内，避免出现「日记搬走了但本还在」的中间状态。
     */
    @Transaction
    suspend fun deleteBookMovingEntriesTo(bookId: Long, fallbackBookId: Long) {
        require(bookId != fallbackBookId) { "不能把日记迁往正在删除的日记本" }
        moveEntries(fromBookId = bookId, toBookId = fallbackBookId)
        deleteBook(bookId)
    }

    /** 确保至少存在一个日记本，返回其 id。为空则创建一个。 */
    @Transaction
    suspend fun ensureAtLeastOneBook(name: String, now: Long): Long {
        if (countBooks() > 0) {
            val existing = firstBook()
            if (existing != null) return existing.id
        }
        return insertBook(Book(name = name, createdAt = now))
    }
}
