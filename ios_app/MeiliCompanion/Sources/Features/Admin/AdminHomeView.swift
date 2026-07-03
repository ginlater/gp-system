import SwiftUI

/// 管理台首页(admin/super)。android 端对应 `ui/admin/AdminHomeScreen.kt`。
/// 当前为占位(运营看板等模块在 task 5/后续移植)。
struct AdminHomeView: View {
    let me: Me
    @EnvironmentObject private var app: AppState

    var body: some View {
        ZStack {
            MeiliColor.bg.ignoresSafeArea()
            VStack(spacing: 16) {
                ZStack {
                    RoundedRectangle(cornerRadius: 24, style: .continuous)
                        .fill(MeiliColor.companionGradient(diameter: 72))
                        .frame(width: 72, height: 72)
                    MeiliIcon(MeiliIcons.settings, size: 34).foregroundStyle(.white)
                }
                Text("管理台").font(MeiliFont.titleSm).foregroundStyle(MeiliColor.ink)
                Text("欢迎，\(me.advisorName ?? me.username ?? "管理员") · 运营看板即将上线")
                    .font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3)
                    .multilineTextAlignment(.center)
                MeiliButton("退出登录", kind: .ghost) {
                    Task { await app.logout() }
                }
                .padding(.top, 8)
            }
            .padding(.horizontal, 40)
        }
    }
}
