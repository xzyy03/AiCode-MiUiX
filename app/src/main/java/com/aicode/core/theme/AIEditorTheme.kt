package com.aicode.core.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

object Radius {
    val xs = 6.dp
    val sm = 10.dp
    val md = 12.dp
    val mdLarge = 14.dp
    val lg = 18.dp
    val xl = 20.dp
    val pill = 999.dp
}

object Brand {
    val Blue = Color(0xFF3482FF)
    val Sky = Color(0xFF5AC8FA)
    val IconGray = Color(0xFF8A8A8E)
    val Orange = Color(0xFFFF8A00)
}

/** 全局统一语义色彩，解决业务代码私自 hardcode 颜色问题。 */
data class AppSemanticColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val info: Color,
    val onInfo: Color,
    val infoContainer: Color,
    val onInfoContainer: Color,
    val diffAdd: Color,
    val diffAddBg: Color,
    val diffRemove: Color,
    val diffRemoveBg: Color,
    val subtleText: Color,
    val subtleBorder: Color,
    val cardSurface: Color,
    val pageBackground: Color,
    val mutedSurface: Color,
    val capsuleSurface: Color,
    val buttonMutedBg: Color
)

val LightSemanticColors = AppSemanticColors(
    success = Color(0xFF00B34A),
    onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFFE6F7ED),
    onSuccessContainer = Color(0xFF0A7F38),
    warning = Color(0xFFFF8A00),
    onWarning = Color(0xFFFFFFFF),
    warningContainer = Color(0xFFFFF3E0),
    onWarningContainer = Color(0xFF8A4B00),
    info = Color(0xFF3482FF),
    onInfo = Color(0xFFFFFFFF),
    infoContainer = Color(0xFFE8F1FF),
    onInfoContainer = Color(0xFF0B4FD8),
    diffAdd = Color(0xFF00B34A),
    diffAddBg = Color(0x1F00B34A),
    diffRemove = Color(0xFFE54545),
    diffRemoveBg = Color(0x1FE54545),
    subtleText = Color(0xFF9A9A9E),
    subtleBorder = Color(0xFFECECEE),
    cardSurface = Color(0xFFFFFFFF),
    pageBackground = Color(0xFFF5F5F6),
    mutedSurface = Color(0xFFF2F2F3),
    capsuleSurface = Color(0xFFF0F0F2),
    buttonMutedBg = Color(0xFFF0F0F2)
)

val DarkSemanticColors = AppSemanticColors(
    success = Color(0xFF32D74B),
    onSuccess = Color(0xFF052E16),
    successContainer = Color(0x3D32D74B),
    onSuccessContainer = Color(0xFFA8E6B8),
    warning = Color(0xFFFFB340),
    onWarning = Color(0xFF3D2600),
    warningContainer = Color(0x3DFFB340),
    onWarningContainer = Color(0xFFFFD79A),
    info = Color(0xFF4A8DFF),
    onInfo = Color(0xFF04102B),
    infoContainer = Color(0x3D4A8DFF),
    onInfoContainer = Color(0xFFB7D2FF),
    diffAdd = Color(0xFF32D74B),
    diffAddBg = Color(0x3332D74B),
    diffRemove = Color(0xFFFF6B6B),
    diffRemoveBg = Color(0x33FF6B6B),
    subtleText = Color(0xFF8E8E93),
    subtleBorder = Color(0xFF2C2C30),
    cardSurface = Color(0xFF1A1A1D),
    pageBackground = Color(0xFF000000),
    mutedSurface = Color(0xFF232326),
    capsuleSurface = Color(0xFF2A2A2E),
    buttonMutedBg = Color(0xFF252528)
)

val LocalAppSemanticColors = staticCompositionLocalOf { LightSemanticColors }

val MaterialTheme.semanticColors: AppSemanticColors
    @Composable
    @ReadOnlyComposable
    get() = LocalAppSemanticColors.current

