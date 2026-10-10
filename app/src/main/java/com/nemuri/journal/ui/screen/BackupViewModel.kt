package com.nemuri.journal.ui.screen

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nemuri.journal.backup.BackupScheduler
import com.nemuri.journal.R
import com.nemuri.journal.util.TimeFormat
import com.nemuri.journal.backup.MjbBackupManager
import com.nemuri.journal.backup.MjbManifest
import com.nemuri.journal.data.prefs.JournalPrefs
import com.nemuri.journal.data.repo.MomentRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 备份配置与首启引导的状态。
 *
 * ## 为什么需要它
 * 0.1 里 `BackupScheduler.setBackupTreeUri` **没有任何调用者**，
 * 权限从未申请过，`isConfigured()` 恒为 false —— 三个备份触发点全部空转。
 * 换句话说备份功能在 0.1 中一次都没有真正执行过。
 * 本类补上那个缺失的入口。
 */
class BackupViewModel(
    private val repo: MomentRepository,
    private val prefs: JournalPrefs,
    private val scheduler: BackupScheduler,
    private val appContext: android.content.Context,
) : ViewModel() {

    /**
     * 是否需要引导用户配置备份目录。
     *
     * 判据是**目录当前能否使用**，而不是"是否做过选择"。
     * 此前用 `onboardingDone` 标记，它只记录用户点过按钮，
     * 不反映目录后来是否被撤销授权、删除或所在存储卡被卸载 ——
     * 那些情况下应用会以为自己配置好了，而备份从此静默失败。
     *
     * 初值为 true（先当作未配置），启动时异步校验后才置为真实值。
     * 宁可多显示一次引导，也不要让用户以为备份在工作而实际没有。
     */
    private val _onboardingVisible = MutableStateFlow(true)
    val onboardingVisible: StateFlow<Boolean> = _onboardingVisible.asStateFlow()

    init {
        viewModelScope.launch { refreshOnboarding() }
    }

    /**
     * 重新判定是否需要引导。
     *
     * 在启动时、以及每次回到前台时都应调用 —— 用户可能在系统设置里
     * 撤销了授权，而应用对此一无所知。
     */
    suspend fun refreshOnboarding() {
        val usable = scheduler.isConfigured() && scheduler.isDirectoryUsable()
        _configured.value = usable
        _onboardingVisible.value = !usable
    }

    /** 是否已配置可用目录。未配置时主界面要挂常驻提示。 */
    private val _configured = MutableStateFlow(prefs.backupConfigured)
    val configured: StateFlow<Boolean> = _configured.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** 最近一次操作的结果，用于即时反馈（成功也报，失败也报）。 */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** 上次备份的失败原因。成功或从未尝试时为 null。 */
    private val _errorState = MutableStateFlow(prefs.lastBackupError)
    val errorState: StateFlow<String?> = _errorState.asStateFlow()

    /**
     * 失败详情，供横幅展开显示。
     *
     * 包含时间与完整原因。横幅上只放一行摘要，展开后才显示这些 ——
     * 长的错误信息（如具体是哪个文件写不进去）直接铺在横幅上会挤掉正文。
     */
    private val _errorDetails = MutableStateFlow<String?>(null)
    val errorDetails: StateFlow<String?> = _errorDetails.asStateFlow()

    /** 用户是否已关闭当前这条失败提示。关闭后不再重复展示，直到出现新的失败。 */
    private val _dismissed = MutableStateFlow(false)
    val dismissed: StateFlow<Boolean> = _dismissed.asStateFlow()

    fun dismissError() {
        _dismissed.value = true
    }

    val lastBackupAt: Long get() = prefs.lastBackupAt
    val lastBackupError: String? get() = prefs.lastBackupError
    val backupTreeUri: String? get() = prefs.backupTreeUri

    // ------------------------------------------------------------ 首启引导

    /** 用户选定了备份目录。立即执行一次备份，让「它真的写进去了」当场可见。 */
    fun onDirectoryChosen(uri: Uri) {
        viewModelScope.launch {
            _busy.value = true
            persistPermission(uri)
            prefs.backupTreeUri = uri.toString()
            _configured.value = true
            _onboardingVisible.value = false
            try {
                val result = scheduler.backupNow()
                _message.value = appContext.getString(
                    R.string.backup_done, result.displayName, result.entryCount,
                )
                _errorState.value = null
            } catch (t: Throwable) {
                // 首次选择就失败必须说清楚，否则用户以为配好了
                val reason = t.message ?: appContext.getString(R.string.backup_unknown_reason)
                _message.value = appContext.getString(R.string.backup_failed, reason)
                _errorState.value = reason
                _errorDetails.value = buildString {
                    append(appContext.getString(R.string.backup_last_success, TimeFormat.listStamp(System.currentTimeMillis())))
                    append('\n')
                    append(reason)
                }
                // 新的失败要重新展示，即使用户关过上一次的
                _dismissed.value = false
            } finally {
                _busy.value = false
            }
        }
    }


    // ------------------------------------------------------------ 手动操作

    /** 手动触发一次备份，结果直接反馈给用户。 */
    fun backupNow() {
        viewModelScope.launch {
            _busy.value = true
            try {
                val result = scheduler.backupNow()
                _message.value = appContext.getString(
                    R.string.backup_done, result.displayName, result.entryCount,
                )
                _configured.value = prefs.backupConfigured
                _errorState.value = null
            } catch (t: Throwable) {
                val reason = t.message ?: appContext.getString(R.string.backup_unknown_reason)
                _message.value = appContext.getString(R.string.backup_failed, reason)
                _errorState.value = reason
                _errorDetails.value = buildString {
                    append(appContext.getString(R.string.backup_last_success, TimeFormat.listStamp(System.currentTimeMillis())))
                    append('\n')
                    append(reason)
                }
                // 新的失败要重新展示，即使用户关过上一次的
                _dismissed.value = false
            } finally {
                _busy.value = false
            }
        }
    }

    /** 更换备份目录。 */
    fun changeDirectory(uri: Uri) {
        persistPermission(uri)
        prefs.backupTreeUri = uri.toString()
        _configured.value = true
        _errorState.value = null
        _message.value = appContext.getString(R.string.backup_location_updated)
    }

    /**
     * 持久化 SAF 授权。
     *
     * 不调用 takePersistableUriPermission 的话，授权只在本次进程内有效，
     * 重启后 DocumentFile 会静默变成不可写 —— 备份从此不再成功，而界面看起来一切正常。
     */
    private fun persistPermission(uri: Uri) {
        runCatching {
            appContext.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.onFailure {
            // 少数设备/来源不支持持久化授权；此时不写入，让「未配置」如实反映
            _errorState.value = appContext.getString(R.string.backup_permission_lost)
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    /**
     * 读取归档清单，不落任何数据。
     * 结果通过 [onLoaded] 交给界面，由界面负责展示并要求用户确认。
     */
    fun inspect(archive: Uri, onLoaded: (MjbManifest) -> Unit) {
        viewModelScope.launch {
            try {
                onLoaded(scheduler.inspectArchive(archive))
            } catch (t: Throwable) {
                _message.value = appContext.getString(
                    R.string.backup_archive_unreadable,
                    t.message ?: appContext.getString(R.string.backup_unknown_reason),
                )
            }
        }
    }

    /** 已授权目录中的归档列表。界面进入恢复流程时加载。 */
    private val _archives = MutableStateFlow<List<MjbBackupManager.ArchiveEntry>>(emptyList())
    val archives: StateFlow<List<MjbBackupManager.ArchiveEntry>> = _archives.asStateFlow()

    private val _archivesLoading = MutableStateFlow(false)
    val archivesLoading: StateFlow<Boolean> = _archivesLoading.asStateFlow()

    fun loadArchives() {
        viewModelScope.launch {
            _archivesLoading.value = true
            // 目录不可用时列表为空，界面据此提示「应从其他位置选择」
            _archives.value = runCatching { scheduler.listArchives() }.getOrDefault(emptyList())
            _archivesLoading.value = false
        }
    }

    /** 执行恢复。破坏性操作，调用方必须已获得用户确认。 */
    fun restore(archive: Uri) {
        viewModelScope.launch {
            _busy.value = true
            try {
                val result = scheduler.restore(archive)
                _message.value = buildString {
                    // 图片数为 0 时用不含图片的句式，避免出现「已恢复 3 篇日记、0 张图片」
                    append(
                        if (result.restoredImageCount > 0) {
                            appContext.getString(
                                R.string.backup_restore_done_images,
                                result.restoredEntryCount,
                                result.restoredImageCount,
                            )
                        } else {
                            appContext.getString(
                                R.string.backup_restore_done,
                                result.restoredEntryCount,
                            )
                        }
                    )
                    if (result.manifestMismatch) {
                        append(appContext.getString(R.string.backup_restore_mismatch))
                    }
                }
            } catch (t: Throwable) {
                _message.value = appContext.getString(
                    R.string.backup_restore_failed,
                    t.message ?: appContext.getString(R.string.backup_unknown_reason),
                )
            } finally {
                _busy.value = false
            }
        }
    }

    /**
     * 所选目录当前是否仍可访问。
     *
     * 用户可能删除文件夹、卸载存储卡或撤销授权，所以每次使用前重新校验，
     * 而不是缓存一个「可用」的判断。
     */
    suspend fun verifyDirectory(): Boolean = scheduler.isDirectoryUsable()

    companion object {
        /**
         * 构造工厂。
         *
         * context 从 [prefs] 拿不到，而 ViewModel 需要在恢复授权时使用
         * ContentResolver，所以由调用方传一个 Context 进来。
         * 注意：本方法运行在 @Composable 作用域之外，不要在里面引用
         * Activity 的成员。
         */
        fun factory(
            context: android.content.Context,
            repo: MomentRepository,
            prefs: JournalPrefs,
            scheduler: BackupScheduler,
        ) = viewModelFactory {
            initializer { BackupViewModel(repo, prefs, scheduler, context.applicationContext) }
        }
    }
}
