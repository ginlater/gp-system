package com.airec.bledemo.ui.report

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.airec.bledemo.data.model.Scoring
import com.airec.bledemo.data.model.ScoringStage
import com.airec.bledemo.data.model.Task
import com.airec.bledemo.designsystem.Dimens
import com.airec.bledemo.designsystem.MeiliIcons
import com.airec.bledemo.designsystem.MeiliPalette
import com.airec.bledemo.designsystem.MeiliShapes
import com.airec.bledemo.designsystem.MeiliTheme
import com.airec.bledemo.designsystem.components.Collapsible
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

/**
 * 分析报告（SPEC §6 / warm_2 #report）。最重要、最复杂的一屏。
 *
 * 顶栏「张陪伴师 × 刘佳佳」+ 副标题；机密 pill +「分析已完成」状态 pill；
 * 动作行「重跑 / 原始音频 / 申请删除」（照 warm_2 #report .btnrow）。
 * 原始音频：整屏唯一入口，点开 = 全程播放器(无60s) + 逐字转写(可独立折叠)。
 * 11 个 PART 可折叠卡（01→11，[Collapsible]）+ 老板/专家点评。
 * 「重跑」弹层 = N/11 完成汇总 + 平铺 11 任务(T1–T11) + 状态 + 勾选重跑 + 全部重跑 + 补齐缺失。
 *
 * 数据走 [ReportViewModel]（repo.session / sessionCustomerTags / recordingUrl /
 * sessionTasks / rerunTask / fillMissingTasks）。数据缺失时各 PART 优雅占位。
 *
 * @param sessionId 会话标识（由路由参数传入；保持签名不变）
 * @param onBack 返回上一页
 * @param modifier 由 NavHost/AppScaffold 传入（已含系统栏 inset）
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReportScreen(
    sessionId: Long,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: ReportViewModel = viewModel(
        key = "report-$sessionId",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ReportViewModel(sessionId) as T
        },
    ),
) {
    val state by viewModel.state.collectAsState()

    // 停留计时：进报告记一个时刻，离开时把停留毫秒上报（喂运营看板「总时长」=顾问看 app 的时长）。
    androidx.compose.runtime.DisposableEffect(sessionId) {
        val enterMs = System.currentTimeMillis()
        onDispose {
            val dwell = System.currentTimeMillis() - enterMs
            if (dwell in 1_000..6 * 60 * 60 * 1000L) viewModel.reportDwell(dwell)
        }
    }

    // 一次性提示（VM toast → 本地条；显示后立即回执 onToastShown 避免重组重弹）。
    var snackbar by remember { mutableStateOf<ReportToast?>(null) }
    LaunchedEffect(state.toast) {
        state.toast?.let {
            snackbar = it
            viewModel.onToastShown()
        }
    }

    // 免审批删除：接诊已被直接删掉 → 退回上一页（报告已不存在）。
    // 先等删除确认弹层收完动画再 pop，否则 popBackStack 会被正在消失的 ModalBottomSheet 吞掉、退不出去。
    LaunchedEffect(state.sessionDeleted) {
        if (state.sessionDeleted) {
            kotlinx.coroutines.delay(350)
            onBack()
        }
    }

    // 原始音频折叠 / 详细评分弹层 / 重新分析确认 / 任务执行状态弹层，均为屏内本地开关。
    var audioOpen by remember { mutableStateOf(false) }
    var scoringOpen by remember { mutableStateOf(false) }
    var reanalyzeConfirm by remember { mutableStateOf(false) }
    var taskSheetOpen by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }
    var withdrawConfirm by remember { mutableStateOf(false) }

    // 任务执行状态：进报告后每 15s 静默轮询刷新（对齐 report.html setInterval(loadTaskStatus,15000)）。
    // D5：同时静默重拉会话详情——分析完成后状态 pill / 报告内容就地更新，不用退出重进。
    // G7(批次六)：随生命周期暂停——压后台/锁屏不再每 15s 白打两个接口。
    val reportPollOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(Unit) {
        reportPollOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            while (true) {
                kotlinx.coroutines.delay(15_000)
                viewModel.loadTasks()
                viewModel.refreshDetailSilently()
            }
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
            // ---- 顶栏：张陪伴师 × 刘佳佳 / 副标题 + 右上角「任务执行状态」chip ----
            MeiliTopBar(
                title = state.headerTitle,
                subtitle = state.headerSubtitle,
                onBack = onBack,
                actions = {
                    if (state.detail != null) {
                        TaskStatusChip(
                            done = state.tasksDone,
                            total = if (state.tasks.isNotEmpty()) state.tasks.size else 11,
                            loading = state.tasksLoading && state.tasks.isEmpty(),
                            onClick = { taskSheetOpen = true },
                        )
                    }
                },
            )

            when {
                state.loading && state.detail == null -> LoadingBlock()
                state.detail == null -> ErrorBlock(state.error ?: "报告加载失败", onRetry = viewModel::load)
                else -> androidx.compose.runtime.CompositionLocalProvider(
                    // 章节展开埋点：报告里任何带 reportKey 的折叠卡首次展开都回调上报（运营看板「展开章节」）。
                    com.airec.bledemo.designsystem.components.LocalChapterExpandReporter provides
                        { key: String -> viewModel.reportPartEnter(key) },
                ) {
                ReportContent(
                    state = state,
                    audioOpen = audioOpen,
                    onToggleAudio = {
                        audioOpen = !audioOpen
                        if (audioOpen) viewModel.ensureAudioUrl()
                    },
                    onRerunTask = { id -> viewModel.rerunTasks(listOf(id)) {} },
                    onFillMissing = { viewModel.fillMissing {} },
                    onReanalyze = { reanalyzeConfirm = true },
                    onOpenScoring = { scoringOpen = true },
                    onSeek = { seconds, segment ->
                        // Case 时间戳跳播：展开原始音频折叠 + 让 VM 切到对应段并发一次性 seekToken，
                        // AudioFold 的真 MediaPlayer 准备就绪后 seek 到该秒并起播。
                        audioOpen = true
                        viewModel.seekTo(seconds, segment)
                    },
                    onSelectSegment = viewModel::selectSegment,
                    onSeekConsumed = viewModel::onSeekConsumed,
                    onConfirmKeep = { viewModel.confirmSpeakers("keep") },
                    onConfirmUnbind = { viewModel.confirmSpeakers("unbind") },
                    onSplitAt = viewModel::splitCurrent,
                    onSubmitEvaluation = { text -> viewModel.addEvaluation(text) },
                    onDeleteEvaluation = viewModel::deleteEvaluation,
                    onRequestDelete = { deleteConfirm = true },
                    onWithdrawDelete = { withdrawConfirm = true },
                )
                }
            }
        }

        // ---- 详细评分弹层（PART01「查看详细评分」入口）----
        ScoringSheet(
            visible = scoringOpen,
            scoring = state.detail?.report?.scoring,
            onDismiss = { scoringOpen = false },
        )

        // ---- 任务执行状态弹层（右上角 chip 点开；平铺 11 任务 + 单独重跑 + 补齐缺失）----
        TaskSheet(
            visible = taskSheetOpen,
            tasks = state.tasks,
            loading = state.tasksLoading,
            submitting = state.rerunSubmitting,
            onRerunTask = { id -> viewModel.rerunTasks(listOf(id)) {} },
            onFillMissing = { viewModel.fillMissing {} },
            onDismiss = { taskSheetOpen = false },
        )

        // ---- 重新分析确认（对齐 report.html reanalyze 二次确认）----
        ConfirmSheet(
            visible = reanalyzeConfirm,
            title = "重新分析这次陪伴？",
            message = "将重跑全部分析任务，预计 2–6 分钟。完成前报告会显示「分析进行中」。",
            confirmText = "开始重新分析",
            confirming = state.reanalyzing,
            onConfirm = {
                reanalyzeConfirm = false
                viewModel.reanalyze()
            },
            onDismiss = { reanalyzeConfirm = false },
        )

        // ---- 删除 二次确认（总时长<5分钟→免审批直接删；否则走管理员审批）----
        ConfirmSheet(
            visible = deleteConfirm,
            title = if (state.freeDeleteEligible) "删除这次接诊？" else "申请删除这次接诊？",
            message = if (state.freeDeleteEligible) {
                "这次陪伴总时长不到 5 分钟，可直接删除、无需审批。"
            } else {
                "提交后将由管理员审批。删除前你可随时撤销。"
            },
            confirmText = "确定删除",
            confirming = false,
            icon = MeiliIcons.Trash,
            onConfirm = {
                deleteConfirm = false
                viewModel.requestDelete()
            },
            onDismiss = { deleteConfirm = false },
        )

        // ---- 撤销删除申请 二次确认 ----
        ConfirmSheet(
            visible = withdrawConfirm,
            title = "撤销删除申请？",
            message = "这次接诊正在删除审批中，撤销后将不再删除。",
            confirmText = "撤销删除",
            confirming = false,
            icon = MeiliIcons.Sync,
            onConfirm = {
                withdrawConfirm = false
                viewModel.withdrawDelete()
            },
            onDismiss = { withdrawConfirm = false },
        )

        // ---- toast ----
        snackbar?.let { t ->
            ReportToastBar(t, modifier = Modifier.align(Alignment.BottomCenter)) { snackbar = null }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReportContent(
    state: ReportUiState,
    audioOpen: Boolean,
    onToggleAudio: () -> Unit,
    onRerunTask: (String) -> Unit,
    onFillMissing: () -> Unit,
    onReanalyze: () -> Unit,
    onOpenScoring: () -> Unit,
    onSeek: (Int, Int) -> Unit,
    onSelectSegment: (Int) -> Unit,
    onSeekConsumed: () -> Unit,
    onConfirmKeep: () -> Unit,
    onConfirmUnbind: () -> Unit,
    onSplitAt: (Double) -> Unit,
    onSubmitEvaluation: (String) -> Unit,
    onDeleteEvaluation: (Long) -> Unit,
    onRequestDelete: () -> Unit,
    onWithdrawDelete: () -> Unit,
) {
    val report = state.detail?.report
    val hasRecordings = state.recordings.isNotEmpty()

    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        // ---- 动作行：原始音频(大·主) + 重新分析/申请删除(小·次)，一排。
        //      机密/分析已完成 pill 去掉；任务执行状态挪到顶栏右上角 chip。----
        MeiliCard(tight = true, modifier = Modifier.padding(bottom = Dimens.CardGap)) {
            val deletePending = state.detail?.deleteRequestPending == true
            Column {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (hasRecordings) {
                        SoftButton(
                            text = "原始音频",
                            onClick = onToggleAudio,
                            icon = MeiliIcons.Headphone,
                            size = MeiliButtonSize.Small,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // 重新分析=小文字按钮；删除=垃圾桶图标按钮（无文字）。
                    // 删除审批中时把垃圾桶撤下，改在下方整条「删除审批中·撤销」蜜色条。
                    // G3(批次六)：0 片段时藏起"重新分析"（必然失败）
                    if (hasRecordings) {
                        GhostButton("重新分析", onReanalyze, size = MeiliButtonSize.Xs, enabled = !state.reanalyzing)
                    }
                    if (!deletePending) {
                        TopBarIconButton(MeiliIcons.Trash, onRequestDelete)
                    }
                }
                if (deletePending) {
                    DeletePendingBar(onClick = onWithdrawDelete, modifier = Modifier.padding(top = 9.dp))
                } else if (hasRecordings && state.freeDeleteEligible) {
                    // 提前告知：本次较短，点垃圾桶可直接删、不用等管理员审批。
                    FreeDeleteHint(modifier = Modifier.padding(top = 9.dp))
                }
            }
        }

        // ---- 原始音频折叠（整屏唯一入口；多段录音、真播放、跳播、说话人确认、分割）----
        if (hasRecordings) {
            AudioFold(
                open = audioOpen,
                recordings = state.recordings,
                segIndex = state.segIndex,
                audioUrl = state.currentAudioUrl,
                loadingUrl = state.audioUrlLoading,
                seekToSeconds = state.seekToken?.seconds,
                onSeekConsumed = onSeekConsumed,
                onSelectSegment = onSelectSegment,
                needsSpeakerConfirm = state.needsSpeakerConfirm,
                speakerCount = state.currentRecording?.asrSpeakerCount,
                confirming = state.confirming,
                onConfirmKeep = onConfirmKeep,
                onConfirmUnbind = onConfirmUnbind,
                isManager = state.isManager,
                onSplitAt = onSplitAt,
                modifier = Modifier.padding(bottom = if (audioOpen) Dimens.CardGap else 0.dp),
            )
        }

        // ---- 报告主体：未生成时三态（分析中 / 失败 / 空），有则 11 个 PART ----
        if (report == null) {
            ReportEmptyState(state = state, onReanalyze = onReanalyze)
        } else {
            // D6（同iOS-C5）：作废报告要当面说清，别让旧报告看着像有效的
            if ((state.detail?.displayStatus ?: state.detail?.analysisStatus) == "outdated") {
                OutdatedBanner(modifier = Modifier.padding(bottom = Dimens.CardGap))
            }
            // ---- 11 个 PART（顺序 01→11，缺字段各自优雅占位）----
            Part01Overview(report.overview, report.scoring)
            // PART01 详细评分入口：仅当有 scoring 明细时给出（warm_2 #report 的「查看详细评分 ▾」）。
            if (report.scoring != null) {
                SoftButton(
                    text = "查看详细评分",
                    onClick = onOpenScoring,
                    icon = MeiliIcons.ChevDown,
                    size = MeiliButtonSize.Small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                )
            }
            Part02Persona(report.persona)
            // 失分根因(PART03)已下线：不再生成/展示
            Part04PainPoints(report.painPoints)
            Part05Harvest(report.harvest)
            Part06Cases(report.cases, report.casesSummary, onSeek = onSeek)
            Part07LogicChain(report.logicChain)
            Part08NextSteps(report.nextSteps)
            Part09Competitors(report.externalSignals?.flatten())
            // 顾客标签(PART10)移到顾客档案/价值预测，报告页不再展示
            Part11DealDiagnosis(report.dealDiagnosis)
        }

        // ---- 老板/专家点评（可输入提交 + 删除已有；列表走 repo.evaluations）----
        BossCommentCard(
            evaluations = state.evaluations,
            submitting = state.evalSubmitting,
            onSubmit = onSubmitEvaluation,
            onDelete = onDeleteEvaluation,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * 报告未生成的三态（对齐 report.html #emptyState）：
 * running/queued → 分析进行中卡（转圈 + 进度文案）；failed → 失败卡（错误 + 重新分析）；
 * 其它（idle/无）→ 空态（立即分析）。
 */
