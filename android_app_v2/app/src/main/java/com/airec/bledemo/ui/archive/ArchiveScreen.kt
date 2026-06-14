package com.airec.bledemo.ui.archive

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.airec.bledemo.designsystem.MeiliTextStyles
import com.airec.bledemo.designsystem.MeiliTheme
import com.airec.bledemo.designsystem.components.GhostButton
import com.airec.bledemo.designsystem.components.MeiliBottomSheet
import com.airec.bledemo.designsystem.components.MeiliButtonSize
import com.airec.bledemo.designsystem.components.MeiliCard
import com.airec.bledemo.designsystem.components.MeiliTopBar
import com.airec.bledemo.designsystem.components.TopBarIconButton
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * 「报告」屏（底栏报告 tab）—— 方案A 精简版，对齐 mockups/redesign_2026-06-13.html 的 `#reportlist`。
 *
 * 顾问只看自己名下接诊（服务端按 advisor_name 自动隔离），每行 → 一份分析报告。
 * 单行只留：头像(姓名首字) + 姓名 + 一行灰小字（服务日期）+ 综合分(衬线陶土) + chevron；
 * 点整行 → onOpenReport(sessionId)。去掉了分析状态/录音时间/最后分析时间/点评/逐行操作按钮。
 *
 * 顶部：标题「报告」+ 副标题「你名下的接诊分析报告」+ 右上角文字「刷新」(GhostButton small) + 齿轮进设置。
 * 筛选：一排 = 会员姓名/卡号搜索(占满) + 「日期」chip(开日期选择器) + 「状态」chip(开状态 bottom sheet)。
 * 底部保留服务端分页器；状态筛选仍复用 VM 既有 status 桶筛选（含计数请求）。
 *
 * 红线：对外只说「接诊 / 分析报告」，绝不出现录音/录制字样；360–390dp 弹性不溢出（搜索框 weight、长姓名 ellipsis）。
 *
 * @param onOpenReport 进入某次接诊的分析报告（sessionId）。
 * @param onOpenSettings 右上角齿轮进设置（皮肤/主题）。
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

    // 「日期」选择器 / 「状态」筛选 bottom sheet 的可见性（屏内本地态）。
    var showDatePicker by remember { mutableStateOf(false) }
    var showStatusSheet by remember { mutableStateOf(false) }

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
                    title = "报告",
                    subtitle = "你名下的接诊分析报告",
                    actions = {
                        // 右上角：文字「刷新」(GhostButton small，非图标按钮) + 齿轮进设置。
                        GhostButton(
                            text = "刷新",
                            onClick = viewModel::refresh,
                            size = MeiliButtonSize.Small,
                        )
                        TopBarIconButton(MeiliIcons.Settings, onClick = onOpenSettings)
                    },
                )
            }

            // ── 筛选：一排 = 搜索(占满) + 日期 chip + 状态 chip ──
            item {
                FilterRow(
                    query = state.customerQuery,
                    date = state.date,
                    statusLabel = activeStatusLabel(state.statusFilter),
                    onQueryChange = viewModel::setCustomerQuery,
                    onDateClick = { showDatePicker = true },
                    onStatusClick = { showStatusSheet = true },
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
                            title = "暂无接诊报告",
                            sub = if (hasActiveFilter(state)) {
                                "换个会员姓名 / 卡号 / 日期 / 状态筛选试试"
                            } else {
                                "完成接诊并绑定顾客后，会在这里看到分析报告"
                            },
                        )
                    }
                }
                else -> {
                    // 列表整段一张紧凑卡（行间用细线分隔），对齐 mockup .card.tight 包多行 .rep-row。
                    item {
                        MeiliCard(tight = true) {
                            state.sessions.forEachIndexed { idx, row ->
                                if (idx > 0) HairLine()
                                ReportRow(row = row, onOpenReport = onOpenReport)
                            }
                        }
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
    }

    // ── 「日期」选择器（按服务日期筛选；接 VM 既有 date 入参）──
    if (showDatePicker) {
        ReportDatePickerDialog(
            date = state.date,
            onPick = { viewModel.setDate(it); showDatePicker = false },
            onClearAll = { viewModel.setDate(null); showDatePicker = false },
            onDismiss = { showDatePicker = false },
        )
    }

    // ── 「状态」筛选 bottom sheet（全部/已完成/排队中/分析中/失败 → VM status 桶）──
    MeiliBottomSheet(
        visible = showStatusSheet,
        onDismiss = { showStatusSheet = false },
        title = "按状态筛选",
        subtitle = "只看某种状态的报告。",
    ) {
        ReportStatusFilters.forEach { opt ->
            StatusRadioRow(
                label = opt.label,
                selected = opt.value == state.statusFilter,
                onClick = {
                    viewModel.setStatus(opt.value)
                    showStatusSheet = false
                },
            )
        }
    }
}

