import SwiftUI

/// 区块小标题。android 端对应 `components/SectionLabel.kt`,还原 .section-lbl:
/// 11.5 / 800 / ink-2 字、0.5 字距、可选前置鼠尾草深色图标(15)。
/// 用于「陪伴设备」「本次将分析的陪伴」「累积标签」等分组标题。
struct SectionLabel: View {
    let text: String
    var icon: MeiliGlyph? = nil

    init(_ text: String, icon: MeiliGlyph? = nil) {
        self.text = text
        self.icon = icon
    }

    var body: some View {
        HStack(spacing: 6) {
            if let icon {
                MeiliIcon(icon, size: 15)
                    .foregroundStyle(MeiliColor.sageDeep)
            }
            Text(text)
                .font(.sz(11.5, weight: .heavy))
                .tracking(0.5)
                .foregroundStyle(MeiliColor.ink2)
        }
        .padding(.horizontal, 2)
    }
}
