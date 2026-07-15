package com.airec.bledemo.data.api

import com.airec.bledemo.data.model.*
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

/* ===================================================================
 * Retrofit 接口：覆盖 SPEC §7 的顾问端端点。
 * base = https://gp.aibeautyfulwomen.com（见 NetworkModule）。
 * Cookie 会话鉴权由 PrefsCookieJar 自动带上。
 *
 * 全部返回 Response<T>，便于 Repository 区分 HTTP 状态码 + 取后端 error 文案。
 * 字段以 webapp.py 真实路由为准（见 model 包）。
 * =================================================================== */
interface ConsultantApi {

    // ───────────── 鉴权 / 我 / 版本 ─────────────

    /** 表单登录。成功后服务端 Set-Cookie，PrefsCookieJar 自动持久化。
     *  字段名以 webapp.py /login 为准：username + password。
     *  302 重定向交给 OkHttp followRedirects；用 200/302 判定成功。 */
    @FormUrlEncoded
    @POST("login")
    suspend fun login(
        @Field("username") username: String,
        @Field("password") password: String,
        @Field("next") next: String? = null,
    ): Response<ResponseBody>

    @GET("logout")
    suspend fun logout(): Response<ResponseBody>

    @GET("api/me")
    suspend fun me(): Response<Me>

    /** 顾问改密(工牌主账号)。体 {old_password,new_password}。 */
    @POST("api/consultant/change-password")
    suspend fun changePassword(
        @Body body: Map<String, @JvmSuppressWildcards Any?>,
    ): Response<ResponseBody>

    /** v2 原生包独立版本检查（启动提示更新）。无需登录。 */
    @GET("api/app/v2/version")
    suspend fun appVersionV2(): Response<AppVersion>

    // ───────────── 陪伴上传 / 占位 ─────────────

    /**
     * 陪伴片段上传（multipart）。
     * file=音频；可带 duration_sec / recorded_at('YYYY-MM-DD HH:MM:SS') /
     * placeholder_id / pen_file / sn。
     * 调用方用 MultipartBody.Part / Part Map 拼装。
     */
    @Multipart
    @POST("api/consultant/upload")
    suspend fun upload(
        @Part file: MultipartBody.Part,
        @PartMap fields: Map<String, @JvmSuppressWildcards okhttp3.RequestBody>,
    ): Response<UploadResult>

    /** 上传运行诊断：meta(设备/版本/笔状态 JSON) + penlog/last_result 日志文件，给工程师远程排查陪伴笔问题。 */
    @Multipart
    @POST("api/consultant/diag/upload")
    suspend fun uploadDiag(
        @PartMap fields: Map<String, @JvmSuppressWildcards okhttp3.RequestBody>,
        @Part files: List<MultipartBody.Part>,
    ): Response<SimpleResult>

    /** 结束陪伴时先建 processing 占位行（recorded_at 可选，表单字段）。 */
    @FormUrlEncoded
    @POST("api/consultant/placeholder")
    suspend fun createPlaceholder(
        @Field("recorded_at") recordedAt: String? = null,
    ): Response<PlaceholderResult>

    /** 上传最终失败时清掉占位。 */
    @FormUrlEncoded
    @POST("api/consultant/placeholder/cancel")
    suspend fun cancelPlaceholder(
        @Field("placeholder_id") placeholderId: Long,
    ): Response<SimpleResult>

    // ───────────── 陪伴笔绑定 / 同步 ─────────────

    @GET("api/consultant/pen/binding")
    suspend fun penBinding(): Response<PenBinding>

    /** "从陪伴笔同步"：给机身片段列表，返回每条 uploaded/deleted/new。 */
    @POST("api/consultant/pen/sync-preview")
    suspend fun penSyncPreview(
        @Body body: PenSyncPreviewBody,
    ): Response<PenSyncPreview>

    // ───────────── 待整理（pending）全套 ─────────────

    @GET("api/consultant/recordings/pending")
    suspend fun pending(): Response<PendingRecordingsResponse>

    @GET("api/consultant/recordings/pending_dates")
    suspend fun pendingDates(): Response<PendingDatesResponse>

    /** 换绑候选（仅本人接待过的客人）。rid 用于标注 in_day / 回填 service_date。 */
    @GET("api/consultant/rebind_candidates")
    suspend fun rebindCandidates(
        @Query("rid") rid: Long? = null,
        @Query("q") q: String? = null,
    ): Response<RebindCandidatesResponse>