/** 是否存在任一生效筛选（用于空态文案）。仅顾客 / 状态 / 日期（方案A 不再有时间区间筛选）。 */
private fun hasActiveFilter(s: ArchiveListState): Boolean =
    s.customerQuery.isNotBlank() || s.statusFilter != null || s.date != null

// ─────────────────────────── 筛选行（搜索 + 日期 + 状态） ───────────────────────────

/**
 * 一排筛选：会员姓名/卡号搜索(占满剩余宽) + 「日期」chip + 「状态」chip。
 * 窄屏(360dp)下搜索框 weight 收缩、两 chip 固定宽，不横向溢出。
 */
@Composable
private fun FilterRow(
    query: String,
    date: String?,
    statusLabel: String?,
    onQueryChange: (String) -> Unit,
    onDateClick: () -> Unit,
    onStatusClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        CustomerSearchField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
        )
        // 「日期」chip：已选→陶土实底显日期(尾段)，未选→描边浅底。
        FilterChip(
            text = date?.let { shortDate(it) } ?: "日期",
            leadingIcon = MeiliIcons.Reception,
            active = date != null,
            onClick = onDateClick,
        )
        // 「状态」chip：已选某桶→陶土实底显桶名，未选(全部)→描边浅底 + 下拉箭头。
        FilterChip(
            text = statusLabel ?: "状态",
            trailingIcon = if (statusLabel == null) MeiliIcons.ChevDown else null,
            active = statusLabel != null,
            onClick = onStatusClick,
        )
    }
}

/** 搜索框：占满给定宽度，占位「搜会员姓名 / 卡号」，驱动顾客筛选。 */
@Composable
private fun CustomerSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = true,
        placeholder = {
            Text(
                "搜会员姓名 / 卡号",
                style = MaterialTheme.typography.bodyMedium,
                color = MeiliPalette.Ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
        shape = MeiliShapes.Pill,
        textStyle = MaterialTheme.typography.bodyMedium,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = {}),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MeiliPalette.White,
            unfocusedContainerColor = MeiliPalette.Surface,
            focusedBorderColor = MeiliPalette.Clay,
            unfocusedBorderColor = MeiliPalette.Line,
            cursorColor = MeiliPalette.Clay,
            focusedTextColor = MeiliPalette.Ink,
            unfocusedTextColor = MeiliPalette.Ink,
        ),
    )
}

/**
 * 过滤 chip（对齐 mockup .fchip / .fchip.on）：未激活=浅底描边、激活=陶土 tint + 陶土深字。
 * 可带前/后线性小图标（取自 MeiliIcons，无 emoji）。文本 nowrap，激活态显当前选择。
 */
@Composable
private fun FilterChip(
    text: String,
    active: Boolean,
    onClick: () -> Unit,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
) {
    Surface(
        onClick = onClick,
        shape = MeiliShapes.Pill,
        color = if (active) MeiliPalette.ClayTint else MeiliPalette.SurfaceSoft,
        contentColor = if (active) MeiliPalette.ClayDeep else MeiliPalette.Ink2,
        border = BorderStroke(Dimens.BorderThin, if (active) MeiliPalette.ClaySoft else MeiliPalette.Line),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (leadingIcon != null) {
                Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(14.dp))
            }
            Text(
                text,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (trailingIcon != null) {
                Icon(trailingIcon, contentDescription = null, modifier = Modifier.size(13.dp))
            }
        }
    }
}

// ─────────────────────────── 报告行 ───────────────────────────

/**
 * 单行报告（对齐 mockup .rep-row）：头像(姓名首字) + 姓名 + 一行灰小字(服务日期) + 综合分 + chevron。
 * 整行可点 → onOpenReport(id)。综合分走衬线陶土；无分(未完成/无 overall) 显 muted「—」。
 *
 * 说明：mockup 灰小字示意为「会员卡号 · 服务 日期」，但列表数据源 [SessionRow] 不含会员卡号字段
 * （/api/sessions 仅返回 customer/service_date，会员卡在 company_customers 表，不在本列表）。
 * 红线「不臆造 model 字段」，故灰小字只用真实存在的服务日期：「服务 {service_date}」；缺日期则「服务日期待补」。
 */
@Composable
private fun ReportRow(
    row: SessionRow,
    onOpenReport: (Long) -> Unit,
) {
    val name = row.customer?.takeIf { it.isNotBlank() } ?: "—"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = row.id != null) { row.id?.let(onOpenReport) }
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(name = name)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.titleMedium,
                color = MeiliPalette.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                metaLine(row),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = MeiliPalette.Ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        // 综合分：done 且有 overall → 衬线陶土整数；否则 muted「—」。
        ReportScore(row = row)
        Icon(
            MeiliIcons.ChevRight,
            contentDescription = null,
            tint = MeiliPalette.Clay,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** 头像：姓名首字 + 暖玉渐变底（对齐 mockup .avatar）。 */
@Composable
private fun Avatar(name: String) {
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(MeiliShapes.Sm)
            .background(MeiliPalette.AvatarGradient),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.take(1),
            style = MeiliTextStyles.SummaryBig.copy(fontSize = 17.sp),
            color = MeiliPalette.ClayDeep,
        )
    }
}

