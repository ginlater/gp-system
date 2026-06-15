package com.airec.bledemo.ui.report

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airec.bledemo.data.model.CaseReview
import com.airec.bledemo.data.model.Competitor
import com.airec.bledemo.data.model.CustomerTag
import com.airec.bledemo.data.model.CustomerTagsResponse
import com.airec.bledemo.data.model.DealDiagnosis
import com.airec.bledemo.data.model.DealDimension
import com.airec.bledemo.data.model.Evaluation
import com.airec.bledemo.data.model.Harvest
import com.airec.bledemo.data.model.LogicChain
import com.airec.bledemo.data.model.NextSteps
import com.airec.bledemo.data.model.Overview
import com.airec.bledemo.data.model.PainPoint
import com.airec.bledemo.data.model.Persona
import com.airec.bledemo.data.model.RootCause
import com.airec.bledemo.data.model.ScoringStage
import com.airec.bledemo.designsystem.Dimens
import com.airec.bledemo.designsystem.MeiliIcons
import com.airec.bledemo.designsystem.MeiliPalette
import com.airec.bledemo.designsystem.MeiliShapes
import com.airec.bledemo.designsystem.MeiliTheme
import com.airec.bledemo.designsystem.components.Collapsible
import com.airec.bledemo.designsystem.components.GhostButton
import com.airec.bledemo.designsystem.components.MeiliButtonSize
import com.airec.bledemo.designsystem.components.PillKind
import com.airec.bledemo.designsystem.components.PrimaryButton
import com.airec.bledemo.designsystem.components.SectionLabel
import com.airec.bledemo.designsystem.components.StatusPill

/* ===================================================================
 * 11 个 PART 折叠卡（01→11），严格逐块照 warm_2.html #report。
 * 内容字段取自 SessionReport（model/SessionReport.kt）。
 * 任一字段缺失时优雅占位（EmptyPartNote）。
 * =================================================================== */

private val partGap = 12.dp

/** PART 体内通用占位：当该 PART 数据缺失时显示。 */
@Composable
private fun EmptyPartNote(text: String = "本部分暂无分析结果") {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MeiliPalette.Ink3,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
    )
}

// ─────────────────────────── PART 01 ───────────────────────────

/**
 * PART 01 全维度评估总览。对齐 web report.html 的「维度 | 我方分析判断」表，
 * 但照搬其手机端断点行为：表头隐藏、每个维度纵向成块（不做 2 列窄表，否则一列只挤两三个字）。
 * 每块 = 维度名 + 彩色标签胶囊 + 说明/彩色要点。
 * 质检评分用 [scoring] 重算的综合分（阶段均值），不读后端可能为 0/空的存量值。
 * 「可操作建议」按产品要求不在 App 渲染（改由生成 prompt 处理）。
 */
@Composable
fun Part01Overview(overview: Overview?, scoring: com.airec.bledemo.data.model.Scoring?) {
    Collapsible(title = "全维度评估总览", numberBadge = "01", modifier = Modifier.padding(bottom = partGap)) {
        if (overview == null) {
            EmptyPartNote()
            return@Collapsible
        }
        var first = true
        // 顾客价值评级
        overview.customerValue?.let { cell ->
            OvRow("顾客价值评级", divider = !first) { first = false
                cell.tag?.let { OvTagPill(it, cell.tagKind ?: "level") }
                cell.note?.let { OvNote(it) }
            }
        }
        // 痛点识别
        overview.painSummary?.let { ps ->
            OvRow("痛点识别", divider = !first) { first = false
                ps.tag?.let { OvTagPill(it, ps.tagKind ?: "warn") }
                ps.items?.takeIf { it.isNotEmpty() }?.let { items ->
                    Column(modifier = Modifier.padding(top = if (ps.tag != null) 8.dp else 0.dp)) {
                        items.forEach { OvColoredBullet(it.color, it.text.orEmpty()) }
                    }
                }
            }
        }
        // 销售问题诊断
        overview.salesDiagnosis?.let { cell ->
            if (cell.tag != null || cell.note != null) {
                OvRow("销售问题诊断", divider = !first) { first = false
                    cell.tag?.let { OvTagPill(it, cell.tagKind ?: "bad") }
                    cell.note?.let { OvNote(it) }
                }
            }
        }
        // 质检评分（重算综合分 = 各阶段分均值；阶段分 = 子项均值。无明细则退回存量值）
        val qScore = overallScoreOf(scoring) ?: overview.qualityScore?.score
        if (qScore != null) {
            OvRow("质检评分", divider = !first) { first = false
                ScoreTag(qScore)
                overview.qualityScore?.note?.let { OvNote(it) }
            }
        }
    }
}

