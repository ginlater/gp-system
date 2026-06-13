package com.airec.bledemo.ui.archive

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.airec.bledemo.data.model.SessionRow
import com.airec.bledemo.designsystem.Dimens
import com.airec.bledemo.designsystem.MeiliIcons
import com.airec.bledemo.designsystem.MeiliPalette
import com.airec.bledemo.designsystem.MeiliShapes
import com.airec.bledemo.designsystem.MeiliTheme
import com.airec.bledemo.designsystem.components.GhostButton
import com.airec.bledemo.designsystem.components.MeiliButtonSize
import com.airec.bledemo.designsystem.components.MeiliCard
import com.airec.bledemo.designsystem.components.MeiliTopBar
import com.airec.bledemo.designsystem.components.PrimaryButton
import com.airec.bledemo.designsystem.components.PillKind
import com.airec.bledemo.designsystem.components.SectionLabel
import com.airec.bledemo.designsystem.components.StatusPill
import com.airec.bledemo.designsystem.components.TopBarIconButton
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * 「美丽档案」屏 —— 对齐 web index.html(`/` 页) 的接诊/会话列表（顾问视角，整页字段补齐版）。
 *
 * 顾问只看自己名下接诊（服务端按 advisor_name 自动隔离），每行 → 一份分析报告。
 * 单行卡片对齐 web 表格列（去掉顾问/勾选/上传等管理项）：
 *   顾客 · 录音/ASR · 录音时间 · 综合分 · 分析状态(+part 进度) · 最后分析时间 · 点评 · 操作。
 * 顶部：状态筛选 pill（带计数）+ 顾客搜索 + ⏱时间筛选（时间类型/日期/时间从-到 + 重置）；底部服务端分页器。
 *
 * 红线：对外一律「接诊记录 / 分析报告 / 查看报告 / 点评」；「录音/ASR」是顾问端内部口径，
 * 与 web 一致保留。图标仅取 MeiliIcons，无 emoji（✓/状态以彩色 chip/dot 表达）；
 * 360–390px 弹性不溢出（FlowRow 排 pill、长姓名 ellipsis、日期时间两行短串）。
 *
 * @param onOpenReport 进入某次接诊的分析报告（sessionId）；「查看报告 →」入口。
 * @param onOpenSettings 右上角进设置（皮肤/主题）。
 * @param modifier 由 AppScaffold 传入（含系统栏 + 底栏避让 padding）。
 */
