import SwiftUI

/// 大「点击开启陪伴」圆钮。android 端对应 `components/CompanionButton.kt`,还原 warm_2 `.comp-circle`:
///  - 空闲态:陶土径向渐变 + 并蒂花蕊图标 + 浅白光环(.lotus-ring)。
///  - 进行中(live):玫瑰径向渐变 + 白色停止方块 + 呼吸光晕(@keyframes breathe 2.6s)。
///  - 唤醒/连接中(starting):白色转圈 + 玫瑰渐变 + 呼吸,点击=取消。
///
/// 红线:文案走「陪伴」系,绝不出现「录音/录制」。
struct CompanionButton: View {
    let live: Bool
    var starting: Bool = false
    var enabled: Bool = true
    let onTap: () -> Void

    @State private var pulse = false
    private var active: Bool { live || starting }
    private let d = MeiliMetric.companionCircle

    var body: some View {
        Button(action: { if enabled { onTap() } }) {
            ZStack {
                // 呼吸光晕(进行中扩散)
                Circle()
                    .fill((active ? MeiliColor.rose : MeiliColor.clay).opacity(0.08))
                    .scaleEffect(active && pulse ? 1.16 : 0.86)
                    .opacity(active ? 1 : 0)

                // 主圆
                ZStack {
                    Circle().fill(active
                        ? MeiliColor.companionLiveGradient(diameter: d)
                        : MeiliColor.companionGradient(diameter: d))
                    Circle().strokeBorder(.white.opacity(0.45), lineWidth: 1.5) // lotus-ring
                    content
                }
                .frame(width: d, height: d)
                .opacity(enabled ? 1 : 0.5)
                .shadow(color: (active ? MeiliColor.roseDeep : MeiliColor.clay)
                    .opacity(active && pulse ? 0.5 : 0.32),
                    radius: active && pulse ? 24 : 18, x: 0, y: 14)
            }
            .frame(width: d + 56, height: d + 56)
        }
        .buttonStyle(PressScaleButtonStyle(scale: 0.96))
        .disabled(!enabled)
        .onAppear { syncPulse() }
        .onChange(of: active) { _ in syncPulse() }
    }

    @ViewBuilder private var content: some View {
        if starting {
            ProgressView()
                .progressViewStyle(.circular)
                .tint(.white)
                .scaleEffect(1.4)
        } else if live {
            RoundedRectangle(cornerRadius: 11, style: .continuous) // 停止方块 .comp-square
                .fill(.white)
                .frame(width: 38, height: 38)
        } else {
            MeiliIcon(MeiliIcons.companion, size: MeiliMetric.companionIcon, lineWidth: 1.7)
                .foregroundStyle(.white)
        }
    }

    private func syncPulse() {
        if active {
            withAnimation(.easeInOut(duration: 1.3).repeatForever(autoreverses: true)) {
                pulse = true
            }
        } else {
            withAnimation(.easeOut(duration: 0.3)) { pulse = false }
        }
    }
}

/// 圆钮 + 上方计时 + 下方状态文案的完整陪伴舞台。android 端对应 `CompanionStage`。
struct CompanionStage: View {
    let timerText: String
    let statusText: String
    let hint: String
    let live: Bool
    var starting: Bool = false
    var enabled: Bool = true
    let onToggle: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            Text(timerText)
                .font(MeiliFont.timer)
                .tracking(3)
                .monospacedDigit()
                .foregroundStyle(MeiliColor.ink)
            HStack(spacing: 7) {
                if live {
                    Circle().fill(MeiliColor.rose).frame(width: 8, height: 8)
                }
                Text(statusText)
                    .font(MeiliFont.body)
                    .foregroundStyle(MeiliColor.ink2)
            }
            .padding(.top, 3)

            CompanionButton(live: live, starting: starting, enabled: enabled, onTap: onToggle)
                .padding(.vertical, 18)

            Text(hint)
                .font(MeiliFont.labelSm)
                .foregroundStyle(MeiliColor.ink3)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 24)
        }
    }
}
