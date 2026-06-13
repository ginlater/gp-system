package com.airec.bledemo.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airec.bledemo.designsystem.MeiliIcons
import com.airec.bledemo.designsystem.MeiliPalette
import com.airec.bledemo.designsystem.MeiliTheme

/**
 * 区块小标题 [SectionLabel]，还原 warm_2.html 的 .section-lbl：
 * 11.5sp / 800 / ink-2 字、0.5 字距、可选前置鼠尾草色图标（15dp）。
 *
 * 用于「陪伴设备」「本次将分析的陪伴」「累积标签」等分组标题。
 *
 * @param text 标题文字
 * @param icon 可选前置线性图标（默认鼠尾草深色，呼应原型 .section-lbl .ic）
 */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Row(
        modifier = modifier.padding(horizontal = 2.dp, vertical = 0.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = MeiliPalette.SageDeep,
                modifier = Modifier.size(15.dp),
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 11.5f.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.5.sp,
            ),
            color = MeiliPalette.Ink2,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFFFFCF8, widthDp = 360)
@Composable
private fun SectionLabelPreview() {
    MeiliTheme {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionLabel("陪伴设备", icon = MeiliIcons.Wifi)
            SectionLabel("本次将分析的陪伴（已绑定）", icon = MeiliIcons.Link)
            SectionLabel("累积标签 · 画像积累", icon = MeiliIcons.Star)
        }
    }
}
