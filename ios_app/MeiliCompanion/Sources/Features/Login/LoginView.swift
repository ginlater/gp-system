import SwiftUI

/// 登录(SPEC §4.1 / warm_2 #login)。android 端对应 `ui/login/LoginScreen.kt`。
///
/// 暖玉柔光、克制高级:陶土圆角品牌徽标 + 衬线大标题「美丽陪伴」+ 副标,
/// 柔光卡内 账号/密码 + 「登 录」主按钮 + 错误提示 + loading。
struct LoginView: View {
    var onLoggedIn: (Me) -> Void
    @StateObject private var vm = LoginViewModel()

    // 隐私合规(对齐 android 2026-07-16 整改):默认【不勾选】同意,未勾选不可登录;政策链接可点开。
    @State private var agreed = false
    // ★2026-07-17 —— 链接收口到 PrivacyConsent 的常量。商店元数据/首启弹窗/设置页入口/
    // 本勾选框必须是同一个地址,安卓侧曾被小米按「隐私政策链接不一致」驳回过。
    private static let privacyUrl = PrivacyConsent.privacyURL
    private static let termsUrl = PrivacyConsent.termsURL

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

                    Text("美业私教")
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
                                       submitLabel: .go, onSubmit: { submit() })

                            if let error = vm.error {
                                MeiliBanner(message: error)
                            }

                            // 未勾选「同意隐私政策」不可登录(合规要求:不默认同意、需用户主动勾选)
                            MeiliButton(vm.loading ? "登录中…" : "登 录",
                                        block: true, enabled: vm.canSubmit && agreed) {
                                submit()
                            }

                            consentRow
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

    /// 键盘「前往」与登录按钮共用:未勾选同意时不发起登录,给出明确提示。
    private func submit() {
        guard agreed else {
            vm.error = "请先阅读并勾选同意《隐私政策》和《用户协议》"
            return
        }
        Task { await vm.login() }
    }

    // ---- 隐私合规:默认不勾选的同意框 + 可点开的政策链接 ----
    private var consentRow: some View {
        HStack(alignment: .center, spacing: 8) {
            Button {
                agreed.toggle()
                if agreed { vm.error = nil }
            } label: {
                ZStack {
                    RoundedRectangle(cornerRadius: 6, style: .continuous)
                        .fill(agreed ? MeiliColor.clay : MeiliColor.surfaceSoft)
                        .frame(width: 20, height: 20)
                        .overlay {
                            RoundedRectangle(cornerRadius: 6, style: .continuous)
                                .strokeBorder(agreed ? MeiliColor.clay : MeiliColor.ink3,
                                              lineWidth: MeiliMetric.borderField)
                        }
                    if agreed {
                        MeiliIcon(MeiliIcons.check, size: 13).foregroundStyle(.white)
                    }
                }
            }
            .buttonStyle(.plain)

            Text(consentText)
                .font(.sz(11))
                .tint(MeiliColor.clayDeep)   // 链接色
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// 「我已阅读并同意《隐私政策》和《用户协议》」——两个书名号是可点开的链接。
    /// 计算属性而非缓存:换肤重建时文字色跟着当前皮肤走。
    private var consentText: AttributedString {
        var s = AttributedString("我已阅读并同意")
        s.foregroundColor = MeiliColor.ink2
        var p = AttributedString("《隐私政策》")
        p.link = Self.privacyUrl
        p.font = .system(size: 11, weight: .bold)
        var mid = AttributedString("和")
        mid.foregroundColor = MeiliColor.ink2
        var t = AttributedString("《用户协议》")
        t.link = Self.termsUrl
        t.font = .system(size: 11, weight: .bold)
        return s + p + mid + t
    }
}
