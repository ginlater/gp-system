import SwiftUI

/// 输入字段:label 在上、icon 前置的圆角填充输入框。android 端对应 LoginScreen 的 LoginField。
/// 还原 warm_2 `.field`:聚焦时陶土描边 + 转白底。
struct MeiliField: View {
    let label: String
    @Binding var text: String
    var placeholder: String = ""
    var icon: MeiliGlyph? = nil
    var isSecure: Bool = false
    var enabled: Bool = true
    var keyboard: UIKeyboardType = .default
    var submitLabel: SubmitLabel = .next
    var onSubmit: () -> Void = {}

    @FocusState private var focused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(label)
                .font(.sz(12.5, weight: .bold))
                .foregroundStyle(MeiliColor.ink2)

            HStack(spacing: 10) {
                if let icon {
                    MeiliIcon(icon, size: 18).foregroundStyle(MeiliColor.ink3)
                }
                Group {
                    if isSecure {
                        SecureField(placeholder, text: $text)
                    } else {
                        TextField(placeholder, text: $text)
                    }
                }
                .font(MeiliFont.bodyLarge)
                .foregroundStyle(MeiliColor.ink)
                .tint(MeiliColor.clay)
                .focused($focused)
                .keyboardType(keyboard)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(submitLabel)
                .onSubmit(onSubmit)
                .disabled(!enabled)
            }
            .padding(.horizontal, 14)
            .frame(minHeight: 52)
            .background(focused ? MeiliColor.white : MeiliColor.surfaceSoft)
            .clipShape(RoundedRectangle(cornerRadius: MeiliRadius.sm, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: MeiliRadius.sm, style: .continuous)
                    .strokeBorder(focused ? MeiliColor.clay : MeiliColor.line,
                                  lineWidth: MeiliMetric.borderField)
            }
            .animation(.easeOut(duration: 0.15), value: focused)
        }
    }
}
