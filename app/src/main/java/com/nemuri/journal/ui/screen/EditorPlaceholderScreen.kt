package com.nemuri.journal.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nemuri.journal.R

/**
 * 编辑器的占位状态：主界面已就位，但还没有打开任何日记。
 *
 * ## 为什么单独写一个 Composable
 * 编辑器本身处处假设「有一篇日记」——标题、正文、保存状态、四项操作
 * 都建立在它之上。若把「没有日记」也塞进去，会让每个使用点都要处理空值，
 * 而这些空值分支永远只有这一种情形会走到。
 * 因此这里是一个独立的、结构上没有编辑能力的界面。
 *
 * ## 为什么编辑区不可输入
 * 若允许输入，敲下的字要么无处归属（丢失），要么得悄悄创建一篇空日记
 * （用户没要求过）。两种结果都比"不能输入"更糟。
 *
 * ## 引导文案不是按钮
 * 它是说明，不是入口。打开日记的入口只有两处：侧栏，或右下角的新建按钮。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorPlaceholderScreen(
    bookName: String?,
    canCreate: Boolean,
    onCreateEntry: () -> Unit,
    onOpenSidebar: () -> Unit,
) {
    // 占位状态下抽屉里只有「备份」，但它必须能打开 ——
    // 备份与有没有打开日记无关。这与「四项编辑功能不显示」并不矛盾：
    // 不是隐藏整个抽屉，而是隐藏其中不可用的项。
    var showActions by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                // 顶栏显示当前日记本名，让用户知道自己身在何处 ——
                // 侧栏是划出的，收起后界面里没有别的线索。
                title = { Text(bookName ?: stringResource(R.string.app_name)) },
                navigationIcon = {
                    IconButton(onClick = onOpenSidebar) {
                        Icon(
                            Icons.Outlined.Menu,
                            contentDescription = stringResource(R.string.home_open_sidebar),
                        )
                    }
                },
                actions = {
                    // 与侧栏按钮同级。抽屉里只有「备份」一项在此时可用，
                    // 但它与是否有日记无关，所以入口始终保留。
                    IconButton(onClick = { showActions = true }) {
                        Icon(
                            Icons.Outlined.MoreVert,
                            contentDescription = stringResource(R.string.home_actions),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        floatingActionButton = {
            // 与列表页一致：没有日记本时不出现，而不是显示了点不动
            if (canCreate) {
                ExtendedFloatingActionButton(
                    onClick = onCreateEntry,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.editor_new_entry)) },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp),
                ) {
                    Text(
                        text = stringResource(R.string.editor_placeholder_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.editor_placeholder_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            // 快捷输入栏在此**整体不出现**：没有日记可插入时间或图片。
            // 这里不是把它禁用，而是根本不构成这个界面的一部分。
            Spacer(Modifier.navigationBarsPadding())
        }
    }

    if (showActions) {
        EditorActionSheet(
            onDismiss = { showActions = false },
            onAction = { action ->
                showActions = false
                // 占位状态下没有日记，四项都不可用。
                // 这里不做事，但抽屉本身仍可打开 —— 它以后还会承载其他项。
                Unit
            },
        )
    }
}
