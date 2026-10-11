package com.nemuri.journal.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nemuri.journal.R
import com.nemuri.journal.ui.theme.AppColorScheme
import com.nemuri.journal.ui.theme.AppDarkMode
import com.nemuri.journal.ui.theme.isDynamicColorAvailable

/**
 * 主题设置。
 *
 * 两个独立的选项：用哪套配色、深还是浅。
 * 此前这两件事被混在一个 `dynamicColor: Boolean` 里，只能表达"跟随壁纸与否"。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSettingsScreen(
    colorScheme: AppColorScheme,
    darkMode: AppDarkMode,
    onColorSchemeChange: (AppColorScheme) -> Unit,
    onDarkModeChange: (AppDarkMode) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.theme_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader(stringResource(R.string.theme_section_color))

            AppColorScheme.entries.forEach { scheme ->
                // 动态取色在低版本不可用。**置灰并说明原因**，
                // 而不是让用户选了没反应 —— 后者会让人以为功能坏了。
                val unavailable = scheme == AppColorScheme.DYNAMIC && !isDynamicColorAvailable()
                ChoiceRow(
                    label = stringResource(scheme.labelRes()),
                    description = if (unavailable) {
                        stringResource(R.string.theme_dynamic_unavailable)
                    } else {
                        null
                    },
                    selected = colorScheme == scheme,
                    enabled = !unavailable,
                    onClick = { onColorSchemeChange(scheme) },
                )
            }

            SectionHeader(stringResource(R.string.theme_section_dark))

            AppDarkMode.entries.forEach { mode ->
                ChoiceRow(
                    label = stringResource(mode.labelRes()),
                    description = null,
                    selected = darkMode == mode,
                    enabled = true,
                    onClick = { onDarkModeChange(mode) },
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Spacer(Modifier.height(16.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
    )
}

/** 单选项。整行可点，不可用时置灰并显示原因。 */
@Composable
private fun ChoiceRow(
    label: String,
    description: String?,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val alpha = if (enabled) 1f else 0.38f
    ListItem(
        headlineContent = {
            Text(
                label,
                modifier = Modifier.padding(0.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            )
        },
        supportingContent = description?.let {
            {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                )
            }
        },
        trailingContent = {
            RadioButton(selected = selected, onClick = null, enabled = enabled)
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
    )
    HorizontalDivider(
        modifier = Modifier.padding(start = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

private fun AppColorScheme.labelRes(): Int = when (this) {
    AppColorScheme.GREEN -> R.string.theme_color_green
    AppColorScheme.BLUE -> R.string.theme_color_blue
    AppColorScheme.DYNAMIC -> R.string.theme_color_dynamic
}

private fun AppDarkMode.labelRes(): Int = when (this) {
    AppDarkMode.SYSTEM -> R.string.theme_dark_system
    AppDarkMode.LIGHT -> R.string.theme_dark_light
    AppDarkMode.DARK -> R.string.theme_dark_dark
}
