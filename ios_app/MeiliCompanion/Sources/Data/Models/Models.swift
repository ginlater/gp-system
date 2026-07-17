import Foundation

/// 数据层领域模型(DTO)。android 端对应 `data/model/Models.kt`。
///
/// 字段名以 webapp.py 真实响应为准(SPEC §7),全部可空兜底。
/// 解析用 `.convertFromSnakeCase`(见 APIClient),故这里用 camelCase 即对上后端 snake_case。
/// 红线:数据层沿用后端真实 key(recording/pen),UI 层对外展示成「陪伴片段/陪伴笔」。

// MARK: - 账号 / 我

/// GET /api/me。role: consultant | store_manager | admin | super
struct Me: Decodable {
    var id: Int?
    var username: String?
    var role: String?
    var companyId: Int?
    var advisorName: String?
    var employeeId: String?
    var phone: String?
    var storeId: Int?
    var allowPhoneRec: Int?      // 录音来源权限门控(F8):0=未开通手机麦
    var allowPenRec: Int?        // 0=未开通陪伴笔
    var error: String?   // 未登录时 {"error":"未登录"} + 401
    // 多系统整合:本账号开通的系统清单(P0a 起返回;旧服务端缺省 nil=只有工牌,行为不变)
    var systems: [SystemEntry]?
    // 账号绑定(2026-07-16):回访/高情商的历史数据在独立 staff 账号名下,
    // 绑定后 App 用它静默登录(数据零迁移);nil/空 = 未绑定,退回统一密码。
    var scriptAccount: String?
    var scriptPassword: String?

    var isConsultantOrManager: Bool { role == "consultant" || role == "store_manager" }
    var isAdminOrSuper: Bool { role == "admin" || role == "super" }

    /// 是否多系统账号(≥2 个系统 → 登录后先落「工作台」宫格;单系统直进录音首页不变)。
    var multiSystem: Bool { (systems?.count ?? 0) >= 2 }
}

/// /api/me 的 systems 元素(SYSTEM_REGISTRY)。
/// type: native(工牌)| hybrid(teach,原生壳+课件 WebView)| web(网页壳系统)。
struct SystemEntry: Decodable, Hashable {
    var key: String?
    var name: String?
    var type: String?
    var desc: String?
    var url: String?
}

// MARK: - 版本 / 强制更新

/// GET /api/app/version(无需登录)。注意:后端这些 key 本就是 camelCase。
struct AppVersion: Decodable {
    var latestVersionCode: Int?
    var latestVersionName: String?
    var minVersionCode: Int?
    var apkUrl: String?
    var pageUrl: String?
    var updateNote: String?
}

// MARK: - 陪伴笔(pen)

struct PenBinding: Decodable {
    var penSn: String?
}

struct PenSnReport: Decodable {
    var ok: Bool?
    var boundSn: String?
    var match: Bool?
    var decision: String?   // allow | deny
    var reason: String?     // my_pen | free | have_other_binding | bound_other
    var message: String?
    var allowed: Bool { decision == "allow" }
}

struct PenSyncItem: Decodable {
    var name: String?
    var status: String?     // uploaded | deleted | new
    var existingId: Int?
}

struct PenSyncPreview: Decodable {
    var items: [PenSyncItem]?
}

// MARK: - 上传 / 占位

struct UploadResult: Decodable {
    var id: Int?
    var ossKey: String?
    var deduped: Bool?
    var penExact: Bool?     // deduped 时:是否按机身文件名精确匹配(否=按录音时刻认亲,可能认错)
    var discarded: Bool?    // 命中墓碑被丢弃(顾问删过该段),未入库
    var error: String?
}

struct PlaceholderResult: Decodable {
    var id: Int?
    var error: String?
}

/// /api/consultant/pen/report-sn 的准/拒决策(策略集中在后端)。
struct PenSnDecision: Decodable {
    var decision: String?   // "allow" / "deny"
    var reason: String?
    var message: String?
}

// MARK: - 待整理片段(pending)

/// upload_status=processing 时是「陪伴笔补传占位」,audio_url 为 null(不可试听)。
struct PendingRecording: Decodable, Identifiable {
    var id: Int
    var ossKey: String?
    var recordedAt: String?
    var durationLabel: String?
    var sizeBytes: Int?
    var asrStatus: String?
    var asrError: String?
    var customer: String?
    var createdAt: String?
    var asrSpeakerCount: Int?
    var asrSpeakerWarning: Int?
    var uploadStatus: String?    // processing | done
    var truncateNote: String?
    var audioUrl: String?
    var serviceDate: String?
    var startHm: String?
    var endHm: String?
    var durationMin: Int?
    var recDate: String?
    var deleteRequestId: Int?
    var deleteRequestStatus: String?
    var deleteRejectReason: String?

    var isProcessing: Bool { uploadStatus == "processing" }
    var hasSpeakerWarning: Bool { (asrSpeakerWarning ?? 0) == 1 }
}

