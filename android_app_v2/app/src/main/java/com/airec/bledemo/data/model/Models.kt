package com.airec.bledemo.data.model

import com.squareup.moshi.Json

/* ===================================================================
 * 数据层领域模型（DTO）
 *
 * 全部字段尽量【可空】，@Json 标注的 key 全部以 webapp.py 真实响应为准
 * （见 SPEC §7；字段名核对自 webapp.py / report.html / consultant.html）。
 * Moshi 反射(moshi-kotlin)解析，不依赖 codegen。
 *
 * 产品红线：顾客可见处不出现"录音"，统一用"陪伴"系词汇；设备叫"陪伴笔"。
 * 这里是数据层，字段名沿用后端真实 key（recording / pen 等），
 * UI 层负责对外展示成"陪伴片段 / 陪伴笔"。
 * =================================================================== */

// ─────────────────────────── 账号 / 我 ───────────────────────────

/** GET /api/me 响应。role: consultant | store_manager | admin | super */
data class Me(
    @Json(name = "id") val id: Long? = null,
    @Json(name = "username") val username: String? = null,
    @Json(name = "role") val role: String? = null,
    @Json(name = "company_id") val companyId: Long? = null,
    @Json(name = "advisor_name") val advisorName: String? = null,
    @Json(name = "employee_id") val employeeId: String? = null,
    @Json(name = "phone") val phone: String? = null,
    @Json(name = "store_id") val storeId: Long? = null,
    // /api/me 在未登录时返回 {"error":"未登录"} + 401
    @Json(name = "error") val error: String? = null,
) {
    val isConsultantOrManager: Boolean
        get() = role == "consultant" || role == "store_manager"

    /** 管理台角色（管理员 / 超管）。登录后 GateScreen 据此分流到 [com.airec.bledemo.nav.Routes.AdminHome]。 */
    val isAdminOrSuper: Boolean
        get() = role == "admin" || role == "super"
}

// ─────────────────────────── 版本 / 强制更新 ───────────────────────────

/** GET /api/app/version（无需登录）。低于 minVersionCode → 强制更新。 */
data class AppVersion(
    @Json(name = "latestVersionCode") val latestVersionCode: Int? = null,
    @Json(name = "latestVersionName") val latestVersionName: String? = null,
    @Json(name = "minVersionCode") val minVersionCode: Int? = null,
    @Json(name = "apkUrl") val apkUrl: String? = null,
    @Json(name = "pageUrl") val pageUrl: String? = null,
    @Json(name = "updateNote") val updateNote: String? = null,
)

// ─────────────────────────── 陪伴笔（pen）───────────────────────────

/** GET /api/consultant/pen/binding。pen_sn 为空=未绑定。 */
data class PenBinding(
    @Json(name = "pen_sn") val penSn: String? = null,
)

/** POST /api/consultant/pen/report-sn 响应。decision: allow | deny。 */
data class PenSnReport(
    @Json(name = "ok") val ok: Boolean? = null,
    @Json(name = "bound_sn") val boundSn: String? = null,
    @Json(name = "match") val match: Boolean? = null,
    @Json(name = "decision") val decision: String? = null,   // allow | deny
    @Json(name = "reason") val reason: String? = null,       // my_pen | free | have_other_binding | bound_other
    @Json(name = "message") val message: String? = null,     // deny 时给 App 弹的话
) {
    val allowed: Boolean get() = decision == "allow"
}

/** POST /api/consultant/pen/sync-preview 单条机身片段的同步状态。 */
data class PenSyncItem(
    @Json(name = "name") val name: String? = null,
    @Json(name = "status") val status: String? = null,        // uploaded | deleted | new
    @Json(name = "existing_id") val existingId: Long? = null,
)

/** POST /api/consultant/pen/sync-preview 响应。 */
data class PenSyncPreview(
    @Json(name = "items") val items: List<PenSyncItem>? = null,
)

/** 上传机身片段列表项的请求体（sync-preview 入参 items 元素）。 */
data class PenSyncQueryItem(
    @Json(name = "name") val name: String,
    @Json(name = "ra") val ra: String? = null,   // 录音时刻 'YYYY-MM-DD HH:MM:SS'，可空
)

data class PenSyncPreviewBody(
    @Json(name = "items") val items: List<PenSyncQueryItem>,
)

// ─────────────────────────── 上传 / 占位 ───────────────────────────

