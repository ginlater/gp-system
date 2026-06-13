package com.airec.bledemo.designsystem

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/**
 * 「暖玉柔光」字体层级。
 *
 * 对照 warm_2.html：
 * - `--serif:"Songti SC","Noto Serif SC",Georgia,serif` —— 标题/大数字用衬线提升高级感。
 *   Compose 没有内建宋体；这里用 [FontFamily.Serif]（系统衬线，Android 上为 Noto Serif 系），
 *   与原型的「Songti SC → Noto Serif SC」回退链同源，气质一致。
 * - 正文用 [FontFamily.SansSerif]（对应 PingFang/系统无衬线）。
 *
 * 命名沿用 M3 角色，便于 `MaterialTheme.typography.*` 直接取用；
 * 像素值取自原型里出现频率最高的字号（appbar title 27、sheet h3 20、card-title 15、
 * 正文 12.5–14.5、smallnote 11.5 等）。
 */

/** 标题用系统衬线（Songti/Noto Serif 同源），还原原型 .title / .timer / .score-big 的高级感。 */
val MeiliSerif: FontFamily = FontFamily.Serif

/** 正文/按钮用系统无衬线（PingFang 同源）。 */
val MeiliSans: FontFamily = FontFamily.SansSerif

private val tightLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

val MeiliTypography = Typography(
    // ---- 衬线标题：appbar .title(27/serif/600) ----
    displaySmall = TextStyle(
        fontFamily = MeiliSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 27.sp,
        lineHeight = 34.sp,
        letterSpacing = 1.sp,
        lineHeightStyle = tightLineHeight,
    ),
    // .title.sm(23/serif/600)
    headlineLarge = TextStyle(
        fontFamily = MeiliSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 23.sp,
        lineHeight = 30.sp,
        letterSpacing = 0.5.sp,
    ),
    // sheet h3(20/serif/600)
    headlineMedium = TextStyle(
        fontFamily = MeiliSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 27.sp,
        letterSpacing = 0.5.sp,
    ),
    // 顶栏次级标题 / report 头(16/800 sans)
    headlineSmall = TextStyle(
        fontFamily = MeiliSans,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.2.sp,
    ),
    // .card-title(15/800)
    titleLarge = TextStyle(
        fontFamily = MeiliSans,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.2.sp,
    ),
    // .nm / .et 行标题(15/800)
    titleMedium = TextStyle(
        fontFamily = MeiliSans,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.2.sp,
    ),
    // .fold-head .ft / part .pt(14–14.5/800)
    titleSmall = TextStyle(
        fontFamily = MeiliSans,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 14.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.2.sp,
    ),
    // 正文 .input / 大正文(14.5)
    bodyLarge = TextStyle(
        fontFamily = MeiliSans,
        fontWeight = FontWeight.Normal,
        fontSize = 14.5f.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.2.sp,
    ),
    // .bullet / .advice / 普通正文(13)
    bodyMedium = TextStyle(
        fontFamily = MeiliSans,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.1.sp,
    ),
    // .meta / .card-sub(12–12.5)
    bodySmall = TextStyle(
        fontFamily = MeiliSans,
        fontWeight = FontWeight.Normal,
        fontSize = 12.5f.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp,
    ),
    // 按钮文字 .btn(14.5/700)
    labelLarge = TextStyle(
        fontFamily = MeiliSans,
        fontWeight = FontWeight.Bold,
        fontSize = 14.5f.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.3.sp,
    ),
    // .section-lbl(11.5/800) / .pill(11.5/700) / 小按钮(13)
    labelMedium = TextStyle(
        fontFamily = MeiliSans,
        fontWeight = FontWeight.Bold,
        fontSize = 11.5f.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.5.sp,
    ),
    // .smallnote / nav-item(10–11.5)
    labelSmall = TextStyle(
        fontFamily = MeiliSans,
        fontWeight = FontWeight.Bold,
        fontSize = 10.5f.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.2.sp,
    ),
)

/** 大数字（计时器/分数）专用衬线样式，原型 .timer(48) / .score-big(34) / tabular-nums。 */
object MeiliTextStyles {
    val Timer = TextStyle(
        fontFamily = MeiliSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 48.sp,
        lineHeight = 52.sp,
        letterSpacing = 3.sp,
    )
    val ScoreBig = TextStyle(
        fontFamily = MeiliSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp,
        lineHeight = 34.sp,
    )
    val BrandTitle = TextStyle(
        fontFamily = MeiliSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 29.sp,
        lineHeight = 36.sp,
        letterSpacing = 3.sp,
    )
    /** 卡内汇总大数字 .tasks-sum .big(24/serif) */
    val SummaryBig = TextStyle(
        fontFamily = MeiliSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 26.sp,
    )
}
