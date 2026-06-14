package com.airec.bledemo.data.model

import com.squareup.moshi.Json

/* ===================================================================
 * 会话详情 + 分析报告
 *
 * GET /api/session/<sid> 返回 sessions 整行 + recordings[] + evaluations[]
 * + report（= analysis_result 解析后的结构化 JSON，含 11 个 PART 所需结构）。
 *
 * 字段名核对自 webapp.py(TASK_REGISTRY result_keys) 与 report.html 的渲染逻辑。
 * report 是 AI 生成的结构化 JSON，所有层级一律【可空】，缺字段不崩。
 *
 * 11 个 PART ↔ report 顶层 key（report.html）：
 *  01 全维度评估总览       overview
 *  02 顾客真实画像重建     persona
 *  03 接诊失分根因定位     root_cause
 *  04 可攻破痛点·作战方案  pain_points[]
 *  05 项目后价值收割窗口   harvest
 *  06 关键 Case 复盘       cases[] + cases_summary
 *  07 顾问能力训练路径     logic_chain (含 training[])
 *  08 下一步动作·回店规划  next_steps
 *  09 竞品分析·学习清单    external_signals
 *  10 顾客标签·画像积累    customer_tags（PART10 动态走 /customer_tags 接口）
 *  11 成交诊断·维度判断    deal_diagnosis
 * =================================================================== */

// ─────────────────────────── 会话外层 ───────────────────────────

/** GET /api/session/<sid> 顶层响应（sessions 行 + 衍生）。 */
data class SessionDetail(
    @Json(name = "id") val id: Long? = null,
    @Json(name = "advisor") val advisor: String? = null,
    @Json(name = "customer") val customer: String? = null,
    @Json(name = "customer_id") val customerId: Long? = null,
    @Json(name = "company_id") val companyId: Long? = null,
    @Json(name = "service_date") val serviceDate: String? = null,
    @Json(name = "locked") val locked: Int? = null,
    @Json(name = "analysis_status") val analysisStatus: String? = null,
    @Json(name = "analysis_progress") val analysisProgress: String? = null,
    @Json(name = "analysis_error") val analysisError: String? = null,
    @Json(name = "display_status") val displayStatus: String? = null,   // running|queued|done|failed|stuck|idle
    @Json(name = "delete_request_pending") val deleteRequestPending: Boolean = false,  // 有待审批的删除申请→可撤销
    @Json(name = "recordings") val recordings: List<SessionRecording>? = null,
    @Json(name = "evaluations") val evaluations: List<Evaluation>? = null,
    @Json(name = "report") val report: SessionReport? = null,
    @Json(name = "error") val error: String? = null,
)

/** GET /api/session/<sid> recordings[]（含逐字转写 asr_transcript）。 */
data class SessionRecording(
    @Json(name = "id") val id: Long,
    @Json(name = "oss_key") val ossKey: String? = null,
    @Json(name = "recorded_at") val recordedAt: String? = null,
    @Json(name = "duration_label") val durationLabel: String? = null,
    @Json(name = "size_bytes") val sizeBytes: Long? = null,
    @Json(name = "source") val source: String? = null,
    @Json(name = "customer") val customer: String? = null,
    @Json(name = "asr_status") val asrStatus: String? = null,
    @Json(name = "asr_transcript") val asrTranscript: String? = null,   // 逐字转写(JSON/文本，UI 解析)
    @Json(name = "asr_error") val asrError: String? = null,
    @Json(name = "asr_started_at") val asrStartedAt: String? = null,
    @Json(name = "asr_finished_at") val asrFinishedAt: String? = null,
    @Json(name = "asr_speaker_count") val asrSpeakerCount: Int? = null,
    @Json(name = "asr_speaker_warning") val asrSpeakerWarning: Int? = null,
    @Json(name = "speaker_confirmed") val speakerConfirmed: Int? = null,
    @Json(name = "advisor") val advisor: String? = null,
    @Json(name = "uploader_user_id") val uploaderUserId: Long? = null,
    @Json(name = "store_id") val storeId: Long? = null,
    // 仅对有权收听者签发，否则 null（转写/报告仍可见）
    @Json(name = "audio_url") val audioUrl: String? = null,
)

