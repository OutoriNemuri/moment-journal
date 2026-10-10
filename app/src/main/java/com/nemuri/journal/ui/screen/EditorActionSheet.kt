package com.nemuri.journal.ui.screen

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.nemuri.journal.R
import com.nemuri.journal.export.Exporter

/**
 * 编辑界面右上角的功能抽屉，以及它展开的四个二级抽屉。
 *
 * ## 形式：下侧抽屉
 * 全部用 `ModalBottomSheet`，即从屏幕下方滑出的抽屉。
 * 不用下拉菜单：四项各自还有二级选择（复制的粒度、查找的范围、导出的格式），
 * 下拉菜单在手机上会挤成一长条，且二级选择无处安放。
 *
 * ## 为什么二级也做成抽屉而不是对话框
 * 二级里的选项多是**点击即执行**（选 TXT 就导出 TXT），不是「先选中再确认」。
 * 做成对话框会让人以为还要按一次确定，多一次无谓的交互。
 * 唯一的例外是「修改创建时间」——那需要日期与时间两步选择，无法一蹴而就。
 */
/**
 * 抽屉里的四项操作。
 *
 * 标签与描述存的是字符串资源 id 而不是字面量：枚举是编译期常量，
 * 拿不到 Composable 的 stringResource，因此在这里存 id、在使用处解析。
 * [BACKUP] 不属于「编辑日记」的四项，但因为占位状态下抽屉仍需可用，
 * 所以与它们并列。
 */
enum class EditorAction(
    @StringRes val labelRes: Int,
    @StringRes val descriptionRes: Int,
) {
    COPY(R.string.action_copy, R.string.action_copy_desc),
    SEARCH(R.string.action_search, R.string.action_search_desc),
    EXPORT(R.string.action_export, R.string.action_export_desc),
    CHANGE_TIME(R.string.action_change_time, R.string.action_change_time_desc),
    BACKUP(R.string.action_backup, R.string.action_backup_desc),
}

private fun EditorAction.icon(): ImageVector = when (this) {
    EditorAction.COPY -> Icons.Outlined.ContentCopy
    EditorAction.SEARCH -> Icons.Outlined.Search
    EditorAction.EXPORT -> Icons.Outlined.FileDownload
    EditorAction.CHANGE_TIME -> Icons.Outlined.Schedule
    // 备份：用云上传表示「存到设备之外」，与「导出」的下载箭头区分开
    EditorAction.BACKUP -> Icons.Outlined.CloudUpload
}

/** 一级抽屉：四项功能入口。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorActionSheet(
    onDismiss: () -> Unit,
    onAction: (EditorAction) -> Unit,
    /**
     * 是否已打开一篇日记。
     *
     * 为 false 时（占位状态）只显示 [EditorAction.BACKUP] ——
     * 其余四项都需要一篇已打开的日记，显示出来点了也没意义。
     * 备份与日记无关，因此在任何状态下都可用。
     */
    entryOpen: Boolean,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        SheetBody(title = stringResource(R.string.action_sheet_title)) {
            EditorAction.entries
                .filter { entryOpen || it == EditorAction.BACKUP }
                .forEach { action ->
                ListItem(
                    headlineContent = { Text(stringResource(action.labelRes)) },
                    supportingContent = {
                        Text(
                            stringResource(action.descriptionRes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    leadingContent = { Icon(action.icon(), contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().clickable { onAction(action) },
                )
            }
        }
    }
}

/** 二级抽屉：复制。两种粒度二选一，选中即执行。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CopySheet(
    onDismiss: () -> Unit,
    onConfirm: (includeHeader: Boolean) -> Unit,
) {
    var includeHeader by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        SheetBody(title = stringResource(R.string.copy_title)) {
            RadioRow(stringResource(R.string.copy_body_only), !includeHeader) { includeHeader = false }
            RadioRow(stringResource(R.string.copy_with_header), includeHeader) { includeHeader = true }
            Hint(stringResource(R.string.copy_hint))
            SheetActions(
                confirmLabel = stringResource(R.string.common_confirm),
                onConfirm = { onConfirm(includeHeader) },
                onDismiss = onDismiss,
            )
        }
    }
}

/** 二级抽屉：导出。四项点击即执行。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(
    onDismiss: () -> Unit,
    onText: (Exporter.TextFormat) -> Unit,
    onImage: (slice: Boolean) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        SheetBody(title = stringResource(R.string.export_title)) {
            SectionLabel(stringResource(R.string.export_section_text))
            ActionRow(stringResource(R.string.export_txt), stringResource(R.string.export_txt_desc)) { onText(Exporter.TextFormat.TXT) }
            ActionRow(stringResource(R.string.export_md), stringResource(R.string.export_md_desc)) { onText(Exporter.TextFormat.MARKDOWN) }

            SectionLabel(stringResource(R.string.export_section_image))
            ActionRow(stringResource(R.string.export_long_image), stringResource(R.string.export_long_image_desc)) { onImage(false) }
            ActionRow(stringResource(R.string.export_sliced_image), stringResource(R.string.export_sliced_image_desc)) { onImage(true) }

            Hint(stringResource(R.string.export_hint))
        }
    }
}

/**
 * 二级抽屉：查找。
 *
 * 查找有独立的结果页（[SearchScreen]），这里只负责选范围并进入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScopeSheet(
    onDismiss: () -> Unit,
    onPick: (SearchViewModel.Scope) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        SheetBody(title = stringResource(R.string.search_scope_title)) {
            ActionRow(stringResource(R.string.search_scope_entry), stringResource(R.string.search_scope_entry_desc)) {
                onPick(SearchViewModel.Scope.CURRENT_ENTRY)
            }
            ActionRow(stringResource(R.string.search_scope_book), stringResource(R.string.search_scope_book_desc)) {
                onPick(SearchViewModel.Scope.CURRENT_BOOK)
            }
            ActionRow(stringResource(R.string.search_scope_all), stringResource(R.string.search_scope_all_desc)) {
                onPick(SearchViewModel.Scope.ALL_BOOKS)
            }
            Hint(stringResource(R.string.search_scope_hint))
        }
    }
}

// ------------------------------------------------------------------ 公共部件

@Composable
private fun SheetBody(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 24.dp, top = 4.dp, bottom = 8.dp),
        )
        content()
    }
}

@Composable
private fun SectionLabel(text: String) {
    Spacer(Modifier.height(12.dp))
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 24.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 4.dp),
    )
}

/** 点击即执行的行。 */
@Composable
private fun ActionRow(label: String, description: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = {
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}

/** 二选一的行。整行可点，不是只有单选按钮可点。 */
@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
    )
}

@Composable
private fun SheetActions(
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        TextButton(onClick = onConfirm) { Text(confirmLabel) }
    }
}
