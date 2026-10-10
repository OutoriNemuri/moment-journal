package com.nemuri.journal.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nemuri.journal.R
import com.nemuri.journal.util.TimeFormat
import java.time.Instant
import java.time.ZoneId

/**
 * 修改创建时间。
 *
 * 用系统日期选择器 + 时间选择器两步完成，而不是自己造一个选择器。
 * 改完后日记会按新的创建时间在列表里重新排序，这一点必须写在界面上，
 * 否则用户会以为日记丢了。
 */
@Composable
fun ChangeTimeDialog(
    currentMillis: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    var millis by remember { mutableStateOf(currentMillis) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    val zone = ZoneId.systemDefault()
    val displayed = Instant.ofEpochMilli(millis).atZone(zone)

    if (showDatePicker) {
        android.app.DatePickerDialog(
            androidx.compose.ui.platform.LocalContext.current,
            { _, y, m, d ->
                // displayed 是 ZonedDateTime，没有 withMonthValue；
                // 月份用 withMonth（1-12），注意系统选出来的 m 是 0-11。
                millis = displayed
                    .withYear(y)
                    .withMonth(m + 1)
                    .withDayOfMonth(d)
                    .toInstant()
                    .toEpochMilli()
                showDatePicker = false
            },
            displayed.year, displayed.monthValue - 1, displayed.dayOfMonth,
        ).apply {
            // 限制在合理区间：不早于 1970，不晚于明天。
            // 允许改到未来（用户可能就是在预写），但不放任到任意远的未来。
            datePicker.minDate = 0L
            datePicker.maxDate = System.currentTimeMillis() + 24L * 3600 * 1000
        }.show()
    }

    if (showTimePicker) {
        android.app.TimePickerDialog(
            androidx.compose.ui.platform.LocalContext.current,
            { _, h, minute ->
                millis = displayed.withHour(h).withMinute(minute).toInstant().toEpochMilli()
                showTimePicker = false
            },
            displayed.hour, displayed.minute, true,
        ).show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.change_time_title)) },
        text = {
            Column {
                Text(
                    TimeFormat.listStamp(millis),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = false,
                        onClick = { showDatePicker = true },
                        label = { Text(stringResource(R.string.change_time_pick_date)) },
                    )
                    FilterChip(
                        selected = false,
                        onClick = { showTimePicker = true },
                        label = { Text(stringResource(R.string.change_time_pick_time)) },
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.change_time_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(millis) }) { Text(stringResource(R.string.common_confirm)) } },
        dismissButton = {
            TextButton(onClick = {
                millis = currentMillis   // 允许还原
                onDismiss()
            }) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/** 单选项行。整行可点，而不是只有单选按钮可点 —— 后者在手机上很难命中。 */
