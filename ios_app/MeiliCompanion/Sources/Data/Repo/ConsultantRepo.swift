import Foundation

/// 顾问端端点封装。android 端对应 `data/repo/ConsultantRepository.kt`。
/// 集中端点路径/参数;ViewModel 只调这里,不散写字符串。按屏所需逐步补方法。
enum ConsultantRepo {
    private static var api: APIClient { .shared }

    // ── 接诊/会话列表(报告 tab)──

    static func sessions(page: Int, pageSize: Int = 10, customer: String?,
                         status: String?, date: String? = nil,
                         timeFrom: String? = nil, timeTo: String? = nil,
                         timeType: String? = nil) async throws -> SessionsResponse {
        try await api.get("api/sessions", query: APIClient.q([
            "page": String(page),
            "page_size": String(pageSize),
            "customer": customer.flatMap { $0.isEmpty ? nil : $0 },
            "status": status,
            "date": date,
            "time_from": timeFrom,
            "time_to": timeTo,
            "time_type": timeType,
        ]))
    }

    static func sessionStatusCounts(customer: String?, date: String? = nil,
                                    timeFrom: String? = nil, timeTo: String? = nil,
                                    timeType: String? = nil) async throws -> StatusCountsResponse {
        try await api.get("api/sessions/status_counts", query: APIClient.q([
            "customer": customer.flatMap { $0.isEmpty ? nil : $0 },
            "date": date,
            "time_from": timeFrom,
            "time_to": timeTo,
            "time_type": timeType,
        ]))
    }

    // ── 上传陪伴音频 / 占位 ──

    static func uploadAudio(fileURL: URL, durationSec: Int, recordedAt: String?,
                            contentType: String = "audio/m4a",
                            placeholderId: Int? = nil,
                            penFile: String? = nil, sn: String? = nil) async throws -> UploadResult {
        try await api.uploadMultipart("api/consultant/upload", fileURL: fileURL, contentType: contentType,
            fields: ["duration_sec": String(durationSec), "recorded_at": recordedAt,
                     "placeholder_id": placeholderId.map(String.init),
                     "pen_file": penFile,     // 机身文件名:后端按 (上传人,pen_file) 去重/完整版替换损坏段
                     "sn": sn])               // 笔SN:审计 + 管理员绑定
    }

    /// 录完立即占位:未归档列表几秒内冒「处理中」(补下载再久也有行可盯);上传时带 placeholder_id 回填同一行。
    /// source=phone 必传(手机麦),否则后端按录音笔权限校验会误拦只开手机权限的顾问。
    static func placeholder(recordedAt: String?, source: String?) async throws -> PlaceholderResult {
        try await api.postForm("api/consultant/placeholder", fields: ["recorded_at": recordedAt, "source": source])
    }

    /// 上传失败/内容丢失时清掉占位,不留「处理中」僵尸行。
    @discardableResult
    static func cancelPlaceholder(_ id: Int) async throws -> SimpleResult {
        try await api.postForm("api/consultant/placeholder/cancel", fields: ["placeholder_id": String(id)])
    }

    /// 连接陪伴笔后上报 SN:后端记 sighting 并返回准/拒决策(策略集中在后端)。
    static func reportPenSn(_ sn: String) async throws -> PenSnDecision {
        try await api.postForm("api/consultant/pen/report-sn", fields: ["sn": sn])
    }

    /// 「从陪伴笔同步」去重预检:给机身文件列表(name+ra),返回每条 uploaded/deleted/new。
    static func penSyncPreview(_ items: [(name: String, ra: String?)]) async throws -> PenSyncPreview {
        struct Body: Encodable {
            struct Item: Encodable { var name: String; var ra: String? }
            var items: [Item]
        }
        return try await api.postJSON("api/consultant/pen/sync-preview",
            body: Body(items: items.map { .init(name: $0.name, ra: $0.ra) }))
    }

    // ── 待整理 ──

    static func pending() async throws -> [PendingRecording] {
        let r: PendingRecordingsResponse = try await api.get("api/consultant/recordings/pending")
        return r.recordings ?? []
    }

    // ── 今日接诊 ──

    static func todayReception(_ date: String?) async throws -> TodayReceptionResponse {
        try await api.get("api/consultant/today_reception", query: APIClient.q(["date": date]))
    }
    static func addTodayReception(customerId: Int? = nil, name: String? = nil,
                                  phoneTail: String? = nil, memberCard: String? = nil,
                                  date: String?) async throws -> SimpleResult {
        try await api.postJSON("api/consultant/today_reception/add",
            body: TodayReceptionAddBody(customerId: customerId, name: name, phoneTail: phoneTail, memberCard: memberCard, date: date))
    }
    static func removeTodayReception(_ drId: Int) async throws -> SimpleResult {
        try await api.delete("api/consultant/today_reception/\(drId)")
    }

    // ── 绑定候选 / 新增当日顾客 ──

    static func rebindCandidates(rid: Int, q: String?) async throws -> RebindCandidatesResponse {
        try await api.get("api/consultant/rebind_candidates", query: APIClient.q(["rid": String(rid), "q": q]))
    }
    static func addDayCustomer(rid: Int, name: String, phoneTail: String?) async throws -> SimpleResult {
        try await api.postJSON("api/consultant/recordings/\(rid)/add_day_customer",
            body: AddDayCustomerBody(name: name, phoneTail: phoneTail))
    }