@Composable
fun ArchiveScreen(
    onOpenReport: (sessionId: Long) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: ArchiveViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 取消分析二次确认 / 申请删除说明：均为屏内 dialog，按行携带的 session 触发。
    var cancelTarget by remember { mutableStateOf<SessionRow?>(null) }
    var deleteTarget by remember { mutableStateOf<SessionRow?>(null) }

    // 一次性提示（取消分析成功/失败）→ 屏内底部条；显示后回执 onToastShown 防重弹。
    var toast by remember { mutableStateOf<ArchiveToast?>(null) }
    LaunchedEffect(state.toast) {
        state.toast?.let {
            toast = it
            viewModel.onToastShown()
        }
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
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = Dimens.BottomNavInset),
            verticalArrangement = Arrangement.spacedBy(Dimens.CardGap),
        ) {
            item {
                MeiliTopBar(
                    title = "美丽档案",
                    subtitle = "所有接诊记录 · 分析报告",
                    actions = {
                        TopBarIconButton(MeiliIcons.Refresh, onClick = viewModel::refresh)
                        TopBarIconButton(MeiliIcons.Palette, onClick = onOpenSettings)
                    },
                )
            }

            // ── 状态筛选 pill（带计数）──
            item {
                StatusFilterRow(
                    active = state.statusFilter,
                    counts = state.statusCounts,
                    onPick = viewModel::setStatus,
                )
            }

            // ── 顾客搜索 + ⏱时间筛选 ──
            item {
                FilterCard(
                    state = state,
                    onCustomerChange = viewModel::setCustomerQuery,
                    onToggleTime = viewModel::toggleTimeFilter,
                    onTimeType = viewModel::setTimeType,
                    onDate = viewModel::setDate,
                    onTimeFrom = viewModel::setTimeFrom,
                    onTimeTo = viewModel::setTimeTo,
                    onReset = viewModel::resetFilter,
                )
            }

            // ── 列表 / 加载 / 空 / 错误 ──
            when {
                state.loading && state.sessions.isEmpty() -> item {
                    MeiliCard { InlineLoading(text = "正在调取接诊记录…") }
                }
                state.error != null && state.sessions.isEmpty() -> item {
                    MeiliCard {
                        EmptyHint(icon = MeiliIcons.Warn, title = "调取失败", sub = state.error)
                    }
                }
                state.sessions.isEmpty() -> item {
                    MeiliCard {
                        EmptyHint(
                            icon = MeiliIcons.Album,
                            title = "暂无接诊记录",
                            sub = if (hasActiveFilter(state)) {
                                "换个顾客 / 状态 / 时间筛选试试"
                            } else {
                                "完成接诊并绑定顾客后，会在这里看到分析报告"
                            },
                        )
                    }
                }
                else -> {
                    items(state.sessions, key = { it.id ?: it.hashCode().toLong() }) { row ->
                        SessionRowCard(
                            row = row,
                            onOpenReport = onOpenReport,
                            onCancelAnalysis = { cancelTarget = row },
                            onRequestDelete = { deleteTarget = row },
                        )
                    }
                    // ── 服务端分页器 ──
                    item {
                        SessionPager(
                            page = state.page,
                            totalPages = state.totalPages,
                            total = state.total,
                            onPrev = { viewModel.goPage(-1) },
                            onNext = { viewModel.goPage(1) },
                        )
                    }
                }
            }
        }

        // ── 屏内底部一次性提示（取消分析成功/失败）──
        toast?.let { t ->
            ArchiveToastBar(
                toast = t,
                modifier = Modifier.align(Alignment.BottomCenter),
                onDismiss = { toast = null },
            )
        }
    }

    // ── 取消分析二次确认（对齐 web confirm「确定取消分析？…」）──
    cancelTarget?.let { row ->
        CancelAnalysisDialog(
            customer = row.customer,
            onConfirm = {
                row.id?.let(viewModel::cancelAnalysis)
                cancelTarget = null
            },
            onDismiss = { cancelTarget = null },
        )
    }

    // ── 申请删除说明（顾问端按 session 申请删录音；逐条入口在报告内）──
    deleteTarget?.let { row ->
        RequestDeleteDialog(
            row = row,
            onConfirm = {
                row.id?.let { viewModel.requestDelete(it, null) }
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

/** 是否存在任一生效筛选（用于空态文案）。 */
private fun hasActiveFilter(s: ArchiveListState): Boolean =
    s.customerQuery.isNotBlank() || s.statusFilter != null ||
        s.date != null || s.timeFrom != null || s.timeTo != null

// ─────────────────────────── 状态筛选 pill ───────────────────────────

/**
 * 状态筛选条：单行**横向滚动**（不再 FlowRow 折两行，清爽不挤），每个 chip = 状态色点 + 文案 + 计数小徽标。
 * 激活态实心陶土 + 白字（醒目）；未激活描边浅底。横滑由 chip 行自身消费，不与 Pager 切 tab 抢。
 */
@Composable
private fun StatusFilterRow(
    active: String?,
    counts: com.airec.bledemo.data.model.StatusCounts?,
    onPick: (String?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArchiveStatusFilters.forEach { opt ->
            FilterChip(
                label = opt.label,
                count = counts?.of(opt.value),
                dotColor = statusDotColor(opt.value),
                selected = opt.value == active,
                onClick = { onPick(opt.value) },
            )
        }
    }
}

/** 状态色点（与列表状态 pill 同色系）：全部无点；其余按状态着色，做"一眼分色"的精致感。 */
private fun statusDotColor(bucket: String?): Color? = when (bucket) {
    null -> null
    "running" -> MeiliPalette.Clay
    "queued" -> MeiliPalette.Honey
    "done" -> MeiliPalette.LeafText
    "failed", "stuck", "cancelled" -> MeiliPalette.Rose
    else -> MeiliPalette.Ink4   // idle / 其它
}

/** 单个筛选 chip：激活=实心陶土+白字+轻浮起；未激活=浅底描边。计数走半透明/浅底小徽标。 */
@Composable
private fun FilterChip(
    label: String,
    count: Int?,
    dotColor: Color?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MeiliShapes.Pill,
        color = if (selected) MeiliPalette.Clay else MeiliPalette.Surface,
        contentColor = if (selected) MeiliPalette.White else MeiliPalette.Ink2,
        border = if (selected) null else BorderStroke(Dimens.BorderThin, MeiliPalette.Line),
        shadowElevation = if (selected) Dimens.Elev1 else 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(
                start = 12.dp,
                end = if (count != null) 8.dp else 12.dp,
                top = 7.dp,
                bottom = 7.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (dotColor != null) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (selected) MeiliPalette.White.copy(alpha = 0.85f) else dotColor),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 12.5.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                ),
                maxLines = 1,
            )
            if (count != null) {
                Box(
                    modifier = Modifier
                        .clip(MeiliShapes.Pill)
                        .background(
                            if (selected) MeiliPalette.White.copy(alpha = 0.22f) else MeiliPalette.SurfaceSoft,
                        )
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = count.toString(),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = if (selected) MeiliPalette.White else MeiliPalette.Ink3,
                    )
                }
            }
        }
    }
}

