package com.airec.bledemo.ui.customer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.airec.bledemo.data.model.AccumulatedTag
import com.airec.bledemo.data.model.AdvisorMatch
import com.airec.bledemo.data.model.CustomerProfileInfo
import com.airec.bledemo.data.model.CustomerValueContent
import com.airec.bledemo.data.model.ProfileSession
import com.airec.bledemo.designsystem.Dimens
import com.airec.bledemo.designsystem.MeiliIcons
import com.airec.bledemo.designsystem.MeiliPalette
import com.airec.bledemo.designsystem.MeiliShapes
import com.airec.bledemo.designsystem.MeiliTheme
import com.airec.bledemo.designsystem.components.MeiliCard
import com.airec.bledemo.designsystem.components.MeiliTopBar
import com.airec.bledemo.designsystem.components.PillKind
import com.airec.bledemo.designsystem.components.SectionLabel
import com.airec.bledemo.designsystem.components.SoftButton
import com.airec.bledemo.designsystem.components.StatusPill

/**
 * 「客户详情」屏（redesign 原型 #customer）—— 顾客历史陪伴与画像，1:1 复刻 web customer_profile.html。
 *
 * MeiliTopBar(返回 + 顾客名 + sub「顾客档案」)；然后：
 *  ① 头部卡（头像 + 姓名 + 会员号/尾号 + 「陪伴 N 次」session_count）；
 *  ② 「累积标签」SectionLabel + 标签 pill（tag ×count，鼠尾草色）；
 *  ③ 「客户价值预测」卡（customer_value 各维度）；未生成 → SoftButton「看客户价值预测」重新拉缓存；
 *  ④ 「陪伴时间线」（sessions：service_date + 顾问/门店 + 状态）。
 *
 * 只读：顾问端不生成/不跳报告。对 null/空响应一律防御。红线：对外零「录音/录制」，统一「陪伴 / 陪伴时间线」。
 *
 * @param customerId 顾客主键（cid）。
 * @param onBack 返回上一屏（客户搜索）。
 * @param modifier 由导航/Scaffold 传入。
 * @param viewModel 状态机；[CustomerDetailViewModel.load] 由 LaunchedEffect(customerId) 触发。
 */
@Composable
fun CustomerDetailScreen(
    customerId: Long,
    onBack: () -> Unit = {},
    onOpenReport: (sessionId: Long) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: CustomerDetailViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(customerId) { viewModel.load(customerId) }

    // 回到本页即刷新（从某次接诊报告/分析返回后，档案里的接诊状态跟着更新）。跳过首个 ON_RESUME 避免与首次 load 重复。
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        var firstResume = true
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                if (firstResume) firstResume = false else viewModel.load(customerId)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val info = state.profile?.info
    val name = info?.name?.takeIf { it.isNotBlank() } ?: "顾客档案"

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
            verticalArrangement = Arrangement.spacedBy(Dimens.CardGap),
        ) {
            item {
                MeiliTopBar(
                    title = name,
                    subtitle = "顾客档案",
                    onBack = onBack,
                )
            }

            when {
                // 首屏加载（还没拿到 profile）
                state.profileLoading && state.profile == null -> item {
                    MeiliCard { InlineLoading(text = "正在调取顾客画像…") }
                }
                // profile 加载失败
                state.profileError != null && state.profile == null -> item {
                    MeiliCard {
                        EmptyHint(icon = MeiliIcons.Warn, title = "调取失败", sub = state.profileError)
                    }
                }
                else -> {
                    // ① 头部卡
                    item {
                        HeaderCard(
                            name = name,
                            info = info,
                            sessionCount = state.profile?.sessionCount,
                        )
                    }

                    // ② 累积标签
                    val tags = state.profile?.accumulatedTags.orEmpty().filter { !it.tag.isNullOrBlank() }
                    if (tags.isNotEmpty()) {
                        item { TagsCard(tags = tags) }
                    }

                    // ③ 客户价值预测
                    item {
                        ValueCard(
                            loading = state.valueLoading,
                            generating = state.valueGenerating,
                            hasContent = state.hasValueContent,
                            content = state.value?.content,
                            stale = state.value?.stale == true,
                            doneCount = state.valueDoneCount,
                            error = state.valueError,
                            onGenerate = viewModel::generateValue,
                        )
                    }

                    // ④ 陪伴时间线（点某次 → 跳该次接诊的分析报告）
                    val sessions = state.profile?.sessions.orEmpty()
                    item {
                        TimelineCard(
                            sessions = sessions,
                            count = state.profile?.sessionCount,
                            onOpenReport = onOpenReport,
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────── ① 头部卡 ───────────────────────────

/** 头像 + 姓名 + 会员号/尾号 + 「陪伴 N 次」(session_count)。 */
@Composable
private fun HeaderCard(
    name: String,
    info: CustomerProfileInfo?,
    sessionCount: Int?,
) {
    MeiliCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(15.dp),
        ) {
            AvatarLg(name = name)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp, fontWeight = FontWeight.ExtraBold),
                    color = MeiliPalette.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = idLine(info),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Normal,
                        letterSpacing = 0.sp,
                    ),
                    color = MeiliPalette.Ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            if (sessionCount != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = sessionCount.toString(),
                        style = MaterialTheme.typography.displaySmall.copy(fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
                        color = MeiliPalette.Clay,
                    )
                    Text(
                        text = "陪伴次",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = MeiliPalette.Ink3,
                    )
                }
            }
        }
    }
}

