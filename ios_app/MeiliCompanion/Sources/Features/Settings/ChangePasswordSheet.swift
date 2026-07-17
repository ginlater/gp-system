import SwiftUI

/// 修改密码底部弹窗:旧密码 + 新密码 + 确认 → 全系统同步(UnifiedPasswordRepository)。
/// android 端对应 SettingsScreen 的 ChangePasswordSheet。
struct ChangePasswordSheet: View {
    @Environment(\.dismiss) private var dismiss

    @State private var oldPwd = ""
    @State private var newPwd = ""
    @State private var confirmPwd = ""
    @State private var busy = false
    @State private var error: String?
    @State private var doneMsg: String?

    var body: some View {
        ZStack {
            MeiliColor.bg.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: MeiliMetric.s2) {
                    Text("修改密码")
                        .font(MeiliFont.sheetH3).foregroundStyle(MeiliColor.ink)
                        .padding(.top, 22)
                    Text("改的是登录密码,工牌 + 各工作台会一起同步")
                        .font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3)
                        .padding(.bottom, 10)

                    MeiliField(label: "原密码", text: $oldPwd, isSecure: true, enabled: !busy)
                    MeiliField(label: "新密码(至少 6 位)", text: $newPwd, isSecure: true, enabled: !busy)
                    MeiliField(label: "确认新密码", text: $confirmPwd, isSecure: true, enabled: !busy,
                               submitLabel: .done)

                    if let error {
                        Text(error).font(MeiliFont.bodySm).foregroundStyle(MeiliColor.roseText)
                    }
                    if let doneMsg {
                        Text(doneMsg).font(MeiliFont.bodySm).foregroundStyle(MeiliColor.leafText)
                    }

                    MeiliButton(busy ? "提交中…" : "确认修改", block: true, enabled: !busy) {
                        submit()
                    }
                    .padding(.top, MeiliMetric.s3)
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
        if oldPwd.isEmpty || newPwd.isEmpty { error = "请填写完整"; return }
        if newPwd.count < 6 { error = "新密码至少 6 位"; return }
        if newPwd != confirmPwd { error = "两次新密码不一致"; return }
        if newPwd == oldPwd { error = "新密码不能与原密码相同"; return }
        busy = true
        Task {
            let r = await UnifiedPasswordRepository().changeAll(old: oldPwd, new: newPwd)
            busy = false
            if r.ok {
                doneMsg = r.message
                try? await Task.sleep(nanoseconds: 1_400_000_000)
                dismiss()
            } else {
                error = r.message
            }
        }
    }
}
