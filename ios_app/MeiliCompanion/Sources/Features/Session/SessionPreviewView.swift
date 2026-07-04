import SwiftUI

/// 会话预览 + 开始分析(warm_2 #m-pkg「接诊包」)。android 端对应 `ui/session/SessionPreviewScreen.kt`。
/// 头部 + 状态/进度 + 本次将分析的陪伴(可试听/移除/确认说话人) + 可加入的陪伴 + 开始/取消分析。
struct SessionPreviewView: View {
    let customerId: Int
    let date: String
    var onOpenReport: (Int) -> Void = { _ in }

    @Environment(\.dismiss) private var dismiss
    @StateObject private var vm: SessionPreviewViewModel
    // 审计 P8:@State 持有不观察,播放心跳只重绘试听小钮/进度条
    @State private var player = AudioPlayer()
    @State private var playingRid: Int?
    @State private var confirmStart = false

    init(customerId: Int, date: String, onOpenReport: @escaping (Int) -> Void = { _ in }) {
        self.customerId = customerId
        self.date = date
        self.onOpenReport = onOpenReport
        _vm = StateObject(wrappedValue: SessionPreviewViewModel(customerId: customerId, date: date))
    }

    var body: some View {
        ScrollView {
            VStack(spacing: MeiliMetric.cardGap) {
                MeiliTopBar(title: vm.customerName.nilIfBlank ?? "接诊包",
                            subtitle: "陪伴预览 · \(vm.serviceDate.nilIfBlank ?? date)",
                            onBack: { dismiss() })

                if vm.loading && vm.bound.isEmpty && vm.unbound.isEmpty {
                    MeiliCard { loadingRow("正在调取接诊包…") }
                } else if let e = vm.loadError {
                    MeiliCard { emptyHint(MeiliIcons.warn, "调取失败", e) }
                } else {
                    statusCard
                    boundSection
                    unboundSection
                    actionArea
                }
            }
            .padding(.horizontal, MeiliMetric.screenH)
            .padding(.bottom, 28)
        }
        .background(MeiliColor.bg)
        .toolbar(.hidden, for: .navigationBar)
        .onAppear { vm.onAppear() }
        .onDisappear { player.stop() }
        .onChange(of: vm.navigateToReport) { sid in
            if let sid { onOpenReport(sid); vm.navigateToReport = nil }
        }
        .sheet(isPresented: Binding(get: { vm.rebindFor != nil },
                                    set: { if !$0 { vm.closeRebind() } })) {
            RebindSheet(vm: vm)
        }
        .overlay(alignment: .bottom) { toastBar }
    }

    // MARK: 状态 / 进度

