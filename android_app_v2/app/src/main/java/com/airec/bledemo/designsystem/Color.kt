package com.airec.bledemo.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * 「暖玉柔光 Warm Jade Glow」全套色板。
 *
 * 1:1 翻译自 `mockups/warm_2.html` 的 `:root` CSS 自定义属性。HEX 全部照搬，
 * 仅在 CSS 用 rgba() 处转为 Compose 的 [Color] alpha。
 *
 * - Material 的 `colorScheme`（见 Theme.kt）只承载与 M3 角色对得上的子集；
 * - 这里 [MeiliColors] 暴露 Material 之外的扩展色（陶土 deep/soft/tint、鼠尾草各变体、
 *   蜜色 honey、玫瑰 rose、叶绿 leaf、各 line/ink 层级、状态文字色、渐变 brush 等），
 *   供组件层精确还原原型。
 */
object MeiliPalette {
    // ---- ground 底色（随主题皮肤；暗色皮肤=深底） ----
    val Bg: Color get() = ThemeManager.current.bg
    val Bg2: Color get() = ThemeManager.current.bg2
    val Surface: Color get() = ThemeManager.current.surface
    val SurfaceSoft: Color get() = ThemeManager.current.surfaceSoft
    val SurfaceFrost: Color get() = ThemeManager.current.surfaceFrost

    // ---- terracotta primary 陶土（随「主题皮肤」动态：读 ThemeManager 当前皮肤；
    //      任何 composable 读它即订阅快照 → 换肤时全 app 重组） ----
    val Clay: Color get() = ThemeManager.current.clay
    val ClayDeep: Color get() = ThemeManager.current.clayDeep
    val ClaySoft: Color get() = ThemeManager.current.claySoft
    val ClayTint: Color get() = ThemeManager.current.clayTint
    val ClayLight: Color get() = ThemeManager.clayLight   // 主按钮渐变起点（按当前皮肤派生）
    val ClayGlow: Color get() = ThemeManager.clayGlow      // 圆钮高光起点（按当前皮肤派生）
    val ClayBright: Color get() = ThemeManager.clayBright  // 录音呼吸态核心高光（最亮，按当前皮肤派生）

    // ---- sage 雾感鼠尾草辅色（sageSoft 随主题，其余固定） ----
    val Sage = Color(0xFF93A38E)
    val SageDeep = Color(0xFF74866F)
    val SageSoft: Color get() = ThemeManager.current.sageSoft
    val SageTint: Color get() = ThemeManager.current.sageTint
    val SageLight = Color(0xFF9DAE97)     // sage 按钮渐变起点

    // ---- ink 文字（随主题；暗色皮肤=浅色文字） ----
    val Ink: Color get() = ThemeManager.current.ink
    val Ink2: Color get() = ThemeManager.current.ink2
    val Ink3: Color get() = ThemeManager.current.ink3
    val Ink4: Color get() = ThemeManager.current.ink4

    // ---- lines（随主题） ----
    val Line: Color get() = ThemeManager.current.line
    val LineSoft: Color get() = ThemeManager.current.lineSoft

    // ---- status：蜜色 / 玫瑰 / 叶绿（柔底+文字随主题，主色固定语义色） ----
    val Honey = Color(0xFFC99A5B)         // warm gold/honey
    val HoneySoft: Color get() = ThemeManager.current.honeySoft
    val HoneyText: Color get() = ThemeManager.current.honeyText  // 蜜色态文字
    val HoneyLight = Color(0xFFD6AC6E)    // honey 按钮渐变起点
    val HoneyDeep = Color(0xFFB98A45)     // honey 按钮渐变终点

    val Rose = Color(0xFFC57D6B)
    val RoseSoft: Color get() = ThemeManager.current.roseSoft
    val RoseText: Color get() = ThemeManager.current.roseText    // 玫瑰/危险态文字
    val RoseDeep = Color(0xFFA8503C)      // 进行中圆钮渐变终点
    val RoseLine: Color get() = ThemeManager.current.roseLine    // danger 边框

    val Leaf = Color(0xFF7FA083)
    val LeafSoft: Color get() = ThemeManager.current.leafSoft
    val LeafText: Color get() = ThemeManager.current.leafText    // 成功态文字
    val LeafLine: Color get() = ThemeManager.current.leafLine    // good 边框

    // ---- 深色 toast / 标签底 ----
    val InkSurface: Color get() = ThemeManager.current.inkSurface
    val OnInk = Color(0xFFFFFFFF)

    val White = Color(0xFFFFFFFF)