// ─────────────────────────── 顾客搜索 + ⏱时间筛选 ───────────────────────────

/** 顾客搜索 + ⏱时间筛选合卡（对齐 index.html 工具栏：顾客 input + ⏱时间 toggle 展开区 + 重置）。 */
@Composable
private fun FilterCard(
    state: ArchiveListState,
    onCustomerChange: (String) -> Unit,
    onToggleTime: () -> Unit,
    onTimeType: (String) -> Unit,
    onDate: (String?) -> Unit,
    onTimeFrom: (String?) -> Unit,
    onTimeTo: (String?) -> Unit,
    onReset: () -> Unit,
) {
    MeiliCard(tight = true) {
        CustomerSearchField(value = state.customerQuery, onValueChange = onCustomerChange)

        Spacer(Modifier.height(10.dp))

        // ⏱时间 toggle + 重置（同一行；窄屏靠 FlowRow 自动换行不溢出）
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TimeToggleChip(open = state.timeFilterOpen, onClick = onToggleTime)
            Spacer(Modifier.weight(1f))
            GhostButton(text = "重置", onClick = onReset, size = MeiliButtonSize.Xs, icon = MeiliIcons.Refresh)
        }

        if (state.timeFilterOpen) {
            Spacer(Modifier.height(12.dp))
            TimeFilterPanel(
                timeType = state.timeType,
                date = state.date,
                timeFrom = state.timeFrom,
                timeTo = state.timeTo,
                onTimeType = onTimeType,
                onDate = onDate,
                onTimeFrom = onTimeFrom,
                onTimeTo = onTimeTo,
            )
        }
    }
}

@Composable
private fun CustomerSearchField(
    value: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = {
            Text(
                "搜索顾客姓名",
                style = MaterialTheme.typography.bodyLarge,
                color = MeiliPalette.Ink3,
            )
        },
        leadingIcon = {
            Icon(
                MeiliIcons.Search,
                contentDescription = null,
                tint = MeiliPalette.Ink3,
                modifier = Modifier.size(Dimens.IconSm),
            )
        },
        shape = MeiliShapes.Sm,
        textStyle = MaterialTheme.typography.bodyLarge,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = {}),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MeiliPalette.White,
            unfocusedContainerColor = MeiliPalette.SurfaceSoft,
            focusedBorderColor = MeiliPalette.Clay,
            unfocusedBorderColor = MeiliPalette.Line,
            cursorColor = MeiliPalette.Clay,
            focusedTextColor = MeiliPalette.Ink,
            unfocusedTextColor = MeiliPalette.Ink,
        ),
    )
}

/** ⏱时间 toggle 胶囊（对齐 index.html #timeToggleBtn；开=陶土实底，关=描边浅底）。 */
@Composable
private fun TimeToggleChip(open: Boolean, onClick: () -> Unit) {
    val bg = if (open) MeiliPalette.ClayTint else MeiliPalette.Surface
    val fg = if (open) MeiliPalette.ClayDeep else MeiliPalette.Ink2
    val border = if (open) MeiliPalette.ClaySoft else MeiliPalette.Line
    Surface(
        onClick = onClick,
        shape = MeiliShapes.Pill,
        color = bg,
        contentColor = fg,
        border = BorderStroke(Dimens.BorderThin, border),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(MeiliIcons.Clock, contentDescription = null, modifier = Modifier.size(15.dp))
            Text(
                "时间筛选",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 12.sp,
                    fontWeight = if (open) FontWeight.Bold else FontWeight.Normal,
                ),
            )
        }
    }
}

