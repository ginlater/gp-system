import SwiftUI
import AVFoundation

/// teach 课件正文(原生渲染,2026-07-16 起替代 WKWebView)。
///
/// 课件仍是服务器上的静态 html(服务器改内容 App 即时生效),但渲染改为:
/// 拉 html → CoursewareParser 解析成 tab/卡片/块 → 原生控件绘制;
/// 音频用原生播放器(播放/进度/倍速),不再依赖网页 <audio>。
///
/// 学习心跳:页面在栈顶期间每 30s POST /teach/heartbeat(服务端 25s 节流),
/// 离开页面即停(.task 自动取消),时长不虚计。
/// A± 调全局字体档位(与设置页同一份,所有文字块即时缩放)。
struct TeachReaderView: View {
    let chapterKey: String

    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var theme = ThemeManager.shared
    @State private var tabs: [CourseTab]?
    @State private var loadError: String?
    @State private var tabIndex = 0

    /// 章标题:首页刚拉过 stats,进程内缓存直查;查不到退回通用标题。
    private var title: String {
        TeachRepository.lastStats?.progress?.chapters?
            .first { $0.key == chapterKey }?.title ?? "课件正文"
    }

    var body: some View {
        VStack(spacing: 0) {
            MeiliTopBar(title: title, onBack: { dismiss() }) {
                FontZoomButton(text: "A－") { theme.setFontScale(max(0.8, theme.fontScale - 0.1)) }
                FontZoomButton(text: "A＋") { theme.setFontScale(min(1.5, theme.fontScale + 0.1)) }
            }
            .padding(.horizontal, MeiliMetric.screenH)

            if let err = loadError {
                TeachErrorRetry(message: err) { reload() }
                Spacer()
            } else if let tabs {
                if tabs.count > 1 {
                    tabBar(tabs)
                }
                CourseTabContent(tab: tabs[min(tabIndex, tabs.count - 1)])
                    .id(tabIndex)   // 切 tab 回到顶部
            } else {
                Spacer()
                ProgressView().tint(MeiliColor.clay)
                Spacer()
            }
        }
        .background(MeiliColor.bg)
        .toolbar(.hidden, for: .navigationBar)
        .onAppear { if tabs == nil { reload() } }
        .onDisappear { CourseAudioCenter.shared.stopAll() }
        .task(id: chapterKey) {
            // 学习时长心跳:在本页期间 30s 一发(首发立即),离开页面 task 取消即停
            let repo = TeachRepository()
            while !Task.isCancelled {
                _ = try? await repo.heartbeat(chapter: chapterKey)   // 失败不打扰阅读,下一轮再试
                try? await Task.sleep(nanoseconds: 30_000_000_000)
            }
        }
    }

    private func reload() {
        loadError = nil
        tabs = nil
        Task {
            do {
                tabs = try await CoursewareParser.fetch(TeachRepository.coursewareUrl(chapterKey))
            } catch {
                loadError = (error as? SubsystemError)?.message ?? "课件加载失败"
            }
        }
    }

    private func tabBar(_ tabs: [CourseTab]) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: MeiliMetric.s2) {
                ForEach(Array(tabs.enumerated()), id: \.offset) { i, tab in
                    let selected = i == tabIndex
                    Button { tabIndex = i } label: {
                        Text(tab.title.isEmpty ? "第\(i + 1)节" : tab.title)
                            .font(.sz(12, weight: .bold))
                            .foregroundStyle(selected ? MeiliColor.clayDeep : MeiliColor.ink2)
                            .padding(.horizontal, 12).padding(.vertical, 7)
                            .background(selected ? MeiliColor.clayTint : MeiliColor.surfaceSoft)
                            .clipShape(Capsule())
                            .overlay {
                                Capsule().strokeBorder(selected ? MeiliColor.clay : MeiliColor.lineSoft,
                                                       lineWidth: MeiliMetric.borderField)
                            }
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, MeiliMetric.screenH)
            .padding(.vertical, 8)
        }
    }
}

