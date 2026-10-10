package com.nemuri.journal.ui.screen

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Visibility
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import com.nemuri.journal.export.Exporter
import com.nemuri.journal.md.MomentMarkdown
import com.nemuri.journal.ui.component.MdBody
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
    /** 进入查找页。三档范围由查找页自己切换。 */
    onOpenSearch: (SearchViewModel.Scope) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val title by viewModel.title.collectAsStateWithLifecycle()
    val body by viewModel.body.collectAsStateWithLifecycle()
    val createdAt by viewModel.createdAt.collectAsStateWithLifecycle()
    val saveState by viewModel.saveState.collectAsStateWithLifecycle()

    var showPreview by remember { mutableStateOf(false) }
    // 四位一体的抽屉状态：一级抽屉与三个二级抽屉
    var showActionSheet by remember { mutableStateOf(false) }
    var showCopySheet by remember { mutableStateOf(false) }
    var showExportSheet by remember { mutableStateOf(false) }
    var showSearchScope by remember { mutableStateOf(false) }
    var showChangeTime by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    // 待写入的导出内容。SAF 的 CreateDocument 会先返回，用户选完位置才回调，
    // 因此内容要先暂存起来。
    var pendingTextExport by remember { mutableStateOf<Pair<String, String>?>(null) }

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

    // 文本导出：让用户选保存位置。SAF 返回的 Uri 可能指向任意位置，
    // 这里只负责把内容写进去，写失败必须让用户看见。
    val textExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val pending = pendingTextExport
        if (uri != null && pending != null) {
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(pending.content.toByteArray(Charsets.UTF_8))
                } ?: error("无法写入所选位置")
            }
            toast = if (ok.isSuccess) "已导出" else "导出失败：${ok.exceptionOrNull()?.message}"
        }
        pendingTextExport = null
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
                    IconButton(onClick = {
                        // 切换前先落库，避免预览看到的是旧内容
                        scope.launch { viewModel.flushNow() }
                        showPreview = !showPreview
                    }) {
                        Icon(
                            imageVector = if (showPreview) {
                                Icons.Outlined.Edit
                            } else {
                                Icons.Outlined.Visibility
                            },
                            contentDescription = if (showPreview) "编辑" else "预览",
                        )
                    }
                    // 功能抽屉入口，与左上角侧栏按钮同级
                    IconButton(onClick = { showActionSheet = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "日记操作")
                    }
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

                if (showPreview) {
                    // 预览模式：渲染后的样式与嵌入。编辑模式直接改 Markdown 源文本，
                    // 因此光标永远进得去，不存在"富文本视图无法输入"的问题。
                    MdBody(
                        source = body,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                    )
                } else {
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
            }

            QuickInputBar(
                enabled = saveState != EditorViewModel.SaveState.MISSING,
                onInsertTime = {
                    viewModel.onBodyChange(
                        body + MomentMarkdown.timeToken(TimeFormat.isoNow()) + " "
                    )
                },
                onPickImage = { imagePicker.launch(arrayOf("image/*")) },
            )
        }
    }

    // ---------------- 功能抽屉 ----------------

    if (showActionSheet) {
        EditorActionSheet(
            onDismiss = { showActionSheet = false },
            onAction = { action ->
                showActionSheet = false
                when (action) {
                    EditorAction.COPY -> showCopySheet = true
                    EditorAction.SEARCH -> showSearchScope = true
                    EditorAction.EXPORT -> showExportSheet = true
                    EditorAction.CHANGE_TIME -> showChangeTime = true
                }
            },
        )
    }

    if (showCopySheet) {
        CopySheet(
            onDismiss = { showCopySheet = false },
            onConfirm = { includeHeader ->
                val text = viewModel.copyText(includeHeader)
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("日记", text))
                showCopySheet = false
                toast = "已复制到剪贴板"
            },
        )
    }

    if (showSearchScope) {
        SearchScopeSheet(
            onDismiss = { showSearchScope = false },
            onPick = { picked ->
                showSearchScope = false
                scope.launch { viewModel.flushNow() }   // 先把当前内容落库，才能查到它
                onOpenSearch(picked)
            },
        )
    }

    if (showExportSheet) {
        ExportSheet(
            onDismiss = { showExportSheet = false },
            onText = { format ->
                showExportSheet = false
                val payload = viewModel.exportPayload(plainText = format == Exporter.TextFormat.TXT)
                // 内容暂存，等用户选完保存位置再写入
                pendingTextExport = payload.suggestedName + "." + format.extension to payload.body
                textExporter.launch(payload.suggestedName + "." + format.extension)
            },
            onImage = { slice ->
                showExportSheet = false
                scope.launch {
                    val payload = viewModel.exportPayload(plainText = false)
                    val result = runCatching {
                        Exporter.renderImages(
                            context = context,
                            title = payload.title,
                            body = payload.body,
                            meta = TimeFormat.listStamp(payload.createdAt),
                            baseName = payload.suggestedName,
                            slice = slice,
                        )
                    }
                    result.onSuccess { r ->
                        // 渲染完成后再交给系统分享。图片可能有多张，统一走分享面板。
                        Exporter.shareFiles(
                            context, r.files, "image/png", payload.title,
                        )
                        if (r.sliced) toast = "内容较长，已切分为 ${r.files.size} 张"
                    }.onFailure {
                        toast = "导出失败：${it.message}"
                    }
                }
            },
        )
    }

    if (showChangeTime) {
        ChangeTimeDialog(
            currentMillis = createdAt,
            onDismiss = { showChangeTime = false },
            onConfirm = { millis ->
                viewModel.changeCreatedAt(millis)
                showChangeTime = false
                toast = "创建时间已修改，日记已按新时间重新排序"
            },
        )
    }

    toast?.let { message ->
        LaunchedEffect(message) {
            kotlinx.coroutines.delay(2400)
            toast = null
        }
        androidx.compose.material3.Snackbar(
            modifier = Modifier.padding(16.dp),
        ) { Text(message) }
    }
}

@Composable
private fun SaveIndicator(state: EditorViewModel.SaveState) {
    val (label, icon, tint) = when (state) {
        EditorViewModel.SaveState.SAVED ->
            Triple("已保存", Icons.Outlined.Done, MaterialTheme.colorScheme.onSurfaceVariant)
        EditorViewModel.SaveState.PENDING ->
            Triple("保存中…", Icons.Outlined.Bolt, MaterialTheme.colorScheme.onSurfaceVariant)
        // 条目已被删除：必须让用户看见，否则他会以为内容还存着
        EditorViewModel.SaveState.MISSING ->
            Triple("日记已被删除", Icons.Outlined.Delete, MaterialTheme.colorScheme.error)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(end = 12.dp),
    ) {
        AnimatedVisibility(visible = true) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = tint,
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = tint)
    }
}

/**
 * 快捷输入栏 —— 悬浮在键盘上方。
 *
 * 本版只放时间与图片：地点需要权限与地理编码，见 docs/BETA-0.1-BUILD-1.md 的外推表。
 */
@Composable
private fun QuickInputBar(
    enabled: Boolean,
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
            QuickAction(Icons.Filled.Schedule, "时间", enabled, onInsertTime)
            QuickAction(Icons.Filled.Image, "图片", enabled, onPickImage)
        }
    }
}

@Composable
private fun QuickAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = label)
    }
}
