package com.airec.bledemo.ui.pending

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airec.bledemo.designsystem.Dimens
import com.airec.bledemo.designsystem.MeiliIcons
import com.airec.bledemo.designsystem.MeiliPalette
import com.airec.bledemo.designsystem.MeiliShapes

/**
 * 试听条 .audio：圆形播放钮 + 进度条 + 当前/总时长。还原 warm_2 的 .audio / .playbtn / .bar / .tt。
 * 试听为「全程」无 60s 上限——进度按真实音频时长走。
 *
 * @param playing 是否正在播放（true 时播放钮可视为暂停态，仍用 play 图标语义保持简洁）
 * @param progress 0f..1f 播放进度
 * @param positionLabel 当前位置 mm:ss
 * @param durationLabel 总时长 mm:ss
 * @param enabled 是否可试听（同步中占位 audio_url 为空时 false）
 */
@Composable
fun AudioPreviewBar(
    playing: Boolean,
    progress: Float,
    positionLabel: String,
    durationLabel: String,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onSeek: (Float) -> Unit = {},
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(vertical = 12.dp),
        shape = MeiliShapes.Sm,
        color = MeiliPalette.SurfaceSoft,
        border = BorderStroke(Dimens.BorderThin, MeiliPalette.Line),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            Surface(
                onClick = onToggle,
                enabled = enabled,
                interactionSource = interaction,
                shape = MeiliShapes.Pill,
                color = MeiliPalette.Clay,
                contentColor = MeiliPalette.White,
                modifier = Modifier
                    .size(Dimens.PlayButton)
                    .scale(if (pressed) 0.94f else 1f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (playing) {
                        // 播放中 = 暂停态：两根白色竖条（无 Pause 图标，内联绘制，保持线性极简）。
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            repeat(2) {
                                Box(
                                    modifier = Modifier
                                        .size(width = 3.5.dp, height = 14.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(MeiliPalette.White),
                                )
                            }
                        }
                    } else {
                        Icon(
                            imageVector = MeiliIcons.Play,
                            contentDescription = "试听",
                            tint = MeiliPalette.White,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                // 进度轨：可点/拖跳播。命中区加高到 22dp 便于手指拖；横向拖动 change.consume() 掉，
                // 避免被外层 Pager 误判成切 tab（视觉轨道仍 7dp，居中）。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(22.dp)
                        .padding(bottom = 2.dp)
                        .then(
                            if (enabled) {
                                Modifier
                                    .pointerInput(Unit) {
                                        detectTapGestures { off ->
                                            val w = size.width.toFloat()
                                            if (w > 0f) onSeek((off.x / w).coerceIn(0f, 1f))
                                        }
                                    }
                                    .pointerInput(Unit) {
                                        detectHorizontalDragGestures { change, _ ->
                                            change.consume() // 关键：吃掉横向拖，Pager 不再误判切 tab
                                            val w = size.width.toFloat()
                                            if (w > 0f) onSeek((change.position.x / w).coerceIn(0f, 1f))
                                        }
                                    }
                            } else {
                                Modifier
                            },
                        ),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(Dimens.TrackHeight)
                            .clip(MeiliShapes.Pill)
                            .background(MeiliPalette.Line),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progress.coerceIn(0f, 1f))
                                .height(Dimens.TrackHeight)
                                .clip(MeiliShapes.Pill)
                                .background(MeiliPalette.TrackGradient),
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(positionLabel, style = MaterialTheme.typography.labelSmall, color = MeiliPalette.Ink3)
                    Text(durationLabel, style = MaterialTheme.typography.labelSmall, color = MeiliPalette.Ink3)
                }
            }
        }
    }
}

/**
 * 提示 banner（对应 warm_2 .banner.{info|warn|danger|clay}）：左侧线性图标 + 多行文案，
 * 可选下划线行动文字（[actionText] + [onAction]）。
 */
