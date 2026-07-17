import Foundation

/// 主壳内 NavigationStack 的次级页路由。android 端对应 `nav/Routes.kt`。
enum AppRoute: Hashable {
    case report(Int)                       // sessionId
    case bind(Int)                         // recordingId
    case sessionPreview(Int)               // sessionId
    case sessionPreviewByCustomer(Int, String) // customerId, date
    case customerDetail(Int)               // customerId
    case reminders
    case settings

    // ---- 多系统整合(工作台宫格 push 的各系统;单系统账号不经过) ----
    case gongpaiMain                       // 智能工牌主壳(4 tab + 录音 FAB)
    case teachHome                         // 美业网课壳首页
    case teachReader(String)               // 课件正文(chapterKey)
    case teachQuiz(String, Int)            // 章节测验(chapterKey, quizIndex 1..3)
    case scriptAgent(String)               // 回访/高情商原生表单(sysKey: followup | higheq)
    case agentWeb(String)                  // 网页壳(systemKey: chat | kpi | kpi_admin)

    /// 系统 key → 目的地(工作台格子/切换器共用;nil = 未接入)。
    static func forSystem(_ key: String) -> AppRoute? {
        switch key {
        case "gongpai": return .gongpaiMain
        case "teach": return .teachHome
        case "followup", "higheq": return .scriptAgent(key)
        case "chat", "kpi", "kpi_admin": return .agentWeb(key)
        default: return nil
        }
    }

    /// 已接入模块的系统 key(点击可进;不在此列 = 即将上线)。
    static let wiredSystemKeys: Set<String> = ["gongpai", "teach", "followup", "higheq", "chat", "kpi", "kpi_admin"]
}
