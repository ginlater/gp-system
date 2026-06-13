package com.airec.bledemo.ui.bind

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.airec.bledemo.designsystem.Dimens
import com.airec.bledemo.designsystem.MeiliIcons
import com.airec.bledemo.designsystem.MeiliPalette
import com.airec.bledemo.designsystem.MeiliShapes
import com.airec.bledemo.designsystem.MeiliTheme
import com.airec.bledemo.designsystem.components.GhostButton
import com.airec.bledemo.designsystem.components.MeiliBottomSheet
import com.airec.bledemo.designsystem.components.MeiliButtonSize
import com.airec.bledemo.designsystem.components.MeiliTopBar
import com.airec.bledemo.designsystem.components.PrimaryButton

private typealias Pick = BindCustomerViewModel.Pick
private typealias Mode = BindCustomerViewModel.Mode
private typealias Tab = BindCustomerViewModel.Tab

/**
 * 绑定 / 换绑顾客（web consultant.html #addMask/#bindMask + #rebindMask + #unbindMask 合并到一屏，
 * SPEC §4.4 / warm_2 #m-bind + #m-rebind）。
 *
 * 本屏只拿到 recordingId（nav 限定），首屏自我探测片段状态（[BindCustomerViewModel.boot]）：
 *  - 未绑定 → 绑定流程：搜索/新增顾客 → 绑定；可对该未归档片段「申请删除 / 撤回删除申请」。
 *  - 已绑定 → 换绑流程：换绑到别的顾客（directRebind，带换绑理由）/「退回未归档」（unbind，理由必填）。
 *
 * 选人来源对齐 web rbLoadCandidates：默认当日接诊名单（带已绑段数/过滤锁定），输入时叠加候选搜索。
 * 新增顾客走「新增并选中 →（可）删除误建」，不直接绑定，给一次反悔机会。
 *
 * @param recordingId 待绑定/换绑的片段标识（由路由参数传入，保持签名）
 * @param onBack 返回上一页
 * @param onBound 绑定/换绑完成后回调（携带 sessionId 跳会话预览）
 * @param modifier 由 AppScaffold 传入
 */
@Composable
fun BindCustomerScreen(
    recordingId: Long,
    onBack: () -> Unit = {},
    onBound: (sessionId: Long) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: BindCustomerViewModel = viewModel(
        factory = BindCustomerViewModel.factory(recordingId),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val rebinding = state.mode == Mode.Rebinding

    // 绑定/换绑成功（有 session）→ 回调跳预览；退回/删除完成（无 session）→ 直接返回。
    LaunchedEffect(state.boundSessionId) { state.boundSessionId?.let { onBound(it) } }
    LaunchedEffect(state.finished) { if (state.finished) onBack() }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.ScreenH)
                .padding(bottom = Dimens.BottomNavInset),
        ) {
            MeiliTopBar(
                title = if (rebinding) "换绑这段陪伴" else "绑定顾客",
                subtitle = if (rebinding) {
                    "只移动这一段陪伴到别的顾客，其余不受影响；新旧接诊包都会重新生成"
                } else {
                    "把这段陪伴绑定到接诊顾客，绑定后才会进入分析"
                },
                onBack = onBack,
            )

            Spacer(Modifier.height(4.dp))

            when {
                state.booting -> CenterHint("正在载入这段陪伴…")
                state.deletePending -> DeletePendingPane(
                    rejectReason = null,
                    submitting = state.submitting,
                    onWithdraw = viewModel::withdrawDeleteRequest,
                )
                else -> BindBody(
                    state = state,
                    rebinding = rebinding,
                    viewModel = viewModel,
                    onBack = onBack,
                )
            }
        }

        // 顶部轻提示（成功/失败）
        val banner = state.error ?: state.toast
        if (banner != null) {
            TopNotice(
                text = banner,
                danger = state.error != null,
                modifier = Modifier.align(Alignment.TopCenter),
            )
            LaunchedEffect(banner) {
                kotlinx.coroutines.delay(2200)
                if (state.error != null) viewModel.consumeError() else viewModel.consumeToast()
            }
        }
    }

    // 确认绑定 / 换绑弹窗
    state.pendingBind?.let { pb ->
        ConfirmBindDialog(
            rebinding = rebinding,
            name = pb.name,
            needsBackfill = pb.needsBackfill,
            serviceDate = state.serviceDate,
            submitting = state.submitting,
            onConfirm = viewModel::confirmBind,
            onDismiss = viewModel::dismissConfirm,
        )
    }

    // 退回未归档 sheet（理由必填）
    UnbindSheet(
        visible = state.unbindSheet,
        recLabel = state.recLabel,
        reason = state.unbindReason,
        submitting = state.submitting,
        onReasonChange = viewModel::onUnbindReasonChange,
        onConfirm = viewModel::confirmUnbind,
        onDismiss = viewModel::dismissUnbind,
    )

    // 申请删除 sheet（理由可选）
    DeleteRequestSheet(
        visible = state.deleteSheet,
        recLabel = state.recLabel,
        reason = state.deleteReason,
        submitting = state.submitting,
        onReasonChange = viewModel::onDeleteReasonChange,
        onConfirm = viewModel::confirmDeleteRequest,
        onDismiss = viewModel::dismissDeleteRequest,
    )
}

