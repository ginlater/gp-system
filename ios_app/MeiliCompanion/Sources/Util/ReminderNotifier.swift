import Foundation
import UserNotifications

/// 提醒本地通知(android 对应 `notify/ReminderNotifier.kt`):
/// - 后端提醒(`/api/consultant/reminders`)以系统通知弹出,按「没见过的 id」只弹一次;
///   提醒消失(已处理)后从已见集合清掉。文案直接用后端 message。
/// - 陪伴笔低电量:≤10% 提醒一次、≤5% 再提醒一次;充电(>100)或回升即重置。
/// 不依赖推送服务:前台由 HomeViewModel 轮询触发,后台由 BGAppRefresh 尽力触发
/// (iOS 系统按配额调度,不保证即时——与安卓 WorkManager 同级别的已知天花板)。
enum ReminderNotifier {
    private static let kSeen = "reminder_notify_seen_ids"
    private static var batteryNotifiedLevel = -1

    /// 首次调用弹系统授权框;之后幂等。
    static func requestAuthorization() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }

    /// 新提醒 → 各弹一条;已见集合=当前全集(消失的自动清,同 id 复现可再提醒)。
    /// userInfo 带 ref_type/ref_id,点通知直达对应报告/绑定页(F5)。
    static func notifyNew(_ items: [Reminder]) {
        let seen = Set(UserDefaults.standard.array(forKey: kSeen) as? [Int] ?? [])
        let fresh = items.filter { !seen.contains($0.id) }
        UserDefaults.standard.set(items.map(\.id), forKey: kSeen)
        for r in fresh {
            post("美丽陪伴提醒", r.message ?? "有新的提醒，请打开查看", id: "reminder-\(r.id)",
                 userInfo: ["ref_type": r.refType ?? "", "ref_id": r.refId ?? 0])
        }
    }

    /// 陪伴笔电量(cmd=6 原始值,110=充电中)。
    static func onPenBattery(_ level: Int) {
        if level > 100 || level < 0 || level > 10 { batteryNotifiedLevel = -1; return }
        if level <= 5 {
            guard batteryNotifiedLevel != 5 else { return }
            batteryNotifiedLevel = 5
            post("陪伴笔电量不足", "电量剩余5%，快去充电吧，充电1.5～2小时就能充满哦～")
        } else {
            guard batteryNotifiedLevel == -1 else { return }
            batteryNotifiedLevel = 10
            post("陪伴笔该充电了", "电量剩余\(level)%，记得抽空充电，别影响接下来的陪伴～")
        }
    }

    private static func post(_ title: String, _ body: String, id: String = UUID().uuidString,
                             userInfo: [AnyHashable: Any] = [:]) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        content.userInfo = userInfo
        UNUserNotificationCenter.current().add(
            UNNotificationRequest(identifier: id, content: content, trigger: nil))
    }
}

extension Notification.Name {
    /// 用户点了提醒通知 → MainShell 按 ref_type/ref_id 路由(F5)。
    static let meiliOpenReminderRef = Notification.Name("meiliOpenReminderRef")
}

/// 前台也展示横幅(iOS 默认前台静默吞掉通知;安卓前台会进通知栏,对齐);点通知转路由事件。
final class NotificationPresenter: NSObject, UNUserNotificationCenterDelegate {
    static let shared = NotificationPresenter()

    func install() { UNUserNotificationCenter.current().delegate = self }

    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound])
    }

    /// 冷启动点通知:MainShell 还没建好、onReceive 还没订阅,事件会丢(复查 B1)。
    /// 存一份 pendingRef,MainShell onAppear 时消费。
    static var pendingRef: [AnyHashable: Any]?

    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        let info = response.notification.request.content.userInfo
        NotificationPresenter.pendingRef = info
        NotificationCenter.default.post(name: .meiliOpenReminderRef, object: nil, userInfo: info)
        completionHandler()
    }
}