/// 一个 tab 的正文:导语块 + 卡片(可折叠)。
private struct CourseTabContent: View {
    let tab: CourseTab
    @State private var collapsed: Set<Int> = []

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: MeiliMetric.s2) {
                CourseBlocksView(blocks: tab.intro)
                ForEach(Array(tab.cards.enumerated()), id: \.offset) { i, card in
                    cardView(card, index: i)
                }
            }
            .padding(.horizontal, MeiliMetric.screenH)
            .padding(.bottom, 28)
            .padding(.top, 4)
        }
    }

    private func cardView(_ card: CourseCard, index: Int) -> some View {
        let open = !collapsed.contains(index)
        return MeiliCard(tight: true) {
            Button {
                if open { collapsed.insert(index) } else { collapsed.remove(index) }
            } label: {
                HStack(spacing: MeiliMetric.s2) {
                    if !card.num.isEmpty {
                        Text(card.num).font(.sz(12, weight: .heavy)).foregroundStyle(MeiliColor.clayDeep)
                            .frame(width: 26, height: 26)
                            .background(MeiliColor.claySoft).clipShape(Circle())
                    }
                    Text(card.title.isEmpty ? "第 \(index + 1) 节" : card.title)
                        .font(.sz(13.5, weight: .bold)).foregroundStyle(MeiliColor.ink)
                        .multilineTextAlignment(.leading)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    if !card.tag.isEmpty {
                        StatusPill(text: card.tag, kind: .clay)
                    }
                    MeiliIcon(open ? MeiliIcons.chevDown : MeiliIcons.chevRight, size: 15)
                        .foregroundStyle(MeiliColor.ink4)
                }
            }
            .buttonStyle(.plain)

            if open {
                Spacer().frame(height: MeiliMetric.s2)
                CourseBlocksView(blocks: card.blocks)
            }
        }
    }
}

