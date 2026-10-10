package com.nemuri.journal.ui.screen

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nemuri.journal.md.MomentMarkdown
import com.nemuri.journal.util.ImageStore
import com.nemuri.journal.util.TimeFormat
import kotlinx.coroutines.launch

/**
 * 日记编辑器。
 *
 * ## 结构
 * 顶栏（返回 + 保存状态）｜标题｜正文｜**快捷输入栏**
 *
 * 快捷输入栏是普通布局里的最后一行，而不是浮在窗口上的独立 View。
 * 配合 `android:windowSoftInputMode="adjustResize"`（见 AndroidManifest）
 * 与 [imePadding]，它会自然地贴在键盘上方 —— 这正是 Android 11+ 软键盘接口
 * 推荐的做法：窗口随键盘 resize，不需要任何 insets 黑魔法。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    viewModel: EditorViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val title by viewModel.title.collectAsStateWithLifecycle()
    val body by viewModel.body.collectAsStateWithLifecycle()
    val createdAt by viewModel.createdAt.collectAsStateWithLifecycle()
    val saveState by viewModel.saveState.collectAsStateWithLifecycle()

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val path = ImageStore.import(context, uri)
            if (path != null) {
                viewModel.onBodyChange(body + "\n" + MomentMarkdown.imageToken(path) + "\n")
            }
        }
    }

    // 退出前把未落库的草稿推一次。返回手势与返回按钮共用这一条路径。
    val leave: () -> Unit = {
        scope.launch {
            viewModel.flushNow()
            onBack()
        }
    }
    BackHandler { leave() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = leave) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    SaveIndicator(saveState)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding(),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
            ) {
                Text(
                    "创建于 " + TimeFormat.listStamp(createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )

                BasicTextField(
                    value = title,
                    onValueChange = viewModel::onTitleChange,
                    textStyle = MaterialTheme.typography.headlineSmall.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        if (title.isEmpty()) {
                            Text(
                                "标题",
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    },
                )

                Spacer(Modifier.height(12.dp))

                // 正文直接编辑 Markdown 源文本。
                // 渲染（样式、嵌入的图片与时间）由 MdBody 负责，见文件末尾说明。
                BasicTextField(
                    value = body,
                    onValueChange = viewModel::onBodyChange,
                    textStyle = TextStyle(
                        fontSize = 17.sp,
                        lineHeight = 28.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth().height(320.dp),
                    decorationBox = { inner ->
                        if (body.isEmpty()) {
                            Text(
                                "写点什么…",
                                style = TextStyle(
                                    fontSize = 17.sp,
                                    lineHeight = 28.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            )
                        }
                        inner()
                    },
                )
            }

            QuickInputBar(
                onInsertTime = {
                    viewModel.onBodyChange(
                        body + MomentMarkdown.timeToken(TimeFormat.isoNow()) + " "
                    )
                },
                onPickImage = { imagePicker.launch(arrayOf("image/*")) },
            )
        }
    }
}

@Composable
private fun SaveIndicator(state: EditorViewModel.SaveState) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(end = 12.dp),
    ) {
        AnimatedVisibility(visible = true) {
            Icon(
                imageVector = if (state == EditorViewModel.SaveState.SAVED) {
                    Icons.Outlined.Done
                } else {
                    Icons.Outlined.Bolt
                },
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            if (state == EditorViewModel.SaveState.SAVED) "已保存" else "保存中…",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 快捷输入栏 —— 悬浮在键盘上方。
 *
 * 本版只放时间与图片：地点需要权限与地理编码，见 docs/BETA-0.1-BUILD-1.md 的外推表。
 */
@Composable
private fun QuickInputBar(
    onInsertTime: () -> Unit,
    onPickImage: () -> Unit,
) {
    Column(Modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            QuickAction(Icons.Filled.Schedule, "时间", onInsertTime)
            QuickAction(Icons.Filled.Image, "图片", onPickImage)
        }
    }
}

@Composable
private fun QuickAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Box {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = label)
        }
    }
}
