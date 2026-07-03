import Foundation

/// 美丽档案(顾客详情页)。android 端对应 `data/model/CustomerProfile.kt`。
/// 两只读接口:customer_profile(信息/累积标签/陪伴时间线) + customer_value(价值预测)。
/// 红线:UI 对外一律「陪伴 / 陪伴时间线 / 查看报告」。

struct CustomerProfileInfo: Decodable {
    var id: Int?
    var name: String?
    var memberCard: String?
    var phoneTail: String?
}

/// 累积标签:count = 多少次陪伴提到该标签。
struct AccumulatedTag: Decodable { var tag: String?; var count: Int? }

/// 陪伴时间线一项(按 service_date 倒序)。done 才可点开报告。
struct ProfileSession: Decodable, Identifiable {
    var id: Int?
    var serviceDate: String?
    var advisor: String?
    var storeName: String?
    var analysisStatus: String?
    var tags: [String]?
    var projectHits: [String]?
    var isDone: Bool { analysisStatus == "done" }
    var rowID: Int { id ?? -1 }
}

struct CustomerProfileResponse: Decodable {
    var info: CustomerProfileInfo?
    var accumulatedTags: [AccumulatedTag]?
    var sessions: [ProfileSession]?
    var sessionCount: Int?
    var range: String?
    var error: String?
}

// ── 价值预测 ──

struct AdvisorMatch: Decodable { var advisor: String?; var assessment: String? }

/// 价值预测正文(各维度 markdown-lite 文本)。
struct CustomerValueContent: Decodable {
    var valueRebuild: String?   // 客户价值评估
    var battlePlan: String?     // 可攻破痛点+作战方案
    var projectPlan: String?    // 竞品分析+项目+学习清单
    var bizPlan: String?        // 下一步动作+回店规划
    var advisorMatch: [AdvisorMatch]?
}

struct CustomerValueResponse: Decodable {
    var content: CustomerValueContent?
    var generatedAt: String?
    var model: String?
    var stale: Bool?
    var doneCount: Int?
    var hasCache: Bool?
    var error: String?
}
