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
@Composable
fun MeiliTheme(content: @Composable () -> Unit) {
    // colorScheme 在组合内构建，读「动态的」MeiliPalette.Clay* → 换肤时连 M3 角色色(primary 等)也跟着重算；
    // 扩展色 MeiliColors() 也在此处新建，其默认值读 MeiliPalette.*（当前皮肤）→ MeiliTheme.colors.clay 同步换肤。
    val colorScheme = lightColorScheme(
        primary = MeiliPalette.Clay,
        onPrimary = MeiliPalette.White,
        primaryContainer = MeiliPalette.ClayTint,
        onPrimaryContainer = MeiliPalette.ClayDeep,
        secondary = MeiliPalette.Sage,
        onSecondary = MeiliPalette.White,
        secondaryContainer = MeiliPalette.SageTint,
        onSecondaryContainer = MeiliPalette.SageDeep,
        tertiary = MeiliPalette.Honey,
        onTertiary = MeiliPalette.White,
        tertiaryContainer = MeiliPalette.HoneySoft,
        onTertiaryContainer = MeiliPalette.HoneyText,
        background = MeiliPalette.Bg,
        onBackground = MeiliPalette.Ink,
        surface = MeiliPalette.Surface,
        onSurface = MeiliPalette.Ink,
        surfaceVariant = MeiliPalette.SurfaceSoft,
        onSurfaceVariant = MeiliPalette.Ink2,
        surfaceTint = MeiliPalette.Clay,
        error = MeiliPalette.Rose,
        onError = MeiliPalette.White,
        errorContainer = MeiliPalette.RoseSoft,
        onErrorContainer = MeiliPalette.RoseText,
        outline = MeiliPalette.Line,
        outlineVariant = MeiliPalette.LineSoft,
        inverseSurface = MeiliPalette.InkSurface,
        inverseOnSurface = MeiliPalette.White,
        scrim = MeiliPalette.Ink,
    )
    CompositionLocalProvider(LocalMeiliColors provides MeiliColors()) {
        MaterialTheme(
            colorScheme = colorScheme,
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
