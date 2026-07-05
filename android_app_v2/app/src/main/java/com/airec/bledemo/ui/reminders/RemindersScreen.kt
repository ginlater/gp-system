package com.airec.bledemo.ui.reminders

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.airec.bledemo.data.model.Reminder
import com.airec.bledemo.designsystem.Dimens
import com.airec.bledemo.designsystem.MeiliIcons
import com.airec.bledemo.designsystem.MeiliShapes
import com.airec.bledemo.designsystem.MeiliTheme
import com.airec.bledemo.designsystem.components.MeiliButtonSize
import com.airec.bledemo.designsystem.components.MeiliTopBar
import com.airec.bledemo.designsystem.components.PillKind
import com.airec.bledemo.designsystem.components.SectionLabel
import com.airec.bledemo.designsystem.components.StatusPill

/**
 * 提醒屏（SPEC §4.8 / warm_2 #reminders）。
 *
 * 严格照 warm_2 #reminders：
 *  - 顶部返回 + 「提醒」标题（次级页）。
 *  - 「个人提醒」分组：每条 = 等级胶囊（未绑定 L1 / 未查看 L2…）+ 内容文案 + 时间 + 右侧操作链接
 *    （未绑定→去绑定/待整理；报告已生成→查看报告）。
 *  - 「本店待跟进升级项」分组（仅店长有数据，· 仅店长可见）：玫瑰左边线卡，升级胶囊 + 陪伴师胶囊 +
 *    内容 + 时间 + 「已跟进」按钮（[RemindersViewModel.handle] → repo.handleReminder）+ 报告链接。
 *  - 空态：无任何提醒时居中「暂无提醒」。
 *
 * @param onBack 返回上一页（AppScaffold 注入）
 * @param onGoToPending 「去绑定」→ 待整理（默认空实现，需要时由 NavHost 注入）
 * @param onOpenReport 「查看报告 / 报告」→ 分析报告，带 sessionId（默认空实现）
 * @param modifier 由 AppScaffold 传入
 */
@Composable
fun RemindersScreen(
    onBack: () -> Unit = {},
    onGoToPending: () -> Unit = {},
    onOpenReport: (sessionId: Long) -> Unit = {},
    onBindRecording: (recordingId: Long) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: RemindersViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 打开 / 回前台即刷新（对齐 web toggleReminderPanel 展示即拉 + pageshow/visibilitychange）。
    // VM 在 init 已首拉一次；这里跳过首个 onResume 以免重复联网，之后每次回前台都重拉「即时-1」。
    val resumedOnce = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        if (resumedOnce.value) viewModel.load() else resumedOnce.value = true
        onPauseOrDispose { }
    }

    // 轮询刷新提醒列表（对齐 web setInterval(loadReminders, 120000)）。
    // F2：随生命周期暂停——锁屏/切后台不再每 2 分钟 load→markRead（揣兜里被置已读复活的口子堵上）。
    val pollLifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        pollLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            while (true) {
                kotlinx.coroutines.delay(120_000)
                viewModel.load()
            }
        }
    }

    RemindersContent(
        state = state,
        onBack = onBack,
        onRetry = { viewModel.load() },
        onHandle = { viewModel.handle(it) },
        onGoToPending = onGoToPending,
        onOpenReport = onOpenReport,
        onBindRecording = onBindRecording,
        modifier = modifier,
    )
}

