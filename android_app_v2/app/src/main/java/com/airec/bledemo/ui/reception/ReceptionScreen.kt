package com.airec.bledemo.ui.reception

import android.app.DatePickerDialog
import android.media.MediaPlayer
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.airec.bledemo.data.model.Customer
import com.airec.bledemo.data.model.PendingRecording
import com.airec.bledemo.data.model.TodayReception
import com.airec.bledemo.data.repo.ApiResult
import com.airec.bledemo.data.repo.ConsultantRepository
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
import com.airec.bledemo.recording.RecordingModule
import com.airec.bledemo.ui.pending.BannerKind
import com.airec.bledemo.ui.pending.CheckRow
import com.airec.bledemo.ui.pending.InfoBanner
import com.airec.bledemo.ui.pending.PendingToast
import com.airec.bledemo.ui.pending.PendingViewModel
import com.airec.bledemo.ui.pending.PenSyncUiState
import com.airec.bledemo.ui.pending.ToastIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * 接诊（方案A · redesign_2026-06-13 #triage）。
 *
 * 把原「今日接诊」与「待整理」合并成一屏单滚：
 *  - 顶部：标题「接诊」+「刷新」文字钮（同时刷新两个列表）。
 *  - 日期切换条（前一天 / 后一天 / 选日期，受补登窗口约束、不越过今天）。
 *  - 醒目「加入今日接诊」主按钮（置顶）。
 *  - 「今日接诊 · N 位」：每条顾客行（姓名 + 内联「接诊 M-DD」可点日期 chip / 尾号·会员 / 状态胶囊 /
 *     右侧移除 + 按状态驱动的上下文按钮：去陪伴 / 开始分析 / 看进度 / 看报告 / 重新分析）。
 *  - 「待整理 · K 段待绑定」：每段未绑定陪伴的紧凑三排卡（相册图标 + 时段·时长 / 服务日期·未绑定 / 试听 +
 *     绑定顾客 + 申请删除），保留跨日玫瑰态、同步中占位、截断 / 误录提示；底部「从陪伴笔同步」。
 *
 * 数据 / 状态：[ReceptionViewModel]（今日接诊）+ [PendingViewModel]（待整理 / 从陪伴笔同步）。
 * 导航回调由 [com.airec.bledemo.nav.AppScaffold] 注入。
 *
 * @param onBindCustomer 待整理某段→绑定顾客（recordingId）
 * @param onOpenPreview 接诊某条→接诊包预览（customerId + 服务日期；绑定录音 / 开始分析 / 看进度 / 重新分析）
 * @param onOpenReport 接诊某条→分析报告（sessionId，已完成）
 * @param openSyncSignal 进页即自动打开「从陪伴笔同步」（一键直达，消费后回调 [onSyncSignalConsumed]）
 * @param modifier 由 AppScaffold 传入（含底栏避让 padding）
 */