/** 总览一个维度块：上方细分隔线 + 维度名（陶土深色加粗）+ 内容。手机端纵向铺满，不挤窄列。 */
@Composable
private fun OvRow(label: String, divider: Boolean, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    if (divider) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MeiliPalette.Line),
        )
    }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 13.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.ExtraBold),
            color = MeiliPalette.ClayDeep,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        content()
    }
}

/** 总览彩色标签胶囊（对齐 web .ov-tag）：level=陶土深 / warn=蜜 / bad=玫瑰 / good=叶绿，白字。 */
@Composable
private fun OvTagPill(text: String, kind: String) {
    val bg = when (kind.removePrefix("c-")) {
        "good" -> MeiliPalette.Leaf
        "warn" -> MeiliPalette.Honey
        "bad" -> MeiliPalette.Rose
        else -> MeiliPalette.ClayDeep   // level / 未知
    }
    Surface(shape = MeiliShapes.Xs, color = bg, contentColor = MeiliPalette.White) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = 0.3.sp),
            color = MeiliPalette.White,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 4.dp),
        )
    }
}

/** 质检评分大胶囊（对齐 web 质检评分 tag）：≥8 叶绿 / ≥6 蜜 / 否则玫瑰，白字「{分} / 10」。 */
@Composable
private fun ScoreTag(score: Double) {
    val bg = when {
        score >= 8.0 -> MeiliPalette.Leaf
        score >= 6.0 -> MeiliPalette.Honey
        else -> MeiliPalette.Rose
    }
    Surface(shape = MeiliShapes.Xs, color = bg, contentColor = MeiliPalette.White) {
        Text(
            "${formatScore(score)} / 10",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
            color = MeiliPalette.White,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
        )
    }
}

/** 总览说明小字（ov-content note）。 */
@Composable
private fun OvNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MeiliPalette.Ink2,
        modifier = Modifier.padding(top = 7.dp),
    )
}

/** 痛点识别彩色要点（对齐 web .c-{color} 的 ● 行）。 */
@Composable
private fun OvColoredBullet(color: String?, text: String) {
    val c = ovBulletColor(color)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text("●", style = MaterialTheme.typography.bodySmall, color = c, modifier = Modifier.padding(end = 7.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = c,
            modifier = Modifier.weight(1f),
        )
    }
}

// ─────────────────────────── PART 02 ───────────────────────────

@Composable
fun Part02Persona(persona: Persona?) {
    Collapsible(title = "顾客真实画像重建", numberBadge = "02", initiallyOpen = false, collapsedHint = "点击展开", reportKey = "part2", modifier = Modifier.padding(bottom = partGap)) {
        if (persona == null || (persona.signals.isNullOrEmpty() && persona.summary.isNullOrBlank() && persona.lead.isNullOrBlank())) {
            EmptyPartNote()
            return@Collapsible
        }
        persona.lead?.let { SmallNote(it, modifier = Modifier.padding(bottom = 9.dp)) }
        persona.signals?.forEach { sig ->
            AdviceBlock(
                text = sig.signal?.let { "顾客原话「$it」" } ?: "",
                secondary = sig.interpretation?.let { "→ 销售判断：$it" },
                accent = MeiliPalette.Clay,
            )
        }
        persona.summary?.let {
            SageBox("综合判断：$it", modifier = Modifier.padding(top = 11.dp))
        }
    }
}

// ─────────────────────────── PART 03 ───────────────────────────

