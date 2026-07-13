import SwiftUI
import AVFoundation

/// 极简音频播放器(原始音频全程,无 60s 限制)。
@MainActor
final class AudioPlayer: ObservableObject {
    @Published var playing = false
    @Published var current: Double = 0
    @Published var duration: Double = 0
    @Published var ready = false

    private var player: AVPlayer?
    private var observer: Any?
    private var loadedURL: String?

    deinit {   // 复查 S4:onDisappear 未触发的销毁路径(换肤重建等)也要拆 observer↔player 互持
        if let observer { player?.removeTimeObserver(observer) }
        player?.pause()
    }

    func load(_ urlString: String) {
        guard urlString != loadedURL, let url = URL(string: urlString) else { return }
        stop()
        loadedURL = urlString
        // 复查 B3/B4:手机麦录音中绝不动会话(独占 .playback 会拆掉录音输入,静默丢录音);
        // 其他时候也带 mixWithOthers,不顶掉微信语音/保活会话
        let m = RecordingManager.shared
        if !(m.isLive && m.source == .phone) {
            try? AVAudioSession.sharedInstance().setCategory(.playback, options: [.mixWithOthers])
            try? AVAudioSession.sharedInstance().setActive(true)
        }
        let p = AVPlayer(playerItem: AVPlayerItem(url: url))
        // 试听要"早出声"：关掉 AVPlayer 的防卡顿评估(默认会先估"整个文件能否不卡地放完"，
        // 网慢/文件大时迟迟不开播)，改为缓到几秒就立即播；中途真跟不上再短暂停顿续播。
        p.automaticallyWaitsToMinimizeStalling = false
        player = p
        observer = p.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: 0.25, preferredTimescale: 600), queue: .main
        ) { [weak self] t in
            guard let self else { return }
            self.current = t.seconds.isFinite ? t.seconds : 0
            if let d = p.currentItem?.duration.seconds, d.isFinite, d > 0 {
                self.duration = d; self.ready = true
            }
        }
    }

    func toggle() {
        guard let player else { return }
        if playing { player.pause() } else { player.playImmediately(atRate: 1.0) }  // 不等评估,立刻开播
        playing.toggle()
    }

    func seek(_ seconds: Double) {
        player?.seek(to: CMTime(seconds: max(0, seconds), preferredTimescale: 600))
    }

    func stop() {
        player?.pause()
        if let observer { player?.removeTimeObserver(observer) }
        observer = nil
        player = nil
        playing = false
        current = 0
        duration = 0
        ready = false
        loadedURL = nil
    }
}

/// 列表试听用的迷你进度条:拖动跳转 + 当前/总时长。挂在正在播放的那一行下方。
struct AuditionScrubber: View {
    @ObservedObject var player: AudioPlayer
    @State private var scrubbing = false
    @State private var scrubValue = 0.0

    var body: some View {
        HStack(spacing: 8) {
            Text(label(player.current)).font(.sz(10)).foregroundStyle(MeiliColor.ink3)
                .frame(width: 36, alignment: .leading)
            Slider(value: Binding(
                get: { scrubbing ? scrubValue : player.current },
                set: { scrubValue = $0 }
            ), in: 0...max(1, player.duration), onEditingChanged: { editing in
                scrubbing = editing
                if !editing { player.seek(scrubValue) }
            })
            .tint(MeiliColor.clay)
            Text(player.duration > 0 ? label(player.duration) : "--:--")
                .font(.sz(10)).foregroundStyle(MeiliColor.ink3)
                .frame(width: 36, alignment: .trailing)
        }
    }

    private func label(_ s: Double) -> String {
        guard s.isFinite, s >= 0 else { return "00:00" }
        let t = Int(s); return String(format: "%02d:%02d", t / 60, t % 60)
    }
}

/// 列表试听圆钮(独立观察 player;审计 P8:播放心跳只重绘这个小钮,不再整页 4Hz 重绘)。
struct AuditionPlayButton: View {
    @ObservedObject var player: AudioPlayer
    let isCurrent: Bool
    let onTap: () -> Void

    var body: some View {
        let isThis = isCurrent && player.playing
        Button(action: onTap) {
            ZStack {
                Circle().fill(MeiliColor.clayTint).frame(width: 40, height: 40)
                if isThis {
                    HStack(spacing: 3) {
                        Capsule().fill(MeiliColor.clayDeep).frame(width: 3, height: 13)
                        Capsule().fill(MeiliColor.clayDeep).frame(width: 3, height: 13)
                    }
                } else {
                    MeiliIcon(MeiliIcons.play, size: 16).foregroundStyle(MeiliColor.clayDeep)
                }
            }
        }
        .buttonStyle(PressScaleButtonStyle())
    }
}

