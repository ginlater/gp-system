package com.airec.bledemo.ui.admin.ops

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.airec.bledemo.data.model.OpsAdvisorRow
import com.airec.bledemo.data.model.OpsCompany
import com.airec.bledemo.data.model.OpsDashboardResponse
import com.airec.bledemo.data.model.OpsFailure
import com.airec.bledemo.data.model.OpsSessionStats
import com.airec.bledemo.designsystem.Dimens
import com.airec.bledemo.designsystem.MeiliIcons
import com.airec.bledemo.designsystem.MeiliShapes
import com.airec.bledemo.designsystem.MeiliTheme
import com.airec.bledemo.designsystem.components.GhostButton
import com.airec.bledemo.designsystem.components.MeiliButtonSize
import com.airec.bledemo.designsystem.components.MeiliCard
import com.airec.bledemo.designsystem.components.MeiliTopBar
import com.airec.bledemo.designsystem.components.SectionLabel
import com.airec.bledemo.designsystem.components.StatusPill
import com.airec.bledemo.designsystem.components.PillKind

/**
 * 运营看板（管理台 · GET /api/admin/ops_dashboard）。
 *
 * 忠实对齐 web admin.html 的渲染：
 *  - 顶部「今日 X | 本周自 Y」生成时间。
 *  - 今日 / 本周各一组 KPI（接诊包 / 已完成 / 失败 / 待分析 / 完成率 / 平均耗时，对齐 `_kpiCards`）。
 *  - 顾问明细（本周）：web 是 7 列表；窄屏改为「每顾问一卡 + 紧凑指标」，数据一致、不横向溢出（对齐 `_advisorTable`）。
 *  - 近期失败：#id 顾问→顾客 + 日期 + 错误摘要（对齐 `_failList`）。
 *  - 超管：按公司分块各渲染上面四项（对齐 `renderOpsSuper`）。
 *
 * 注：web 还有「顾问报告查看情况」子表（独立端点 /api/admin/report_view_stats），WAVE 1 暂不含，后续可加。
 *
 * 内部管理视图：词汇沿用后端（接诊包/顾问/失败），不走顾客可见的「陪伴」红线。
 *
 * @param onBack 返回管理台
 * @param modifier 由 AppScaffold 传入
 */
@Composable
fun AdminOpsScreen(
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: AdminOpsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AdminOpsContent(
        state = state,
        onBack = onBack,
        onRetry = { viewModel.load() },
        modifier = modifier,
    )
}

@Composable
private fun AdminOpsContent(
    state: AdminOpsUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
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
            val d = state.data
            val subtitle = if (d?.today != null) {
                "今日 ${d.today}  |  本周自 ${d.weekStart ?: "—"}"
            } else null
            MeiliTopBar(title = "运营看板", subtitle = subtitle, onBack = onBack)

            when {
                state.isInitialLoading -> LoadingState()

                state.error != null && d == null -> ErrorState(message = state.error, onRetry = onRetry)

                d != null -> {
                    if (d.isSuper) {
                        val companies = d.companies.orEmpty()
                        if (companies.isEmpty()) {
                            EmptyState()
                        } else {
                            companies.forEachIndexed { i, co ->
                                if (i > 0) Spacer(Modifier.height(Dimens.S5))
                                CompanyBlock(co)
                            }
                        }
                    } else {
                        AdminBlock(
                            todayStats = d.todayStats,
                            weekStats = d.weekStats,
                            advisors = d.advisors.orEmpty(),
                            failures = d.recentFailures.orEmpty(),
                        )
                    }
                    // 刷新中（已有旧数据时的轻提示）
                    if (state.loading) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "正在刷新…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MeiliTheme.colors.ink3,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else if (state.error != null) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = state.error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MeiliTheme.colors.roseText,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                else -> EmptyState()
            }
        }
    }
}

/* ───────────────────────── admin / 店长块 ───────────────────────── */

@Composable
private fun AdminBlock(
    todayStats: OpsSessionStats?,
    weekStats: OpsSessionStats?,
    advisors: List<OpsAdvisorRow>,
    failures: List<OpsFailure>,
) {
    KpiSection(label = "今日", stats = todayStats, periodWord = "今日")
    Spacer(Modifier.height(Dimens.CardGap))
    KpiSection(label = "本周", stats = weekStats, periodWord = "本周")
    Spacer(Modifier.height(Dimens.S5))
    AdvisorSection(advisors)
    Spacer(Modifier.height(Dimens.S5))
    FailureSection(failures)
}

