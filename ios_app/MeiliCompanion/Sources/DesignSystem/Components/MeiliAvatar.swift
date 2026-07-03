import SwiftUI

/// 头像:姓名首字 + 暖玉渐变底(对齐 warm_2 `.avatar`)。
struct MeiliAvatar: View {
    let name: String
    var size: CGFloat = MeiliMetric.avatar

    var body: some View {
        Text(String(name.prefix(1)))
            .font(MeiliFont.serif(size * 0.37))
            .foregroundStyle(MeiliColor.clayDeep)
            .frame(width: size, height: size)
            .background(MeiliColor.avatarGradient)
            .clipShape(RoundedRectangle(cornerRadius: size * 0.34, style: .continuous))
    }
}
