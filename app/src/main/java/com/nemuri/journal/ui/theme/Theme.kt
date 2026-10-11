package com.nemuri.journal.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * 配色方案。
 *
 * ## 与之前的不同
 * 此前只有一套硬编码配色（绿），`dynamicColor` 开关虽然存在但从未启用。
 * 现在把「用哪套色」与「深浅模式」拆成两个独立选择，各自可配。
 *
 * ## 为什么内置配色至少保留两套
 * 动态取色只在 **Android 12+** 可用，低版本（本项目 minSdk 28）必须有降级，
 * 否则低版本用户会看到另一套界面。因此内置配色不是"备选项"，
 * 而是低版本的**唯一选项**，必须有。
 */
enum class AppColorScheme {
    /** 品牌绿。默认，也是低版本的降级方案之一。 */
    GREEN,

    /** 品牌蓝。与图标同色（#1B4F9C）。 */
    BLUE,

    /**
     * 动态取色，跟随壁纸。
     *
     * 仅 Android 12+ 有效；低版本自动退回到 [GREEN]，
     * 且设置页里该项应置灰并说明原因，而不是选了没反应。
     */
    DYNAMIC,
}

/** 深浅模式。 */
enum class AppDarkMode {
    /** 跟随系统的深色设置。 */
    SYSTEM,
    LIGHT,
    DARK,
}

// ------------------------------------------------------------------ 配色定义

private val GreenLight = lightColorScheme(
    primary = Color(0xFF4A6350),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCCE8D2),
    onPrimaryContainer = Color(0xFF07200E),
    secondary = Color(0xFF4E6355),
    surface = Color(0xFFFBFDF8),
    onSurface = Color(0xFF191C1A),
    surfaceVariant = Color(0xFFDEE5DE),
    onSurfaceVariant = Color(0xFF424941),
    background = Color(0xFFFBFDF8),
    outlineVariant = Color(0xFFC2C9C2),
)

private val GreenDark = darkColorScheme(
    primary = Color(0xFFB1D0B7),
    onPrimary = Color(0xFF1C3525),
    primaryContainer = Color(0xFF334C3A),
    onPrimaryContainer = Color(0xFFCCE8D2),
    secondary = Color(0xFFB5CCBA),
    surface = Color(0xFF111411),
    onSurface = Color(0xFFE1E3DF),
    surfaceVariant = Color(0xFF424941),
    onSurfaceVariant = Color(0xFFC2C9C2),
    background = Color(0xFF111411),
    outlineVariant = Color(0xFF424941),
)

/**
 * 品牌蓝。与图标背景同色（#1B4F9C）。
 *
 * 深色下的 primary 用浅蓝 —— 深蓝在白字上对比度不足，
 * 而深色主题里 primary 是**前景色**（文字、图标），必须是亮的。
 */
private val BlueLight = lightColorScheme(
    primary = Color(0xFF1B4F9C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E3FF),
    onPrimaryContainer = Color(0xFF001A41),
    secondary = Color(0xFF40548A),
    surface = Color(0xFFFBF9FC),
    onSurface = Color(0xFF1A1B20),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF44464F),
    background = Color(0xFFFBF9FC),
    outlineVariant = Color(0xFFC5C6D0),
)

private val BlueDark = darkColorScheme(
    primary = Color(0xFFADC6FF),
    onPrimary = Color(0xFF002E69),
    primaryContainer = Color(0xFF004494),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFFB9C3DD),
    surface = Color(0xFF121318),
    onSurface = Color(0xFFE3E2E9),
    surfaceVariant = Color(0xFF44464F),
    onSurfaceVariant = Color(0xFFC5C6D0),
    background = Color(0xFF121318),
    outlineVariant = Color(0xFF44464F),
)

/** 动态取色在本机是否可用。 */
fun isDynamicColorAvailable(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * 解析出实际要用的配色。
 *
 * 单独抽成函数是为了让「设置页显示什么」与「主题实际用什么」共用同一套判断 ——
 * 否则设置页可能显示"动态取色"已选中，而主题因为版本不够悄悄用了绿色。
 */
@Composable
fun resolveColorScheme(
    colorScheme: AppColorScheme,
    dark: Boolean,
): androidx.compose.material3.ColorScheme {
    val context = LocalContext.current
    return when (colorScheme) {
        AppColorScheme.DYNAMIC -> {
            if (isDynamicColorAvailable()) {
                if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else {
                // 低版本降级。设置页会把该项置灰，所以正常走不到这里，
                // 但用户可能在旧设备上恢复了一份来自新设备的配置。
                if (dark) GreenDark else GreenLight
            }
        }

        AppColorScheme.GREEN -> if (dark) GreenDark else GreenLight
        AppColorScheme.BLUE -> if (dark) BlueDark else BlueLight
    }
}

/** 解析深浅模式。 */
@Composable
fun resolveDark(darkMode: AppDarkMode): Boolean = when (darkMode) {
    AppDarkMode.SYSTEM -> isSystemInDarkTheme()
    AppDarkMode.LIGHT -> false
    AppDarkMode.DARK -> true
}

@Composable
fun MomentJournalTheme(
    colorScheme: AppColorScheme = AppColorScheme.GREEN,
    darkMode: AppDarkMode = AppDarkMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = resolveDark(darkMode)
    MaterialTheme(colorScheme = resolveColorScheme(colorScheme, dark), content = content)
}
