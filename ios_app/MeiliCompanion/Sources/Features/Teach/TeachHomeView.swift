import SwiftUI

/// teach 网课首页(工作台「美业网课」进)。android 端对应 `ui/teach/TeachHomeScreen.kt`。
///
/// 顶部打卡/学习时长卡 + 9 章课程列表(锁/勾/分数状态)。
///  - 章卡点击 → 课件正文(原生渲染,TeachReaderView);
///  - 章卡内三个测验 pill 点击 → 测验页(TeachQuizView);
///  - unlocked=false 锁着不让进(toast 提示先完成上一章);allowed=false 置灰「未开通」。
/// 从课件/测验返回(onAppear)静默刷新,分数与解锁状态即时可见。
struct TeachHomeView: View {
    let onOpenReader: (String) -> Void
    let onOpenQuiz: (String, Int) -> Void
    let onSwitchSystem: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @StateObject private var vm = TeachHomeViewModel()
    @State private var switcherOpen = false
    @State private var appeared = false

    var body: some View {
        ZStack(alignment: .bottom) {
            MeiliColor.bg.ignoresSafeArea()
            VStack(spacing: 0) {
                MeiliTopBar(title: "美业网课", subtitle: "课程学习 · 打卡测验", onBack: { dismiss() }) {
                    TopBarIconButton(icon: MeiliIcons.workspace) { switcherOpen = true }
                    TopBarIconButton(icon: MeiliIcons.refresh) { vm.load() }
                }
                .padding(.horizontal, MeiliMetric.screenH)

                content
            }

            if let t = vm.toast {
                Text(t).font(MeiliFont.bodySm).foregroundStyle(.white)
                    .padding(.horizontal, 16).padding(.vertical, 11)
                    .background(MeiliColor.inkSurface).clipShape(Capsule())
                    .padding(.bottom, 30)
                    .task(id: t) {
                        try? await Task.sleep(nanoseconds: 2_200_000_000)
                        if !Task.isCancelled { vm.toast = nil }
                    }
            }
        }
        .toolbar(.hidden, for: .navigationBar)
        .sheet(isPresented: $switcherOpen) {
            SystemSwitcherSheet(currentKey: "teach") { key in
                switcherOpen = false
                onSwitchSystem(key)
            }
        }
        .onAppear {
            // 首次进入整页加载;从课件/测验页返回时静默刷新(测验刚提交,分数/解锁要立刻反映)
            if appeared { vm.load(silent: true) } else { appeared = true; vm.load() }
        }
    }

    @ViewBuilder private var content: some View {
        if vm.loading {
            Spacer()
            ProgressView().tint(MeiliColor.clay)
            Spacer()
        } else if let err = vm.error {
            TeachErrorRetry(message: err) { vm.load() }
            Spacer()
        } else {
            let stats = vm.stats
            ScrollView {
                LazyVStack(spacing: MeiliMetric.cardGap) {
                    CheckinCard(
                        todaySigned: stats?.checkin?.todaySigned == true,
                        streak: stats?.checkin?.streak ?? 0,
                        todayMinutes: stats?.study?.todayMinutes ?? 0,
                        completedCount: stats?.progress?.completedCount ?? 0,
                        totalCount: stats?.progress?.totalCount ?? 0,
                        busy: vm.checkinBusy,
                        onCheckin: { vm.checkin() })

                    let chapters = stats?.progress?.chapters ?? []
                    ForEach(Array(chapters.enumerated()), id: \.offset) { i, ch in
                        ChapterCard(index: i + 1, chapter: ch,
                                    onOpenReader: onOpenReader,
                                    onOpenQuiz: onOpenQuiz,
                                    onBlocked: { vm.toast = $0 })
                    }
                }
                .padding(.horizontal, MeiliMetric.screenH)
                .padding(.bottom, 28)
            }
        }
    }
}

/// 顶部打卡卡:今日学习分钟 + 连续打卡天数 + 完成进度 + 打卡按钮。
private struct CheckinCard: View {
    let todaySigned: Bool
    let streak: Int
    let todayMinutes: Double
    let completedCount: Int
    let totalCount: Int
    let busy: Bool
    let onCheckin: () -> Void