/** ⏱时间筛选展开面板：时间类型分段 + 日期 + 时间从–到（对齐 index.html #timeFilterRow）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeFilterPanel(
    timeType: String,
    date: String?,
    timeFrom: String?,
    timeTo: String?,
    onTimeType: (String) -> Unit,
    onDate: (String?) -> Unit,
    onTimeFrom: (String?) -> Unit,
    onTimeTo: (String?) -> Unit,
) {
    // 三个 picker 弹窗的可见性
    var showDate by remember { mutableStateOf(false) }
    var showFrom by remember { mutableStateOf(false) }
    var showTo by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // 时间类型分段：录音时间 / 分析时间
        SectionLabel("时间类型", icon = MeiliIcons.Clock)
        SegmentedPair(
            leftLabel = "录音时间",
            rightLabel = "分析时间",
            leftSelected = timeType != "analysis",
            onLeft = { onTimeType("recording") },
            onRight = { onTimeType("analysis") },
        )

        // 日期 + 时间从–到（FlowRow 窄屏自动换行，不横向溢出）
        FlowRowFields(
            date = date,
            timeFrom = timeFrom,
            timeTo = timeTo,
            onPickDate = { showDate = true },
            onClearDate = { onDate(null) },
            onPickFrom = { showFrom = true },
            onClearFrom = { onTimeFrom(null) },
            onPickTo = { showTo = true },
            onClearTo = { onTimeTo(null) },
        )
    }

    // ── 日期选择器（Material3）──
    if (showDate) {
        val dpState = rememberDatePickerState(initialSelectedDateMillis = parseDateToUtcMillis(date))
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    onDate(dpState.selectedDateMillis?.let { utcMillisToDate(it) })
                    showDate = false
                }) { Text("确定", color = MeiliPalette.Clay) }
            },
            dismissButton = {
                TextButton(onClick = { showDate = false }) { Text("取消", color = MeiliPalette.Ink3) }
            },
        ) {
            DatePicker(state = dpState)
        }
    }

    // ── 时间从 选择器 ──
    if (showFrom) {
        TimePickDialog(
            initial = timeFrom,
            onConfirm = { onTimeFrom(it); showFrom = false },
            onDismiss = { showFrom = false },
        )
    }
    // ── 时间到 选择器 ──
    if (showTo) {
        TimePickDialog(
            initial = timeTo,
            onConfirm = { onTimeTo(it); showTo = false },
            onDismiss = { showTo = false },
        )
    }
}

/** 日期 + 时间从–到 三个可点字段（自动换行）。已选→显示值；点 ✕ 清除。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowFields(
    date: String?,
    timeFrom: String?,
    timeTo: String?,
    onPickDate: () -> Unit,
    onClearDate: () -> Unit,
    onPickFrom: () -> Unit,
    onClearFrom: () -> Unit,
    onPickTo: () -> Unit,
    onClearTo: () -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PickerField(
            label = "日期",
            value = date,
            placeholder = "选择日期",
            onPick = onPickDate,
            onClear = onClearDate,
        )
        PickerField(
            label = "从",
            value = timeFrom,
            placeholder = "时间",
            onPick = onPickFrom,
            onClear = onClearFrom,
        )
        PickerField(
            label = "到",
            value = timeTo,
            placeholder = "时间",
            onPick = onPickTo,
            onClear = onClearTo,
        )
    }
}

/** 单个可点筛选字段：小标签 + 值/占位 + 已选时尾随清除 ✕。 */
@Composable
private fun PickerField(
    label: String,
    value: String?,
    placeholder: String,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    val has = !value.isNullOrBlank()
    Surface(
        onClick = onPick,
        shape = MeiliShapes.Sm,
        color = if (has) MeiliPalette.ClayTint else MeiliPalette.SurfaceSoft,
        contentColor = if (has) MeiliPalette.ClayDeep else MeiliPalette.Ink2,
        border = BorderStroke(Dimens.BorderThin, if (has) MeiliPalette.ClaySoft else MeiliPalette.Line),
    ) {
        Row(
            modifier = Modifier.padding(start = 11.dp, end = if (has) 7.dp else 11.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = MeiliPalette.Ink3,
            )
            Text(
                value?.takeIf { it.isNotBlank() } ?: placeholder,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                color = if (has) MeiliPalette.ClayDeep else MeiliPalette.Ink3,
            )
            if (has) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clickable { onClear() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        MeiliIcons.Add, // 旋转 45° 视作 ✕（避免引入 emoji；MeiliIcons 无独立 close）
                        contentDescription = "清除",
                        tint = MeiliPalette.ClayDeep,
                        modifier = Modifier
                            .size(13.dp)
                            .rotate(45f),
                    )
                }
            }
        }
    }
}

/** 两段式选择（时间类型）：选中=陶土 tint，未选=描边浅底，各占一半宽。 */
@Composable
private fun SegmentedPair(
    leftLabel: String,
    rightLabel: String,
    leftSelected: Boolean,
    onLeft: () -> Unit,
    onRight: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SegmentChip(leftLabel, leftSelected, onLeft, Modifier.weight(1f))
        SegmentChip(rightLabel, !leftSelected, onRight, Modifier.weight(1f))
    }
}

