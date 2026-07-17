import SwiftUI

/// 多系统账号的根壳:NavigationStack,根 = 工作台宫格,各系统以 AppRoute 入栈。
/// android 端对应 AppScaffold 的 Workspace 落点 + navigateToSystem/switchSystem。
struct WorkspaceShell: View {
    let me: Me
    @Binding var path: NavigationPath   // 提到 RootView,换肤重建时导航不丢

    var body: some View {
        NavigationStack(path: $path) {
            WorkspaceView(
                me: me,
                onOpenSystem: { key in
                    if let route = AppRoute.forSystem(key) { path.append(route) }
                },
                onOpenSettings: { path.append(AppRoute.settings) })
                .toolbar(.hidden, for: .navigationBar)
                .navigationDestination(for: AppRoute.self) { route in
                    AppRouteDestinationView(route: route, path: $path, me: me, workspaceMode: true)
                }
                .onAppear(perform: deepLinkIfNeeded)
        }
    }

    @State private var deepLinked = false
    private func deepLinkIfNeeded() {
        #if DEBUG
        // `simctl launch ... -openSystem teach` 直接打开某系统(无人值守截图验证各系统屏)。
        guard !deepLinked else { return }
        deepLinked = true
        if let key = UserDefaults.standard.string(forKey: "openSystem"),
           let route = AppRoute.forSystem(key) {
            path.append(route)
        }
        // `-openChapter <key>` 直开某章课件正文(验证原生渲染)
        if let ch = UserDefaults.standard.string(forKey: "openChapter"), !ch.isEmpty {
            path.append(AppRoute.teachHome)
            path.append(AppRoute.teachReader(ch))
        }
        #endif
    }
}
