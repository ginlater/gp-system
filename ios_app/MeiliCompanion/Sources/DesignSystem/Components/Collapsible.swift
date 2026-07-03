import SwiftUI

/// 可折叠卡(报告 11 个 PART / 原始音频用)。android 端对应 `components/Collapsible.kt`。
/// 头部:序号徽标(如 01) + 标题(+副标) + 右侧 chevron(展开旋转);展开显示内容。
struct Collapsible<Content: View>: View {
    let title: String
    var subtitle: String? = nil
    var badge: String? = nil
    var initiallyOpen: Bool = false
    @ViewBuilder var content: Content

    @State private var open: Bool

    init(title: String, subtitle: String? = nil, badge: String? = nil,
         initiallyOpen: Bool = false, @ViewBuilder content: () -> Content) {
        self.title = title
        self.subtitle = subtitle
        self.badge = badge
        self.initiallyOpen = initiallyOpen
        self.content = content()
        _open = State(initialValue: initiallyOpen)
    }

    var body: some View {
        MeiliCard(padded: false) {
            VStack(spacing: 0) {
                Button {
                    withAnimation(.easeInOut(duration: 0.22)) { open.toggle() }
                } label: {
                    HStack(spacing: 12) {
                        if let badge {
                            Text(badge)
                                .font(MeiliFont.serif(15))
                                .foregroundStyle(MeiliColor.clayDeep)
                                .frame(width: 34, height: 34)
                                .background(MeiliColor.clayTint)
                                .clipShape(RoundedRectangle(cornerRadius: 11, style: .continuous))
                        }
                        VStack(alignment: .leading, spacing: 2) {
                            Text(title).font(MeiliFont.foldTitle).foregroundStyle(MeiliColor.ink)
                                .multilineTextAlignment(.leading)
                            if let subtitle {
                                Text(subtitle).font(.sz(11.5)).foregroundStyle(MeiliColor.ink3)
                            }
                        }
                        Spacer(minLength: 8)
                        MeiliIcon(MeiliIcons.chevDown, size: 18)
                            .foregroundStyle(MeiliColor.ink3)
                            .rotationEffect(.degrees(open ? 180 : 0))
                    }
                    .padding(MeiliMetric.cardPad)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)

                if open {
                    VStack(alignment: .leading, spacing: 14) { content }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, MeiliMetric.cardPad)
                        .padding(.bottom, MeiliMetric.cardPad)
                }
            }
        }
    }
}