@Composable
private fun SegmentChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = MeiliShapes.Sm,
        color = if (selected) MeiliPalette.ClayTint else MeiliPalette.SurfaceSoft,
        contentColor = if (selected) MeiliPalette.ClayDeep else MeiliPalette.Ink2,
        border = BorderStroke(Dimens.BorderThin, if (selected) MeiliPalette.ClaySoft else MeiliPalette.Line),
    ) {
        Box(
            modifier = Modifier.padding(vertical = 9.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 12.5f.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                ),
            )
        }
    }
}

/** 时间选择弹窗（Material3 TimePicker）→ 回 'HH:mm'。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickDialog(
    initial: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val (initH, initM) = parseHm(initial)
    val tpState = rememberTimePickerState(initialHour = initH, initialMinute = initM, is24Hour = true)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onConfirm(String.format(Locale.US, "%02d:%02d", tpState.hour, tpState.minute))
            }) { Text("确定", color = MeiliPalette.Clay) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = MeiliPalette.Ink3) }
        },
    ) {
        Box(modifier = Modifier.fillMaxWidth().padding(18.dp), contentAlignment = Alignment.Center) {
            TimePicker(state = tpState)
        }
    }
}

// ─────────────────────────── 接诊行卡 ───────────────────────────

/**
 * 单行接诊卡，补齐 web 列：顾客 / 录音·ASR / 录音时间 / 综合分 / 分析状态 / 最后分析时间 / 点评 / 操作。
 * 标签 + 值成对铺排（窄屏不溢出）；状态胶囊与 part 进度随 display_status 显示；
 * running/queued 额外给「取消分析」次操作。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionRowCard(
    row: SessionRow,
    onOpenReport: (Long) -> Unit,
    onCancelAnalysis: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    val ds = row.displayStatus
    MeiliCard(tight = true) {
        // 顶行：顾客名（左，省略号）+ 状态胶囊（右）
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                row.customer?.takeIf { it.isNotBlank() } ?: "—",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            DisplayStatusPill(ds)
        }

        // part 进度后缀（完成 x/y · 正在 T..）
        val prog = taskProgressText(row)
        if (prog != null) {
            Text(
                prog,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = MeiliPalette.SageDeep,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Spacer(Modifier.height(11.dp))
        HairLine()
        Spacer(Modifier.height(11.dp))

        // 指标区：录音/ASR · 综合分 · 点评（FlowRow 窄屏自动换行，不溢出）+ 录音时间 / 最后分析时间（两组两行短串）
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MetricInline(
                label = "录音/ASR",
                value = "${row.asrDoneCount ?: 0}/${row.recordingCount ?: 0}",
            )
            ScoreMetric(score = row.overallScore)
            EvalMetric(hasEval = row.hasEval)
        }

        Spacer(Modifier.height(11.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TimeRangeColumn(
                label = "录音时间",
                first = row.firstRecordedAt,
                last = row.lastRecordedAt,
                modifier = Modifier.weight(1f),
            )
            TimeRangeColumn(
                label = "最后分析时间",
                first = row.analysisStartedAt,
                last = row.analysisFinishedAt,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(12.dp))
        HairLine()
        Spacer(Modifier.height(11.dp))

        // 操作：(取消分析) · (申请删除) · 查看报告 →
        // FlowRow 末端对齐：三项同现（running/queued + 有录音）时窄屏自动换行，不溢出。
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (ds == "running" || ds == "queued") {
                GhostButton(
                    text = "取消分析",
                    onClick = onCancelAnalysis,
                    size = MeiliButtonSize.Xs,
                    enabled = row.id != null,
                )
            }
            // 申请删除：对齐 web requestDelSessionRecs（仅 recording_count>0 时出现）。
            if ((row.recordingCount ?: 0) > 0) {
                GhostButton(
                    text = "申请删除",
                    onClick = onRequestDelete,
                    size = MeiliButtonSize.Xs,
                    icon = MeiliIcons.Trash,
                    enabled = row.id != null,
                )
            }
            Row(
                modifier = Modifier
                    .clickable(enabled = row.id != null) { row.id?.let(onOpenReport) }
                    .padding(vertical = 6.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    "查看报告",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 12.5f.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = MeiliPalette.Clay,
                )
                Icon(
                    MeiliIcons.ChevRight,
                    contentDescription = null,
                    tint = MeiliPalette.Clay,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

/** 小标签 + 值（行内并排）。 */
@Composable
private fun MetricInline(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        MetricLabel(label)
        Text(
            value,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.5f.sp, fontWeight = FontWeight.Bold),
            color = MeiliPalette.Ink,
        )
    }
}

