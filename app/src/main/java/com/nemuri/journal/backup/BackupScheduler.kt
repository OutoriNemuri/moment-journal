package com.nemuri.journal.backup

import android.content.Context
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * 备份触发点。
 *
 * 本版刻意**不做**精确定时备份。Android 的 Doze 与后台限制会压制周期任务，
 * 承诺"每 5 分钟"而实际做不到，只会让用户误以为数据安全。
 *
 * 实际策略：
 * 1. **冷启动完成后** —— 用户每次打开应用都可能换机/丢机，这个点最便宜也最有效。
 * 2. **应用退到后台（onStop）** —— 会话结束时收口。
 * 3. WorkManager 兜底，最小间隔 15 分钟（系统允许的下限）。
 */
object BackupScheduler {

    private const val PREFS = "backup_prefs"
    private const val KEY_TREE_URI = "backup_tree_uri"
    private const val KEY_LAST_AT = "last_backup_at"
    private const val KEY_LAST_ERROR = "last_backup_error"

    /** 用户是否已指定备份目录。未指定时不备份，也不打扰用户。 */
    fun isConfigured(context: Context): Boolean =
        prefs(context).getString(KEY_TREE_URI, null) != null

    fun backupTreeUri(context: Context): String? =
        prefs(context).getString(KEY_TREE_URI, null)

    fun setBackupTreeUri(context: Context, uri: String?) {
        prefs(context).edit {
            if (uri == null) remove(KEY_TREE_URI) else putString(KEY_TREE_URI, uri)
        }
    }

    fun lastBackupAt(context: Context): Long = prefs(context).getLong(KEY_LAST_AT, 0L)

    fun lastError(context: Context): String? = prefs(context).getString(KEY_LAST_ERROR, null)

    internal fun recordSuccess(context: Context) {
        prefs(context).edit {
            putLong(KEY_LAST_AT, System.currentTimeMillis())
            remove(KEY_LAST_ERROR)
        }
    }

    internal fun recordFailure(context: Context, message: String) {
        prefs(context).edit { putString(KEY_LAST_ERROR, message) }
    }

    /** 冷启动完成后调用。 */
    fun onColdStart(context: Context) {
        enqueueNow(context)
        schedulePeriodicFloor(context)
    }

    /** 退到后台时调用（见 MainActivity.onStop）。 */
    fun onBackground(context: Context) {
        enqueueNow(context)
    }

    private fun enqueueNow(context: Context) {
        if (!isConfigured(context)) return
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    private fun schedulePeriodicFloor(context: Context) {
        if (!isConfigured(context)) return
        val request = PeriodicWorkRequestBuilder<BackupWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME_PERIODIC,
            // 周期任务用的是 ExistingPeriodicWorkPolicy，与一次性任务的
            // ExistingWorkPolicy 是两个不同的枚举，不能混用。
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * 所选目录是否仍可访问。
     * 用户可能删掉 SD 卡、撤销授权或清理目录，备份前必须确认。
     */
    fun resolveBackupDir(context: Context): DocumentFile? {
        val raw = backupTreeUri(context) ?: return null
        val uri = android.net.Uri.parse(raw)
        val root = DocumentFile.fromTreeUri(context, uri) ?: return null
        if (!root.canWrite()) return null
        val appDir = root.findFile(MjbBackupManager.BACKUP_DIR_NAME)
            ?: root.createDirectory(MjbBackupManager.BACKUP_DIR_NAME) ?: return null
        return appDir.findFile(MjbBackupManager.BACKUP_SUBDIR)
            ?: appDir.createDirectory(MjbBackupManager.BACKUP_SUBDIR)
    }

    /** 用户手动触发"立即备份"，绕过 WorkManager 直接执行，便于立即反馈结果。 */
    suspend fun backupNow(context: Context): BackupResult {
        val raw = backupTreeUri(context) ?: throw IllegalStateException("尚未指定备份目录")
        return try {
            val result = MjbBackupManager(context).backup(android.net.Uri.parse(raw))
            recordSuccess(context)
            result
        } catch (t: Throwable) {
            recordFailure(context, t.message ?: t.javaClass.simpleName)
            throw t
        }
    }

    private const val WORK_NAME = "mjb-backup-now"
    private const val WORK_NAME_PERIODIC = "mjb-backup-periodic"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

class BackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!BackupScheduler.isConfigured(applicationContext)) return@withContext Result.success()
        if (BackupScheduler.resolveBackupDir(applicationContext) == null) {
            BackupScheduler.recordFailure(applicationContext, "备份目录不可访问")
            return@withContext Result.retry()
        }
        try {
            BackupScheduler.backupNow(applicationContext)
            Result.success()
        } catch (t: Throwable) {
            BackupScheduler.recordFailure(applicationContext, t.message ?: "备份失败")
            // 目录问题重试无意义，网络/存储暂时不可用则可以
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
