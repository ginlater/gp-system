package com.airec.bledemo.ui.report

import android.media.MediaPlayer
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airec.bledemo.data.model.SessionRecording
import com.airec.bledemo.designsystem.Dimens
import com.airec.bledemo.designsystem.MeiliIcons
import com.airec.bledemo.designsystem.MeiliPalette
import com.airec.bledemo.designsystem.MeiliShapes
import com.airec.bledemo.designsystem.components.GhostButton
import com.airec.bledemo.designsystem.components.MeiliButtonSize
import com.airec.bledemo.designsystem.components.SectionLabel
import com.airec.bledemo.designsystem.components.SoftButton
import kotlinx.coroutines.delay

/* ===================================================================
 * 原始音频折叠卡（warm_2 #report .fold #audioFold + report.html recPanel）。
 * 整屏唯一入口：顶部「原始音频」按钮开关本卡。
 * 卡内：段落选择（多段录音） + 真·全程播放条（MediaPlayer，无 60s 限制，可拖动 / 跳播）
 *       + 说话人>2 警告条（确认照常分析 / 解绑此录音段）
 *       + 逐字转写（独立可折叠）+（仅店长/管理员）在当前位置「分割」。
 * =================================================================== */

/**
 * 原始音频折叠卡。由 [open] 控制展开（顶部「原始音频」动作行按钮即开关）。
 * 多段录音由 [recordings] + [segIndex] 决定，切段走 [onSelectSegment]；
 * Case 时间戳跳播通过 [seekToSeconds]（一次性秒数，消费后由 [onSeekConsumed] 清掉）。
 *
 * @param open 是否展开
 * @param recordings 全部音频段落（0/1/n 段）
 * @param segIndex 当前选中段下标
 * @param audioUrl 当前选中段的播放地址（懒取；null 且 [loadingUrl]=false 视为暂不可听）
 * @param loadingUrl 正在取当前段播放地址
 * @param seekToSeconds 一次性跳播目标（秒），非 null 时播放器 seek 到该位置并起播
 * @param onSeekConsumed 跳播执行后回执（清掉一次性 token）
 * @param onSelectSegment 切换段落
 * @param needsSpeakerConfirm 当前段是否说话人>2 警告且未确认
 * @param speakerCount 检测到的说话人数（提示用）
 * @param confirming 说话人确认/解绑/分割提交中（按钮禁用）
 * @param onConfirmKeep 确认照常分析（confirmSpeakers "keep"）
 * @param onConfirmUnbind 解绑此录音段（confirmSpeakers "unbind"）
 * @param isManager 是否店长/管理员（决定「分割」入口是否显示；splitRecording @manager_required）
 * @param onSplitAt 在指定秒处分割当前段（仅 [isManager] 为真时调用）
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AudioFold(
    open: Boolean,
    recordings: List<SessionRecording>,
    segIndex: Int,
    audioUrl: String?,
    loadingUrl: Boolean,
    seekToSeconds: Int?,
    onSeekConsumed: () -> Unit,
    onSelectSegment: (Int) -> Unit,
    needsSpeakerConfirm: Boolean,
    speakerCount: Int?,
    confirming: Boolean,
    onConfirmKeep: () -> Unit,
    onConfirmUnbind: () -> Unit,
    isManager: Boolean,
    onSplitAt: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rec = recordings.getOrNull(segIndex) ?: recordings.firstOrNull()
    val canListen = audioUrl != null || loadingUrl || rec != null

    AnimatedVisibility(
        visible = open,
        enter = expandVertically(tween(220)) + fadeIn(tween(220)),
        exit = shrinkVertically(tween(180)) + fadeOut(tween(120)),
        modifier = modifier,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MeiliShapes.Lg,
            color = MeiliPalette.Surface,
            contentColor = MeiliPalette.Ink,
            border = BorderStroke(Dimens.BorderThin, MeiliPalette.LineSoft),
            shadowElevation = Dimens.Elev2,
        ) {
            Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp)) {
                // ---- 段落选择（多段录音才显示）----
                if (recordings.size > 1) {
                    SegmentPicker(recordings = recordings, segIndex = segIndex, onSelect = onSelectSegment)
                }
                // ---- 全程播放条（真 MediaPlayer，支持跳播 / 切段）----
                PlaybackBar(
                    audioUrl = audioUrl,
                    loadingUrl = loadingUrl,
                    canListen = canListen,
                    seekToSeconds = seekToSeconds,
                    onSeekConsumed = onSeekConsumed,
                    isManager = isManager,
                    confirming = confirming,
                    onSplitAt = onSplitAt,
                )
                // ---- 说话人>2 警告条（confirmSpeakers）----
                if (needsSpeakerConfirm) {
                    SpeakerWarnBox(
                        speakerCount = speakerCount,
                        confirming = confirming,
                        onKeep = onConfirmKeep,
                        onUnbind = onConfirmUnbind,
                    )
                }
                // ---- 逐字转写（当前段；独立可折叠，默认展开）----
                TranscriptFold(transcript = rec?.asrTranscript)
            }
        }
    }
}

/** 段落选择（report.html segSelect）。横排 pill，选中态陶土底。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SegmentPicker(
    recordings: List<SessionRecording>,
    segIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = 13.dp)) {
        SectionLabel("选择段落", icon = MeiliIcons.Album, modifier = Modifier.padding(bottom = 9.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            recordings.forEachIndexed { i, r ->
                val on = i == segIndex
                val bg = if (on) MeiliPalette.Clay else MeiliPalette.SurfaceSoft
                val fg = if (on) MeiliPalette.White else MeiliPalette.Ink2
                val border = if (on) MeiliPalette.Clay else MeiliPalette.Line
                Surface(
                    shape = MeiliShapes.Pill,
                    color = bg,
                    contentColor = fg,
                    border = BorderStroke(Dimens.BorderThin, border),
                    modifier = Modifier.clickable { onSelect(i) },
                ) {
                    Text(
                        text = segmentLabel(i, r),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = fg,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

private fun segmentLabel(index: Int, rec: SessionRecording): String {
    val dur = rec.durationLabel?.takeIf { it.isNotBlank() }
    return if (dur != null) "第${index + 1}段 · 约$dur" else "第${index + 1}段"
}

/**
 * 全程播放条（.audio）：圆形播放/暂停钮 + 可拖动进度轨 + 当前/总时长。无 60s 限制。
 * 用 [MediaPlayer] 真播放；进度每 250ms 轮询；轨道点击/拖动 seek。
 * [seekToSeconds] 非 null（Case 时间戳跳播）时，准备就绪后 seek 到该秒并起播。
 * 当 [audioUrl] 变化（切段）时重建播放器。
 */
