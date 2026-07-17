import SwiftUI

/// 多系统工作台(宫格)。android 端对应 `ui/workspace/WorkspaceScreen.kt`。
///
/// /api/me 的 systems ≥2 的账号登录后落这里,宫格展示本账号开通的系统:
/// 已接入 key 点击进对应系统;未接入 → toast「即将上线」。
///
/// ⚠️ 苹果审核红线:App 主体是原生录音(工牌),本屏只是模块入口,
/// 不做成"一堆网页格子"——native/hybrid 系统都是原生页承载。
struct WorkspaceView: View {
    let me: Me
    let onOpenSystem: (String) -> Void
    let onOpenSettings: () -> Void

    @State private var toast: String?
    /// lastMe 兜底(进程恢复等极端情况 systems 为空时重拉一次)。
    @State private var refreshedMe: Me?

    private var systems: [SystemEntry] {
        refreshedMe?.systems ?? me.systems ?? []
    }

    var body: some View {
        ZStack(alignment: .bottom) {
            MeiliColor.bg.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    header
                    Text("选择要进入的系统")
                        .font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3)
                        .padding(.top, 4)
                        .padding(.bottom, MeiliMetric.s4)

                    if systems.isEmpty {
                        HStack {
                            Spacer()
                            ProgressView().tint(MeiliColor.clay)
                            Spacer()
                        }
                        .padding(.top, 80)
                    } else {
                        LazyVGrid(columns: [GridItem(.flexible(), spacing: MeiliMetric.cardGap),
                                            GridItem(.flexible())],
                                  spacing: MeiliMetric.cardGap) {
                            ForEach(systems, id: \.self) { sys in
                                SystemTile(sys: sys) {
                                    if let key = sys.key, AppRoute.wiredSystemKeys.contains(key) {
                                        onOpenSystem(key)
                                    } else {
                                        showToast("「\(sys.name ?? "该系统")」即将上线")
                                    }
                                }
                            }
                        }
                    }
                }
                .padding(.horizontal, MeiliMetric.screenH)
                .padding(.bottom, 28)
            }

            if let t = toast {
                Text(t).font(MeiliFont.bodySm).foregroundStyle(.white)
                    .padding(.horizontal, 16).padding(.vertical, 11)
                    .background(MeiliColor.inkSurface).clipShape(Capsule())
                    .padding(.bottom, 40)
                    .task(id: t) {
                        try? await Task.sleep(nanoseconds: 2_200_000_000)
                        if !Task.isCancelled { toast = nil }
                    }
            }
        }
        .task {
            // Gate 刚拉过 /api/me → 一般直接命中;systems 为空(进程恢复)时兜底重拉一次。
            if (me.systems ?? []).isEmpty, refreshedMe == nil {
                refreshedMe = await AuthManager().currentUser()
            }
        }
    }

    private func showToast(_ msg: String) { toast = msg }

    // ---- 头部:问候 + 标题 + 设置 ----
    private var header: some View {
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 3) {
                let name = me.advisorName?.nilIfBlank ?? me.username?.nilIfBlank
                Text(name == nil ? "你好" : "你好，\(name!)")
                    .font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3)
                Text("工作台")
                    .font(MeiliFont.title).foregroundStyle(MeiliColor.ink)
            }
            Spacer()
            IconSquareButton(icon: MeiliIcons.settings) { onOpenSettings() }
        }
        .padding(.top, 14)
        .padding(.bottom, 10)
    }
}

/// 单个系统格子:图标 chip + 系统名 + 一句话描述。
private struct SystemTile: View {
    let sys: SystemEntry
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            MeiliCard {
                ZStack {
                    Circle().fill(MeiliColor.claySoft).frame(width: 48, height: 48)
                    MeiliIcon(systemIcon(sys.key), size: MeiliMetric.iconLg)
                        .foregroundStyle(MeiliColor.clayDeep)
                }
                Spacer().frame(height: MeiliMetric.s3)
                Text(sys.name ?? "未命名系统")
                    .font(.sz(15, weight: .bold)).foregroundStyle(MeiliColor.ink)
                    .multilineTextAlignment(.leading)
                if let desc = sys.desc?.nilIfBlank {
                    Spacer().frame(height: 4)
                    Text(desc)
                        .font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3)
                        .multilineTextAlignment(.leading)
                        .lineLimit(2)
                }
            }
        }
        .buttonStyle(PressScaleButtonStyle(scale: 0.97))
    }
}

/// 系统 key → 图标(与安卓工作台宫格一致;未知 key 用 Spark 兜底)。
func systemIcon(_ key: String?) -> MeiliGlyph {
    switch key {
    case "gongpai": return MeiliIcons.companion
    case "teach": return MeiliIcons.headphone
    case "followup": return MeiliIcons.phone
    case "higheq": return MeiliIcons.heart
    case "chat": return MeiliIcons.target
    case "kpi": return MeiliIcons.trend
    case "kpi_admin": return MeiliIcons.gem
    default: return MeiliIcons.spark
    }
}
