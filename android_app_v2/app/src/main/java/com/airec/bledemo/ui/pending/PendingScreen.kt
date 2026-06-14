package com.airec.bledemo.ui.pending

import android.media.MediaPlayer
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.airec.bledemo.data.model.NeedsConfirmRecording
import com.airec.bledemo.data.model.PendingRecording
import com.airec.bledemo.recording.RecordingModule
import com.airec.bledemo.designsystem.Dimens
import com.airec.bledemo.designsystem.MeiliIcons
import com.airec.bledemo.designsystem.MeiliPalette
import com.airec.bledemo.designsystem.MeiliShapes
import com.airec.bledemo.designsystem.MeiliTheme
import com.airec.bledemo.designsystem.components.GhostButton
import com.airec.bledemo.designsystem.components.MeiliBottomSheet
import com.airec.bledemo.designsystem.components.MeiliButtonSize
import com.airec.bledemo.designsystem.components.MeiliCard
import com.airec.bledemo.designsystem.components.MeiliTopBar
import com.airec.bledemo.designsystem.components.PillKind
import com.airec.bledemo.designsystem.components.PrimaryButton
import com.airec.bledemo.designsystem.components.SoftButton
import com.airec.bledemo.designsystem.components.StatusPill
import com.airec.bledemo.designsystem.components.TopBarIconButton
import com.airec.bledemo.data.repo.ApiResult
import com.airec.bledemo.data.repo.ConsultantRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 待整理（SPEC §4.3 / warm_2 #pending）。
 *
 * 未绑定的陪伴片段列表，每条：时段 / 服务日期 / 时长 / 说话人数 / 「试听」(全程,无 60s) /
 * 「未绑定」pill / 「绑定顾客」按钮。顶部「刷新」+「从陪伴笔同步」。空态；误录（多说话人）
 * 与跨日提示 banner。「从陪伴笔同步」唤起 [MeiliBottomSheet] 勾选机身片段导入。
 *
 * @param onBindCustomer 某片段→绑定顾客（recordingId），由 [com.airec.bledemo.nav.AppScaffold] 注入导航到 BindCustomer
 * @param modifier 由 AppScaffold 传入（含底栏避让 padding）
 * @param viewModel 屏状态（默认 [viewModel]）
 */
