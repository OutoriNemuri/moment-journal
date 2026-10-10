package com.nemuri.journal.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nemuri.journal.data.db.Entry
import com.nemuri.journal.util.TimeFormat
import kotlinx.coroutines.launch

/**
 * 日记列表 = 应用默认界面。
 *
 * 左侧栏的"日记本"区域在抽屉底部，符合企划描述。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenEntry: (Long) -> Unit,
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val books by viewModel.books.collectAsStateWithLifecycle()
    val selectedBookId by viewModel.selectedBookId.collectAsStateWithLifecycle()

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    var showNewBookDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Entry?>(null) }
    var pendingDeleteBook by remember { mutableStateOf<Long?>(null) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            BookDrawer(
                books = books,
                selectedBookId = selectedBookId,
                onSelect = { viewModel.selectBook(it); scope.launch { drawerState.close() } },
                onCreate = { showNewBookDialog = true },
                onDeleteRequest = { pendingDeleteBook = it },
            )
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        val name = books.firstOrNull { it.id == selectedBookId }?.name
                        Text(name ?: "日记")
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Outlined.Menu, contentDescription = "打开日记本")
                        }
                    },
                )
            },
            floatingActionButton = {
                androidx.compose.material3.ExtendedFloatingActionButton(
                    onClick = { viewModel.createEntry(onOpenEntry) },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("写日记") },
                )
            },
        ) { padding ->
            if (entries.isEmpty()) {
                EmptyState(Modifier.padding(padding).fillMaxSize())
            } else {
                LazyColumn(
                    modifier = Modifier.padding(padding).fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp),
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

    if (showNewBookDialog) {
        NameInputDialog(
            title = "新建日记本",
            label = "名称",
            onDismiss = { showNewBookDialog = false },
            onConfirm = { viewModel.createBook(it); showNewBookDialog = false },
        )
    }

    pendingDelete?.let { entry ->
        ConfirmDialog(
            title = "删除这篇日记？",
            text = "删除后无法从应用内恢复。若已配置备份，可从 .mjb 归档找回。",
            onDismiss = { pendingDelete = null },
            onConfirm = { viewModel.deleteEntry(entry.id); pendingDelete = null },
        )
    }

    pendingDeleteBook?.let { id ->
        ConfirmDialog(
            title = "删除这个日记本？",
            text = "日记本会被删除，其中的日记将退回「未分类」，不会丢失。",
            onDismiss = { pendingDeleteBook = null },
            onConfirm = { viewModel.deleteBook(id); pendingDeleteBook = null },
        )
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
    ListItem(
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        headlineContent = {
            val title = entry.title.ifBlank { "无标题" }
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
 * 侧栏抽屉。日记本区域在底部，与企划的"底部日记本区域拉出抽屉"一致。
 */
@Composable
private fun BookDrawer(
    books: List<com.nemuri.journal.data.db.Book>,
    selectedBookId: Long?,
    onSelect: (Long?) -> Unit,
    onCreate: () -> Unit,
    onDeleteRequest: (Long) -> Unit,
) {
    ModalDrawerSheet {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(24.dp))
            NavigationDrawerItem(
                label = { Text("全部日记") },
                icon = { Icon(Icons.Outlined.Bookmarks, contentDescription = null) },
                selected = selectedBookId == null,
                onClick = { onSelect(null) },
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            Spacer(Modifier.weight(1f))

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 28.dp, end = 8.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "日记本",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = onCreate) {
                    Icon(Icons.Filled.Add, contentDescription = "新建日记本")
                }
            }

            LazyColumn(Modifier.height(240.dp)) {
                items(books, key = { it.id }) { book ->
                    NavigationDrawerItem(
                        label = {
                            Text(book.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        selected = selectedBookId == book.id,
                        onClick = { onSelect(book.id) },
                        badge = {
                            IconButton(onClick = { onDeleteRequest(book.id) }) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = "删除日记本",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
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

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("还没有日记", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "点击右下角开始记录今天",
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
        confirmButton = { TextButton(onClick = onConfirm) { Text("删除") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
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
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
