package com.airec.bledemo.designsystem.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airec.bledemo.designsystem.Dimens
import com.airec.bledemo.designsystem.MeiliIcons
import com.airec.bledemo.designsystem.MeiliPalette
import com.airec.bledemo.designsystem.MeiliShapes
import com.airec.bledemo.designsystem.MeiliTheme

/**
 * 暖玉柔光按钮族。胶囊形（r-pill）、点按 0.97 缩放、图标 + 文字水平居中。
 * 还原 warm_2.html 的 .btn / .btn-primary / .btn-ghost / .btn-soft。
 *
 * 三个公开按钮共用同一内部实现 [MeiliButtonBase]，仅背景/文字色不同：
 *  - [PrimaryButton]：陶土渐变实心（主操作，如「登录」「绑定顾客」「确认并开始分析」）。
 *  - [GhostButton]：surface 底 + 描边（次操作，如「取消」「退回待整理」「查看进度」）。
 *  - [SoftButton]：陶土 tint 浅底（轻操作，如「仍然分析」「补齐缺失」、评审预览按钮）。
 */

/** 按钮尺寸档位，对应 .btn / .btn-sm / .btn-xs。 */
enum class MeiliButtonSize { Normal, Small, Xs }

private data class ButtonMetrics(
    val vPad: Dp,
    val hPad: Dp,
    val fontSize: Float,
    val iconSize: Dp,
    val minHeight: Dp,
)

private fun metricsOf(size: MeiliButtonSize): ButtonMetrics = when (size) {
    MeiliButtonSize.Normal -> ButtonMetrics(14.dp, 22.dp, 14.5f, 18.dp, 48.dp)
    MeiliButtonSize.Small -> ButtonMetrics(10.dp, 16.dp, 13f, 16.dp, 40.dp)
    MeiliButtonSize.Xs -> ButtonMetrics(8.dp, 14.dp, 12.5f, 15.dp, 34.dp)
}

@Composable
private fun MeiliButtonBase(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    icon: ImageVector?,
    enabled: Boolean,
    contentColor: Color,
    size: MeiliButtonSize,
    background: Brush? = null,
    backgroundColor: Color? = null,
    border: BorderStroke? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val m = metricsOf(size)

    Surface(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        shape = MeiliShapes.Pill,
        color = backgroundColor ?: Color.Transparent,
        contentColor = contentColor,
        border = border,
        modifier = modifier
            .heightIn(min = m.minHeight)
            .scale(if (pressed) 0.97f else 1f)
            .alpha(if (enabled) 1f else 0.45f),
    ) {
        Row(
            modifier = Modifier
                .then(if (background != null) Modifier.background(background, MeiliShapes.Pill) else Modifier)
                .padding(horizontal = m.hPad, vertical = m.vPad),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompositionLocalProvider(LocalContentColor provides contentColor) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(m.iconSize))
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = m.fontSize.sp),
                )
            }
        }
    }
}

/**
 * 主按钮：陶土渐变实心。app 内最高优先级操作。
 *
 * @param text 按钮文字
 * @param onClick 点击回调
 * @param icon 可选前置线性图标（取自 MeiliIcons，绝不用 emoji/Material 默认图标）
 * @param enabled 是否可点（禁用态 0.45 透明、不可点）
 * @param size 尺寸档位
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    size: MeiliButtonSize = MeiliButtonSize.Normal,
) = MeiliButtonBase(
    text = text,
    onClick = onClick,
    modifier = modifier,
    icon = icon,
    enabled = enabled,
    contentColor = MeiliPalette.White,
    size = size,
    background = MeiliPalette.PrimaryGradient,
)

/**
 * 幽灵按钮：surface 底 + 细描边。次级 / 取消类操作。
 */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    size: MeiliButtonSize = MeiliButtonSize.Normal,
) = MeiliButtonBase(
    text = text,
    onClick = onClick,
    modifier = modifier,
    icon = icon,
    enabled = enabled,
    contentColor = MeiliPalette.Ink2,
    size = size,
    backgroundColor = MeiliPalette.Surface,
    border = BorderStroke(Dimens.BorderThin, MeiliPalette.Line),
)

/**
 * 柔按钮：陶土 tint 浅底、陶土深字。轻量操作 / 评审预览。
 */
@Composable
fun SoftButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    size: MeiliButtonSize = MeiliButtonSize.Normal,
) = MeiliButtonBase(
    text = text,
    onClick = onClick,
    modifier = modifier,
    icon = icon,
    enabled = enabled,
    contentColor = MeiliPalette.ClayDeep,
    size = size,
    backgroundColor = MeiliPalette.ClayTint,
)

@Preview(showBackground = true, backgroundColor = 0xFFF8F3ED, widthDp = 360)
@Composable
private fun ButtonsPreview() {
    MeiliTheme {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PrimaryButton("登 录", {}, modifier = Modifier.fillMaxWidth())
            PrimaryButton("绑定顾客", {}, icon = MeiliIcons.Link)
            GhostButton("取消", {})
            GhostButton("从陪伴笔同步", {}, icon = MeiliIcons.Sync, size = MeiliButtonSize.Xs)
            SoftButton("仍然分析", {}, size = MeiliButtonSize.Small)
            PrimaryButton("禁用态", {}, enabled = false)
        }
    }
}
