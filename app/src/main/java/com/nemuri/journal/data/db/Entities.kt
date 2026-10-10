package com.nemuri.journal.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 日记本。用于按年份、地区等维度分类日记。
 *
 * [id] 从 0.1 第一版起就写进 schema，之后不再变更。
 */
@Entity(tableName = "books")
data class Book(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * 一条日记。
 *
 * [body] 存的是 **隐式扩展 Markdown 源文本**，是正文的唯一真相：
 * 样式与内联嵌入（时间 / 地点 / 图片）全部编码在文本里，不另存富文本树。
 * 具体语法见 [com.nemuri.journal.md.MomentMarkdown]。
 *
 * [bookId] 为 null 表示"未分类"，即不属于任何日记本。
 * 外键使用 SET NULL：删除日记本不应连带删除日记。
 */
@Entity(
    tableName = "entries",
    foreignKeys = [
        ForeignKey(
            entity = Book::class,
            parentColumns = ["id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("book_id"), Index("created_at")],
)
data class Entry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "book_id") val bookId: Long? = null,
    @ColumnInfo(name = "title") val title: String = "",
    @ColumnInfo(name = "body") val body: String = "",
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
