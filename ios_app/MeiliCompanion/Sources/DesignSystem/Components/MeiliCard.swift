import SwiftUI

/// 柔光卡片。android 端对应 `components/MeiliCard.kt`,还原 warm_2.html `.card`:
/// surface 底、r-lg(24) 连续圆角(iOS squircle 更精致)、--sh-2 柔光阴影、line-soft 细描边、默认内边距 20。
struct MeiliCard<Content: View>: View {
    var tight: Bool = false
    var padded: Bool = true
    var cornerRadius: CGFloat = MeiliRadius.lg
    let content: Content

    init(tight: Bool = false, padded: Bool = true, cornerRadius: CGFloat = MeiliRadius.lg,
         @ViewBuilder content: () -> Content) {
        self.tight = tight
        self.padded = padded
        self.cornerRadius = cornerRadius
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) { content }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(padded ? (tight ? MeiliMetric.cardPadTight : MeiliMetric.cardPad) : 0)
            .background(MeiliColor.surface)
            .clipShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .strokeBorder(MeiliColor.lineSoft, lineWidth: MeiliMetric.borderThin)
            }
            // --sh-2: 0 8px 26px rgba(120,90,68,.09)
            .shadow(color: Color(hex: 0x785A44, alpha: 0.09), radius: 13, x: 0, y: 8)
    }
}
