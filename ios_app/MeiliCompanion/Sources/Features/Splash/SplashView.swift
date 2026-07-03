import SwiftUI

/// 启动/角色判定中的过渡屏:品牌徽标 + 加载圈。
struct SplashView: View {
    var body: some View {
        ZStack {
            MeiliColor.bg.ignoresSafeArea()
            VStack(spacing: 18) {
                ZStack {
                    RoundedRectangle(cornerRadius: 30, style: .continuous)
                        .fill(MeiliColor.companionGradient(diameter: 90))
                        .frame(width: 90, height: 90)
                        .shadow(color: MeiliColor.clay.opacity(0.30), radius: 18, y: 12)
                    MeiliIcon(MeiliIcons.companion, size: 46).foregroundStyle(.white)
                }
                Text("美丽陪伴")
                    .font(MeiliFont.brandTitle).tracking(3)
                    .foregroundStyle(MeiliColor.clayDeep)
                ProgressView().tint(MeiliColor.clay).padding(.top, 4)
            }
        }
    }
}