    /** 多说话人误录待确认列表。 */
    @GET("api/consultant/recordings/needs_confirm")
    suspend fun needsConfirm(): Response<NeedsConfirmResponse>

    /** 绑定到今日接诊里的某顾客（建/找 session 并触发分析链路）。 */
    @POST("api/consultant/recordings/{rid}/bind")
    suspend fun bind(
        @Path("rid") rid: Long,
        @Body body: BindBody,
    ): Response<SimpleResult>

    /** 直接换绑（无需审批）。 */
    @POST("api/consultant/recordings/{rid}/direct_rebind")
    suspend fun directRebind(
        @Path("rid") rid: Long,
        @Body body: DirectRebindBody,
    ): Response<SimpleResult>

    /** 申请换绑（走审批）。 */
    @POST("api/consultant/recordings/{rid}/rebind_request")
    suspend fun rebindRequest(
        @Path("rid") rid: Long,
        @Body body: RebindRequestBody,
    ): Response<SimpleResult>

    /** 退回未整理（解绑）。 */
    @POST("api/consultant/recordings/{rid}/unbind")
    suspend fun unbind(
        @Path("rid") rid: Long,
        @Body body: UnbindBody,
    ): Response<SimpleResult>

    /** 换绑弹窗"＋新增客人"：为该片段当天建客人 + 补登接诊。 */
    @POST("api/consultant/recordings/{rid}/add_day_customer")
    suspend fun addDayCustomer(
        @Path("rid") rid: Long,
        @Body body: AddDayCustomerBody,
    ): Response<SimpleResult>

    /** 删除换绑时误建的客人。 */
    @POST("api/consultant/recordings/{rid}/remove_day_customer")
    suspend fun removeDayCustomer(
        @Path("rid") rid: Long,
        @Body body: RemoveDayCustomerBody,
    ): Response<SimpleResult>

    /** 确认说话人（action=keep 照常分析 / action=unbind 解绑）。 */
    @POST("api/consultant/recordings/{rid}/confirm_speakers")
    suspend fun confirmSpeakers(
        @Path("rid") rid: Long,
        @Body body: ConfirmSpeakersBody,
    ): Response<SimpleResult>

    // ───────────── 今日接诊（reception）─────────────

    @GET("api/consultant/today_reception")
    suspend fun todayReception(
        @Query("date") date: String? = null,
    ): Response<TodayReceptionResponse>

    @POST("api/consultant/today_reception/add")
    suspend fun addTodayReception(
        @Body body: TodayReceptionAddBody,
    ): Response<SimpleResult>

    @DELETE("api/consultant/today_reception/{drId}")
    suspend fun removeTodayReception(
        @Path("drId") drId: Long,
    ): Response<SimpleResult>

    @PATCH("api/consultant/today_reception/{drId}/date")
    suspend fun changeTodayReceptionDate(
        @Path("drId") drId: Long,
        @Body body: ChangeDateBody,
    ): Response<SimpleResult>

    // ───────────── 顾客查询 ─────────────

    /** 顾问端搜本公司顾客（加入今日接诊用）。 */
    @GET("api/consultant/customer_lookup")
    suspend fun customerLookup(
        @Query("q") q: String? = null,
    ): Response<CustomerLookupResponse>

    /** 通用顾客搜索（档案搜索用）。 */
    @GET("api/customers/search")
    suspend fun customersSearch(
        @Query("q") q: String? = null,
    ): Response<CustomerSearchResponse>

    /** 美丽档案：顾客基本信息 + 累积标签 + 服务时间线。range: all|30|90|180|365。 */
    @GET("api/admin/customer_profile")
    suspend fun customerProfile(
        @Query("customer_id") id: Long,
        @Query("range") range: String,
    ): Response<CustomerProfileResponse>

    /** 美丽档案：客户价值预测（只读，缓存内容）。 */
    @GET("api/admin/customer_value")
    suspend fun customerValue(
        @Query("customer_id") id: Long,
    ): Response<CustomerValueResponse>

    /** 美丽档案：生成/刷新客户价值预测（POST，强制重算；后端对所有登录角色开放）。 */
    @POST("api/admin/customer_value")
    suspend fun generateCustomerValue(
        @Body body: CustomerValueGenBody,
    ): Response<CustomerValueResponse>

