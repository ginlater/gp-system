package com.airec.bledemo.data.model

import com.squareup.moshi.Json

/* ===================================================================
 * 接诊/会话列表（「美丽档案」= web index.html / `/` 页）
 *
 * 顾问端只看到自己名下的接诊（服务端按 advisor_name 自动隔离），
 * 每行 → 一份分析报告。字段名核对自 webapp.py：
 *   - GET /api/sessions（api_sessions，~line 5251）
 *   - GET /api/sessions/status_counts（api_sessions_status_counts，~line 5407）
 *
 * 所有层级一律【可空】+ 默认值，缺字段不崩；沿用 SessionReport.kt 约定。
 * 产品红线：本层沿用后端真实 key，UI 层对外只说「接诊记录 / 分析报告 / 查看报告」。
 * =================================================================== */

/** GET /api/sessions 顶层响应（接诊列表 + 服务端分页）。 */
data class SessionsResponse(
    @Json(name = "sessions") val sessions: List<SessionRow>? = null,
    @Json(name = "total") val total: Int? = null,
    @Json(name = "page") val page: Int? = null,
    @Json(name = "page_size") val pageSize: Int? = null,
    @Json(name = "error") val error: String? = null,
)

/**
 * 单行接诊。display_status 是后端唯一真源状态串：
 *   done | running | queued | failed | stuck | idle（见 _status_bucket_sql）。
 * has_evaluation 是 int(0/1)，>0 视为有点评。
 */
data class SessionRow(
    @Json(name = "id") val id: Long? = null,
    @Json(name = "customer") val customer: String? = null,
    @Json(name = "advisor") val advisor: String? = null,
    @Json(name = "service_date") val serviceDate: String? = null,
    @Json(name = "analysis_status") val analysisStatus: String? = null,
    @Json(name = "display_status") val displayStatus: String? = null,
    @Json(name = "has_evaluation") val hasEvaluation: Int? = null,
    @Json(name = "recording_count") val recordingCount: Int? = null,
    // 录音/ASR 计数：done/running/failed（仅 asr_done_count 进 UI「录音/ASR」列，其余备查）
    @Json(name = "asr_done_count") val asrDoneCount: Int? = null,
    @Json(name = "asr_running_count") val asrRunningCount: Int? = null,
    @Json(name = "asr_failed_count") val asrFailedCount: Int? = null,
    // 综合分藏在 analysis_scores —— 注意它是一段 JSON【字符串】，需二次解析取 .overall
    @Json(name = "analysis_scores") val analysisScores: String? = null,
    @Json(name = "analysis_progress") val analysisProgress: String? = null,
    @Json(name = "task_done") val taskDone: Int? = null,
    @Json(name = "task_total") val taskTotal: Int? = null,
    // 正在跑的子任务名（running/queued 时拼「· 正在 T..」）
    @Json(name = "task_running") val taskRunning: List<String>? = null,
    @Json(name = "analysis_started_at") val analysisStartedAt: String? = null,
    @Json(name = "analysis_finished_at") val analysisFinishedAt: String? = null,
    @Json(name = "created_at") val createdAt: String? = null,
    @Json(name = "first_recorded_at") val firstRecordedAt: String? = null,
    @Json(name = "last_recorded_at") val lastRecordedAt: String? = null,
) {
    /** >0 视为「有点评」。 */
    val hasEval: Boolean get() = (hasEvaluation ?: 0) > 0

    /**
     * 综合分：解析 analysis_scores(JSON 字符串) 取 .overall。
     * 对齐 web `JSON.parse(s.analysis_scores).overall`，任何异常/缺字段一律 null（显示「—」）。
     */
    val overallScore: Double? get() = runCatching {
        val raw = analysisScores
        if (raw.isNullOrBlank()) null
        else {
            val obj = org.json.JSONObject(raw)
            if (obj.isNull("overall")) null else obj.getDouble("overall")
        }
    }.getOrNull()
}

/**
 * GET /api/sessions/status_counts 响应。
 * 后端形如 {"counts": {"all": n, "running": n, "queued": n, "done": n,
 *                       "failed": n, "stuck": n, "idle": n}}。
 * 「全部」用 counts.all（后端单独累加并与 total 自检对齐）。
 */
data class StatusCountsResponse(
    @Json(name = "counts") val counts: StatusCounts? = null,
    @Json(name = "error") val error: String? = null,
)

/** 各状态桶计数（键名与 _DISPLAY_BUCKETS + "all" 一一对应）。 */
data class StatusCounts(
    @Json(name = "all") val all: Int? = null,
    @Json(name = "running") val running: Int? = null,
    @Json(name = "queued") val queued: Int? = null,
    @Json(name = "done") val done: Int? = null,
    @Json(name = "failed") val failed: Int? = null,
    @Json(name = "stuck") val stuck: Int? = null,
    @Json(name = "idle") val idle: Int? = null,
) {
    /** 取某桶计数（bucket=null → 全部）。 */
    fun of(bucket: String?): Int = when (bucket) {
        null, "" -> all ?: 0
        "running" -> running ?: 0
        "queued" -> queued ?: 0
        "done" -> done ?: 0
        "failed" -> failed ?: 0
        "stuck" -> stuck ?: 0
        "idle" -> idle ?: 0
        else -> 0
    }
}
