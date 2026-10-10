package com.nemuri.journal.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nemuri.journal.R
import com.nemuri.journal.data.db.Book
import com.nemuri.journal.data.db.Entry
import com.nemuri.journal.util.TimeFormat

/**
 * 侧栏内容。
 *
 * 上半段 = 当前日记本的日记目录
 * 下半段 = 日记本列表
 *
 * **容器由调用方决定。** 手机（含横屏）用 `ModalNavigationDrawer` 从左侧划出、
 * 覆盖在内容上；平板常驻并排。两条路径共用这一份内容函数，
 * 因此形态差异只体现在外层容器，不涉及内容。
 */
@Composable
fun Sidebar(
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