    private var statusCard: some View {
        let (label, kind) = phaseInfo
        return MeiliCard {
            HStack {
                StatusPill(text: label, kind: kind)
                Spacer()
                if vm.phase == .running || vm.phase == .done {
                    Text(vm.progress?.progressText?.nilIfBlank ?? "\(vm.progressDone)/\(vm.progressTotal) 任务")
                        .font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3)
                }
            }
            if vm.phase == .running || vm.phase == .done {
                Spacer().frame(height: 10)
                GeometryReader { geo in
                    ZStack(alignment: .leading) {
                        Capsule().fill(MeiliColor.surfaceSoft).frame(height: 7)
                        Capsule().fill(MeiliColor.trackGradient)
                            .frame(width: geo.size.width * progressFraction, height: 7)
                    }
                }
                .frame(height: 7)
            }
        }
    }

    private var progressFraction: CGFloat {
        guard vm.progressTotal > 0 else { return 0 }
        return min(1, CGFloat(vm.progressDone) / CGFloat(vm.progressTotal))
    }

    // MARK: 本次将分析

    @ViewBuilder private var boundSection: some View {
        if !vm.bound.isEmpty {
            MeiliCard {
                SectionLabel("本次将分析的陪伴（\(vm.bound.count)）", icon: MeiliIcons.link)
                Spacer().frame(height: 6)
                VStack(spacing: 0) {
                    ForEach(Array(vm.bound.enumerated()), id: \.offset) { idx, rec in
                        if idx > 0 { Rectangle().fill(MeiliColor.lineSoft).frame(height: 1) }
                        recRow(rec, bound: true)
                    }
                }
            }
        }
    }

    @ViewBuilder private var unboundSection: some View {
        if !vm.unbound.isEmpty {
            MeiliCard {
                SectionLabel("可加入的陪伴（本人当日未绑定）", icon: MeiliIcons.add)
                Spacer().frame(height: 6)
                VStack(spacing: 0) {
                    ForEach(Array(vm.unbound.enumerated()), id: \.offset) { idx, rec in
                        if idx > 0 { Rectangle().fill(MeiliColor.lineSoft).frame(height: 1) }
                        recRow(rec, bound: false)
                    }
                }
            }
        }
    }

    private func recRow(_ rec: PreviewRecording, bound: Bool) -> some View {
        let warn = rec.asrSpeakerWarning == 1 && (rec.speakerConfirmed ?? 0) == 0
        return VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 10) {
                playButton(rec)
                VStack(alignment: .leading, spacing: 2) {
                    Text(rec.recordedAt?.nilIfBlank ?? "陪伴片段").font(MeiliFont.body).foregroundStyle(MeiliColor.ink).lineLimit(1)
                    HStack(spacing: 6) {
                        if let d = rec.durationLabel?.nilIfBlank {
                            Text(d).font(.sz(11)).foregroundStyle(MeiliColor.ink3)
                        }
                        if let sc = rec.asrSpeakerCount {
                            Text("\(sc) 人").font(.sz(11)).foregroundStyle(MeiliColor.ink3)
                        }
                        if rec.asrStatus != "done" {
                            Text("识别中").font(.sz(11)).foregroundStyle(MeiliColor.honeyText)
                        }
                    }
                }
                Spacer(minLength: 6)
                if bound {
                    if vm.editable {
                        // 换绑/退回都免理由免审批(2026-07-04 用户拍板)
                        MeiliButton("换绑", kind: .ghost, size: .xs, enabled: vm.rowOpId == nil) { vm.openRebind(rec) }
                        MeiliButton("退回", kind: .ghost, size: .xs, enabled: vm.rowOpId == nil) { vm.removeFromPackage(rec.id) }
                    }
                } else {
                    MeiliButton("加入", size: .xs, icon: MeiliIcons.add, enabled: vm.rowOpId == nil) { vm.addToPackage(rec.id) }
                }
            }
            // 正在试听的这条:显示可拖动进度条,方便跳到后面听
            if playingRid == rec.id {
                AuditionScrubber(player: player)
            }
            if warn {
                HStack(spacing: 8) {
                    MeiliBanner(message: "这段提示说话人异常，确认无误后可开始分析", kind: .warn)
                }
                MeiliButton("确认说话人", kind: .soft, size: .xs, icon: MeiliIcons.check, enabled: vm.rowOpId == nil) { vm.confirmSpeakers(rec.id) }
            }
        }
        .padding(.vertical, 12)
    }

    private func playButton(_ rec: PreviewRecording) -> some View {
        AuditionPlayButton(player: player, isCurrent: playingRid == rec.id) { togglePlay(rec) }
    }

    private func togglePlay(_ rec: PreviewRecording) {
        if playingRid == rec.id && player.playing { player.toggle(); return }
        guard let url = rec.audioUrl?.nilIfBlank else { vm.toast = "暂无可试听的音频"; return }
        player.load(url)
        if !player.playing { player.toggle() }
        playingRid = rec.id
    }

    // MARK: 操作区

    @ViewBuilder private var actionArea: some View {
        VStack(spacing: 10) {
            if vm.phase == .outdated {
                // 对齐 android:解释为什么建议重跑(不是自动分析,是老报告作废了)
                MeiliBanner(message: "陪伴片段在上次分析后有变更（新增/换绑/退回等），原报告已作废，请重新分析以更新。", kind: .warn)
            }
            if vm.phase == .done {
                MeiliButton("查看报告", kind: .honey, icon: MeiliIcons.doc, block: true) { onOpenReport(vm.sessionId) }
            }
            if vm.canCancel {
                MeiliButton(vm.submitting ? "处理中…" : "取消分析", kind: .ghost, block: true, enabled: !vm.submitting) { vm.cancelAnalysis() }
            }
            if vm.canStart {
                let title = vm.phase == .outdated || vm.phase == .failed ? "重新分析" : "确认并开始分析"
                // 对齐 android:分析要花几分钟算力,必须二次确认,防误触
                MeiliButton(vm.submitting ? "提交中…" : title, icon: MeiliIcons.spark, block: true, enabled: !vm.submitting) { confirmStart = true }
                if vm.speakerUnconfirmed.isEmpty, vm.bound.contains(where: { $0.asrStatus != "done" }) {
                    Text("部分片段还在识别中，开始后会等识别完成自动分析").font(.sz(11)).foregroundStyle(MeiliColor.ink3)
                }
            }
        }
        .alert("确认开始分析？", isPresented: $confirmStart) {
            Button("再想想", role: .cancel) {}
            Button("确认并开始分析") { vm.startAnalysis() }
        } message: {
            // 李雪雪案帮凶:原来不显示是谁的包,测试/多开时容易替别的顾客确认了分析
            Text("将分析「\(vm.customerName)」\(vm.serviceDate.isEmpty ? "" : " \(vm.serviceDate) ")的 \(vm.bound.count) 段陪伴，分析会锁定该接诊包直到完成。")
        }
    }

    // MARK: 小件

    private var phaseInfo: (String, PillKind) {
        switch vm.phase {
        case .done: return ("已完成分析", .ok)
        case .running: return ("分析进行中", .run)
        case .failed: return ("上次分析失败", .danger)
        case .outdated: return ("录音有变更 · 建议重跑", .warn)
        case .idle: return ("待开始分析", .neutral)
        }
    }

    @ViewBuilder private var toastBar: some View {
        if let t = vm.toast {
            Text(t).font(MeiliFont.bodySm).foregroundStyle(.white)
                .padding(.horizontal, 16).padding(.vertical, 11)
                .background(MeiliColor.inkSurface).clipShape(Capsule())
                .padding(.bottom, 24)
                .task { try? await Task.sleep(nanoseconds: 2_000_000_000); vm.toast = nil }
        }
    }
    private func loadingRow(_ text: String) -> some View {
        HStack(spacing: 10) { ProgressView().tint(MeiliColor.clay); Text(text).font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3) }
            .frame(maxWidth: .infinity).padding(.vertical, 20)
    }
    private func emptyHint(_ icon: MeiliGlyph, _ title: String, _ sub: String?) -> some View {
        VStack(spacing: 8) {
            MeiliIcon(icon, size: 30).foregroundStyle(MeiliColor.roseText)
            Text(title).font(.sz(14.5, weight: .bold)).foregroundStyle(MeiliColor.ink2)
            if let sub { Text(sub).font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3).multilineTextAlignment(.center) }
        }
        .frame(maxWidth: .infinity).padding(.vertical, 28)
    }
}

