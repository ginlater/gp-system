package com.airec.bledemo.data.model

import com.squareup.moshi.Json

/* ===================================================================
 * POST/PATCH 端点的 JSON 请求体（application/json）。
 * 字段名以 webapp.py 对应路由 request.get_json() 读取的 key 为准。
 * =================================================================== */

/** POST /api/consultant/recordings/<rid>/bind */
data class BindBody(
    @Json(name = "customer_id") val customerId: Long,
)

/** POST /api/admin/customer_value（生成/刷新客户价值预测，强制重算）。 */
data class CustomerValueGenBody(
    @Json(name = "customer_id") val customerId: Long,
)

/** POST /api/consultant/recordings/<rid>/direct_rebind */
data class DirectRebindBody(
    @Json(name = "to_customer_id") val toCustomerId: Long,
    @Json(name = "reason") val reason: String? = null,
)

/** POST /api/consultant/recordings/<rid>/rebind_request */
data class RebindRequestBody(
    @Json(name = "to_customer_id") val toCustomerId: Long,
    @Json(name = "reason") val reason: String,
)

/** POST /api/consultant/recordings/<rid>/unbind */
data class UnbindBody(
    @Json(name = "reason") val reason: String,
)

/** POST /api/consultant/recordings/<rid>/add_day_customer */
data class AddDayCustomerBody(
    @Json(name = "name") val name: String,
    @Json(name = "phone_tail") val phoneTail: String? = null,
)

/** POST /api/consultant/recordings/<rid>/remove_day_customer */
data class RemoveDayCustomerBody(
    @Json(name = "customer_id") val customerId: Long,
)

/** POST /api/consultant/recordings/<rid>/confirm_speakers。action: keep | unbind。 */
data class ConfirmSpeakersBody(
    @Json(name = "action") val action: String,
)

/** POST /api/consultant/today_reception/add（customer_id 为空=新增顾客）。 */
data class TodayReceptionAddBody(
    @Json(name = "customer_id") val customerId: Long? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "phone_tail") val phoneTail: String? = null,
    @Json(name = "member_card") val memberCard: String? = null,
    @Json(name = "date") val date: String? = null,
)

/** PATCH /api/consultant/today_reception/<dr_id>/date */
data class ChangeDateBody(
    @Json(name = "date") val date: String,
)

/** POST /api/consultant/session/preview/remove */
data class PreviewRemoveBody(
    @Json(name = "recording_id") val recordingId: Long,
)

/** POST /api/consultant/session/start_analysis */
data class StartAnalysisBody(
    @Json(name = "customer_id") val customerId: Long,
    @Json(name = "date") val date: String? = null,
)

/** POST /api/consultant/session/cancel_analysis */
data class CancelAnalysisBody(
    @Json(name = "session_id") val sessionId: Long,
)

/** ★2026-07-13 POST /api/session/<sid>/customer_type：'new'=新客 / 'returning'=老客(默认)。 */
data class CustomerTypeBody(
    @Json(name = "type") val type: String,
)

/** POST /api/session/<sid>/task/<tid>/rerun & /tasks/fill-missing（model 可空）。 */
data class RerunBody(
    @Json(name = "model") val model: String? = null,
)

/**
 * POST /api/recording/<rid>/delete-request（顾问申请删除录音）。
 * webapp.py 读 data.get("reason")，可空（后端 strip 兜底）。
 */
data class DeleteRequestBody(
    @Json(name = "reason") val reason: String? = null,
)

/**
 * POST /api/session/<sid>/evaluate（追加一条点评）。
 * webapp.py 读 data.get("comment")，必填（空串后端 400）。
 */
data class EvaluateBody(
    @Json(name = "comment") val comment: String,
)

/**
 * POST /api/session/<sid>/analyze（重新/补跑分析，可指定模型）。
 * webapp.py 读 data.get("model")，可空=用默认模型。
 */
data class AnalyzeBody(
    @Json(name = "model") val model: String? = null,
)

/**
 * POST /api/admin/recordings/<rid>/split（按秒数把录音切成两段）。
 * webapp.py 读 data.get("at_seconds")（float，秒）。注意：该端点 @manager_required，
 * 仅店长/管理员可调；顾问端通常用不到，放此仅为数据层完整。
 */
data class SplitBody(
    @Json(name = "at_seconds") val atSeconds: Double,
)

/** POST /api/report_view/enter：打开报告即上报"已查看"（后端据此关闭该 session 的未查看提醒）。 */
data class ReportViewBody(
    @Json(name = "session_id") val sessionId: Long,
    @Json(name = "source") val source: String = "app",
)

/**
 * POST /api/report_view/part：上报章节展开 / 停留时长（喂运营看板的「展开章节」「总时长」）。
 * event="enter"（展开某章节，part_key 用 part2..part11 白名单）/ "duration"（停留 duration_ms 毫秒）。
 */
data class ReportViewPartBody(
    @Json(name = "session_id") val sessionId: Long,
    @Json(name = "part_key") val partKey: String,
    @Json(name = "event") val event: String,
    @Json(name = "duration_ms") val durationMs: Long = 0,
)