@Composable
fun Part03RootCause(rootCause: RootCause?) {
    Collapsible(title = "接诊失分根因定位", numberBadge = "03", initiallyOpen = false, collapsedHint = "点击展开", modifier = Modifier.padding(bottom = partGap)) {
        if (rootCause == null) {
            EmptyPartNote()
            return@Collapsible
        }
        rootCause.headline?.let {
            ReportBanner(it, BannerKind.Danger, MeiliIcons.Warn, modifier = Modifier.padding(bottom = 11.dp))
        }
        val prod = rootCause.productDimension?.joinToString("、").orEmpty()
        val prob = rootCause.problemDimension?.joinToString("、").orEmpty()
        if (prod.isNotEmpty() || prob.isNotEmpty()) {
            TwoColBoxes(
                left = ColBoxData("产品维度（师做的）", MeiliIcons.Doc, MeiliPalette.SageDeep, prod.ifEmpty { "—" }),
                right = ColBoxData("问题维度（顾客要听的）", MeiliIcons.Heart, MeiliPalette.ClayDeep, prob.ifEmpty { "—" }),
                modifier = Modifier.padding(vertical = 11.dp),
            )
        }
        rootCause.gapNote?.let {
            AdviceBlock(text = it, leadBold = "差距的本质：", accent = MeiliPalette.Honey)
        }
    }
}

// ─────────────────────────── PART 04 ───────────────────────────

@Composable
fun Part04PainPoints(painPoints: List<PainPoint>?) {
    Collapsible(title = "可攻破痛点 · 完整作战方案", numberBadge = "03", initiallyOpen = false, collapsedHint = "点击展开", reportKey = "part4", modifier = Modifier.padding(bottom = partGap)) {
        if (painPoints.isNullOrEmpty()) {
            EmptyPartNote()
            return@Collapsible
        }
        painPoints.forEachIndexed { idx, pp ->
            PainPointCard(pp, idx)
        }
    }
}

private val stepColors = listOf(
    MeiliPalette.ClayTint to MeiliPalette.ClayDeep,
    MeiliPalette.SageTint to MeiliPalette.SageDeep,
    MeiliPalette.HoneySoft to MeiliPalette.HoneyText,
    MeiliPalette.LeafSoft to MeiliPalette.LeafText,
)

@Composable
private fun PainPointCard(pp: PainPoint, index: Int) {
    Surface(
        shape = MeiliShapes.Md,
        color = MeiliPalette.SurfaceSoft,
        border = BorderStroke(Dimens.BorderThin, MeiliPalette.Line),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (index == 0) 0.dp else 13.dp),
    ) {
        Column(modifier = Modifier.padding(13.dp)) {
            // 标题 + badge
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    pp.title.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.ExtraBold, fontSize = 13.5f.sp),
                    color = MeiliPalette.Ink,
                    modifier = Modifier.weight(1f),
                )
                pp.badge?.let { PainBadge(it, index) }
            }
            // steps
            pp.steps?.forEachIndexed { si, step ->
                Row(
                    modifier = Modifier.padding(top = if (si == 0) 11.dp else 9.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val (bg, fg) = stepColors[si % stepColors.size]
                    Box(
                        modifier = Modifier
                            .width(58.dp)
                            .background(bg, MeiliShapes.Xs)
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            step.label.orEmpty(),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.5f.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.sp,
                            ),
                            color = fg,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Text(
                        step.body.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MeiliPalette.Ink,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            // 可选策略表
            pp.strategyTable?.forEach { st ->
                AdviceBlock(text = st.logic.orEmpty(), leadBold = st.strategy, accent = MeiliPalette.Clay)
            }
        }
    }
}

@Composable
private fun PainBadge(text: String, index: Int) {
    val bg = when (index % 3) {
        0 -> MeiliPalette.Clay
        1 -> MeiliPalette.SageDeep
        else -> MeiliPalette.Honey
    }
    val icon = when (index % 3) {
        0 -> MeiliIcons.Target
        1 -> MeiliIcons.Heart
        else -> MeiliIcons.Gem
    }
    Surface(shape = MeiliShapes.Pill, color = bg, contentColor = MeiliPalette.White, modifier = Modifier.padding(start = 6.dp)) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MeiliPalette.White, modifier = Modifier.size(12.dp))
            Text(
                text,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5f.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.sp),
                color = MeiliPalette.White,
            )
        }
    }
}

// ─────────────────────────── PART 05 ───────────────────────────

