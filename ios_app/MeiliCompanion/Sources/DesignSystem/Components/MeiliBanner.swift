import SwiftUI

/// 横幅提示。android 端对应 LoginScreen 的 ErrorBanner,还原 warm_2 `.banner.*`:
/// 柔底 + 图标 + 深字。`.danger` 玫瑰、`.info` 鼠尾草、`.warn` 蜜色。
struct MeiliBanner: View {
    enum Kind { case danger, info, warn }
    let message: String
    var kind: Kind = .danger

    var body: some View {
        HStack(alignment: .top, spacing: 9) {
            MeiliIcon(glyph, size: 18).foregroundStyle(fg)
            Text(message)
                .font(.sz(12.5, weight: .semibold))
                .foregroundStyle(fg)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 15)
        .padding(.vertical, 13)
        .background(bg)
        .clipShape(RoundedRectangle(cornerRadius: MeiliRadius.md, style: .continuous))
    }

    private var glyph: MeiliGlyph {
        switch kind {
        case .danger: return MeiliIcons.warn
        case .info: return MeiliIcons.info
        case .warn: return MeiliIcons.warn
        }
    }
    private var fg: Color {
        switch kind {
        case .danger: return MeiliColor.roseText
        case .info: return MeiliColor.sageDeep
        case .warn: return MeiliColor.honeyText
        }
    }
    private var bg: Color {
        switch kind {
        case .danger: return MeiliColor.roseSoft
        case .info: return MeiliColor.sageTint
        case .warn: return MeiliColor.honeySoft
        }
    }
}