// ───────────────────────────── 主体 ─────────────────────────────

@Composable
private fun BindBody(
    state: BindCustomerViewModel.UiState,
    rebinding: Boolean,
    viewModel: BindCustomerViewModel,
    onBack: () -> Unit,
) {
    // 顶部说明 banner
    if (rebinding) {
        RebindNoticeBanner()
    } else {
        ServiceDateBanner(serviceDate = state.serviceDate)
    }

    Spacer(Modifier.height(14.dp))

    BindSegmented(tab = state.tab, rebinding = rebinding, onSelect = viewModel::selectTab)

    Spacer(Modifier.height(16.dp))

    when (state.tab) {
        Tab.Existing -> ExistingPane(
            rebinding = rebinding,
            query = state.query,
            onQueryChange = viewModel::onQueryChange,
            searching = state.searching,
            picks = state.picks,
            submitting = state.submitting,
            onPick = viewModel::onPick,
            onRemoveMisCreated = viewModel::removeMisCreated,
            onGoNew = { viewModel.selectTab(Tab.New) },
        )

        Tab.New -> NewCustomerPane(
            rebinding = rebinding,
            name = state.newName,
            phoneTail = state.newPhoneTail,
            canSubmit = state.canSubmitNew,
            submitting = state.submitting,
            onNameChange = viewModel::onNewNameChange,
            onPhoneTailChange = viewModel::onNewPhoneTailChange,
            onSubmit = viewModel::onSubmitNew,
            onCancel = { viewModel.selectTab(Tab.Existing) },
        )
    }

    // 换绑理由（可选）
    if (rebinding) {
        Spacer(Modifier.height(18.dp))
        ReasonField(
            label = "换绑理由（可选）",
            value = state.rebindReason,
            onValueChange = viewModel::onRebindReasonChange,
            placeholder = "可选：说明换绑原因，便于管理员复盘",
        )
    }

    // 危险操作区：换绑模式=退回未归档；绑定模式=申请删除
    Spacer(Modifier.height(20.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(MeiliPalette.LineSoft))
    Spacer(Modifier.height(14.dp))
    if (rebinding) {
        DangerAction(
            title = "不想分析这一段？",
            desc = "退回未归档片段（从当前顾客解绑，回到未归档池可再申请删除；若已分析将作废）。",
            buttonText = "退回未归档",
            icon = MeiliIcons.Unbind,
            enabled = !state.submitting,
            onClick = viewModel::openUnbind,
        )
    } else {
        DangerAction(
            title = "这段是误录 / 不需要？",
            desc = "可申请删除这段未归档片段，提交后等待管理员审批；审批前可随时撤回。",
            buttonText = "申请删除",
            icon = MeiliIcons.Trash,
            enabled = !state.submitting,
            onClick = viewModel::openDeleteRequest,
        )
    }
}

// ───────────────────────────── 提示 banner ─────────────────────────────

@Composable
private fun ServiceDateBanner(serviceDate: String?) {
    val text = if (serviceDate.isNullOrBlank()) {
        "只能绑定到该片段当天的接诊顾客。列表里没有，可切到「新增顾客」当天新建并补登。"
    } else {
        "这段陪伴是 $serviceDate 的，只能绑定到当天的接诊顾客。其余顾客选中后会自动补登当天接诊。"
    }
    WarnBanner(text)
}

@Composable
private fun RebindNoticeBanner() {
    WarnBanner(
        "只移动这一段陪伴到该顾客，该顾客其他陪伴不受影响（不是换整个顾客）。换绑后新旧接诊包都会重新生成，直接生效、无需审批，所有操作管理员可见。",
    )
}

@Composable
private fun WarnBanner(text: String) {
    Surface(
        shape = MeiliShapes.Md,
        color = MeiliPalette.HoneySoft,
        contentColor = MeiliPalette.HoneyText,
        border = BorderStroke(Dimens.BorderThin, MeiliPalette.Honey.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 15.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(MeiliIcons.Warn, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.5f.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 19.sp,
                ),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ───────────────────────────── 分段切换 ─────────────────────────────

@Composable
private fun BindSegmented(
    tab: Tab,
    rebinding: Boolean,
    onSelect: (Tab) -> Unit,
) {
    Surface(
        shape = MeiliShapes.Pill,
        color = MeiliPalette.SurfaceSoft,
        border = BorderStroke(Dimens.BorderThin, MeiliPalette.Line),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SegItem(
                text = "选已有顾客",
                selected = tab == Tab.Existing,
                onClick = { onSelect(Tab.Existing) },
                modifier = Modifier.weight(1f),
            )
            SegItem(
                text = if (rebinding) "＋ 新增当日顾客" else "＋ 新增顾客",
                selected = tab == Tab.New,
                onClick = { onSelect(Tab.New) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SegItem(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = MeiliShapes.Pill,
        color = if (selected) MeiliPalette.Surface else Color.Transparent,
        contentColor = if (selected) MeiliPalette.ClayDeep else MeiliPalette.Ink2,
        shadowElevation = if (selected) Dimens.Elev1 else 0.dp,
        modifier = modifier,
    ) {
        Box(modifier = Modifier.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ───────────────────────────── 选已有顾客 ─────────────────────────────

@Composable
private fun ExistingPane(
    rebinding: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    searching: Boolean,
    picks: List<Pick>,
    submitting: Boolean,
    onPick: (Pick) -> Unit,
    onRemoveMisCreated: (Pick) -> Unit,
    onGoNew: () -> Unit,
) {
    SearchField(value = query, onValueChange = onQueryChange)

    Spacer(Modifier.height(11.dp))

    Text(
        text = "仅能" + (if (rebinding) "换绑" else "绑定") +
            "到您本人接待过的顾客。标「当日」的已在该陪伴当天接诊；其余选中后会自动补登当天接诊。",
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5f.sp, lineHeight = 17.sp),
        color = MeiliPalette.Ink3,
    )

    Spacer(Modifier.height(8.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(MeiliPalette.LineSoft))
    Spacer(Modifier.height(4.dp))

    when {
        searching && picks.isEmpty() -> CenterHint("正在搜索…")
        picks.isEmpty() -> EmptyCandidates(onGoNew = onGoNew)
        else -> picks.forEach { p ->
            CandidateRow(
                pick = p,
                rebinding = rebinding,
                enabled = !submitting,
                onBind = { onPick(p) },
                onRemove = { onRemoveMisCreated(p) },
            )
        }
    }
}

@Composable
private fun CandidateRow(
    pick: Pick,
    rebinding: Boolean,
    enabled: Boolean,
    onBind: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(name = pick.name)
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text(
                    text = pick.name.ifBlank { "未命名顾客" },
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.ExtraBold),
                    color = MeiliPalette.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (pick.inDay) DayTag()
                if (pick.locked) LockedTag()
            }
            Text(
                text = candidateMeta(pick),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5f.sp),
                color = MeiliPalette.Ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        // 本次误建可删除（名下有片段时后端会拒绝）
        if (pick.justCreated) {
            GhostButton(text = "删除", onClick = onRemove, enabled = enabled, size = MeiliButtonSize.Xs)
        }
        PrimaryButton(
            text = if (rebinding) "换到这里" else "绑定到这里",
            onClick = onBind,
            enabled = enabled && !pick.locked,
            size = MeiliButtonSize.Xs,
        )
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(MeiliPalette.LineSoft))
}

/** 候选副信息：尾号 / 会员卡 / 已绑段数；in_day=false 时提示将补登。 */
private fun candidateMeta(p: Pick): String {
    val parts = buildList {
        p.phoneTail?.takeIf { it.isNotBlank() }?.let { add("尾号$it") }
        p.memberCard?.takeIf { it.isNotBlank() }?.let { add(it) }
        if (p.boundCount > 0) add("已绑 ${p.boundCount} 段")
    }
    val base = parts.joinToString(" · ")
    return when {
        p.locked -> if (base.isNotBlank()) "$base · 接诊包已锁定" else "接诊包已锁定，无法加入"
        p.inDay && base.isNotBlank() -> base
        p.inDay -> "已在当日接诊"
        base.isNotBlank() -> "$base · 选中后将补登当天接诊"
        else -> "选中后将补登当天接诊"
    }
}

@Composable
private fun EmptyCandidates(onGoNew: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(shape = MeiliShapes.Md, color = MeiliPalette.SurfaceSoft, modifier = Modifier.size(58.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(MeiliIcons.Search, contentDescription = null, tint = MeiliPalette.Ink4, modifier = Modifier.size(30.dp))
            }
        }
        Text(
            text = "没有匹配的顾客",
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
            color = MeiliPalette.Ink2,
        )
        Text(
            text = "换个姓名/尾号/卡号再试，或如是新顾客请切到「＋ 新增顾客」当天新建。",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp),
            color = MeiliPalette.Ink3,
            modifier = Modifier.padding(horizontal = 18.dp),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        GhostButton(text = "去新增顾客", onClick = onGoNew, icon = MeiliIcons.Add, size = MeiliButtonSize.Small)
    }
}

// ───────────────────────────── 新增顾客 ─────────────────────────────

@Composable
private fun NewCustomerPane(
    rebinding: Boolean,
    name: String,
    phoneTail: String,
    canSubmit: Boolean,
    submitting: Boolean,
    onNameChange: (String) -> Unit,
    onPhoneTailChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(15.dp)) {
        FormField(label = "顾客姓名", required = true) {
            PlainInput(value = name, onValueChange = onNameChange, placeholder = "如：李雪", keyboardType = KeyboardType.Text)
        }
        FormField(label = "手机尾号（4 位，可选）", required = false) {
            PlainInput(value = phoneTail, onValueChange = onPhoneTailChange, placeholder = "如：8821", keyboardType = KeyboardType.Number)
        }
        Text(
            text = "新增即为该陪伴当天补登一次接诊，并自动选中这位顾客；写错了可在列表里点「删除」撤回（名下已有片段则无法删除）。",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5f.sp, lineHeight = 17.sp),
            color = MeiliPalette.Ink3,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            PrimaryButton(
                text = if (submitting) "处理中…" else "新增并选中",
                onClick = onSubmit,
                enabled = canSubmit,
                modifier = Modifier.weight(1f),
            )
            GhostButton(text = "返回选择", onClick = onCancel, enabled = !submitting)
        }
    }
}

@Composable
private fun FormField(label: String, required: Boolean, content: @Composable () -> Unit) {
    Column {
        Row {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.5f.sp, fontWeight = FontWeight.Bold),
                color = MeiliPalette.Ink2,
            )
            if (required) {
                Text(
                    text = " *",
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.5f.sp, fontWeight = FontWeight.Bold),
                    color = MeiliPalette.Clay,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        content()
    }
}

// ───────────────────────────── 危险操作区（退回 / 申请删除入口）─────────────────────────────

@Composable
private fun DangerAction(
    title: String,
    desc: String,
    buttonText: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = MeiliShapes.Md,
        color = MeiliPalette.RoseSoft.copy(alpha = 0.5f),
        border = BorderStroke(Dimens.BorderThin, MeiliPalette.RoseLine),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.5f.sp, fontWeight = FontWeight.Bold),
                color = MeiliPalette.RoseText,
            )
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp),
                color = MeiliPalette.Ink2,
            )
            GhostButton(text = buttonText, onClick = onClick, icon = icon, enabled = enabled, size = MeiliButtonSize.Small)
        }
    }
}

