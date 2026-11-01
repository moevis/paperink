package io.github.moevis.paperink.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFFC0442F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF6DED8),
    onPrimaryContainer = Color(0xFF3D0F07),
    secondary = Color(0xFF4A5A66),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF4F2EC),
    onBackground = Color(0xFF1B1B18),
    surface = Color(0xFFFFFEFB),
    onSurface = Color(0xFF1B1B18),
    surfaceVariant = Color(0xFFECE9E0),
    onSurfaceVariant = Color(0xFF5C5A54),
    outline = Color(0xFFC9C5BA),
    outlineVariant = Color(0xFFDEDAD0),
    error = Color(0xFFB3261E),
    // 浮层/菜单用的容器色。不写这几档，M3 会回退到默认的偏紫灰，
    // 跟这套暖中性色并排放会显得脏（弹层、⋯ 菜单都吃这个）。
    surfaceDim = Color(0xFFDBD9D2),
    surfaceBright = Color(0xFFFFFEFB),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAF8F3),
    surfaceContainer = Color(0xFFF4F2EC),
    surfaceContainerHigh = Color(0xFFEEEBE4),
    surfaceContainerHighest = Color(0xFFE7E4DC),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFE26A50),
    onPrimary = Color(0xFF2A120C),
    primaryContainer = Color(0xFF5A2418),
    onPrimaryContainer = Color(0xFFFFDBD2),
    secondary = Color(0xFFA9BDCB),
    onSecondary = Color(0xFF13242E),
    background = Color(0xFF121211),
    onBackground = Color(0xFFEAE7DF),
    surface = Color(0xFF1C1C1A),
    onSurface = Color(0xFFEAE7DF),
    surfaceVariant = Color(0xFF262623),
    onSurfaceVariant = Color(0xFFA3A09A),
    outline = Color(0xFF3A3934),
    outlineVariant = Color(0xFF302F2C),
    error = Color(0xFFF2B8B5),
    // 见上面浅色主题的同名注释：暖中性色，不用 M3 默认的紫灰
    surfaceDim = Color(0xFF141413),
    surfaceBright = Color(0xFF3B3A35),
    surfaceContainerLowest = Color(0xFF171716),
    surfaceContainerLow = Color(0xFF20201E),
    surfaceContainer = Color(0xFF262623),
    surfaceContainerHigh = Color(0xFF30302C),
    surfaceContainerHighest = Color(0xFF3B3A35),
)

@Composable
fun PaperTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        typography = Typography(),
        content = content,
    )
}