@Composable
fun Part05Harvest(harvest: Harvest?) {
    Collapsible(title = "项目后价值收割 · 黄金窗口标准流程", numberBadge = "04", initiallyOpen = false, collapsedHint = "点击展开", reportKey = "part5", modifier = Modifier.padding(bottom = partGap)) {
        if (harvest == null || harvest.steps.isNullOrEmpty()) {
            EmptyPartNote()
            return@Collapsible
        }
        harvest.intro?.let {
            ReportBanner(it, BannerKind.Info, MeiliIcons.Spark, modifier = Modifier.padding(bottom = 13.dp))
        }
        harvest.steps.forEachIndexed { i, step ->
            Column {
                Row(
                    modifier = Modifier.padding(vertical = 11.dp),
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Surface(shape = MeiliShapes.Pill, color = MeiliPalette.ClayTint, contentColor = MeiliPalette.ClayDeep) {
                        Text(
                            "STEP ${i + 1}",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5f.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                        // 只展示步骤标题（话术 body 一律不显示，老报告也不再露出）
                        step.title?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.ExtraBold), color = MeiliPalette.Ink)
                        }
                    }
                }
                if (i != harvest.steps.lastIndex) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MeiliPalette.Line),
                    )
                }
            }
        }
    }
}

// ─────────────────────────── PART 06 ───────────────────────────

@Composable
fun Part06Cases(cases: List<CaseReview>?, casesSummary: String?, onSeek: (Int, Int) -> Unit) {
    Collapsible(title = "关键 Case 复盘 · 三层分析", numberBadge = "05", initiallyOpen = false, collapsedHint = "点击展开", reportKey = "part6", modifier = Modifier.padding(bottom = partGap)) {
        if (cases.isNullOrEmpty()) {
            EmptyPartNote()
            return@Collapsible
        }
        cases.forEachIndexed { i, c ->
            CaseCard(c, i + 1, onSeek)
        }
        casesSummary?.let {
            SageBox("一句话总结：$it", dark = true, modifier = Modifier.padding(top = 11.dp))
        }
    }
}

@Composable
private fun CaseCard(c: CaseReview, no: Int, onSeek: (Int, Int) -> Unit) {
    val (pillText, pillKind) = when (c.kind) {
        "good" -> "做对" to PillKind.Ok
        "miss" -> "遗漏" to PillKind.Warn
        else -> "做错" to PillKind.Danger
    }
    Column(modifier = Modifier.padding(bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                c.title ?: "Case $no",
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.sp),
                color = MeiliPalette.Ink,
            )
            StatusPill(pillText, pillKind)
        }
        // 时间戳跳播
        val ts = c.timestampLabel ?: c.timestampSeconds?.let { formatSeconds(it.toInt()) }
        if (ts != null) {
            Surface(
                shape = MeiliShapes.Pill,
                color = MeiliPalette.Clay,
                contentColor = MeiliPalette.White,
                modifier = Modifier.padding(vertical = 9.dp),
            ) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 13.dp, vertical = 7.dp)
                        .clickableSeek { onSeek(c.timestampSeconds?.toInt() ?: 0, (c.segment ?: 1) - 1) },
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(MeiliIcons.Play, contentDescription = null, tint = MeiliPalette.White, modifier = Modifier.size(14.dp))
                    Text(ts, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), color = MeiliPalette.White)
                }
            }
        }
        c.quote?.let { AdviceBlock(text = "顾客原话「$it」", accent = MeiliPalette.Clay) }
        c.surface?.let { Bullet("表层做法：$it") }
        c.deep?.let { Bullet("深层问题：$it") }
        c.improve?.let { Bullet("可优化方向：$it") }
    }
}

// ─────────────────────────── PART 07 ───────────────────────────

@Composable
fun Part07LogicChain(logicChain: LogicChain?) {
    Collapsible(title = "逻辑总结 · 顾问能力训练路径", numberBadge = "06", initiallyOpen = false, collapsedHint = "点击展开", reportKey = "part7", modifier = Modifier.padding(bottom = partGap)) {
        if (logicChain == null) {
            EmptyPartNote()
            return@Collapsible
        }
        SectionLabel("接诊失败的底层逻辑链", icon = MeiliIcons.Trend, modifier = Modifier.padding(bottom = 8.dp))
        logicChain.badChain?.let {
            LogicBlock("实际思维", it, logicChain.badChainNote, bad = true)
        }
        logicChain.goodChain?.let {
            LogicBlock("正确思维", it, null, bad = false)
        }
        logicChain.missingStep?.let {
            ReportBanner("缺失的关键一步：$it", BannerKind.Warn, MeiliIcons.Warn, modifier = Modifier.padding(vertical = 13.dp))
        }
        if (!logicChain.training.isNullOrEmpty()) {
            SectionLabel("能力训练路径", icon = MeiliIcons.Route, modifier = Modifier.padding(bottom = 8.dp))
            logicChain.training.forEach { t ->
                CapCard(stage = t.stage.orEmpty(), issue = t.issue, skill = t.skill)
            }
        }
    }
}