/**
 * 综合分：对齐 web fmtScore —— null→「—」(muted)；否则按值上色（≥7 叶绿 / ≥5 蜜色 / else 玫瑰）+ 一位小数。
 */
@Composable
private fun ScoreMetric(score: Double?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        MetricLabel("综合分")
        if (score == null) {
            Text("—", style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.5f.sp), color = MeiliPalette.Ink3)
        } else {
            val c: Color = when {
                score >= 7.0 -> MeiliPalette.LeafText
                score >= 5.0 -> MeiliPalette.HoneyText
                else -> MeiliPalette.RoseText
            }
            Text(
                String.format(Locale.US, "%.1f", score),
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold),
                color = c,
            )
        }
    }
}

/** 点评：has_evaluation>0 → 叶绿「已点评」chip（含对勾），否则「—」。 */
@Composable
private fun EvalMetric(hasEval: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        MetricLabel("点评")
        if (hasEval) {
            Surface(
                shape = MeiliShapes.Pill,
                color = MeiliPalette.LeafSoft,
                contentColor = MeiliPalette.LeafText,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(MeiliIcons.Check, contentDescription = null, modifier = Modifier.size(11.dp))
                    Text(
                        "已点评",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5f.sp, fontWeight = FontWeight.Bold),
                    )
                }
            }
        } else {
            Text("—", style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.5f.sp), color = MeiliPalette.Ink3)
        }
    }
}

/** 时间区间列：小标题 + 最多两行短串（日期行 + 时间行；nowrap）。对齐 web fmtTimeRange 的两行 div。 */
@Composable
private fun TimeRangeColumn(
    label: String,
    first: String?,
    last: String?,
    modifier: Modifier = Modifier,
) {
    val lines = fmtTimeRange(first, last)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        MetricLabel(label)
        Spacer(Modifier.height(2.dp))
        if (lines.isEmpty()) {
            Text("—", style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp), color = MeiliPalette.Ink3)
        } else {
            lines.forEach { ln ->
                Text(
                    ln,
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.5f.sp),
                    color = MeiliPalette.Ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MetricLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5f.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp),
        color = MeiliPalette.Ink3,
    )
}

/**
 * display_status → 状态胶囊。映射对齐 web fmtStatus / _status_bucket_sql：
 *  done→已完成(Ok绿) / running→分析中(Run鼠尾草) / queued→排队中(Warn蜜) /
 *  failed→失败(Danger玫瑰) / stuck→中断(Danger) / cancelled→已中断(Danger) / idle→未分析(Neutral)。
 */
@Composable
private fun DisplayStatusPill(status: String?) {
    when (status) {
        "done" -> StatusPill("已完成", PillKind.Ok, icon = MeiliIcons.Check)
        "running" -> StatusPill("分析中", PillKind.Run, icon = MeiliIcons.Sync)
        "queued" -> StatusPill("排队中", PillKind.Warn, icon = MeiliIcons.Clock)
        "failed" -> StatusPill("失败", PillKind.Danger, icon = MeiliIcons.Warn)
        "stuck" -> StatusPill("中断", PillKind.Danger, icon = MeiliIcons.Warn)
        "cancelled" -> StatusPill("已中断", PillKind.Danger, icon = MeiliIcons.Warn)
        "idle" -> StatusPill("未分析", PillKind.Neutral)
        null, "" -> StatusPill("未分析", PillKind.Neutral)
        else -> StatusPill(status, PillKind.Neutral)
    }
}

/**
 * part 进度后缀文案，对齐 web fmtStatus 的 .task-prog：
 *  - running/queued 且 task_total>0 → 「完成 done/total」(+「 · 正在 T..」若 task_running 非空)
 *  - done 且 task_total>0          → 「done/total part」
 *  - 其余 → null（不显示）
 */
private fun taskProgressText(row: SessionRow): String? {
    val ds = row.displayStatus
    val total = row.taskTotal ?: 0
    if (total <= 0) return null
    val done = row.taskDone ?: 0
    return when (ds) {
        "running", "queued" -> {
            val cur = row.taskRunning?.filter { it.isNotBlank() }?.joinToString(", ") ?: ""
            "完成 $done/$total" + if (cur.isNotEmpty()) " · 正在 $cur" else ""
        }
        "done" -> "$done/$total part"
        else -> null
    }
}

// ─────────────────────────── 服务端分页器 ───────────────────────────