@Composable
fun ReceptionScreen(
    onBindCustomer: (recordingId: Long) -> Unit = {},
    onOpenPreview: (customerId: Long, date: String) -> Unit = { _, _ -> },
    onOpenReport: (sessionId: Long) -> Unit = {},
    openSyncSignal: Boolean = false,
    onSyncSignalConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val receptionVm: ReceptionViewModel = viewModel()
    // 待整理 VM 需要共享录音引擎（从陪伴笔同步靠它拉机身片段 / 导入）——controller 是构造参数，走 Factory。
    val pendingVm: PendingViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                PendingViewModel(controller = RecordingModule.controller) as T
        },
    )

    val state by receptionVm.ui.collectAsStateWithLifecycle()
    val pending by pendingVm.state.collectAsStateWithLifecycle()
    val penSync by pendingVm.penSync.collectAsStateWithLifecycle()
    val pendingToast by pendingVm.toast.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 回到本页即刷新接诊列表（从接诊包预览「开始/重新分析」「退回片段」回来后，
    // 状态胶囊立刻反映最新——分析中 / 已作废，而不是傻等 20s 轮询）。
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) receptionVm.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // 试听播放态（按片段 id）——抬到屏级，待整理自动轮询据此「播放中跳过」。
    val playingIds = remember { mutableStateMapOf<Long, Boolean>() }
    val anyPlaying by remember { derivedStateOf { playingIds.values.any { it } } }

    // 待整理屏内 dialog 状态（不碰 nav）
    var delReasonFor by remember { mutableStateOf<Long?>(null) }   // 「申请删除」原因弹窗目标 rid（≥5分钟，走审批）
    var delDirectFor by remember { mutableStateOf<Long?>(null) }   // 「免审批直接删除」确认目标 rid（<5分钟）
    var confirmImport by remember { mutableStateOf(false) }        // 「导入选中」条数二次确认

    // 接诊一次性 toast（成交 / 删除 / 改期 / 错误）
    LaunchedEffect(state.toast) {
        if (state.toast != null) {
            delay(2200)
            receptionVm.toastShown()
        }
    }
    // 待整理一次性 toast（试听 / 删除申请 / 导入 / 刷新…）
    LaunchedEffect(pendingToast) {
        if (pendingToast != null) {
            delay(2200)
            pendingVm.toastShown()
        }
    }

    // 从首页「从陪伴笔同步」跳来：进页即自动打开同步 sheet，消费一次性信号避免重开。
    LaunchedEffect(openSyncSignal) {
        if (openSyncSignal) {
            pendingVm.openPenSync()
            onSyncSignalConsumed()
        }
    }

    // 待整理自动刷新：可见时每 ~5s 轮询；试听播放中 / 同步 sheet 打开时跳过，避免打断。
    LaunchedEffect(Unit) {
        while (true) {
            delay(5000)
            if (!anyPlaying && !penSync.visible) pendingVm.poll()
        }
    }

    /** 顶部「刷新」：同时刷新两个列表。 */
    val refreshBoth = {
        receptionVm.refresh()
        pendingVm.poll()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MeiliPalette.Bg),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Dimens.ScreenH),
            contentPadding = PaddingValues(bottom = Dimens.BottomNavInset),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            // 顶栏：标题「接诊」+ 副标 + 右上「刷新」文字钮（无图标）
            item {
                MeiliTopBar(
                    title = "接诊",
                    subtitle = "今天要见的顾客 + 待绑定的陪伴",
                    actions = {
                        GhostButton(
                            text = "刷新",
                            onClick = refreshBoth,
                            size = MeiliButtonSize.Small,
                        )
                    },
                )
            }

            // 日期切换条
            item {
                Spacer(Modifier.height(4.dp))
                DateSwitch(
                    date = state.date,
                    isToday = state.isToday,
                    onPrev = { receptionVm.prevDay() },
                    onNext = { receptionVm.nextDay() },
                    onToday = { receptionVm.goToday() },
                    onPick = {
                        showDatePicker(
                            context = context,
                            current = state.date,
                            minDate = receptionVm.minBackfillDate,
                            maxDate = receptionVm.maxDate,
                            onPicked = { receptionVm.selectDate(it) },
                        )
                    },
                )
                Spacer(Modifier.height(Dimens.S3))
            }

            // 醒目主按钮：加入今日接诊（置顶）
            item {
                PrimaryButton(
                    text = "加入今日接诊",
                    onClick = { receptionVm.openAddSheet() },
                    icon = MeiliIcons.Add,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(Dimens.S4))
            }

            // ── 今日接诊 ──
            item {
                SectionLabel(
                    text = "今日接诊 · ${state.items.size} 位",
                    icon = MeiliIcons.Reception,
                )
                Spacer(Modifier.height(9.dp))
            }

            when {
                state.loading && state.items.isEmpty() -> item { LoadingRow() }
                state.errorMsg != null && state.items.isEmpty() -> item {
                    EmptyState(
                        icon = MeiliIcons.Warn,
                        title = "没能加载接诊列表",
                        sub = state.errorMsg ?: "请稍后重试",
                    )
                }
                state.items.isEmpty() -> item {
                    EmptyState(
                        icon = MeiliIcons.Reception,
                        title = if (state.isToday) "今天还没有接诊顾客" else "这一天没有接诊记录",
                        sub = "点上方「加入今日接诊」，把要陪伴的顾客加进来",
                    )
                }
                else -> item {
                    MeiliCard(tight = true) {
                        state.items.forEachIndexed { idx, item ->
                            ReceptionRow(
                                item = item,
                                showDivider = idx != state.items.lastIndex,
                                onEditDate = { receptionVm.openEditDate(item) },
                                onRemove = { receptionVm.askRemove(item) },
                                onOpenPreview = onOpenPreview,
                                onOpenReport = onOpenReport,
                            )
                        }
                    }
                }
            }

            // ── 待整理 ──
            item {
                Spacer(Modifier.height(Dimens.S4))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionLabel(
                        text = "待整理 · ${pending.recordings.size} 段待绑定",
                        icon = MeiliIcons.Tidy,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "没绑定不会进入分析",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MeiliPalette.Ink3,
                        modifier = Modifier.padding(end = 2.dp),
                    )
                }
                Spacer(Modifier.height(9.dp))
            }

            // 跨日提示 banner（列出与当前接诊不一致的具体日期）
            if (pending.crossDayDates.isNotEmpty()) {
                item {
                    val dates = pending.crossDayDates
                    InfoBanner(
                        text = "有 ${dates.size} 段陪伴的日期与当前接诊不一致（${dates.joinToString("、")}），" +
                            "记得在「绑定顾客」时选对应的服务日期。",
                        kind = BannerKind.Warn,
                        icon = MeiliIcons.Warn,
                        modifier = Modifier.padding(bottom = Dimens.CardGap),
                    )
                }
            }

            // 加载失败
            pending.error?.let { msg ->
                item {
                    InfoBanner(
                        text = msg,
                        kind = BannerKind.Danger,
                        icon = MeiliIcons.Warn,
                        modifier = Modifier.padding(bottom = Dimens.CardGap),
                    )
                }
            }

            when {
                pending.loading && pending.recordings.isEmpty() -> item { LoadingRow() }
                pending.isEmpty -> item { PendingEmptyState() }
                else -> {
                    items(pending.recordings, key = { it.id }) { rec ->
                        TriageRecordingCard(
                            rec = rec,
                            busy = rec.id in pending.busyIds,
                            canRetry = pendingVm.canRetryPenUploads,
                            onBindCustomer = onBindCustomer,
                            onRequestDelete = {
                                // <5分钟→免审批直接删(简单确认)；≥5分钟→原因弹窗走审批。
                                if (isFreeDeleteEligible(rec)) delDirectFor = rec.id else delReasonFor = rec.id
                            },
                            onWithdrawDelete = { pendingVm.withdrawDelete(rec.id) },
                            onDismissReject = { pendingVm.dismissDeleteReject(rec.id) },
                            onRetry = pendingVm::retryPenUploads,
                            onPlayingChange = { p -> playingIds[rec.id] = p },
                            onPreviewToast = { pendingVm.showToast(it, ToastIcon.Play) },
                            modifier = Modifier.padding(bottom = Dimens.CardGap),
                        )
                    }
                }
            }

            // 从陪伴笔同步（待整理区底部，满宽 ghost）
            item {
                GhostButton(
                    text = "从陪伴笔同步",
                    onClick = pendingVm::openPenSync,
                    icon = MeiliIcons.Sync,
                    size = MeiliButtonSize.Small,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // 接诊 toast（贴底，避让底栏）
        state.toast?.let { msg ->
            ToastBar(
                text = msg,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = Dimens.ScreenH, vertical = 8.dp),
            )
        }
        // 待整理 toast（带语义图标）
        PendingToast(
            event = pendingToast,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = Dimens.ScreenH, vertical = 18.dp),
        )
    }

    // ── 接诊弹层 ──

    // 新增 / 补登
    state.addSheet?.let { sheet ->
        AddReceptionSheet(
            sheet = sheet,
            onDismiss = { receptionVm.closeAddSheet() },
            onSwitchMode = { receptionVm.switchAddMode(it) },
            onQueryChange = { receptionVm.onSearchQueryChange(it) },
            onPickExisting = { receptionVm.addExisting(it) },
            onNewNameChange = { receptionVm.onNewNameChange(it) },
            onNewPhoneTailChange = { receptionVm.onNewPhoneTailChange(it) },
            onNewMemberCardChange = { receptionVm.onNewMemberCardChange(it) },
            onSubmitNew = { receptionVm.addNew() },
        )
    }

    // 删除确认
    state.confirmRemove?.let { item ->
        ConfirmRemoveSheet(
            item = item,
            onCancel = { receptionVm.cancelRemove() },
            onConfirm = { receptionVm.confirmRemove() },
        )
    }

    // 改接诊日期
    state.editDate?.let { ed ->
        EditDateSheet(
            state = ed,
            onDismiss = { receptionVm.closeEditDate() },
            onPickDate = {
                showDatePicker(
                    context = context,
                    current = ed.target,
                    minDate = ed.minDate,
                    maxDate = ed.maxDate,
                    onPicked = { receptionVm.onEditDatePicked(it) },
                )
            },
            onConfirm = { receptionVm.confirmEditDate() },
        )
    }

    // ── 待整理弹层 ──

    // 「从陪伴笔同步」sheet（导入前先二次确认条数）
    PenSyncSheet(
        state = penSync,
        onDismiss = pendingVm::closePenSync,
        onToggleRow = pendingVm::togglePenRow,
        onToggleAll = pendingVm::togglePenAll,
        onImport = { confirmImport = true },
    )

    // 「申请删除」原因输入弹窗（≥5分钟，可空原因，确认才提交走审批）
    delReasonFor?.let { rid ->
        DeleteReasonDialog(
            onConfirm = { reason ->
                delReasonFor = null
                pendingVm.requestDelete(rid, reason)
            },
            onDismiss = { delReasonFor = null },
        )
    }

    // 「免审批直接删除」确认（<5分钟，无需原因/审批）
    delDirectFor?.let { rid ->
        ConfirmDialog(
            icon = MeiliIcons.Trash,
            title = "删除这段陪伴？",
            body = "这段不足 5 分钟，可直接删除、无需审批。删除后不可恢复。",
            confirmText = "确定删除",
            danger = true,
            onConfirm = {
                delDirectFor = null
                pendingVm.requestDelete(rid, null)
            },
            onDismiss = { delDirectFor = null },
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
                pendingVm.importSelected()
            },
            onDismiss = { confirmImport = false },
        )
    }
}