    var body: some View {
        MeiliCard {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("今日已学 \(Int(todayMinutes.rounded())) 分钟")
                        .font(.sz(15, weight: .bold)).foregroundStyle(MeiliColor.ink)
                    Text("连续打卡 \(streak) 天 · 已完成 \(completedCount)/\(totalCount) 章")
                        .font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3)
                }
                Spacer()
                if todaySigned {
                    StatusPill(text: "今日已打卡", kind: .ok, icon: MeiliIcons.check)
                } else {
                    MeiliButton(busy ? "打卡中…" : "打卡", kind: .soft, size: .small,
                                enabled: !busy, action: onCheckin)
                }
            }
        }
    }
}

/// 单章卡:序号圆 + 标题 + 状态 + (解锁时)三个测验分数 pill。
private struct ChapterCard: View {
    let index: Int
    let chapter: TeachChapter
    let onOpenReader: (String) -> Void
    let onOpenQuiz: (String, Int) -> Void
    let onBlocked: (String) -> Void

    private var allowed: Bool { chapter.allowed != false }   // 缺省当 true(防御写法)
    private var unlocked: Bool { chapter.unlocked == true }
    private var key: String { chapter.key ?? "" }

    var body: some View {
        MeiliCard {
            Button {
                if !allowed { onBlocked("本账号未开通该章节") }
                else if !unlocked { onBlocked("完成上一章全部测验后解锁") }
                else if !key.isEmpty { onOpenReader(key) }
            } label: {
                HStack(spacing: MeiliMetric.s3) {
                    numberBadge
                    VStack(alignment: .leading, spacing: 3) {
                        Text(chapter.title ?? key)
                            .font(.sz(14, weight: .bold))
                            .foregroundStyle(unlocked && allowed ? MeiliColor.ink : MeiliColor.ink3)
                            .multilineTextAlignment(.leading)
                        Text(statusText)
                            .font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink4)
                    }
                    Spacer()
                    if unlocked && allowed {
                        MeiliIcon(MeiliIcons.chevRight, size: MeiliMetric.iconSm)
                            .foregroundStyle(MeiliColor.ink4)
                    }
                }
            }
            .buttonStyle(.plain)

            // 解锁章:三套测验 pill(分数/未考),点击进测验
            if unlocked && allowed && !key.isEmpty {
                Spacer().frame(height: MeiliMetric.s3)
                HStack(spacing: MeiliMetric.s2) {
                    ForEach(1...3, id: \.self) { qi in
                        let score = chapter.quizScore(qi)
                        Button { onOpenQuiz(key, qi) } label: {
                            StatusPill(
                                text: score != nil ? "测验\(qi) · \(score!)分" : "测验\(qi) · 未考",
                                kind: score == nil ? .neutral : (score! >= 60 ? .ok : .warn))
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
    }

    private var statusText: String {
        if !allowed { return "未开通" }
        if !unlocked { return "完成上一章后解锁" }
        if chapter.completed == true { return "已完成 · 可随时复习" }
        return "点击阅读课件"
    }

    /// 序号圆:完成=叶绿勾 / 解锁=陶土序号 / 锁定=灰锁。
    @ViewBuilder private var numberBadge: some View {
        let (bg, fg): (Color, Color) = {
            if chapter.completed == true { return (MeiliColor.leafSoft, MeiliColor.leafText) }
            if unlocked && allowed { return (MeiliColor.claySoft, MeiliColor.clayDeep) }
            return (MeiliColor.surfaceSoft, MeiliColor.ink4)
        }()
        ZStack {
            Circle().fill(bg).frame(width: 36, height: 36)
            if chapter.completed == true {
                MeiliIcon(MeiliIcons.check, size: 18).foregroundStyle(fg)
            } else if !unlocked || !allowed {
                MeiliIcon(MeiliIcons.lock, size: 16).foregroundStyle(fg)
            } else {
                Text("\(index)").font(.sz(14, weight: .bold)).foregroundStyle(fg)
            }
        }
    }
}

/// 整页错误 + 重试(teach/followup 共用样式)。
struct TeachErrorRetry: View {
    let message: String
    let onRetry: () -> Void

    var body: some View {
        VStack(spacing: MeiliMetric.s3) {
            MeiliIcon(MeiliIcons.warn, size: 34).foregroundStyle(MeiliColor.rose)
            Text(message).font(.sz(13)).foregroundStyle(MeiliColor.ink2)
                .multilineTextAlignment(.center)
            MeiliButton("重试", action: onRetry)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 70)
        .padding(.horizontal, MeiliMetric.screenH)
    }
}