/// 块渲染:标题/段落/列表/步骤/表格/音频。
struct CourseBlocksView: View {
    let blocks: [CourseBlock]

    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            ForEach(Array(blocks.enumerated()), id: \.offset) { _, block in
                blockView(block)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder private func blockView(_ block: CourseBlock) -> some View {
        switch block {
        case .heading(let text):
            HStack(alignment: .top, spacing: 8) {
                RoundedRectangle(cornerRadius: 2).fill(MeiliColor.clay).frame(width: 3)
                Text(text).font(.sz(14.5, weight: .heavy)).foregroundStyle(MeiliColor.ink)
            }
            .fixedSize(horizontal: false, vertical: true)
            .padding(.top, 5)
        case .paragraph(let text):
            Text(text).font(.sz(13.5)).foregroundStyle(MeiliColor.ink).lineSpacing(5)
        case .bullets(let items):
            VStack(alignment: .leading, spacing: 6) {
                ForEach(Array(items.enumerated()), id: \.offset) { _, it in
                    HStack(alignment: .top, spacing: 9) {
                        Circle().fill(MeiliColor.clay).frame(width: 6, height: 6).padding(.top, 7)
                        Text(it).font(.sz(13.5)).foregroundStyle(MeiliColor.ink2).lineSpacing(4)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
            }
        case .step(let n, let text):
            HStack(alignment: .top, spacing: 9) {
                Text(n).font(.sz(11, weight: .bold)).foregroundStyle(.white)
                    .padding(.horizontal, 7).padding(.vertical, 3)
                    .background(MeiliColor.sage).clipShape(Capsule())
                Text(text).font(.sz(13.5)).foregroundStyle(MeiliColor.ink2).lineSpacing(4)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        case .table(let rows):
            CourseTableView(rows: rows)
        case .audio(let title, let url):
            CourseAudioRow(title: title, url: url)
        }
    }
}

/// 表格:横向可滚,首行做表头(与档案页 MarkdownLite 表格同风格)。
private struct CourseTableView: View {
    let rows: [[String]]

    var body: some View {
        let cols = rows.map(\.count).max() ?? 1
        let cellW: CGFloat = cols <= 2 ? 150 : 128
        ScrollView(.horizontal, showsIndicators: false) {
            VStack(spacing: 0) {
                ForEach(Array(rows.enumerated()), id: \.offset) { i, row in
                    HStack(spacing: 0) {
                        ForEach(0..<cols, id: \.self) { c in
                            Text(c < row.count ? row[c] : "")
                                .font(.sz(12, weight: i == 0 ? .semibold : .regular))
                                .foregroundStyle(i == 0 ? .white : MeiliColor.ink)
                                .lineSpacing(3)
                                .frame(width: cellW, alignment: .leading)
                                .padding(.horizontal, 10).padding(.vertical, 8)
                                .background(i == 0 ? MeiliColor.clay
                                            : (i % 2 == 0 ? MeiliColor.surfaceSoft : MeiliColor.surface))
                                .overlay(alignment: .leading) {
                                    if c > 0 { Rectangle().fill(MeiliColor.line).frame(width: 1) }
                                }
                        }
                    }
                    .overlay(alignment: .top) {
                        if i > 0 { Rectangle().fill(MeiliColor.line).frame(height: 1) }
                    }
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 9, style: .continuous)
                    .strokeBorder(MeiliColor.line, lineWidth: 1)
            }
        }
        .padding(.vertical, 3)
    }
}

// ============================================================
// 原生音频播放器(替代网页 <audio> + 自定义播放条)
// ============================================================

/// 全局音频调度:同屏多条音频,播一条自动暂停其它;离屏全停。
final class CourseAudioCenter {
    static let shared = CourseAudioCenter()
    private var players: [ObjectIdentifier: CourseAudioPlayer] = [:]

    func register(_ p: CourseAudioPlayer) { players[ObjectIdentifier(p)] = p }
    func unregister(_ p: CourseAudioPlayer) { players.removeValue(forKey: ObjectIdentifier(p)) }

    func willPlay(_ p: CourseAudioPlayer) {
        for (_, other) in players where other !== p { other.pause() }
    }

    func stopAll() {
        for (_, p) in players { p.pause() }
    }
}

/// 单条音频的播放状态(AVPlayer 包装:播放/暂停/进度/拖动/倍速)。
@MainActor
final class CourseAudioPlayer: ObservableObject {
    @Published var playing = false
    @Published var current: Double = 0
    @Published var duration: Double = 0
    @Published var rate: Float = 1.0

    private var player: AVPlayer?
    private var timeObserver: Any?
    private let url: URL

    init(url: URL) {
        self.url = url
        CourseAudioCenter.shared.register(self)
    }

    deinit {
        if let timeObserver { player?.removeTimeObserver(timeObserver) }
        // deinit 非 MainActor:直接停内部播放器
        player?.pause()
    }

    func toggle() {
        if playing { pause() } else { play() }
    }

    func play() {
        if player == nil { setUp() }
        CourseAudioCenter.shared.willPlay(self)
        try? AVAudioSession.sharedInstance().setCategory(.playback)
        try? AVAudioSession.sharedInstance().setActive(true)
        player?.rate = rate    // rate 赋值即开播
        playing = true
    }

    nonisolated func pause() {
        Task { @MainActor in
            self.player?.pause()
            self.playing = false
        }
    }

    func seek(to seconds: Double) {
        current = seconds
        player?.seek(to: CMTime(seconds: seconds, preferredTimescale: 600))
    }

    func setRate(_ r: Float) {
        rate = r
        if playing { player?.rate = r }
    }

    private func setUp() {
        let item = AVPlayerItem(url: url)
        let p = AVPlayer(playerItem: item)
        player = p
        timeObserver = p.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: 0.5, preferredTimescale: 600), queue: .main
        ) { [weak self] time in
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.current = time.seconds
                if let d = self.player?.currentItem?.duration.seconds, d.isFinite {
                    self.duration = d
                }
                // 播完复位
                if self.duration > 0, self.current >= self.duration - 0.3, self.playing {
                    self.playing = false
                    self.player?.pause()
                    self.seek(to: 0)
                }
            }
        }
    }
}

/// 音频行:▶/⏸ + 标题 + 进度条 + 时间 + 倍速。
struct CourseAudioRow: View {
    let title: String
    let url: URL
    @StateObject private var player: CourseAudioPlayer
    @State private var dragging = false
    @State private var dragValue: Double = 0