struct PendingRecordingsResponse: Decodable {
    var recordings: [PendingRecording]?
}

struct PendingDatesResponse: Decodable {
    var dates: [String]?
}

struct NeedsConfirmRecording: Decodable, Identifiable {
    var id: Int
    var ossKey: String?
    var recordedAt: String?
    var customer: String?
    var advisor: String?
    var sessionId: Int?
    var asrSpeakerCount: Int?
    var asrStatus: String?
    var createdAt: String?
    var audioUrl: String?
}

struct NeedsConfirmResponse: Decodable {
    var recordings: [NeedsConfirmRecording]?
}

// MARK: - 顾客 / 候选

struct Customer: Decodable {
    var id: Int?
    var customerId: Int?
    var name: String?
    var phoneTail: String?
    var memberCard: String?
    var inDay: Bool?

    /// 统一主键:优先 id,回退 customer_id。
    var cid: Int? { id ?? customerId }
}

struct CustomerLookupResponse: Decodable {
    var customers: [Customer]?
}

struct CustomerSearchResponse: Decodable {
    var customers: [Customer]?
}

struct RebindCandidatesResponse: Decodable {
    var items: [Customer]?
    var serviceDate: String?
}

// MARK: - 今日接诊(reception)

struct TodayReception: Decodable, Identifiable {
    var id: Int                       // daily_reception.id (dr_id)
    var customerId: Int?
    var name: String?
    var phoneTail: String?
    var memberCard: String?
    var serviceDate: String?
    var sessionId: Int?
    var locked: Bool?
    var analysisStatus: String?
    var recordingCount: Int?
    var pendingRebindCount: Int?
}

struct TodayReceptionResponse: Decodable {
    var items: [TodayReception]?
    var date: String?
}

// MARK: - 会话预览(preview)

struct PreviewRecording: Decodable, Identifiable {
    var id: Int
    var ossKey: String?
    var recordedAt: String?
    var durationLabel: String?
    var asrStatus: String?
    var asrError: String?
    var asrSpeakerCount: Int?
    var asrSpeakerWarning: Int?
    var speakerConfirmed: Int?
    var createdAt: String?
    var audioUrl: String?
    var pendingRebindRequestId: Int?
}

struct TaskProgress: Decodable {
    var total: Int?
    var done: Int?
    var running: Int?
    var failed: Int?
    var pending: Int?
    var doneNames: [String]?
    var runningNames: [String]?
    var failedNames: [String]?
    var pendingNames: [String]?
    var waitSec: Int?
    var progressText: String?
}

struct SessionPreview: Decodable {
    var customer: Customer?
    var serviceDate: String?
    var sessionId: Int?
    var locked: Bool?
    var analysisStatus: String?
    /// ★2026-07-13 老客评分维度:'new'=新客 / 'returning'=老客(默认)。评分维度按它二选一。
    var customerType: String?
    var taskProgress: TaskProgress?
    var bound: [PreviewRecording]?
    var unbound: [PreviewRecording]?
    var error: String?
}

struct CustomerRecordingsGroup: Decodable {
    var serviceDate: String?
    var recordings: [PreviewRecording]?
}

struct CustomerRecordingsResponse: Decodable {
    var groups: [CustomerRecordingsGroup]?
}

// MARK: - 提醒(reminders)

struct Reminder: Decodable, Identifiable {
    var id: Int
    var kind: String?
    var level: Int?          // 后端是数字等级(1/2…),不是字符串
    var channel: String?     // inapp | phone | escalation
    var refType: String?
    var refId: Int?
    var message: String?
    var createdAt: String?
    var scope: String?       // personal | escalation
    var advisorUserId: Int?
    var advisorName: String?
    var sessionId: Int?
    var result: String?
    var isRead: Bool?
    var isHandled: Bool?
}

struct RemindersResponse: Decodable {
    var count: Int?
    var personalCount: Int?
    var escalationCount: Int?
    var items: [Reminder]?
}

// MARK: - 录音 URL / 通用

struct RecordingUrlResponse: Decodable {
    var url: String?
    var error: String?
}

struct ConsultantAnalyzeResult: Decodable {
    var ok: Bool?
    var error: String?
    var sessionIds: [Int]?
    var skipped: [Int]?
}

/// 大量 POST 端点统一返回 {ok / error / ...}。可空兜底。
struct SimpleResult: Decodable {
    var ok: Bool?
    var error: String?
    var msg: String?
    var sessionId: Int?
    var customerId: Int?
    var requestId: Int?
    var deleted: Bool?
    var unboundCount: Int?
    var unchanged: Bool?
    var status: String?
    var taskId: String?
    var name: String?
    var phoneTail: String?
    var memberCard: String?
    var serviceDate: String?
    var id: Int?
    var analysisTriggered: Bool?
    var newSessionId: Int?
    var progress: String?
    var model: String?
    var mode: String?
    var tasks: [String]?
}
