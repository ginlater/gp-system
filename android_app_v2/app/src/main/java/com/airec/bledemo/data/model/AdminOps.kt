package com.airec.bledemo.data.model

import com.squareup.moshi.Json

/* ===================================================================
 * 管理台 · 运营看板（GET /api/admin/ops_dashboard）数据模型。
 *
 * 字段以 webapp.py `api_admin_ops_dashboard` 真实响应为准（见该函数 + web admin.html
 * 的 renderOpsAdmin/renderOpsSuper/_kpiCards/_advisorTable/_failList）。
 *
 * 响应有两种形态，由 [OpsDashboardResponse.isSuper] 区分：
 *  - 非超管（admin / store_manager）：顶层直接给 today_stats / week_stats / advisors / recent_failures。
 *  - 超管（super）：顶层给 companies[]，每家公司各自一份 today / week / advisors / recent_failures。
 *
 * 全字段可空 + 默认值（Moshi 反射解析，缺字段不崩）。
 *
 * 这是内部管理视图：词汇沿用后端（接诊包 / 顾问 / 门店 / 失败），不走顾客可见的「陪伴」红线。
 * =================================================================== */

/** KPI 一档统计（今日 / 本周；web `_kpiCards`）。rate = 完成率(%)，avgMin = 平均耗时(分钟，可空)。 */
data class OpsSessionStats(
    @Json(name = "total") val total: Int = 0,
    @Json(name = "done") val done: Int = 0,
    @Json(name = "failed") val failed: Int = 0,
    @Json(name = "pending") val pending: Int = 0,
    @Json(name = "rate") val rate: Int = 0,
    @Json(name = "avg_min") val avgMin: Double? = null,
)

/** 顾问明细行（本周；web `_advisorTable`）。 */
data class OpsAdvisorRow(
    @Json(name = "name") val name: String? = null,
    @Json(name = "week_total") val weekTotal: Int = 0,
    @Json(name = "week_done") val weekDone: Int = 0,
    @Json(name = "week_failed") val weekFailed: Int = 0,
    @Json(name = "week_pending") val weekPending: Int = 0,
    @Json(name = "week_rate") val weekRate: Int = 0,
    @Json(name = "avg_min") val avgMin: Double? = null,
)

/** 近期失败接诊包（web `_failList`）。 */
data class OpsFailure(
    @Json(name = "id") val id: Long? = null,
    @Json(name = "advisor") val advisor: String? = null,
    @Json(name = "customer") val customer: String? = null,
    @Json(name = "service_date") val serviceDate: String? = null,
    @Json(name = "analysis_error") val analysisError: String? = null,
)

/** 超管视图里单家公司的一整块（web `renderOpsSuper` 每个 co）。 */
data class OpsCompany(
    @Json(name = "company_id") val companyId: Long? = null,
    @Json(name = "company_name") val companyName: String? = null,
    @Json(name = "today") val today: OpsSessionStats? = null,
    @Json(name = "week") val week: OpsSessionStats? = null,
    @Json(name = "advisors") val advisors: List<OpsAdvisorRow>? = null,
    @Json(name = "recent_failures") val recentFailures: List<OpsFailure>? = null,
)

/**
 * GET /api/admin/ops_dashboard 完整响应。
 *
 * @property isSuper true=超管(看 [companies])；false=admin/店长(看 [todayStats]/[weekStats]/[advisors]/[recentFailures])
 * @property today 今日日期串 'YYYY-MM-DD'
 * @property weekStart 本周起始日 'YYYY-MM-DD'
 */
data class OpsDashboardResponse(
    @Json(name = "is_super") val isSuper: Boolean = false,
    @Json(name = "today") val today: String? = null,
    @Json(name = "week_start") val weekStart: String? = null,
    // ─ 非超管（admin / store_manager） ─
    @Json(name = "today_stats") val todayStats: OpsSessionStats? = null,
    @Json(name = "week_stats") val weekStats: OpsSessionStats? = null,
    @Json(name = "advisors") val advisors: List<OpsAdvisorRow>? = null,
    @Json(name = "recent_failures") val recentFailures: List<OpsFailure>? = null,
    // ─ 超管（super） ─
    @Json(name = "companies") val companies: List<OpsCompany>? = null,
    // 未登录/无权时后端可能回 {"error": "..."}
    @Json(name = "error") val error: String? = null,
)