@Composable
private fun ReportEmptyState(state: ReportUiState, onReanalyze: () -> Unit) {
    val status = state.detail?.displayStatus ?: state.detail?.analysisStatus
    MeiliCard(modifier = Modifier.padding(bottom = Dimens.CardGap)) {
        when (status) {
            "running", "queued" -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(color = MeiliPalette.Clay, strokeWidth = 2.5.dp, modifier = Modifier.size(30.dp))
                    Text(
                        if (status == "queued") "排队中…" else "分析正在进行中…",
                        style = MaterialTheme.typography.titleMedium,
                        color = MeiliPalette.Ink,
                    )
                    Text(
                        state.detail?.analysisProgress?.takeIf { it.isNotBlank() }
                            ?: "深度分析通常需要 2–6 分钟，本页会自动刷新进度，请勿离开。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MeiliPalette.Ink3,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            "failed", "stuck", "cancelled" -> {
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // D7：cancelled 与 failed 分开说——"已中断分析"，与接诊列表口径一致
                    Text(if (status == "cancelled") "已中断分析" else "分析失败", style = MaterialTheme.typography.titleMedium, color = MeiliPalette.Rose)
                    state.detail?.analysisError?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MeiliPalette.Ink3)
                    }
                    PrimaryButton("重新分析", onReanalyze, icon = MeiliIcons.Refresh, enabled = !state.reanalyzing, modifier = Modifier.fillMaxWidth())
                }
            }
            else -> {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (state.recordings.isEmpty()) {
                        // G3(批次六)：0 片段空壳——分析必然失败(服务端也已加守卫)，不给"立即分析"，
                        // 引导删除(服务端对空壳已改免审批直删)
                        Text(
                            "本次接诊已没有陪伴片段（可能已被退回或换绑）。如不需要，点右上角垃圾桶可直接删除。",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MeiliPalette.Ink2,
                            textAlign = TextAlign.Center,
                        )
                    } else {
                        Text(
                            "本次陪伴还没有生成分析报告。",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MeiliPalette.Ink2,
                            textAlign = TextAlign.Center,
                        )
                        PrimaryButton("立即分析", onReanalyze, icon = MeiliIcons.Spark, enabled = !state.reanalyzing, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

/** .pill 深墨底「销售复盘 · 内部机密」。 */
@Composable
private fun ConfidentialPill() {
    Surface(shape = MeiliShapes.Pill, color = MeiliPalette.InkSurface, contentColor = MeiliPalette.White) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(MeiliIcons.Lock, contentDescription = null, tint = MeiliPalette.White, modifier = Modifier.size(13.dp))
            Text(
                "销售复盘 · 内部机密",
                style = MaterialTheme.typography.labelMedium,
                color = MeiliPalette.White,
            )
        }
    }
}

/** 分析状态 pill：done→已完成；running/queued→分析中；failed/stuck→失败；D6/D7：outdated→已过期、cancelled→已中断（对齐 iOS）。 */
@Composable
private fun AnalysisStatePill(state: ReportUiState) {
    when (state.detail?.displayStatus ?: state.detail?.analysisStatus) {
        "running", "queued" -> StatusPill("分析进行中", PillKind.Run, icon = MeiliIcons.Sync)
        "failed", "stuck" -> StatusPill("分析失败", PillKind.Danger, icon = MeiliIcons.Warn)
        "outdated" -> StatusPill("报告已过期", PillKind.Warn, icon = MeiliIcons.Warn)
        "cancelled" -> StatusPill("已中断", PillKind.Warn, icon = MeiliIcons.Warn)
        else -> StatusPill("分析已完成", PillKind.Ok, icon = MeiliIcons.Check)
    }
}

/** D6：作废（outdated）横幅——片段有变更，旧报告已过期，提示重新分析。 */
@Composable
private fun OutdatedBanner(modifier: Modifier = Modifier) {
    Surface(
        shape = MeiliShapes.Md,
        color = MeiliPalette.HoneySoft,
        contentColor = MeiliPalette.HoneyText,
        border = BorderStroke(Dimens.BorderThin, MeiliPalette.Honey.copy(alpha = 0.45f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(MeiliIcons.Warn, contentDescription = null, modifier = Modifier.size(17.dp))
            Text(
                text = "陪伴片段在上次分析后有变更（新增/换绑/退回等），本报告已过期——点「重新分析」可更新。",
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold, lineHeight = 18.sp),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * 顶栏右上角「任务执行状态」chip：紧凑 pill 显示「X/11」（全完成→叶绿，否则陶土），点开 [TaskSheet]。
 */
@Composable
private fun TaskStatusChip(done: Int, total: Int, loading: Boolean, onClick: () -> Unit) {
    val allDone = total > 0 && done >= total
    val bg = if (allDone) MeiliPalette.LeafSoft else MeiliPalette.ClayTint
    val fg = if (allDone) MeiliPalette.LeafText else MeiliPalette.ClayDeep
    Surface(onClick = onClick, shape = MeiliShapes.Pill, color = bg, contentColor = fg) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(MeiliIcons.Tasks, contentDescription = "任务执行状态", tint = fg, modifier = Modifier.size(14.dp))
            Text(
                if (loading) "…" else "$done/$total",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold),
                color = fg,
            )
        }
    }
}

/** 免审批提示：本次陪伴不足 5 分钟，点垃圾桶可直接删、无需审批。一行小灰字，提前告知用户。 */
@Composable
private fun FreeDeleteHint(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(MeiliIcons.Trash, contentDescription = null, tint = MeiliPalette.Ink3, modifier = Modifier.size(13.dp))
        Text(
            "本次陪伴不足 5 分钟，可直接删除、无需审批",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal),
            color = MeiliPalette.Ink3,
        )
    }
}

