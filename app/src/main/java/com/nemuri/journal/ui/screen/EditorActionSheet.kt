package com.nemuri.journal.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
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
enum class EditorAction(val label: String, val description: String) {
    COPY("复制日记", "复制正文，或标题 + 正文 + 创建时间"),
    SEARCH("查找", "在本篇、本日记本或所有日记本中搜索"),
    EXPORT("导出", "导出为 TXT、Markdown、长图或切分短图"),
    CHANGE_TIME("时间", "修改这篇日记的创建时间"),
}

private fun EditorAction.icon(): ImageVector = when (this) {
    EditorAction.COPY -> Icons.Outlined.ContentCopy
    EditorAction.SEARCH -> Icons.Outlined.Search
    EditorAction.EXPORT -> Icons.Outlined.FileDownload
    EditorAction.CHANGE_TIME -> Icons.Outlined.Schedule
}

/** 一级抽屉：四项功能入口。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorActionSheet(
    onDismiss: () -> Unit,
    onAction: (EditorAction) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        SheetBody(title = "日记操作") {
            EditorAction.entries.forEach { action ->
                ListItem(
                    headlineContent = { Text(action.label) },
                    supportingContent = {
                        Text(
                            action.description,
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
        SheetBody(title = "复制日记") {
            RadioRow("仅正文", !includeHeader) { includeHeader = false }
            RadioRow("标题 + 正文 + 创建时间", includeHeader) { includeHeader = true }
            Hint("复制的是 Markdown 源文本，插入的时间、地点与图片都会保留，粘回本应用任意一篇日记都能还原。")
            SheetActions(
                confirmLabel = "复制",
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
        SheetBody(title = "导出") {
            SectionLabel("文本")
            ActionRow("纯文本 (.txt)", "嵌入标记转为可读文字") { onText(Exporter.TextFormat.TXT) }
            ActionRow("Markdown (.md)", "保留源文本与全部标记") { onText(Exporter.TextFormat.MARKDOWN) }

            SectionLabel("图片")
            ActionRow("一张长图", "完整的一屏纵向长图") { onImage(false) }
            ActionRow("切分的几张短图", "分成固定高度的数张，便于逐张查看") { onImage(true) }

            Hint("内容过高的日记会自动切分，避免占用过多内存。")
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
        SheetBody(title = "查找范围") {
            ActionRow("本篇日记", "只在当前编辑的这篇里查找") {
                onPick(SearchViewModel.Scope.CURRENT_ENTRY)
            }
            ActionRow("本日记本", "在当前打开的日记本内查找") {
                onPick(SearchViewModel.Scope.CURRENT_BOOK)
            }
            ActionRow("所有日记本", "跨全部日记本查找，结果是一次性列表") {
                onPick(SearchViewModel.Scope.ALL_BOOKS)
            }
            Hint("跨日记本查找不会改变当前打开的日记本，关闭结果即结束。")
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
        TextButton(onClick = onDismiss) { Text("取消") }
        TextButton(onClick = onConfirm) { Text(confirmLabel) }
    }
}
