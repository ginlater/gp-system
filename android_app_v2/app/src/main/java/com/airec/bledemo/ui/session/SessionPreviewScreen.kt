package com.airec.bledemo.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.airec.bledemo.data.model.Customer
import com.airec.bledemo.data.model.PreviewRecording
import com.airec.bledemo.data.model.TaskProgress
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
import com.airec.bledemo.designsystem.components.SectionLabel
import com.airec.bledemo.designsystem.components.SoftButton
import com.airec.bledemo.designsystem.components.StatusPill

/**
 * 会话预览 + 开始分析（SPEC §4.6 / warm_2 #m-pkg「接诊包」）。
 *
 * 展示本次将分析的片段（已绑定）、本人同日未绑定可加入的片段；
 * 可移除片段（[SessionPreviewViewModel.removeFromPackage]）、
 * 二次确认后「确认并开始分析」（[SessionPreviewViewModel.startAnalysis]）、
 * 分析中可「取消分析」（[SessionPreviewViewModel.cancelAnalysis]）。
 * 锁定 / 进行中态只读；开始/完成后可导航到报告。
 *
 * 红线：顾客可见处一律「陪伴」系；设备叫「陪伴笔」；无 emoji，仅用 MeiliIcons。
 *
 * @param sessionId 会话标识（由路由参数传入；内部解析为 customer_id + date 驱动预览）
 * @param onBack 返回上一页
 * @param onAnalysisStarted 开始分析 / 查看报告时跳报告（sessionId）
 * @param modifier 由 AppScaffold 传入
 */
@Composable
fun SessionPreviewScreen(
    sessionId: Long,
    onBack: () -> Unit = {},
    onAnalysisStarted: (sessionId: Long) -> Unit = {},
    modifier: Modifier = Modifier,
    // 「按顾客+日期」入口（今日接诊「绑定录音/开始分析」用）：给了就走 loadByCustomer，否则按 sessionId 加载。
    customerId: Long? = null,
    serviceDate: String? = null,
    viewModel: SessionPreviewViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    var showConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(sessionId, customerId, serviceDate) {
        if (customerId != null && customerId > 0 && !serviceDate.isNullOrBlank()) {
            viewModel.loadByCustomer(customerId, serviceDate)
        } else {
            viewModel.load(sessionId)
        }
    }

    // 一次性提示
    LaunchedEffect(state.toast) {
        // toast 直接通过下方 inline 提示展示；这里仅做自动清理
        if (state.toast != null) {
            kotlinx.coroutines.delay(2200)
            viewModel.consumeToast()
        }
    }

    // start 成功 → 跳报告
    LaunchedEffect(state.navigateToReport) {
        state.navigateToReport?.let {
            viewModel.consumeNavigation()
            onAnalysisStarted(it)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MeiliTheme.colors.bg),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.ScreenH)
                .padding(bottom = Dimens.BottomNavInset),
        ) {
            MeiliTopBar(
                title = if (state.customerName.isNotBlank()) "接诊包 · ${state.customerName}" else "接诊包",
                subtitle = subtitleFor(state),
                onBack = onBack,
            )

            when {
                state.loading -> LoadingBlock()
                state.loadError != null -> ErrorBlock(state.loadError!!) { viewModel.refresh(initial = true) }
                else -> PreviewBody(
                    state = state,
                    onRemove = viewModel::removeFromPackage,
                    onAdd = viewModel::addToPackage,
                    onRebind = viewModel::openRebind,
                    onConfirmSpeakers = viewModel::confirmSpeakers,
                    onStartClick = { showConfirm = true },
                    onCancel = viewModel::cancelAnalysis,
                    onViewReport = { onAnalysisStarted(state.sessionId) },
                )
            }
        }

        // inline toast（深底，对应 .toast）
        state.toast?.let { msg ->
            Surface(
                shape = MeiliTheme.shapes.Sm,
                color = MeiliPalette.InkSurface,
                contentColor = MeiliPalette.White,
                shadowElevation = 12.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = Dimens.ScreenH, vertical = 24.dp)
                    .fillMaxWidth(),
            ) {
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MeiliPalette.White,
                    modifier = Modifier.padding(horizontal = 17.dp, vertical = 14.dp),
                )
            }
        }
    }

    // 二次确认：开始分析前接诊包将被锁定
    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            containerColor = MeiliPalette.Surface,
            titleContentColor = MeiliPalette.Ink,
            textContentColor = MeiliPalette.Ink2,
            title = { Text("确认开始分析？", style = MaterialTheme.typography.titleLarge) },
            text = {
                Text(
                    "本次将分析 ${state.bound.size} 段陪伴。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    viewModel.startAnalysis()
                }) {
                    Text("确认并开始分析", color = MeiliPalette.ClayDeep, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text("再想想", color = MeiliPalette.Ink2)
                }
            },
        )
    }

    // 换绑弹层（只移动这一段到别的顾客；repo.directRebind）
    RebindSheet(
        sheet = state.rebindSheet,
        onDismiss = viewModel::closeRebind,
        onQueryChange = viewModel::onRebindQueryChange,
        onPick = viewModel::submitRebind,
    )
}

