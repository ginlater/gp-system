import SwiftUI

/// 顾问端主壳:4 tab 横滑(陪伴/接诊/报告/客户)+ 中间陪伴 FAB + 底栏。
/// android 端对应 `nav/AppScaffold.kt` 的 MainTabsScaffold。
///
/// 当前各 tab 为占位屏,逐屏移植(task 5)替换;FAB 录音引擎接入在 task 6。
struct MainShell: View {
    let me: Me
    @Binding var path: NavigationPath   // 提到 RootView,换肤重建本壳时导航不丢
    @EnvironmentObject private var app: AppState
    @StateObject private var rec = RecordingManager.shared
    @State private var tab = 0

    init(me: Me, path: Binding<NavigationPath>) {
        self.me = me
        self._path = path
        #if DEBUG
        // 调试:`simctl launch ... -startTab 2` 直接打开某 tab(用于无人值守截图各 tab)。
        if UserDefaults.standard.object(forKey: "startTab") != nil {
            _tab = State(initialValue: UserDefaults.standard.integer(forKey: "startTab"))
        }
        #endif
    }

    var body: some View {
        NavigationStack(path: $path) {
            ZStack(alignment: .bottom) {
                MeiliColor.bg.ignoresSafeArea()

                TabView(selection: $tab) {
                    HomeView(me: me,
                             onOpenReception: { withAnimation { tab = 1 } },
                             onOpenReminders: { path.append(AppRoute.reminders) },
                             onOpenSettings: { path.append(AppRoute.settings) })
                        .tag(0)
                    ReceptionView(
                        onOpenReport: { path.append(AppRoute.report($0)) },
                        onOpenPreview: { cid, date in path.append(AppRoute.sessionPreviewByCustomer(cid, date)) },
                        onBind: { path.append(AppRoute.bind($0)) },
                        onOpenReminders: { path.append(AppRoute.reminders) }
                    )
                    .tag(1)
                    ArchiveView(
                        onOpenReport: { path.append(AppRoute.report($0)) },
                        onOpenSettings: { path.append(AppRoute.settings) }
                    )
                    .tag(2)
                    CustomerView(onOpenDetail: { path.append(AppRoute.customerDetail($0)) })
                        .tag(3)
                }
                .tabViewStyle(.page(indexDisplayMode: .never))
                .ignoresSafeArea(.keyboard)

                MeiliBottomBar(selected: $tab, live: rec.isLive) {
                    rec.toggle()
                    // 跨页回首页也不路过中间页(同 tab 按钮的规则)
                    if abs(tab - 0) <= 1 {
                        withAnimation { tab = 0 }
                    } else {
                        var tx = Transaction()
                        tx.disablesAnimations = true
                        withTransaction(tx) { tab = 0 }
                    }
                }

                // ★对齐安卓2.1.3:蓝牙未开/未授权全局红条(常驻任意 tab,toast 会闪没;
                // 只在用过笔的手机上显示)。「去打开」弹系统开蓝牙对话框;权限被拒则跳设置。
                if let issue = rec.btIssue {
                    VStack {
                        HStack(spacing: 10) {
                            MeiliIcon(MeiliIcons.warn, size: 16).foregroundStyle(.white)
                            Text(issue == .unauthorized ? "未允许蓝牙权限，陪伴笔无法连接"
                                                        : "手机蓝牙未开启，陪伴笔无法连接")
                                .font(.sz(12.5, weight: .semibold)).foregroundStyle(.white)
                            Spacer(minLength: 6)
                            Button(issue == .unauthorized ? "去设置" : "去打开") {
                                if issue == .unauthorized {
                                    if let url = URL(string: UIApplication.openSettingsURLString) {
                                        UIApplication.shared.open(url)
                                    }
                                } else {
                                    PenBluetoothWatch.shared.promptEnableBluetooth()
                                }
                            }
                            .font(.sz(12.5, weight: .bold)).foregroundStyle(MeiliColor.roseDeep)
                            .padding(.horizontal, 10).padding(.vertical, 5)
                            .background(.white).clipShape(Capsule())
                        }
                        .padding(.horizontal, 14).padding(.vertical, 10)
                        .background(MeiliColor.roseDeep)
                        .clipShape(RoundedRectangle(cornerRadius: MeiliRadius.md, style: .continuous))
                        .padding(.horizontal, 12).padding(.top, 6)
                        Spacer()
                    }
                }

                // 录音引擎全局 toast(同步进度/断连补取/删除结果等,任何 tab 可见)
                // .task(id:) + isCancelled 判断(复查 B9):内容变化重启计时,视图移除不误清
                if let t = rec.toast {
                    Text(t).font(MeiliFont.bodySm).foregroundStyle(.white)
                        .padding(.horizontal, 16).padding(.vertical, 11)
                        .background(MeiliColor.inkSurface).clipShape(Capsule())
                        .padding(.bottom, MeiliMetric.bottomNavInset + 8)
                        .task(id: t) {
                            try? await Task.sleep(nanoseconds: 2_600_000_000)
                            if !Task.isCancelled { rec.toast = nil }
                        }
                }
            }
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(for: AppRoute.self) { route in
                routeDestination(route)
            }
            // 对齐 android v2.0.60:录完上传成功 → 直接跳绑定页(强制绑定,去掉「稍后」对话框)。
            // 录音 FAB 全局,任意 tab 录完都能跳;停录时 onFab 已切回 tab0,这里只负责入栈。
            .onChange(of: rec.bindPrompt) { rid in
                guard let rid, rid > 0 else { return }
                rec.bindPrompt = nil
                path.append(AppRoute.bind(rid))
            }
            // 点提醒通知直达对应页(F5):report→报告,recording→绑定,其余→提醒列表
            .onReceive(NotificationCenter.default.publisher(for: .meiliOpenReminderRef)) { note in
                NotificationPresenter.pendingRef = nil   // 已在线消费,清掉冷启动暂存
                routeReminderRef(note.userInfo)
            }
            // 冷启动点通知拉起:didReceive 早于本视图订阅,事件存在 pendingRef 里(复查 B1)
            .onAppear {
                if let ref = NotificationPresenter.pendingRef {
                    NotificationPresenter.pendingRef = nil
                    routeReminderRef(ref)
                }
            }
            .onAppear(perform: deepLinkIfNeeded)
        }
    }