    // ── 顾客查询 ──

    /// 顾问端可见顾客(空 q = 最近接待一批)。
    static func customerLookup(_ q: String?) async throws -> [Customer] {
        let r: CustomerLookupResponse = try await api.get("api/consultant/customer_lookup", query: APIClient.q(["q": q]))
        return r.customers ?? []
    }
    /// 通用顾客搜索(姓名/卡号)。
    static func customersSearch(_ q: String?) async throws -> [Customer] {
        let r: CustomerSearchResponse = try await api.get("api/customers/search", query: APIClient.q(["q": q]))
        return r.customers ?? []
    }

    // ── 提醒 ──

    static func reminders() async throws -> RemindersResponse {
        try await api.get("api/consultant/reminders")
    }
    static func handleReminder(_ rid: Int) async throws -> SimpleResult {
        try await api.postJSON("api/manager/reminder/\(rid)/handle", body: EmptyBody())
    }

    // ── 美丽档案 ──

    static func customerProfile(_ cid: Int, range: String = "all") async throws -> CustomerProfileResponse {
        try await api.get("api/admin/customer_profile", query: APIClient.q(["customer_id": String(cid), "range": range]))
    }
    static func customerValue(_ cid: Int) async throws -> CustomerValueResponse {
        try await api.get("api/admin/customer_value", query: APIClient.q(["customer_id": String(cid)]))
    }
    static func generateCustomerValue(_ cid: Int) async throws -> CustomerValueResponse {
        try await api.postJSON("api/admin/customer_value", body: CustomerValueGenBody(customerId: cid))
    }

    // ── 报告详情 / 任务 / 标签 / 音频 / 点评 ──

    static func session(_ sid: Int) async throws -> SessionDetail {
        try await api.get("api/session/\(sid)")
    }
    static func sessionTasks(_ sid: Int) async throws -> TasksResponse {
        try await api.get("api/session/\(sid)/tasks")
    }
    static func sessionCustomerTags(_ sid: Int, range: String? = nil) async throws -> CustomerTagsResponse {
        try await api.get("api/session/\(sid)/customer_tags", query: APIClient.q(["range": range]))
    }
    static func recordingUrl(_ rid: Int) async throws -> RecordingUrlResponse {
        try await api.get("api/recording/\(rid)/url")
    }
    static func evaluations(_ sid: Int) async throws -> EvaluationsResponse {
        try await api.get("api/session/\(sid)/evaluations")
    }
    /// 打开报告上报「已查看」(关该 session 未查看提醒)。fire-and-forget。
    static func reportViewEnter(_ sid: Int) async {
        _ = try? await api.postJSON("api/report_view/enter", body: ReportViewBody(sessionId: sid)) as SimpleResult
    }

    // ── 会话预览 / 绑定 / 开始分析 ──

    static func sessionPreview(customerId: Int, date: String?) async throws -> SessionPreview {
        try await api.get("api/consultant/session/preview",
            query: APIClient.q(["customer_id": String(customerId), "date": date]))
    }
    static func sessionPreviewRemove(recordingId: Int) async throws -> SimpleResult {
        try await api.postJSON("api/consultant/session/preview/remove", body: PreviewRemoveBody(recordingId: recordingId))
    }
    static func bind(recordingId: Int, customerId: Int) async throws -> SimpleResult {
        try await api.postJSON("api/consultant/recordings/\(recordingId)/bind", body: BindBody(customerId: customerId))
    }
    static func confirmSpeakers(recordingId: Int, action: String) async throws -> SimpleResult {
        try await api.postJSON("api/consultant/recordings/\(recordingId)/confirm_speakers", body: ConfirmSpeakersBody(action: action))
    }
    static func startAnalysis(customerId: Int, date: String?) async throws -> SimpleResult {
        try await api.postJSON("api/consultant/session/start_analysis", body: StartAnalysisBody(customerId: customerId, date: date))
    }
    static func cancelAnalysis(sessionId: Int) async throws -> SimpleResult {
        try await api.postJSON("api/consultant/session/cancel_analysis", body: CancelAnalysisBody(sessionId: sessionId))
    }

    static func sessionDeleteRequest(sessionId: Int, reason: String?) async throws -> SimpleResult {
        try await api.postJSON("api/session/\(sessionId)/delete-request", body: DeleteRequestBody(reason: reason))
    }

    // ── 换绑 / 删除(2026-07-04 用户拍板:换绑退回免理由免审批;删除≤5分钟直删、>5分钟走审批)──

    /// 直接换绑(免审批):只移动这一段到目标顾客;目标是本人接待过的会自动补登当天接诊。
    static func directRebind(recordingId: Int, toCustomerId: Int) async throws -> SimpleResult {
        try await api.postJSON("api/consultant/recordings/\(recordingId)/direct_rebind",
            body: DirectRebindBody(toCustomerId: toCustomerId))
    }

    /// 删除录音:后端裁决——时长≤5分钟直接删(回 deleted=true),>5分钟生成审批单等管理员。
    static func requestDeleteRecording(_ rid: Int) async throws -> SimpleResult {
        try await api.postJSON("api/recording/\(rid)/delete-request", body: EmptyBody())
    }
}