@Composable
private fun PlaybackBar(
    audioUrl: String?,
    loadingUrl: Boolean,
    canListen: Boolean,
    seekToSeconds: Int?,
    onSeekConsumed: () -> Unit,
    isManager: Boolean,
    confirming: Boolean,
    onSplitAt: (Double) -> Unit,
) {
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var prepared by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var positionMs by remember { mutableIntStateOf(0) }
    var durationMs by remember { mutableIntStateOf(0) }
    var trackWidthPx by remember { mutableIntStateOf(0) }

    // 切段 / 拿到地址：重建播放器（preload，不自动播放，避免一展开就响）。
    DisposableEffect(audioUrl) {
        prepared = false
        playing = false
        positionMs = 0
        durationMs = 0
        val url = audioUrl
        val mp = if (url != null) {
            MediaPlayer().also { m ->
                runCatching {
                    m.setDataSource(url)
                    m.setOnPreparedListener {
                        durationMs = it.duration
                        prepared = true
                    }
                    m.setOnCompletionListener {
                        playing = false
                        positionMs = 0
                    }
                    m.prepareAsync()
                }.onFailure { m.release() }
            }
        } else null
        player = mp
        onDispose { mp?.release() }
    }

    // 播放时轮询进度
    LaunchedEffect(playing, player) {
        while (playing && player != null) {
            val p = player ?: break
            positionMs = runCatching { p.currentPosition }.getOrDefault(positionMs)
            delay(250)
        }
    }

    // Case 时间戳跳播：准备就绪后 seek 到目标秒并起播
    LaunchedEffect(seekToSeconds, prepared, player) {
        val sec = seekToSeconds ?: return@LaunchedEffect
        val p = player ?: return@LaunchedEffect
        if (!prepared) return@LaunchedEffect
        val target = (sec * 1000).coerceIn(0, durationMs.takeIf { it > 0 } ?: Int.MAX_VALUE)
        runCatching {
            p.seekTo(target)
            p.start()
        }
        positionMs = target
        playing = true
        onSeekConsumed()
    }

    Surface(
        shape = MeiliShapes.Sm,
        color = MeiliPalette.SurfaceSoft,
        border = BorderStroke(Dimens.BorderThin, MeiliPalette.Line),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 圆形播放 / 暂停钮
                Box(
                    modifier = Modifier
                        .size(Dimens.PlayButton)
                        .background(MeiliPalette.PrimaryGradient, RoundedCornerShape(50))
                        .clickable(enabled = prepared) {
                            val p = player ?: return@clickable
                            if (playing) {
                                runCatching { p.pause() }; playing = false
                            } else {
                                runCatching { p.start() }; playing = true
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (loadingUrl || (audioUrl != null && !prepared)) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = MeiliPalette.White,
                            strokeWidth = 2.dp,
                        )
                    } else if (playing) {
                        // 播放中 = 暂停态：两根白色竖条（无 Pause 图标，内联绘制，对齐试听条约定）。
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            repeat(2) {
                                Box(
                                    modifier = Modifier
                                        .size(width = 3.5.dp, height = 14.dp)
                                        .background(MeiliPalette.White, RoundedCornerShape(2.dp)),
                                )
                            }
                        }
                    } else {
                        Icon(
                            MeiliIcons.Play,
                            contentDescription = "播放原始音频",
                            tint = MeiliPalette.White,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                }
                // 进度轨（可点 / 拖动 seek）+ 时间
                Column(modifier = Modifier.weight(1f)) {
                    val frac = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(Dimens.TrackHeight)
                            .background(MeiliPalette.Line, MeiliShapes.Pill)
                            .onGloballyPositioned { trackWidthPx = it.size.width }
                            .pointerInput(prepared, durationMs) {
                                if (!prepared || durationMs <= 0) return@pointerInput
                                detectTapGestures { offset ->
                                    val w = trackWidthPx.takeIf { it > 0 } ?: return@detectTapGestures
                                    val target = ((offset.x / w).coerceIn(0f, 1f) * durationMs).toInt()
                                    val p = player ?: return@detectTapGestures
                                    runCatching { p.seekTo(target) }
                                    positionMs = target
                                }
                            },
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(frac)
                                .height(Dimens.TrackHeight)
                                .background(MeiliPalette.TrackGradient, MeiliShapes.Pill),
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = when {
                                !canListen && audioUrl == null -> "无收听权限"
                                else -> msToLabel(positionMs)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MeiliPalette.Ink3,
                        )
                        Text(
                            text = if (durationMs > 0) msToLabel(durationMs) else "--:--",
                            style = MaterialTheme.typography.labelSmall,
                            color = MeiliPalette.Ink3,
                        )
                    }
                }
            }
            // ---- 分割（仅店长/管理员；在当前播放位置切两段）----
            if (isManager) {
                SoftButton(
                    text = "在当前位置分割为两段",
                    onClick = {
                        if (durationMs <= 0) return@SoftButton
                        onSplitAt(positionMs / 1000.0)
                    },
                    icon = MeiliIcons.Unbind,
                    size = MeiliButtonSize.Xs,
                    enabled = prepared && durationMs > 0 && !confirming,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 11.dp),
                )
            }
        }
    }
}

