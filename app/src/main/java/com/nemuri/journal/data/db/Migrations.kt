package com.nemuri.journal.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 迁移 1 → 2：为「日记本成为容器」铺路。
 *
 * ## 目标
 * 让每篇日记都有归属，且保证至少存在一个日记本。
 *
 * ## 为什么不在这一版加非空约束
 * 加约束是破坏性操作，而它依赖两个尚未验证的前提：`.mjb` 恢复流程没在真实数据上
 * 跑过，用户的历史数据也没有样本。所以拆开：本迁移只回填数据（非破坏、可重跑），
 * 非空约束与外键改级联推迟到 0.3。
 *
 * ## 幂等性
 * 可以重复执行：第二次运行时既没有日记本需要创建，也没有空值需要回填，
 * 整个迁移成为空操作。这很重要 —— 迁移失败后用户会在下次启动时重跑它。
 *
 * ## 外键保持 SET NULL
 * 因此本版删除日记本时，其中的日记会退回 `book_id = null`。
 * 这是过渡期的已知不一致，由上层处理（删除日记本前先把日记迁入默认本），
 * 不在数据库层强行制造约束。
 */
/** 默认日记本的名字。首启创建、以及迁移回填时使用。 */
const val DEFAULT_BOOK_NAME = "我的日记"

val MIGRATION_1_2 = object : Migration(1, 2) {

    override fun migrate(db: SupportSQLiteDatabase) {
        val now = System.currentTimeMillis()

        // 1) 确保至少有一个日记本。
        //    不能依赖 AUTOINCREMENT 的具体取值，所以插入后按 rowid 回查。
        db.execSQL(
            """
            INSERT INTO books (name, sort_order, created_at)
            SELECT ?, 0, ?
            WHERE NOT EXISTS (SELECT 1 FROM books)
            """.trimIndent(),
            arrayOf(DEFAULT_BOOK_NAME, now),
        )

        val defaultBookId = db.query("SELECT MIN(id) FROM books").use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        }
        if (defaultBookId <= 0L) {
            // books 表存在却查不到 id，说明数据已损坏。中止迁移而不是猜。
            error("迁移 1→2 失败：无法确定默认日记本 id")
        }

        // 2) 回填空值。已归属的日记不动。
        db.execSQL(
            "UPDATE entries SET book_id = ? WHERE book_id IS NULL",
            arrayOf(defaultBookId),
        )
    }

}