@Composable
fun PendingScreen(
    onBindCustomer: (recordingId: Long) -> Unit = {},
    modifier: Modifier = Modifier,
    // 从陪伴首页「从陪伴笔同步」入口跳来时为 true → 进页即自动打开同步 sheet（对齐 web 一键直达，不用再点一次）。
    openSyncSignal: Boolean = false,
    onSyncSignalConsumed: () -> Unit = {},
    // 注入共享录音引擎：「从陪伴笔同步」要靠它拉机身片段 / 导入。controller 是构造参数，故走 Factory。
    viewModel: PendingViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                PendingViewModel(controller = RecordingModule.controller) as T
        },
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val penSync by viewModel.penSync.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()

    // 试听播放态（按片段 id）——抬到屏级，自动轮询据此「播放中跳过」。
    val playingIds = remember { mutableStateMapOf<Long, Boolean>() }
    val anyPlaying by remember { derivedStateOf { playingIds.values.any { it } } }

    // 屏内 sheet / dialog 状态（不碰 nav）
    var delReasonFor by remember { mutableStateOf<Long?>(null) }       // 「申请删除」原因弹窗目标 rid
    var confirmUnbindFor by remember { mutableStateOf<Long?>(null) }   // 误录「解绑拆分重传」二次确认 rid
    var confirmImport by remember { mutableStateOf(false) }           // 「导入选中」条数二次确认

    // toast 自动消失（warm_2 ~2.2s）
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(2200)
            viewModel.toastShown()
        }
    }

    // 从首页「从陪伴笔同步」跳来：进页即自动打开同步 sheet，消费一次性信号避免重开。
    LaunchedEffect(openSyncSignal) {
        if (openSyncSignal) {
            viewModel.openPenSync()
            onSyncSignalConsumed()
        }
    }

    // 自动刷新：可见时每 ~5s 轮询一次；试听播放中跳过，避免打断（对齐网页端 5s 自动刷新）。
    // collectAsStateWithLifecycle + 该 effect 仅在屏组合时运行 ⇒ 不在前台/不可见自然不轮询。
    LaunchedEffect(Unit) {
        while (true) {
            delay(5000)
            if (!anyPlaying && !penSync.visible) viewModel.poll()
        }
    }

    Box(modifier = modifier.fillMaxSize().background(MeiliTheme.colors.bg)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.ScreenH),
        ) {
            MeiliTopBar(
                title = "待整理",
                subtitle = "回顾每一次陪伴 · 没绑定顾客的陪伴不会进入分析",
                actions = {
                    GhostButton(
                        text = "从陪伴笔同步",
                        onClick = viewModel::openPenSync,
                        icon = MeiliIcons.Sync,
                        size = MeiliButtonSize.Xs,
                    )
                    TopBarIconButton(MeiliIcons.Refresh, onClick = viewModel::refresh)
                },
            )

            // 跨日提示 banner（列出与当前接诊不一致的具体日期）
            if (state.crossDayDates.isNotEmpty()) {
                val dates = state.crossDayDates
                InfoBanner(
                    text = "有 ${dates.size} 段陪伴的日期与当前接诊不一致（${dates.joinToString("、")}），" +
                        "记得在「绑定顾客」时选对应的服务日期。",
                    kind = BannerKind.Warn,
                    icon = MeiliIcons.Warn,
                    modifier = Modifier.padding(bottom = Dimens.CardGap),
                )
            }

            // 误录（多说话人）逐条可操作卡
            if (state.needsConfirm.isNotEmpty()) {
                NeedsConfirmSection(
                    items = state.needsConfirm,
                    busyIds = state.busyIds,
                    onKeep = { viewModel.confirmSpeakers(it, "keep") },
                    onUnbind = { confirmUnbindFor = it },
                    onPlayingChange = { id, p -> playingIds[id] = p },
                    onPreviewToast = { viewModel.showToast(it, ToastIcon.Play) },
                    modifier = Modifier.padding(bottom = Dimens.CardGap),
                )
            }

            // 加载失败
            state.error?.let { msg ->
                InfoBanner(
                    text = msg,
                    kind = BannerKind.Danger,
                    icon = MeiliIcons.Warn,
                    modifier = Modifier.padding(bottom = Dimens.CardGap),
                )
            }

            when {
                state.loading && state.recordings.isEmpty() -> LoadingBlock()
                state.isEmpty -> EmptyBlock()
                else -> {
                    // 客户端分页（每页 5 段，对齐网页端）：只渲染当前页，长列表不卡。
                    state.pagedRecordings.forEach { rec ->
                        PendingRecordingCard(
                            rec = rec,
                            busy = rec.id in state.busyIds,
                            canRetry = viewModel.canRetryPenUploads,
                            onBindCustomer = onBindCustomer,
                            onRequestDelete = { delReasonFor = rec.id },
                            onWithdrawDelete = { viewModel.withdrawDelete(rec.id) },
                            onDismissReject = { viewModel.dismissDeleteReject(rec.id) },
                            onRetry = viewModel::retryPenUploads,
                            onPlayingChange = { p -> playingIds[rec.id] = p },
                            onPreviewToast = { viewModel.showToast(it, ToastIcon.Play) },
                            modifier = Modifier.padding(bottom = Dimens.CardGap),
                        )
                    }
                    if (state.totalPages > 1) {
                        PendingPager(
                            page = state.page,
                            totalPages = state.totalPages,
                            total = state.recordings.size,
                            onPrev = { viewModel.goPage(-1) },
                            onNext = { viewModel.goPage(1) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(Dimens.BottomNavInset))
        }

        // 屏底 toast
        PendingToast(
            event = toast,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = Dimens.ScreenH, vertical = 18.dp),
        )
    }

    // 「从陪伴笔同步」sheet（导入前先二次确认条数）
    PenSyncSheet(
        state = penSync,
        onDismiss = viewModel::closePenSync,
        onToggleRow = viewModel::togglePenRow,
        onToggleAll = viewModel::togglePenAll,
        onImport = { confirmImport = true },
    )

    // 「申请删除」原因输入弹窗（对齐网页端 requestDelRec：可空原因，确认才提交）
    delReasonFor?.let { rid ->
        DeleteReasonDialog(
            onConfirm = { reason ->
                delReasonFor = null
                viewModel.requestDelete(rid, reason)
            },
            onDismiss = { delReasonFor = null },
        )
    }

    // 误录「解绑拆分重传」二次确认（对齐网页端 confirm 文案）
    confirmUnbindFor?.let { rid ->
        ConfirmDialog(
            icon = MeiliIcons.Unbind,
            title = "解绑并拆分重传",
            body = "解绑后这段陪伴会回到待整理列表，方便你拆分后重新上传。确认解绑吗？",
            confirmText = "确认解绑",
            danger = true,
            onConfirm = {
                confirmUnbindFor = null
                viewModel.confirmSpeakers(rid, "unbind")
            },
            onDismiss = { confirmUnbindFor = null },
        )
    }

    // 「导入选中」条数二次确认
    if (confirmImport) {
        val n = penSync.selectedCount
        ConfirmDialog(
            icon = MeiliIcons.Sync,
            title = "导入 $n 段到待整理",
            body = "将从陪伴笔导入选中的 $n 段到「待整理」，导入后在这里绑定顾客即可进入分析。确认导入吗？",
            confirmText = "确认导入",
            danger = false,
            onConfirm = {
                confirmImport = false
                viewModel.importSelected()
            },
            onDismiss = { confirmImport = false },
        )
    }
}

/** 待整理分页器（对齐网页端「‹ 上一页 · 第 X/Y 页 · 共 N 段 · 下一页 ›」）。翻页纯客户端、不联网。 */
@Composable
private fun PendingPager(
    page: Int,
    totalPages: Int,
    total: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = Dimens.CardGap),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GhostButton(text = "上一页", onClick = onPrev, size = MeiliButtonSize.Xs, enabled = page > 1)
        Text(
            text = "第 $page / $totalPages 页 · 共 $total 段",
            style = MaterialTheme.typography.bodySmall,
            color = MeiliPalette.Ink3,
            modifier = Modifier.padding(horizontal = 14.dp),
        )
        GhostButton(text = "下一页", onClick = onNext, size = MeiliButtonSize.Xs, enabled = page < totalPages)
    }
}