/** 说话人>2 警告条（report.html speakerWarnBox）。确认照常分析 / 解绑此录音段。 */
@Composable
private fun SpeakerWarnBox(
    speakerCount: Int?,
    confirming: Boolean,
    onKeep: () -> Unit,
    onUnbind: () -> Unit,
) {
    Surface(
        shape = MeiliShapes.Md,
        color = MeiliPalette.HoneySoft,
        contentColor = MeiliPalette.HoneyText,
        border = BorderStroke(Dimens.BorderThin, androidx.compose.ui.graphics.Color(0xFFE8D2A6)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Icon(
                    MeiliIcons.Warn,
                    contentDescription = null,
                    tint = MeiliPalette.HoneyText,
                    modifier = Modifier.padding(top = 1.dp).size(18.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "检测到 ${speakerCount ?: "多"} 位说话人（多于 2 人），分析已暂停",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.ExtraBold),
                        color = MeiliPalette.HoneyText,
                    )
                    Text(
                        "可能混入了第三人。请对照下方逐字转写核对，再决定是照常分析还是解绑此段。",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal),
                        color = MeiliPalette.Ink3,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            Row(
                modifier = Modifier.padding(top = 11.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                SoftButton(
                    text = "仍然分析",
                    onClick = onKeep,
                    icon = MeiliIcons.Check,
                    size = MeiliButtonSize.Small,
                    enabled = !confirming,
                    modifier = Modifier.weight(1f),
                )
                GhostButton(
                    text = "解绑此片段",
                    onClick = onUnbind,
                    icon = MeiliIcons.Unbind,
                    size = MeiliButtonSize.Small,
                    enabled = !confirming,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 逐字转写子折叠（.subfold），默认展开，可单独收起。 */
@Composable
private fun TranscriptFold(transcript: String?) {
    var open by remember { mutableStateOf(true) }
    val chevRotation by animateFloatAsState(
        targetValue = if (open) 180f else 0f,
        animationSpec = tween(250),
        label = "transcriptChev",
    )
    val lines = remember(transcript) { parseTranscript(transcript) }

    Column(modifier = Modifier.padding(top = 14.dp)) {
        // 表头（可点 + chevron）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(horizontal = 2.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabel("逐字转写", icon = MeiliIcons.Waveform, modifier = Modifier.weight(1f))
            Icon(
                MeiliIcons.ChevDown,
                contentDescription = if (open) "收起" else "展开",
                tint = MeiliPalette.Ink3,
                modifier = Modifier
                    .size(16.dp)
                    .rotate(chevRotation),
            )
        }
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(tween(200)) + fadeIn(tween(200)),
            exit = shrinkVertically(tween(160)) + fadeOut(tween(120)),
        ) {
            Column(modifier = Modifier.padding(top = 9.dp)) {
                if (lines.isEmpty()) {
                    Surface(
                        shape = MeiliShapes.Sm,
                        color = MeiliPalette.SurfaceSoft,
                        border = BorderStroke(Dimens.BorderThin, MeiliPalette.Line),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "（暂无转写文本）",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MeiliPalette.Ink3,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                } else {
                    Surface(
                        shape = MeiliShapes.Sm,
                        color = MeiliPalette.SurfaceSoft,
                        border = BorderStroke(Dimens.BorderThin, MeiliPalette.Line),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier
                                .heightIn(max = 236.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                        ) {
                            lines.forEachIndexed { idx, line ->
                                TranscriptLine(line, showDivider = idx != lines.lastIndex)
                            }
                        }
                    }
                }
                Text(
                    "AI 自动转写，仅供顾问复盘参考。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MeiliPalette.Ink3,
                    fontWeight = FontWeight.Normal,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun TranscriptLine(line: TranscriptLineData, showDivider: Boolean) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 9.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (line.speaker != null) {
                val (bg, fg) = when (line.speakerKind) {
                    SpeakerKind.Advisor -> MeiliPalette.ClayTint to MeiliPalette.ClayDeep
                    SpeakerKind.Customer -> MeiliPalette.SageTint to MeiliPalette.SageDeep
                    else -> MeiliPalette.SurfaceSoft to MeiliPalette.Ink2
                }
                Surface(shape = MeiliShapes.Pill, color = bg, contentColor = fg) {
                    Text(
                        line.speaker,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            if (line.timestamp != null) {
                Text(
                    line.timestamp,
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp),
                    color = MeiliPalette.Ink3,
                    modifier = Modifier.padding(start = 7.dp, end = 8.dp, top = 1.dp),
                )
            }
            Text(
                line.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MeiliPalette.Ink,
                modifier = Modifier.weight(1f),
            )
        }
        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MeiliPalette.LineSoft),
            )
        }
    }
}

// ─────────────────────────── helpers ───────────────────────────

/** 毫秒 → mm:ss（超过 1 小时则 h:mm:ss）。 */
private fun msToLabel(ms: Int): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

// ─────────────────────────── 转写解析 ───────────────────────────

enum class SpeakerKind { Advisor, Customer, Unknown }

data class TranscriptLineData(
    val speaker: String?,
    val speakerKind: SpeakerKind,
    val timestamp: String?,
    val text: String,
)

/**
 * 解析后端 asr_transcript（纯文本）。后端按行存，可能形如：
 *   "[00:12] 陪伴师：……" / "陪伴师 00:12 ……" / 纯一段文字。
 * 尽量抠出 说话人 + 时间戳；抠不到就整行当正文。空字符串 → 空列表（UI 显示占位）。
 */
fun parseTranscript(raw: String?): List<TranscriptLineData> {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return emptyList()

    val tsRegex = Regex("""(\d{1,2}:\d{2}(?::\d{2})?)""")
    return text.split('\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { line ->
            var rest = line
            var speaker: String? = null
            var kind = SpeakerKind.Unknown

            // 说话人前缀：陪伴师 / 顾问 / 客户 / 顾客 / Speaker1 / 说话人1，紧跟 ：或 :
            // 仅当能识别为陪伴师/顾客时才剥离，避免把正文里的「您好：…」误当说话人。
            val spkMatch = Regex("""^([^\s:：\[\]]{1,8})\s*[:：]""").find(rest)
            if (spkMatch != null) {
                val name = spkMatch.groupValues[1]
                kind = classifySpeaker(name)
                if (kind != SpeakerKind.Unknown) {
                    speaker = normalizeSpeaker(name, kind)
                    rest = rest.removeRange(spkMatch.range).trim()
                }
            }

            // 时间戳：行内第一个出现的 mm:ss / hh:mm:ss（去括号）
            var timestamp: String? = null
            val tsMatch = tsRegex.find(rest)
            if (tsMatch != null) {
                timestamp = tsMatch.value
                rest = rest.replaceFirst(Regex("""\[?\s*""" + Regex.escape(timestamp) + """\s*\]?"""), "").trim()
            }

            TranscriptLineData(
                speaker = speaker,
                speakerKind = kind,
                timestamp = timestamp,
                text = if (rest.isEmpty()) line else rest,
            )
        }
}

private fun classifySpeaker(name: String): SpeakerKind = when {
    name.contains("陪伴") || name.contains("顾问") || name.contains("销售") ||
        name.contains("师") || name.contains("S1") || name.contains("说话人1") || name == "1" -> SpeakerKind.Advisor
    name.contains("顾客") || name.contains("客户") || name.contains("客") ||
        name.contains("S2") || name.contains("说话人2") || name == "2" -> SpeakerKind.Customer
    else -> SpeakerKind.Unknown
}

private fun normalizeSpeaker(name: String, kind: SpeakerKind): String = when (kind) {
    SpeakerKind.Advisor -> "陪伴师"
    SpeakerKind.Customer -> "顾客"
    else -> name
}