    // ====== 渐变 brush（按钮 / 圆钮 / 进度条等），还原 CSS linear/radial-gradient ======

    /** btn-primary：linear-gradient(135deg,#CC846C,clay-deep) */
    val PrimaryGradient: Brush
        get() = Brush.linearGradient(listOf(ClayLight, ClayDeep))

    /** btn-sage：linear-gradient(135deg,#9DAE97,sage-deep) */
    val SageGradient: Brush
        get() = Brush.linearGradient(listOf(SageLight, SageDeep))

    /** btn-honey：linear-gradient(135deg,#D6AC6E,#B98A45) */
    val HoneyGradient: Brush
        get() = Brush.linearGradient(listOf(HoneyLight, HoneyDeep))

    /** 进度条 fill：linear-gradient(90deg,clay,honey) */
    val TrackGradient: Brush
        get() = Brush.horizontalGradient(listOf(Clay, Honey))

    /** 陪伴圆钮（空闲态）：radial circle 高光 → 陶土 → deep */
    val CompanionGradient: Brush
        get() = Brush.radialGradient(listOf(ClayGlow, Clay, ClayDeep))

    /** 陪伴圆钮（进行中/录音中）：随主题的呼吸态——比空闲态更亮的同主色高光核心，
     *  配合缩放呼吸 + 白色停止方块表达「正在陪伴」。各皮肤都跟着主色变（黑金=金色呼吸等）。 */
    val CompanionLiveGradient: Brush
        get() = Brush.radialGradient(listOf(ClayBright, Clay, ClayDeep))

    /** 头像默认底：linear-gradient(135deg,clay-soft,sage-soft) */
    val AvatarGradient: Brush
        get() = Brush.linearGradient(listOf(ClaySoft, SageSoft))
}

/**
 * Material 之外的扩展色容器，通过 [LocalMeiliColors] 注入，组件里用
 * `MeiliTheme.colors` 读取（见 Theme.kt 的便捷访问器）。
 *
 * 这样 screens 不必直接 import [MeiliPalette]，统一走主题，便于将来出暗色/换肤。
 */
data class MeiliColors(
    val bg: Color = MeiliPalette.Bg,
    val bg2: Color = MeiliPalette.Bg2,
    val surface: Color = MeiliPalette.Surface,
    val surfaceSoft: Color = MeiliPalette.SurfaceSoft,
    val surfaceFrost: Color = MeiliPalette.SurfaceFrost,

    val clay: Color = MeiliPalette.Clay,
    val clayDeep: Color = MeiliPalette.ClayDeep,
    val claySoft: Color = MeiliPalette.ClaySoft,
    val clayTint: Color = MeiliPalette.ClayTint,

    val sage: Color = MeiliPalette.Sage,
    val sageDeep: Color = MeiliPalette.SageDeep,
    val sageSoft: Color = MeiliPalette.SageSoft,
    val sageTint: Color = MeiliPalette.SageTint,

    val ink: Color = MeiliPalette.Ink,
    val ink2: Color = MeiliPalette.Ink2,
    val ink3: Color = MeiliPalette.Ink3,
    val ink4: Color = MeiliPalette.Ink4,

    val line: Color = MeiliPalette.Line,
    val lineSoft: Color = MeiliPalette.LineSoft,

    val honey: Color = MeiliPalette.Honey,
    val honeySoft: Color = MeiliPalette.HoneySoft,
    val honeyText: Color = MeiliPalette.HoneyText,

    val rose: Color = MeiliPalette.Rose,
    val roseSoft: Color = MeiliPalette.RoseSoft,
    val roseText: Color = MeiliPalette.RoseText,
    val roseLine: Color = MeiliPalette.RoseLine,

    val leaf: Color = MeiliPalette.Leaf,
    val leafSoft: Color = MeiliPalette.LeafSoft,
    val leafText: Color = MeiliPalette.LeafText,
    val leafLine: Color = MeiliPalette.LeafLine,

    val inkSurface: Color = MeiliPalette.InkSurface,
    val onInk: Color = MeiliPalette.OnInk,
)

/** 默认（暖玉柔光浅色）扩展色实例。 */
val LightMeiliColors = MeiliColors()

val LocalMeiliColors = staticCompositionLocalOf { LightMeiliColors }

/**
 * 便捷读取扩展色：`MeiliTheme.colors.clayDeep`。
 * 与 Theme.kt 里的 object MeiliTheme 配合使用。
 */
val meiliColorsCurrent: MeiliColors
    @Composable
    @ReadOnlyComposable
    get() = LocalMeiliColors.current