// ───────────────────────── 列表卡 ─────────────────────────

@Composable
private fun PendingRecordingCard(
    rec: PendingRecording,
    busy: Boolean,
    canRetry: Boolean,
    onBindCustomer: (Long) -> Unit,
    onRequestDelete: () -> Unit,
    onWithdrawDelete: () -> Unit,
    onDismissReject: () -> Unit,
    onRetry: () -> Unit,
    onPlayingChange: (Boolean) -> Unit,
    onPreviewToast: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        // 删除审批中：需先撤回才能其他操作
        rec.deleteRequestStatus == "pending" -> DeletePendingCard(busy, onWithdrawDelete, modifier)
        // 同步中占位：audio_url 为空，不可试听（可重试补传）
        rec.isProcessing -> ProcessingCard(canRetry = canRetry, onRetry = onRetry, modifier = modifier)
        else -> NormalRecordingCard(
            rec = rec,
            busy = busy,
            onBindCustomer = onBindCustomer,
            onRequestDelete = onRequestDelete,
            onDismissReject = onDismissReject,
            onPlayingChange = onPlayingChange,
            onPreviewToast = onPreviewToast,
            modifier = modifier,
        )
    }
}

@Composable
private fun NormalRecordingCard(
    rec: PendingRecording,
    busy: Boolean,
    onBindCustomer: (Long) -> Unit,
    onRequestDelete: () -> Unit,
    onDismissReject: () -> Unit,
    onPlayingChange: (Boolean) -> Unit,
    onPreviewToast: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val crossDay = rec.serviceDate != null && rec.recDate != null && rec.serviceDate != rec.recDate
    val rejected = rec.deleteRequestStatus == "rejected"
    val titleColor = if (crossDay) MeiliPalette.RoseText else MeiliPalette.Ink
    val cardShape = MeiliShapes.Lg

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = cardShape,
        color = if (crossDay) MeiliPalette.RoseSoft else MeiliPalette.Surface,
        contentColor = MeiliPalette.Ink,
        border = BorderStroke(
            Dimens.BorderThin,
            if (crossDay) MeiliPalette.RoseLine else MeiliPalette.LineSoft,
        ),
        shadowElevation = Dimens.Elev2,
    ) {
        Column(modifier = Modifier.padding(Dimens.CardPad)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        Icon(MeiliIcons.Album, contentDescription = null, tint = if (crossDay) MeiliPalette.RoseText else MeiliPalette.Clay, modifier = Modifier.size(Dimens.Icon))
                        Text(
                            text = timeRangeLabel(rec),
                            style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp, fontWeight = FontWeight.ExtraBold),
                            color = titleColor,
                        )
                    }
                    Text(
                        text = subLabel(rec, crossDay),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (crossDay) MeiliPalette.RoseText else MeiliPalette.Ink3,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (crossDay) {
                    StatusPill(text = "跨日", kind = PillKind.Danger)
                } else {
                    StatusPill(text = "未绑定", kind = PillKind.Neutral)
                }
            }

            // 删除申请被拒：红 banner + 「知道了」（对齐网页端 isDelRejected）
            if (rejected) {
                val reason = rec.deleteRejectReason?.takeIf { it.isNotBlank() }
                InfoBanner(
                    text = "删除申请被拒" + (reason?.let { "：$it" } ?: ""),
                    kind = BannerKind.Danger,
                    icon = MeiliIcons.Warn,
                    actionText = if (busy) "处理中…" else "知道了",
                    onAction = if (busy) null else onDismissReject,
                    modifier = Modifier.padding(top = 11.dp),
                )
            }

            // 上报时长远大于实测音频的截断提示
            rec.truncateNote?.takeIf { it.isNotBlank() }?.let { note ->
                InfoBanner(
                    text = note,
                    kind = BannerKind.Warn,
                    icon = MeiliIcons.Warn,
                    modifier = Modifier.padding(top = 11.dp),
                )
            }

            // 试听（全程）
            PreviewAudio(
                recordingId = rec.id,
                processing = rec.isProcessing,
                directUrl = rec.audioUrl,
                onPlayingChange = onPlayingChange,
                onPreviewToast = onPreviewToast,
            )

            // 绑定顾客
            PrimaryButton(
                text = "绑定顾客",
                onClick = { onBindCustomer(rec.id) },
                icon = MeiliIcons.Link,
                size = MeiliButtonSize.Small,
                modifier = Modifier.fillMaxWidth(),
            )
            // 申请删除（对齐网页端 requestDelRec：弹原因 → 走审批）
            GhostButton(
                text = if (busy) "处理中…" else "申请删除",
                onClick = onRequestDelete,
                icon = MeiliIcons.Trash,
                enabled = !busy,
                size = MeiliButtonSize.Small,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun ProcessingCard(canRetry: Boolean, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    MeiliCard(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Icon(MeiliIcons.Upload, contentDescription = null, tint = MeiliPalette.Ink2, modifier = Modifier.size(Dimens.Icon))
            Text(
                text = "同步中…",
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp, fontWeight = FontWeight.ExtraBold),
                color = MeiliPalette.Ink2,
            )
        }
        Text(
            text = "后台同步中，传完后补时段 / 时长",
            style = MaterialTheme.typography.bodySmall,
            color = MeiliPalette.Ink3,
            modifier = Modifier.padding(top = 4.dp),
        )
        // 卡住可手动重推补传队列（对齐网页端 penRetryBtn，仅 App 内 controller 可用时显示）
        if (canRetry) {
            GhostButton(
                text = "重试",
                onClick = onRetry,
                icon = MeiliIcons.Sync,
                size = MeiliButtonSize.Xs,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun DeletePendingCard(busy: Boolean, onWithdrawDelete: () -> Unit, modifier: Modifier = Modifier) {
    MeiliCard(modifier = modifier) {
        InfoBanner(
            text = "删除审批中（需先撤回申请才能进行其他操作）",
            kind = BannerKind.Clay,
            icon = MeiliIcons.Clock,
        )
        GhostButton(
            text = if (busy) "处理中…" else "撤回删除申请",
            onClick = onWithdrawDelete,
            enabled = !busy,
            size = MeiliButtonSize.Small,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 11.dp),
        )
    }
}

// ───────────────────────── 误录（多说话人）逐条确认 ─────────────────────────

/**
 * 误录区（对齐网页端 loadNeedsConfirm + #needsConfirmCard）：顶部说明 + 逐条卡片。
 * 每条：时段/顾客 · N 人说话 · 试听 · 「仍然分析」(keep) · 「解绑拆分重传」(unbind)。
 */
@Composable
private fun NeedsConfirmSection(
    items: List<NeedsConfirmRecording>,
    busyIds: Set<Long>,
    onKeep: (Long) -> Unit,
    onUnbind: (Long) -> Unit,
    onPlayingChange: (Long, Boolean) -> Unit,
    onPreviewToast: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        InfoBanner(
            text = "检测到 ${items.size} 段疑似误录（说话人 > 2），可能一段混了多位顾客。" +
                "确认无误可「仍然分析」；若确实混录，请「解绑拆分重传」。",
            kind = BannerKind.Danger,
            icon = MeiliIcons.Warn,
        )
        items.forEach { rec ->
            NeedsConfirmCard(
                rec = rec,
                busy = rec.id in busyIds,
                onKeep = { onKeep(rec.id) },
                onUnbind = { onUnbind(rec.id) },
                onPlayingChange = { p -> onPlayingChange(rec.id, p) },
                onPreviewToast = onPreviewToast,
                modifier = Modifier.padding(top = Dimens.CardGap),
            )
        }
    }
}

@Composable
private fun NeedsConfirmCard(
    rec: NeedsConfirmRecording,
    busy: Boolean,
    onKeep: () -> Unit,
    onUnbind: () -> Unit,
    onPlayingChange: (Boolean) -> Unit,
    onPreviewToast: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MeiliShapes.Lg,
        color = MeiliPalette.RoseSoft,
        contentColor = MeiliPalette.Ink,
        border = BorderStroke(Dimens.BorderThin, MeiliPalette.RoseLine),
        shadowElevation = Dimens.Elev2,
    ) {
        Column(modifier = Modifier.padding(Dimens.CardPad)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        Icon(MeiliIcons.Profile, contentDescription = null, tint = MeiliPalette.RoseText, modifier = Modifier.size(Dimens.Icon))
                        Text(
                            text = rec.customer?.takeIf { it.isNotBlank() } ?: "未绑定顾客",
                            style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp, fontWeight = FontWeight.ExtraBold),
                            color = MeiliPalette.RoseText,
                        )
                    }
                    Text(
                        text = (rec.recordedAt ?: rec.createdAt ?: "时段待补"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MeiliPalette.RoseText,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                StatusPill(text = "${rec.asrSpeakerCount ?: "?"} 人说话", kind = PillKind.Danger, icon = MeiliIcons.Warn)
            }

            // 试听（误录条服务端已带 audio_url，直接播）
            PreviewAudio(
                recordingId = rec.id,
                processing = false,
                directUrl = rec.audioUrl,
                onPlayingChange = onPlayingChange,
                onPreviewToast = onPreviewToast,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                SoftButton(
                    text = "仍然分析",
                    onClick = onKeep,
                    icon = MeiliIcons.Check,
                    enabled = !busy,
                    size = MeiliButtonSize.Small,
                    modifier = Modifier.weight(1f),
                )
                GhostButton(
                    text = if (busy) "处理中…" else "解绑拆分重传",
                    onClick = onUnbind,
                    icon = MeiliIcons.Unbind,
                    enabled = !busy,
                    size = MeiliButtonSize.Small,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// ───────────────────────── 屏内弹窗 ─────────────────────────

/** 「申请删除」原因输入弹窗（对齐网页端 prompt：原因可空，确认才提交，取消放弃）。 */
@Composable
private fun DeleteReasonDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MeiliPalette.Surface,
        titleContentColor = MeiliPalette.Ink,
        textContentColor = MeiliPalette.Ink2,
        shape = MeiliShapes.Lg,
        icon = { Icon(MeiliIcons.Trash, contentDescription = null, tint = MeiliPalette.RoseText, modifier = Modifier.size(26.dp)) },
        title = { Text("申请删除这段陪伴", style = MaterialTheme.typography.headlineSmall, color = MeiliPalette.Ink) },
        text = {
            Column {
                Text(
                    "删除需走审批。可填写原因（选填），提交后等待审批。",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5f.sp, lineHeight = 20.sp),
                    color = MeiliPalette.Ink2,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text("删除原因（选填）", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.5f.sp), color = MeiliPalette.Ink3)
                    },
                    shape = MeiliShapes.Sm,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.5f.sp),
                    colors = dialogFieldColors(),
                )
            }
        },
        confirmButton = {
            PrimaryButton(text = "提交申请", onClick = { onConfirm(reason) }, icon = MeiliIcons.Check, size = MeiliButtonSize.Small)
        },
        dismissButton = {
            GhostButton(text = "取消", onClick = onDismiss, size = MeiliButtonSize.Small)
        },
    )
}

