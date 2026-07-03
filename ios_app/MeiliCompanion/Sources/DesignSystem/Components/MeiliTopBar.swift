import SwiftUI

/// 顶栏。android 端对应 `components/MeiliTopBar.kt`,还原 .appbar:
/// 左侧可选返回方圆角按钮 + 标题(含可选副标题),右侧自定义动作区(图标按钮)。
struct MeiliTopBar<Actions: View>: View {
    let title: String
    var subtitle: String? = nil
    var onBack: (() -> Void)? = nil
    @ViewBuilder var actions: Actions

    init(title: String, subtitle: String? = nil, onBack: (() -> Void)? = nil,
         @ViewBuilder actions: () -> Actions = { EmptyView() }) {
        self.title = title
        self.subtitle = subtitle
        self.onBack = onBack
        self.actions = actions()
    }

    var body: some View {
        HStack(spacing: 10) {
            if let onBack {
                IconSquareButton(icon: MeiliIcons.back, back: true, action: onBack)
            }
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.sz(16, weight: .heavy))
                    .foregroundStyle(MeiliColor.ink)
                if let subtitle {
                    Text(subtitle)
                        .font(MeiliFont.bodySm)
                        .foregroundStyle(MeiliColor.ink3)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            HStack(spacing: 9) { actions }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 10)
    }
}

/// 顶栏 / 右上角图标按钮(.iconbtn):44×44 surface 方圆角 + 细描边 + 轻浮起,点按 0.94 缩放。
/// 可选右上角红点 badge(如提醒未读数)。
struct TopBarIconButton: View {
    let icon: MeiliGlyph
    var badge: String? = nil
    let action: () -> Void

    var body: some View {
        IconSquareButton(icon: icon, back: false, action: action)
            .overlay(alignment: .topTrailing) {
                if let badge {
                    Text(badge)
                        .font(.sz(10.5, weight: .bold))
                        .foregroundStyle(MeiliColor.white)
                        .padding(.horizontal, 5)
                        .padding(.vertical, 1)
                        .background(MeiliColor.clay)
                        .clipShape(Capsule())
                        .overlay(Capsule().strokeBorder(MeiliColor.bg, lineWidth: 2.5))
                        .offset(x: 4, y: -4)
                }
            }
    }
}

struct IconSquareButton: View {
    let icon: MeiliGlyph
    var back: Bool = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            MeiliIcon(icon, size: MeiliMetric.icon)
                .foregroundStyle(MeiliColor.ink2)
                .frame(width: back ? MeiliMetric.iconButtonBack : MeiliMetric.iconButton,
                       height: back ? MeiliMetric.iconButtonBack : MeiliMetric.iconButton)
                .background(MeiliColor.surface)
                .clipShape(RoundedRectangle(cornerRadius: back ? 14 : 16, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: back ? 14 : 16, style: .continuous)
                        .strokeBorder(MeiliColor.line, lineWidth: MeiliMetric.borderThin)
                }
                .shadow(color: Color(hex: 0x785A44, alpha: 0.06), radius: 5, x: 0, y: 2)
        }
        .buttonStyle(PressScaleButtonStyle(scale: 0.94))
    }
}
