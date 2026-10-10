package com.nemuri.journal.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File

@Database(
    entities = [Book::class, Entry::class],
    version = 1,
    exportSchema = true,
)
abstract class MomentDatabase : RoomDatabase() {

    abstract fun dao(): MomentDao

    companion object {
        const val DB_NAME = "journal.db"

        @Volatile
        private var instance: MomentDatabase? = null

        fun get(context: Context): MomentDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): MomentDatabase {
            // 备份恢复时数据库文件被整体替换，需要显式清理缓存，见 BackupManager。
            return Room.databaseBuilder(context, MomentDatabase::class.java, DB_NAME)
                .addMigrations(*MIGRATIONS)
                // WAL 已由 Room 默认开启；这里不启用 destructive migration，
                // 任何 schema 变更都必须显式提供 Migration。
                .build()
        }

        private val MIGRATIONS: Array<Migration> = arrayOf()

        /** 供恢复流程使用：关闭单例并释放文件句柄。 */
        fun closeForRestore() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }

        /** 数据库在磁盘上的绝对路径。 */
        fun fileFor(context: Context): File =
            context.getDatabasePath(DB_NAME)

        /** WAL 与 SHM 附属文件路径，备份打包时不能遗漏/残留。 */
        fun auxFilesFor(context: Context): List<File> =
            listOf(File(fileFor(context).path + "-wal"), File(fileFor(context).path + "-shm"))
    }
}