@Composable
private fun LogicBlock(label: String, body: String, note: String?, bad: Boolean) {
    val bg = if (bad) MeiliPalette.RoseSoft else MeiliPalette.LeafSoft
    val border = if (bad) MeiliPalette.RoseLine else MeiliPalette.LeafLine
    val lblBg = if (bad) MeiliPalette.Rose else MeiliPalette.LeafText
    Surface(
        shape = MeiliShapes.Sm,
        color = bg,
        border = BorderStroke(Dimens.BorderThin, border),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = if (bad) 9.dp else 13.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 13.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Surface(shape = MeiliShapes.Pill, color = lblBg, contentColor = MeiliPalette.White, modifier = Modifier.padding(end = 7.dp, top = 1.dp)) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5f.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.sp),
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                    )
                }
                Text(body, style = MaterialTheme.typography.bodySmall, color = MeiliPalette.Ink, modifier = Modifier.weight(1f))
            }
            note?.let {
                Text(it, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal), color = MeiliPalette.RoseText, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun CapCard(stage: String, issue: String?, skill: String?) {
    Surface(
        shape = MeiliShapes.Sm,
        color = MeiliPalette.SurfaceSoft,
        border = BorderStroke(Dimens.BorderThin, MeiliPalette.Line),
        modifier = Modifier.fillMaxWidth().padding(bottom = 9.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp)) {
            Text(stage, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.ExtraBold), color = MeiliPalette.ClayDeep, modifier = Modifier.padding(bottom = 7.dp))
            issue?.let { CapRow("本次问题", it, valueColor = MeiliPalette.RoseText) }
            skill?.let { CapRow("需训练能力", it, valueColor = MeiliPalette.Ink) }
        }
    }
}

@Composable
private fun CapRow(key: String, value: String, valueColor: Color) {
    Row(modifier = Modifier.padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(
            key,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
            color = MeiliPalette.Ink3,
            modifier = Modifier.width(62.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall, color = valueColor, modifier = Modifier.weight(1f))
    }
}

// ─────────────────────────── PART 08 ───────────────────────────

@Composable
fun Part08NextSteps(nextSteps: NextSteps?) {
    Collapsible(title = "下一步动作 · 回店规划", numberBadge = "07", initiallyOpen = false, collapsedHint = "点击展开", reportKey = "part8", modifier = Modifier.padding(bottom = partGap)) {
        if (nextSteps == null) {
            EmptyPartNote()
            return@Collapsible
        }
        if (!nextSteps.returnScripts.isNullOrEmpty()) {
            SectionLabel("回店切入角度", icon = MeiliIcons.Comment, modifier = Modifier.padding(bottom = 8.dp))
            // 只展示切入角度大标题（话术正文已下线）
            nextSteps.returnScripts.forEach { s ->
                val head = s.title?.takeIf { it.isNotBlank() } ?: s.body.orEmpty()
                if (head.isNotBlank()) AdviceBlock(text = head, accent = MeiliPalette.Clay)
            }
        }
        if (!nextSteps.priorityProjects.isNullOrEmpty()) {
            SectionLabel("优先推荐项目", icon = MeiliIcons.Check, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
            nextSteps.priorityProjects.forEachIndexed { i, p ->
                NumberedRow(i + 1, p.name.orEmpty(), p.desc.orEmpty())
            }
        }
        if (!nextSteps.painEntryScripts.isNullOrEmpty()) {
            SectionLabel("针对痛点的进店切入", icon = MeiliIcons.Target, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
            // 只展示痛点名称（entry/principle/direction/sales_link 话术已下线）
            nextSteps.painEntryScripts.forEach { s ->
                s.painName?.takeIf { it.isNotBlank() }?.let { AdviceBlock(text = it) }
            }
        }
        val qa = (nextSteps.medicalObjections ?: emptyList()) + (nextSteps.objectionQa ?: emptyList())
        if (qa.isNotEmpty()) {
            SectionLabel("泛医疗异议应答库", icon = MeiliIcons.Info, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
            qa.forEach { item ->
                QaBlock(item.question.orEmpty(), item.reply.orEmpty())
            }
        }
    }
}

@Composable
private fun NumberedRow(no: Int, name: String, desc: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier.size(24.dp).background(MeiliPalette.ClayTint, MeiliShapes.Xs),
            contentAlignment = Alignment.Center,
        ) {
            Text(no.toString(), style = MeiliTheme.summaryStyle.copy(fontSize = 12.sp), color = MeiliPalette.ClayDeep)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.ExtraBold), color = MeiliPalette.Ink)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MeiliPalette.Ink2, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun QaBlock(q: String, a: String) {
    Surface(shape = MeiliShapes.Sm, color = MeiliPalette.SurfaceSoft, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp)) {
            Text("Q：$q", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.ExtraBold), color = MeiliPalette.ClayDeep, modifier = Modifier.padding(bottom = 5.dp))
            Text(a, style = MaterialTheme.typography.bodySmall, color = MeiliPalette.Ink)
        }
    }
}