@Composable
private fun RemindersContent(
    state: RemindersUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onHandle: (Long) -> Unit,
    onGoToPending: () -> Unit,
    onOpenReport: (Long) -> Unit,
    onBindRecording: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
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
            // .appbar：返回 + 「提醒」标题（次级页）
            MeiliTopBar(title = "提醒", onBack = onBack)

            when {
                state.error != null && state.personal.isEmpty() && state.escalation.isEmpty() ->
                    ErrorState(message = state.error, onRetry = onRetry)

                state.isEmpty -> EmptyState()

                else -> {
                    // ───── 个人提醒 ─────
                    if (state.personal.isNotEmpty()) {
                        SectionLabel(
                            text = "个人提醒",
                            icon = MeiliIcons.Reminder,
                            modifier = Modifier.padding(top = 2.dp, bottom = 11.dp),
                        )
                        state.personal.forEach { rem ->
                            ReminderRow(
                                reminder = rem,
                                onGoToPending = onGoToPending,
                                onOpenReport = onOpenReport,
                                onBindRecording = onBindRecording,
                                modifier = Modifier.padding(bottom = 11.dp),
                            )
                        }
                    }

                    // ───── 本店待跟进升级项（仅店长可见） ─────
                    if (state.showEscalationGroup) {
                        EscalationGroupLabel(
                            count = state.escalation.size,
                            modifier = Modifier.padding(
                                top = if (state.personal.isNotEmpty()) 18.dp else 2.dp,
                                bottom = 11.dp,
                            ),
                        )
                        state.escalation.forEach { rem ->
                            EscalationRow(
                                reminder = rem,
                                handled = state.handledIds.contains(rem.id),
                                handling = state.handling.contains(rem.id),
                                onHandle = { onHandle(rem.id) },
                                onOpenReport = onOpenReport,
                                modifier = Modifier.padding(bottom = 11.dp),
                            )
                        }
                    }

                    // 已经有内容时，错误以底部提示出现（如「已跟进」失败）
                    if (state.error != null) {
                        Spacer(Modifier.padding(top = 2.dp))
                        Text(
                            text = state.error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MeiliTheme.colors.roseText,
                            modifier = Modifier.padding(horizontal = 2.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

/* ───────────────────────── 个人提醒行（.rem） ───────────────────────── */

@Composable
private fun ReminderRow(
    reminder: Reminder,
    onGoToPending: () -> Unit,
    onOpenReport: (Long) -> Unit,
    onBindRecording: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pill = personalPill(reminder)
    val action = personalAction(reminder)

    RemCard(modifier = modifier) {
        // 等级胶囊
        StatusPill(text = pill.text, kind = pill.kind, icon = pill.icon)
        Spacer(Modifier.padding(top = 7.dp))
        // 内容文案 .rt
        Text(
            text = reminder.message ?: "您有一条待处理的提醒",
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 13.5f.sp,
                fontWeight = FontWeight.Bold,
            ),
            color = MeiliTheme.colors.ink,
        )
        Spacer(Modifier.padding(top = 8.dp))
        // .rmeta：时间 + 右侧操作链接
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = reminder.createdAt.orEmpty(),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal),
                color = MeiliTheme.colors.ink3,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (action != null) {
                LinkAction(
                    text = action.label,
                    onClick = {
                        when (action.target) {
                            // 「去绑定」：未绑定提醒带录音 id → 直达该录音的绑定页；拿不到才回退待整理
                            ActionTarget.Pending ->
                                reminder.recordingRef()?.let(onBindRecording) ?: onGoToPending()
                            ActionTarget.Report -> reminder.sessionRef()?.let(onOpenReport)
                        }
                    },
                )
            }
        }
    }
}

/* ─────────────────── 升级项行（.rem + 玫瑰左边线） ─────────────────── */

@Composable
private fun EscalationRow(
    reminder: Reminder,
    handled: Boolean,
    handling: Boolean,
    onHandle: () -> Unit,
    onOpenReport: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val alreadyHandled = handled || reminder.isHandled == true

    RemCard(modifier = modifier, leftAccent = true) {
        // 胶囊行：升级等级 + 陪伴师（web `升级 L${level}` + `advisor_name || 顾问#id`）
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusPill(text = reminder.escalationLevelText(), kind = PillKind.Danger)
            StatusPill(text = "陪伴师 ${reminder.advisorDisplay()}", kind = PillKind.Neutral)
        }
        Spacer(Modifier.padding(top = 7.dp))
        Text(
            text = reminder.message ?: "该客户存在跟进风险，请及时跟进",
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 13.5f.sp,
                fontWeight = FontWeight.Bold,
            ),
            color = MeiliTheme.colors.ink,
        )
        Spacer(Modifier.padding(top = 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = reminder.createdAt.orEmpty(),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal),
                color = MeiliTheme.colors.ink3,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (alreadyHandled) {
                // 已跟进胶囊（按钮点完后变这个，对齐 warm_2 outerHTML 替换）
                StatusPill(text = "已跟进", kind = PillKind.Ok, icon = MeiliIcons.Check)
            } else {
                SageXsButton(
                    text = if (handling) "处理中…" else "已跟进",
                    enabled = !handling,
                    onClick = onHandle,
                )
            }
            // 报告链接
            reminder.sessionRef()?.let { sid ->
                Spacer(Modifier.width(9.dp))
                LinkAction(text = "报告", onClick = { onOpenReport(sid) })
            }
        }
    }
}

/* ───────────────────────── 局部组件 ───────────────────────── */

/** .rem 卡：surface + r-md + 细描边 + sh-1；[leftAccent] 时加玫瑰左边线（升级项）。 */
@Composable
private fun RemCard(
    modifier: Modifier = Modifier,
    leftAccent: Boolean = false,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MeiliShapes.Md,
        color = MeiliTheme.colors.surface,
        contentColor = MeiliTheme.colors.ink,
        border = androidx.compose.foundation.BorderStroke(Dimens.BorderThin, MeiliTheme.colors.lineSoft),
        shadowElevation = Dimens.Elev1,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
        ) {
            if (leftAccent) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(MeiliTheme.colors.rose),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(14.dp, 14.dp, 15.dp, 14.dp),
            ) {
                content()
            }
        }
    }
}