    // ───────────── 接诊/会话列表（「美丽档案」= web index.html）─────────────

    /**
     * 接诊列表（顾问端自动按本人 advisor_name 服务端隔离，无需传 advisor）。
     * status: running|queued|done|failed|stuck|idle（逗号分隔多选；null/空=全部）。
     * ⏱时间筛选（对齐 index.html）：date='YYYY-MM-DD'、timeFrom/timeTo='HH:MM'、
     * timeType=recording|analysis（按录音时间还是最后分析时间过滤；仅在有 date/time 时随附）。
     */
    @GET("api/sessions")
    suspend fun sessions(
        @Query("page") page: Int,
        @Query("page_size") pageSize: Int,
        @Query("customer") customer: String?,
        @Query("status") status: String?,
        @Query("date") date: String? = null,
        @Query("time_from") timeFrom: String? = null,
        @Query("time_to") timeTo: String? = null,
        @Query("time_type") timeType: String? = null,
    ): Response<SessionsResponse>

    /** 状态筛选 pill 的计数（同样服务端隔离；customer/时间筛选与列表保持一致）。 */
    @GET("api/sessions/status_counts")
    suspend fun sessionStatusCounts(
        @Query("customer") customer: String?,
        @Query("date") date: String? = null,
        @Query("time_from") timeFrom: String? = null,
        @Query("time_to") timeTo: String? = null,
        @Query("time_type") timeType: String? = null,
    ): Response<StatusCountsResponse>

    // ───────────── 会话预览 + 开始分析 ─────────────

    @GET("api/consultant/session/preview")
    suspend fun sessionPreview(
        @Query("customer_id") customerId: Long,
        @Query("date") date: String? = null,
    ): Response<SessionPreview>

    @POST("api/consultant/session/preview/remove")
    suspend fun sessionPreviewRemove(
        @Body body: PreviewRemoveBody,
    ): Response<SimpleResult>

    @POST("api/consultant/session/start_analysis")
    suspend fun startAnalysis(
        @Body body: StartAnalysisBody,
    ): Response<SimpleResult>

    @POST("api/consultant/session/cancel_analysis")
    suspend fun cancelAnalysis(
        @Body body: CancelAnalysisBody,
    ): Response<SimpleResult>

    /** ★2026-07-13 老客评分维度：标注本单客型(新客/老客)，评分维度按它二选一。 */
    @POST("api/session/{sid}/customer_type")
    suspend fun setCustomerType(
        @Path("sid") sid: Long,
        @Body body: CustomerTypeBody,
    ): Response<SimpleResult>

    // ───────────── 报告 / 任务重跑 ─────────────

    @GET("api/session/{sid}")
    suspend fun session(
        @Path("sid") sid: Long,
    ): Response<SessionDetail>

    @GET("api/session/{sid}/tasks")
    suspend fun sessionTasks(
        @Path("sid") sid: Long,
    ): Response<TasksResponse>

    @POST("api/session/{sid}/task/{tid}/rerun")
    suspend fun rerunTask(
        @Path("sid") sid: Long,
        @Path("tid") tid: String,
        @Body body: RerunBody = RerunBody(),
    ): Response<SimpleResult>

    @POST("api/session/{sid}/tasks/fill-missing")
    suspend fun fillMissingTasks(
        @Path("sid") sid: Long,
        @Body body: RerunBody = RerunBody(),
    ): Response<SimpleResult>

    /**
     * 重新/补跑整个会话的分析（可指定 model）。
     * 后端自动判定：failed 且仅缺部分任务→只补跑；其它→全量重跑。
     * 返回 {status, progress, model, mode, tasks}。
     */
    @POST("api/session/{sid}/analyze")
    suspend fun analyzeSession(
        @Path("sid") sid: Long,
        @Body body: AnalyzeBody = AnalyzeBody(),
    ): Response<SimpleResult>

    // ───────────── 点评（evaluation）─────────────

    /** 追加一条点评。体 {comment}（必填）。返回 {ok, evaluation}。 */
    @POST("api/session/{sid}/evaluate")
    suspend fun addEvaluation(
        @Path("sid") sid: Long,
        @Body body: EvaluateBody,
    ): Response<EvaluateResult>