// ─────────────────────────── 日期切换条 ───────────────────────────

@Composable
private fun DateSwitch(
    date: String,
    isToday: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    onPick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        DateNavButton(MeiliIcons.ChevLeft, onClick = onPrev)
        Surface(
            onClick = onPick,
            modifier = Modifier.weight(1f),
            shape = MeiliShapes.Sm,
            color = MeiliPalette.Surface,
            contentColor = MeiliPalette.Ink,
            border = BorderStroke(Dimens.BorderThin, MeiliPalette.Line),
            shadowElevation = Dimens.Elev1,
        ) {
            Text(
                text = date,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                color = MeiliPalette.Ink,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 11.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        DateNavButton(
            MeiliIcons.ChevRight,
            onClick = onNext,
            enabled = !isToday,
        )
        if (isToday) {
            Surface(
                shape = MeiliShapes.Pill,
                color = MeiliPalette.LeafSoft,
                contentColor = MeiliPalette.LeafText,
            ) {
                Text(
                    text = "今天",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.sp,
                    ),
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
                )
            }
        } else {
            // 回到今天（仅非今天时显示）
            Surface(
                onClick = onToday,
                shape = MeiliShapes.Pill,
                color = MeiliPalette.ClaySoft,
                contentColor = MeiliPalette.ClayDeep,
                border = BorderStroke(Dimens.BorderThin, MeiliPalette.Line),
            ) {
                Text(
                    text = "回到今天",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.sp,
                    ),
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
                )
            }
        }
    }
}

@Composable
private fun DateNavButton(
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MeiliShapes.Sm,
        color = MeiliPalette.Surface,
        contentColor = if (enabled) MeiliPalette.Ink2 else MeiliPalette.Ink4,
        border = BorderStroke(Dimens.BorderThin, MeiliPalette.Line),
        shadowElevation = Dimens.Elev1,
        modifier = Modifier.size(38.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(Dimens.IconSm))
        }
    }
}