    init(title: String, url: URL) {
        self.title = title
        self.url = url
        _player = StateObject(wrappedValue: CourseAudioPlayer(url: url))
    }

    var body: some View {
        HStack(alignment: .center, spacing: MeiliMetric.s2) {
            Button { player.toggle() } label: {
                ZStack {
                    Circle().fill(MeiliColor.primaryGradient).frame(width: 38, height: 38)
                    if player.playing {
                        // 两条竖线画 ⏸(图标集中没有 pause)
                        HStack(spacing: 4) {
                            Capsule().fill(.white).frame(width: 3, height: 14)
                            Capsule().fill(.white).frame(width: 3, height: 14)
                        }
                    } else {
                        MeiliIcon(MeiliIcons.play, size: 16).foregroundStyle(.white)
                    }
                }
            }
            .buttonStyle(PressScaleButtonStyle(scale: 0.92))

            VStack(alignment: .leading, spacing: 4) {
                Text(title.isEmpty ? "课件音频" : title)
                    .font(.sz(12, weight: .bold)).foregroundStyle(MeiliColor.ink)
                    .lineLimit(2)
                HStack(spacing: 8) {
                    Slider(value: Binding(
                        get: { dragging ? dragValue : min(player.current, max(player.duration, 1)) },
                        set: { v in dragging = true; dragValue = v }
                    ), in: 0...max(player.duration, 1)) { editing in
                        if !editing {
                            player.seek(to: dragValue)
                            dragging = false
                        }
                    }
                    .tint(MeiliColor.clay)
                    Text("\(fmt(dragging ? dragValue : player.current))/\(player.duration > 0 ? fmt(player.duration) : "--:--")")
                        .font(.sz(10)).foregroundStyle(MeiliColor.ink4)
                        .monospacedDigit()
                        .fixedSize()
                    Button {
                        let next: Float = player.rate == 1.0 ? 1.25 : (player.rate == 1.25 ? 0.75 : 1.0)
                        player.setRate(next)
                    } label: {
                        Text(String(format: "%.2gx", player.rate))
                            .font(.sz(10.5, weight: .bold)).foregroundStyle(MeiliColor.clayDeep)
                            .padding(.horizontal, 7).padding(.vertical, 3)
                            .background(MeiliColor.clayTint).clipShape(Capsule())
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .padding(MeiliMetric.s2)
        .background(MeiliColor.surfaceSoft)
        .clipShape(RoundedRectangle(cornerRadius: MeiliRadius.sm, style: .continuous))
        .onDisappear { player.pause(); CourseAudioCenter.shared.unregister(player) }
    }

    private func fmt(_ s: Double) -> String {
        guard s.isFinite, s >= 0 else { return "0:00" }
        return String(format: "%d:%02d", Int(s) / 60, Int(s) % 60)
    }
}

/// 顶栏字号调节小方钮(与 .iconbtn 同款:44 方圆角 surface + 细描边)。
struct FontZoomButton: View {
    let text: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(text).font(.sz(12.5, weight: .bold))
                .foregroundStyle(MeiliColor.ink2)
                .frame(width: MeiliMetric.iconButton, height: MeiliMetric.iconButton)
                .background(MeiliColor.surface)
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .strokeBorder(MeiliColor.line, lineWidth: MeiliMetric.borderThin)
                }
        }
        .buttonStyle(PressScaleButtonStyle(scale: 0.94))
    }
}
