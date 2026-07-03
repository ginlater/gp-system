import SwiftUI

/// 登录(SPEC §4.1 / warm_2 #login)。android 端对应 `ui/login/LoginScreen.kt`。
///
/// 暖玉柔光、克制高级:陶土圆角品牌徽标 + 衬线大标题「美丽陪伴」+ 副标,
/// 柔光卡内 账号/密码 + 「登 录」主按钮 + 错误提示 + loading。
struct LoginView: View {
    var onLoggedIn: (Me) -> Void
    @StateObject private var vm = LoginViewModel()

    var body: some View {
        ZStack {
            MeiliColor.bg.ignoresSafeArea()
            ScrollView {
                VStack(spacing: 0) {
                    // 品牌徽标:90×90 圆角 30、陶土径向高光、并蒂花蕊
                    ZStack {
                        RoundedRectangle(cornerRadius: 30, style: .continuous)
                            .fill(MeiliColor.companionGradient(diameter: 90))
                            .frame(width: 90, height: 90)
                            .shadow(color: MeiliColor.clay.opacity(0.30), radius: 18, y: 12)
                        MeiliIcon(MeiliIcons.companion, size: 46).foregroundStyle(.white)
                    }
                    .padding(.top, 44)

                    Text("美丽陪伴")
                        .font(MeiliFont.brandTitle).tracking(3)
                        .foregroundStyle(MeiliColor.clayDeep)
                        .padding(.top, 20)
                    Text("温柔记录每一次陪伴 · 让美更被读懂")
                        .font(.sz(12.5)).tracking(0.5)
                        .foregroundStyle(MeiliColor.ink2)
                        .padding(.top, 8)

                    MeiliCard {
                        VStack(spacing: 15) {
                            MeiliField(label: "账号", text: $vm.username,
                                       placeholder: "工号 / 手机号", icon: MeiliIcons.profile,
                                       enabled: !vm.loading)
                            MeiliField(label: "密码", text: $vm.password,
                                       placeholder: "请输入密码", icon: MeiliIcons.lock,
                                       isSecure: true, enabled: !vm.loading,
                                       submitLabel: .go, onSubmit: { Task { await vm.login() } })

                            if let error = vm.error {
                                MeiliBanner(message: error)
                            }

                            MeiliButton(vm.loading ? "登录中…" : "登 录",
                                        block: true, enabled: vm.canSubmit) {
                                Task { await vm.login() }
                            }

                            Text("登录即代表同意《服务协议》与《隐私政策》")
                                .font(.sz(10.5))
                                .foregroundStyle(MeiliColor.ink3)
                                .multilineTextAlignment(.center)
                                .frame(maxWidth: .infinity)
                        }
                    }
                    .padding(.top, 30)

                    Text("陪伴师端 · 高端身体美容陪伴")
                        .font(.sz(10.5))
                        .foregroundStyle(MeiliColor.ink3)
                        .padding(.top, 12)
                }
                .padding(.horizontal, 30)
                .padding(.bottom, 28)
            }
            .scrollDismissesKeyboard(.interactively)
        }
        .onAppear { vm.onSuccess = onLoggedIn }
    }
}