/** POST /api/consultant/upload 响应。deduped=true 表示命中去重已存在。 */
data class UploadResult(
    @Json(name = "id") val id: Long? = null,
    @Json(name = "oss_key") val ossKey: String? = null,
    @Json(name = "deduped") val deduped: Boolean? = null,
    @Json(name = "error") val error: String? = null,
)

/** POST /api/consultant/placeholder 响应。 */
data class PlaceholderResult(
    @Json(name = "id") val id: Long? = null,
    @Json(name = "error") val error: String? = null,
)

// ─────────────────────────── 待整理片段（pending）───────────────────────────

/**
 * GET /api/consultant/recordings/pending → recordings[]。
 * upload_status=processing 时是"陪伴笔补传占位"，audio_url 为 null（不可试听）。
 */
data class PendingRecording(
    @Json(name = "id") val id: Long,
    @Json(name = "oss_key") val ossKey: String? = null,
    @Json(name = "recorded_at") val recordedAt: String? = null,
    @Json(name = "duration_label") val durationLabel: String? = null,
    @Json(name = "size_bytes") val sizeBytes: Long? = null,
    @Json(name = "asr_status") val asrStatus: String? = null,
    @Json(name = "asr_error") val asrError: String? = null,
    @Json(name = "customer") val customer: String? = null,
    @Json(name = "created_at") val createdAt: String? = null,
    @Json(name = "asr_speaker_count") val asrSpeakerCount: Int? = null,
    @Json(name = "asr_speaker_warning") val asrSpeakerWarning: Int? = null,
    @Json(name = "upload_status") val uploadStatus: String? = null,    // processing | done
    @Json(name = "truncate_note") val truncateNote: String? = null,
    // 服务端补算字段
    @Json(name = "audio_url") val audioUrl: String? = null,
    @Json(name = "service_date") val serviceDate: String? = null,
    @Json(name = "start_hm") val startHm: String? = null,
    @Json(name = "end_hm") val endHm: String? = null,
    @Json(name = "duration_min") val durationMin: Int? = null,
    @Json(name = "rec_date") val recDate: String? = null,
    // 删除申请状态
    @Json(name = "delete_request_id") val deleteRequestId: Long? = null,
    @Json(name = "delete_request_status") val deleteRequestStatus: String? = null,
    @Json(name = "delete_reject_reason") val deleteRejectReason: String? = null,
) {
    val isProcessing: Boolean get() = uploadStatus == "processing"
    val hasSpeakerWarning: Boolean get() = (asrSpeakerWarning ?: 0) == 1
}

data class PendingRecordingsResponse(
    @Json(name = "recordings") val recordings: List<PendingRecording>? = null,
)

/** GET /api/consultant/recordings/pending_dates（非今天的待整理日期集合）。 */
data class PendingDatesResponse(
    @Json(name = "dates") val dates: List<String>? = null,
)

/** GET /api/consultant/recordings/needs_confirm（多说话人误录待确认）。 */
data class NeedsConfirmRecording(
    @Json(name = "id") val id: Long,
    @Json(name = "oss_key") val ossKey: String? = null,
    @Json(name = "recorded_at") val recordedAt: String? = null,
    @Json(name = "customer") val customer: String? = null,
    @Json(name = "advisor") val advisor: String? = null,
    @Json(name = "session_id") val sessionId: Long? = null,
    @Json(name = "asr_speaker_count") val asrSpeakerCount: Int? = null,
    @Json(name = "asr_status") val asrStatus: String? = null,
    @Json(name = "created_at") val createdAt: String? = null,
    @Json(name = "audio_url") val audioUrl: String? = null,
)

data class NeedsConfirmResponse(
    @Json(name = "recordings") val recordings: List<NeedsConfirmRecording>? = null,
)

// ─────────────────────────── 顾客 / 候选 ───────────────────────────

/** 通用顾客模型（customer_lookup / customers/search / rebind_candidates 共用核心字段）。 */
data class Customer(
    @Json(name = "id") val id: Long? = null,
    // rebind_candidates / add_day_customer 用 customer_id 作为主键名
    @Json(name = "customer_id") val customerId: Long? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "phone_tail") val phoneTail: String? = null,
    @Json(name = "member_card") val memberCard: String? = null,
    // rebind_candidates 标注：是否已在该录音当天本人接诊
    @Json(name = "in_day") val inDay: Boolean? = null,
) {
    /** 统一取主键：优先 id，回退 customer_id。 */
    val cid: Long? get() = id ?: customerId
}

