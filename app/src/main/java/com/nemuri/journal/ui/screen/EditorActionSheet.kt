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
import kotlin.math.roundToInt
import androidx.compose.ui.res.stringResource
import com.nemuri.journal.R
import com.nemuri.journal.export.Exporter
import com.nemuri.journal.export.SliceStrategy

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
 * 标签存的是字符串资源 id 而不是字面量：枚举是编译期常量，
 * 拿不到 Composable 的 stringResource，因此在这里存 id、在使用处解析。
 *
 * 这四项都是**对当前这篇日记的操作**。备份不属于这里 —— 它不操作日记，
 * 已移入独立的「设置」页。
 */
enum class EditorAction(@StringRes val labelRes: Int) {
    COPY(R.string.action_copy),
    SEARCH(R.string.action_search),
    EXPORT(R.string.action_export),
    CHANGE_TIME(R.string.action_change_time),
}

private fun EditorAction.icon(): ImageVector = when (this) {
    EditorAction.COPY -> Icons.Outlined.ContentCopy
    EditorAction.SEARCH -> Icons.Outlined.Search
    EditorAction.EXPORT -> Icons.Outlined.FileDownload
    EditorAction.CHANGE_TIME -> Icons.Outlined.Schedule
}

/** 一级抽屉：功能入口，双列网格。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorActionSheet(
    onDismiss: () -> Unit,
    onAction: (EditorAction) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        SheetBody(title = stringResource(R.string.action_sheet_title)) {
            // 双列网格，去掉了每项的说明文字。
            //
            // 说明在单列布局里占了抽屉一半以上的高度，而四项的名称本身
            // 已经足够表意（"复制""查找""导出""时间"）。说明只在用户
            // 不确定某项做什么时才有价值，而带图标的网格更容易扫视。
            Column(Modifier.padding(horizontal = 16.dp)) {
                EditorAction.entries.chunked(2).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        row.forEach { action ->
                            GridActionCell(
                                action = action,
                                modifier = Modifier.weight(1f),
                                onClick = { onAction(action) },
                            )
                        }
                        // 奇数个时补一个占位，避免最后一个被拉伸到整行
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

/** 网格中的一个功能格。图标在上、名称在下。 */
@Composable
private fun GridActionCell(
    action: EditorAction,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    androidx.compose.material3.Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(action.icon(), contentDescription = null)
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(action.labelRes),
                style = MaterialTheme.typography.labelLarge,
            )
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
    onImage: (SliceStrategy) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        SheetBody(title = stringResource(R.string.export_title)) {
            SectionLabel(stringResource(R.string.export_section_text))
            ActionRow(stringResource(R.string.export_txt), stringResource(R.string.export_txt_desc)) { onText(Exporter.TextFormat.TXT) }
            ActionRow(stringResource(R.string.export_md), stringResource(R.string.export_md_desc)) { onText(Exporter.TextFormat.MARKDOWN) }

            SectionLabel(stringResource(R.string.export_section_image))
            // 不切
            ActionRow(
                stringResource(R.string.export_long_image),
                stringResource(R.string.export_long_image_desc),
            ) { onImage(SliceStrategy.None) }

            // 分成 N 张。张数直接列出来，省掉再进一层选择。
            COUNT_OPTIONS.forEach { n ->
                ActionRow(
                    stringResource(R.string.export_slice_count, n),
                    stringResource(R.string.export_slice_count_desc),
                ) { onImage(SliceStrategy.Count(n)) }
            }

            // 按屏幕物理高度切分
            ActionRow(
                stringResource(R.string.export_slice_screen),
                stringResource(R.string.export_slice_screen_desc),
            ) { onImage(SliceStrategy.Screen(screenHeightPx())) }

            // 按固定比例
            ActionRow(
                stringResource(R.string.export_slice_ratio, "4:3"),
                stringResource(R.string.export_slice_ratio_desc),
            ) { onImage(SliceStrategy.RATIO_4_3) }
            ActionRow(
                stringResource(R.string.export_slice_ratio, "5:3"),
                stringResource(R.string.export_slice_ratio_desc),
            ) { onImage(SliceStrategy.RATIO_5_3) }

            Hint(stringResource(R.string.export_hint))
        }
    }
}

/**
 * 屏幕物理高度，像素。
 *
 * 用物理高度而非可用高度：这样每张导出图与屏幕等宽等高，
 * 「逐屏查看」时图片与屏幕一一对应，不会因多出/少掉状态栏而错位。
 */
@Composable
private fun screenHeightPx(): Int {
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    // 用 density 直接换算，不引 dp 扩展 —— 避免在这个小函数里堆导入
    return (config.screenHeightDp * density.density).roundToInt()
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

/**
 * 点击即执行的行，带一句说明。
 *
 * 一级抽屉改成了双列网格（功能入口，无说明），但**二级抽屉保持这种带说明的行**：
 * 那里列的是选项（导出格式、查找范围），选项之间的差别需要一句话讲清，
 * 而且它们是纵向列表而非可扫视的图标网格。两者形态不同是因为用途不同。
 */
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
