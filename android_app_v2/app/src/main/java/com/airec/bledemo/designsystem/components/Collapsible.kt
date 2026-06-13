package com.airec.bledemo.designsystem.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airec.bledemo.designsystem.Dimens
import com.airec.bledemo.designsystem.MeiliIcons
import com.airec.bledemo.designsystem.MeiliPalette
import com.airec.bledemo.designsystem.MeiliShapes
import com.airec.bledemo.designsystem.MeiliTheme

/**
 * 可折叠柔光卡 [Collapsible]，还原 warm_2.html 的 .part / .fold：
 *  - 表头可点，右侧 chevron 展开 180° 旋转（CSS .part.open .chev transform:rotate(180)）。
 *  - 展开 / 收起带高度动画。
 *  - 支持两种头部前缀：编号徽标 [numberBadge]（报告 PART 01–11 的 .part-no）
 *    或方圆角图标徽标 [leadingIcon]（原始音频 .fold-head .fi）。
 *
 * 用于：报告 11 个 PART、原始音频折叠卡、逐字转写子折叠等。
 *
 * @param title 头部标题
 * @param modifier 外部修饰
 * @param subtitle 可选头部副标题（fold-head .fs）
 * @param numberBadge 可选编号（如 "01"），显示陶土 tint 衬线编号徽标
 * @param leadingIcon 可选前置图标徽标（与 numberBadge 二选一）
 * @param initiallyOpen 初始是否展开
 * @param collapsedHint 折叠态时表头右侧的淡色提示（如「点击展开」，对齐 report.html 折叠 part）；展开后隐藏
 * @param content 折叠体内容，置于 [ColumnScope]
 */
@Composable
fun Collapsible(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    numberBadge: String? = null,
    leadingIcon: ImageVector? = null,
    initiallyOpen: Boolean = true,
    collapsedHint: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var open by remember { mutableStateOf(initiallyOpen) }
    val chevRotation by animateFloatAsState(
        targetValue = if (open) 180f else 0f,
        animationSpec = tween(250),
        label = "chev",
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MeiliShapes.Lg,
        color = MeiliPalette.Surface,
        contentColor = MeiliPalette.Ink,
        border = BorderStroke(Dimens.BorderThin, MeiliPalette.LineSoft),
        shadowElevation = Dimens.Elev2,
    ) {
        Column {
            // ---- 头 ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { open = !open }
                    .padding(horizontal = 17.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                when {
                    numberBadge != null -> NumberBadge(numberBadge)
                    leadingIcon != null -> IconBadge(leadingIcon)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, color = MeiliPalette.Ink)
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MeiliPalette.Ink3,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                if (!open && collapsedHint != null) {
                    Text(
                        collapsedHint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MeiliPalette.Ink4,
                    )
                }
                Icon(
                    MeiliIcons.ChevDown,
                    contentDescription = if (open) "收起" else "展开",
                    tint = MeiliPalette.Ink3,
                    modifier = Modifier
                        .size(Dimens.IconSm)
                        .rotate(chevRotation),
                )
            }
            // ---- 体 ----
            AnimatedVisibility(
                visible = open,
                enter = expandVertically(tween(220)) + fadeIn(tween(220)),
                exit = shrinkVertically(tween(180)) + fadeOut(tween(120)),
            ) {
                Column(modifier = Modifier.padding(start = 17.dp, end = 17.dp, bottom = 18.dp)) {
                    content()
                }
            }
        }
    }
}

/** .part-no：32×32 陶土 tint 圆角 + 衬线编号。 */
@Composable
private fun NumberBadge(text: String) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .background(MeiliPalette.ClayTint, MeiliShapes.Xs),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MeiliTheme.summaryStyle.copy(fontSize = 13.sp),
            color = MeiliPalette.ClayDeep,
        )
    }
}

/** .fold-head .fi：38×38 陶土 tint 方圆角 + 线性图标。 */
@Composable
private fun IconBadge(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .background(MeiliPalette.ClayTint, MeiliShapes.Xs),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MeiliPalette.ClayDeep, modifier = Modifier.size(Dimens.IconSm))
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF8F3ED, widthDp = 360)
@Composable
private fun CollapsiblePreview() {
    MeiliTheme {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Collapsible(title = "全维度评估总览", numberBadge = "01") {
                Text(
                    "A级高价值客户。消费力强、有抗衰刚需，决策周期短，适合主推高客单套餐。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Collapsible(
                title = "原始音频",
                subtitle = "全程播放 · 逐字转写",
                leadingIcon = MeiliIcons.Headphone,
                initiallyOpen = false,
            ) {
                Text(
                    "AI 自动转写，仅供顾问复盘参考。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MeiliPalette.Ink3,
                )
            }
        }
    }
}
