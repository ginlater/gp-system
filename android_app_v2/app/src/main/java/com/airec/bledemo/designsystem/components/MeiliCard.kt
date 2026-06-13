package com.airec.bledemo.designsystem.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.airec.bledemo.designsystem.Dimens
import com.airec.bledemo.designsystem.MeiliPalette
import com.airec.bledemo.designsystem.MeiliShapes
import com.airec.bledemo.designsystem.MeiliTheme

/**
 * 柔光卡片，还原 warm_2.html 的 .card：
 * surface 底、r-lg(24) 圆角、--sh-2 柔光阴影、line-soft 细描边、默认内边距 20。
 *
 * @param modifier 外部布局修饰
 * @param tight 紧凑内边距（对应 .card.tight padding:15）
 * @param padded 是否套用内边距；设为 false 时内容自行控制（如顶部满宽 audio 行）
 * @param shape 圆角形状，默认 r-lg
 * @param content 卡片内容，置于 [ColumnScope]
 */
@Composable
fun MeiliCard(
    modifier: Modifier = Modifier,
    tight: Boolean = false,
    padded: Boolean = true,
    shape: Shape = MeiliShapes.Lg,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = MeiliPalette.Surface,
        contentColor = MeiliPalette.Ink,
        border = BorderStroke(Dimens.BorderThin, MeiliPalette.LineSoft),
        shadowElevation = Dimens.Elev2,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = if (padded) {
                Modifier.padding(if (tight) Dimens.CardPadTight else Dimens.CardPad)
            } else {
                Modifier
            },
            content = content,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF8F3ED, widthDp = 360)
@Composable
private fun MeiliCardPreview() {
    MeiliTheme {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MeiliCard {
                Text("柔光卡 · 默认 padding 20", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
                Text(
                    "陪伴结束后，请把这段陪伴绑定到今日接诊里的顾客。",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = MeiliPalette.Ink3,
                )
            }
            MeiliCard(tight = true) {
                Text("紧凑卡 · padding 15", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            }
        }
    }
}