/** 「删除审批中 · 点击撤销」整条蜜色条（替代垃圾桶图标）：已提交删除申请、待管理员审批；点它撤销删除。 */
@Composable
private fun DeletePendingBar(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = MeiliShapes.Sm,
        color = MeiliPalette.HoneySoft,
        contentColor = MeiliPalette.HoneyText,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(MeiliIcons.Clock, contentDescription = null, tint = MeiliPalette.HoneyText, modifier = Modifier.size(15.dp))
            Text(
                "删除审批中",
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                color = MeiliPalette.HoneyText,
                modifier = Modifier.weight(1f),
            )
            Text(
                "点击撤销",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = MeiliPalette.HoneyText,
            )
        }
    }
}

/**
 * 任务执行状态弹层（右上角 chip 点开）。平铺 11 个任务(T1–T11) + 单独「重跑」+「补齐所有缺失任务」。
 * 对齐原 report.html 顶部「📋 任务执行状态 (X/Y 完成)」，只是从内联折叠改成右上角入口 + 弹层。
 */
@Composable
private fun TaskSheet(
    visible: Boolean,
    tasks: List<Task>,
    loading: Boolean,
    submitting: Boolean,
    onRerunTask: (String) -> Unit,
    onFillMissing: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    val done = tasks.count { it.isDone }
    val total = if (tasks.isNotEmpty()) tasks.size else 11
    MeiliBottomSheet(
        visible = true,
        onDismiss = onDismiss,
        title = "任务执行状态",
        subtitle = if (tasks.isEmpty() && loading) "加载中…" else "$done / $total 完成",
        onClose = onDismiss,
        scrollable = true,
    ) {
        if (tasks.isEmpty()) {
            Text(
                if (loading) "加载中…" else "暂无任务信息",
                style = MaterialTheme.typography.bodySmall,
                color = MeiliPalette.Ink3,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            tasks.forEach { t -> TaskRow(task = t, submitting = submitting, onRerun = onRerunTask) }
            SoftButton(
                text = "补齐所有缺失任务",
                onClick = onFillMissing,
                icon = MeiliIcons.Sync,
                size = MeiliButtonSize.Small,
                enabled = !submitting,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            )
        }
    }
}

/** 单个任务行：状态圆点 + 任务号(T1..) + 名称 + 状态文字 + 「重跑」；失败时下方红字错误。 */
@Composable
private fun TaskRow(task: Task, submitting: Boolean, onRerun: (String) -> Unit) {
    val (label, color) = when {
        task.isDone -> "已完成" to MeiliPalette.LeafText
        task.isRunning -> "进行中" to MeiliPalette.Clay
        task.isFailed -> "失败" to MeiliPalette.Rose
        else -> "待跑" to MeiliPalette.Ink4
    }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Box(modifier = Modifier.size(8.dp).background(color, MeiliShapes.Pill))
            Text(
                task.id,
                style = MaterialTheme.typography.labelMedium,
                color = MeiliPalette.Ink3,
                modifier = Modifier.width(30.dp),
            )
            Text(
                task.name ?: "—",
                style = MaterialTheme.typography.bodySmall,
                color = MeiliPalette.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(label, style = MaterialTheme.typography.labelSmall, color = color)
            GhostButton("重跑", { onRerun(task.id) }, size = MeiliButtonSize.Xs, enabled = !submitting)
        }
        if (task.isFailed && !task.error.isNullOrBlank()) {
            Text(
                task.error!!,
                style = MaterialTheme.typography.labelSmall,
                color = MeiliPalette.Rose,
                modifier = Modifier.padding(start = 17.dp, top = 2.dp),
            )
        }
    }
}