/** 接诊列表分页器（服务端翻页，对齐 index.html「‹ 上一页 · 第 X/Y 页 · 共 N 条 · 下一页 ›」）。 */
@Composable
private fun SessionPager(
    page: Int,
    totalPages: Int,
    total: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GhostButton(text = "上一页", onClick = onPrev, size = MeiliButtonSize.Xs, enabled = page > 1)
        Text(
            text = "第 $page / $totalPages 页 · 共 $total 条",
            style = MaterialTheme.typography.bodySmall,
            color = MeiliPalette.Ink3,
            modifier = Modifier.padding(horizontal = 14.dp),
        )
        GhostButton(text = "下一页", onClick = onNext, size = MeiliButtonSize.Xs, enabled = page < totalPages)
    }
}

// ─────────────────────────── 时间格式化（1:1 复刻 web fmtRecTime / fmtTimeRange）───────────────────────────

/** 解析单个时间戳 → (date, time)；对齐 web fmtRecTime。无法识别时 date="" time=raw。 */
private data class RecTime(val date: String, val time: String)

private fun fmtRecTime(t: String?): RecTime? {
    if (t.isNullOrBlank()) return null
    val s = t
    if (s.length == 14 && s.all { it.isDigit() }) {
        return RecTime(
            date = "${s.substring(0, 4)}-${s.substring(4, 6)}-${s.substring(6, 8)}",
            time = "${s.substring(8, 10)}:${s.substring(10, 12)}",
        )
    }
    // ^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})
    val m = Regex("""^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})""").find(s)
    return if (m != null) {
        val (y, mo, d, hh, mm) = m.destructured
        RecTime(date = "$y-$mo-$d", time = "$hh:$mm")
    } else {
        RecTime(date = "", time = s)
    }
}

/**
 * 时间区间 → 最多两行短串，1:1 复刻 web fmtTimeRange：
 *  - both null → []（调用方显示「—」）
 *  - only one → [date, time]
 *  - 同日同时 → [date, time]
 *  - 同日不同时 → [date, "t1–t2"]
 *  - 不同日 → ["d1 t1", "d2 t2"]
 * 空 date 行会被过滤掉（避免出现孤零零空白行）。
 */
private fun fmtTimeRange(first: String?, last: String?): List<String> {
    val a = fmtRecTime(first)
    val b = fmtRecTime(last)
    val raw: List<String> = when {
        a == null && b == null -> emptyList()
        a != null && b == null -> listOf(a.date, a.time)
        a == null && b != null -> listOf(b.date, b.time)
        a!!.date == b!!.date && a.time == b.time -> listOf(a.date, a.time)
        a.date == b.date -> listOf(a.date, "${a.time}–${b.time}")
        else -> listOf("${a.date} ${a.time}", "${b.date} ${b.time}")
    }
    return raw.map { it.trim() }.filter { it.isNotEmpty() }
}

// ─────────────────────────── 日期/时间 picker 取值 helper（minSdk 24，无 java.time）───────────────────────────

/** 'YYYY-MM-DD' → DatePicker 用的 UTC 毫秒（M3 DatePicker 以 UTC 午夜计）；解析失败 → null（默认今日）。 */
private fun parseDateToUtcMillis(date: String?): Long? {
    if (date.isNullOrBlank()) return null
    return runCatching {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        fmt.parse(date)?.time
    }.getOrNull()
}

/** M3 DatePicker 的 UTC 毫秒 → 'yyyy-MM-dd'（同样按 UTC 格式化，避免负时区跳天）。 */
private fun utcMillisToDate(millis: Long): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    return fmt.format(java.util.Date(millis))
}

/** 'HH:MM' → (hour, minute)；解析失败 → (9, 0)。 */
private fun parseHm(hm: String?): Pair<Int, Int> {
    if (hm.isNullOrBlank()) return 9 to 0
    val m = Regex("""^(\d{1,2}):(\d{2})""").find(hm) ?: return 9 to 0
    val (h, mi) = m.destructured
    val hh = h.toIntOrNull()?.coerceIn(0, 23) ?: 9
    val mm = mi.toIntOrNull()?.coerceIn(0, 59) ?: 0
    return hh to mm
}

// ─────────────────────────── 取消分析 / 申请删除 dialog + 提示条 ───────────────────────────

/**
 * 取消分析二次确认（对齐 web cancelAnalysis 的 confirm）。
 * 确认后由调用方触发 repo.cancelAnalysis；失败文案走屏内底部条（VM 推 toast）。
 */