// MARK: - 换绑弹层(android 对应 SessionPreviewScreen.RebindSheet;免理由免审批)

struct RebindSheet: View {
    @ObservedObject var vm: SessionPreviewViewModel
    @State private var confirmTarget: Customer?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("换绑这段陪伴").font(MeiliFont.cardTitle).foregroundStyle(MeiliColor.ink)
            Text("只移动这一段（该顾客其它陪伴不受影响），换绑后新旧接诊包会重新生成，直接生效。")
                .font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3)
            if let rec = vm.rebindFor {
                Text([rec.recordedAt?.nilIfBlank, rec.durationLabel?.nilIfBlank].compactMap { $0 }.joined(separator: " · "))
                    .font(.sz(11)).foregroundStyle(MeiliColor.ink3)
            }

            HStack(spacing: 8) {
                MeiliIcon(MeiliIcons.search, size: 16).foregroundStyle(MeiliColor.ink3)
                TextField("搜索顾客姓名 / 会员号 / 手机尾号", text: Binding(
                    get: { vm.rebindQuery },
                    set: { vm.onRebindQueryChange($0) }
                ))
                .font(MeiliFont.body)
                .autocorrectionDisabled()
            }
            .padding(.horizontal, 12).padding(.vertical, 10)
            .background(MeiliColor.surfaceSoft)
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))

            if vm.rebindSearching && vm.rebindPicks.isEmpty {
                HStack(spacing: 8) {
                    ProgressView().tint(MeiliColor.clay)
                    Text("正在搜索…").font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3)
                }
                .frame(maxWidth: .infinity).padding(.vertical, 24)
            } else if vm.rebindPicks.isEmpty {
                Text("没有匹配的顾客，换个关键词试试")
                    .font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3)
                    .frame(maxWidth: .infinity).padding(.vertical, 24)
            } else {
                ScrollView {
                    VStack(spacing: 0) {
                        ForEach(Array(vm.rebindPicks.enumerated()), id: \.offset) { _, c in
                            HStack(spacing: 10) {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(c.name ?? "未命名顾客").font(MeiliFont.body).foregroundStyle(MeiliColor.ink)
                                    HStack(spacing: 6) {
                                        if let t = c.phoneTail?.nilIfBlank {
                                            Text("尾号\(t)").font(.sz(11)).foregroundStyle(MeiliColor.ink3)
                                        }
                                        if let m = c.memberCard?.nilIfBlank {
                                            Text(m).font(.sz(11)).foregroundStyle(MeiliColor.ink3)
                                        }
                                        if c.inDay == true { StatusPill(text: "当日", kind: .ok) }
                                    }
                                }
                                Spacer()
                                MeiliButton("换到这里", kind: .soft, size: .xs, enabled: !vm.submitting) {
                                    confirmTarget = c
                                }
                            }
                            .padding(.vertical, 10)
                            Rectangle().fill(MeiliColor.lineSoft).frame(height: 1)
                        }
                    }
                }
            }
            Spacer(minLength: 0)
        }
        .padding(18)
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .background(MeiliColor.bg)
        .alert("换绑到「\(confirmTarget?.name ?? "")」", isPresented: Binding(
            get: { confirmTarget != nil },
            set: { if !$0 { confirmTarget = nil } })) {
            Button("再想想", role: .cancel) {}
            Button("确认换绑（仅这段）") {
                if let t = confirmTarget { vm.submitRebind(t) }
            }
        } message: {
            Text("只移动这一段陪伴（该顾客其他陪伴不受影响），若原报告已分析将作废。直接生效，确认吗？")
        }
    }
}
