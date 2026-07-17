import SwiftUI

/// 系统切换底部弹窗(多系统整合 P1.1)。android 端对应 `ui/workspace/SystemSwitcher.kt`。
///
/// 在任一系统内部(工牌首页/网课首页顶栏、设置页)唤起,列出本账号开通的全部系统
/// (开几个显示几个),当前系统标「当前」,点其它项切过去——不用退出重登。
/// 数据源 AuthManager.lastMe(登录后进程内缓存;空则兜底重拉 /api/me)。
struct SystemSwitcherSheet: View {
    /// 当前所在系统 key(标「当前」+ 点击忽略)。
    let currentKey: String
    /// 切换回调(只会带回已接入且非当前的 key)。
    let onSwitch: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var me: Me? = AuthManager.lastMe
    @State private var toast: String?

    private var systems: [SystemEntry] { me?.systems ?? [] }

    var body: some View {
        ZStack(alignment: .bottom) {
            MeiliColor.bg.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: MeiliMetric.s2) {
                    Text("切换工作台")
                        .font(MeiliFont.sheetH3).foregroundStyle(MeiliColor.ink)
                        .padding(.top, 22)
                    Text("本账号已开通 \(systems.count) 个系统")
                        .font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3)
                        .padding(.bottom, 8)

                    ForEach(systems, id: \.self) { sys in
                        row(sys)
                    }
                }
                .padding(.horizontal, MeiliMetric.screenH)
                .padding(.bottom, 24)
            }

            if let t = toast {
                Text(t).font(MeiliFont.bodySm).foregroundStyle(.white)
                    .padding(.horizontal, 16).padding(.vertical, 11)
                    .background(MeiliColor.inkSurface).clipShape(Capsule())
                    .padding(.bottom, 24)
                    .task(id: t) {
                        try? await Task.sleep(nanoseconds: 2_000_000_000)
                        if !Task.isCancelled { toast = nil }
                    }
            }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .task {
            if me == nil { me = await AuthManager().currentUser() }
        }
    }

    private func row(_ sys: SystemEntry) -> some View {
        let current = sys.key == currentKey
        return Button {
            guard let key = sys.key else { return }
            if current {
                dismiss()
            } else if AppRoute.wiredSystemKeys.contains(key) {
                dismiss()
                onSwitch(key)
            } else {
                toast = "「\(sys.name ?? "该系统")」即将上线"
            }
        } label: {
            HStack(spacing: MeiliMetric.s3) {
                ZStack {
                    Circle().fill(MeiliColor.claySoft).frame(width: 40, height: 40)
                    MeiliIcon(systemIcon(sys.key), size: MeiliMetric.icon)
                        .foregroundStyle(MeiliColor.clayDeep)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(sys.name ?? "未命名系统")
                        .font(.sz(14, weight: .bold)).foregroundStyle(MeiliColor.ink)
                    if let desc = sys.desc?.nilIfBlank {
                        Text(desc).font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3)
                            .lineLimit(1)
                    }
                }
                Spacer()
                if current {
                    StatusPill(text: "当前", kind: .clay, icon: MeiliIcons.check)
                } else {
                    MeiliIcon(MeiliIcons.chevRight, size: MeiliMetric.iconSm)
                        .foregroundStyle(MeiliColor.ink4)
                }
            }
            .padding(.horizontal, MeiliMetric.s3)
            .padding(.vertical, 12)
            .background(current ? MeiliColor.clayTint : MeiliColor.surface)
            .clipShape(RoundedRectangle(cornerRadius: MeiliRadius.md, style: .continuous))
        }
        .buttonStyle(.plain)
    }
}