@Composable
private fun LoadingBlock() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 120.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = MeiliPalette.Clay)
    }
}

@Composable
private fun ErrorBlock(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 100.dp, start = 8.dp, end = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(message, style = MaterialTheme.typography.bodyLarge, color = MeiliPalette.Ink2, textAlign = TextAlign.Center)
        PrimaryButton("重新加载", onRetry, icon = MeiliIcons.Refresh)
    }
}

/**
 * 通用二次确认弹层（重新分析等"重操作"前确认；对齐 report.html confirm(...)）。
 * 屏内 sheet，不动 nav。confirm 按钮在 [confirming] 时禁用并显示进行中。
 */
@Composable
private fun ConfirmSheet(
    visible: Boolean,
    title: String,
    message: String,
    confirmText: String,
    confirming: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector = MeiliIcons.Refresh,
) {
    if (!visible) return
    MeiliBottomSheet(visible = true, onDismiss = onDismiss, title = title, subtitle = message) {
        PrimaryButton(
            text = if (confirming) "处理中…" else confirmText,
            onClick = onConfirm,
            icon = icon,
            enabled = !confirming,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        )
        GhostButton(
            text = "取消",
            onClick = onDismiss,
            size = MeiliButtonSize.Small,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
        )
    }
}

/**
 * 质检评分明细弹层（PART01「查看详细评分」）。
 * 总分大字 + 各阶段（名称 + 分）+ 子项行（名称 + 分 + 说明）。无明细时给占位。
 */
