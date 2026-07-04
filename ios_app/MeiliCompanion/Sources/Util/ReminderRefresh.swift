import Foundation
import BackgroundTasks

/// 后台定时拉提醒(android 对应 `notify/ReminderPollWorker.kt` 的 WorkManager 周期任务)。
/// iOS 用 BGAppRefreshTask:App 退后台时预约,系统按使用习惯/电量配额择机唤醒(通常十几分钟~几小时),
/// 醒来拉一次 /api/consultant/reminders → 新提醒弹本地通知 → 预约下一次。
/// 不保证即时(iOS 平台限制),但"App 没开也能收到提醒"尽力做到。
enum ReminderRefresh {
    static let taskId = "com.aibeautyfulwomen.gongpai.ios.reminders"

    /// App 启动时注册(必须在 didFinishLaunching 前后立即调,晚了系统拒绝)。
    static func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: taskId, using: nil) { task in
            handle(task as! BGAppRefreshTask)
        }
    }

    /// 退后台时预约下一次(最早 15 分钟后,实际时机系统定)。
    static func schedule() {
        let req = BGAppRefreshTaskRequest(identifier: taskId)
        req.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        try? BGTaskScheduler.shared.submit(req)
    }

    private static func handle(_ task: BGAppRefreshTask) {
        schedule()   // 先预约下一轮,形成周期
        let work = Task {
            if let r = try? await ConsultantRepo.reminders() {
                ReminderNotifier.notifyNew(r.items ?? [])
            }
            // 单点完成(复查 B8):过期时 expirationHandler 只 cancel,由这里统一收尾,
            // 避免 setTaskCompleted 双调用触发框架断言
            task.setTaskCompleted(success: !Task.isCancelled)
        }
        task.expirationHandler = { work.cancel() }
    }
}
