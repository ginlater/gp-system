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
    // E5:档位持久化——纯内存的话笔停在6-10%时每次冷启动都重弹"该充电"
    private static let kBatteryNotified = "pen_battery_notified_level"
    private static var batteryNotifiedLevel: Int {
        get { UserDefaults.standard.object(forKey: kBatteryNotified) as? Int ?? -1 }
        set { UserDefaults.standard.set(newValue, forKey: kBatteryNotified) }
    }

    /// 首次调用弹系统授权框;之后幂等。
    static func requestAuthorization() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }

    /// 新提醒 → 各弹一条。userInfo 带 ref_type/ref_id,点通知直达对应报告/绑定页(F5)。
    static func notifyNew(_ items: [Reminder]) {
        // E6:BG任务与前台轮询可能并发到这——收敛到主队列串行,kSeen 读改写不再竞态双弹
        DispatchQueue.main.async { notifyNewOnMain(items) }
    }

    private static func notifyNewOnMain(_ items: [Reminder]) {
        UNUserNotificationCenter.current().getNotificationSettings { st in
            DispatchQueue.main.async {
                // E3:授权框还没点(notDetermined)不消费"未见"——否则点允许后这批存量永远不弹
                guard st.authorizationStatus != .notDetermined else { return }
                let defaults = UserDefaults.standard
                let ids = items.map(\.id)
                // E2:首启/重装/换账号(无已见集)只播种不弹——店长重装不再被几百条存量轰炸
                guard defaults.object(forKey: kSeen) != nil else {
                    defaults.set(ids, forKey: kSeen)
                    return
                }
                let old = Set(defaults.array(forKey: kSeen) as? [Int] ?? [])
                let fresh = items.filter { !old.contains($0.id) }
                // E10:已见=旧∪新,留最近1000条——接口LIMIT截断时老id滑出又滑回不再重复弹
                defaults.set(Array(old.union(ids).sorted(by: >).prefix(1000)), forKey: kSeen)
                for r in fresh {
                    post(title(for: r), r.message ?? "有新的提醒，请打开查看", id: "reminder-\(r.id)",
                         userInfo: ["ref_type": r.refType ?? "", "ref_id": r.refId ?? 0])
                }
            }
        }
    }

    /// E9:分级标题(对齐安卓 postOne)——通知栏一眼分清轻重,不再一律"美丽陪伴提醒"。
    private static func title(for r: Reminder) -> String {
        if r.scope == "escalation" || r.channel == "escalation" { return "门店提醒待跟进" }
        switch r.refType ?? "" {
        case "recording": return "有陪伴录音待绑定"
        case "session", "report": return "有陪伴报告待查看"
        default: return "美业私教提醒"
        }
    }

    /// 陪伴笔电量(cmd=6 原始值,110=充电中)。
    static func onPenBattery(_ level: Int) {
        // E5:>15% 或充电才复位(原来 >10 即复位,电量在 10↔11 抖动时每次跌回都重弹)
        if level > 100 || level < 0 || level > 15 {
            if batteryNotifiedLevel != -1 { batteryNotifiedLevel = -1 }
            return
        }
        if level > 10 { return }   // 11~15%:滞回区,不弹也不复位
        if level <= 5 {
            guard batteryNotifiedLevel != 5 else { return }
            batteryNotifiedLevel = 5
            post("陪伴笔电量不足", "电量剩余5%，快去充电吧，充电1.5～2小时就能充满哦～",
                 userInfo: ["kind": "battery"])   // E4:标记电量通知,点击只拉起App不乱跳
        } else {
            guard batteryNotifiedLevel == -1 else { return }
            batteryNotifiedLevel = 10
            post("陪伴笔该充电了", "电量剩余\(level)%，记得抽空充电，别影响接下来的陪伴～",
                 userInfo: ["kind": "battery"])
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
        // E1:带 .list——前台弹的横幅3秒消失后也进通知中心,错过横幅还能翻到(对齐安卓)
        completionHandler([.banner, .list, .sound])
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