@Composable
private fun ScoringSheet(
    visible: Boolean,
    scoring: Scoring?,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    // 综合分/阶段分一律重算（阶段=子项均值，综合=阶段均值），不读后端可能为 0/空的存量值。
    val overall = overallScoreOf(scoring)
    MeiliBottomSheet(
        visible = true,
        onDismiss = onDismiss,
        title = "质检评分明细",
        subtitle = "AI 质检按阶段拆分打分，仅供顾问复盘参考。",
        onClose = onDismiss,       // 右上角 ✕ 收起
        scrollable = true,         // 阶段多时可滚动，避免底部（如 3.x 分项）被裁切
    ) {
        if (scoring == null || (overall == null && scoring.stages.isNullOrEmpty())) {
            Text(
                "暂无评分明细",
                style = MaterialTheme.typography.bodyMedium,
                color = MeiliPalette.Ink3,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 20.dp),
                textAlign = TextAlign.Center,
            )
        } else {
            // 总分
            overall?.let {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                    Text(formatScoreValue(it), style = MeiliTheme.scoreStyle, color = scoreTextColor(it))
                    Text(
                        "综合质检评分 / 10",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal),
                        color = MeiliPalette.Ink3,
                    )
                }
            }
            // 各阶段（阶段分重算）
            scoring.stages?.forEach { stage -> ScoringStageBlock(stage, stageScoreOf(stage)) }
        }
    }
}

