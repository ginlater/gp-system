package com.airec.bledemo.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.TextStyle

/**
 * 「暖玉柔光 Warm Jade Glow」主题入口。
 *
 * 把 [MaterialTheme] 的 colorScheme / typography / shapes 全部接上设计系统 token
 * （Color.kt / Type.kt / Shape.kt），并通过 [LocalMeiliColors] 注入 Material 之外的扩展色，
 * 供组件层 `MeiliTheme.colors.*` 精确还原原型。
 *
 * 用法：`MeiliTheme { ... }` 包住整个 app 内容（含每个组件的 @Preview）。
 *
 * token 像素级事实源：mockups/warm_2.html 的 :root；本主题不引入暗色（原型已定亮色版）。
 */
private val WarmJadeColorScheme: ColorScheme = lightColorScheme(
    // ---- 陶土主色 ----
    primary = MeiliPalette.Clay,
    onPrimary = MeiliPalette.White,
    primaryContainer = MeiliPalette.ClayTint,
    onPrimaryContainer = MeiliPalette.ClayDeep,

    // ---- 雾感鼠尾草辅色 ----
    secondary = MeiliPalette.Sage,
    onSecondary = MeiliPalette.White,
    secondaryContainer = MeiliPalette.SageTint,
    onSecondaryContainer = MeiliPalette.SageDeep,

    // ---- 蜜色第三色 ----
    tertiary = MeiliPalette.Honey,
    onTertiary = MeiliPalette.White,
    tertiaryContainer = MeiliPalette.HoneySoft,
    onTertiaryContainer = MeiliPalette.HoneyText,

    // ---- 底 / surface ----
    background = MeiliPalette.Bg,
    onBackground = MeiliPalette.Ink,
    surface = MeiliPalette.Surface,
    onSurface = MeiliPalette.Ink,
    surfaceVariant = MeiliPalette.SurfaceSoft,
    onSurfaceVariant = MeiliPalette.Ink2,
    surfaceTint = MeiliPalette.Clay,

    // ---- 错误（玫瑰）----
    error = MeiliPalette.Rose,
    onError = MeiliPalette.White,
    errorContainer = MeiliPalette.RoseSoft,
    onErrorContainer = MeiliPalette.RoseText,

    // ---- 线/描边 ----
    outline = MeiliPalette.Line,
    outlineVariant = MeiliPalette.LineSoft,

    // ---- 反色（深色 toast / scrim） ----
    inverseSurface = MeiliPalette.InkSurface,
    inverseOnSurface = MeiliPalette.White,
    scrim = MeiliPalette.Ink,
)

@Composable
fun MeiliTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalMeiliColors provides LightMeiliColors) {
        MaterialTheme(
            colorScheme = WarmJadeColorScheme,
            typography = MeiliTypography,
            shapes = MeiliMaterialShapes,
            content = content,
        )
    }
}

/**
 * 设计系统便捷访问器。在 `MeiliTheme { }` 内：
 *  - `MeiliTheme.colors.clayDeep` 取扩展色（Material 之外）
 *  - `MeiliTheme.shapes.Md` 取圆角
 *  - `MeiliTheme.timer` 等取专用文本样式
 *
 * Material 角色色仍走 `MaterialTheme.colorScheme.*`（两者可混用）。
 */
object MeiliTheme {
    val colors: MeiliColors
        @Composable
        @ReadOnlyComposable
        get() = LocalMeiliColors.current

    val shapes get() = MeiliShapes

    val timerStyle: TextStyle get() = MeiliTextStyles.Timer
    val scoreStyle: TextStyle get() = MeiliTextStyles.ScoreBig
    val brandStyle: TextStyle get() = MeiliTextStyles.BrandTitle
    val summaryStyle: TextStyle get() = MeiliTextStyles.SummaryBig
}