    /// 按提醒的 ref_type/ref_id 路由(在线 onReceive 与冷启动 pendingRef 共用)。
    private func routeReminderRef(_ info: [AnyHashable: Any]?) {
        if info?["kind"] as? String == "battery" { return }   // E4:电量通知只拉起App,不硬跳提醒页
        let refType = info?["ref_type"] as? String ?? ""
        let refId = (info?["ref_id"] as? Int) ?? (info?["ref_id"] as? NSNumber)?.intValue ?? 0
        switch (refType, refId) {
        case ("session", let id) where id > 0, ("report", let id) where id > 0:
            path.append(AppRoute.report(id))
        case ("recording", let id) where id > 0:
            path.append(AppRoute.bind(id))
        default:
            path.append(AppRoute.reminders)
        }
    }

    @State private var deepLinked = false
    private func deepLinkIfNeeded() {
        #if DEBUG
        guard !deepLinked else { return }
        deepLinked = true
        // `simctl launch ... -openReport <sid>` 直接打开某报告(无人值守截图报告页)。
        if UserDefaults.standard.object(forKey: "openReport") != nil {
            let sid = UserDefaults.standard.integer(forKey: "openReport")
            if sid > 0 { path.append(AppRoute.report(sid)) }
        }
        if UserDefaults.standard.object(forKey: "openCustomer") != nil {
            let cid = UserDefaults.standard.integer(forKey: "openCustomer")
            if cid > 0 { path.append(AppRoute.customerDetail(cid)) }
        }
        if UserDefaults.standard.object(forKey: "openPreviewCust") != nil {
            let cid = UserDefaults.standard.integer(forKey: "openPreviewCust")
            let d = UserDefaults.standard.string(forKey: "openPreviewDate") ?? ""
            if cid > 0 { path.append(AppRoute.sessionPreviewByCustomer(cid, d)) }
        }
        if UserDefaults.standard.object(forKey: "openBind") != nil {
            let rid = UserDefaults.standard.integer(forKey: "openBind")
            if rid > 0 { path.append(AppRoute.bind(rid)) }
        }
        if UserDefaults.standard.bool(forKey: "openReminders") { path.append(AppRoute.reminders) }
        if UserDefaults.standard.bool(forKey: "openSettings") { path.append(AppRoute.settings) }
        // `simctl launch ... -simBind <rid>`:模拟「录完上传成功」触发 bindPrompt,
        // 验证录完自动跳绑定页链路(模拟器无法真录音,避免污染生产)。
        if UserDefaults.standard.object(forKey: "simBind") != nil {
            let rid = UserDefaults.standard.integer(forKey: "simBind")
            if rid > 0 {
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { rec.bindPrompt = rid }
            }
        }
        #endif
    }

