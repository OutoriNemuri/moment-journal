package com.nemuri.journal.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
                title = { Text("备份") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
            SectionTitle("备份位置")
            Text(
                text = when {
                    !configured -> "尚未指定"
                    !directoryUsable -> "所选文件夹已无法访问"
                    else -> viewModel.backupTreeUri ?: "尚未指定"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (!directoryUsable && configured) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { dirPicker.launch(null) }) {
                Text(if (configured) "更换文件夹" else "选择文件夹")
            }

            SectionTitle("状态")
            Text(
                text = if (lastBackupAt > 0) {
                    "上次成功备份：${TimeFormat.listStamp(lastBackupAt)}"
                } else {
                    "尚未成功备份过"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            lastError?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    "上次失败：$it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            SectionTitle("操作")
            Button(
                onClick = {
                    viewModel.backupNow()
                },
                enabled = configured && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (busy) "备份中…" else "立即备份")
            }

            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = { archivePicker.launch(arrayOf("application/zip", "*/*")) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("从归档恢复")
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "恢复会覆盖当前全部数据，操作前会先展示归档内容供你确认。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            message?.let {
                Spacer(Modifier.height(20.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { viewModel.clearMessage() }) { Text("知道了") }
            }

            Spacer(Modifier.height(40.dp))
        }
    }

    // 恢复确认：先展示归档内容，再要求确认
    pendingRestore?.let { (uri, manifest) ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("覆盖当前数据？") },
            text = {
                Column {
                    Text("将要导入的归档：")
                    Spacer(Modifier.height(8.dp))
                    Text("· 备份时间：${TimeFormat.listStamp(manifest.createdAt)}")
                    Text("· 日记：${manifest.entryCount} 篇")
                    Text("· 日记本：${manifest.bookCount} 个")
                    Text("· 图片：${manifest.imageCount} 张")
                    Text("· 格式版本：${manifest.formatVersion}")
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "当前设备上的全部日记将被替换，此操作无法撤销。" +
                            "建议先做一次备份。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.restore(uri)
                    pendingRestore = null
                }) { Text("覆盖") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestore = null }) { Text("取消") }
            },
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
