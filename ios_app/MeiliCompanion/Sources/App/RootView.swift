import SwiftUI

/// 应用根视图 = 登录态路由(对应 android 的 Gate)。
/// 启动 → bootstrap 拉 /api/me:已登录按角色进 主壳/管理台,未登录进 登录页。
struct RootView: View {
    @StateObject private var app = AppState()
    @ObservedObject private var theme = ThemeManager.shared
    @State private var mainPath = NavigationPath()   // 提到 RootView:换肤重建主壳时导航不丢

    @State private var themeTested = false

    // ★2026-07-17 隐私合规 —— 首启先弹隐私政策,同意前不进任何页面(对齐 android 2.3.4)。
    // bootstrap 也挡在同意之后:同意前连 /api/me 都不发,不落「同意前收集」的口实。
    @State private var privacyAgreed = PrivacyConsent.isAgreed

    var body: some View {
        content
            // 换肤 → 整树换 identity 重建,全 app 立即变色;mainPath 在 RootView 故导航保留。
            .id(theme.currentId)
            .environmentObject(app)
            .task(id: privacyAgreed) { if privacyAgreed { await app.bootstrap() } }
            .onAppear {
                #if DEBUG
                // 调试:`-themeAfter <id>` 启动 2.5s 后切皮肤,验证运行时换肤是否全 app 传导。
                if !themeTested, let id = UserDefaults.standard.string(forKey: "themeAfter") {
                    themeTested = true
                    Task { try? await Task.sleep(nanoseconds: 2_500_000_000); ThemeManager.shared.apply(id) }
                }
                #endif
            }
    }

    @ViewBuilder private var content: some View {
        if !privacyAgreed {
            PrivacyConsentView(onAgree: {
                PrivacyConsent.setAgreed()
                privacyAgreed = true   // task(id:) 观察到变化 → 这时才 bootstrap
            })
        } else {
            phased
        }
    }

    @ViewBuilder private var phased: some View {
        switch app.phase {
        case .loading:
            SplashView()
        case .login:
            LoginView(onLoggedIn: { app.onLoggedIn($0) })
        case .main(let me):
            MainShell(me: me, path: $mainPath)
        case .workspace(let me):
            WorkspaceShell(me: me, path: $mainPath)
        case .admin(let me):
            AdminHomeView(me: me)
        }
    }
}
