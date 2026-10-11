package com.nemuri.journal.data.prefs

import android.content.Context
import androidx.core.content.edit

/**
 * 应用偏好。
 *
 * 目前只存两类东西：**当前打开的日记本** 与 **备份目录授权**。
 * 二者都是「上次会话留下的、下次启动要接着用」的状态，且都不属于日记数据本身，
 * 所以不放进数据库 —— 数据库只承载用户的日记，偏好丢了不该影响日记可读性。
 */
class JournalPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(NAME, Context.MODE_PRIVATE)

    // ---------------------------------------------------------- 当前日记本

    /**
     * 上次打开的日记本 id。
     *
     * -1 表示从未选择过。用 -1 而不是 0，是因为 Room 的自增主键从 1 开始，
     * 0 会与「未初始化」和「不存在的 id」混淆。
     */
    var currentBookId: Long
        get() = prefs.getLong(KEY_CURRENT_BOOK, NO_BOOK)
        set(value) = prefs.edit { putLong(KEY_CURRENT_BOOK, value) }

    fun clearCurrentBook() = prefs.edit { remove(KEY_CURRENT_BOOK) }

    // ---------------------------------------------------------- 备份目录

    /**
     * 备份根目录的 SAF 授权 URI。
     *
     * 存的是用户通过文件框架选定的那个目录（企划中的 `%i`），
     * 归档落在其下的 `MomentJournal/Backups`。
     */
    var backupTreeUri: String?
        get() = prefs.getString(KEY_BACKUP_URI, null)
        set(value) = prefs.edit {
            if (value == null) remove(KEY_BACKUP_URI) else putString(KEY_BACKUP_URI, value)
        }

    /** 上次成功备份的时刻，0 表示从未成功过。 */
    var lastBackupAt: Long
        get() = prefs.getLong(KEY_LAST_BACKUP_AT, 0L)
        set(value) = prefs.edit { putLong(KEY_LAST_BACKUP_AT, value) }

    /** 上次失败原因，null 表示上次是成功的（或从未尝试过）。 */
    var lastBackupError: String?
        get() = prefs.getString(KEY_LAST_BACKUP_ERROR, null)
        set(value) = prefs.edit {
            if (value == null) remove(KEY_LAST_BACKUP_ERROR) else putString(KEY_LAST_BACKUP_ERROR, value)
        }

    /**
     * 备份是否**已配置**。
     *
     * 注意这只是"用户指定过位置"，**不代表该位置当前可用** ——
     * 用户可能撤销授权、删除文件夹或卸载存储卡。
     * 「是否可用」需要实际访问目录来判断，见
     * [com.nemuri.journal.backup.BackupScheduler.isDirectoryUsable]。
     *
     * 曾经用一个 `onboardingDone` 标记记录"用户是否做过选择"，
     * 它与这个判断表达的是同一件事却存了两份，已删除。
     */
    val backupConfigured: Boolean
        get() = backupTreeUri != null

    // ---------------------------------------------------------- 主题

    /**
     * 配色方案。
     *
     * 存的是枚举**名字**而不是序号：序号会随枚举顺序变化而失效，
     * 用户升级后主题可能悄悄变成另一个。
     */
    var colorScheme: String
        get() = prefs.getString(KEY_COLOR_SCHEME, DEFAULT_COLOR_SCHEME) ?: DEFAULT_COLOR_SCHEME
        set(value) = prefs.edit { putString(KEY_COLOR_SCHEME, value) }

    /** 深浅模式。同样存名字。 */
    var darkMode: String
        get() = prefs.getString(KEY_DARK_MODE, DEFAULT_DARK_MODE) ?: DEFAULT_DARK_MODE
        set(value) = prefs.edit { putString(KEY_DARK_MODE, value) }

    companion object {
        private const val NAME = "journal_prefs"
        private const val KEY_COLOR_SCHEME = "color_scheme"
        private const val KEY_DARK_MODE = "dark_mode"

        /** 默认品牌绿。低版本没有动态取色，必须有可用的默认值。 */
        const val DEFAULT_COLOR_SCHEME = "GREEN"
        /** 默认跟随系统。 */
        const val DEFAULT_DARK_MODE = "SYSTEM"
        private const val KEY_CURRENT_BOOK = "current_book_id"
        private const val KEY_BACKUP_URI = "backup_tree_uri"
        private const val KEY_LAST_BACKUP_AT = "last_backup_at"
        private const val KEY_LAST_BACKUP_ERROR = "last_backup_error"

        const val NO_BOOK = -1L
    }
}
