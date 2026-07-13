import Foundation

/// POST/PATCH 端点的 JSON 请求体。android 端对应 `data/model/RequestBodies.kt`。
/// 编码用 `.convertToSnakeCase`(见 APIClient),故 camelCase 即对上后端 snake_case key。

struct BindBody: Encodable { let customerId: Int }
struct CustomerValueGenBody: Encodable { let customerId: Int }
struct DirectRebindBody: Encodable { let toCustomerId: Int; var reason: String? = nil }
struct RebindRequestBody: Encodable { let toCustomerId: Int; let reason: String }
struct UnbindBody: Encodable { let reason: String }
struct AddDayCustomerBody: Encodable { let name: String; var phoneTail: String? = nil }
struct RemoveDayCustomerBody: Encodable { let customerId: Int }
struct ConfirmSpeakersBody: Encodable { let action: String }   // keep | unbind

struct TodayReceptionAddBody: Encodable {
    var customerId: Int? = nil
    var name: String? = nil
    var phoneTail: String? = nil
    var memberCard: String? = nil
    var date: String? = nil
}
struct ChangeDateBody: Encodable { let date: String }
struct PreviewRemoveBody: Encodable { let recordingId: Int }
struct StartAnalysisBody: Encodable { let customerId: Int; var date: String? = nil }
struct CancelAnalysisBody: Encodable { let sessionId: Int }
/// ★2026-07-13 老客评分维度:'new'=新客 / 'returning'=老客(默认)。
struct CustomerTypeBody: Encodable { let type: String }
struct RerunBody: Encodable { var model: String? = nil }
struct DeleteRequestBody: Encodable { var reason: String? = nil }
struct EvaluateBody: Encodable { let comment: String }
struct AnalyzeBody: Encodable { var model: String? = nil }
struct ConsultantAnalyzeBody: Encodable { let customer: String; let startDate: String; var endDate: String? = nil }
struct SplitBody: Encodable { let atSeconds: Double }
struct ReportViewBody: Encodable { let sessionId: Int; var source: String = "app" }
struct ReportViewPartBody: Encodable {
    let sessionId: Int
    let partKey: String
    let event: String       // enter | duration
    var durationMs: Int = 0
}
struct PenSyncQueryItem: Encodable { let name: String; var ra: String? = nil }
struct PenSyncPreviewBody: Encodable { let items: [PenSyncQueryItem] }
/// 空 body(无请求体的 POST,如 handle reminder)。
struct EmptyBody: Encodable {}