// ─────────────────────────── PART 09 ───────────────────────────

@Composable
fun Part09Competitors(competitors: List<Competitor>?) {
    Collapsible(title = "竞品分析 · 顾问售后学习清单", numberBadge = "08", initiallyOpen = false, collapsedHint = "点击展开", reportKey = "part9", modifier = Modifier.padding(bottom = partGap)) {
        if (competitors.isNullOrEmpty()) {
            EmptyPartNote()
            return@Collapsible
        }
        SmallNote("顾客本次提到的外部项目 / 机构 / 品牌，顾问需在售后了解清楚，下次顾客再提时能接得住。", modifier = Modifier.padding(bottom = 11.dp))
        competitors.forEach { cp -> CompetitorCard(cp) }
    }
}

@Composable
private fun CompetitorCard(cp: Competitor) {
    Surface(
        shape = MeiliShapes.Sm,
        color = MeiliPalette.SurfaceSoft,
        border = BorderStroke(Dimens.BorderThin, MeiliPalette.Line),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 13.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                Text(cp.item.orEmpty(), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.ExtraBold), color = MeiliPalette.Ink)
                cp.type?.let { StatusPill(it, PillKind.Neutral) }
            }
            cp.customerQuote?.let { CapRow("顾客原话", it, valueColor = MeiliPalette.Ink) }
            cp.competitorLearn?.let { CapRow("顾问需学习", it, valueColor = MeiliPalette.Ink) }
        }
    }
}

// ─────────────────────────── PART 10 ───────────────────────────

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun Part10Tags(reportTags: List<CustomerTag>?, dynamic: CustomerTagsResponse?) {
    Collapsible(title = "顾客标签 · 画像积累", numberBadge = "10", initiallyOpen = false, collapsedHint = "点击展开", modifier = Modifier.padding(bottom = partGap)) {
        val current = dynamic?.currentTags
        val history = dynamic?.history ?: reportTags
        val hasCurrent = !current.isNullOrEmpty()
        val hasHistory = !history.isNullOrEmpty()
        if (!hasCurrent && !hasHistory) {
            EmptyPartNote()
            return@Collapsible
        }
        if (hasCurrent) {
            SectionLabel("本次新增", icon = MeiliIcons.Star, modifier = Modifier.padding(bottom = 9.dp))
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                current!!.forEach { t -> TagChip(text = (t.tag.orEmpty()) + if (t.isNew == true) " · 新增" else "", isNew = t.isNew == true) }
            }
        }
        if (hasHistory) {
            SectionLabel("历次累积（×n = 有 n 次陪伴提到）", icon = MeiliIcons.Album, modifier = Modifier.padding(top = if (hasCurrent) 16.dp else 0.dp, bottom = 9.dp))
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                history!!.forEach { t -> TagChip(text = t.tag.orEmpty(), count = t.count) }
            }
        }
    }
}

