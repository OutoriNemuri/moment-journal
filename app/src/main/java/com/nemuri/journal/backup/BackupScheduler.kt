package com.nemuri.journal.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nemuri.journal.data.prefs.JournalPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * 备份的触发与调度。
 *
 * ## 刻意不做精确定时备份
 * Android 的 Doze 与后台限制会压制周期任务。承诺「每 5 分钟」而实际做不到，
 * 只会让用户误以为数据安全 —— 这比不承诺更糟。
 *
 * 实际策略：
 * 1. **冷启动完成后** —— 用户每次打开应用都可能换机/丢机，这个点最便宜也最有效。
 * 2. **应用退到后台（onStop）** —— 会话结束时收口。
 * 3. WorkManager 兜底，最小间隔 15 分钟（系统允许的下限）。
 *
 * ## 状态存放
 * 备份相关的偏好全部存在 [JournalPrefs] 里，本类不再自己开一份
 * SharedPreferences —— 同一份状态有两个存储位置，迟早会不同步。
 */
class BackupScheduler(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = JournalPrefs(appContext)

    /** 是否已指定备份目录。未指定时不备份，也不调度。 */
    fun isConfigured(): Boolean = prefs.backupConfigured

    val backupTreeUri: String? get() = prefs.backupTreeUri
    val lastBackupAt: Long get() = prefs.lastBackupAt
    val lastError: String? get() = prefs.lastBackupError

    // ---------------------------------------------------------------- 调度

    /** 冷启动完成后调用。 */
    fun onColdStart() {
        if (!isConfigured()) return
        enqueueNow()
        schedulePeriodicFloor()
    }

    /** 退到后台时调用（见 MainActivity.onStop）。 */
    fun onBackground() {
        if (!isConfigured()) return
        enqueueNow()
    }

    private fun enqueueNow() {
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(appContext)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    private fun schedulePeriodicFloor() {
        val request = PeriodicWorkRequestBuilder<BackupWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
            WORK_NAME_PERIODIC,
            // 周期任务用的是 ExistingPeriodicWorkPolicy，与一次性任务的
            // ExistingWorkPolicy 是两个不同的枚举，不能混用。
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    // ---------------------------------------------------------------- 执行

    /**
     * 所选目录当前是否仍可用。
     *
     * 用户可能删除文件夹、卸载存储卡或撤销授权，因此每次使用前重新校验，
     * 不缓存「可用」这个判断。
     */
    suspend fun isDirectoryUsable(): Boolean = withContext(Dispatchers.IO) {
        resolveBackupDir() != null
    }

    private fun resolveBackupDir(): DocumentFile? {
        val raw = prefs.backupTreeUri ?: return null
        val root = DocumentFile.fromTreeUri(appContext, Uri.parse(raw)) ?: return null
        if (!root.canWrite()) return null
        val appDir = root.findFile(MjbBackupManager.BACKUP_DIR_NAME)
            ?: root.createDirectory(MjbBackupManager.BACKUP_DIR_NAME) ?: return null
        return appDir.findFile(MjbBackupManager.BACKUP_SUBDIR)
            ?: appDir.createDirectory(MjbBackupManager.BACKUP_SUBDIR)
    }

    /**
     * 立即备份。绕过 WorkManager 直接执行，便于向用户即时反馈结果。
     * 失败会抛出，且已记录原因 —— 调用方必须处理，不允许静默。
     */
    suspend fun backupNow(): BackupResult {
        val raw = prefs.backupTreeUri
            ?: throw IllegalStateException("尚未指定备份目录")
        return try {
            val result = MjbBackupManager(appContext).backup(Uri.parse(raw))
            prefs.lastBackupAt = System.currentTimeMillis()
            prefs.lastBackupError = null
            result
        } catch (t: Throwable) {
            prefs.lastBackupError = t.message ?: t.javaClass.simpleName
            throw t
        }
    }

    /**
     * 列出已授权备份目录中的归档。
     * 目录未配置时返回空列表 —— 此时用户应从别处选择归档。
     */
    suspend fun listArchives(limit: Int = 20): List<MjbBackupManager.ArchiveEntry> {
        val raw = prefs.backupTreeUri ?: return emptyList()
        return MjbBackupManager(appContext).listArchives(Uri.parse(raw), limit)
    }

    /** 从归档恢复。破坏性操作，调用方必须已获得用户确认。 */
    suspend fun restore(archive: Uri): RestoreResult = withContext(Dispatchers.IO) {
        MjbBackupManager(appContext).restore(archive)
    }

    /**
     * 读取归档的清单而不恢复。
     * 用于「先把归档内容展示给用户看过，再让他决定是否覆盖」。
     */
    suspend fun inspectArchive(archive: Uri): MjbManifest = withContext(Dispatchers.IO) {
        MjbBackupManager(appContext).readManifest(archive)
    }

    companion object {
        private const val WORK_NAME = "mjb-backup-now"
        private const val WORK_NAME_PERIODIC = "mjb-backup-periodic"
    }
}

/**
 * WorkManager 入口。
 *
 * 它拿不到 [BackupScheduler] 实例，所以自行构造 —— 这是 WorkManager
 * 按无参构造反射创建 Worker 的固有限制。因此 [BackupScheduler] 必须保持
 * 无状态、可从 Context 重建，状态一律落在 [JournalPrefs] 中。
 */
class BackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val scheduler = BackupScheduler(applicationContext)
        if (!scheduler.isConfigured()) return@withContext Result.success()
        if (!scheduler.isDirectoryUsable()) {
            // 目录不可访问时重试没有意义，等用户重新授权
            return@withContext Result.failure()
        }
        try {
            scheduler.backupNow()
            Result.success()
        } catch (t: Throwable) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}

/** 供 Worker 之外的地方持有调度器时使用，避免到处重复 new。 */
object BackupSchedulers {
    @Volatile
    private var instance: BackupScheduler? = null

    fun of(context: Context): BackupScheduler =
        instance ?: synchronized(this) {
            instance ?: BackupScheduler(context.applicationContext).also { instance = it }
        }
}