/** GET /api/consultant/customer_lookup 响应。 */
data class CustomerLookupResponse(
    @Json(name = "customers") val customers: List<Customer>? = null,
)

/** GET /api/customers/search 响应。 */
data class CustomerSearchResponse(
    @Json(name = "customers") val customers: List<Customer>? = null,
)

/** GET /api/consultant/rebind_candidates 响应。 */
data class RebindCandidatesResponse(
    @Json(name = "items") val items: List<Customer>? = null,
    @Json(name = "service_date") val serviceDate: String? = null,
)

// ─────────────────────────── 今日接诊（reception）───────────────────────────

/** GET /api/consultant/today_reception → items[]。 */
data class TodayReception(
    @Json(name = "id") val id: Long,                       // daily_reception.id (dr_id)
    @Json(name = "customer_id") val customerId: Long? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "phone_tail") val phoneTail: String? = null,
    @Json(name = "member_card") val memberCard: String? = null,
    @Json(name = "service_date") val serviceDate: String? = null,
    @Json(name = "session_id") val sessionId: Long? = null,
    @Json(name = "locked") val locked: Boolean? = null,
    @Json(name = "analysis_status") val analysisStatus: String? = null,
    @Json(name = "recording_count") val recordingCount: Int? = null,
    @Json(name = "pending_rebind_count") val pendingRebindCount: Int? = null,
)

data class TodayReceptionResponse(
    @Json(name = "items") val items: List<TodayReception>? = null,
    @Json(name = "date") val date: String? = null,
)

// ─────────────────────────── 会话预览（preview）───────────────────────────

/** preview 接诊包内已绑/未绑录音项。 */
data class PreviewRecording(
    @Json(name = "id") val id: Long,
    @Json(name = "oss_key") val ossKey: String? = null,
    @Json(name = "recorded_at") val recordedAt: String? = null,
    @Json(name = "duration_label") val durationLabel: String? = null,
    @Json(name = "asr_status") val asrStatus: String? = null,
    @Json(name = "asr_speaker_count") val asrSpeakerCount: Int? = null,
    @Json(name = "asr_speaker_warning") val asrSpeakerWarning: Int? = null,
    @Json(name = "speaker_confirmed") val speakerConfirmed: Int? = null,
    @Json(name = "created_at") val createdAt: String? = null,
    @Json(name = "audio_url") val audioUrl: String? = null,
    @Json(name = "pending_rebind_request_id") val pendingRebindRequestId: Long? = null,
)

/** preview 里的 11 任务级进度（task_progress）。 */
data class TaskProgress(
    @Json(name = "total") val total: Int? = null,
    @Json(name = "done") val done: Int? = null,
    @Json(name = "running") val running: Int? = null,
    @Json(name = "failed") val failed: Int? = null,
    @Json(name = "pending") val pending: Int? = null,
    @Json(name = "done_names") val doneNames: List<String>? = null,
    @Json(name = "running_names") val runningNames: List<String>? = null,
    @Json(name = "failed_names") val failedNames: List<String>? = null,
    @Json(name = "pending_names") val pendingNames: List<String>? = null,
    @Json(name = "wait_sec") val waitSec: Int? = null,
    @Json(name = "progress_text") val progressText: String? = null,
)

/** GET /api/consultant/session/preview 响应。 */
data class SessionPreview(
    @Json(name = "customer") val customer: Customer? = null,
    @Json(name = "service_date") val serviceDate: String? = null,
    @Json(name = "session_id") val sessionId: Long? = null,
    @Json(name = "locked") val locked: Boolean? = null,
    @Json(name = "analysis_status") val analysisStatus: String? = null,
    @Json(name = "task_progress") val taskProgress: TaskProgress? = null,
    @Json(name = "bound") val bound: List<PreviewRecording>? = null,
    @Json(name = "unbound") val unbound: List<PreviewRecording>? = null,
    @Json(name = "error") val error: String? = null,
)

/** GET /api/consultant/customer_recordings → groups[]（按服务日期分组）。 */
data class CustomerRecordingsGroup(
    @Json(name = "service_date") val serviceDate: String? = null,
    @Json(name = "recordings") val recordings: List<PreviewRecording>? = null,
)

data class CustomerRecordingsResponse(
    @Json(name = "groups") val groups: List<CustomerRecordingsGroup>? = null,
)