@Composable
private fun ScoringStageBlock(stage: ScoringStage, stageScore: Double?) {
    Surface(
        shape = MeiliShapes.Sm,
        color = MeiliPalette.SurfaceSoft,
        contentColor = MeiliPalette.Ink,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stage.name.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.ExtraBold),
                    color = MeiliPalette.ClayDeep,
                    modifier = Modifier.weight(1f),
                )
                stageScore?.let {
                    Text(
                        formatScoreValue(it),
                        style = MeiliTheme.summaryStyle.copy(fontSize = 18.sp),
                        color = scoreTextColor(it),
                    )
                }
            }
            stage.sub?.forEach { sub -> SubItemRow(sub) }
        }
    }
}

/**
 * 子项评分行：名称 + 分数同一行（分数右对齐、彩色，小数完整显示）；下方一条进度条 + 可选说明。
 * 纵向铺开避免「名称/进度条/分数」三者挤在一行把分数截断（之前 3.x 显示不全的根因）。
 */
@Composable
private fun SubItemRow(sub: com.airec.bledemo.data.model.ScoringSub) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 11.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                sub.name.orEmpty(),
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                color = MeiliPalette.Ink,
                modifier = Modifier.weight(1f),
            )
            sub.score?.let {
                Text(
                    formatScoreValue(it),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.ExtraBold),
                    color = scoreTextColor(it),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        sub.score?.let { sc ->
            val frac = (sc / 10.0).coerceIn(0.0, 1.0).toFloat()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .height(5.dp)
                    .background(MeiliPalette.Line, MeiliShapes.Pill),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(frac)
                        .height(5.dp)
                        .background(scoreBarColor(sc), MeiliShapes.Pill),
                )
            }
        }
        sub.detail?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal),
                color = MeiliPalette.Ink3,
                modifier = Modifier.padding(top = 5.dp),
            )
        }
    }
}