/// 原始音频折叠内容:播放器(播放/暂停 + 进度条 + 时间) + 逐字转写。
/// 审计 P3:本视图**不观察** player——播放进度 4Hz 心跳只重绘 PlayerBar 小子视图,
/// 几千行转写的解析/渲染不再每 0.25s 重来一遍。
struct AudioFoldContent: View {
    let player: AudioPlayer
    let urlString: String?
    let loading: Bool
    let transcript: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            if urlString != nil {
                PlayerBar(player: player)
            } else if loading {
                HStack(spacing: 8) {
                    ProgressView().tint(MeiliColor.clay)
                    Text("正在获取音频…").font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3)
                }
            } else {
                Text("此片段暂无可播放音频（转写与报告仍可查看）")
                    .font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3)
            }
            transcriptView
            Text("AI 自动转写，仅供顾问复盘参考")
                .font(.sz(10.5)).foregroundStyle(MeiliColor.ink4)
        }
    }

    @ViewBuilder private var transcriptView: some View {
        let lines = parseTranscript(transcript)
        if lines.isEmpty {
            Text("暂无逐字转写").font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3)
        } else {
            VStack(alignment: .leading, spacing: 10) {
                ForEach(Array(lines.enumerated()), id: \.offset) { _, ln in
                    VStack(alignment: .leading, spacing: 3) {
                        HStack(spacing: 6) {
                            Text(ln.speaker)
                                .font(.sz(10.5, weight: .bold))
                                .foregroundStyle(ln.isAdvisor ? MeiliColor.clayDeep : MeiliColor.sageDeep)
                            if let t = ln.time {
                                Text(t).font(.sz(10)).foregroundStyle(MeiliColor.ink4)
                            }
                        }
                        Text(ln.text).font(MeiliFont.body).foregroundStyle(MeiliColor.ink)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(10)
                    .background(ln.isAdvisor ? MeiliColor.clayTint.opacity(0.5) : MeiliColor.surfaceSoft)
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
            }
        }
    }

}

/// 播放条(独立观察 player,4Hz 心跳只重绘这一小块)。
struct PlayerBar: View {
    @ObservedObject var player: AudioPlayer
    @State private var scrubbing = false
    @State private var scrubValue = 0.0

    var body: some View {
        VStack(spacing: 8) {
            HStack(spacing: 12) {
                Button { player.toggle() } label: {
                    MeiliIcon(player.playing ? MeiliIcons.warn : MeiliIcons.play, size: 18)
                        .foregroundStyle(.white)
                        .frame(width: 40, height: 40)
                        .background(MeiliColor.primaryGradient)
                        .clipShape(Circle())
                }
                .buttonStyle(PressScaleButtonStyle())
                .overlay {
                    // play 图标用三角;暂停用两竖条(warn 占位不贴切,改画竖条)
                    if player.playing {
                        HStack(spacing: 4) {
                            Capsule().fill(.white).frame(width: 4, height: 15)
                            Capsule().fill(.white).frame(width: 4, height: 15)
                        }
                        .allowsHitTesting(false)
                    }
                }

                Slider(value: Binding(
                    get: { scrubbing ? scrubValue : player.current },
                    set: { scrubValue = $0 }
                ), in: 0...max(1, player.duration), onEditingChanged: { editing in
                    scrubbing = editing
                    if !editing { player.seek(scrubValue) }
                })
                .tint(MeiliColor.clay)
            }
            HStack {
                Text(timeLabel(player.current)).font(.sz(11)).foregroundStyle(MeiliColor.ink3)
                Spacer()
                Text(player.duration > 0 ? timeLabel(player.duration) : "--:--")
                    .font(.sz(11)).foregroundStyle(MeiliColor.ink3)
            }
        }
    }

    private func timeLabel(_ s: Double) -> String {
        guard s.isFinite, s >= 0 else { return "00:00" }
        let t = Int(s); return String(format: "%02d:%02d", t / 60, t % 60)
    }
}

/// 转写行。
private struct TranscriptLine { let speaker: String; let time: String?; let text: String; let isAdvisor: Bool }

/// 解析 asr_transcript 文本:每行 "[5.20s - 7.60s] 说话人0: 文本"。
private func parseTranscript(_ raw: String?) -> [TranscriptLine] {
    guard let raw, !raw.isEmpty else { return [] }
    var out: [TranscriptLine] = []
    for line in raw.split(separator: "\n") {
        let s = String(line).trimmingCharacters(in: .whitespaces)
        if s.isEmpty { continue }
        var time: String? = nil
        var rest = s
        if s.hasPrefix("["), let close = s.firstIndex(of: "]") {
            time = String(s[s.index(after: s.startIndex)..<close])
                .replacingOccurrences(of: "s", with: "")
            rest = String(s[s.index(after: close)...]).trimmingCharacters(in: .whitespaces)
        }
        // "说话人0: 文本"
        var speaker = "说话人"
        var text = rest
        if let colon = rest.range(of: "：") ?? rest.range(of: ":") {
            speaker = String(rest[..<colon.lowerBound]).trimmingCharacters(in: .whitespaces)
            text = String(rest[colon.upperBound...]).trimmingCharacters(in: .whitespaces)
        }
        let isAdvisor = speaker.contains("0")
        out.append(TranscriptLine(speaker: speaker, time: time, text: text, isAdvisor: isAdvisor))
    }
    return out
}
