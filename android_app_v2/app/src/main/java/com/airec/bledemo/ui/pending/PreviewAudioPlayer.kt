package com.airec.bledemo.ui.pending

import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.airec.bledemo.data.repo.ApiResult
import com.airec.bledemo.data.repo.ConsultantRepository
import com.airec.bledemo.designsystem.Dimens
import com.airec.bledemo.designsystem.MeiliIcons
import com.airec.bledemo.designsystem.MeiliPalette
import com.airec.bledemo.designsystem.MeiliShapes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ───────────────────────── 试听播放器 ─────────────────────────

/**
 * 单条片段的试听条：用 [MediaPlayer] 流式播放（全程无 60s 上限）并轮询进度。
 * 有 [directUrl]（服务端已带 audio_url）就直接播；没有才拉 [ConsultantRepository.recordingUrl]。
 * 同步中占位（[processing]）不可试听。播放态经 [onPlayingChange] 上报给屏（自动轮询据此跳过）。
 */
/**
 * 试听播放控制器：托管 [MediaPlayer] + 进度轮询。UI 拆两处放——
 * 行内小播放钮 [PreviewPlayDot]（跟服务日期并排，省面积）+ 展开后的进度条 [PreviewTrack]（点了播放才出现、可拖拽跳播）。
 * 全程无 60s 上限；processing（同步中）不可试听；播放态经 onPlayingChange 上报屏级（自动轮询据此跳过）。
 */
internal class PreviewAudioController {
    var expanded by mutableStateOf(false)
    var playing by mutableStateOf(false)
    var positionMs by mutableIntStateOf(0)
    var durationMs by mutableIntStateOf(0)
    var loading by mutableStateOf(false)
    var processing by mutableStateOf(false)
    var toggleImpl: () -> Unit = {}
    var seekImpl: (Float) -> Unit = {}
    fun toggle() = toggleImpl()
    fun seek(frac: Float) = seekImpl(frac)
    val progressFrac: Float get() = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
    val canPreview: Boolean get() = !processing && !loading
}

/** 建一个试听控制器（托管 MediaPlayer + 轮询 + 取 URL）。在卡片里：行内放 [PreviewPlayDot]，下方放 [PreviewTrack]。 */
@Composable
internal fun rememberPreviewAudio(
    recordingId: Long,
    directUrl: String?,
    processing: Boolean,
    onPlayingChange: (Boolean) -> Unit,
    onPreviewToast: (String) -> Unit,
): PreviewAudioController {
    val repo = remember { ConsultantRepository() }
    val scope = rememberCoroutineScope()
    val ctrl = remember(recordingId) { PreviewAudioController() }
    ctrl.processing = processing
    var player by remember(recordingId) { mutableStateOf<MediaPlayer?>(null) }

    LaunchedEffect(ctrl.playing) { onPlayingChange(ctrl.playing) }
    DisposableEffect(recordingId) {
        onDispose {
            onPlayingChange(false)
            player?.release()
            player = null
        }
    }
    LaunchedEffect(ctrl.playing, player) {
        while (ctrl.playing && player != null) {
            val p = player ?: break
            ctrl.positionMs = runCatching { p.currentPosition }.getOrDefault(ctrl.positionMs)
            delay(250)
        }
    }

    fun startWith(url: String) {
        val mp = MediaPlayer()
        runCatching {
            mp.setDataSource(url)
            mp.setOnPreparedListener {
                ctrl.durationMs = it.duration
                it.start()
                ctrl.playing = true
                onPreviewToast("试听播放中")
            }
            mp.setOnCompletionListener {
                ctrl.playing = false
                ctrl.positionMs = 0
                ctrl.expanded = false // 播完自动收起进度条，回到行内小钮（省空间）
            }
            mp.prepareAsync()
            player = mp
        }.onFailure {
            mp.release()
            ctrl.expanded = false
            onPreviewToast("试听失败，请稍后重试")
        }
    }

    ctrl.toggleImpl = toggle@{
        if (ctrl.processing) return@toggle
        val existing = player
        if (existing != null) {
            if (ctrl.playing) {
                existing.pause(); ctrl.playing = false
            } else {
                existing.start(); ctrl.playing = true; onPreviewToast("试听播放中")
            }
            return@toggle
        }
        // 首次：展开 + 起播（有现成 url 直接播，否则取 URL；失败收回）
        ctrl.expanded = true
        val ready = directUrl?.takeIf { it.isNotBlank() }
        if (ready != null) {
            startWith(ready)
            return@toggle
        }
        ctrl.loading = true
        scope.launch {
            when (val r = repo.recordingUrl(recordingId)) {
                is ApiResult.Success -> { ctrl.loading = false; startWith(r.data) }
                is ApiResult.Failure -> { ctrl.loading = false; ctrl.expanded = false; onPreviewToast(r.message) }
            }
        }
    }
    ctrl.seekImpl = { frac ->
        val p = player
        if (p != null && ctrl.durationMs > 0) {
            val target = (frac * ctrl.durationMs).toInt().coerceIn(0, ctrl.durationMs)
            runCatching { p.seekTo(target) }
            ctrl.positionMs = target
        }
    }
    return ctrl
}

/** 行内小播放/暂停圆钮（跟服务日期并排）：未展开时点一下=展开下方进度条 + 起播；播放中=暂停。 */
@Composable
internal fun PreviewPlayDot(controller: PreviewAudioController, modifier: Modifier = Modifier) {
    Surface(
        onClick = { controller.toggle() },
        enabled = !controller.processing,
        shape = MeiliShapes.Pill,
        color = MeiliPalette.Clay,
        contentColor = MeiliPalette.White,
        modifier = modifier.size(Dimens.PlayButton),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (controller.playing) {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(2) {
                        Box(
                            modifier = Modifier
                                .size(width = 3.5.dp, height = 14.dp)
                                .background(MeiliPalette.White, MeiliShapes.Xs),
                        )
                    }
                }
            } else {
                Icon(MeiliIcons.Play, contentDescription = "试听", tint = MeiliPalette.White, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** 展开后的进度条（点播放才出现）：可拖拽跳播 + 当前/总时长，无播放钮（钮在行内 [PreviewPlayDot]）。 */
@Composable
internal fun PreviewTrack(controller: PreviewAudioController, modifier: Modifier = Modifier) {
    if (!controller.expanded) return
    val durLabel = controller.durationMs.takeIf { it > 0 }?.let { msToLabel(it) } ?: "--:--"
    AudioPreviewBar(
        playing = controller.playing,
        progress = controller.progressFrac,
        positionLabel = msToLabel(controller.positionMs),
        durationLabel = durLabel,
        enabled = controller.canPreview,
        onToggle = { controller.toggle() },
        onSeek = { controller.seek(it) },
        showButton = false,
        modifier = modifier,
    )
}

private fun msToLabel(ms: Int): String {
    val sec = ms / 1000
    return "%02d:%02d".format(sec / 60, sec % 60)
}
