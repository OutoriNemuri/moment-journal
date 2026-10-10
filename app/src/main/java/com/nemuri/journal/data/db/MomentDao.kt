package com.nemuri.journal.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MomentDao {

    // ---------- 日记 ----------

    /** 按日记本过滤；[bookId] 为 null 时返回全部。 */
    @Query("SELECT * FROM entries WHERE (:bookId IS NULL OR book_id = :bookId) ORDER BY created_at DESC")
    fun observeEntriesInBook(bookId: Long?): Flow<List<Entry>>

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

    // ---------- 日记本 ----------

    @Query("SELECT * FROM books ORDER BY sort_order ASC, created_at ASC")
    fun observeBooks(): Flow<List<Book>>

    @Insert
    suspend fun insertBook(book: Book): Long

    /** 删除日记本。日记通过外键 SET NULL 变为未分类，不会被删除。 */
    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteBook(id: Long)

    @Query("SELECT COUNT(*) FROM entries WHERE book_id = :bookId")
    suspend fun countEntriesInBook(bookId: Long): Int

    // ---------- 维护 ----------

    @Query("SELECT COUNT(*) FROM entries")
    suspend fun countEntries(): Int

    @Query("SELECT COUNT(*) FROM books")
    suspend fun countBooks(): Int
}
