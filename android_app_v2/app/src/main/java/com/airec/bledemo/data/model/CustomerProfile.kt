package com.airec.bledemo.data.model

import com.squareup.moshi.Json

/* ===================================================================
 * 美丽档案（顾客档案页 /customer/<cid>）数据模型。
 *
 * 1:1 对应 web_v2/templates/customer_profile.html 拉取的两个只读接口：
 *  - GET /api/admin/customer_profile?customer_id=&range=  →  [CustomerProfileResponse]
 *  - GET /api/admin/customer_value?customer_id=           →  [CustomerValueResponse]
 *
 * 顾问端【只读】：客户价值预测由管理员/店长生成，本 App 不提供生成/刷新入口。
 *
 * 约定同 Models.kt：字段尽量可空 + 默认值；@Json key 以 webapp.py 真实响应为准。
 * 产品红线：UI 层对外一律「陪伴 / 服务时间线 / 查看报告」，绝不出现录音/录制。
 * =================================================================== */

// ─────────────────────────── customer_profile ───────────────────────────

/** 顾客基本信息（response.info）。 */
data class CustomerProfileInfo(
    @Json(name = "id") val id: Long? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "member_card") val memberCard: String? = null,
    @Json(name = "phone_tail") val phoneTail: String? = null,
)

/** 累积标签一项（response.accumulated_tags[]）。count = 多少次陪伴提到该标签。 */
data class AccumulatedTag(
    @Json(name = "tag") val tag: String? = null,
    @Json(name = "count") val count: Int? = null,
)

/** 服务时间线一项（response.sessions[]，按 service_date 倒序）。 */
data class ProfileSession(
    @Json(name = "id") val id: Long? = null,
    @Json(name = "service_date") val serviceDate: String? = null,
    @Json(name = "advisor") val advisor: String? = null,
    @Json(name = "store_name") val storeName: String? = null,
    // done | running | queued | pending | failed | …（done 时才可点开报告）
    @Json(name = "analysis_status") val analysisStatus: String? = null,
    @Json(name = "tags") val tags: List<String>? = null,
    @Json(name = "project_hits") val projectHits: List<String>? = null,
) {
    val isDone: Boolean get() = analysisStatus == "done"
}

/** GET /api/admin/customer_profile 响应。错误时仅 error 非空（400/403/404）。 */
data class CustomerProfileResponse(
    @Json(name = "info") val info: CustomerProfileInfo? = null,
    @Json(name = "accumulated_tags") val accumulatedTags: List<AccumulatedTag>? = null,
    @Json(name = "sessions") val sessions: List<ProfileSession>? = null,
    @Json(name = "session_count") val sessionCount: Int? = null,
    @Json(name = "range") val range: String? = null,
    @Json(name = "error") val error: String? = null,
)

// ─────────────────────────── customer_value ───────────────────────────

/** 按顾问匹配度一项（content.advisor_match[]）。 */
data class AdvisorMatch(
    @Json(name = "advisor") val advisor: String? = null,
    @Json(name = "assessment") val assessment: String? = null,
)

/**
 * 客户价值预测正文（response.content）。各维度为 markdown-lite 文本。
 * 字段中文标签（与 web VALUE_TOOL 一致）：
 *  value_rebuild=客户价值评估, battle_plan=可攻破痛点+作战方案,
 *  project_plan=竞品分析+项目+学习清单, biz_plan=下一步动作+回店规划,
 *  advisor_match=按顾问匹配度。
 */
data class CustomerValueContent(
    @Json(name = "value_rebuild") val valueRebuild: String? = null,
    @Json(name = "battle_plan") val battlePlan: String? = null,
    @Json(name = "project_plan") val projectPlan: String? = null,
    @Json(name = "biz_plan") val bizPlan: String? = null,
    @Json(name = "advisor_match") val advisorMatch: List<AdvisorMatch>? = null,
)

/**
 * GET /api/admin/customer_value 响应。
 *  - has_cache=false → content 为 null（尚未生成）。
 *  - stale=true → 展示缓存内容，附「可能已过期」轻提示。
 */
data class CustomerValueResponse(
    @Json(name = "content") val content: CustomerValueContent? = null,
    @Json(name = "generated_at") val generatedAt: String? = null,
    @Json(name = "model") val model: String? = null,
    @Json(name = "stale") val stale: Boolean? = null,
    @Json(name = "done_count") val doneCount: Int? = null,
    @Json(name = "has_cache") val hasCache: Boolean? = null,
    @Json(name = "error") val error: String? = null,
)