private fun subtitleFor(s: SessionPreviewViewModel.UiState): String {
    val date = s.serviceDate.ifBlank { null }
    return when (s.phase) {
        SessionPreviewViewModel.AnalysisPhase.Running ->
            listOfNotNull(date, "分析进行中，接诊包已锁定").joinToString(" · ")
        SessionPreviewViewModel.AnalysisPhase.Done ->
            listOfNotNull(date, "分析已完成").joinToString(" · ")
        SessionPreviewViewModel.AnalysisPhase.Outdated ->
            listOfNotNull(date, "陪伴片段有变更，需重新分析").joinToString(" · ")
        else ->
            listOfNotNull(date, "核对顾客信息后开始分析，如绑错可换绑或退回待整理").joinToString(" · ")
    }
}

// ────────────────────────────── 主体 ──────────────────────────────

@Composable
private fun PreviewBody(
    state: SessionPreviewViewModel.UiState,
    onRemove: (Long) -> Unit,
    onAdd: (Long) -> Unit,
    onRebind: (PreviewRecording) -> Unit,
    onConfirmSpeakers: (Long) -> Unit,
    onStartClick: () -> Unit,
    onCancel: () -> Unit,
    onViewReport: () -> Unit,
) {
    val phase = state.phase
    val running = phase == SessionPreviewViewModel.AnalysisPhase.Running

    // 顶部状态卡：状态 pill + 本次将分析 N 段
    MeiliCard(tight = true, modifier = Modifier.padding(bottom = 13.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusPhasePill(state)
            Text(
                text = "本次将分析 ${state.bound.size} 段陪伴",
                style = MaterialTheme.typography.bodySmall,
                color = MeiliPalette.Ink3,
            )
        }

        // 任务级进度（进度条 + 已等时长 + 正在跑/失败任务名）—— 非 pending 才展示
        if (phase != SessionPreviewViewModel.AnalysisPhase.Idle) {
            AnalysisProgressBlock(progress = state.progress, phase = phase)
        }
    }

    // 进行中提示（只有真在分析时才锁，不再因 locked 残留把已完成/已中断的包说成"锁定·联系管理员"）
    if (running) {
        ClayBanner(text = "分析进行中，接诊包已锁定，无法再修改片段。")
    } else if (phase == SessionPreviewViewModel.AnalysisPhase.Done) {
        ClayBanner(text = "本接诊包已完成分析。如需调整片段（退回 / 换绑）将作废原报告，可重新分析。")
    }

    // 录音有变更（outdated）：单独提示可重跑，不并进「待开始分析」
    if (phase == SessionPreviewViewModel.AnalysisPhase.Outdated) {
        ClayBanner(text = "陪伴片段在上次分析后有变更（换绑/退回等），原报告已作废，请重新分析以更新。")
    }

    // 本次将分析的陪伴（已绑定）
    SectionLabel(
        text = "本次将分析的陪伴（已绑定）",
        icon = MeiliIcons.Link,
        modifier = Modifier.padding(top = 4.dp, bottom = 9.dp),
    )
    if (state.bound.isEmpty()) {
        EmptyHint("本接诊包暂无已绑定的陪伴片段。")
    } else {
        state.bound.forEach { rec ->
            val busyOp = if (state.rowOpId == rec.id) state.rowOp else null
            BoundRecordingCard(
                rec = rec,
                editable = state.editable,
                anyRowBusy = state.rowOpId != null,
                busyOp = busyOp,
                onRemove = { onRemove(rec.id) },
                onRebind = { onRebind(rec) },
                onConfirmSpeakers = { onConfirmSpeakers(rec.id) },
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
    }

    // 本人同日未绑定（可加入）—— 「加入本接诊包」直接绑到本会话顾客
    if (state.unbound.isNotEmpty()) {
        SectionLabel(
            text = "本人 ${state.serviceDate.ifBlank { "当日" }} 未绑定的陪伴",
            icon = MeiliIcons.Tidy,
            modifier = Modifier.padding(top = 4.dp, bottom = 9.dp),
        )
        state.unbound.forEach { rec ->
            UnboundRecordingCard(
                rec = rec,
                addable = state.editable,
                adding = state.rowOpId == rec.id && state.rowOp == SessionPreviewViewModel.RowOp.Bind,
                anyRowBusy = state.rowOpId != null,
                onAdd = { onAdd(rec.id) },
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
    }

    Spacer(Modifier.height(4.dp))

    // 底部动作区（按状态切换）
    when (phase) {
        SessionPreviewViewModel.AnalysisPhase.Running -> {
            ClayBanner(text = "分析需要几分钟，可离开本页，完成后会出现在报告里。")
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.fillMaxWidth()) {
                PrimaryButton(
                    text = "查看进度",
                    onClick = onViewReport,
                    icon = MeiliIcons.Spark,
                    modifier = Modifier.weight(1f),
                )
                GhostButton(
                    text = if (state.submitting) "取消中…" else "取消分析",
                    onClick = onCancel,
                    enabled = !state.submitting,
                )
            }
        }

        SessionPreviewViewModel.AnalysisPhase.Done -> {
            PrimaryButton(
                text = "查看陪伴报告",
                onClick = onViewReport,
                icon = MeiliIcons.Doc,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        else -> {
            // Idle / Failed / Outdated：可开始（失败/变更=重新分析，后端只补跑失败/缺失或全量重跑）
            val startLabel = when {
                state.submitting -> "提交中…"
                phase == SessionPreviewViewModel.AnalysisPhase.Failed -> "重新开始分析"
                phase == SessionPreviewViewModel.AnalysisPhase.Outdated -> "重新分析"
                else -> "确认并开始分析"
            }
            PrimaryButton(
                text = startLabel,
                onClick = onStartClick,
                icon = MeiliIcons.Spark,
                enabled = state.canStart && !state.submitting,
                modifier = Modifier.fillMaxWidth(),
            )
            // 就地原因反馈：硬阻塞(speaker_unconfirmed / no_recording)优先；
            // 否则若识别中给软提示(点开始分析后自动等识别完再跑)。
            val hintCode = state.blockReason
                ?: if (state.asrStillRunning) "asr_autostart" else null
            hintCode?.let { BlockReasonHint(code = it) }
        }
    }
}

/** 触发前置条件未满足时的原因提示（对齐后端 reason）。 */
@Composable
private fun BlockReasonHint(code: String) {
    Text(
        text = SessionPreviewViewModel.reasonText(code),
        style = MaterialTheme.typography.labelSmall,
        color = if (code == "speaker_unconfirmed") MeiliPalette.ClayDeep else MeiliPalette.Ink3,
        modifier = Modifier.padding(top = 9.dp, start = 2.dp),
    )
}

/**
 * 分析进度块（点 4）：状态行 + 已等时长 + 进度条(done/total) + 正在跑任务名 +（失败态）失败任务名。
 * 对齐 web renderPkgProgress：仅 failed/outdated 才提示失败任务（分析中/排队中说明在重试，不提示）。
 */
@Composable
private fun AnalysisProgressBlock(
    progress: TaskProgress?,
    phase: SessionPreviewViewModel.AnalysisPhase,
) {
    if (progress == null) return
    val total = progress.total ?: 11
    val done = progress.done ?: 0
    val running = progress.running ?: 0
    val failed = progress.failed ?: 0
    val pct = if (total > 0) done.toFloat() / total else 0f
    val barColor = when (phase) {
        SessionPreviewViewModel.AnalysisPhase.Done -> MeiliPalette.Clay
        SessionPreviewViewModel.AnalysisPhase.Failed -> MeiliPalette.RoseText
        SessionPreviewViewModel.AnalysisPhase.Outdated -> MeiliPalette.ClayDeep
        else -> MeiliPalette.Clay
    }

    Column(modifier = Modifier.padding(top = 12.dp)) {
        // 已等时长（仅分析中后端给 wait_sec）
        val wait = progress.waitSec
        if (phase == SessionPreviewViewModel.AnalysisPhase.Running && wait != null) {
            Text(
                text = "已等待 ${formatElapsed(wait)}",
                style = MaterialTheme.typography.labelSmall,
                color = MeiliPalette.Ink3,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            LinearProgressIndicator(
                progress = { pct },
                color = barColor,
                trackColor = MeiliPalette.LineSoft,
                modifier = Modifier
                    .weight(1f)
                    .height(8.dp)
                    .clip(MeiliShapes.Pill),
            )
            Text(
                text = "$done / $total",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = MeiliPalette.Ink2,
            )
        }
        // 正在跑的任务名
        if (running > 0 && !progress.runningNames.isNullOrEmpty()) {
            Text(
                text = "正在分析：${progress.runningNames!!.joinToString("、")}",
                style = MaterialTheme.typography.labelSmall,
                color = MeiliPalette.Clay,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        // 失败任务名（仅 failed/outdated 提示，重跑可重试）
        val showFailed = failed > 0 && !progress.failedNames.isNullOrEmpty() &&
            (phase == SessionPreviewViewModel.AnalysisPhase.Failed ||
                phase == SessionPreviewViewModel.AnalysisPhase.Outdated)
        if (showFailed) {
            Text(
                text = "未完成：${progress.failedNames!!.joinToString("、")}（重新分析可重试）",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MeiliPalette.RoseText,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** wait_sec → 「X分Y秒 / Y秒」可读文案。 */
private fun formatElapsed(sec: Int): String {
    if (sec < 60) return "${sec}秒"
    val m = sec / 60
    val s = sec % 60
    return if (s == 0) "${m}分" else "${m}分${s}秒"
}

// ────────────────────────────── 子组件 ──────────────────────────────

/** 状态卡里的状态 pill（待开始 / 分析中 N/11 / 已完成 / 失败 / 录音有变更）。 */
@Composable
private fun StatusPhasePill(state: SessionPreviewViewModel.UiState) {
    when (state.phase) {
        SessionPreviewViewModel.AnalysisPhase.Running -> StatusPill(
            text = "分析中… ${state.progressDone}/${state.progressTotal}",
            kind = PillKind.Run,
            icon = MeiliIcons.Sync,
        )
        SessionPreviewViewModel.AnalysisPhase.Done -> StatusPill(
            text = "已完成分析",
            kind = PillKind.Ok,
            icon = MeiliIcons.Check,
        )
        SessionPreviewViewModel.AnalysisPhase.Failed -> StatusPill(
            text = "上次分析未完成",
            kind = PillKind.Danger,
            icon = MeiliIcons.Warn,
        )
        SessionPreviewViewModel.AnalysisPhase.Outdated -> StatusPill(
            text = "录音有变更，需重新分析",
            kind = PillKind.Clay,
            icon = MeiliIcons.Refresh,
        )
        else -> StatusPill(
            text = "待开始分析",
            kind = PillKind.Warn,
            icon = MeiliIcons.Clock,
        )
    }
}

/**
 * 已绑定片段卡：日期/时长 + 识别状态 + 全程试听 + 操作区。
 * 操作区（可编辑时）：换绑顾客 / 退回待整理（退回=移出本接诊包，片段回到待整理，无需理由）；
 * 若片段有说话人警告未确认，额外给「确认说话人」入口（解除 speaker_unconfirmed 阻塞）。
 */
@Composable
private fun BoundRecordingCard(
    rec: PreviewRecording,
    editable: Boolean,
    anyRowBusy: Boolean,
    busyOp: SessionPreviewViewModel.RowOp?,
    onRemove: () -> Unit,
    onRebind: () -> Unit,
    onConfirmSpeakers: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val speakerWarn = rec.asrSpeakerWarning == 1 && (rec.speakerConfirmed ?: 0) == 0
    MeiliCard(tight = true, modifier = modifier) {
        val audio = com.airec.bledemo.ui.pending.rememberPreviewAudio(
            recordingId = rec.id,
            directUrl = rec.audioUrl,
            processing = false,
            onPlayingChange = {},
            onPreviewToast = {},
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = rec.recordedAt ?: "时间未知",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = MeiliPalette.Ink,
                )
                Text(
                    text = "时长 ${rec.durationLabel ?: "—"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MeiliPalette.Ink3,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            AsrPill(rec.asrStatus, rec.asrError)
            // 行内播放钮：跟时长/识别状态并排，点了才在下方展开进度条
            com.airec.bledemo.ui.pending.PreviewPlayDot(audio)
        }

        // 换绑审批中（后端已有 pending 换绑申请）→ 只读提示，不再叠加换绑动作
        if (rec.pendingRebindRequestId != null) {
            Spacer(Modifier.height(9.dp))
            StatusPill(text = "换绑审批中", kind = PillKind.Warn, icon = MeiliIcons.Clock)
        }

        // 说话人警告：提示 + 就地确认
        if (speakerWarn) {
            Spacer(Modifier.height(9.dp))
            Text(
                text = "识别提示这段可能不止一位说话人（${rec.asrSpeakerCount ?: "?"} 人）。确认是同一次陪伴后可继续分析；若混入他人请换绑或退回。",
                style = MaterialTheme.typography.labelSmall,
                color = MeiliPalette.RoseText,
            )
        }

        // 点了行内播放钮才出现的进度条（可拖拽跳播 + 当前/总时长，全程无 60s 上限）
        com.airec.bledemo.ui.pending.PreviewTrack(audio)

        if (editable) {
            Spacer(Modifier.height(11.dp))
            if (speakerWarn) {
                SoftButton(
                    text = if (busyOp == SessionPreviewViewModel.RowOp.Confirm) "确认中…" else "确认说话人无误",
                    onClick = onConfirmSpeakers,
                    icon = MeiliIcons.Check,
                    enabled = !anyRowBusy,
                    size = MeiliButtonSize.Small,
                    modifier = Modifier.padding(bottom = 9.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GhostButton(
                    text = if (busyOp == SessionPreviewViewModel.RowOp.Rebind) "换绑中…" else "换绑顾客",
                    onClick = onRebind,
                    icon = MeiliIcons.Link,
                    enabled = !anyRowBusy && rec.pendingRebindRequestId == null,
                    size = MeiliButtonSize.Small,
                    modifier = Modifier.weight(1f),
                )
                GhostButton(
                    text = if (busyOp == SessionPreviewViewModel.RowOp.Remove) "退回中…" else "退回待整理",
                    onClick = onRemove,
                    icon = MeiliIcons.Unbind,
                    enabled = !anyRowBusy,
                    size = MeiliButtonSize.Small,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 未绑定片段卡：日期/时长 +「加入本接诊包」（直接绑到本会话顾客）。 */
@Composable
private fun UnboundRecordingCard(
    rec: PreviewRecording,
    addable: Boolean,
    adding: Boolean,
    anyRowBusy: Boolean,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MeiliCard(tight = true, modifier = modifier) {
        val audio = com.airec.bledemo.ui.pending.rememberPreviewAudio(
            recordingId = rec.id,
            directUrl = rec.audioUrl,
            processing = false,
            onPlayingChange = {},
            onPreviewToast = {},
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = rec.recordedAt ?: rec.createdAt ?: "时间未知",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = MeiliPalette.Ink,
                )
                Text(
                    text = "时长 ${rec.durationLabel ?: "—"} · 未绑定",
                    style = MaterialTheme.typography.bodySmall,
                    color = MeiliPalette.Ink3,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (addable) {
                PrimaryButton(
                    text = if (adding) "加入中…" else "加入本接诊包",
                    onClick = onAdd,
                    icon = MeiliIcons.Add,
                    enabled = !anyRowBusy,
                    size = MeiliButtonSize.Xs,
                )
            } else {
                StatusPill(text = "去待整理绑定", kind = PillKind.Neutral, icon = MeiliIcons.Tidy)
            }
            // 行内播放钮：跟时长并排，点了才在下方展开进度条（先听清是谁的再加入绑定）
            com.airec.bledemo.ui.pending.PreviewPlayDot(audio)
        }
        // 点了行内播放钮才出现的进度条（可拖拽跳播 + 时长）
        com.airec.bledemo.ui.pending.PreviewTrack(audio)
    }
}

/**
 * 识别状态 pill。done=识别完成 / running|pending=识别中 / failed。
 * failed 再细分：DashScope 说"这段没听清/没有可识别的话"(asr_error 含 未识别 / NO_WORDS)
 * → 友好显示「未识别到文字」(琥珀色 Warn，不是系统故障)；其它真故障才显示「识别失败」(红 Danger)。
 */
@Composable
private fun AsrPill(asrStatus: String?, asrError: String? = null) {
    when (asrStatus) {
        "done" -> StatusPill(text = "识别完成", kind = PillKind.Ok, icon = MeiliIcons.Check)
        "failed" -> {
            val noWords = asrError != null &&
                (asrError.contains("未识别") || asrError.contains("NO_WORDS"))
            if (noWords) {
                StatusPill(text = "未识别到文字", kind = PillKind.Warn, icon = MeiliIcons.Warn)
            } else {
                StatusPill(text = "识别失败", kind = PillKind.Danger, icon = MeiliIcons.Warn)
            }
        }
        else -> StatusPill(text = "识别中", kind = PillKind.Warn, icon = MeiliIcons.Clock)
    }
}

/** 圆形播放/停止按钮（.playbtn）。 */
@Composable
private fun PlayButton(playing: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MeiliPalette.Clay,
        contentColor = MeiliPalette.White,
        shadowElevation = 5.dp,
        modifier = Modifier.size(Dimens.PlayButton),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (playing) MeiliIcons.Clock else MeiliIcons.Play,
                contentDescription = if (playing) "停止试听" else "试听",
                tint = MeiliPalette.White,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

/** 陶土色横幅（.banner.clay）。 */
@Composable
private fun ClayBanner(text: String) {
    Surface(
        shape = MeiliTheme.shapes.Md,
        color = MeiliPalette.ClayTint,
        contentColor = MeiliPalette.ClayDeep,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 15.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = MeiliIcons.Lock,
                contentDescription = null,
                tint = MeiliPalette.ClayDeep,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = MeiliPalette.ClayDeep,
            )
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    MeiliCard(tight = true, modifier = Modifier.padding(bottom = 12.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MeiliPalette.Ink3,
            modifier = Modifier.padding(vertical = 6.dp),
        )
    }
}

// ────────────────────────────── 换绑弹层 ──────────────────────────────

/**
 * 换绑弹层（点 2）：搜索本人接待过的顾客 → 选一个 → repo.directRebind 只移动这一段。
 * 复用 rebindCandidates；不展示该段当前所属顾客本人；标「当日」者已在该天接诊。
 */
@Composable
private fun RebindSheet(
    sheet: SessionPreviewViewModel.RebindSheet,
    onDismiss: () -> Unit,
    onQueryChange: (String) -> Unit,
    onPick: (Customer) -> Unit,
) {
    MeiliBottomSheet(
        visible = sheet.visible,
        onDismiss = onDismiss,
        title = "换绑这段陪伴到别的顾客",
        subtitle = "只移动这一段（该顾客其它陪伴不受影响），换绑后新旧报告都会重新生成，直接生效。",
    ) {
        Text(
            text = "陪伴：${sheet.recordedAt ?: "—"} · ${sheet.durationLabel ?: ""}",
            style = MaterialTheme.typography.bodySmall,
            color = MeiliPalette.Ink2,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        SearchField(value = sheet.query, onValueChange = onQueryChange)
        Spacer(Modifier.height(9.dp))
        Text(
            text = "仅能换绑到您本人接待过的顾客。标「当日」的已在该天接诊；其余选中后会自动补登当天接诊。",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5f.sp, lineHeight = 17.sp),
            color = MeiliPalette.Ink3,
        )
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(MeiliPalette.LineSoft))

        Column(modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            when {
                sheet.error != null -> SheetMsg(sheet.error, error = true)
                sheet.searching && sheet.candidates.isEmpty() -> SheetMsg("正在搜索…")
                sheet.candidates.isEmpty() -> SheetMsg("没有匹配的顾客，换个词试试。")
                else -> sheet.candidates.forEach { c ->
                    RebindCandidateRow(
                        customer = c,
                        enabled = !sheet.submitting,
                        onPick = { onPick(c) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RebindCandidateRow(customer: Customer, enabled: Boolean, onPick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = customer.name?.ifBlank { "未命名顾客" } ?: "未命名顾客",
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.ExtraBold),
                    color = MeiliPalette.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (customer.inDay == true) {
                    StatusPill(text = "当日", kind = PillKind.Ok)
                }
            }
            Text(
                text = rebindCandidateMeta(customer),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5f.sp),
                color = MeiliPalette.Ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        PrimaryButton(text = "换到这位", onClick = onPick, enabled = enabled, size = MeiliButtonSize.Xs)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(MeiliPalette.LineSoft))
}

private fun rebindCandidateMeta(c: Customer): String {
    val parts = buildList {
        c.phoneTail?.takeIf { it.isNotBlank() }?.let { add("尾号$it") }
        c.memberCard?.takeIf { it.isNotBlank() }?.let { add(it) }
    }
    val base = parts.joinToString(" · ")
    return when {
        c.inDay == true && base.isNotBlank() -> base
        c.inDay == true -> "已在当日接诊"
        base.isNotBlank() -> "$base · 选中后将补登当天接诊"
        else -> "选中后将补登当天接诊"
    }
}

@Composable
private fun SheetMsg(text: String, error: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MeiliPalette.RoseText else MeiliPalette.Ink3,
        modifier = Modifier.padding(vertical = 16.dp),
    )
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = {
            Text(
                "搜索：姓名 / 手机尾号 / 卡号",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.5f.sp),
                color = MeiliPalette.Ink3,
            )
        },
        leadingIcon = {
            Icon(MeiliIcons.Search, contentDescription = null, tint = MeiliPalette.Ink3, modifier = Modifier.size(18.dp))
        },
        singleLine = true,
        shape = MeiliShapes.Sm,
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.5f.sp),
        colors = sessionFieldColors(),
    )
}

@Composable
private fun sessionFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MeiliPalette.White,
    unfocusedContainerColor = MeiliPalette.SurfaceSoft,
    focusedBorderColor = MeiliPalette.Clay,
    unfocusedBorderColor = MeiliPalette.Line,
    cursorColor = MeiliPalette.Clay,
    focusedTextColor = MeiliPalette.Ink,
    unfocusedTextColor = MeiliPalette.Ink,
)

@Composable
private fun LoadingBlock() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 120.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = MeiliPalette.Clay, strokeWidth = 2.5.dp)
    }
}

@Composable
private fun ErrorBlock(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 90.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MeiliPalette.Ink2,
            textAlign = TextAlign.Center,
        )
        SoftButton(text = "重试", onClick = onRetry, icon = MeiliIcons.Refresh, size = MeiliButtonSize.Small)
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 760)
@Composable
private fun SessionPreviewScreenPreview() {
    MeiliTheme { SessionPreviewScreen(sessionId = 1L) }
}
