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
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nemuri.journal.R
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
    /** 关掉当前日记，回到占位状态。编辑器是主界面，这里不是「返回上一层」。 */
    onCloseEntry: () -> Unit,
    /** 新建一篇日记。当前这篇若有未落库内容会先落库，不会丢。 */
    onCreateEntry: () -> Unit = {},
    /** 打开侧栏。它与功能抽屉同在顶栏，同级。 */
    onOpenSidebar: () -> Unit = {},
    /** 进入查找页。三档范围由查找页自己切换。 */
    onOpenSearch: (SearchViewModel.Scope) -> Unit = {},
) {
    val loadState by viewModel.loadState.collectAsStateWithLifecycle()

    // 数据尚未就绪时不渲染编辑区。
    // 此前以空值作初值直接渲染，于是打开日记会先闪一帧「空标题 + 1970 年」。
    //
    // ⚠️ 必须写成 if/else 的**并列两枝**，不能在 if 里提前 return。
    // loadState 每次打开日记都会经历 Loading → Ready，
    // 若用 return 提前退出，第二次重组会突然开始执行下面的 Scaffold ——
    // 同一个组合位点在不同重组间多出 remember 调用，Compose 会抛异常。
    if (loadState != EditorViewModel.LoadState.Ready) {
        EditorLoadingScreen(
            missing = loadState == EditorViewModel.LoadState.Missing,
            onCloseEntry = onCloseEntry,
            onOpenSidebar = onOpenSidebar,
        )
    } else {
        EditorContent(
            viewModel = viewModel,
            onCloseEntry = onCloseEntry,
            onOpenSidebar = onOpenSidebar,
            onCreateEntry = onCreateEntry,
            onOpenSearch = onOpenSearch,
        )
    }
}

/**
 * 编辑器的正文部分。
 *
 * 抽成独立函数，与 [EditorLoadingScreen] 构成并列的两个组合分支，
 * 而不是同一条语句流上的前后两段 —— 原因见调用处的注释。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorContent(
    viewModel: EditorViewModel,
    onCloseEntry: () -> Unit,
    onOpenSidebar: () -> Unit,
    onCreateEntry: () -> Unit,
    onOpenSearch: (SearchViewModel.Scope) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val title by viewModel.title.collectAsStateWithLifecycle()
    val body by viewModel.body.collectAsStateWithLifecycle()
    val createdAt by viewModel.createdAt.collectAsStateWithLifecycle()
    val saveState by viewModel.saveState.collectAsStateWithLifecycle()

    var showPreview by remember { mutableStateOf(false) }
    var showActionSheet by remember { mutableStateOf(false) }
    var showCopySheet by remember { mutableStateOf(false) }
    var showExportSheet by remember { mutableStateOf(false) }
    var showSearchScope by remember { mutableStateOf(false) }
    var showChangeTime by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(toast) {
        val message = toast ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
        toast = null
    }

    var pendingTextExport by remember { mutableStateOf<TextExport?>(null) }

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

    val textExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val pending = pendingTextExport
        if (uri != null && pending != null) {
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(pending.body.toByteArray(Charsets.UTF_8))
                } ?: error(context.getString(R.string.export_failed, ""))
            }
            toast = if (ok.isSuccess) {
                context.getString(R.string.export_done)
            } else {
                context.getString(R.string.export_failed, ok.exceptionOrNull()?.message ?: "")
            }
        }
        pendingTextExport = null
    }

    val closeEntry: () -> Unit = {
        scope.launch {
            viewModel.flushNow()
            onCloseEntry()
        }
    }
    BackHandler { closeEntry() }

    Scaffold(
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(modifier = Modifier.padding(16.dp)) { Text(data.visuals.message) }
            }
        },
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    // 左上角是侧栏按钮 —— 与右上角的功能抽屉同级，
                    // 而不是「返回」：编辑器已是主界面，没有上一层可退。
                    IconButton(onClick = onOpenSidebar) {
                        Icon(
                            Icons.Outlined.Menu,
                            contentDescription = stringResource(R.string.home_open_sidebar),
                        )
                    }
                },
                actions = {
                    // 新建。它不操作当前这篇日记，因此不属于功能抽屉 —
                    // 抽屉里四项都是「对当前日记做什么」，新建不是。
                    IconButton(onClick = {
                        // 先落库再新建：新建会替换当前编辑的日记，
                        // 与「关闭当前日记」在数据层面是同一个动作，
                        // 因此复用同一套 flush 流程，不另写一份。
                        scope.launch {
                            viewModel.flushNow()
                            onCreateEntry()
                        }
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.editor_new_entry))
                    }
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
                            contentDescription = if (showPreview) stringResource(R.string.editor_edit)
                            else stringResource(R.string.editor_preview),
                        )
                    }
                    // 功能抽屉入口，与左上角侧栏按钮同级
                    IconButton(onClick = { showActionSheet = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.home_actions))
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
                    stringResource(R.string.editor_created_at, TimeFormat.listStamp(createdAt)),
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
                                stringResource(R.string.editor_title_hint),
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
                                    stringResource(R.string.editor_body_hint),
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
                val text = viewModel.copyText(
                    includeHeader,
                    context.getString(R.string.common_untitled),
                )
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                // 剪贴板条目的标签。部分系统会显示它，因此也需要可翻译。
                clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText(
                        context.getString(R.string.app_name), text,
                    )
                )
                showCopySheet = false
                toast = context.getString(R.string.copy_done)
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
                // 文件名交给 SAF 的 CreateDocument，暂存的只是内容
                pendingTextExport = TextExport(body = payload.body)
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
                        if (r.sliced) toast = context.getString(R.string.export_sliced_done, r.files.size)
                    }.onFailure {
                        toast = context.getString(R.string.export_failed, it.message ?: "")
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
                toast = context.getString(R.string.change_time_done)
            },
        )
    }

}

/**
 * 待写入的导出内容。
 *
 * 只存正文，不存文件名 —— 文件名已经在 CreateDocument 时交给系统了，
 * 这里再存一份就会有两个来源，迟早不同步。
 * 用具名类型而非 Pair：Pair 的 first/second 在调用处看不出含义。
 */
private data class TextExport(val body: String)

@Composable
private fun SaveIndicator(state: EditorViewModel.SaveState) {
    val (label, icon, tint) = when (state) {
        EditorViewModel.SaveState.SAVED ->
            Triple(stringResource(R.string.editor_save_saved), Icons.Outlined.Done,
                MaterialTheme.colorScheme.onSurfaceVariant)
        EditorViewModel.SaveState.PENDING ->
            Triple(stringResource(R.string.editor_save_pending), Icons.Outlined.Bolt,
                MaterialTheme.colorScheme.onSurfaceVariant)
        // 「条目已被删除」不在这里 —— 那是加载状态，由 EditorLoadingScreen 呈现。
        // 保存状态只表达"改动存了没有"，两种值就够了。
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
/**
 * 键盘上方的快捷输入栏。
 *
 * 不再需要 enabled 参数：此前用它表达"没有日记时不可用"，
 * 但现在整块编辑器已被 LoadState 挡在前面 —— 能走到这里就一定有一篇日记。
 * 保留一个恒为 true 的开关只会误导。
 */
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
            QuickAction(Icons.Filled.Schedule, stringResource(R.string.editor_insert_time), onInsertTime)
            QuickAction(Icons.Filled.Image, stringResource(R.string.editor_insert_image), onPickImage)
        }
    }
}

@Composable
private fun QuickAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = label)
    }
}
