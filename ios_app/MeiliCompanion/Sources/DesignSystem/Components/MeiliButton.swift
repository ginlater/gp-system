import SwiftUI

/// 点按缩放样式(还原 .btn:active transform:scale)。共享给按钮族 / 圆钮 / 图标按钮。
struct PressScaleButtonStyle: ButtonStyle {
    var scale: CGFloat = 0.97
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? scale : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

/// 暖玉柔光按钮族。胶囊形、图标 + 文字水平居中。android 端对应 `components/Buttons.kt`。
///  - `.primary` 陶土渐变实心(最高优先级操作)
///  - `.ghost`   surface 底 + 细描边(次级/取消)
///  - `.soft`    陶土 tint 浅底(轻量操作)
///  - `.sage` / `.honey` 渐变实心(已跟进 / 看报告 等)
enum MeiliButtonKind { case primary, ghost, soft, sage, honey }

enum MeiliButtonSize {
    case normal, small, xs
    var vPad: CGFloat { self == .normal ? 14 : (self == .small ? 10 : 8) }
    var hPad: CGFloat { self == .normal ? 22 : (self == .small ? 16 : 14) }
    var font: CGFloat { self == .normal ? 14.5 : (self == .small ? 13 : 12.5) }
    var icon: CGFloat { self == .normal ? 18 : (self == .small ? 16 : 15) }
    var minH: CGFloat { self == .normal ? 48 : (self == .small ? 40 : 34) }
}

struct MeiliButton: View {
    let title: String
    var kind: MeiliButtonKind = .primary
    var size: MeiliButtonSize = .normal
    var icon: MeiliGlyph? = nil
    var block: Bool = false
    var enabled: Bool = true
    let action: () -> Void

    init(_ title: String, kind: MeiliButtonKind = .primary, size: MeiliButtonSize = .normal,
         icon: MeiliGlyph? = nil, block: Bool = false, enabled: Bool = true,
         action: @escaping () -> Void) {
        self.title = title
        self.kind = kind
        self.size = size
        self.icon = icon
        self.block = block
        self.enabled = enabled
        self.action = action
    }

    var body: some View {
        Button(action: { if enabled { action() } }) {
            HStack(spacing: 8) {
                if let icon { MeiliIcon(icon, size: size.icon) }
                Text(title).font(.sz(size.font, weight: .bold))
            }
            .padding(.horizontal, size.hPad)
            .padding(.vertical, size.vPad)
            .frame(maxWidth: block ? .infinity : nil, minHeight: size.minH)
            .foregroundStyle(foreground)
            .background(background)
            .clipShape(Capsule())
            .overlay {
                if kind == .ghost {
                    Capsule().strokeBorder(MeiliColor.line, lineWidth: MeiliMetric.borderThin)
                }
            }
        }
        .buttonStyle(PressScaleButtonStyle())
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.45)
    }

    private var foreground: Color {
        switch kind {
        case .primary, .sage, .honey: return MeiliColor.white
        case .ghost: return MeiliColor.ink2
        case .soft: return MeiliColor.clayDeep
        }
    }

    @ViewBuilder private var background: some View {
        switch kind {
        case .primary: MeiliColor.primaryGradient
        case .sage: MeiliColor.sageGradient
        case .honey: MeiliColor.honeyGradient
        case .ghost: MeiliColor.surface
        case .soft: MeiliColor.clayTint
        }
    }
}
