package com.airec.bledemo.designsystem.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.airec.bledemo.designsystem.Dimens
import com.airec.bledemo.designsystem.MeiliIcons
import com.airec.bledemo.designsystem.MeiliPalette
import com.airec.bledemo.designsystem.MeiliTheme

/**
 * 大「点击开启陪伴」圆钮 [CompanionButton]，还原 warm_2.html 的 .comp-circle：
 *  - 空闲态：陶土径向渐变 + 并蒂花蕊图标（[MeiliIcons.Companion]）+ 浅白光环 .lotus-ring。
 *  - 进行中（[live] = true）：偏玫瑰渐变 + 白色方块「停止」图形 + 呼吸态光晕动画
 *    （对应 CSS @keyframes breathe 2.6s）。
 *
 * 红线：文案走「陪伴」系，绝不出现「录音/录制」。圆钮上方/下方的计时与状态文字
 * 由调用方在 comp-card 里组织（见 [CompanionStage]）。
 *
 * @param live 是否陪伴进行中（呼吸态 + 停止图形）
 * @param onClick 点击切换回调
 * @param starting 唤醒/连接中（已发开始命令、等陪伴笔确认）：转圈 + 偏玫瑰渐变 + 呼吸，点击=取消，明显区别于空闲
 * @param enabled 是否可点（保存中=false：圆钮置灰且不可点，对齐 web 的 btn.disabled）
 */
@Composable
fun CompanionButton(
    live: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    starting: Boolean = false,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    // 进行中或唤醒中都走「活跃」皮肤（玫瑰渐变 + 呼吸光晕）。
    val active = live || starting

    // 呼吸态：光晕环半径 14→26、外层缩放轻微脉动。
    val transition = rememberInfiniteTransition(label = "breathe")
    val glow by transition.animateFloat(
        initialValue = 14f,
        targetValue = if (active) 26f else 14f,
        animationSpec = infiniteRepeatable(
            animation = tween(1300),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glow",
    )

    Box(
        modifier = modifier
            .size(Dimens.CompanionCircle + (glow.dp * 2))
            .scale(if (pressed) 0.96f else 1f),
        contentAlignment = Alignment.Center,
    ) {
        // 外层柔光晕（呼吸时扩散）
        Box(
            modifier = Modifier
                .size(Dimens.CompanionCircle + glow.dp)
                .background(
                    color = (if (active) MeiliPalette.Rose else MeiliPalette.Clay).copy(alpha = 0.07f),
                    shape = CircleShape,
                ),
        )
        // 主圆（保存中置灰半透明）
        Box(
            modifier = Modifier
                .size(Dimens.CompanionCircle)
                .alpha(if (enabled) 1f else 0.5f)
                .background(
                    brush = if (active) MeiliPalette.CompanionLiveGradient else MeiliPalette.CompanionGradient,
                    shape = CircleShape,
                )
                .border(1.5.dp, Color.White.copy(alpha = 0.45f), CircleShape)
                .then(
                    if (enabled) {
                        Modifier.clickable(
                            interactionSource = interaction,
                            indication = null,
                            onClick = onClick,
                        )
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            when {
                starting -> {
                    // 唤醒/连接中：白色转圈，明确「正在准备 · 可再点取消」，不与空闲花蕊混淆。
                    CircularProgressIndicator(
                        modifier = Modifier.size(34.dp),
                        color = Color.White,
                        strokeWidth = 3.dp,
                    )
                }
                live -> {
                    // 停止方块 .comp-square 38×38 白色圆角
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .background(Color.White, androidx.compose.foundation.shape.RoundedCornerShape(11.dp)),
                    )
                }
                else -> {
                    Icon(
                        MeiliIcons.Companion,
                        contentDescription = "开启陪伴",
                        tint = Color.White,
                        modifier = Modifier.size(Dimens.CompanionIcon),
                    )
                }
            }
        }
    }
}

/**
 * 圆钮 + 上方计时 + 下方状态文案的完整陪伴舞台 [CompanionStage]，
 * 对应 .comp-stage（timer + comp-status + 圆钮 + hint）。screen 可直接整块复用。
 *
 * @param timerText 计时文字（如 "00:00" / "12:34"，衬线 tabular）
 * @param statusText 圆钮上方状态（如「点击下方 · 开启今天的陪伴」/「陪伴进行中」）
 * @param hint 圆钮下方小提示
 * @param live 进行中（驱动呼吸态 + 状态前红点）
 * @param onToggle 点击圆钮回调
 * @param starting 唤醒/连接中（圆钮转圈 + 可取消）
 * @param enabled 圆钮是否可点（保存中置灰不可点）
 */
@Composable
fun CompanionStage(
    timerText: String,
    statusText: String,
    hint: String,
    live: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    starting: Boolean = false,
    enabled: Boolean = true,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(timerText, style = MeiliTheme.timerStyle, color = MeiliPalette.Ink)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            modifier = Modifier.padding(top = 3.dp),
        ) {
            if (live) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(MeiliPalette.Rose, CircleShape),
                )
            }
            Text(
                statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = MeiliPalette.Ink2,
            )
        }
        CompanionButton(
            live = live,
            onClick = onToggle,
            starting = starting,
            enabled = enabled,
            modifier = Modifier.padding(vertical = 18.dp),
        )
        Text(
            hint,
            style = MaterialTheme.typography.labelSmall,
            color = MeiliPalette.Ink3,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFFFFCF8, widthDp = 360, heightDp = 380)
@Composable
private fun CompanionIdlePreview() {
    MeiliTheme {
        CompanionStage(
            timerText = "00:00",
            statusText = "点击下方 · 开启今天的陪伴",
            hint = "陪伴结束后，请把这段陪伴绑定到今日接诊里的顾客。",
            live = false,
            onToggle = {},
            modifier = Modifier.padding(20.dp),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFFFFCF8, widthDp = 360, heightDp = 380)
@Composable
private fun CompanionLivePreview() {
    MeiliTheme {
        CompanionStage(
            timerText = "12:34",
            statusText = "陪伴进行中",
            hint = "正在温柔记录这次陪伴 · 结束后绑定顾客即可。",
            live = true,
            onToggle = {},
            modifier = Modifier.padding(20.dp),
        )
    }
}