    @ViewBuilder
    private func routeDestination(_ route: AppRoute) -> some View {
        switch route {
        case .report(let sid):
            ReportView(sessionId: sid)
        case .settings:
            SettingsView()
        case .reminders:
            RemindersView(onOpenReport: { path.append(AppRoute.report($0)) },
                          onBind: { path.append(AppRoute.bind($0)) })
        case .customerDetail(let cid):
            CustomerDetailView(customerId: cid, onOpenReport: { path.append(AppRoute.report($0)) })
        case .sessionPreviewByCustomer(let cid, let date):
            SessionPreviewView(customerId: cid, date: date, onOpenReport: { path.append(AppRoute.report($0)) })
        case .sessionPreview(let sid):
            DetailPlaceholder(title: "会话预览", note: "session #\(sid) · 预览 移植中")
        case .bind(let rid):
            BindCustomerView(recordingId: rid, onBound: { cid, date in
                if !path.isEmpty { path.removeLast() }   // 绑定页出栈,返回直达待整理
                path.append(AppRoute.sessionPreviewByCustomer(cid, date))
            })
        default:
            DetailPlaceholder(title: "建设中", note: String(describing: route))
        }
    }
}

/// 次级页占位(报告/设置/提醒等,逐步替换为真实页)。带系统返回。
private struct DetailPlaceholder: View {
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

/// 底栏:高 88、柔光磨砂底 + 上描边,5 槽(4 tab + 中间 FAB)。
struct MeiliBottomBar: View {
    @Binding var selected: Int
    var live: Bool
    var onFab: () -> Void

    private struct Tab { let label: String; let icon: MeiliGlyph; let index: Int }
    private let left: [Tab] = [
        .init(label: "陪伴", icon: MeiliIcons.companion, index: 0),
        .init(label: "接诊", icon: MeiliIcons.reception, index: 1),
    ]
    private let right: [Tab] = [
        .init(label: "报告", icon: MeiliIcons.doc, index: 2),
        .init(label: "客户", icon: MeiliIcons.profile, index: 3),
    ]

    var body: some View {
        HStack(spacing: 0) {
            ForEach(left, id: \.index) { item($0) }
            CompanionFab(live: live, action: onFab)
                .frame(maxWidth: .infinity)
            ForEach(right, id: \.index) { item($0) }
        }
        .padding(.horizontal, 8)
        .padding(.top, 6)
        .frame(height: MeiliMetric.bottomNav)
        .background(MeiliColor.surface.opacity(0.96))
        .background(.ultraThinMaterial)
        .overlay(alignment: .top) {
            Rectangle().fill(MeiliColor.line).frame(height: 1)
        }
    }