/** 老板/专家点评（PART 11 区域下方）。 */
data class Evaluation(
    @Json(name = "id") val id: Long? = null,
    @Json(name = "session_id") val sessionId: Long? = null,
    @Json(name = "comment") val comment: String? = null,
    @Json(name = "author") val author: String? = null,
    @Json(name = "created_at") val createdAt: String? = null,
)

// ─────────────────────────── report：11 个 PART ───────────────────────────

/** 结构化报告 JSON 顶层（report 字段）。 */
data class SessionReport(
    @Json(name = "overview") val overview: Overview? = null,                       // PART 01
    @Json(name = "persona") val persona: Persona? = null,                          // PART 02
    @Json(name = "root_cause") val rootCause: RootCause? = null,                   // PART 03
    @Json(name = "pain_points") val painPoints: List<PainPoint>? = null,           // PART 04
    @Json(name = "harvest") val harvest: Harvest? = null,                          // PART 05
    @Json(name = "cases") val cases: List<CaseReview>? = null,                     // PART 06
    @Json(name = "cases_summary") val casesSummary: String? = null,                // PART 06
    @Json(name = "logic_chain") val logicChain: LogicChain? = null,                // PART 07
    @Json(name = "next_steps") val nextSteps: NextSteps? = null,                   // PART 08
    @Json(name = "external_signals") val externalSignals: ExternalSignals? = null, // PART 09
    @Json(name = "customer_tags") val customerTags: List<CustomerTag>? = null,     // PART 10（本次标签）
    @Json(name = "deal_diagnosis") val dealDiagnosis: DealDiagnosis? = null,       // PART 11
    @Json(name = "scoring") val scoring: Scoring? = null,                          // 质检评分(T5)，挂在 PART 01 详情里
)

// ── PART 01 全维度评估总览 ──
data class Overview(
    @Json(name = "customer_value") val customerValue: OverviewCell? = null,
    @Json(name = "pain_summary") val painSummary: OverviewPainSummary? = null,
    @Json(name = "sales_diagnosis") val salesDiagnosis: OverviewCell? = null,
    @Json(name = "quality_score") val qualityScore: OverviewScore? = null,
    @Json(name = "suggestions") val suggestions: List<String>? = null,
)

data class OverviewCell(
    @Json(name = "tag") val tag: String? = null,
    @Json(name = "tag_kind") val tagKind: String? = null,   // level | warn | bad ...
    @Json(name = "note") val note: String? = null,
)

data class OverviewPainSummary(
    @Json(name = "tag") val tag: String? = null,
    @Json(name = "tag_kind") val tagKind: String? = null,
    @Json(name = "items") val items: List<OverviewPainItem>? = null,
)

data class OverviewPainItem(
    @Json(name = "color") val color: String? = null,
    @Json(name = "text") val text: String? = null,
)

data class OverviewScore(
    @Json(name = "score") val score: Double? = null,
    @Json(name = "note") val note: String? = null,
)

// ── PART 02 顾客真实画像重建 ──
data class Persona(
    @Json(name = "lead") val lead: String? = null,
    @Json(name = "signals") val signals: List<PersonaSignal>? = null,
    @Json(name = "summary") val summary: String? = null,
)

data class PersonaSignal(
    @Json(name = "signal") val signal: String? = null,            // 顾客原话
    @Json(name = "interpretation") val interpretation: String? = null,  // 销售判断
)

// ── PART 03 接诊失分根因定位 ──
data class RootCause(
    @Json(name = "lead") val lead: String? = null,
    @Json(name = "headline") val headline: String? = null,
    @Json(name = "product_dimension") val productDimension: List<String>? = null,
    @Json(name = "problem_dimension") val problemDimension: List<String>? = null,
    @Json(name = "gap_note") val gapNote: String? = null,
)