/** 通用二次确认弹窗（误录解绑 / 导入条数）。 */
@Composable
private fun ConfirmDialog(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    confirmText: String,
    danger: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MeiliPalette.Surface,
        titleContentColor = MeiliPalette.Ink,
        textContentColor = MeiliPalette.Ink2,
        shape = MeiliShapes.Lg,
        icon = { Icon(icon, contentDescription = null, tint = if (danger) MeiliPalette.RoseText else MeiliPalette.ClayDeep, modifier = Modifier.size(26.dp)) },
        title = { Text(title, style = MaterialTheme.typography.headlineSmall, color = MeiliPalette.Ink) },
        text = {
            Text(body, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5f.sp, lineHeight = 20.sp), color = MeiliPalette.Ink2)
        },
        confirmButton = {
            PrimaryButton(text = confirmText, onClick = onConfirm, icon = MeiliIcons.Check, size = MeiliButtonSize.Small)
        },
        dismissButton = {
            GhostButton(text = "取消", onClick = onDismiss, size = MeiliButtonSize.Small)
        },
    )
}

@Composable
private fun dialogFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MeiliPalette.White,
    unfocusedContainerColor = MeiliPalette.SurfaceSoft,
    focusedBorderColor = MeiliPalette.Clay,
    unfocusedBorderColor = MeiliPalette.Line,
    cursorColor = MeiliPalette.Clay,
    focusedTextColor = MeiliPalette.Ink,
    unfocusedTextColor = MeiliPalette.Ink,
)

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