/** 会员号 / 尾号 拼一行（都没有则「新客」）。 */
private fun idLine(info: CustomerProfileInfo?): String {
    val parts = mutableListOf<String>()
    info?.memberCard?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
    info?.phoneTail?.takeIf { it.isNotBlank() }?.let { parts.add("尾号 $it") }
    return if (parts.isEmpty()) "新客" else parts.joinToString(" · ")
}

// ─────────────────────────── ② 累积标签 ───────────────────────────

/** 累积标签卡：SectionLabel + 标签 pill（tag ×count，count>1 才显示次数）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsCard(tags: List<AccumulatedTag>) {
    MeiliCard {
        SectionLabel("累积标签", icon = MeiliIcons.Star)
        Spacer(Modifier.height(11.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            tags.forEach { t ->
                val label = buildString {
                    append(t.tag.orEmpty())
                    val c = t.count ?: 0
                    if (c > 1) append(" ×$c")
                }
                StatusPill(text = label, kind = PillKind.Run)
            }
        }
    }
}

// ─────────────────────────── ③ 客户价值预测 ───────────────────────────

/**
 * 客户价值预测卡。各维度 markdown-lite 文本，标题对齐 web VALUE_TOOL：
 *  value_rebuild=客户价值评估 / battle_plan=可攻破痛点 + 作战方案 /
 *  project_plan=竞品 + 项目 + 学习清单 / biz_plan=下一步动作 + 回店规划 / advisor_match=按顾问匹配度。
 *
 * 任何登录角色都能点「生成」(后端 POST 已放开)；每次接诊分析完成后台也会自动重算。
 * 未生成 → 「生成客户价值预测」按钮；已生成但过期 → 末尾给「重新生成」。无已完成接诊则提示先去接诊。
 */
@Composable
private fun ValueCard(
    loading: Boolean,
    generating: Boolean,
    hasContent: Boolean,
    content: CustomerValueContent?,
    stale: Boolean,
    doneCount: Int,
    error: String?,
    onGenerate: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.CardGap)) {
        // 区块标题 + 过期提示
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionLabel("客户价值预测", icon = MeiliIcons.Gem)
            if (stale && hasContent) {
                Text(
                    text = "· 可能已过期",
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
                    color = MeiliPalette.HoneyText,
                )
            }
        }
        when {
            generating -> MeiliCard { InlineLoading(text = "正在生成客户价值预测…（约 20–40 秒，请稍候）") }
            loading -> MeiliCard { InlineLoading(text = "正在调取客户价值预测…") }
            hasContent && content != null -> {
                // 4 维度卡 + 顾问匹配度卡，1:1 还原 web VP_DIMS（彩色头部 + markdown-lite 正文）。
                content.valueRebuild?.takeIf { it.isNotBlank() }?.let {
                    VpCard("客户价值评估", MeiliIcons.Gem, VpGold) { MdLiteContent(it, VpGold) }
                }
                content.battlePlan?.takeIf { it.isNotBlank() }?.let {
                    VpCard("攻坚作战方案", MeiliIcons.Target, VpRed) { MdLiteContent(it, VpRed) }
                }
                content.projectPlan?.takeIf { it.isNotBlank() }?.let {
                    VpCard("项目规划 · 竞品/项目/学习清单", MeiliIcons.Doc, VpBlue) { MdLiteContent(it, VpBlue) }
                }
                content.bizPlan?.takeIf { it.isNotBlank() }?.let {
                    VpCard("经营规划 · 下一步/回店", MeiliIcons.Trend, VpGreen) { MdLiteContent(it, VpGreen) }
                }
                val matches = content.advisorMatch.orEmpty()
                    .filter { !it.advisor.isNullOrBlank() || !it.assessment.isNullOrBlank() }
                if (matches.isNotEmpty()) {
                    VpCard("顾问匹配度", MeiliIcons.Heart, VpPurple) {
                        matches.forEachIndexed { i, m ->
                            AdvisorRow(m, showDivider = i != matches.lastIndex)
                        }
                    }
                }
                // 已过期 → 给「重新生成」入口（不过期就不打扰）
                if (stale) {
                    SoftButton(
                        text = "重新生成",
                        onClick = onGenerate,
                        icon = MeiliIcons.Refresh,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            // 无已完成接诊：生成会被后端拒，直接提示
            doneCount <= 0 && error == null -> MeiliCard {
                Text(
                    text = "这位顾客还没有已完成的接诊分析，完成一次接诊分析后即可生成价值预测。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MeiliPalette.Ink3,
                )
            }
            else -> MeiliCard {
                error?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MeiliPalette.RoseText,
                    )
                    Spacer(Modifier.height(10.dp))
                }
                Text(
                    text = "还没有生成客户价值预测。点下方按钮，按这位顾客的历次接诊分析即时生成。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MeiliPalette.Ink3,
                )
                Spacer(Modifier.height(12.dp))
                SoftButton(
                    text = "生成客户价值预测",
                    onClick = onGenerate,
                    icon = MeiliIcons.Spark,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** 顾问匹配度卡内一行：圆形头像徽标 + 顾问名 + 评估（markdown-lite 渲染）。 */
@Composable
private fun AdvisorRow(m: AdvisorMatch, showDivider: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(
                    Brush.linearGradient(listOf(Color(0xFF7B5EC7), Color(0xFF9B7FE0))),
                    MeiliShapes.Pill,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = (m.advisor?.takeIf { it.isNotBlank() } ?: "?").take(1),
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
                color = Color.White,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            m.advisor?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
                    color = MeiliPalette.Ink,
                )
            }
            m.assessment?.takeIf { it.isNotBlank() }?.let {
                MdLiteContent(it, VpPurple)
            }
        }
    }
    if (showDivider) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MeiliPalette.Line),
        )
    }
}