/** 拉起系统日期选择器，限制在补登窗口内、不越过今天。 */
private fun showDatePicker(
    context: android.content.Context,
    current: String,
    minDate: String,
    maxDate: String,
    onPicked: (String) -> Unit,
) {
    fun toMillis(s: String): Long? {
        val p = s.split("-").mapNotNull { it.toIntOrNull() }
        if (p.size != 3) return null
        return Calendar.getInstance().apply {
            clear()
            set(p[0], p[1] - 1, p[2], 0, 0, 0)
        }.timeInMillis
    }
    val minMs = toMillis(minDate)
    val maxMs = toMillis(maxDate)
    // 初始日期夹到 [min,max] 区间内，避免越界时系统抛 IllegalArgumentException
    val initMs = (toMillis(current) ?: maxMs)?.let { ms ->
        var v = ms
        if (minMs != null && v < minMs) v = minMs
        if (maxMs != null && v > maxMs) v = maxMs
        v
    }
    val cal = Calendar.getInstance()
    if (initMs != null) cal.timeInMillis = initMs
    val dlg = DatePickerDialog(
        context,
        { _, y, m, d ->
            onPicked("%04d-%02d-%02d".format(y, m + 1, d))
        },
        cal.get(Calendar.YEAR),
        cal.get(Calendar.MONTH),
        cal.get(Calendar.DAY_OF_MONTH),
    )
    minMs?.let { dlg.datePicker.minDate = it }
    maxMs?.let { dlg.datePicker.maxDate = it }
    dlg.show()
}

// ─────────────────────────── 今日接诊行 ───────────────────────────

@Composable
private fun ReceptionRow(
    item: TodayReception,
    showDivider: Boolean,
    onEditDate: () -> Unit,
    onRemove: () -> Unit,
    onOpenPreview: (customerId: Long, date: String) -> Unit,
    onOpenReport: (Long) -> Unit,
) {
    val name = item.name?.takeIf { it.isNotBlank() } ?: "未知顾客"
    // 可编辑态（未分析/未运行 + 近 7 天内）才允许点 chip 改接诊日期，对齐 web editable。
    val editable = item.editable
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 15.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Avatar(name = name, sage = (item.id % 2L == 0L))
        Column(modifier = Modifier.weight(1f)) {
            // Row1: 姓名 + 内联「接诊 M-DD」日期 chip
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MeiliPalette.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                DateChip(
                    label = "接诊 ${shortDate(item.serviceDate)}",
                    enabled = editable,
                    onClick = onEditDate,
                )
            }
            Text(
                text = metaLine(item),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Normal,
                    letterSpacing = 0.sp,
                ),
                color = MeiliPalette.Ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            statusFor(item)?.let { (text, kind, icon) ->
                Spacer(Modifier.height(7.dp))
                StatusPill(text = text, kind = kind, icon = icon)
            }
        }
        // 右列：移除（上）+ 上下文动作（下）
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RowIconButton(
                icon = MeiliIcons.Trash,
                contentDescription = "移出今日接诊",
                onClick = onRemove,
            )
            ContextAction(
                item = item,
                onOpenPreview = onOpenPreview,
                onOpenReport = onOpenReport,
            )
        }
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

/**
 * 接诊行右下角的上下文动作（按 状态 + 录音数 驱动）：
 *  - 无陪伴 → 「绑定录音」（开接诊包按 customerId+日期，里面列「本人当日未绑定的陪伴」可加入绑定，对齐 web openPkg）；
 *    未分析 → 开始分析；pending/queued/running → 看进度；done → 看报告；failed → 重新分析。
 * 预览类动作走 customerId+日期（判空），看报告走 sessionId（判空），缺键则不出该按钮。
 */
@Composable
private fun ContextAction(
    item: TodayReception,
    onOpenPreview: (customerId: Long, date: String) -> Unit,
    onOpenReport: (Long) -> Unit,
) {
    val sid = item.sessionId
    val cid = item.customerId
    val date = item.serviceDate.orEmpty()
    val canPreview = cid != null && date.isNotBlank()
    val recCount = item.recordingCount ?: 0
    val status = item.analysisStatus

    when {
        // 尚无陪伴：点「绑定录音」→ 开接诊包，挑「本人当日未绑定的陪伴」加入绑定
        recCount == 0 -> if (canPreview) {
            PrimaryButton(text = "绑定录音", onClick = { onOpenPreview(cid!!, date) }, size = MeiliButtonSize.Xs)
        }
        status == "done" -> if (sid != null) {
            HoneyButton(text = "看报告", onClick = { onOpenReport(sid) })
        }
        status == "failed" -> if (canPreview) {
            GhostButton(text = "重新分析", onClick = { onOpenPreview(cid!!, date) }, size = MeiliButtonSize.Xs)
        }
        status == "running" || status == "queued" || status == "pending" -> if (canPreview) {
            GhostButton(text = "看进度", onClick = { onOpenPreview(cid!!, date) }, size = MeiliButtonSize.Xs)
        }
        // 有陪伴但未分析（null/""/idle）
        else -> if (canPreview) {
            PrimaryButton(text = "开始分析", onClick = { onOpenPreview(cid!!, date) }, size = MeiliButtonSize.Xs)
        }
    }
}

/**
 * 蜜色小按钮（对应原型 .btn-honey.btn-xs）——「看报告」用。
 * 设计系统未单列 HoneyButton，这里就地用 [MeiliPalette.HoneyGradient] 拼一个 xs 实心钮，气质一致。
 */
@Composable
private fun HoneyButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        shape = MeiliShapes.Pill,
        color = androidx.compose.ui.graphics.Color.Transparent,
        contentColor = MeiliPalette.White,
        modifier = modifier
            .heightIn(min = 34.dp)
            .scale(if (pressed) 0.97f else 1f),
    ) {
        Box(
            modifier = Modifier
                .background(MeiliPalette.HoneyGradient, MeiliShapes.Pill)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            CompositionLocalProvider(LocalContentColor provides MeiliPalette.White) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.5f.sp),
                )
            }
        }
    }
}

/** 内联「接诊 M-DD」日期 chip（陶土 tint pill + 日历图标），可编辑态可点改期。 */
@Composable
private fun DateChip(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val base = Modifier
    val clickable = if (enabled) base.clickable(onClick = onClick) else base
    Surface(
        modifier = clickable,
        shape = MeiliShapes.Pill,
        color = MeiliPalette.ClayTint,
        contentColor = MeiliPalette.ClayDeep,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(MeiliIcons.Reception, contentDescription = null, modifier = Modifier.size(13.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 11.5f.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.sp,
                ),
                maxLines = 1,
            )
        }
    }
}

