import Foundation

/// 接诊/会话列表(「报告」tab = web index.html)。android 端对应 `data/model/SessionList.kt`。
/// 顾问端只看到自己名下接诊(服务端按 advisor_name 隔离),每行 → 一份分析报告。

struct SessionsResponse: Decodable {
    var sessions: [SessionRow]?
    var total: Int?
    var page: Int?
    var pageSize: Int?
    var error: String?
}

/// 单行接诊。display_status: done | running | queued | failed | stuck | idle。
struct SessionRow: Decodable, Identifiable {
    var id: Int?
    var customer: String?
    var memberCard: String?
    var advisor: String?
    var serviceDate: String?
    var analysisStatus: String?
    var displayStatus: String?
    var hasEvaluation: Int?
    var recordingCount: Int?
    var asrDoneCount: Int?
    var asrRunningCount: Int?
    var asrFailedCount: Int?
    var analysisScores: String?   // 一段 JSON 字符串,需二次解析取 .overall
    var analysisProgress: String?
    var taskDone: Int?
    var taskTotal: Int?
    var taskRunning: [String]?
    var analysisStartedAt: String?
    var analysisFinishedAt: String?
    var createdAt: String?
    var firstRecordedAt: String?
    var lastRecordedAt: String?

    var hasEval: Bool { (hasEvaluation ?? 0) > 0 }

    /// 综合分:解析 analysis_scores(JSON 字符串) 取 .overall;异常/缺字段 → nil(显示「—」)。
    var overallScore: Double? {
        guard let raw = analysisScores, !raw.isEmpty,
              let data = raw.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let n = obj["overall"] as? NSNumber else { return nil }
        return n.doubleValue
    }

    /// SwiftUI 列表用稳定 id(id 理论恒有;兜底 -1)。
    var rowID: Int { id ?? -1 }
}

struct StatusCountsResponse: Decodable {
    var counts: StatusCounts?
    var error: String?
}

struct StatusCounts: Decodable {
    var all: Int?
    var running: Int?
    var queued: Int?
    var done: Int?
    var failed: Int?
    var stuck: Int?
    var idle: Int?

    /// 取某桶计数(bucket=nil/"" → 全部)。
    func of(_ bucket: String?) -> Int {
        switch bucket {
        case nil, "": return all ?? 0
        case "running": return running ?? 0
        case "queued": return queued ?? 0
        case "done": return done ?? 0
        case "failed": return failed ?? 0
        case "stuck": return stuck ?? 0
        case "idle": return idle ?? 0
        default: return 0
        }
    }
}
