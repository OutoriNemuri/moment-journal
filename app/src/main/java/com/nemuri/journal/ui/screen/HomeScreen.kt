package com.nemuri.journal.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nemuri.journal.R
import com.nemuri.journal.data.db.Book
import com.nemuri.journal.data.db.Entry
import com.nemuri.journal.util.TimeFormat
import kotlinx.coroutines.launch

/**
 * 主界面：当前日记本的日记列表。
 *
 * 侧栏分上下两段 —— 上半段是该本的日记目录，下半段是日记本列表。
 * 0.2 取消了「全部日记」入口：日记本即容器，没有跨本视图。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    backupViewModel: BackupViewModel,
    onOpenEntry: (Long) -> Unit,
    onOpenBackupSettings: () -> Unit,
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val books by viewModel.books.collectAsStateWithLifecycle()
    val currentBook by viewModel.currentBook.collectAsStateWithLifecycle()
    val canCreate by viewModel.canCreateEntry.collectAsStateWithLifecycle()
    val backupConfigured by backupViewModel.configured.collectAsStateWithLifecycle()
    val backupError by backupViewModel.errorState.collectAsStateWithLifecycle()

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    var showNewBookDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Entry?>(null) }
    var pendingDeleteBook by remember { mutableStateOf<Book?>(null) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            Sidebar(
                books = books,
                currentBookId = currentBook?.id,
                entries = entries,
                onOpenEntry = { id ->
                    scope.launch { drawerState.close() }
                    onOpenEntry(id)
                },
                onSelectBook = { viewModel.selectBook(it) },
                onCreateBook = { showNewBookDialog = true },
                onDeleteBookRequest = { pendingDeleteBook = it },
            )
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(currentBook?.name ?: stringResource(R.string.app_name)) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Outlined.Menu, contentDescription = stringResource(R.string.home_open_sidebar))
                        }
                    },
                    actions = {
                        IconButton(onClick = onOpenBackupSettings) {
                            Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.home_backup_settings))
                        }
                    },
                )
            },
            floatingActionButton = {
                // 既然不允许在无日记本时新建，就该真的不可点 ——
                // 只改颜色而仍可点击，等于让用户点下去什么也不会发生。
                if (canCreate) {
                    androidx.compose.material3.ExtendedFloatingActionButton(
                        onClick = { viewModel.createEntry(onOpenEntry) },
                        icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                        text = { Text(stringResource(R.string.editor_new_entry)) },
                    )
                }
            },
        ) { padding ->
            Column(Modifier.padding(padding)) {
                // 未配置备份时的常驻提示。不打断操作，但持续可见，
                // 让「日记仅存本机」这件事不被遗忘。
                if (!backupConfigured || backupError != null) {
                    BackupWarningBar(
                        isError = backupError != null,
                        message = backupError?.let {
                            stringResource(R.string.backup_warning_failed, it)
                        } ?: stringResource(R.string.backup_warning_unconfigured),
                        onAction = onOpenBackupSettings,
                    )
                }

                // 取出为局部变量，让编译器能智能转换 —— 用 currentBook!! 硬转
                // 在状态被并发改动时会直接崩溃，而这里只是取个名字显示。
                val book = currentBook
                when {
                    book == null -> NoBookState(Modifier.fillMaxSize())
                    entries.isEmpty() -> EmptyBookState(
                        bookName = book.name,
                        modifier = Modifier.fillMaxSize(),
                    )

                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 96.dp),
                    ) {
                        items(entries, key = { it.id }) { entry ->
                            EntryRow(
                                entry = entry,
                                modifier = Modifier.animateItemPlacement(),
                                onClick = { onOpenEntry(entry.id) },
                                onLongClick = { pendingDelete = entry },
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showNewBookDialog) {
        NameInputDialog(
            title = stringResource(R.string.book_new_title),
            label = stringResource(R.string.book_name_label),
            onDismiss = { showNewBookDialog = false },
            onConfirm = {
                viewModel.createBook(it) { id -> viewModel.selectBook(id) }
                showNewBookDialog = false
            },
        )
    }

    pendingDelete?.let { entry ->
        ConfirmDialog(
            title = stringResource(R.string.entry_delete_title),
            text = stringResource(R.string.entry_delete_body),
            onDismiss = { pendingDelete = null },
            onConfirm = { viewModel.deleteEntry(entry.id); pendingDelete = null },
        )
    }

    pendingDeleteBook?.let { book ->
        DeleteBookDialog(
            book = book,
            viewModel = viewModel,
            onDismiss = { pendingDeleteBook = null },
        )
    }
}

/**
 * 侧栏。
 *
 * 上半段 = 当前日记本的日记目录（顶替 0.1 的「全部日记」）
 * 下半段 = 日记本列表
 */
