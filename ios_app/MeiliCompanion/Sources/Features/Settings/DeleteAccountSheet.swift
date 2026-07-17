import SwiftUI

/// ★2026-07-17 合规 —— 注销账号底部弹窗(苹果 5.1.1(v) 硬性要求 + 小米驳回项)。
/// android 端对应 SettingsScreen 的 DeleteAccountDialog,后端同一个接口。
///
/// 【为什么要输密码】注销不可逆。手机放桌上被人顺手点两下就把号注销了,这种事必须堵死。
/// 密码由服务端校验(gp-system webapp.py 的 api_delete_my_account),前端不碰哈希。
///
/// 【为什么要写清删什么留什么】注销 ≠ 数据全没。录音和报告是门店花钱买的经营资产,
/// 归门店所有、不跟着删(隐私政策已载明)。不讲清楚,员工会以为点了这个老板的报告
/// 就没了,或者反过来以为自己的记录能一键抹掉 —— 两种误解都会出事。
struct DeleteAccountSheet: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var app: AppState

    @State private var pwd = ""
    @State private var busy = false
    @State private var error: String?

    private struct OkBody: Decodable { let ok: Bool? }

    var body: some View {
        ZStack {
            MeiliColor.bg.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: MeiliMetric.s2) {
                    Text("注销账号")
                        .font(MeiliFont.sheetH3).foregroundStyle(MeiliColor.ink)
                        .padding(.top, 22)
                    Text("注销后将删除您的登录账号与个人信息（手机号、姓名、工号），此操作不可恢复，您将无法再用该账号登录。\n\n您此前录制的接待记录与分析报告属于所属机构的经营数据，将按隐私政策由机构继续保留，不会随注销一并删除。\n\n请输入登录密码以确认。")
                        .font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink2)
                        .lineSpacing(3)
                        .padding(.bottom, 10)

                    MeiliField(label: "登录密码", text: $pwd, isSecure: true, enabled: !busy,
                               submitLabel: .done)

                    if let error {
                        Text(error).font(MeiliFont.bodySm).foregroundStyle(MeiliColor.roseText)
                    }

                    MeiliButton(busy ? "注销中…" : "确认注销", block: true, enabled: !busy && !pwd.isEmpty) {
                        submit()
                    }
                    .padding(.top, MeiliMetric.s3)

                    MeiliButton("取消", kind: .ghost, block: true, enabled: !busy) {
                        dismiss()
                    }
                }
                .padding(.horizontal, MeiliMetric.screenH)
                .padding(.bottom, 24)
            }
            .scrollDismissesKeyboard(.interactively)
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .interactiveDismissDisabled(busy)
    }

    private func submit() {
        error = nil
        busy = true
        Task {
            do {
                // 密码错 → 后端 403 {"error":"密码不正确"},APIError 会把这句原样抠出来给用户
                let _: OkBody = try await APIClient.shared.sendJSON(
                    "api/me/account", method: "DELETE", body: ["password": pwd])
                // 成功:服务端已删账号并清会话。走一遍 logout 把本地 Cookie/凭据/
                // 各子系统(teach/回访/高情商/KPI)缓存全部作废,并把导航切回登录页。
                await app.logout()
                dismiss()
            } catch {
                busy = false
                self.error = error.localizedDescription
            }
        }
    }
}
