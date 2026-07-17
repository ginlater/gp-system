import Foundation

/* ===================================================================
 * teach 网课(多系统整合)领域模型。android 端对应 `data/model/TeachModels.kt`。
 *
 * 服务端 = huifang-prod /opt/teach-server FastAPI(teach.aibeautyfulwomen.com),
 * 字段名 1:1 对齐 app.py 真实返回;解析用 convertFromSnakeCase,模型写 camelCase。
 *
 * 鉴权:POST /teach/login {username,password}(JSON) → {token},之后走 Bearer。
 * 单设备策略——每次登录踢掉旧 session,被踢的请求回 401,TeachRepository 静默重登一次再重试。
 * =================================================================== */

/// POST /teach/login 响应。成功 {token,username};401/403 {error}。
struct TeachLoginResp: Decodable {
    var token: String?
    var username: String?
    var error: String?
}

/// GET /teach/me/stats 响应(课程列表 + 打卡 + 学习时长,一次拉全)。
struct TeachStats: Decodable {
    var ok: Bool?
    var checkin: TeachCheckin?
    var study: TeachStudy?
    var progress: TeachProgress?
    var error: String?
}

struct TeachCheckin: Decodable {
    var todaySigned: Bool?
    var streak: Int?
    var totalDays: Int?
    var last30Days: [String]?
}

struct TeachStudy: Decodable {
    var todaySeconds: Int?
    var todayMinutes: Double?
    var totalSeconds: Int?
}

struct TeachProgress: Decodable {
    var currentChapter: String?
    var completedCount: Int?
    var totalCount: Int?
    var chapters: [TeachChapter]?
}

/// 单章进度。
/// - allowed=false:本账号没被分配该章 → 置灰「未开通」,不让进。
/// - unlocked=false:顺序未解锁(前一章 3 套测验各 ≥60 才解锁)→ 锁图标,不让进。
struct TeachChapter: Decodable {
    var key: String?
    var title: String?
    var allowed: Bool?
    var unlocked: Bool?
    var readDone: Bool?
    var quiz1Score: Int?
    var quiz2Score: Int?
    var quiz3Score: Int?
    var completed: Bool?

    /// 按序取三套测验分数(index 1..3)。
    func quizScore(_ index: Int) -> Int? {
        switch index {
        case 1: return quiz1Score
        case 2: return quiz2Score
        case 3: return quiz3Score
        default: return nil
        }
    }
}

/// POST /teach/checkin 响应(幂等,重复打卡也回 ok)。
struct TeachCheckinResp: Decodable {
    var ok: Bool?
    var todaySigned: Bool?
    var streak: Int?
    var totalDays: Int?
    var error: String?
}

/// POST /teach/heartbeat 响应。服务端 25s 节流,<25s 的心跳回 throttled=true。
struct TeachHeartbeatResp: Decodable {
    var ok: Bool?
    var throttled: Bool?
    var todaySeconds: Int?
}

/// GET /teach/quiz/{chapter}/{quiz_index} 响应。答案/解析已被服务端剥掉。
struct TeachQuizResp: Decodable {
    var ok: Bool?
    var chapter: String?
    var title: String?
    var quizIndex: Int?
    var total: Int?
    var questions: [TeachQuestion]?
    var error: String?
}

/// 单题。type: single_choice(options={"A":文案,…})| scenario(自由作答,关键词命中打分)。
struct TeachQuestion: Decodable {
    var type: String?
    var question: String?
    var options: [String: String]?

    var isScenario: Bool { type == "scenario" }
}

/// POST /teach/quiz/submit 响应。passed = score ≥60;chapterCompleted = 三套全过(本次达成)。
struct TeachSubmitResp: Decodable {
    var ok: Bool?
    var score: Int?
    var passed: Bool?
    var details: [TeachQuizDetail]?
    var chapterCompleted: Bool?
    var nextUnlocked: String?
    var error: String?
}

/// 单题判分明细。user 服务端可能给字符串或其它类型,宽松解成字符串。
struct TeachQuizDetail: Decodable {
    var type: String?
    var correct: Bool?
    var user: String?
    var answer: String?
    var explanation: String?
    var points: Int?

    enum CodingKeys: String, CodingKey { case type, correct, user, answer, explanation, points }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        type = try? c.decode(String.self, forKey: .type)
        correct = try? c.decode(Bool.self, forKey: .correct)
        answer = try? c.decode(String.self, forKey: .answer)
        explanation = try? c.decode(String.self, forKey: .explanation)
        points = try? c.decode(Int.self, forKey: .points)
        if let s = try? c.decode(String.self, forKey: .user) {
            user = s
        } else if let i = try? c.decode(Int.self, forKey: .user) {
            user = String(i)
        } else {
            user = nil
        }
    }
}
