package com.airec.bledemo.designsystem.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airec.bledemo.designsystem.Dimens
import com.airec.bledemo.designsystem.MeiliIcons
import com.airec.bledemo.designsystem.MeiliPalette
import com.airec.bledemo.designsystem.MeiliShapes
import com.airec.bledemo.designsystem.MeiliTheme

/**
 * 状态胶囊 [StatusPill]，还原 warm_2.html 的 .pill 各色：
 *  - [PillKind.Ok]      → .pill.ok（叶绿）：已分析 / 已完成 / 已成交
 *  - [PillKind.Warn]    → .pill.wait（蜜色）：待分析 / 待开始 / 审批中
 *  - [PillKind.Danger]  → .pill.fail（玫瑰）：失败 / 跨日 / 高风险
 *  - [PillKind.Neutral] → .pill.muted（surface-soft + 描边）：未绑定 / 已锁定
 *  - [PillKind.Clay]    → .pill.clay（陶土 tint）：陪伴笔 / 同步中 / 留意
 *
 * 另外 .pill.run（鼠尾草）通过 [PillKind.Run] 暴露（分析中… 5/11）。
 */
enum class PillKind { Ok, Warn, Danger, Neutral, Clay, Run }

private data class PillStyle(val bg: Color, val fg: Color, val border: Color?)

private fun styleOf(kind: PillKind): PillStyle = when (kind) {
    PillKind.Ok -> PillStyle(MeiliPalette.LeafSoft, MeiliPalette.LeafText, null)
    PillKind.Warn -> PillStyle(MeiliPalette.HoneySoft, MeiliPalette.HoneyText, null)
    PillKind.Danger -> PillStyle(MeiliPalette.RoseSoft, MeiliPalette.RoseText, null)
    PillKind.Neutral -> PillStyle(MeiliPalette.SurfaceSoft, MeiliPalette.Ink2, MeiliPalette.Line)
    PillKind.Clay -> PillStyle(MeiliPalette.ClayTint, MeiliPalette.ClayDeep, null)
    PillKind.Run -> PillStyle(MeiliPalette.SageTint, MeiliPalette.SageDeep, null)
}

/**
 * @param text 胶囊文字
 * @param kind 语义色（见 [PillKind]）
 * @param icon 可选前置线性图标（如对勾/时钟/警告/同步）
 */
@Composable
fun StatusPill(
    text: String,
    kind: PillKind,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val s = styleOf(kind)
    Surface(
        modifier = modifier,
        shape = MeiliShapes.Pill,
        color = s.bg,
        contentColor = s.fg,
        border = s.border?.let { BorderStroke(Dimens.BorderThin, it) },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(13.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 11.5f.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.sp,
                ),
            )
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Preview(showBackground = true, backgroundColor = 0xFFF8F3ED, widthDp = 360)
@Composable
private fun StatusPillPreview() {
    MeiliTheme {
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusPill("已分析", PillKind.Ok, icon = MeiliIcons.Check)
            StatusPill("待分析", PillKind.Warn, icon = MeiliIcons.Clock)
            StatusPill("分析失败，点击重试", PillKind.Danger, icon = MeiliIcons.Warn)
            StatusPill("未绑定", PillKind.Neutral)
            StatusPill("陪伴笔", PillKind.Clay, icon = MeiliIcons.Pen)
            StatusPill("分析中… 5/11", PillKind.Run, icon = MeiliIcons.Sync)
        }
    }
}