@Composable
fun InfoBanner(
    text: String,
    kind: BannerKind,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val (bg, fg, line) = bannerColors(kind)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MeiliShapes.Md,
        color = bg,
        contentColor = fg,
        border = line?.let { BorderStroke(Dimens.BorderThin, it) },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 15.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp).padding(top = 1.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 12.5f.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 19.sp,
                    ),
                    color = fg,
                )
                if (actionText != null && onAction != null) {
                    Text(
                        text = actionText,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 12.5f.sp,
                            fontWeight = FontWeight.Bold,
                            textDecoration = TextDecoration.Underline,
                        ),
                        color = fg,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .clickable(onClick = onAction),
                    )
                }
            }
        }
    }
}

enum class BannerKind { Info, Warn, Danger, Clay }

private fun bannerColors(kind: BannerKind): Triple<Color, Color, Color?> =
    when (kind) {
        BannerKind.Info -> Triple(MeiliPalette.SageTint, MeiliPalette.SageDeep, MeiliPalette.SageSoft)
        BannerKind.Warn -> Triple(MeiliPalette.HoneySoft, MeiliPalette.HoneyText, MeiliPalette.HoneyDeep)
        BannerKind.Danger -> Triple(MeiliPalette.RoseSoft, MeiliPalette.RoseText, MeiliPalette.RoseLine)
        BannerKind.Clay -> Triple(MeiliPalette.ClayTint, MeiliPalette.ClayDeep, MeiliPalette.ClaySoft)
    }

/**
 * 勾选行 .checkrow（陪伴笔机身记录 sheet 用）：左侧方圆角勾选框 + 标题/副标题 + 右侧 pill。
 * 选中态描边 + 陶土 tint 底，勾选框填充陶土显白勾。不可选（已导入/已删）时置灰不可点。
 */
@Composable
fun CheckRow(
    title: String,
    meta: String,
    checked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    dashed: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
) {
    val border = if (checked && enabled) BorderStroke(Dimens.BorderField, MeiliPalette.Clay)
    else BorderStroke(Dimens.BorderField, MeiliPalette.Line)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        shape = MeiliShapes.Sm,
        color = if (checked && enabled) MeiliPalette.ClayTint
        else if (dashed) MeiliPalette.SurfaceSoft else MeiliPalette.Surface,
        contentColor = MeiliPalette.Ink,
        border = border,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            // .cbx 勾选框
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (checked && enabled) MeiliPalette.Clay else MeiliPalette.Surface)
                    .then(
                        if (checked && enabled) Modifier
                        else Modifier.border(Dimens.BorderField, MeiliPalette.Ink4, RoundedCornerShape(7.dp))
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (checked && enabled) {
                    Icon(MeiliIcons.Check, contentDescription = null, tint = MeiliPalette.White, modifier = Modifier.size(15.dp))
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.ExtraBold),
                    color = if (enabled) MeiliPalette.Ink else MeiliPalette.Ink3,
                )
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MeiliPalette.Ink3,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            trailing?.invoke()
        }
    }
}

/**
 * 屏底 toast 浮层（对应 warm_2 .toast）：ink 深底 + 白字 + 可选语义图标，从下淡入。
 * 由 Screen 监听 [ToastEvent]，定时 [onTimeout] 清空。
 */
@Composable
fun PendingToast(
    event: ToastEvent?,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = event != null,
        enter = fadeIn() + slideInVertically(initialOffsetY = { it / 3 }),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        val e = event ?: return@AnimatedVisibility
        Surface(
            shape = MeiliShapes.Sm,
            color = if (e.danger) MeiliPalette.Rose else MeiliPalette.InkSurface,
            contentColor = MeiliPalette.White,
            shadowElevation = 12.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 17.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                toastIcon(e.icon)?.let {
                    Icon(it, contentDescription = null, tint = MeiliPalette.White, modifier = Modifier.size(18.dp))
                }
                Text(
                    text = e.message,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MeiliPalette.White,
                )
            }
        }
    }
}

private fun toastIcon(icon: ToastIcon): ImageVector? = when (icon) {
    ToastIcon.None -> null
    ToastIcon.Check -> MeiliIcons.Check
    ToastIcon.Play -> MeiliIcons.Play
    ToastIcon.Sync -> MeiliIcons.Sync
    ToastIcon.Warn -> MeiliIcons.Warn
}