// ── PART 04 可攻破痛点·完整作战方案 ──
data class PainPoint(
    @Json(name = "title") val title: String? = null,
    @Json(name = "badge") val badge: String? = null,   // 最强突破口 / 最佳情感连接点 / 最高价值突破口
    @Json(name = "lead") val lead: String? = null,
    @Json(name = "steps") val steps: List<PainStep>? = null,
    @Json(name = "strategy_table") val strategyTable: List<PainStrategy>? = null,
)

data class PainStep(
    @Json(name = "label") val label: String? = null,
    @Json(name = "body") val body: String? = null,
)

data class PainStrategy(
    @Json(name = "strategy") val strategy: String? = null,
    @Json(name = "logic") val logic: String? = null,
)

// ── PART 05 项目后价值收割·黄金窗口 ──
data class Harvest(
    @Json(name = "intro") val intro: String? = null,
    @Json(name = "steps") val steps: List<HarvestStep>? = null,
)

data class HarvestStep(
    @Json(name = "title") val title: String? = null,
    @Json(name = "body") val body: String? = null,
)

// ── PART 06 关键 Case 复盘 ──
data class CaseReview(
    @Json(name = "kind") val kind: String? = null,         // good | bad | miss
    @Json(name = "title") val title: String? = null,
    @Json(name = "quote") val quote: String? = null,
    // AI 可能产出小数秒（如 2.81）；用 Double 兜底，避免 Moshi「Expected an int」整份报告解析失败
    @Json(name = "timestamp_seconds") val timestampSeconds: Double? = null,
    @Json(name = "timestamp_label") val timestampLabel: String? = null,
    @Json(name = "segment") val segment: Int? = null,
    @Json(name = "surface") val surface: String? = null,   // 表层做法/问题
    @Json(name = "deep") val deep: String? = null,         // 深层问题/值得保留
    @Json(name = "improve") val improve: String? = null,   // 正确做法/可优化方向
)

// ── PART 07 逻辑链 + 顾问能力训练路径 ──
data class LogicChain(
    @Json(name = "bad_chain") val badChain: String? = null,
    @Json(name = "bad_chain_note") val badChainNote: String? = null,
    @Json(name = "good_chain") val goodChain: String? = null,
    @Json(name = "missing_step") val missingStep: String? = null,
    @Json(name = "training") val training: List<TrainingItem>? = null,
)

data class TrainingItem(
    @Json(name = "stage") val stage: String? = null,
    @Json(name = "issue") val issue: String? = null,
    @Json(name = "skill") val skill: String? = null,
)

// ── PART 08 下一步动作·回店规划 ──
data class NextSteps(
    @Json(name = "return_scripts") val returnScripts: List<NextTitledBody>? = null,
    @Json(name = "priority_projects") val priorityProjects: List<PriorityProject>? = null,
    @Json(name = "medical_objections") val medicalObjections: List<ObjectionQa>? = null,
    @Json(name = "objection_qa") val objectionQa: List<ObjectionQa>? = null,
    @Json(name = "pain_entry_scripts") val painEntryScripts: List<PainEntryScript>? = null,
)

data class NextTitledBody(
    @Json(name = "title") val title: String? = null,
    @Json(name = "body") val body: String? = null,
)

data class PriorityProject(
    @Json(name = "name") val name: String? = null,
    @Json(name = "desc") val desc: String? = null,
)

/** 异议应答：兼容 objection/q 与 answer/a 两套字段名（report.html 两路读取）。 */
data class ObjectionQa(
    @Json(name = "objection") val objection: String? = null,
    @Json(name = "q") val q: String? = null,
    @Json(name = "answer") val answer: String? = null,
    @Json(name = "a") val a: String? = null,
) {
    val question: String? get() = objection ?: q
    val reply: String? get() = answer ?: a
}