@Composable
private fun TagChip(text: String, isNew: Boolean = false, count: Int? = null) {
    val bg = if (isNew) MeiliPalette.LeafSoft else MeiliPalette.ClayTint
    val fg = if (isNew) MeiliPalette.LeafText else MeiliPalette.ClayDeep
    val border = if (isNew) MeiliPalette.LeafLine else MeiliPalette.ClaySoft
    Surface(shape = MeiliShapes.Pill, color = bg, contentColor = fg, border = BorderStroke(Dimens.BorderThin, border)) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), color = fg)
            if (count != null && count > 0) {
                Text(" ×$count", style = MaterialTheme.typography.labelSmall, color = MeiliPalette.Ink3, modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}

// ─────────────────────────── PART 11 ───────────────────────────

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun Part11DealDiagnosis(deal: DealDiagnosis?) {
    Collapsible(title = "成交诊断 · 5 维度判断", numberBadge = "09", initiallyOpen = false, collapsedHint = "点击展开", reportKey = "part11", modifier = Modifier.padding(bottom = partGap)) {
        if (deal == null) {
            EmptyPartNote()
            return@Collapsible
        }
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 11.dp),
        ) {
            deal.dealResult?.let {
                if (it) StatusPill("已成交", PillKind.Ok, icon = MeiliIcons.Check)
                else StatusPill("未成交", PillKind.Danger, icon = MeiliIcons.Warn)
            }
            // 加前缀让单独的「无」「留意」自解释，不再没头没尾。成交结果已够清楚，不加。
            deal.dealAmount?.let { StatusPill("成交金额：$it", PillKind.Warn) }
            deal.riskLevel?.let { StatusPill("差评风险：${riskLabel(it)}", PillKind.Clay) }
        }
        deal.dimensions?.let { d ->
            val rows = listOf(
                "顾客被打动" to d.customerMoved,
                "认可方案" to d.customerAgreed,
                "效果感受到位" to d.effectSatisfied,
                "价格被匹配" to d.priceMatched,
                "紧迫感建立" to d.urgencyBuilt,
            ).filter { it.second != null }
            rows.forEachIndexed { i, (label, dim) ->
                val (txt, color) = dimStatus(dim!!)
                val note = dim.note?.takeIf { it.isNotBlank() }
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 9.dp, bottom = if (note == null) 9.dp else 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(label, style = MaterialTheme.typography.bodySmall, color = MeiliPalette.Ink2)
                        Text(
                            txt,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                            color = color,
                        )
                    }
                    // 维度详细解读（引用原话）——之前数据里有但没展示
                    note?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5f.sp, lineHeight = 19.sp),
                            color = MeiliPalette.Ink3,
                            modifier = Modifier.padding(bottom = 10.dp),
                        )
                    }
                    if (i != rows.lastIndex) {
                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(MeiliPalette.Line))
                    }
                }
            }
        }
        // 只有真有风险文字才显示警示框（risk_text 为空/空白时不显示空框）
        deal.riskText?.takeIf { it.isNotBlank() }?.let {
            ReportBanner(it, BannerKind.Warn, MeiliIcons.Warn, modifier = Modifier.padding(top = 13.dp))
        }
    }
}

// ─────────────────────────── 老板/专家点评 ───────────────────────────

/**
 * 老板/专家点评（report.html boss-section）。
 * 顶部输入框 + 「提交点评」；下方列表（倒序），每条可「删除」。
 * 列表数据 [evaluations] 由 VM 经 repo.evaluations 拉；提交走 [onSubmit]、删除走 [onDelete]。
 */
