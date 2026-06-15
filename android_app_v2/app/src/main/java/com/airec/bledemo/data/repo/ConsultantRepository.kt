package com.airec.bledemo.data.repo

import com.airec.bledemo.data.api.ConsultantApi
import com.airec.bledemo.data.model.*
import com.airec.bledemo.data.net.NetworkModule
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import java.io.File

/**
 * 顾问端数据仓库：把 ConsultantApi 的端点包成 suspend 函数，统一返回 [ApiResult]。
 * 给 ViewModel 用——所有调用都在 IO 线程，失败时尽量带上后端 error 文案。
 *
 * 产品红线：本层沿用后端真实 key（recording/pen），UI 层对外说"陪伴片段/陪伴笔"。
 */
class ConsultantRepository(
    private val api: ConsultantApi = NetworkModule.api,
    private val moshi: Moshi = NetworkModule.moshi,
) {

    // ───────────── 我 / 版本 ─────────────

    suspend fun me(): ApiResult<Me> = call { api.me() }

    suspend fun appVersion(): ApiResult<AppVersion> = call { api.appVersion() }

    suspend fun appVersionV2(): ApiResult<AppVersion> = call { api.appVersionV2() }

    // ───────────── 上传 / 占位 ─────────────

    /**
     * 上传陪伴片段。
     * @param file 本地音频文件
     * @param durationSec 墙钟时长(秒)，可空
     * @param recordedAt 录音真实开始 'YYYY-MM-DD HH:MM:SS'，连录/补传必传
     * @param placeholderId 之前 createPlaceholder 拿到的占位 id（回填占位）
     * @param penFile 陪伴笔机身文件名（去重用）
     * @param sn 陪伴笔 SN（审计）
     * @param mediaType 默认按扩展名兜底
     */
    suspend fun upload(
        file: File,
        durationSec: Int? = null,
        recordedAt: String? = null,
        placeholderId: Long? = null,
        penFile: String? = null,
        sn: String? = null,
        mediaType: String? = null,
    ): ApiResult<UploadResult> = call {
        val mt = (mediaType ?: guessMediaType(file.name)).toMediaTypeOrNull()
        val part = MultipartBody.Part.createFormData("file", file.name, file.asRequestBody(mt))
        val fields = HashMap<String, RequestBody>()
        fun put(k: String, v: String?) {
            if (!v.isNullOrBlank()) fields[k] = v.toRequestBody(TEXT)
        }
        put("duration_sec", durationSec?.toString())
        put("recorded_at", recordedAt)
        put("placeholder_id", placeholderId?.toString())
        put("pen_file", penFile)
        put("sn", sn)
        api.upload(part, fields)
    }

    suspend fun createPlaceholder(recordedAt: String? = null): ApiResult<PlaceholderResult> =
        call { api.createPlaceholder(recordedAt) }

    suspend fun cancelPlaceholder(placeholderId: Long): ApiResult<SimpleResult> =
        call { api.cancelPlaceholder(placeholderId) }

    // ───────────── 陪伴笔 ─────────────

    suspend fun penBinding(): ApiResult<PenBinding> = call { api.penBinding() }

    suspend fun penReportSn(sn: String): ApiResult<PenSnReport> = call { api.penReportSn(sn) }

    suspend fun penSyncPreview(items: List<PenSyncQueryItem>): ApiResult<PenSyncPreview> =
        call { api.penSyncPreview(PenSyncPreviewBody(items)) }

    // ───────────── 待整理 ─────────────

    suspend fun pending(): ApiResult<List<PendingRecording>> =
        call { api.pending() }.map { it.recordings ?: emptyList() }

    suspend fun pendingDates(): ApiResult<List<String>> =
        call { api.pendingDates() }.map { it.dates ?: emptyList() }

    suspend fun rebindCandidates(rid: Long? = null, q: String? = null): ApiResult<RebindCandidatesResponse> =
        call { api.rebindCandidates(rid, q) }

    suspend fun needsConfirm(): ApiResult<List<NeedsConfirmRecording>> =
        call { api.needsConfirm() }.map { it.recordings ?: emptyList() }

    suspend fun bind(rid: Long, customerId: Long): ApiResult<SimpleResult> =
        call { api.bind(rid, BindBody(customerId)) }

    suspend fun directRebind(rid: Long, toCustomerId: Long, reason: String? = null): ApiResult<SimpleResult> =
        call { api.directRebind(rid, DirectRebindBody(toCustomerId, reason)) }

    suspend fun rebindRequest(rid: Long, toCustomerId: Long, reason: String): ApiResult<SimpleResult> =
        call { api.rebindRequest(rid, RebindRequestBody(toCustomerId, reason)) }

    suspend fun unbind(rid: Long, reason: String): ApiResult<SimpleResult> =
        call { api.unbind(rid, UnbindBody(reason)) }

    suspend fun addDayCustomer(rid: Long, name: String, phoneTail: String? = null): ApiResult<SimpleResult> =
        call { api.addDayCustomer(rid, AddDayCustomerBody(name, phoneTail)) }

    suspend fun removeDayCustomer(rid: Long, customerId: Long): ApiResult<SimpleResult> =
        call { api.removeDayCustomer(rid, RemoveDayCustomerBody(customerId)) }

    /** action: "keep"(照常分析) | "unbind"(解绑拆分) */
    suspend fun confirmSpeakers(rid: Long, action: String): ApiResult<SimpleResult> =
        call { api.confirmSpeakers(rid, ConfirmSpeakersBody(action)) }

    // ───────────── 今日接诊 ─────────────

    suspend fun todayReception(date: String? = null): ApiResult<TodayReceptionResponse> =
        call { api.todayReception(date) }

    /** 新增/补登今日接诊。customerId 为空 => 新建顾客(需 name + phoneTail)。 */
    suspend fun addTodayReception(
        customerId: Long? = null,
        name: String? = null,
        phoneTail: String? = null,
        memberCard: String? = null,
        date: String? = null,
    ): ApiResult<SimpleResult> =
        call { api.addTodayReception(TodayReceptionAddBody(customerId, name, phoneTail, memberCard, date)) }

    suspend fun removeTodayReception(drId: Long): ApiResult<SimpleResult> =
        call { api.removeTodayReception(drId) }

    suspend fun changeTodayReceptionDate(drId: Long, date: String): ApiResult<SimpleResult> =
        call { api.changeTodayReceptionDate(drId, ChangeDateBody(date)) }

    // ───────────── 顾客查询 ─────────────

    suspend fun customerLookup(q: String? = null): ApiResult<List<Customer>> =
        call { api.customerLookup(q) }.map { it.customers ?: emptyList() }

    suspend fun customersSearch(q: String? = null): ApiResult<List<Customer>> =
        call { api.customersSearch(q) }.map { it.customers ?: emptyList() }

    suspend fun customerRecordings(customer: String): ApiResult<List<CustomerRecordingsGroup>> =
        call { api.customerRecordings(customer) }.map { it.groups ?: emptyList() }

    /** 美丽档案：基本信息 + 累积标签 + 服务时间线（range: all|30|90|180|365）。 */
    suspend fun customerProfile(id: Long, range: String = "all"): ApiResult<CustomerProfileResponse> =
        call { api.customerProfile(id, range) }

    /** 美丽档案：客户价值预测（只读缓存内容）。 */
    suspend fun customerValue(id: Long): ApiResult<CustomerValueResponse> =
        call { api.customerValue(id) }

    /** 美丽档案：生成/刷新客户价值预测（强制重算；约 20–40 秒）。 */
    suspend fun generateCustomerValue(id: Long): ApiResult<CustomerValueResponse> =
        call { api.generateCustomerValue(CustomerValueGenBody(id)) }

    // ───────────── 接诊/会话列表（「美丽档案」= web index.html）─────────────

    /**
     * 接诊列表（顾问端服务端自动按本人隔离）。
     * @param page 1 起页码
     * @param pageSize 每页条数（web 默认 10）
     * @param customer 顾客姓名模糊筛选（可空）
     * @param status 状态桶：running|queued|done|failed|stuck|idle（null/空=全部）
     * @param date ⏱时间筛选-日期 'YYYY-MM-DD'（可空）
     * @param timeFrom/timeTo ⏱时间筛选-时间从/到 'HH:MM'（可空）
     * @param timeType recording(录音时间) | analysis(最后分析时间)；仅在 date/time 任一存在时随附（对齐 web）
     */
    suspend fun sessions(
        page: Int,
        pageSize: Int = 10,
        customer: String? = null,
        status: String? = null,
        date: String? = null,
        timeFrom: String? = null,
        timeTo: String? = null,
        timeType: String? = null,
    ): ApiResult<SessionsResponse> {
        val d = date?.takeIf { it.isNotBlank() }
        val tf = timeFrom?.takeIf { it.isNotBlank() }
        val tt = timeTo?.takeIf { it.isNotBlank() }
        // 对齐 index.html：仅当 date/time 任一存在时才带 time_type
        val ty = timeType?.takeIf { it.isNotBlank() && (d != null || tf != null || tt != null) }
        return call {
            api.sessions(
                page, pageSize,
                customer?.takeIf { it.isNotBlank() },
                status?.takeIf { it.isNotBlank() },
                d, tf, tt, ty,
            )
        }
    }

    /** 接诊列表状态 pill 计数（customer/时间筛选与列表保持一致，对齐 refreshCounts）。 */
    suspend fun sessionStatusCounts(
        customer: String? = null,
        date: String? = null,
        timeFrom: String? = null,
        timeTo: String? = null,
        timeType: String? = null,
    ): ApiResult<StatusCountsResponse> {
        val d = date?.takeIf { it.isNotBlank() }
        val tf = timeFrom?.takeIf { it.isNotBlank() }
        val tt = timeTo?.takeIf { it.isNotBlank() }
        val ty = timeType?.takeIf { it.isNotBlank() && (d != null || tf != null || tt != null) }
        return call {
            api.sessionStatusCounts(customer?.takeIf { it.isNotBlank() }, d, tf, tt, ty)
        }
    }

    // ───────────── 会话预览 + 分析 ─────────────

    suspend fun sessionPreview(customerId: Long, date: String? = null): ApiResult<SessionPreview> =
        call { api.sessionPreview(customerId, date) }

    suspend fun sessionPreviewRemove(recordingId: Long): ApiResult<SimpleResult> =
        call { api.sessionPreviewRemove(PreviewRemoveBody(recordingId)) }

    suspend fun startAnalysis(customerId: Long, date: String? = null): ApiResult<SimpleResult> =
        call { api.startAnalysis(StartAnalysisBody(customerId, date)) }

    suspend fun cancelAnalysis(sessionId: Long): ApiResult<SimpleResult> =
        call { api.cancelAnalysis(CancelAnalysisBody(sessionId)) }

    // ───────────── 报告 / 任务 ─────────────

    suspend fun session(sid: Long): ApiResult<SessionDetail> = call { api.session(sid) }

    suspend fun sessionTasks(sid: Long): ApiResult<List<Task>> =
        call { api.sessionTasks(sid) }.map { it.tasks ?: emptyList() }

    suspend fun rerunTask(sid: Long, taskId: String, model: String? = null): ApiResult<SimpleResult> =
        call { api.rerunTask(sid, taskId, RerunBody(model)) }

    suspend fun fillMissingTasks(sid: Long, model: String? = null): ApiResult<SimpleResult> =
        call { api.fillMissingTasks(sid, RerunBody(model)) }

    /** 重新/补跑整个会话分析（后端自动判定补跑 vs 全量）。 */
    suspend fun analyzeSession(sid: Long, model: String? = null): ApiResult<SimpleResult> =
        call { api.analyzeSession(sid, AnalyzeBody(model)) }

    /** 顾问端按"顾客+日期段"批量触发分析。endDate 缺省=startDate。 */
    suspend fun consultantAnalyze(
        customer: String,
        startDate: String,
        endDate: String? = null,
    ): ApiResult<ConsultantAnalyzeResult> =
        call { api.consultantAnalyze(ConsultantAnalyzeBody(customer, startDate, endDate)) }

    // ───────────── 点评（evaluation）─────────────

    suspend fun addEvaluation(sid: Long, comment: String): ApiResult<EvaluateResult> =
        call { api.addEvaluation(sid, EvaluateBody(comment)) }

    suspend fun evaluations(sid: Long): ApiResult<List<Evaluation>> =
        call { api.evaluations(sid) }.map { it.evaluations ?: emptyList() }

    suspend fun deleteEvaluation(eid: Long): ApiResult<SimpleResult> =
        call { api.deleteEvaluation(eid) }

    suspend fun sessionCustomerTags(sid: Long, range: String? = null): ApiResult<CustomerTagsResponse> =
        call { api.sessionCustomerTags(sid, range) }

    suspend fun recordingUrl(rid: Long, forDownload: Boolean = false): ApiResult<String> =
        call { api.recordingUrl(rid, if (forDownload) 1 else null) }
            .let { r ->
                when (r) {
                    is ApiResult.Success -> {
                        val url = r.data.url
                        if (url.isNullOrBlank()) ApiResult.Failure(r.data.error ?: "未取到播放地址")
                        else ApiResult.Success(url)
                    }
                    is ApiResult.Failure -> r
                }
            }

    suspend fun rerunAsr(rid: Long): ApiResult<SimpleResult> = call { api.rerunAsr(rid) }

    // ───────────── 删除申请链 ─────────────

    /** 发起删除申请（走审批）。reason 可空。 */
    suspend fun deleteRequest(rid: Long, reason: String? = null): ApiResult<SimpleResult> =
        call { api.deleteRequest(rid, DeleteRequestBody(reason)) }

    /** 按 session 申请删除其下所有录音（档案行/报告页用）。reason 可空。 */
    suspend fun sessionDeleteRequest(sid: Long, reason: String? = null): ApiResult<SimpleResult> =
        call { api.sessionDeleteRequest(sid, DeleteRequestBody(reason)) }

    /** 按 session 撤回本接诊未审批的删除申请。 */
    suspend fun sessionDeleteRequestWithdraw(sid: Long): ApiResult<SimpleResult> =
        call { api.sessionDeleteRequestWithdraw(sid) }

    /** 撤回自己未审批的删除申请。 */
    suspend fun deleteRequestWithdraw(rid: Long): ApiResult<SimpleResult> =
        call { api.deleteRequestWithdraw(rid) }

    /** 关闭"删除被拒"提示。 */
    suspend fun deleteRequestDismiss(rid: Long): ApiResult<SimpleResult> =
        call { api.deleteRequestDismiss(rid) }

    /**
     * 按秒数把录音切成两段。
     * ⚠后端 @manager_required；顾问账号调用会 403，仅店长/管理员可用。
     */
    suspend fun splitRecording(rid: Long, atSeconds: Double): ApiResult<SimpleResult> =
        call { api.splitRecording(rid, SplitBody(atSeconds)) }

    // ───────────── 报告查看埋点 ─────────────

    /** 打开报告即上报"已查看"（关闭未查看提醒）。失败不影响阅读，调用方忽略结果即可。 */
    suspend fun reportViewEnter(sessionId: Long): ApiResult<SimpleResult> =
        call { api.reportViewEnter(ReportViewBody(sessionId = sessionId)) }

    /** 上报章节展开/停留时长（运营看板埋点）。失败静默。 */
    suspend fun reportViewPart(sessionId: Long, partKey: String, event: String, durationMs: Long = 0): ApiResult<SimpleResult> =
        call { api.reportViewPart(ReportViewPartBody(sessionId, partKey, event, durationMs)) }

    // ───────────── 提醒 ─────────────

    suspend fun reminders(): ApiResult<RemindersResponse> = call { api.reminders() }

    suspend fun handleReminder(rid: Long): ApiResult<SimpleResult> = call { api.handleReminder(rid) }

    // ───────────── 内部：统一调用 + 错误解析 ─────────────

    /** 在 IO 线程执行 Retrofit 调用，把 Response<T> 收敛成 ApiResult<T>。 */
    private suspend fun <T> call(block: suspend () -> Response<T>): ApiResult<T> =
        withContext(Dispatchers.IO) {
            try {
                val resp = block()
                if (resp.isSuccessful) {
                    val body = resp.body()
                    if (body != null) ApiResult.Success(body)
                    else ApiResult.Failure("空响应", resp.code())
                } else {
                    ApiResult.Failure(parseError(resp), resp.code())
                }
            } catch (e: Exception) {
                ApiResult.Failure(e.message ?: "网络异常", 0, e)
            }
        }

    /** 失败时从 errorBody 里抠后端 {"error": "..."} 文案，抠不到就给个通用文案。 */
    private fun <T> parseError(resp: Response<T>): String {
        return try {
            val raw = resp.errorBody()?.string()
            if (raw.isNullOrBlank()) return defaultErr(resp.code())
            val adapter = moshi.adapter(ErrorEnvelope::class.java)
            adapter.fromJson(raw)?.error ?: defaultErr(resp.code())
        } catch (e: Exception) {
            defaultErr(resp.code())
        }
    }

    private fun defaultErr(code: Int): String = when (code) {
        401 -> "登录已失效，请重新登录"
        403 -> "无权操作"
        404 -> "未找到"
        409 -> "操作冲突，请刷新后重试"
        else -> "请求失败（HTTP $code）"
    }

    private fun guessMediaType(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "m4a", "mp4" -> "audio/mp4"
            "ogg", "opus" -> "audio/ogg"
            "aac" -> "audio/aac"
            "amr" -> "audio/amr"
            "webm" -> "audio/webm"
            else -> "application/octet-stream"
        }
    }

    private data class ErrorEnvelope(val error: String? = null)

    companion object {
        private val TEXT = "text/plain".toMediaTypeOrNull()
    }
}