/**
 * 综合分（对齐 mockup .rep-score / .rep-score.none）：
 * 仅在分析完成(display_status=done)且 analysis_scores.overall 非空时显分；其余一律 muted「—」。
 * 数字走衬线陶土，约 23sp，定宽右对齐，避免一位/两位分跳动。
 */
@Composable
private fun ReportScore(row: SessionRow) {
    val score = row.overallScore?.takeIf { row.displayStatus == "done" }
    Box(
        modifier = Modifier.size(width = 38.dp, height = 26.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (score == null) {
            Text(
                "—",
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
                color = MeiliPalette.Ink4,
            )
        } else {
            Text(
                formatScore(score),
                style = MeiliTextStyles.ScoreBig.copy(fontSize = 23.sp),
                color = MeiliPalette.Clay,
                maxLines = 1,
            )
        }
    }
}

/** 综合分整数显示：四舍五入取整（mockup 用整数 86/79；overall 为 0–10/0–100 皆按原值取整）。 */
private fun formatScore(score: Double): String = Math.round(score).toString()

/** 灰小字：「会员卡号 · 服务 {date}」（member_card 由 /api/sessions JOIN company_customers 回传；无卡号则只显服务日期）。 */
private fun metaLine(row: SessionRow): String {
    val card = row.memberCard?.takeIf { it.isNotBlank() }
    val date = row.serviceDate?.takeIf { it.isNotBlank() }?.let { "服务 $it" } ?: "服务日期待补"
    return if (card != null) "$card · $date" else date
}

// ─────────────────────────── 状态筛选 bottom sheet ───────────────────────────

/** 状态筛选选项：value=后端桶名(null=全部) + 展示文案。对齐 mockup m-statusfilter 的 5 项。 */
private data class ReportStatusOption(val value: String?, val label: String)

private val ReportStatusFilters: List<ReportStatusOption> = listOf(
    ReportStatusOption(null, "全部"),
    ReportStatusOption("done", "已完成"),
    ReportStatusOption("queued", "排队中"),
    ReportStatusOption("running", "分析中"),
    ReportStatusOption("failed", "失败"),
)

/** 当前状态桶 → chip 展示文案；全部(null) → null（chip 显「状态」+ 下拉箭头）。 */
private fun activeStatusLabel(bucket: String?): String? =
    ReportStatusFilters.firstOrNull { it.value == bucket && it.value != null }?.label

/** 状态单选行（对齐 mockup .radio-row）：左圆点单选 + 文案；选中=陶土。 */
@Composable
private fun StatusRadioRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MeiliShapes.Sm,
        color = if (selected) MeiliPalette.ClayTint else MeiliPalette.Surface,
        contentColor = if (selected) MeiliPalette.ClayDeep else MeiliPalette.Ink,
        border = BorderStroke(Dimens.BorderThin, if (selected) MeiliPalette.Clay else MeiliPalette.Line),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 单选圈：选中=陶土实心内点，未选=描边空圈。
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(if (selected) MeiliPalette.Clay else Color.Transparent)
                    .then(
                        if (selected) Modifier
                        else Modifier.border(2.dp, MeiliPalette.Ink4, androidx.compose.foundation.shape.CircleShape),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(MeiliPalette.White),
                    )
                }
            }
            Text(
                label,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp),
                color = if (selected) MeiliPalette.ClayDeep else MeiliPalette.Ink,
            )
        }
    }
}

// ─────────────────────────── 日期选择器 ───────────────────────────

/** 按服务日期筛选弹窗（对齐 mockup m-datefilter）：选某天 → setDate；「全部日期」→ 清空。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReportDatePickerDialog(
    date: String?,
    onPick: (String?) -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val dpState = rememberDatePickerState(initialSelectedDateMillis = parseDateToUtcMillis(date))
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onPick(dpState.selectedDateMillis?.let { utcMillisToDate(it) })
            }) { Text("确定", color = MeiliPalette.Clay) }
        },
        dismissButton = {
            TextButton(onClick = onClearAll) { Text("全部日期", color = MeiliPalette.Ink3) }
        },
    ) {
        DatePicker(state = dpState)
    }
}

// ─────────────────────────── 服务端分页器 ───────────────────────────

/** 接诊列表分页器（服务端翻页，对齐 mockup「‹ 上一页 · 第 X/Y 页 · 下一页 ›」）。 */
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

// ─────────────────────────── 日期解析 helper（minSdk 24，无 java.time）───────────────────────────

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

/** chip 上的日期短串：'2026-06-08' → '06-08'（省宽，不溢出）；非标准串原样返回。 */
private fun shortDate(date: String): String {
    val m = Regex("""^\d{4}-(\d{2}-\d{2})$""").find(date)
    return m?.groupValues?.get(1) ?: date
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
