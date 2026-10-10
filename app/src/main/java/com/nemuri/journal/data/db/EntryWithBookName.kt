package com.nemuri.journal.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded

/**
 * 查找结果：日记本体 + 它所属的日记本名。
 *
 * 单独定义而不是给 [Entry] 加字段：`book_name` 来自 JOIN，不属于 entries 表，
 * 混进实体会让 Room 的 schema 多出一个并不存在的列。
 *
 * [bookName] 显式标注列名，因为 SQL 里的别名是 `book_name`。
 * 依赖 Room 的自动驼峰转换不可靠 —— 显式写出来，改列名时编译器会报错。
 * 左侧用 LEFT JOIN，日记本被删且日记尚未迁移时该列为 null，因此类型可空。
 */
data class EntryWithBookName(
    @Embedded val entry: Entry,
    @ColumnInfo(name = "book_name") val bookName: String?,
)