/* ───────────────────────── 超管：按公司分块 ───────────────────────── */

@Composable
private fun CompanyBlock(co: OpsCompany) {
    SectionLabel(text = co.companyName ?: "公司 #${co.companyId ?: "?"}", icon = MeiliIcons.Doc)
    Spacer(Modifier.height(10.dp))
    KpiSection(label = "今日", stats = co.today, periodWord = "今日")
    Spacer(Modifier.height(Dimens.CardGap))
    KpiSection(label = "本周", stats = co.week, periodWord = "本周")
    Spacer(Modifier.height(Dimens.S5))
    AdvisorSection(co.advisors.orEmpty())
    Spacer(Modifier.height(Dimens.S5))
    FailureSection(co.recentFailures.orEmpty())
}

/* ───────────────────────── KPI 区（对齐 _kpiCards 6 项） ───────────────────────── */

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KpiSection(label: String, stats: OpsSessionStats?, periodWord: String) {
    val s = stats ?: OpsSessionStats()
    SectionLabel(text = label, icon = MeiliIcons.Trend)
    Spacer(Modifier.height(10.dp))
    MeiliCard(tight = true) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            KpiTile("${s.total}", "${periodWord}接诊包", KpiTone.Ink)
            KpiTile("${s.done}", "已完成", KpiTone.Green)
            KpiTile("${s.failed}", "失败", KpiTone.Red)
            KpiTile("${s.pending}", "待分析", KpiTone.Ink)
            KpiTile("${s.rate}%", "完成率", rateTone(s.rate))
            KpiTile(avgLabel(s.avgMin), "平均耗时", KpiTone.Ink)
        }
    }
}

private enum class KpiTone { Ink, Green, Red }

/** 完成率配色：>=80 绿、>=50 中性、否则红（对齐 web rateColor）。 */
private fun rateTone(rate: Int): KpiTone = when {
    rate >= 80 -> KpiTone.Green
    rate >= 50 -> KpiTone.Ink
    else -> KpiTone.Red
}

private fun avgLabel(avgMin: Double?): String =
    if (avgMin == null) "-" else "${trimNum(avgMin)} 分钟"

/** 去掉整数的 .0 尾巴（1.0→1，1.5→1.5），与 web 的数字呈现一致。 */
private fun trimNum(v: Double): String =
    if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()

/**
 * 单个 KPI 瓦片。三列等分（每行 3 个，2 行共 6 个），宽度 = (满宽 - 两个 10dp 间距)/3，
 * 用 FlowRow + 固定百分比避免窄屏横向溢出。
 */