// ───────────────────────────── 删除审批中（撤回） ─────────────────────────────

@Composable
private fun DeletePendingPane(
    rejectReason: String?,
    submitting: Boolean,
    onWithdraw: () -> Unit,
) {
    Spacer(Modifier.height(10.dp))
    Surface(
        shape = MeiliShapes.Lg,
        color = MeiliPalette.Surface,
        border = BorderStroke(Dimens.BorderThin, MeiliPalette.LineSoft),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Icon(MeiliIcons.Clock, contentDescription = null, tint = MeiliPalette.ClayDeep, modifier = Modifier.size(20.dp))
                Text(
                    text = "删除审批中",
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp, fontWeight = FontWeight.ExtraBold),
                    color = MeiliPalette.Ink,
                )
            }
            Text(
                text = "这段陪伴已提交删除申请，正在等待管理员审批。需先撤回申请，才能继续绑定 / 换绑等操作。",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 20.sp),
                color = MeiliPalette.Ink2,
            )
            rejectReason?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = "管理员备注：$it",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp),
                    color = MeiliPalette.RoseText,
                )
            }
            PrimaryButton(
                text = if (submitting) "撤回中…" else "撤回删除申请",
                onClick = onWithdraw,
                enabled = !submitting,
                icon = MeiliIcons.Refresh,
                size = MeiliButtonSize.Small,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ───────────────────────────── 退回未归档 sheet ─────────────────────────────

@Composable
private fun UnbindSheet(
    visible: Boolean,
    recLabel: String?,
    reason: String,
    submitting: Boolean,
    onReasonChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    MeiliBottomSheet(
        visible = visible,
        onDismiss = onDismiss,
        title = "退回未归档片段",
        subtitle = "解绑后这段陪伴会回到未归档池，可在那里申请删除；若已分析将作废。所有操作管理员可见。",
    ) {
        recLabel?.let { RecInfoLine(it) }
        ReasonField(
            label = "退回理由 *",
            value = reason,
            onValueChange = onReasonChange,
            placeholder = "请简要说明（如：绑错顾客、想删除等）",
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            GhostButton(text = "取消", onClick = onDismiss, enabled = !submitting, modifier = Modifier.weight(1f))
            PrimaryButton(
                text = if (submitting) "退回中…" else "确认退回未归档",
                onClick = onConfirm,
                enabled = !submitting,
                icon = MeiliIcons.Unbind,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ───────────────────────────── 申请删除 sheet ─────────────────────────────

@Composable
private fun DeleteRequestSheet(
    visible: Boolean,
    recLabel: String?,
    reason: String,
    submitting: Boolean,
    onReasonChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    MeiliBottomSheet(
        visible = visible,
        onDismiss = onDismiss,
        title = "申请删除这段陪伴",
        subtitle = "提交后等待管理员审批，审批前可随时撤回；删除不可恢复。",
    ) {
        recLabel?.let { RecInfoLine(it) }
        ReasonField(
            label = "删除原因（可选）",
            value = reason,
            onValueChange = onReasonChange,
            placeholder = "可选：说明为何要删除，便于管理员审批",
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            GhostButton(text = "取消", onClick = onDismiss, enabled = !submitting, modifier = Modifier.weight(1f))
            PrimaryButton(
                text = if (submitting) "提交中…" else "提交删除申请",
                onClick = onConfirm,
                enabled = !submitting,
                icon = MeiliIcons.Trash,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun RecInfoLine(label: String) {
    Surface(
        shape = MeiliShapes.Sm,
        color = MeiliPalette.SurfaceSoft,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "陪伴片段：$label",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            color = MeiliPalette.Ink2,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
    Spacer(Modifier.height(12.dp))
}

// ───────────────────────────── 输入框 ─────────────────────────────

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
        colors = fieldColors(),
    )
}

@Composable
private fun PlainInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = {
            Text(placeholder, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.5f.sp), color = MeiliPalette.Ink3)
        },
        singleLine = true,
        shape = MeiliShapes.Sm,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.5f.sp),
        colors = fieldColors(),
    )
}

/** 多行理由输入（换绑/退回/删除共用）。 */
@Composable
private fun ReasonField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.5f.sp, fontWeight = FontWeight.Bold),
            color = MeiliPalette.Ink2,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
            placeholder = {
                Text(placeholder, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5f.sp), color = MeiliPalette.Ink3)
            },
            shape = MeiliShapes.Sm,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5f.sp),
            colors = fieldColors(),
        )
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MeiliPalette.White,
    unfocusedContainerColor = MeiliPalette.SurfaceSoft,
    focusedBorderColor = MeiliPalette.Clay,
    unfocusedBorderColor = MeiliPalette.Line,
    cursorColor = MeiliPalette.Clay,
    focusedTextColor = MeiliPalette.Ink,
    unfocusedTextColor = MeiliPalette.Ink,
)

