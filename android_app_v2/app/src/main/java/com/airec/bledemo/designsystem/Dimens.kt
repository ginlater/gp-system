package com.airec.bledemo.designsystem

import androidx.compose.ui.unit.dp

/**
 * 「暖玉柔光」间距 / 尺寸 token。
 *
 * 间距 s1–s6 1:1 对应 warm_2.html `:root`：
 *  --s1:6 --s2:10 --s3:14 --s4:18 --s5:24 --s6:32
 *
 * 另收录原型里高频复用的固定尺寸（卡内边距 20、屏幕水平留白 18、
 * 圆钮 152、头像 46、图标按钮 44、底栏高 78 等），避免各 screen 各写魔数。
 *
 * 全部以 dp 计；屏幕基线 360–390，组件须弹性等分、不得横向溢出。
 */
object Dimens {
    // ---- 间距阶梯 ----
    val S1 = 6.dp
    val S2 = 10.dp
    val S3 = 14.dp
    val S4 = 18.dp
    val S5 = 24.dp
    val S6 = 32.dp

    // ---- 布局留白 ----
    /** .scroll 左右内边距 padding:0 18。 */
    val ScreenH = 18.dp
    /** 卡片默认内边距 .card padding:20。 */
    val CardPad = 20.dp
    /** .card.tight padding:15。 */
    val CardPadTight = 15.dp
    /** 卡片间距 margin-bottom:16。 */
    val CardGap = 16.dp
    /** 底栏避让，scroll padding-bottom:96。 */
    val BottomNavInset = 96.dp

    // ---- 组件固定尺寸 ----
    /** .iconbtn 44×44。 */
    val IconButton = 44.dp
    /** .iconbtn.back 40×40。 */
    val IconButtonBack = 40.dp
    /** .avatar 46×46。 */
    val Avatar = 46.dp
    /** 大头像（顾客档案）56。 */
    val AvatarLg = 56.dp
    /** 陪伴圆钮 .comp-circle 152。 */
    val CompanionCircle = 152.dp
    /** 圆钮内图标 48。 */
    val CompanionIcon = 48.dp
    /** .playbtn 40。 */
    val PlayButton = 40.dp
    /** 底部导航高 .botnav（加高以容下 图标chip + 文字标签，避免标签被竖向裁切）。 */
    val BottomNav = 88.dp
    /** 进度条高 .bar 7。 */
    val TrackHeight = 7.dp
    /** 描边线宽（输入框/选项卡 1.5）。 */
    val BorderThin = 1.dp
    val BorderField = 1.5.dp

    // ---- 默认图标尺寸（与 .ic 22 / .ic.sm 18 / .ic.lg 26 对应） ----
    val Icon = 22.dp
    val IconSm = 18.dp
    val IconLg = 26.dp
    val IconXs = 15.dp

    // ---- 阴影高度（柔光、克制不拟物） ----
    /** --sh-1 ≈ 轻浮起。 */
    val Elev1 = 2.dp
    /** --sh-2 ≈ 卡片柔光。 */
    val Elev2 = 8.dp
    /** --sh-glow ≈ 圆钮发光。 */
    val ElevGlow = 14.dp
}
