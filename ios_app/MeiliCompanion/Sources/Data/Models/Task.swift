import Foundation

/// 分析任务(11 个,平铺 T1–T11)。android 端对应 `data/model/Task.kt`。
/// 来自 GET /api/session/<sid>/tasks。status: done | running | failed | pending | missing。
/// 红线:UI 不得出现「调用 1/2/3」(call 字段仅内部排序,不展示)。
struct AnalysisTask: Decodable, Identifiable {
    var taskId: String            // T1 .. T11
    var name: String?
    var call: Int?                // 内部分组,UI 禁止展示
    var status: String?           // done | running | failed | pending | missing
    var updatedAt: String?
    var error: String?

    var id: String { taskId }
    var isDone: Bool { status == "done" }
    var isRunning: Bool { status == "running" }
    var isFailed: Bool { status == "failed" }
}

struct TasksResponse: Decodable {
    var tasks: [AnalysisTask]?
}
