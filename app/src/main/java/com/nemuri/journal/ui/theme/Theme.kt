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

private val PaperLight = lightColorScheme(
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

private val InkDark = darkColorScheme(
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

@Composable
fun MomentJournalTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** 跟随系统取色。Android 12+ 可用，默认关闭以保证品牌观感一致。 */
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }

        darkTheme -> InkDark
        else -> PaperLight
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