/** 行右侧的方形线性图标按钮（移出今日接诊）。 */
@Composable
private fun RowIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MeiliShapes.Sm,
        color = MeiliPalette.Surface,
        contentColor = MeiliPalette.Ink3,
        border = BorderStroke(Dimens.BorderThin, MeiliPalette.Line),
        modifier = Modifier.size(36.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(Dimens.IconSm))
        }
    }
}

/** "YYYY-MM-DD" → "M-DD"；解析不出就原样回退（再退到 "—"）。 */
private fun shortDate(date: String?): String {
    val d = date?.takeIf { it.isNotBlank() } ?: return "—"
    val p = d.split("-")
    return if (p.size == 3) "${p[1].trimStart('0').ifEmpty { "0" }}-${p[2]}" else d
}

/** 尾号 + 会员号 / 新老客拼一行 meta。 */
private fun metaLine(item: TodayReception): String {
    val parts = mutableListOf<String>()
    item.phoneTail?.takeIf { it.isNotBlank() }?.let { parts.add("尾号$it") }
    val card = item.memberCard?.takeIf { it.isNotBlank() }
    if (card != null) parts.add(card) else parts.add("新客")
    return parts.joinToString(" · ")
}

/** 把 analysis_status + 录音数 + 换绑审批数映射成一个状态胶囊（顾客可见，零「录音」字样）。 */
private fun statusFor(item: TodayReception): Triple<String, PillKind, ImageVector?>? {
    val rebind = item.pendingRebindCount ?: 0
    if (rebind > 0) {
        return Triple("换绑审批中（$rebind）", PillKind.Warn, MeiliIcons.Clock)
    }
    return when (item.analysisStatus) {
        "done" -> Triple("已完成", PillKind.Ok, MeiliIcons.Check)
        "failed" -> Triple("分析失败，可重试", PillKind.Danger, MeiliIcons.Warn)
        "running", "queued" -> Triple("分析中…", PillKind.Run, MeiliIcons.Sync)
        "pending" -> Triple("待分析", PillKind.Warn, MeiliIcons.Clock)
        null, "" -> {
            val n = item.recordingCount ?: 0
            if (n > 0) {
                Triple("$n 段陪伴待分析", PillKind.Warn, MeiliIcons.Clock)
            } else {
                Triple("等待绑定录音", PillKind.Neutral, MeiliIcons.Clock)
            }
        }
        else -> Triple("待分析", PillKind.Warn, MeiliIcons.Clock)
    }
}

@Composable
private fun Avatar(name: String, sage: Boolean) {
    Box(
        modifier = Modifier
            .size(Dimens.Avatar)
            .background(
                if (sage) MeiliPalette.SageSoft else MeiliPalette.ClaySoft,
                MeiliShapes.Sm,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.take(1),
            style = MaterialTheme.typography.headlineMedium.copy(fontSize = 17.sp),
            color = if (sage) MeiliPalette.SageDeep else MeiliPalette.ClayDeep,
        )
    }
}

// ─────────────────────────── 待整理：紧凑三排卡 ───────────────────────────

/** 这段待绑定录音是否够免审批删除：时长可解析且 <5 分钟（与后端 FREE_DELETE_MAX_SEC=300 同口径）。 */
private fun isFreeDeleteEligible(rec: PendingRecording): Boolean {
    val label = rec.durationLabel
    if (label.isNullOrBlank()) return false
    val m = Regex("""^\s*(\d+)\s*分\s*(\d+)\s*秒""").find(label) ?: return false
    val secs = (m.groupValues[1].toIntOrNull() ?: return false) * 60 + (m.groupValues[2].toIntOrNull() ?: return false)
    return secs < 300
}