@Composable
private fun KpiTile(value: String, label: String, tone: KpiTone) {
    val valueColor = when (tone) {
        KpiTone.Ink -> MeiliTheme.colors.ink
        KpiTone.Green -> MeiliTheme.colors.leafText
        KpiTone.Red -> MeiliTheme.colors.roseText
    }
    Column(
        // 三等分：fillMaxWidth(1/3) 会含进 spacing，故用稍小系数留出 10dp*2 间距余量。
        modifier = Modifier.fillMaxWidth(0.30f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall.copy(
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
            ),
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = MeiliTheme.colors.ink3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/* ───────────────────────── 顾问明细（窄屏卡式，对齐 _advisorTable） ───────────────────────── */

@Composable
private fun AdvisorSection(advisors: List<OpsAdvisorRow>) {
    SectionLabel(text = "顾问明细（本周）", icon = MeiliIcons.Profile)
    Spacer(Modifier.height(10.dp))
    if (advisors.isEmpty()) {
        MutedNote("本周暂无数据")
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        advisors.forEach { a -> AdvisorRowCard(a) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdvisorRowCard(a: OpsAdvisorRow) {
    MeiliCard(tight = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = a.name ?: "—",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = MeiliTheme.colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            // 完成率胶囊（>=80 绿 / >=50 中性 / 否则玫瑰）
            StatusPill(
                text = "完成率 ${a.weekRate}%",
                kind = when {
                    a.weekRate >= 80 -> PillKind.Ok
                    a.weekRate >= 50 -> PillKind.Neutral
                    else -> PillKind.Danger
                },
            )
        }
        Spacer(Modifier.height(10.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MetricChip("接诊", "${a.weekTotal}", MeiliTheme.colors.ink)
            MetricChip("完成", "${a.weekDone}", MeiliTheme.colors.leafText)
            MetricChip("失败", "${a.weekFailed}", MeiliTheme.colors.roseText)
            MetricChip("待分析", "${a.weekPending}", MeiliTheme.colors.ink)
            MetricChip("平均", avgLabel(a.avgMin), MeiliTheme.colors.ink2)
        }
    }
}

/** 小指标块：「标签 值」横排，surfaceSoft 底圆角。 */
@Composable
private fun MetricChip(label: String, value: String, valueColor: Color) {
    Surface(
        shape = MeiliShapes.Xs,
        color = MeiliTheme.colors.surfaceSoft,
        contentColor = MeiliTheme.colors.ink2,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = MeiliTheme.colors.ink3,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = valueColor,
            )
        }
    }
}

/* ───────────────────────── 近期失败（对齐 _failList） ───────────────────────── */

@Composable
private fun FailureSection(failures: List<OpsFailure>) {
    SectionLabel(text = "近期失败", icon = MeiliIcons.Warn)
    Spacer(Modifier.height(10.dp))
    if (failures.isEmpty()) {
        MutedNote("暂无失败记录")
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        failures.forEach { f -> FailureCard(f) }
    }
}

@Composable
private fun FailureCard(f: OpsFailure) {
    MeiliCard(tight = true) {
        val head = buildString {
            f.id?.let { append("#").append(it).append("  ") }
            append(f.advisor ?: "—")
            append(" → ")
            append(f.customer ?: "—")
        }
        Text(
            text = head,
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = 13.5f.sp,
                fontWeight = FontWeight.Bold,
            ),
            color = MeiliTheme.colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!f.serviceDate.isNullOrBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(
                text = f.serviceDate,
                style = MaterialTheme.typography.labelSmall,
                color = MeiliTheme.colors.ink3,
            )
        }
        Spacer(Modifier.height(7.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MeiliShapes.Xs,
            color = MeiliTheme.colors.roseSoft,
            contentColor = MeiliTheme.colors.roseText,
        ) {
            Text(
                text = (f.analysisError ?: "未知错误").take(120),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
                color = MeiliTheme.colors.roseText,
                modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
            )
        }
    }
}

/* ───────────────────────── 状态/小组件 ───────────────────────── */

@Composable
private fun MutedNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MeiliTheme.colors.ink3,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp, horizontal = 2.dp),
    )
}

@Composable
private fun LoadingState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 80.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = MeiliTheme.colors.clay, modifier = Modifier.size(34.dp))
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
                .background(MeiliTheme.colors.surfaceSoft, MeiliShapes.Lg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                MeiliIcons.Trend,
                contentDescription = null,
                tint = MeiliTheme.colors.ink4,
                modifier = Modifier.size(30.dp),
            )
        }
        Text(
            text = "暂无运营数据",
            style = MaterialTheme.typography.titleSmall,
            color = MeiliTheme.colors.ink2,
            modifier = Modifier.padding(top = 14.dp),
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
                .background(MeiliTheme.colors.surfaceSoft, MeiliShapes.Lg),
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
            text = "运营看板没能加载出来",
            style = MaterialTheme.typography.titleSmall,
            color = MeiliTheme.colors.ink2,
            modifier = Modifier.padding(top = 14.dp),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MeiliTheme.colors.ink3,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
        GhostButton(
            text = "重试",
            onClick = onRetry,
            icon = MeiliIcons.Refresh,
            size = MeiliButtonSize.Small,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 1000)
@Composable
private fun AdminOpsScreenPreview() {
    MeiliTheme {
        AdminOpsContent(
            state = AdminOpsUiState(
                data = OpsDashboardResponse(
                    isSuper = false,
                    today = "2026-06-09",
                    weekStart = "2026-06-08",
                    todayStats = OpsSessionStats(total = 12, done = 9, failed = 1, pending = 2, rate = 75, avgMin = 6.5),
                    weekStats = OpsSessionStats(total = 84, done = 73, failed = 4, pending = 7, rate = 87, avgMin = 7.0),
                    advisors = listOf(
                        OpsAdvisorRow("张敏", weekTotal = 30, weekDone = 28, weekFailed = 1, weekPending = 1, weekRate = 93, avgMin = 6.2),
                        OpsAdvisorRow("李华", weekTotal = 24, weekDone = 15, weekFailed = 3, weekPending = 6, weekRate = 62, avgMin = 8.1),
                    ),
                    recentFailures = listOf(
                        OpsFailure(id = 1201, advisor = "李华", customer = "刘佳佳", serviceDate = "2026-06-08", analysisError = "ASR 超时：音频过长，转写任务被中断"),
                    ),
                ),
            ),
            onBack = {},
            onRetry = {},
        )
    }
}