/** Git 模块统一 8 色泳道调色板 */
object GitLanePalette {
    val colors = listOf(
        Color(0xFF2563EB), // 1. 经典蓝
        Color(0xFF16A34A), // 2. 翠绿
        Color(0xFFF59E0B), // 3. 琥珀
        Color(0xFF8B5CF6), // 4. 优雅紫
        Color(0xFF06B6D4), // 5. 青蓝
        Color(0xFFEF4444), // 6. 珊瑚红
        Color(0xFFEC4899), // 7. 亮粉
        Color(0xFFEAB308)  // 8. 柠檬金
    )
}

/** Git 状态徽章统一色彩映射 */
object GitStatusColors {
    val Added = Color(0xFF16A34A)
    val Modified = Color(0xFFD97706)
    val Deleted = Color(0xFFDC2626)
    val Renamed = Color(0xFF2563EB)
    val Untracked = Color(0xFF94A3B8)
    val Conflict = Color(0xFF9333EA)
    val TypeChanged = Color(0xFF0891B2)
    val Default = Color(0xFF64748B)
}

/** 日志级别统一色彩映射 */
object LogLevelColors {
    val Verbose = Color(0xFF94A3B8)
    val Debug = Color(0xFF3B82F6)
    val Info = Color(0xFF22C55E)
    val Warn = Color(0xFFF59E0B)
    val Error = Color(0xFFEF4444)
    val None = Color(0xFF64748B)
}

/** Token / 统计图表统一调色板 */
object TokenStatsPalette {
    val Input = Color(0xFF3B82F6)
    val Output = Color(0xFFF59E0B)
    val Cache = Color(0xFF8B5CF6)
    val Error = Color(0xFFEF4444)
    val Cancelled = Color(0xFF94A3B8)
    val Progress = Color(0xFF22C55E)
}

/** 存储空间页各占用分类的色标（堆叠条与行前色点共用）。 */
object StorageUsagePalette {
    val Chat = Color(0xFF3B82F6)
    val Container = Color(0xFF8B5CF6)
    val ContainerImages = Color(0xFFA78BFA)
    val Workspaces = Color(0xFF22C55E)
    val AiConfig = Color(0xFF14B8A6)
    val Checkpoints = Color(0xFFF59E0B)
    val Logs = Color(0xFFF97316)
    val Caches = Color(0xFF94A3B8)
    val OtherData = Color(0xFFCBD5E1)
    val Apk = Color(0xFF64748B)
}

internal val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF4A8DFF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF14305C),
    onPrimaryContainer = Color(0xFFD6E5FF),
    secondary = Color(0xFF7EB2FF),
    onSecondary = Color(0xFF04102B),
    secondaryContainer = Color(0xFF1B3B66),
    onSecondaryContainer = Color(0xFFCFE0FF),
    tertiary = Color(0xFF32D74B),
    tertiaryContainer = Color(0xFF10361C),
    onTertiaryContainer = Color(0xFFB7EEBE),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF2F2F3),
    surface = Color(0xFF17171A),
    onSurface = Color(0xFFF2F2F3),
    surfaceVariant = Color(0xFF232326),
    onSurfaceVariant = Color(0xFFA0A0A5),
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF0E0E10),
    surfaceContainer = Color(0xFF17171A),
    surfaceContainerHigh = Color(0xFF1F1F22),
    surfaceContainerHighest = Color(0xFF2A2A2E),
    surfaceBright = Color(0xFF33333A),
    surfaceDim = Color(0xFF000000),
    outline = Color(0xFF48484D),
    outlineVariant = Color(0xFF2C2C30),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3D0000),
    errorContainer = Color(0xFF5C1010),
    onErrorContainer = Color(0xFFFFC9C9)
)

internal val LightColorScheme = lightColorScheme(
    primary = Brand.Blue,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE8F1FF),
    onPrimaryContainer = Color(0xFF0B4FD8),
    secondary = Color(0xFF5B8DEF),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEDF3FF),
    onSecondaryContainer = Color(0xFF1B4FA8),
    tertiary = Color(0xFF00B34A),
    tertiaryContainer = Color(0xFFE6F7ED),
    onTertiaryContainer = Color(0xFF0A7F38),
    background = Color(0xFFF5F5F6),
    onBackground = Color(0xFF0D0D0D),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0D0D0D),
    surfaceVariant = Color(0xFFF2F2F3),
    onSurfaceVariant = Color(0xFF7A7A7E),
    surfaceTint = Color.White,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFBFBFC),
    surfaceContainer = Color(0xFFF5F5F6),
    surfaceContainerHigh = Color(0xFFF0F0F2),
    surfaceContainerHighest = Color(0xFFEAEAEC),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFEDEDEF),
    outline = Color(0xFFD9D9DB),
    outlineVariant = Color(0xFFEBEBED),
    error = Color(0xFFE54545),

    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFDECEC),
    onErrorContainer = Color(0xFF8A1F1F)
)