/** 兼容旧调用（竖排：行内小钮 + 展开后进度条）。新卡片请直接 rememberPreviewAudio + 行内 PreviewPlayDot + 下方 PreviewTrack。 */
@Composable
internal fun PreviewAudio(
    recordingId: Long,
    processing: Boolean,
    directUrl: String?,
    onPlayingChange: (Boolean) -> Unit,
    onPreviewToast: (String) -> Unit,
) {
    val c = rememberPreviewAudio(recordingId, directUrl, processing, onPlayingChange, onPreviewToast)
    PreviewPlayDot(c)
    PreviewTrack(c)
}

// ───────────────────────── 从陪伴笔同步 sheet ─────────────────────────

@Composable
private fun PenSyncSheet(
    state: PenSyncUiState,
    onDismiss: () -> Unit,
    onToggleRow: (String) -> Unit,
    onToggleAll: () -> Unit,
    onImport: () -> Unit,
) {
    MeiliBottomSheet(
        visible = state.visible,
        onDismiss = onDismiss,
        title = "陪伴笔机身记录",
        subtitle = "以下是陪伴笔本地保存、尚未导入的片段，勾选后导入到「待整理」去绑定顾客。",
    ) {
        when {
            state.loading -> {
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 36.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MeiliPalette.Clay, strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
                }
            }
            state.penUnavailable || state.rows.isEmpty() -> {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 30.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Surface(shape = MeiliShapes.Md, color = MeiliPalette.SurfaceSoft, modifier = Modifier.size(58.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(MeiliIcons.Pen, contentDescription = null, tint = MeiliPalette.Ink4, modifier = Modifier.size(30.dp))
                        }
                    }
                    Text(
                        text = if (state.penUnavailable) "未连接陪伴笔" else "暂无可导入的机身片段",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = MeiliPalette.Ink2,
                    )
                    Text(
                        text = if (state.penUnavailable) "请在陪伴首页连接陪伴笔后再从机身同步" else "陪伴笔机身已无未导入的片段",
                        style = MaterialTheme.typography.bodySmall,
                        color = MeiliPalette.Ink3,
                    )
                }
            }
            else -> {
                // 全选行
                CheckRow(
                    title = "全选",
                    meta = "共 ${state.selectableRows.size} 段 · 陪伴笔脱机时本地保存",
                    checked = state.allSelected,
                    onClick = onToggleAll,
                    dashed = true,
                    trailing = { StatusPill(text = "陪伴笔", kind = PillKind.Clay, icon = MeiliIcons.Pen) },
                    modifier = Modifier.padding(bottom = 14.dp),
                )
                state.rows.forEach { row ->
                    CheckRow(
                        title = "${penDateLabel(row.file.recordedAt)} · ${secToLabel(row.file.durationSec)}",
                        meta = penMeta(row),
                        checked = row.selected,
                        enabled = row.importable,
                        onClick = { onToggleRow(row.file.name) },
                        trailing = { StatusPill(text = penStatusText(row.status), kind = penStatusKind(row.status)) },
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    PrimaryButton(
                        text = "导入选中 (${state.selectedCount})",
                        onClick = onImport,
                        icon = MeiliIcons.Sync,
                        enabled = state.selectedCount > 0 && !state.importing,
                        modifier = Modifier.weight(1f),
                    )
                    GhostButton(text = "取消", onClick = onDismiss)
                }
            }
        }
    }
}