// ───────────────────────────── 确认弹窗 ─────────────────────────────

@Composable
private fun ConfirmBindDialog(
    rebinding: Boolean,
    name: String,
    needsBackfill: Boolean,
    serviceDate: String?,
    submitting: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val dateText = serviceDate?.let { "（$it）" } ?: ""
    val body = buildString {
        if (rebinding) {
            append("只移动这一段陪伴到「$name」（该顾客其他陪伴不受影响），新旧接诊包都会重新生成。")
            if (needsBackfill) append("\n\n该顾客尚未在当天接诊，换绑会自动为其补登一次当天$dateText 接诊。")
            append("\n\n换绑直接生效、无需审批。确认吗？")
        } else {
            append("确认把这段陪伴绑定到「$name」吗？")
            if (needsBackfill) append("\n\n该顾客尚未在当天接诊，绑定会自动为其补登一次当天$dateText 接诊。")
            append("\n\n绑定后即进入会话预览，可在那里确认并开始分析。")
        }
    }
    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        containerColor = MeiliPalette.Surface,
        titleContentColor = MeiliPalette.Ink,
        textContentColor = MeiliPalette.Ink2,
        shape = MeiliShapes.Lg,
        icon = {
            Icon(MeiliIcons.Link, contentDescription = null, tint = MeiliPalette.ClayDeep, modifier = Modifier.size(26.dp))
        },
        title = {
            Text(
                if (rebinding) "换绑到 $name" else "绑定到 $name",
                style = MaterialTheme.typography.headlineSmall,
                color = MeiliPalette.Ink,
            )
        },
        text = {
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5f.sp, lineHeight = 20.sp),
                color = MeiliPalette.Ink2,
            )
        },
        confirmButton = {
            PrimaryButton(
                text = if (submitting) (if (rebinding) "换绑中…" else "绑定中…") else (if (rebinding) "确认换绑（仅这段）" else "确认绑定"),
                onClick = onConfirm,
                enabled = !submitting,
                icon = MeiliIcons.Check,
                size = MeiliButtonSize.Small,
            )
        },
        dismissButton = {
            GhostButton(text = "取消", onClick = onDismiss, enabled = !submitting, size = MeiliButtonSize.Small)
        },
    )
}

