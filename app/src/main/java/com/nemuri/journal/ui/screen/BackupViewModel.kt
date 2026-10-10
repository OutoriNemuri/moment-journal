package com.nemuri.journal.ui.screen

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nemuri.journal.backup.BackupScheduler
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

    /** 是否已在首启引导中做过选择（无论选指定目录还是稍后再说）。 */
    private val _onboardingVisible = MutableStateFlow(!prefs.onboardingDone)
    val onboardingVisible: StateFlow<Boolean> = _onboardingVisible.asStateFlow()

    /** 是否已配置可用目录。未配置时主界面要挂常驻提示。 */
    private val _configured = MutableStateFlow(prefs.backupConfigured)
    val configured: StateFlow<Boolean> = _configured.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** 最近一次操作的结果，用于即时反馈（成功也报，失败也报）。 */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** 上次备份的失败原因，用于主界面的常驻提示。成功或从未尝试时为 null。 */
    private val _errorState = MutableStateFlow(prefs.lastBackupError)
    val errorState: StateFlow<String?> = _errorState.asStateFlow()

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
            prefs.onboardingDone = true
            _onboardingVisible.value = false
            try {
                val result = scheduler.backupNow()
                _message.value = "已备份到 ${result.displayName}（${result.entryCount} 篇日记）"
                _errorState.value = null
            } catch (t: Throwable) {
                // 首次选择就失败必须说清楚，否则用户以为配好了
                val reason = t.message ?: "未知原因"
                _message.value = "备份失败：$reason"
                _errorState.value = reason
            } finally {
                _busy.value = false
            }
        }
    }

    /**
     * 用户选择「稍后再说」。
     *
     * 不是静默跳过：主界面会挂一条常驻提示说明日记仅存本机。
     * 记录 onboardingDone 是为了不再反复弹窗骚扰 —— 提示条承担提醒职责。
     */
    fun postponeOnboarding() {
        prefs.onboardingDone = true
        _onboardingVisible.value = false
    }

    // ------------------------------------------------------------ 手动操作

    /** 手动触发一次备份，结果直接反馈给用户。 */
    fun backupNow() {
        viewModelScope.launch {
            _busy.value = true
            try {
                val result = scheduler.backupNow()
                _message.value = "已备份到 ${result.displayName}（${result.entryCount} 篇日记）"
                _configured.value = prefs.backupConfigured
                _errorState.value = null
            } catch (t: Throwable) {
                val reason = t.message ?: "未知原因"
                _message.value = "备份失败：$reason"
                _errorState.value = reason
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
        _message.value = "备份位置已更新"
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
            _errorState.value = "无法长期保留该文件夹的访问权限，请换一个位置"
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
                _message.value = "无法读取归档：${t.message ?: "未知原因"}"
            }
        }
    }

    /** 执行恢复。破坏性操作，调用方必须已获得用户确认。 */
    fun restore(archive: Uri) {
        viewModelScope.launch {
            _busy.value = true
            try {
                val result = scheduler.restore(archive)
                _message.value = buildString {
                    append("已恢复 ${result.restoredEntryCount} 篇日记")
                    if (result.restoredImageCount > 0) append("、${result.restoredImageCount} 张图片")
                    if (result.manifestMismatch) {
                        append("。注意：归档记录的条数与实际不一致，可能不完整")
                    }
                }
            } catch (t: Throwable) {
                _message.value = "恢复失败：${t.message ?: "未知原因"}"
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
