import SwiftUI

/// ★2026-07-17 隐私合规 —— 首次启动的隐私政策弹窗(对齐 android 2.3.4 的 PrivacyConsent)。
///
/// 【为什么有这个文件】安卓侧 OPPO/小米按「首次运行未通过弹窗等明显方式提醒用户阅读
/// 隐私政策」驳回过,iOS 一起对齐:首启先糊脸弹窗,同意前不进任何页面、不发任何网络
/// 请求(RootView 把 bootstrap 也挡在同意之后);不同意则回桌面;同意状态持久化只弹一次。
enum PrivacyConsent {
    /// 全 App 唯一的隐私政策地址。
    /// ⚠️ 三处必须完全一致,少一处对不上就会被商店按「隐私政策链接不一致」打回:
    ///   ① App Store Connect 元数据里填的  ② 本弹窗里的  ③ 设置页「隐私政策」入口。
    /// 安卓侧同款常量在 privacy/PrivacyConsent.kt,改任何一边记得同步另一边。
    static let privacyURL = URL(string: "https://company.aibeautyfulwomen.com/privacy.html")!
    static let termsURL = URL(string: "https://gp.aibeautyfulwomen.com/terms-of-service")!

    private static let key = "privacy_agreed_v1"

    static var isAgreed: Bool { UserDefaults.standard.bool(forKey: key) }
    static func setAgreed() { UserDefaults.standard.set(true, forKey: key) }
}

/// 首次启动的隐私政策弹窗。同意 → onAgree(调用方持久化并放行);不同意 → 回桌面。
struct PrivacyConsentView: View {
    var onAgree: () -> Void

    var body: some View {
        ZStack {
            MeiliColor.bg.ignoresSafeArea()
            VStack(alignment: .leading, spacing: 0) {
                Text("隐私政策与用户协议")
                    .font(MeiliFont.sheetH3).foregroundStyle(MeiliColor.ink)
                    .padding(.bottom, 14)

                ScrollView {
                    Text(Self.bodyText)
                        .font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink2)
                        .lineSpacing(4)
                        .tint(MeiliColor.clay)   // 正文里《隐私政策》《用户协议》链接的颜色
                }

                HStack {
                    Spacer()
                    Button("不同意并退出") { Self.exitToHome() }
                        .font(.sz(14)).foregroundStyle(MeiliColor.ink2)
                    Button("同意") { onAgree() }
                        .font(.sz(14, weight: .bold)).foregroundStyle(MeiliColor.clay)
                        .padding(.leading, 26)
                }
                .padding(.top, 16)
            }
            .padding(20)
            .background(MeiliColor.surface)
            .clipShape(RoundedRectangle(cornerRadius: 22))
            .padding(.horizontal, 24)
        }
    }

    /// 正文尾段(单独一个字面量,别用 + 拼 —— 长拼接会把 Swift 类型检查器拖爆)。
    private static let bodyTail: String = """
    。我们将按其中的约定收集和使用您的信息:

    • 账号信息(手机号、姓名、所属机构):用于登录与身份识别。
    • 麦克风 / 蓝牙:仅在您主动使用录音、连接陪伴笔时申请,申请前会单独说明用途。
    • 设备与日志信息:用于保障服务稳定与安全。

    我们不会向第三方出售您的个人信息。您可随时在系统设置中撤回权限,也可在 App 的「设置」中注销账号。

    点击「同意」表示您已阅读并接受上述条款;点击「不同意」将退出应用。
    """

    /// 正文:说清收集什么、干什么用;《隐私政策》《用户协议》做成可点链接(监管要求「可查阅」)。
    private static var bodyText: AttributedString {
        var s = AttributedString("欢迎使用「美业私教」。我们非常重视您的个人信息保护。在您使用前,请阅读并理解")
        var p = AttributedString("《隐私政策》")
        p.link = PrivacyConsent.privacyURL
        p.font = MeiliFont.bodySm.bold()
        s.append(p)
        s.append(AttributedString("和"))
        var t = AttributedString("《用户协议》")
        t.link = PrivacyConsent.termsURL
        t.font = MeiliFont.bodySm.bold()
        s.append(t)
        s.append(AttributedString(bodyTail))
        return s
    }

    /// 「不同意」的退场。
    /// ⚠️ 不能用 exit(0):苹果明令禁止程序化终止(QA1561),那本身就是被拒理由。
    /// 用「挂起回桌面」替代 —— 用户看到的效果就是退出了,且下次启动因未同意仍会弹本框。
    private static func exitToHome() {
        UIControl().sendAction(#selector(URLSessionTask.suspend), to: UIApplication.shared, for: nil)
    }
}