    private func item(_ tab: Tab) -> some View {
        let selectedNow = selected == tab.index
        return VStack(spacing: 3) {
            MeiliIcon(tab.icon, size: 22)
                .foregroundStyle(selectedNow ? MeiliColor.clayDeep : MeiliColor.ink3)
                .scaleEffect(selectedNow ? 1.18 : 1)
                .padding(.horizontal, 12)
                .padding(.vertical, 4)
                .background(selectedNow ? MeiliColor.clayTint : .clear, in: Capsule())
            Text(tab.label)
                .font(.sz(10.5, weight: .bold))
                .foregroundStyle(selectedNow ? MeiliColor.clayDeep : MeiliColor.ink3)
        }
        .frame(maxWidth: .infinity)
        .contentShape(Rectangle())
        .onTapGesture {
            // 翻页式 TabView:带动画跨页切换会把中间每页都滑一遍(0→3 连滑三次,很怪)。
            // 相邻页保留滑动动画;跨页直接跳,不路过中间页。
            if abs(selected - tab.index) <= 1 {
                withAnimation(.spring(response: 0.32, dampingFraction: 0.78)) { selected = tab.index }
            } else {
                var tx = Transaction()
                tx.disablesAnimations = true
                withTransaction(tx) { selected = tab.index }
            }
        }
        .animation(.spring(response: 0.3, dampingFraction: 0.7), value: selectedNow)
    }
}

/// 中间陪伴 FAB:54 径向陶土渐变圆 + 发光;录音中=玫瑰渐变 + 白色停止方块 + 呼吸。
struct CompanionFab: View {
    var live: Bool
    var action: () -> Void
    @State private var pulse = false

    var body: some View {
        Button(action: action) {
            ZStack {
                Circle()
                    .fill(live ? MeiliColor.companionLiveGradient(diameter: 54)
                              : MeiliColor.companionGradient(diameter: 54))
                    .frame(width: 54, height: 54)
                    .shadow(color: (live ? MeiliColor.roseDeep : MeiliColor.clay).opacity(0.4),
                            radius: live && pulse ? 16 : 12, y: 8)
                if live {
                    RoundedRectangle(cornerRadius: 6, style: .continuous)
                        .fill(.white).frame(width: 20, height: 20)
                } else {
                    MeiliIcon(MeiliIcons.companion, size: 26).foregroundStyle(.white)
                }
            }
            .scaleEffect(live && pulse ? 1.07 : 1)
            .offset(y: -10)
        }
        .buttonStyle(PressScaleButtonStyle(scale: 0.95))
        .onAppear { sync() }
        .onChange(of: live) { _ in sync() }
    }

    private func sync() {
        if live {
            withAnimation(.easeInOut(duration: 1.3).repeatForever(autoreverses: true)) { pulse = true }
        } else {
            withAnimation(.easeOut(duration: 0.3)) { pulse = false }
        }
    }
}

// MARK: - 占位屏(task 5 替换)

/// 陪伴首页占位:大圆钮 + 状态。点 FAB 或圆钮切换呼吸态演示。
private struct CompanionPlaceholder: View {
    let me: Me
    @Binding var fabLive: Bool

    var body: some View {
        ScrollView {
            VStack(spacing: MeiliMetric.cardGap) {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("你好，\(me.advisorName ?? me.username ?? "陪伴师")")
                            .font(.sz(16, weight: .heavy)).foregroundStyle(MeiliColor.ink)
                        Text("今天也温柔地陪伴每一位顾客")
                            .font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3)
                    }
                    Spacer()
                }
                .padding(.top, 8)

                MeiliCard {
                    CompanionStage(
                        timerText: fabLive ? "00:12" : "00:00",
                        statusText: fabLive ? "陪伴进行中" : "点击下方 · 开启今天的陪伴",
                        hint: "陪伴结束后，请把这段陪伴绑定到今日接诊里的顾客。",
                        live: fabLive,
                        onToggle: { fabLive.toggle() }
                    )
                    .frame(maxWidth: .infinity)
                }
            }
            .padding(.horizontal, MeiliMetric.screenH)
            .padding(.bottom, MeiliMetric.bottomNavInset)
        }
    }
}

/// 通用占位屏(接诊/报告/客户)。
private struct TabPlaceholder: View {
    let title: String
    let subtitle: String
    let icon: MeiliGlyph

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                MeiliTopBar(title: title)
                    .padding(.horizontal, MeiliMetric.screenH)
                MeiliCard {
                    VStack(spacing: 12) {
                        MeiliIcon(icon, size: 40).foregroundStyle(MeiliColor.clay)
                        Text(title).font(MeiliFont.cardTitle).foregroundStyle(MeiliColor.ink)
                        Text("\(subtitle)\n· 即将上线 ·")
                            .font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3)
                            .multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 20)
                }
                .padding(.horizontal, MeiliMetric.screenH)
                .padding(.top, 60)
            }
            .padding(.bottom, MeiliMetric.bottomNavInset)
        }
    }
}
