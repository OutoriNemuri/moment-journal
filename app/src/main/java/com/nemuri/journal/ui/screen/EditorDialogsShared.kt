package com.nemuri.journal.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.nemuri.journal.R
import com.nemuri.journal.data.db.Book

@OptIn(ExperimentalFoundationApi::class)
/**
 * 删除日记本的确认框。
 *
 * 文案必须写明两件事：日记不会被删除（会被迁往哪里），以及这是不可逆的。
 * 用户按 0.1 的习惯操作时，这条文案是他唯一的保护。
 */
@Composable
fun DeleteBookDialog(
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
/** 有当前日记本，但本内还没有日记。 */
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
