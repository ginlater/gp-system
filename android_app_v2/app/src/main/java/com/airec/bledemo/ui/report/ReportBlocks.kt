package com.airec.bledemo.ui.report

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airec.bledemo.designsystem.Dimens
import com.airec.bledemo.designsystem.MeiliPalette
import com.airec.bledemo.designsystem.MeiliShapes

/* ===================================================================
 * 报告内复用的小积木，1:1 还原 warm_2.html 的 .bullet / .advice / .tag-lbl /
 * .twocol / .col-box / .sagebox / .banner / .kv 等。各 PART 卡共用。
 * 全部弹性、min-width:0 等价（Text 自动换行），360–390 不横向溢出。
 * =================================================================== */

/** banner 语义色。 */
enum class BannerKind { Info, Warn, Danger, Clay }

/** .bullet：陶土小圆点 + 正文。 */
@Composable
fun Bullet(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 7.dp, end = 12.dp)
                .size(6.dp)
                .background(MeiliPalette.Clay, RoundedCornerShape(50)),
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MeiliPalette.Ink,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * .advice：左侧色条 + surface-soft 底的小建议块。
 * @param leadBold 行首加粗前缀（如「建议 1」「话术 1」「法令纹」），陶土深色
 * @param accent 左侧色条颜色（默认蜜色，强调可传陶土）
 */
@Composable
fun AdviceBlock(
    text: String,
    modifier: Modifier = Modifier,
    leadBold: String? = null,
    secondary: String? = null,
    accent: Color = MeiliPalette.Honey,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(MeiliShapes.Xs)
            .background(MeiliPalette.SurfaceSoft),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(accent),
        )
        Column(modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp)) {
            Row {
                if (leadBold != null) {
                    Text(
                        "$leadBold ",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MeiliPalette.ClayDeep,
                    )
                }
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MeiliPalette.Ink,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            if (secondary != null) {
                Text(
                    secondary,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal),
                    color = MeiliPalette.Ink3,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** .tag-lbl：小圆角标签头（带图标），蜜色或玫瑰色。 */
@Composable
fun TagLabel(
    text: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    rose: Boolean = false,
) {
    val bg = if (rose) MeiliPalette.RoseSoft else MeiliPalette.HoneySoft
    val fg = if (rose) MeiliPalette.RoseText else MeiliPalette.HoneyText
    Surface(shape = MeiliShapes.Pill, color = bg, contentColor = fg, modifier = modifier) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(13.dp))
            Text(
                text,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.sp,
                ),
            )
        }
    }
}

/** .sagebox：鼠尾草 tint 块（综合判断/一句话总结）。可传 dark=true → 深墨底白字。 */
@Composable
fun SageBox(text: String, modifier: Modifier = Modifier, dark: Boolean = false) {
    val bg = if (dark) MeiliPalette.InkSurface else MeiliPalette.SageTint
    val fg = if (dark) MeiliPalette.White else MeiliPalette.SageDeep
    Surface(shape = MeiliShapes.Sm, color = bg, contentColor = fg, modifier = modifier.fillMaxWidth()) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = fg,
            modifier = Modifier.padding(14.dp),
        )
    }
}

/** .banner：信息/警告/危险/陶土 四色横幅，左图标。 */
@Composable
fun ReportBanner(
    text: String,
    kind: BannerKind,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    val (bg, fg, border) = when (kind) {
        BannerKind.Info -> Triple(MeiliPalette.SageTint, MeiliPalette.SageDeep, MeiliPalette.SageSoft)
        BannerKind.Warn -> Triple(MeiliPalette.HoneySoft, MeiliPalette.HoneyText, Color(0xFFE8D2A6))
        BannerKind.Danger -> Triple(MeiliPalette.RoseSoft, MeiliPalette.RoseText, MeiliPalette.RoseLine)
        BannerKind.Clay -> Triple(MeiliPalette.ClayTint, MeiliPalette.ClayDeep, MeiliPalette.ClaySoft)
    }
    Surface(
        shape = MeiliShapes.Md,
        color = bg,
        contentColor = fg,
        border = BorderStroke(Dimens.BorderThin, border),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 15.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.padding(top = 1.dp).size(18.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = fg,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** .twocol：两列 col-box，左右等分弹性。 */
@Composable
fun TwoColBoxes(
    left: ColBoxData,
    right: ColBoxData,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        ColBox(left, modifier = Modifier.weight(1f))
        ColBox(right, modifier = Modifier.weight(1f))
    }
}

data class ColBoxData(val heading: String, val icon: ImageVector, val headingColor: Color, val body: String)

@Composable
private fun ColBox(data: ColBoxData, modifier: Modifier = Modifier) {
    Surface(shape = MeiliShapes.Sm, color = MeiliPalette.SurfaceSoft, modifier = modifier) {
        Column(modifier = Modifier.padding(13.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 7.dp),
            ) {
                Icon(data.icon, contentDescription = null, tint = data.headingColor, modifier = Modifier.size(15.dp))
                Text(
                    data.heading,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.ExtraBold),
                    color = data.headingColor,
                )
            }
            Text(data.body, style = MaterialTheme.typography.bodySmall, color = MeiliPalette.Ink)
        }
    }
}

/** .kv：键值行，底部虚线（这里用细实线近似）。值色可指定。 */
@Composable
fun KvRow(key: String, value: String, valueColor: Color, showDivider: Boolean = true) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 7.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(key, style = MaterialTheme.typography.bodySmall, color = MeiliPalette.Ink2)
            Text(
                value,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                color = valueColor,
            )
        }
        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MeiliPalette.Line),
            )
        }
    }
}

/** 报告体内简单段落（标题 + 正文）。 */
@Composable
fun BodyText(text: String, modifier: Modifier = Modifier, color: Color = MeiliPalette.Ink) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color, modifier = modifier)
}

/** smallnote 小灰注。 */
@Composable
fun SmallNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal),
        color = MeiliPalette.Ink3,
        modifier = modifier,
    )
}