@Composable
private fun TriageRecordingCard(
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
        else -> TriageNormalCard(
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
private fun TriageNormalCard(
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
    val freeDelete = isFreeDeleteEligible(rec)   // <5分钟→免审批直接删
    val titleColor = if (crossDay) MeiliPalette.RoseText else MeiliPalette.Ink

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MeiliShapes.Lg,
        color = if (crossDay) MeiliPalette.RoseSoft else MeiliPalette.Surface,
        contentColor = MeiliPalette.Ink,
        border = BorderStroke(
            Dimens.BorderThin,
            if (crossDay) MeiliPalette.RoseLine else MeiliPalette.LineSoft,
        ),
        shadowElevation = Dimens.Elev2,
    ) {
        Column(modifier = Modifier.padding(Dimens.CardPadTight)) {
            // 试听控制器：行内小播放钮（跟时段/服务日期并排）+ 点了才展开的进度条（省面积）
            val audio = com.airec.bledemo.ui.pending.rememberPreviewAudio(
                recordingId = rec.id,
                directUrl = rec.audioUrl,
                processing = rec.isProcessing,
                onPlayingChange = onPlayingChange,
                onPreviewToast = onPreviewToast,
            )
            // tri-top: 相册图标 chip + 时段/时长 标题 + 副标 + 行内播放钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .background(
                            if (crossDay) MeiliPalette.RoseSoft else MeiliPalette.ClayTint,
                            MeiliShapes.Xs,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        MeiliIcons.Album,
                        contentDescription = null,
                        tint = if (crossDay) MeiliPalette.RoseText else MeiliPalette.ClayDeep,
                        modifier = Modifier.size(Dimens.IconSm),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = timeRangeLabel(rec),
                        style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.5f.sp, fontWeight = FontWeight.ExtraBold),
                        color = titleColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = subLabel(rec, crossDay),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (crossDay) MeiliPalette.RoseText else MeiliPalette.Ink3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                // 行内播放钮：跟时段/服务日期并排，点了才在下方展开进度条
                com.airec.bledemo.ui.pending.PreviewPlayDot(audio)
            }

            // 删除申请被拒：红 banner + 「知道了」
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

            // 点了行内播放钮才出现的进度条（可拖拽跳播 + 当前/总时长，全程无 60s 上限）
            com.airec.bledemo.ui.pending.PreviewTrack(audio)

            // tri-actions: 绑定顾客（弹性占满） + 申请删除（下划线文字链）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PrimaryButton(
                    text = "绑定顾客",
                    onClick = { onBindCustomer(rec.id) },
                    icon = MeiliIcons.Link,
                    size = MeiliButtonSize.Small,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (busy) "处理中…" else if (freeDelete) "删除" else "申请删除",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.Bold,
                        textDecoration = TextDecoration.Underline,
                    ),
                    color = MeiliPalette.Ink3,
                    modifier = Modifier
                        .clickable(enabled = !busy, onClick = onRequestDelete)
                        .padding(vertical = 6.dp),
                )
            }

            // 免审批提示：本段不足 5 分钟，点「删除」可直接删、无需审批。提前告知用户。
            if (freeDelete && !rejected) {
                Text(
                    text = "不足 5 分钟，删除可直接生效、无需审批",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal),
                    color = MeiliPalette.Ink3,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ProcessingCard(canRetry: Boolean, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    MeiliCard(modifier = modifier, tight = true) {
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
    MeiliCard(modifier = modifier, tight = true) {
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

// ─────────────────────────── 试听 ───────────────────────────

/**
 * 紧凑圆形试听钮（对应原型 .miniplay）：用 [MediaPlayer] 流式播放全程（无 60s 上限），
 * 播放态经 [onPlayingChange] 上报给屏（自动轮询据此跳过）。同步中占位无 audio_url 则取 URL。
 * 还原 PendingScreen 的 PreviewAudio 播放逻辑，但 UI 收成一个圆钮（试听条改放整段卡内的紧凑形态）。
 */
@Composable
private fun MiniPlay(
    recordingId: Long,
    directUrl: String?,
    onPlayingChange: (Boolean) -> Unit,
    onPreviewToast: (String) -> Unit,
) {
    val repo = remember { ConsultantRepository() }
    val scope = rememberCoroutineScope()
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var loadingUrl by remember { mutableStateOf(false) }

    LaunchedEffect(playing) { onPlayingChange(playing) }
    DisposableEffect(recordingId) {
        onDispose {
            onPlayingChange(false)
            player?.release()
            player = null
        }
    }

    fun startWith(url: String) {
        val mp = MediaPlayer()
        runCatching {
            mp.setDataSource(url)
            mp.setOnPreparedListener {
                it.start()
                playing = true
                onPreviewToast("试听播放中")
            }
            mp.setOnCompletionListener {
                playing = false
            }
            mp.prepareAsync()
            player = mp
        }.onFailure {
            mp.release()
            onPreviewToast("试听失败，请稍后重试")
        }
    }

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Surface(
        onClick = onClick@{
            val existing = player
            if (existing != null) {
                if (playing) {
                    existing.pause(); playing = false
                } else {
                    existing.start(); playing = true; onPreviewToast("试听播放中")
                }
                return@onClick
            }
            val ready = directUrl?.takeIf { it.isNotBlank() }
            if (ready != null) {
                startWith(ready)
                return@onClick
            }
            loadingUrl = true
            scope.launch {
                when (val r = repo.recordingUrl(recordingId)) {
                    is ApiResult.Success -> {
                        loadingUrl = false
                        startWith(r.data)
                    }
                    is ApiResult.Failure -> {
                        loadingUrl = false
                        onPreviewToast(r.message)
                    }
                }
            }
        },
        enabled = !loadingUrl,
        interactionSource = interaction,
        shape = MeiliShapes.Pill,
        color = androidx.compose.ui.graphics.Color.Transparent,
        contentColor = MeiliPalette.White,
        modifier = Modifier
            .size(Dimens.PlayButton)
            .scale(if (pressed) 0.94f else 1f),
    ) {
        Box(
            modifier = Modifier.background(MeiliPalette.PrimaryGradient, MeiliShapes.Pill),
            contentAlignment = Alignment.Center,
        ) {
            if (playing) {
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
}

// ─────────────────────────── 从陪伴笔同步 sheet（复刻 PendingScreen 私有版的紧凑形态） ───────────────────────────

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
                        meta = penMeta(row.file.sizeBytes),
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

// ─────────────────────────── 待整理屏内弹窗（复刻 PendingScreen） ───────────────────────────

/** 「申请删除」原因输入弹窗（原因可空，确认才提交，取消放弃）。 */
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

/** 通用二次确认弹窗（导入条数）。 */
@Composable
private fun ConfirmDialog(
    icon: ImageVector,
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

// ─────────────────────────── 待整理文案工具（复刻 PendingScreen） ───────────────────────────

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
    return "$date$crossTag · $dur · 未绑定"
}

private fun secToLabel(sec: Int): String {
    val m = sec / 60
    val s = sec % 60
    return "%02d:%02d".format(m, s)
}

private fun penDateLabel(recordedAt: String): String =
    recordedAt.take(10).ifBlank { "陪伴笔片段" }

private fun penMeta(sizeBytes: Long): String {
    val mb = sizeBytes / (1024.0 * 1024.0)
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

// ─────────────────────────── 新增 / 补登 弹层（沿用原 ReceptionScreen） ───────────────────────────

@Composable
private fun AddReceptionSheet(
    sheet: AddSheetState,
    onDismiss: () -> Unit,
    onSwitchMode: (AddMode) -> Unit,
    onQueryChange: (String) -> Unit,
    onPickExisting: (Customer) -> Unit,
    onNewNameChange: (String) -> Unit,
    onNewPhoneTailChange: (String) -> Unit,
    onNewMemberCardChange: (String) -> Unit,
    onSubmitNew: () -> Unit,
) {
    val sub = if (sheet.targetDate == ReceptionViewModel.todayStr()) {
        "把已有或新顾客加入今天的接诊列表。陪伴结束后只能绑定到这里的人。"
    } else {
        "补登到 ${sheet.targetDate}。陪伴结束后只能绑定到这里的人。"
    }
    MeiliBottomSheet(
        visible = true,
        onDismiss = onDismiss,
        title = "加入今日接诊",
        subtitle = sub,
    ) {
        SegmentedTwo(
            leftText = "搜已有顾客",
            rightText = "新建顾客",
            leftOn = sheet.mode == AddMode.Existing,
            onLeft = { onSwitchMode(AddMode.Existing) },
            onRight = { onSwitchMode(AddMode.New) },
        )
        Spacer(Modifier.height(Dimens.S4))

        if (sheet.mode == AddMode.Existing) {
            MeiliTextField(
                value = sheet.query,
                onValueChange = onQueryChange,
                placeholder = "搜姓名 / 会员号 / 手机尾号",
                leadingIcon = MeiliIcons.Search,
            )
            Spacer(Modifier.height(Dimens.S3))
            when {
                sheet.searching -> LoadingRow()
                sheet.query.isBlank() -> Text(
                    text = "输入关键词搜本公司顾客",
                    style = MaterialTheme.typography.bodySmall,
                    color = MeiliPalette.Ink3,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                sheet.results.isEmpty() -> Text(
                    text = "没有匹配的顾客，可切到「新建顾客」",
                    style = MaterialTheme.typography.bodySmall,
                    color = MeiliPalette.Ink3,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                else -> LazyColumn(
                    modifier = Modifier.heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    items(sheet.results, key = { it.cid ?: it.hashCode().toLong() }) { c ->
                        SearchResultRow(
                            customer = c,
                            enabled = !sheet.submitting,
                            onClick = { onPickExisting(c) },
                        )
                    }
                }
            }
        } else {
            MeiliTextField(
                value = sheet.newName,
                onValueChange = onNewNameChange,
                placeholder = "顾客姓名（必填）",
                leadingIcon = MeiliIcons.Profile,
            )
            Spacer(Modifier.height(Dimens.S3))
            MeiliTextField(
                value = sheet.newPhoneTail,
                onValueChange = onNewPhoneTailChange,
                placeholder = "手机尾号 4 位（必填）",
                leadingIcon = MeiliIcons.Phone,
                keyboardType = KeyboardType.Number,
            )
            Spacer(Modifier.height(Dimens.S3))
            MeiliTextField(
                value = sheet.newMemberCard,
                onValueChange = onNewMemberCardChange,
                placeholder = "会员号（可留空，自动生成）",
                leadingIcon = MeiliIcons.Star,
            )
            Spacer(Modifier.height(Dimens.S4))
            PrimaryButton(
                text = if (sheet.submitting) "提交中…" else "新建并加入",
                onClick = onSubmitNew,
                icon = MeiliIcons.Add,
                enabled = !sheet.submitting,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        sheet.errorMsg?.let {
            Spacer(Modifier.height(Dimens.S3))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MeiliPalette.RoseText,
            )
        }
        Spacer(Modifier.height(Dimens.S2))
    }
}

@Composable
private fun SearchResultRow(
    customer: Customer,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val name = customer.name?.takeIf { it.isNotBlank() } ?: "未知顾客"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Avatar(name = name, sage = false)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                color = MeiliPalette.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = buildList {
                customer.phoneTail?.takeIf { it.isNotBlank() }?.let { add("尾号$it") }
                customer.memberCard?.takeIf { it.isNotBlank() }?.let { add(it) }
            }.joinToString(" · ").ifEmpty { "无尾号 / 会员号" }
            Text(
                text = meta,
                style = MaterialTheme.typography.bodySmall,
                color = MeiliPalette.Ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Icon(
            MeiliIcons.Add,
            contentDescription = null,
            tint = MeiliPalette.Clay,
            modifier = Modifier.size(Dimens.IconSm),
        )
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MeiliPalette.LineSoft),
    )
}

// ─────────────────────────── 删除确认 弹层（沿用原 ReceptionScreen） ───────────────────────────

@Composable
private fun ConfirmRemoveSheet(
    item: TodayReception,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val name = item.name?.takeIf { it.isNotBlank() } ?: "该顾客"
    MeiliBottomSheet(
        visible = true,
        onDismiss = onCancel,
        title = "移出今日接诊",
        subtitle = "把「$name」移出 ${item.serviceDate ?: "今日"} 的接诊列表？若已开启过陪伴并绑定，将无法移除。",
    ) {
        PrimaryButton(
            text = "确认移出",
            onClick = onConfirm,
            icon = MeiliIcons.Trash,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Dimens.S2))
        GhostButton(
            text = "取消",
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Dimens.S2))
    }
}

// ─────────────────────────── 改接诊日期 弹层（沿用原 ReceptionScreen） ───────────────────────────

@Composable
private fun EditDateSheet(
    state: EditDateState,
    onDismiss: () -> Unit,
    onPickDate: () -> Unit,
    onConfirm: () -> Unit,
) {
    val name = state.item.name?.takeIf { it.isNotBlank() } ?: "该顾客"
    MeiliBottomSheet(
        visible = true,
        onDismiss = onDismiss,
        title = "改接诊日期",
        subtitle = "把「$name」的接诊改到另一天（限原日期前 7 天内、不超过今天）。",
    ) {
        Text(
            text = "原日期：${state.item.serviceDate ?: "—"}",
            style = MaterialTheme.typography.bodySmall,
            color = MeiliPalette.Ink3,
        )
        Spacer(Modifier.height(Dimens.S3))

        Text(
            text = "改到",
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.sp),
            color = MeiliPalette.Ink2,
        )
        Spacer(Modifier.height(6.dp))
        Surface(
            onClick = { if (!state.submitting) onPickDate() },
            modifier = Modifier.fillMaxWidth(),
            shape = MeiliShapes.Sm,
            color = MeiliPalette.SurfaceSoft,
            contentColor = MeiliPalette.Ink,
            border = BorderStroke(Dimens.BorderThin, MeiliPalette.Line),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 15.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Icon(MeiliIcons.Reception, contentDescription = null, modifier = Modifier.size(Dimens.IconSm), tint = MeiliPalette.Ink3)
                Text(
                    text = state.target,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                    color = MeiliPalette.Ink,
                    modifier = Modifier.weight(1f),
                )
                Icon(MeiliIcons.ChevDown, contentDescription = null, modifier = Modifier.size(Dimens.IconSm), tint = MeiliPalette.Ink4)
            }
        }

        if (state.recordingCount > 0) {
            Spacer(Modifier.height(Dimens.S3))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MeiliShapes.Md,
                color = MeiliPalette.HoneySoft,
                contentColor = MeiliPalette.HoneyText,
                border = BorderStroke(Dimens.BorderThin, MeiliPalette.Honey),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 15.dp, vertical = 13.dp),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(MeiliIcons.Warn, contentDescription = null, modifier = Modifier.size(Dimens.IconSm))
                    Text(
                        text = "已绑 ${state.recordingCount} 段陪伴，改日期后会自动解绑、退回待整理。",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MeiliPalette.HoneyText,
                    )
                }
            }
        }

        state.errorMsg?.let {
            Spacer(Modifier.height(Dimens.S3))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MeiliPalette.RoseText,
            )
        }

        Spacer(Modifier.height(Dimens.S4))
        PrimaryButton(
            text = if (state.submitting) "修改中…" else "确认修改",
            onClick = onConfirm,
            icon = MeiliIcons.Check,
            enabled = !state.submitting,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Dimens.S2))
        GhostButton(
            text = "取消",
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Dimens.S2))
    }
}

