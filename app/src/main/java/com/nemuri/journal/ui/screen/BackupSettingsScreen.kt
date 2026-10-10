package com.nemuri.journal.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nemuri.journal.R
import com.nemuri.journal.backup.MjbManifest
import com.nemuri.journal.util.TimeFormat

/**
 * 备份设置。
 *
 * 0.1 有完整的备份逻辑却没有任何界面，用户既不知道它在哪里，也无法触发它。
 * 本页把三件事补齐：看当前状态、手动备份、从归档恢复。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupSettingsScreen(
    viewModel: BackupViewModel,
    onBack: () -> Unit,
) {
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val configured by viewModel.configured.collectAsStateWithLifecycle()
    val archives by viewModel.archives.collectAsStateWithLifecycle()
    val archivesLoading by viewModel.archivesLoading.collectAsStateWithLifecycle()
    var showRestoreList by remember { mutableStateOf(false) }

    var directoryUsable by remember { mutableStateOf(true) }
    var pendingRestore by remember { mutableStateOf<Pair<android.net.Uri, MjbManifest>?>(null) }
    var lastBackupAt by remember { mutableStateOf(viewModel.lastBackupAt) }
    var lastError by remember { mutableStateOf(viewModel.lastBackupError) }

    // 目录可能已被删除或撤销授权，进页面时重新校验一次
    LaunchedEffect(configured) {
        if (configured) directoryUsable = viewModel.verifyDirectory()
    }

    val dirPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        // 授权持久化由 ViewModel 统一负责，界面只传达用户的选择
        if (uri != null) viewModel.changeDirectory(uri)
    }

    val archivePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            // 先读清单展示给用户，得到确认后才真正覆盖
            viewModel.inspect(uri) { manifest -> pendingRestore = uri to manifest }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.backup_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            SectionTitle(stringResource(R.string.backup_section_location))
            Text(
                text = when {
                    !configured -> stringResource(R.string.backup_not_configured)
                    !directoryUsable -> stringResource(R.string.backup_dir_unreachable)
                    else -> viewModel.backupTreeUri ?: stringResource(R.string.backup_not_configured)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (!directoryUsable && configured) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { dirPicker.launch(null) }) {
                Text(
                    if (configured) stringResource(R.string.backup_change_dir)
                    else stringResource(R.string.backup_pick_dir)
                )
            }

            SectionTitle(stringResource(R.string.backup_section_status))
            Text(
                text = if (lastBackupAt > 0) {
                    stringResource(R.string.backup_last_success, TimeFormat.listStamp(lastBackupAt))
                } else {
                    stringResource(R.string.backup_never)
                },
                style = MaterialTheme.typography.bodySmall,
            )
            lastError?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.backup_last_failed, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            SectionTitle(stringResource(R.string.backup_section_actions))
            Button(
                onClick = {
                    viewModel.backupNow()
                },
                enabled = configured && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (busy) stringResource(R.string.backup_in_progress)
                    else stringResource(R.string.backup_now)
                )
            }

            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = {
                    // 直接列出本机备份，不必每次从文件框架里翻找。
                    // 「从其他位置选择」作为次要入口保留 —— 归档可能来自
                    // 另一台设备、聊天软件或网盘，不只是本机生成的。
                    showRestoreList = true
                    viewModel.loadArchives()
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.backup_restore))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.backup_restore_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (showRestoreList) {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.restore_list_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))

                when {
                    archivesLoading -> Text(
                        stringResource(R.string.restore_loading),
                        style = MaterialTheme.typography.bodySmall,
                    )

                    archives.isEmpty() -> Text(
                        stringResource(R.string.restore_list_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    else -> archives.forEach { entry ->
                        ArchiveRow(
                            entry = entry,
                            // 读不出清单的归档单独标记为不可选，
                            // 不影响其他项 —— 一个坏文件不该让整列失效
                            enabled = entry.manifest != null,
                            onClick = {
                                entry.manifest?.let { m -> pendingRestore = entry.uri to m }
                            },
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { archivePicker.launch(arrayOf("application/zip", "*/*")) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.restore_pick_other))
                }
            }

            message?.let {
                Spacer(Modifier.height(20.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { viewModel.clearMessage() }) {
                    Text(stringResource(R.string.common_got_it))
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }

    // 恢复确认：先展示归档内容，再要求确认
    pendingRestore?.let { (uri, manifest) ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text(stringResource(R.string.restore_confirm_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.restore_archive_intro))
                    Spacer(Modifier.height(8.dp))
                    Text("· " + stringResource(R.string.restore_field_time) + "：" + TimeFormat.listStamp(manifest.createdAt))
                    Text("· " + stringResource(R.string.restore_field_entries) + "：" + manifest.entryCount)
                    Text("· " + stringResource(R.string.restore_field_books) + "：" + manifest.bookCount)
                    Text("· " + stringResource(R.string.restore_field_images) + "：" + manifest.imageCount)
                    Text("· " + stringResource(R.string.restore_field_version) + "：" + manifest.formatVersion)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.restore_warning),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.restore(uri)
                    pendingRestore = null
                }) { Text(stringResource(R.string.restore_action)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestore = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

/** 归档列表中的一项。 */
@Composable
private fun ArchiveRow(
    entry: com.nemuri.journal.backup.MjbBackupManager.ArchiveEntry,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = entry.name,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        val m = entry.manifest
        Text(
            text = if (m == null) {
                // 损坏的归档要说明原因，而不是只显示一个灰条
                stringResource(R.string.restore_item_unreadable)
            } else {
                stringResource(
                    R.string.restore_item_summary,
                    m.entryCount,
                    android.text.format.Formatter.formatShortFileSize(context, entry.sizeBytes),
                )
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(24.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.height(12.dp))
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}