@Composable
private fun CancelAnalysisDialog(
    customer: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val who = customer?.takeIf { it.isNotBlank() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MeiliPalette.Surface,
        titleContentColor = MeiliPalette.Ink,
        textContentColor = MeiliPalette.Ink2,
        shape = MeiliShapes.Lg,
        icon = {
            Icon(MeiliIcons.Warn, contentDescription = null, tint = MeiliPalette.RoseText, modifier = Modifier.size(26.dp))
        },
        title = { Text("确定取消分析？", style = MaterialTheme.typography.headlineSmall, color = MeiliPalette.Ink) },
        text = {
            Text(
                buildString {
                    if (who != null) append("「$who」这次接诊的")
                    append("分析将被中断，状态变为「已中断」，之后可重新开始。")
                },
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5f.sp, lineHeight = 20.sp),
                color = MeiliPalette.Ink2,
            )
        },
        confirmButton = {
            PrimaryButton(
                text = "取消分析",
                onClick = onConfirm,
                icon = MeiliIcons.Check,
                size = MeiliButtonSize.Small,
            )
        },
        dismissButton = {
            GhostButton(text = "再想想", onClick = onDismiss, size = MeiliButtonSize.Small)
        },
    )
}

/**
 * 申请删除说明（对齐 web requestDelSessionRecs 的意图：顾问端就某次接诊的录音发起删除申请，走审批）。
 *
 * ⚠ 数据约束：列表行 [SessionRow] 只带 session id + recording_count，不带逐条 recording id；
 * 而仓库现有 deleteRequest(rid) 是【按 recording 申请】的端点（/api/recording/{rid}/delete-request）。
 * 直接拿 session id 当 rid 调会打到错误录音（误删风险），故此处不直连，而是把入口引导进「查看报告」——
 * 报告内按单段录音逐条申请删除（rid 在那里才拿得到），与"不误删 + 能编译 + 不加 api/repo/model"一致。
 */
@Composable
private fun RequestDeleteDialog(
    row: SessionRow,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val who = row.customer?.takeIf { it.isNotBlank() }
    val n = row.recordingCount ?: 0
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MeiliPalette.Surface,
        titleContentColor = MeiliPalette.Ink,
        textContentColor = MeiliPalette.Ink2,
        shape = MeiliShapes.Lg,
        icon = {
            Icon(MeiliIcons.Trash, contentDescription = null, tint = MeiliPalette.RoseText, modifier = Modifier.size(26.dp))
        },
        title = { Text("申请删除录音", style = MaterialTheme.typography.headlineSmall, color = MeiliPalette.Ink) },
        text = {
            Text(
                buildString {
                    append("将对")
                    append(if (who != null) "「$who」这次接诊" else "本次接诊")
                    if (n > 0) append("的 $n 段录音")
                    append("发起删除申请，需管理员审批通过后才会真正删除。")
                },
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5f.sp, lineHeight = 20.sp),
                color = MeiliPalette.Ink2,
            )
        },
        confirmButton = {
            PrimaryButton(
                text = "确认申请删除",
                onClick = onConfirm,
                icon = MeiliIcons.Trash,
                size = MeiliButtonSize.Small,
                enabled = row.id != null,
            )
        },
        dismissButton = {
            GhostButton(text = "取消", onClick = onDismiss, size = MeiliButtonSize.Small)
        },
    )
}

/** 屏内底部一次性提示条（对齐 ReportScreen 的 toast；danger=玫瑰底，否则墨底；约 2.6s 自动消失）。 */
@Composable
private fun ArchiveToastBar(
    toast: ArchiveToast,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(toast) {
        kotlinx.coroutines.delay(2600)
        onDismiss()
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.ScreenH)
            .padding(bottom = Dimens.BottomNavInset + 16.dp),
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

// ─────────────────────────── 公共小件 ───────────────────────────

@Composable
private fun HairLine() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(Dimens.BorderThin)
            .background(MeiliPalette.LineSoft),
    )
}

@Composable
private fun InlineLoading(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            color = MeiliPalette.Clay,
            strokeWidth = 2.dp,
        )
        Text(text, style = MaterialTheme.typography.bodySmall, color = MeiliPalette.Ink3)
    }
}

@Composable
private fun EmptyHint(icon: ImageVector, title: String, sub: String?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(58.dp)
                .background(MeiliPalette.SurfaceSoft, RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MeiliPalette.Ink4,
                modifier = Modifier.size(30.dp),
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
            color = MeiliPalette.Ink2,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 14.dp),
        )
        if (!sub.isNullOrBlank()) {
            Text(
                sub,
                style = MaterialTheme.typography.bodySmall,
                color = MeiliPalette.Ink3,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF8F3ED, widthDp = 360, heightDp = 760)
@Composable
private fun ArchiveScreenPreview() {
    MeiliTheme { ArchiveScreen() }
}