// ─────────────────────────── ④ 陪伴时间线 ───────────────────────────

/** 陪伴时间线卡：标题（共 N 次）+ 每次接诊一行（日期 + 顾问·门店·状态）。点行 → 该次接诊报告。 */
@Composable
private fun TimelineCard(sessions: List<ProfileSession>, count: Int?, onOpenReport: (Long) -> Unit) {
    MeiliCard(tight = true) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionLabel("陪伴时间线", icon = MeiliIcons.Route)
            val n = count ?: sessions.size
            if (n > 0) {
                Text(
                    text = "（共 $n 次）",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = MeiliPalette.Ink3,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        if (sessions.isEmpty()) {
            Text(
                text = "还没有陪伴记录",
                style = MaterialTheme.typography.bodySmall,
                color = MeiliPalette.Ink3,
                modifier = Modifier.padding(vertical = 14.dp),
            )
        } else {
            sessions.forEachIndexed { idx, s ->
                TimelineRow(session = s, showDivider = idx != sessions.lastIndex, onOpenReport = onOpenReport)
            }
        }
    }
}

@Composable
private fun TimelineRow(session: ProfileSession, showDivider: Boolean, onOpenReport: (Long) -> Unit) {
    val sid = session.id
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (sid != null) Modifier.clickable { onOpenReport(sid) } else Modifier
            )
            .padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        // 状态点头像（done=叶绿对勾，其余=时钟）
        val done = session.isDone
        Box(
            modifier = Modifier
                .size(Dimens.Avatar)
                .background(
                    if (done) MeiliPalette.LeafSoft else MeiliPalette.SurfaceSoft,
                    MeiliShapes.Sm,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (done) MeiliIcons.Check else MeiliIcons.Clock,
                contentDescription = null,
                tint = if (done) MeiliPalette.LeafText else MeiliPalette.Ink4,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = session.serviceDate?.takeIf { it.isNotBlank() } ?: "—",
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp),
                color = MeiliPalette.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = timelineMeta(session),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Normal,
                    letterSpacing = 0.sp,
                ),
                color = MeiliPalette.Ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (sid != null) {
            Icon(
                MeiliIcons.ChevRight,
                contentDescription = "查看报告",
                tint = MeiliPalette.Ink4,
                modifier = Modifier.size(18.dp),
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

/** 顾问 · 门店 · 状态拼一行 meta。 */
private fun timelineMeta(session: ProfileSession): String {
    val parts = mutableListOf<String>()
    session.advisor?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
    session.storeName?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
    parts.add(statusText(session.analysisStatus))
    return parts.joinToString(" · ")
}

/** analysis_status → 顾客可见状态文案（零「录音」字样）。 */
private fun statusText(status: String?): String = when (status) {
    "done" -> "已完成"
    "running" -> "分析中"
    "queued" -> "排队中"
    "pending" -> "待分析"
    "failed" -> "分析失败"
    null, "" -> "待分析"
    else -> status
}

// ─────────────────────────── 头像 / 公共小件 ───────────────────────────

/** 大号头像（详情头部用，56dp）。 */
@Composable
private fun AvatarLg(name: String) {
    Box(
        modifier = Modifier
            .size(Dimens.AvatarLg)
            .background(MeiliPalette.ClaySoft, MeiliShapes.Md),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.take(1),
            style = MaterialTheme.typography.headlineMedium.copy(fontSize = 21.sp),
            color = MeiliPalette.ClayDeep,
        )
    }
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
            Icon(icon, contentDescription = null, tint = MeiliPalette.Ink4, modifier = Modifier.size(30.dp))
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
private fun CustomerDetailScreenPreview() {
    MeiliTheme { CustomerDetailScreen(customerId = 1L) }
}
