package com.nemuri.journal.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nemuri.journal.R

/**
 * 备份状态提示，位于顶栏下方的整宽横幅。
 *
 * ## 为什么是横幅而不是浮动提示
 * 备份失败是**持续性状态**，不是一次性事件。浮动提示（toast/snackbar）
 * 会自动消失，用户没看见就永远错过了。横幅留在界面上直到问题解决或用户主动关闭。
 *
 * ## 关闭的语义
 * 关闭**不是**「忽略这个错误」，而是「我知道了，别再挡着我」。因此：
 * - 关闭后不再显示**同一条**失败，记录已读的时间戳
 * - 出现**新的失败**时会重新出现
 * - 备份成功后失败状态清除，横幅自然消失
 *
 * 这样避免两种坏结果：用户关掉后再也看不到问题；或每次启动又被同样的提示烦一次。
 */
@Composable
fun BackupBanner(
    level: Level,
    message: String,
    details: String?,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    val container = when (level) {
        Level.Warning -> MaterialTheme.colorScheme.errorContainer
        Level.Info -> MaterialTheme.colorScheme.surfaceVariant
    }
    val onContainer = when (level) {
        Level.Warning -> MaterialTheme.colorScheme.onErrorContainer
        Level.Info -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(color = container, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (level == Level.Warning) {
                    Icon(Icons.Outlined.Warning, contentDescription = null, tint = onContainer)
                }
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = onContainer,
                    modifier = Modifier.weight(1f),
                )
                // 有详情才给展开入口。没有详情时给一个点不动的箭头是误导。
                if (details != null) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(
                            stringResource(
                                if (expanded) R.string.backup_warning_collapse
                                else R.string.backup_warning_details
                            ),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(
                        stringResource(R.string.backup_warning_dismiss),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            AnimatedVisibility(visible = expanded && details != null) {
                Column {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = details.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = onContainer,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = onOpenSettings) {
                            Text(stringResource(R.string.backup_warning_action))
                        }
                    }
                }
            }
        }
    }
}

enum class Level { Warning, Info }