// ───────────────────────────── 小部件 ─────────────────────────────

@Composable
private fun Avatar(name: String) {
    Box(
        modifier = Modifier
            .size(Dimens.Avatar)
            .background(brush = MeiliPalette.AvatarGradient, shape = MeiliShapes.Sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.take(1).ifBlank { "客" },
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold),
            color = MeiliPalette.ClayDeep,
        )
    }
}

@Composable
private fun DayTag() {
    Surface(shape = MeiliShapes.Pill, color = MeiliPalette.LeafSoft, contentColor = MeiliPalette.LeafText) {
        Text(
            text = "当日",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.ExtraBold),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun LockedTag() {
    Surface(shape = MeiliShapes.Pill, color = MeiliPalette.SurfaceSoft, contentColor = MeiliPalette.Ink3) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Icon(MeiliIcons.Lock, contentDescription = null, modifier = Modifier.size(11.dp))
            Text(
                text = "已锁定",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.ExtraBold),
            )
        }
    }
}

@Composable
private fun CenterHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 36.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator(color = MeiliPalette.Clay, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            Text(text, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5f.sp), color = MeiliPalette.Ink3)
        }
    }
}

@Composable
private fun TopNotice(text: String, danger: Boolean, modifier: Modifier = Modifier) {
    Surface(
        shape = MeiliShapes.Sm,
        color = if (danger) MeiliPalette.Rose else MeiliPalette.InkSurface,
        contentColor = MeiliPalette.White,
        shadowElevation = 12.dp,
        modifier = modifier
            .padding(top = 10.dp, start = Dimens.ScreenH, end = Dimens.ScreenH)
            .fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 17.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Icon(
                if (danger) MeiliIcons.Warn else MeiliIcons.Check,
                contentDescription = null,
                tint = MeiliPalette.White,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                color = MeiliPalette.White,
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF8F3ED, widthDp = 360, heightDp = 760)
@Composable
private fun BindCustomerScreenPreview() {
    MeiliTheme { BindCustomerScreen(recordingId = 1L) }
}
