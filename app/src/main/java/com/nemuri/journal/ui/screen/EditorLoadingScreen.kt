package com.nemuri.journal.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nemuri.journal.R

/**
 * 编辑器在数据就绪之前的界面。
 *
 * ## 为什么要有这一层
 * 数据是异步加载的。此前界面直接以空值渲染，于是打开一篇日记会**先闪一帧
 * 「空标题、空正文、1970 年」**，数据到了才刷新成真实内容。
 *
 * 把初值换成一个"看起来正常"的时间只是掩盖问题：用户仍会看到一段错误内容，
 * 只是错得不明显。正确做法是**未就绪就不渲染编辑区**。
 *
 * ## 两种状态的差别
 * - **加载中**：只是还没读到，什么都不必说，留白即可。
 *   刻意不做转圈动画 —— 本地数据库读取通常在几十毫秒内完成，
 *   一个一闪而过的转圈比留白更让人分神。
 * - **缺失**：日记已被删除。这必须明确告诉用户，
 *   否则他面对一片空白会以为日记丢了或应用坏了。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorLoadingScreen(
    missing: Boolean,
    onCloseEntry: () -> Unit,
    onOpenSidebar: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onOpenSidebar) {
                        Icon(
                            Icons.Outlined.Menu,
                            contentDescription = stringResource(R.string.home_open_sidebar),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            if (missing) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp),
                ) {
                    Text(
                        text = stringResource(R.string.editor_missing_entry_body),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = onCloseEntry) {
                        Text(stringResource(R.string.editor_missing_dismiss))
                    }
                }
            }
            // 加载中：留白。见类注释中关于不做转圈的说明。
        }
    }
}
