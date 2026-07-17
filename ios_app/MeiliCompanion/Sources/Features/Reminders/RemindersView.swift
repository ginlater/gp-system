import SwiftUI

/// 提醒(SPEC §4.8 / warm_2 #reminders)。android 端对应 `ui/reminders/RemindersScreen.kt`。
/// 个人提醒(未绑定/未查看,可去绑定/看报告) + 升级项(店长可见,可已跟进)。
struct RemindersView: View {
    var onOpenReport: (Int) -> Void = { _ in }
    var onBind: (Int) -> Void = { _ in }

    @Environment(\.dismiss) private var dismiss
    @StateObject private var vm = RemindersViewModel()

    var body: some View {
        ScrollView {
            VStack(spacing: MeiliMetric.cardGap) {
                MeiliTopBar(title: "提醒", subtitle: "未绑定 / 未查看 / 待跟进", onBack: { dismiss() })

                if vm.loading && vm.isEmpty {
                    MeiliCard { loadingRow("正在调取提醒…") }
                } else if let e = vm.error, vm.personal.isEmpty, vm.escalation.isEmpty {
                    MeiliCard { emptyHint(MeiliIcons.warn, "调取失败", e) }
                } else if vm.isEmpty {
                    MeiliCard { emptyHint(MeiliIcons.check, "暂无待处理提醒", "未绑定、未查看的陪伴会在这里提醒你") }
                } else {
                    if !vm.personal.isEmpty {
                        SectionLabel("待处理", icon: MeiliIcons.reminder).frame(maxWidth: .infinity, alignment: .leading).padding(.leading, 2)
                        MeiliCard(tight: true) {
                            VStack(spacing: 0) {
                                ForEach(Array(vm.personal.enumerated()), id: \.offset) { idx, r in
                                    if idx > 0 { Rectangle().fill(MeiliColor.lineSoft).frame(height: 1) }
                                    personalRow(r)
                                }
                            }
                        }
                    }
                    if !vm.escalation.isEmpty {
                        SectionLabel("待跟进升级项", icon: MeiliIcons.warn).frame(maxWidth: .infinity, alignment: .leading).padding(.leading, 2)
                        MeiliCard(tight: true) {
                            VStack(spacing: 0) {
                                ForEach(Array(vm.escalation.enumerated()), id: \.offset) { idx, r in
                                    if idx > 0 { Rectangle().fill(MeiliColor.lineSoft).frame(height: 1) }
                                    escalationRow(r)
                                }
                            }
                        }
                    }
                }
            }
            .padding(.horizontal, MeiliMetric.screenH)
            .padding(.bottom, 28)
        }
        .background(MeiliColor.bg)
        .toolbar(.hidden, for: .navigationBar)
        // 每次回到本页都重拉:看过报告/绑定后,后端已少一条,数目即时变少。
        .onAppear {
            vm.load()
            // 通知权限在此申请(而非 App 启动时):用户主动看提醒 = 需要通知的场景,合规且不突兀。
            // 系统只会真正弹一次框,后续调用是无操作。
            ReminderNotifier.requestAuthorization()
        }
    }

    private func personalRow(_ r: Reminder) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top, spacing: 10) {
                MeiliIcon(r.bindTarget != nil ? MeiliIcons.link : MeiliIcons.doc, size: 18)
                    .foregroundStyle(MeiliColor.clay).padding(.top, 1)
                VStack(alignment: .leading, spacing: 3) {
                    Text(r.displayMessage).font(MeiliFont.body).foregroundStyle(MeiliColor.ink)
                        .fixedSize(horizontal: false, vertical: true)
                    if let t = r.createdAt?.nilIfBlank {
                        Text(t).font(.sz(11)).foregroundStyle(MeiliColor.ink4)
                    }
                }
            }
            HStack {
                Spacer()
                if let sid = r.reportTarget {
                    MeiliButton("查看报告", kind: .honey, size: .xs, icon: MeiliIcons.doc) { onOpenReport(sid) }
                } else if let rid = r.bindTarget {
                    MeiliButton("去绑定", size: .xs, icon: MeiliIcons.link) { onBind(rid) }
                }
            }
        }
        .padding(.vertical, 13)
    }

    private func escalationRow(_ r: Reminder) -> some View {
        let handled = vm.handledIds.contains(r.id)
        return VStack(alignment: .leading, spacing: 8) {
            VStack(alignment: .leading, spacing: 3) {
                if let a = r.advisorName?.nilIfBlank {
                    Text(a).font(.sz(12.5, weight: .bold)).foregroundStyle(MeiliColor.clayDeep)
                }
                Text(r.displayMessage).font(MeiliFont.body).foregroundStyle(MeiliColor.ink).fixedSize(horizontal: false, vertical: true)
            }
            HStack {
                if let sid = r.sessionId { MeiliButton("看报告", kind: .ghost, size: .xs) { onOpenReport(sid) } }
                Spacer()
                if handled {
                    StatusPill(text: "已跟进", kind: .ok, icon: MeiliIcons.check)
                } else {
                    MeiliButton(vm.handling.contains(r.id) ? "处理中…" : "已跟进", kind: .soft, size: .xs,
                                icon: MeiliIcons.check, enabled: !vm.handling.contains(r.id)) { vm.handle(r.id) }
                }
            }
        }
        .padding(.vertical, 13)
    }

    private func loadingRow(_ text: String) -> some View {
        HStack(spacing: 10) { ProgressView().tint(MeiliColor.clay); Text(text).font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3) }
            .frame(maxWidth: .infinity).padding(.vertical, 20)
    }
    private func emptyHint(_ icon: MeiliGlyph, _ title: String, _ sub: String?) -> some View {
        VStack(spacing: 8) {
            MeiliIcon(icon, size: 30).foregroundStyle(MeiliColor.ink4)
            Text(title).font(.sz(14.5, weight: .bold)).foregroundStyle(MeiliColor.ink2)
            if let sub { Text(sub).font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3).multilineTextAlignment(.center) }
        }
        .frame(maxWidth: .infinity).padding(.vertical, 30)
    }
}