@Composable
fun BossCommentCard(
    evaluations: List<Evaluation>,
    submitting: Boolean,
    onSubmit: (String) -> Unit,
    onDelete: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var input by remember { mutableStateOf(TextFieldValue("")) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MeiliShapes.Lg,
        color = MeiliPalette.Surface,
        contentColor = MeiliPalette.Ink,
        border = BorderStroke(Dimens.BorderThin, MeiliPalette.LineSoft),
        shadowElevation = Dimens.Elev2,
    ) {
        Column(modifier = Modifier.padding(Dimens.CardPad)) {
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(MeiliIcons.Comment, contentDescription = null, tint = MeiliPalette.Clay, modifier = Modifier.size(22.dp))
                Text("老板点评 · 专家点评记录", style = MaterialTheme.typography.titleLarge, color = MeiliPalette.Ink)
            }

            // ---- 新增点评输入 ----
            SmallNote("新增点评（提交后追加到下方）", modifier = Modifier.padding(top = 14.dp, bottom = 7.dp))
            Surface(
                shape = MeiliShapes.Sm,
                color = MeiliPalette.SurfaceSoft,
                border = BorderStroke(Dimens.BorderField, MeiliPalette.Line),
                modifier = Modifier.fillMaxWidth(),
            ) {
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    enabled = !submitting,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MeiliPalette.Ink),
                    cursorBrush = SolidColor(MeiliPalette.Clay),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 72.dp)
                        .padding(horizontal = 13.dp, vertical = 11.dp),
                    decorationBox = { inner ->
                        if (input.text.isEmpty()) {
                            Text(
                                "在这里输入点评……例如：这位顾客性格慢热，赞美式破冰其实不太适合……",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MeiliPalette.Ink4,
                            )
                        }
                        inner()
                    },
                )
            }
            PrimaryButton(
                text = if (submitting) "提交中…" else "提交点评",
                onClick = {
                    onSubmit(input.text)
                    input = TextFieldValue("")
                },
                icon = MeiliIcons.Check,
                size = MeiliButtonSize.Small,
                enabled = !submitting && input.text.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 11.dp),
            )

            // ---- 已有点评列表 ----
            if (evaluations.isEmpty()) {
                SmallNote("暂无点评记录", modifier = Modifier.padding(top = 14.dp))
            } else {
                evaluations.forEach { ev ->
                    Surface(
                        shape = MeiliShapes.Md,
                        color = MeiliPalette.SurfaceSoft,
                        modifier = Modifier.fillMaxWidth().padding(top = 13.dp),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 15.dp, vertical = 14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    listOfNotNull(ev.author, ev.createdAt).joinToString(" · ").ifEmpty { "点评" },
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal),
                                    color = MeiliPalette.Ink3,
                                    modifier = Modifier.weight(1f),
                                )
                                ev.id?.let { eid ->
                                    GhostButton(
                                        text = "删除",
                                        onClick = { onDelete(eid) },
                                        icon = MeiliIcons.Trash,
                                        size = MeiliButtonSize.Xs,
                                    )
                                }
                            }
                            Text(
                                ev.comment.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MeiliPalette.Ink,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────── helpers ───────────────────────────

private fun formatScore(score: Double): String =
    if (score == score.toLong().toDouble()) score.toLong().toString() else String.format("%.1f", score)

// ── 质检评分重算（对齐 report.html:177-196）：阶段分=子项均值，综合分=阶段分均值，保留1位 ──
internal fun round1(d: Double): Double = kotlin.math.round(d * 10.0) / 10.0

/** 阶段分 = 子项分均值（保留1位）；无子项分则退回存量阶段分。 */
internal fun stageScoreOf(stage: ScoringStage): Double? {
    val subs = stage.sub?.mapNotNull { it.score }
    return if (!subs.isNullOrEmpty()) round1(subs.average()) else stage.score
}

/** 综合分 = 各阶段分均值（保留1位）；无阶段明细则退回存量综合分。 */
internal fun overallScoreOf(scoring: com.airec.bledemo.data.model.Scoring?): Double? {
    val stageScores = scoring?.stages?.mapNotNull { stageScoreOf(it) }
    return if (!stageScores.isNullOrEmpty()) round1(stageScores.average()) else scoring?.overall
}

/** 总览痛点要点彩色（web .c-red/.c-green/.c-blue/.c-orange/.c-gold）。 */
internal fun ovBulletColor(color: String?): Color = when (color) {
    "red" -> MeiliPalette.RoseText
    "green" -> MeiliPalette.LeafText
    "blue" -> MeiliPalette.SageDeep
    "orange" -> MeiliPalette.HoneyText
    "gold" -> MeiliPalette.Honey
    else -> MeiliPalette.Ink2
}

private fun formatSeconds(sec: Int): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "0:%02d:%02d".format(m, s)
}

private fun riskLabel(level: String): String = when (level) {
    "high" -> "高风险"
    "medium" -> "留意"
    "low" -> "低风险"
    else -> level
}

private fun dimStatus(dim: DealDimension): Pair<String, Color> = when (dim.status) {
    "ok" -> "到位" to MeiliPalette.Leaf
    "partial" -> "部分" to MeiliPalette.Honey
    "missing" -> "缺失" to MeiliPalette.Rose
    else -> (dim.note ?: "—") to MeiliPalette.Ink2
}

private fun Modifier.clickableSeek(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)