@Composable
private fun Sidebar(
    books: List<Book>,
    currentBookId: Long?,
    entries: List<Entry>,
    onOpenEntry: (Long) -> Unit,
    onSelectBook: (Long) -> Unit,
    onCreateBook: () -> Unit,
    onDeleteBookRequest: (Book) -> Unit,
) {
    ModalDrawerSheet {
        Column(Modifier.fillMaxSize()) {
            // ---------- 上半段：当前日记本的目录 ----------
            Text(
                text = stringResource(R.string.sidebar_index),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 28.dp, top = 24.dp, bottom = 4.dp),
            )

            if (entries.isEmpty()) {
                Text(
                    text = stringResource(R.string.sidebar_empty_book),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 28.dp, top = 8.dp),
                )
                Spacer(Modifier.weight(1f))
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    items(entries, key = { it.id }) { entry ->
                        NavigationDrawerItem(
                            label = {
                                Column {
                                    Text(
                                        text = entry.title.ifBlank { stringResource(R.string.common_untitled) },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        text = TimeFormat.listStamp(entry.createdAt),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            selected = false,
                            onClick = { onOpenEntry(entry.id) },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                }
            }

            // ---------- 下半段：日记本 ----------
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 28.dp, end = 8.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(R.string.sidebar_books),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = onCreateBook) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.sidebar_new_book))
                }
            }

            LazyColumn(Modifier.heightIn(max = 220.dp)) {
                items(books, key = { it.id }) { book ->
                    NavigationDrawerItem(
                        label = {
                            Text(
                                text = book.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = if (book.id == currentBookId) FontWeight.Bold else null,
                            )
                        },
                        selected = book.id == currentBookId,
                        onClick = { onSelectBook(book.id) },
                        badge = {
                            // 最后一个日记本不允许删除 —— 删了就无处安放日记
                            if (books.size > 1) {
                                IconButton(onClick = { onDeleteBookRequest(book) }) {
                                    Icon(
                                        Icons.Outlined.Delete,
                                        contentDescription = stringResource(R.string.sidebar_delete_book),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(
    entry: Entry,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val summary = remember(entry.body) { EntrySummary.of(entry) }
    androidx.compose.material3.ListItem(
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        headlineContent = {
            Text(
                entry.title.ifBlank { stringResource(R.string.common_untitled) },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Column {
                if (summary.isNotEmpty()) {
                    Text(summary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    TimeFormat.listStamp(entry.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

/**
 * 删除日记本的确认框。
 *
 * 文案必须写明两件事：日记不会被删除（会被迁往哪里），以及这是不可逆的。
 * 用户按 0.1 的习惯操作时，这条文案是他唯一的保护。
 */
@Composable
private fun DeleteBookDialog(
    book: Book,
    viewModel: HomeViewModel,
    onDismiss: () -> Unit,
) {
    val fallback = remember(book.id) { viewModel.fallbackBookId(book.id) }
    var entryCount by remember(book.id) { mutableStateOf<Int?>(null) }

    LaunchedEffect(book.id) {
        entryCount = viewModel.countEntriesInBook(book.id)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.book_delete_title, book.name)) },
        text = {
            val n = entryCount
            val moved = fallback?.let { id ->
                viewModel.books.value.firstOrNull { it.id == id }?.name
            }
            // 逐段取资源：整句拼接无法翻译 —— 不同语言的语序、量词、
            // 标点都不同，把它拆成可独立替换的片段才是可翻译的写法。
            Text(
                buildString {
                    if (n != null && n > 0) {
                        val target = if (moved != null) {
                            stringResource(R.string.book_delete_move_target, moved)
                        } else {
                            stringResource(R.string.book_delete_move_elsewhere)
                        }
                        append(stringResource(R.string.book_delete_with_entries, n, target))
                    } else {
                        append(stringResource(R.string.book_delete_empty))
                    }
                    append(stringResource(R.string.book_delete_final))
                }
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    fallback?.let { viewModel.deleteBook(book.id, it) }
                    onDismiss()
                },
                enabled = fallback != null,
            ) { Text(stringResource(R.string.common_delete)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** 一个日记本都没有。理论上不该出现（启动时会自动建一个），但必须处理，否则界面空白无解释。 */
@Composable
private fun NoBookState(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Text(stringResource(R.string.home_no_book_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.home_no_book_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 有当前日记本，但本内还没有日记。 */
@Composable
private fun EmptyBookState(bookName: String, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.home_empty_book_title, bookName), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.home_empty_book_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.common_delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
fun NameInputDialog(
    title: String,
    label: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value) },
                enabled = value.isNotBlank(),
            ) { Text(stringResource(R.string.common_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