    /** 某会话的点评列表（倒序）。 */
    @GET("api/session/{sid}/evaluations")
    suspend fun evaluations(
        @Path("sid") sid: Long,
    ): Response<EvaluationsResponse>

    /** 删除一条点评（按 evaluation id）。 */
    @DELETE("api/evaluation/{eid}")
    suspend fun deleteEvaluation(
        @Path("eid") eid: Long,
    ): Response<SimpleResult>

    /** PART 10 动态顾客标签（range: all|30|90|180|365）。 */
    @GET("api/session/{sid}/customer_tags")
    suspend fun sessionCustomerTags(
        @Path("sid") sid: Long,
        @Query("range") range: String? = null,
    ): Response<CustomerTagsResponse>

    /** 按需签名的播放 URL（全程，无 60s 限制）。 */
    @GET("api/recording/{rid}/url")
    suspend fun recordingUrl(
        @Path("rid") rid: Long,
        @Query("download") download: Int? = null,
    ): Response<RecordingUrlResponse>

    /** 手动重跑 ASR（兜底）。 */
    @POST("api/recording/{rid}/asr")
    suspend fun rerunAsr(
        @Path("rid") rid: Long,
    ): Response<SimpleResult>

    // ───────────── 删除申请链（顾问端）─────────────

    /** 发起删除申请（走管理员审批）。体 {reason}（可空）。 */
    @POST("api/recording/{rid}/delete-request")
    suspend fun deleteRequest(
        @Path("rid") rid: Long,
        @Body body: DeleteRequestBody = DeleteRequestBody(),
    ): Response<SimpleResult>

    /** 按 session 申请删除其下所有录音（顾问端；档案行/报告页用）。体 {reason}（可空）。 */
    @POST("api/session/{sid}/delete-request")
    suspend fun sessionDeleteRequest(
        @Path("sid") sid: Long,
        @Body body: DeleteRequestBody = DeleteRequestBody(),
    ): Response<SimpleResult>

    /** 撤回自己未审批的删除申请（无请求体）。 */
    @POST("api/recording/{rid}/delete-request/withdraw")
    suspend fun deleteRequestWithdraw(
        @Path("rid") rid: Long,
    ): Response<SimpleResult>

    /** 按 session 撤回本接诊未审批的删除申请（撤销该 session 下自己仍 pending 的申请；无请求体）。 */
    @POST("api/session/{sid}/delete-request/withdraw")
    suspend fun sessionDeleteRequestWithdraw(
        @Path("sid") sid: Long,
    ): Response<SimpleResult>

    /** 关闭"删除被拒"提示：把最新一条 rejected 标记 dismissed（无请求体）。 */
    @POST("api/recording/{rid}/delete-request/dismiss")
    suspend fun deleteRequestDismiss(
        @Path("rid") rid: Long,
    ): Response<SimpleResult>

    /**
     * 按秒数把一条录音切成两段（都留在原接诊里）。体 {at_seconds}。
     * ⚠后端 @manager_required（店长/管理员），路径在 admin 命名空间；顾问端通常不可用。
     */
    @POST("api/admin/recordings/{rid}/split")
    suspend fun splitRecording(
        @Path("rid") rid: Long,
        @Body body: SplitBody,
    ): Response<SimpleResult>

    // ───────────── 报告查看埋点 ─────────────

    /** 打开报告即上报"已查看"：后端关闭该 session 的未查看提醒，红点即时 -1。 */
    @POST("api/report_view/enter")
    suspend fun reportViewEnter(@Body body: ReportViewBody): Response<SimpleResult>

    /** 上报章节展开 / 停留时长，喂运营看板的「展开章节」「总时长」。 */
    @POST("api/report_view/part")
    suspend fun reportViewPart(@Body body: ReportViewPartBody): Response<SimpleResult>

    // ───────────── 提醒 ─────────────

    @GET("api/consultant/reminders")
    suspend fun reminders(): Response<RemindersResponse>

    /** E7 方案B(2026-07-04):打开提醒页才算已读——服务端已不再"拉取即已读"。 */
    @POST("api/consultant/reminders/mark_read")
    suspend fun markRemindersRead(): Response<SimpleResult>

    /** 店长"已跟进"升级项。 */
    @POST("api/manager/reminder/{rid}/handle")
    suspend fun handleReminder(
        @Path("rid") rid: Long,
    ): Response<SimpleResult>
}