// ─────────────────────────── 提醒（reminders）───────────────────────────

/** GET /api/consultant/reminders → items[]。scope: personal | escalation。 */
data class Reminder(
    @Json(name = "id") val id: Long,
    @Json(name = "kind") val kind: String? = null,
    @Json(name = "level") val level: String? = null,
    @Json(name = "channel") val channel: String? = null,    // inapp | phone | escalation
    @Json(name = "ref_type") val refType: String? = null,
    @Json(name = "ref_id") val refId: Long? = null,
    @Json(name = "message") val message: String? = null,
    @Json(name = "created_at") val createdAt: String? = null,
    @Json(name = "scope") val scope: String? = null,        // personal | escalation
    // escalation（店长视角）专有
    @Json(name = "advisor_user_id") val advisorUserId: Long? = null,
    @Json(name = "advisor_name") val advisorName: String? = null,
    @Json(name = "session_id") val sessionId: Long? = null,
    @Json(name = "result") val result: String? = null,
    @Json(name = "is_read") val isRead: Boolean? = null,
    @Json(name = "is_handled") val isHandled: Boolean? = null,
)

data class RemindersResponse(
    @Json(name = "count") val count: Int? = null,
    @Json(name = "personal_count") val personalCount: Int? = null,
    @Json(name = "escalation_count") val escalationCount: Int? = null,
    @Json(name = "items") val items: List<Reminder>? = null,
)

// ─────────────────────────── 录音 URL / 通用 ───────────────────────────

/** GET /api/recording/<rid>/url 响应。 */
data class RecordingUrlResponse(
    @Json(name = "url") val url: String? = null,
    @Json(name = "error") val error: String? = null,
)

/**
 * POST /api/session/<sid>/evaluate 响应：{ok, evaluation:{...}}。
 * evaluation 复用 SessionReport.kt 的 [Evaluation]。
 */
data class EvaluateResult(
    @Json(name = "ok") val ok: Boolean? = null,
    @Json(name = "error") val error: String? = null,
    @Json(name = "evaluation") val evaluation: Evaluation? = null,
)

/** GET /api/session/<sid>/evaluations 响应（点评列表，倒序）。 */
data class EvaluationsResponse(
    @Json(name = "evaluations") val evaluations: List<Evaluation>? = null,
)

/**
 * POST /api/consultant/analyze 响应：{ok, session_ids:[...], skipped:[...]}。
 * session_ids = 实际触发分析的会话；skipped = 已是最新分析跳过的会话。
 */
data class ConsultantAnalyzeResult(
    @Json(name = "ok") val ok: Boolean? = null,
    @Json(name = "error") val error: String? = null,
    @Json(name = "session_ids") val sessionIds: List<Long>? = null,
    @Json(name = "skipped") val skipped: List<Long>? = null,
)

/** 大量 POST 端点统一返回 {ok / error / ...}。可空兜底。 */
data class SimpleResult(
    @Json(name = "ok") val ok: Boolean? = null,
    @Json(name = "error") val error: String? = null,
    @Json(name = "msg") val msg: String? = null,
    @Json(name = "session_id") val sessionId: Long? = null,
    @Json(name = "customer_id") val customerId: Long? = null,
    @Json(name = "request_id") val requestId: Long? = null,
    @Json(name = "unbound_count") val unboundCount: Int? = null,
    @Json(name = "unchanged") val unchanged: Boolean? = null,
    @Json(name = "status") val status: String? = null,        // started | all_done | running ... | queued | pending | done | failed
    @Json(name = "task_id") val taskId: String? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "phone_tail") val phoneTail: String? = null,
    @Json(name = "member_card") val memberCard: String? = null,
    @Json(name = "service_date") val serviceDate: String? = null,
    @Json(name = "id") val id: Long? = null,
    @Json(name = "analysis_triggered") val analysisTriggered: Boolean? = null,
    // direct_rebind 换绑成功后新接诊包 id
    @Json(name = "new_session_id") val newSessionId: Long? = null,
    // /api/session/<sid>/analyze 重跑响应附带
    @Json(name = "progress") val progress: String? = null,
    @Json(name = "model") val model: String? = null,
    @Json(name = "mode") val mode: String? = null,            // only_failed | full
    @Json(name = "tasks") val tasks: List<String>? = null,    // only_failed 模式补跑的任务 id
)
