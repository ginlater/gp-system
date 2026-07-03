import SwiftUI

/// 「美丽陪伴」iOS 版入口。
///
/// 顾问端纯原生重写(SwiftUI),1:1 复刻 android_app_v2,后端零改动。
/// 事实源见 `ios_app/SPEC.md`;视觉对齐 `android_app_v2/mockups/warm_2.html`「暖玉柔光」。
/// 后台上传会话被系统重新拉起时的桥接(App 被杀期间系统代传完成 → 冷启动交回收尾回调)。
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication,
                     handleEventsForBackgroundURLSession identifier: String,
                     completionHandler: @escaping () -> Void) {
        UploadQueue.backgroundCompletionHandler = completionHandler
        Task { @MainActor in _ = UploadQueue.shared }   // 重建会话接管回调
    }
}

@main
struct MeiliCompanionApp: App {
    @Environment(\.scenePhase) private var scenePhase
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    init() {
        #if DEBUG
        // devicectl --console 挂管道时 stdout 默认全缓冲,print 日志会积压不出;改行缓冲便于真机联调看日志
        setvbuf(stdout, nil, _IOLBF, 0)
        #endif
        NotificationPresenter.shared.install()   // 前台也展示通知横幅
        ReminderNotifier.requestAuthorization()
        ReminderRefresh.register()               // 后台定时拉提醒(BGAppRefresh,系统按配额调度)
    }

    var body: some Scene {
        WindowGroup {
            RootView()
        }
        .onChange(of: scenePhase) { phase in
            switch phase {
            case .active:
                // 回前台:笔状态对账(挂起期间的失联/笔自停即时校准) + 踢上传重传队列
                PenController.shared.appDidBecomeActive()
                UploadQueue.shared.kick()
            case .background:
                PenController.shared.appDidEnterBackground()
                ReminderRefresh.schedule()
            default: break
            }
        }
    }
}
