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

    /** 用户是否已在首启引导中做过选择（无论选「指定目录」还是「稍后再说」）。 */
    var onboardingDone: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING, false)
        set(value) = prefs.edit { putBoolean(KEY_ONBOARDING, value) }

    /** 备份是否已配置可用。 */
    val backupConfigured: Boolean
        get() = backupTreeUri != null

    companion object {
        private const val NAME = "journal_prefs"
        private const val KEY_CURRENT_BOOK = "current_book_id"
        private const val KEY_BACKUP_URI = "backup_tree_uri"
        private const val KEY_LAST_BACKUP_AT = "last_backup_at"
        private const val KEY_LAST_BACKUP_ERROR = "last_backup_error"
        private const val KEY_ONBOARDING = "onboarding_done"

        const val NO_BOOK = -1L
    }
}