/** 分数文字色（阶段/子项）：≥7 叶绿 / ≥5 蜜 / 否则玫瑰（对齐 web sc-ok/warn/bad）。 */
private fun scoreTextColor(s: Double): androidx.compose.ui.graphics.Color = when {
    s >= 7.0 -> MeiliPalette.LeafText
    s >= 5.0 -> MeiliPalette.HoneyText
    else -> MeiliPalette.RoseText
}

/** 进度条填充色：≥7 叶绿 / ≥5 蜜 / 否则玫瑰（对齐 web bar-good/warn/bad）。 */
private fun scoreBarColor(s: Double): androidx.compose.ui.graphics.Color = when {
    s >= 7.0 -> MeiliPalette.Leaf
    s >= 5.0 -> MeiliPalette.Honey
    else -> MeiliPalette.Rose
}

/** toast（.toast）：底部深墨条；danger 玫瑰底。约 2.6 秒自动消失。 */
@Composable
private fun ReportToastBar(toast: ReportToast, modifier: Modifier = Modifier, onDismiss: () -> Unit) {
    LaunchedEffect(toast) {
        kotlinx.coroutines.delay(2600)
        onDismiss()
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.ScreenH, vertical = 96.dp),
        shape = MeiliShapes.Sm,
        color = if (toast.danger) MeiliPalette.Rose else MeiliPalette.InkSurface,
        contentColor = MeiliPalette.White,
        shadowElevation = Dimens.Elev2,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 17.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (toast.danger) MeiliIcons.Warn else MeiliIcons.Check,
                contentDescription = null,
                tint = MeiliPalette.White,
                modifier = Modifier.size(18.dp),
            )
            Text(toast.text, style = MaterialTheme.typography.bodyMedium, color = MeiliPalette.White)
        }
    }
}

// ─────────────────────────── helpers ───────────────────────────

/** 整数分去掉 .0，否则保留一位小数（评分展示口径与 ReportParts 一致）。 */
private fun formatScoreValue(score: Double): String =
    if (score == score.toLong().toDouble()) score.toLong().toString() else String.format("%.1f", score)