data class PainEntryScript(
    @Json(name = "pain_name") val painName: String? = null,
    @Json(name = "entry") val entry: String? = null,
    @Json(name = "principle") val principle: String? = null,
    @Json(name = "direction") val direction: String? = null,
    @Json(name = "sales_link") val salesLink: String? = null,
)

// ── PART 09 竞品分析·售后学习清单 ──
data class ExternalSignals(
    @Json(name = "medical_aesthetics") val medicalAesthetics: List<Competitor>? = null,
    @Json(name = "other_institutions") val otherInstitutions: List<Competitor>? = null,
    @Json(name = "external_brands") val externalBrands: List<Competitor>? = null,
) {
    /** 三类竞品平铺成一张表（report.html PART 09 渲染口径）。 */
    fun flatten(): List<Competitor> =
        (medicalAesthetics ?: emptyList()) +
            (otherInstitutions ?: emptyList()) +
            (externalBrands ?: emptyList())
}

data class Competitor(
    @Json(name = "item") val item: String? = null,
    @Json(name = "type") val type: String? = null,
    @Json(name = "customer_quote") val customerQuote: String? = null,
    @Json(name = "competitor_learn") val competitorLearn: String? = null,
)

// ── PART 10 顾客标签（本次 report 里的；动态历史走 /customer_tags 接口）──
data class CustomerTag(
    @Json(name = "tag") val tag: String? = null,
    @Json(name = "count") val count: Int? = null,
)

// ── PART 11 成交诊断·5 维度判断 ──
data class DealDiagnosis(
    @Json(name = "deal_result") val dealResult: Boolean? = null,
    @Json(name = "deal_amount") val dealAmount: String? = null,
    @Json(name = "risk_level") val riskLevel: String? = null,    // high | medium | low
    @Json(name = "risk_alert") val riskAlert: Boolean? = null,
    @Json(name = "risk_text") val riskText: String? = null,
    @Json(name = "dimensions") val dimensions: DealDimensions? = null,
)

/** 5 个固定维度（report.html dim_labels 顺序）。 */
data class DealDimensions(
    @Json(name = "customer_moved") val customerMoved: DealDimension? = null,
    @Json(name = "customer_agreed") val customerAgreed: DealDimension? = null,
    @Json(name = "effect_satisfied") val effectSatisfied: DealDimension? = null,
    @Json(name = "price_matched") val priceMatched: DealDimension? = null,
    @Json(name = "urgency_built") val urgencyBuilt: DealDimension? = null,
)

data class DealDimension(
    @Json(name = "status") val status: String? = null,   // ok | partial | missing
    @Json(name = "note") val note: String? = null,
)

// ── 质检评分明细（T5 scoring，PART 01 内"查看详细评分"）──
data class Scoring(
    @Json(name = "overall") val overall: Double? = null,
    @Json(name = "stages") val stages: List<ScoringStage>? = null,
)

data class ScoringStage(
    @Json(name = "name") val name: String? = null,
    @Json(name = "score") val score: Double? = null,
    @Json(name = "sub") val sub: List<ScoringSub>? = null,
)

data class ScoringSub(
    @Json(name = "name") val name: String? = null,
    @Json(name = "score") val score: Double? = null,
    @Json(name = "detail") val detail: String? = null,
)

// ─────────────────────────── PART 10 动态接口 ───────────────────────────

/** GET /api/session/<sid>/customer_tags 响应（PART 10 动态标签）。 */
data class CustomerTagsResponse(
    @Json(name = "first_visit") val firstVisit: Boolean? = null,
    @Json(name = "current_tags") val currentTags: List<CurrentTag>? = null,
    @Json(name = "history") val history: List<CustomerTag>? = null,
    @Json(name = "range") val range: String? = null,
)

data class CurrentTag(
    @Json(name = "tag") val tag: String? = null,
    @Json(name = "is_new") val isNew: Boolean? = null,
)
