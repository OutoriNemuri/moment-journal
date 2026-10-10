package com.nemuri.journal.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nemuri.journal.R

/**
 * 首次启动引导：要求用户指定一个备份目录。
 *
 * ## 为什么必须是引导而不是可跳过的弹窗
 * 0.1 的通病是「有权能无入口」——备份逻辑写完了却没有地方触发它。
 * 首启是唯一能保证用户看见这个入口的时机。
 *
 * ## 「稍后再说」不是静默跳过
 * 用户有权拒绝。但拒绝之后主界面会挂一条常驻提示说明日记仅存本机，
 * 提示条承担提醒职责，因此不再反复弹窗骚扰。
 */
@Composable
fun BackupOnboardingScreen(
    viewModel: BackupViewModel,
    onFinished: () -> Unit,
) {
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            viewModel.onDirectoryChosen(uri)
            onFinished()
        }
        // 用户取消选择则停留在本页，不强推进 —— 他还没做出决定
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.onboarding_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))

        Button(
            onClick = { picker.launch(null) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.onboarding_pick))
        }
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = {
                viewModel.postponeOnboarding()
                onFinished()
            },
        ) {
            Text(stringResource(R.string.onboarding_later))
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.onboarding_later_warning),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 未配置备份时挂在主界面上的常驻提示条。
 *
 * 用提示条而不是弹窗：不打断写日记，但持续可见，
 * 让「日记仅存本机」这件事不被遗忘。
 */
@Composable
fun BackupWarningBar(
    isError: Boolean,
    message: String,
    onAction: () -> Unit,
    actionLabel: String = stringResource(R.string.backup_warning_action),
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onAction) { Text(actionLabel) }
    }
}