// ─────────────────────────── 局部小组件 ───────────────────────────

@Composable
private fun SegmentedTwo(
    leftText: String,
    rightText: String,
    leftOn: Boolean,
    onLeft: () -> Unit,
    onRight: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MeiliPalette.SurfaceSoft, MeiliShapes.Pill)
            .border(BorderStroke(Dimens.BorderThin, MeiliPalette.Line), MeiliShapes.Pill)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SegItem(leftText, leftOn, onLeft, Modifier.weight(1f))
        SegItem(rightText, !leftOn, onRight, Modifier.weight(1f))
    }
}

@Composable
private fun SegItem(text: String, on: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = MeiliShapes.Pill,
        color = if (on) MeiliPalette.Surface else androidx.compose.ui.graphics.Color.Transparent,
        contentColor = if (on) MeiliPalette.ClayDeep else MeiliPalette.Ink2,
        shadowElevation = if (on) Dimens.Elev1 else 0.dp,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.sp,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun MeiliTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    leadingIcon: ImageVector? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = {
            Text(placeholder, style = LocalTextStyle.current, color = MeiliPalette.Ink3)
        },
        leadingIcon = leadingIcon?.let {
            { Icon(it, contentDescription = null, modifier = Modifier.size(Dimens.IconSm), tint = MeiliPalette.Ink3) }
        },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge,
        shape = MeiliShapes.Sm,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MeiliPalette.Clay,
            unfocusedBorderColor = MeiliPalette.Line,
            focusedContainerColor = MeiliPalette.White,
            unfocusedContainerColor = MeiliPalette.SurfaceSoft,
            cursorColor = MeiliPalette.Clay,
            focusedTextColor = MeiliPalette.Ink,
            unfocusedTextColor = MeiliPalette.Ink,
        ),
    )
}

