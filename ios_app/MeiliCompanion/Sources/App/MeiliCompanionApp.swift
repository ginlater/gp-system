import SwiftUI

/// 「美丽陪伴」iOS 版入口。
///
/// 顾问端纯原生重写(SwiftUI),1:1 复刻 android_app_v2,后端零改动。
/// 事实源见 `ios_app/SPEC.md`;视觉对齐 `android_app_v2/mockups/warm_2.html`「暖玉柔光」。
@main
struct MeiliCompanionApp: App {
    @Environment(\.scenePhase) private var scenePhase

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
            if phase == .background { ReminderRefresh.schedule() }
        }
    }
}