/** .rem .link：陶土色文字 + 右向 chevron，可点。 */
@Composable
private fun LinkAction(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(MeiliShapes.Pill)
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.sp,
            ),
            color = MeiliTheme.colors.clay,
        )
        Icon(
            MeiliIcons.ChevRight,
            contentDescription = null,
            tint = MeiliTheme.colors.clay,
            modifier = Modifier.size(13.dp),
        )
    }
}

/** .btn-sage.btn-xs（升级项「已跟进」按钮）。鼠尾草渐变实心、xs 尺寸。 */
@Composable
private fun SageXsButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MeiliShapes.Pill,
        color = androidx.compose.ui.graphics.Color.Transparent,
        contentColor = MeiliTheme.colors.onInk,
        modifier = Modifier.alpha(if (enabled) 1f else 0.6f),
    ) {
        Box(
            modifier = Modifier
                .background(com.airec.bledemo.designsystem.MeiliPalette.SageGradient, MeiliShapes.Pill)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.5f.sp),
                color = MeiliTheme.colors.onInk,
            )
        }
    }
}

@Composable
private fun EscalationGroupLabel(count: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            MeiliIcons.Trend,
            contentDescription = null,
            tint = MeiliTheme.colors.sageDeep,
            modifier = Modifier.size(15.dp),
        )
        Text(
            text = "本店待跟进升级项（$count）",
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 11.5f.sp,
                fontWeight = FontWeight.ExtraBold,
            ),
            color = MeiliTheme.colors.ink2,
        )
        Text(
            text = "· 仅店长可见",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal),
            color = MeiliTheme.colors.ink3,
        )
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 60.dp, start = 22.dp, end = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(58.dp)
                .clip(MeiliShapes.Lg)
                .background(MeiliTheme.colors.surfaceSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                MeiliIcons.Reminder,
                contentDescription = null,
                tint = MeiliTheme.colors.ink4,
                modifier = Modifier.size(30.dp),
            )
        }
        Text(
            text = "暂无提醒",
            style = MaterialTheme.typography.titleSmall,
            color = MeiliTheme.colors.ink2,
            modifier = Modifier.padding(top = 14.dp),
        )
        Text(
            text = "待绑定的陪伴、待查看的报告会出现在这里",
            style = MaterialTheme.typography.bodySmall,
            color = MeiliTheme.colors.ink3,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 60.dp, start = 22.dp, end = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(58.dp)
                .clip(MeiliShapes.Lg)
                .background(MeiliTheme.colors.surfaceSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                MeiliIcons.Warn,
                contentDescription = null,
                tint = MeiliTheme.colors.ink4,
                modifier = Modifier.size(30.dp),
            )
        }
        Text(
            text = "提醒没能加载出来",
            style = MaterialTheme.typography.titleSmall,
            color = MeiliTheme.colors.ink2,
            modifier = Modifier.padding(top = 14.dp),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MeiliTheme.colors.ink3,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
        com.airec.bledemo.designsystem.components.GhostButton(
            text = "重试",
            onClick = onRetry,
            icon = MeiliIcons.Refresh,
            size = MeiliButtonSize.Small,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

/* ───────────────────────── 字段 → UI 映射 ───────────────────────── */

private enum class ActionTarget { Pending, Report }

private data class RowPill(val text: String, val kind: PillKind, val icon: ImageVector?)

private data class RowAction(val label: String, val target: ActionTarget)

/**
 * 个人提醒胶囊。**严格对齐 web `_renderPersonal`**：
 *  - 文案：`kind === 'unbound'` → 「未绑定」，否则（unviewed）→ 「未查看」；按 [Reminder.kind] 区分（非 refType）。
 *  - 等级：恢复 'L' 前缀 → 「未绑定 L1」（web `${tag} L${it.level}`）。后端 level 为 1/2/3，Moshi 收成 "1"/"2"/"3"。
 *  - 颜色：`level >= 2` → 玫瑰危险（Danger），否则蜜色（Warn）（web `it.level >= 2 ? 红 : 橙`）。
 */
private fun personalPill(r: Reminder): RowPill {
    val isUnbound = r.kind == "unbound"
    val tag = if (isUnbound) "未绑定" else "未查看"
    val text = r.levelNum()?.let { "$tag L$it" } ?: tag
    // L2+（含报告未查看 level=2）= 玫瑰；L1（未绑定首程）= 蜜色。
    val kind = if ((r.levelNum() ?: 1) >= 2) PillKind.Danger else PillKind.Warn
    return RowPill(text = text, kind = kind, icon = null)
}

/**
 * 个人提醒右侧操作：
 *  - web 仅 `ref_type === 'session'` 给「查看报告 →」链接；
 *  - 原生额外便利：未绑定（kind=unbound / ref_type=recording）给「去绑定 →」直达待整理（web 下拉无此入口，原生保留）。
 */
private fun personalAction(r: Reminder): RowAction? = when {
    r.refType == "session" && r.sessionRef() != null ->
        RowAction(label = "查看报告", target = ActionTarget.Report)
    r.kind == "unbound" || r.refType == "recording" ->
        RowAction(label = "去绑定", target = ActionTarget.Pending)
    else -> null
}

/** 取报告 sessionId：升级项后端回填了 session_id；个人报告提醒 ref_type=session 时用 ref_id。 */
private fun Reminder.sessionRef(): Long? =
    sessionId ?: refId?.takeIf { refType == "session" }

/** 取待绑定录音 id：未绑定提醒 ref_type=recording 时用 ref_id（点「去绑定」直达该录音绑定页）。 */
private fun Reminder.recordingRef(): Long? =
    refId?.takeIf { refType == "recording" }

/** level 解析为数字（后端为 INTEGER 1/2/3，Moshi 收成字符串；非数字/缺省 → null）。用于 'L' 前缀与分级配色。 */
private fun Reminder.levelNum(): Int? = level?.trim()?.toIntOrNull()

/** 升级项「升级 Lx」标签文案；level 解析不出时退回 web 默认的 L2。 */
private fun Reminder.escalationLevelText(): String = "升级 L${levelNum() ?: 2}"

/** 升级项陪伴师名：缺省兜底「顾问#<advisor_user_id>」（对齐 web `it.advisor_name || ('顾问#'+id)`）。 */
private fun Reminder.advisorDisplay(): String =
    advisorName?.takeIf { it.isNotBlank() } ?: "顾问#${advisorUserId ?: "?"}"

@Preview(showBackground = true, widthDp = 360, heightDp = 760)
@Composable
private fun RemindersScreenPreview() {
    MeiliTheme {
        RemindersContent(
            state = RemindersUiState(
                personal = listOf(
                    Reminder(
                        id = 1, kind = "unbound", level = "1", refType = "recording",
                        message = "您有一段 06-08 的陪伴还没绑定顾客，请尽快处理",
                        createdAt = "2026-06-08 15:20", scope = "personal",
                    ),
                    Reminder(
                        id = 2, kind = "unviewed", level = "2", refType = "session", refId = 88,
                        message = "李雪 06-08 的报告已生成，请尽快查看",
                        createdAt = "2026-06-08 15:20", scope = "personal",
                    ),
                ),
                escalation = listOf(
                    Reminder(
                        id = 3, level = "2", channel = "escalation", sessionId = 90,
                        advisorName = "张敏", advisorUserId = 12,
                        message = "该客户存在高流失风险，请及时跟进",
                        createdAt = "2026-06-08 15:20", scope = "escalation",
                    ),
                ),
                showEscalationGroup = true,
            ),
            onBack = {}, onRetry = {}, onHandle = {}, onGoToPending = {}, onOpenReport = {}, onBindRecording = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 760, name = "空态")
@Composable
private fun RemindersEmptyPreview() {
    MeiliTheme {
        RemindersContent(
            state = RemindersUiState(loading = false),
            onBack = {}, onRetry = {}, onHandle = {}, onGoToPending = {}, onOpenReport = {}, onBindRecording = {},
        )
    }
}