@Composable
private fun LoadingRow() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            color = MeiliPalette.Clay,
            strokeWidth = 2.5.dp,
            modifier = Modifier.size(28.dp),
        )
    }
}

@Composable
private fun EmptyState(icon: ImageVector, title: String, sub: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp, horizontal = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(58.dp)
                .background(MeiliPalette.SurfaceSoft, MeiliShapes.Lg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MeiliPalette.Ink4, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
            color = MeiliPalette.Ink2,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = sub,
            style = MaterialTheme.typography.bodySmall,
            color = MeiliPalette.Ink3,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** 待整理空态（区块内嵌，比整屏空态更紧凑）。 */
@Composable
private fun PendingEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 30.dp, horizontal = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(MeiliPalette.SurfaceSoft, MeiliShapes.Md),
            contentAlignment = Alignment.Center,
        ) {
            Icon(MeiliIcons.Tidy, contentDescription = null, tint = MeiliPalette.Ink4, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = "没有待整理的陪伴",
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = MeiliPalette.Ink2,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "陪伴结束后会出现在这里，绑定顾客即可进入分析。也可「从陪伴笔同步」机身片段。",
            style = MaterialTheme.typography.bodySmall,
            color = MeiliPalette.Ink3,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun ToastBar(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MeiliShapes.Sm,
        color = MeiliPalette.InkSurface,
        contentColor = MeiliPalette.OnInk,
        shadowElevation = Dimens.Elev2,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MeiliPalette.OnInk,
            modifier = Modifier.padding(horizontal = 17.dp, vertical = 14.dp),
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 760)
@Composable
private fun ReceptionScreenPreview() {
    MeiliTheme { ReceptionScreen() }
}