private val AppTypography = Typography().run {
    copy(
        headlineMedium = headlineMedium.copy(
            fontSize = 30.sp,
            lineHeight = 38.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.sp
        ),
        headlineSmall = headlineSmall.copy(
            fontSize = 24.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.sp
        ),
        titleLarge = titleLarge.copy(
            fontSize = 20.sp,
            lineHeight = 28.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.sp
        ),
        titleMedium = titleMedium.copy(
            fontSize = 17.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.sp
        ),
        titleSmall = titleSmall.copy(
            fontSize = 15.sp,
            lineHeight = 21.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.sp
        ),
        bodyLarge = bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.sp),
        bodyMedium = bodyMedium.copy(fontSize = 14.sp, lineHeight = 21.sp, letterSpacing = 0.sp),
        bodySmall = bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp),
        labelLarge = labelLarge.copy(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
        labelMedium = labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.sp),
        labelSmall = labelSmall.copy(fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.sp)
    )
}

/** miuix 风格圆角体系：卡片大圆角，控件中等圆角，胶囊用于按钮与标签。 */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/**
 * 按比例缩放整套字号：只改字号与行高，字重与字距保持不变。
 * 信息密集页面（设置、浏览器）用它整体“缩小一档”，不必逐处改 TextStyle。
 */
fun Typography.scaled(factor: Float): Typography = copy(
    displayLarge = displayLarge.scaledBy(factor),
    displayMedium = displayMedium.scaledBy(factor),
    displaySmall = displaySmall.scaledBy(factor),
    headlineLarge = headlineLarge.scaledBy(factor),
    headlineMedium = headlineMedium.scaledBy(factor),
    headlineSmall = headlineSmall.scaledBy(factor),
    titleLarge = titleLarge.scaledBy(factor),
    titleMedium = titleMedium.scaledBy(factor),
    titleSmall = titleSmall.scaledBy(factor),
    bodyLarge = bodyLarge.scaledBy(factor),
    bodyMedium = bodyMedium.scaledBy(factor),
    bodySmall = bodySmall.scaledBy(factor),
    labelLarge = labelLarge.scaledBy(factor),
    labelMedium = labelMedium.scaledBy(factor),
    labelSmall = labelSmall.scaledBy(factor)
)

private fun TextStyle.scaledBy(factor: Float): TextStyle {
    val size = fontSize
    val line = lineHeight
    return copy(
        fontSize = if (size == TextUnit.Unspecified) size else (size.value * factor).sp,
        lineHeight = if (line == TextUnit.Unspecified) line else (line.value * factor).sp
    )
}

/** 把子树内的 Material 字体整体缩放一档，颜色与形状保持当前主题不变。 */
@Composable
fun CompactTypography(
    factor: Float = 0.9f,
    content: @Composable () -> Unit
) {
    val base = MaterialTheme.typography
    val scaled = remember(base, factor) { base.scaled(factor) }
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme,
        typography = scaled,
        shapes = MaterialTheme.shapes,
        content = content
    )
}

/**
 * @param preset 用户选定的配色主题。
 * @param dynamicColor 是否启用系统莫奈取色；低于 Android 12 时自动回退到 [preset]。
 */
@Composable
fun AIEditorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    preset: AppThemePreset = AppThemePreset.DEFAULT,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val themeColors = remember(darkTheme, preset, dynamicColor, context) {
        if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val dynamicScheme =
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            deriveDynamicThemeColors(dynamicScheme, darkTheme)
        } else if (darkTheme) {
            preset.dark
        } else {
            preset.light
        }
    }

    CompositionLocalProvider(LocalAppSemanticColors provides themeColors.semanticColors) {
        MaterialTheme(
            colorScheme = themeColors.colorScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content
        )
    }
}