// ───────────────────────── 空 / 加载态 ─────────────────────────

@Composable
private fun EmptyBlock() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 50.dp, bottom = 22.dp, start = 22.dp, end = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(shape = MeiliShapes.Md, color = MeiliPalette.SurfaceSoft, modifier = Modifier.size(58.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(MeiliIcons.Tidy, contentDescription = null, tint = MeiliPalette.Ink4, modifier = Modifier.size(30.dp))
            }
        }
        Text(
            text = "没有待整理的陪伴",
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = MeiliPalette.Ink2,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            text = "陪伴结束后会出现在这里，绑定顾客即可进入分析。也可「从陪伴笔同步」机身片段。",
            style = MaterialTheme.typography.bodySmall,
            color = MeiliPalette.Ink3,
        )
    }
}

@Composable
private fun LoadingBlock() {
    Box(modifier = Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MeiliPalette.Clay, strokeWidth = 2.5.dp, modifier = Modifier.size(30.dp))
    }
}

// ───────────────────────── 文案工具 ─────────────────────────

private fun timeRangeLabel(rec: PendingRecording): String {
    val s = rec.startHm
    val e = rec.endHm
    return when {
        s != null && e != null -> "$s – $e"
        s != null -> s
        rec.recordedAt != null -> rec.recordedAt.takeLast(8).take(5).ifBlank { rec.recordedAt }
        else -> "时段待补"
    }
}

