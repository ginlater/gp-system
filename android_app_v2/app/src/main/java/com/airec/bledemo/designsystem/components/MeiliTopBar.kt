package com.airec.bledemo.designsystem.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.airec.bledemo.designsystem.Dimens
import com.airec.bledemo.designsystem.MeiliIcons
import com.airec.bledemo.designsystem.MeiliPalette
import com.airec.bledemo.designsystem.MeiliShapes
import com.airec.bledemo.designsystem.MeiliTheme

/**
 * 顶栏 [MeiliTopBar]，还原 warm_2.html 的 .appbar：
 * 左侧可选返回方圆角按钮 + 标题（含可选副标题），右侧自定义动作区（图标按钮）。
 *
 * 标题用无衬线 800（report/customer 头那种），如需衬线大标题请在 screen 自行用
 * `MaterialTheme.typography.displaySmall`。这里走通用「带返回 + 动作」型顶栏。
 *
 * @param title 主标题
 * @param onBack 可选返回回调；非空时左侧显示返回按钮（.iconbtn.back）
 * @param subtitle 可选副标题（ink-3 小字）
 * @param actions 右侧动作区，置于 [RowScope]，通常放 [TopBarIconButton]
 */
@Composable
fun MeiliTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (onBack != null) {
            IconSquareButton(
                icon = MeiliIcons.Back,
                onClick = onBack,
                back = true,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MeiliPalette.Ink,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MeiliPalette.Ink3,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
    }
}

/**
 * 顶栏 / 右上角图标按钮，还原 .iconbtn：44×44 surface 方圆角 + 细描边 + 轻浮起，
 * 点按 0.94 缩放。可选右上角红点 [badge]（对应 .badge-dot，如提醒未读数）。
 */
@Composable
fun TopBarIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
) {
    Box(modifier = modifier) {
        IconSquareButton(icon = icon, onClick = onClick, back = false)
        if (badge != null) {
            Surface(
                shape = MeiliShapes.Pill,
                color = MeiliPalette.Clay,
                contentColor = MeiliPalette.White,
                border = BorderStroke(2.5.dp, MeiliPalette.Bg),
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = MeiliPalette.White,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
    }
}

@Composable
private fun IconSquareButton(
    icon: ImageVector,
    onClick: () -> Unit,
    back: Boolean,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        shape = if (back) MeiliShapes.IconButtonBack else MeiliShapes.IconButton,
        color = MeiliPalette.Surface,
        contentColor = MeiliPalette.Ink2,
        border = BorderStroke(Dimens.BorderThin, MeiliPalette.Line),
        shadowElevation = Dimens.Elev1,
        modifier = Modifier
            .size(if (back) Dimens.IconButtonBack else Dimens.IconButton)
            .scale(if (pressed) 0.94f else 1f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(Dimens.Icon))
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF8F3ED, widthDp = 360)
@Composable
private fun MeiliTopBarPreview() {
    MeiliTheme {
        Column(modifier = Modifier.padding(horizontal = 18.dp)) {
            MeiliTopBar(
                title = "今日接诊",
                subtitle = "陪伴前后，请把今天要接诊的顾客加进来",
                actions = {
                    TopBarIconButton(MeiliIcons.Refresh, {})
                    TopBarIconButton(MeiliIcons.Reminder, {}, badge = "3")
                },
            )
            MeiliTopBar(
                title = "张陪伴师 × 刘佳佳",
                subtitle = "陪伴全维度分析报告 · 2026-06-08",
                onBack = {},
            )
        }
    }
}
