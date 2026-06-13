package com.airec.bledemo.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 「暖玉柔光」圆角体系，1:1 对应 warm_2.html `:root` 的 --r-* token。
 *
 * 原型口径（圆润 18–22 系，外加 12/16/24/30/pill）：
 *  --r-xs:12  --r-sm:16  --r-md:20  --r-lg:24  --r-xl:30  --r-pill:999
 *
 * Material 的 [Shapes] 槽位映射：
 *  extraSmall=12 small=16 medium=20 large=24 extraLarge=30
 *  （pill 用 [MeiliShapes.Pill] 单独取，M3 没有对应槽位）。
 */
object MeiliShapes {
    val Xs = RoundedCornerShape(12.dp)
    val Sm = RoundedCornerShape(16.dp)
    val Md = RoundedCornerShape(20.dp)
    val Lg = RoundedCornerShape(24.dp)
    val Xl = RoundedCornerShape(30.dp)
    val Pill = RoundedCornerShape(50)   // 999px 等价的胶囊

    /** sheet 顶部圆角：上 30、下 0 —— 对应 .sheet border-radius:r-xl r-xl 0 0。 */
    val SheetTop = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)

    /** 图标按钮方圆角 .iconbtn(16) / .iconbtn.back(14)。 */
    val IconButton = RoundedCornerShape(16.dp)
    val IconButtonBack = RoundedCornerShape(14.dp)
}

val MeiliMaterialShapes = Shapes(
    extraSmall = MeiliShapes.Xs,
    small = MeiliShapes.Sm,
    medium = MeiliShapes.Md,
    large = MeiliShapes.Lg,
    extraLarge = MeiliShapes.Xl,
)