private fun subLabel(rec: PendingRecording, crossDay: Boolean): String {
    val date = rec.serviceDate ?: rec.recDate ?: "—"
    val dur = rec.durationLabel ?: rec.durationMin?.let { "${it}分钟" } ?: "时长待补"
    val crossTag = if (crossDay) "（跨日）" else ""
    val speakers = rec.asrSpeakerCount?.let { " · ${it}人说话" } ?: ""
    return "$date$crossTag · $dur$speakers"
}

private fun msToLabel(ms: Int): String {
    val totalSec = ms / 1000
    return secToLabel(totalSec)
}

private fun secToLabel(sec: Int): String {
    val m = sec / 60
    val s = sec % 60
    return "%02d:%02d".format(m, s)
}

private fun penDateLabel(recordedAt: String): String =
    recordedAt.take(10).ifBlank { "陪伴笔片段" }

private fun penMeta(row: PenSyncRow): String {
    val mb = row.file.sizeBytes / (1024.0 * 1024.0)
    val sizeStr = if (mb >= 0.1) "约 %.1f MB".format(mb) else "本地片段"
    return "陪伴笔本地片段 · $sizeStr"
}

private fun penStatusText(status: String?): String = when (status) {
    "uploaded" -> "已导入"
    "deleted" -> "已删除"
    else -> "未导入"
}

private fun penStatusKind(status: String?): PillKind = when (status) {
    "uploaded" -> PillKind.Ok
    "deleted" -> PillKind.Neutral
    else -> PillKind.Warn
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun PendingEmptyPreview() {
    // 预览空态骨架（不触发 ViewModel 网络 init）
    MeiliTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MeiliTheme.colors.bg)
                .padding(horizontal = Dimens.ScreenH),
        ) {
            MeiliTopBar(
                title = "待整理",
                subtitle = "回顾每一次陪伴 · 没绑定顾客的陪伴不会进入分析",
                actions = {
                    GhostButton("从陪伴笔同步", {}, icon = MeiliIcons.Sync, size = MeiliButtonSize.Xs)
                    TopBarIconButton(MeiliIcons.Refresh, {})
                },
            )
            EmptyBlock()
        }
    }
}
