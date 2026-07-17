import SwiftUI

/// AppRoute → 目的地视图(MainShell 与 WorkspaceShell 共用一份注册,对齐 android AppNavHost)。
///
/// workspaceMode=true(多系统账号,栈根=工作台宫格)时:
///  - 系统路由(gongpaiMain/teachHome/…)可入栈;
///  - switchSystem = 先清栈回工作台再 push 目标系统(返回层级始终 工作台→系统);
///  - 设置页出现「切换系统工作台」入口。
struct AppRouteDestinationView: View {
    let route: AppRoute
    @Binding var path: NavigationPath
    let me: Me
    let workspaceMode: Bool

    /// 多系统切换(P1.1):pop 回工作台(栈根),再进目标系统。
    private func switchSystem(_ key: String) {
        guard workspaceMode else { return }
        var p = NavigationPath()
        if let target = AppRoute.forSystem(key) { p.append(target) }
        path = p
    }

    var body: some View {
        switch route {
        case .report(let sid):
            ReportView(sessionId: sid)
        case .settings:
            SettingsView(onSwitchWorkspace: workspaceMode ? { path = NavigationPath() } : nil)
        case .reminders:
            RemindersView(onOpenReport: { path.append(AppRoute.report($0)) },
                          onBind: { path.append(AppRoute.bind($0)) })
        case .customerDetail(let cid):
            CustomerDetailView(customerId: cid, onOpenReport: { path.append(AppRoute.report($0)) })
        case .sessionPreviewByCustomer(let cid, let date):
            SessionPreviewView(customerId: cid, date: date, onOpenReport: { path.append(AppRoute.report($0)) })
        case .sessionPreview(let sid):
            RouteDetailPlaceholder(title: "会话预览", note: "session #\(sid) · 预览 移植中")
        case .bind(let rid):
            BindCustomerView(recordingId: rid, onBound: { cid, date in
                if !path.isEmpty { path.removeLast() }   // 绑定页出栈,返回直达待整理
                path.append(AppRoute.sessionPreviewByCustomer(cid, date))
            })

        // ---- 多系统整合 ----
        case .gongpaiMain:
            MainTabsView(me: me, path: $path, onSwitchSystem: workspaceMode ? switchSystem : nil)
                .toolbar(.hidden, for: .navigationBar)
        case .teachHome:
            TeachHomeView(
                onOpenReader: { path.append(AppRoute.teachReader($0)) },
                onOpenQuiz: { key, idx in path.append(AppRoute.teachQuiz(key, idx)) },
                onSwitchSystem: switchSystem)
        case .teachReader(let key):
            TeachReaderView(chapterKey: key)
        case .teachQuiz(let key, let idx):
            TeachQuizView(chapterKey: key, quizIndex: idx)
        case .scriptAgent(let sysKey):
            FollowupView(systemKey: sysKey, onSwitchSystem: switchSystem)
        // 2026-07-16 四系统全原生化:chat/kpi/kpi_admin 不再走网页壳
        case .agentWeb("kpi"):
            KpiHomeView(onSwitchSystem: switchSystem)
        case .agentWeb("kpi_admin"):
            KpiAdminView(onSwitchSystem: switchSystem)
        case .agentWeb:
            ChatView(onSwitchSystem: switchSystem)
        }
    }
}

/// 次级页占位。带系统返回。
struct RouteDetailPlaceholder: View {
    let title: String
    let note: String
    var body: some View {
        ZStack {
            MeiliColor.bg.ignoresSafeArea()
            VStack(spacing: 12) {
                MeiliIcon(MeiliIcons.doc, size: 40).foregroundStyle(MeiliColor.clay)
                Text(title).font(MeiliFont.titleSm).foregroundStyle(MeiliColor.ink)
                Text(note).font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3)
                    .multilineTextAlignment(.center)
            }
            .padding(40)
        }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
    }
}
