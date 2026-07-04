import SwiftUI

/// 陪伴首页(SPEC §4.2 / redesign 方案A #home)。android 端对应 `ui/home/HomeScreen.kt`。
/// 顶部(问候 + 美丽陪伴 + 陪伴师 chip + 设置齿轮) → 陪伴卡(紧凑横排圆钮 + 来源切换 + 同步badge)
/// → 提醒 tile → 今天的接诊与待整理 tile。
struct HomeView: View {
    let me: Me
    var onOpenReception: () -> Void = {}
    var onOpenReminders: () -> Void = {}
    var onOpenSettings: () -> Void = {}

    @ObservedObject private var rec = RecordingManager.shared
    @ObservedObject private var queue = UploadQueue.shared
    @StateObject private var vm = HomeViewModel()
    @State private var showPenScan = false

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                appHeader
                companionCard
                reminderTile
                receptionTile
            }
            .padding(.horizontal, MeiliMetric.screenH)
            .padding(.bottom, MeiliMetric.bottomNavInset)
        }
        .background(MeiliColor.bg)
        .onAppear { vm.onAppear() }
        // 录音引擎 toast 改由 MainShell 全局显示(同步/删除等提示在任何 tab 都能看到)
        .sheet(isPresented: $showPenScan) { PenScanSheet(rec: rec) }
    }

    // MARK: 顶部

    private var appHeader: some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 0) {
                Text("\(greetingNow())，")
                    .font(.sz(12.5, weight: .semibold)).foregroundStyle(MeiliColor.ink2)
                Text("美丽陪伴")
                    .font(MeiliFont.title).foregroundStyle(MeiliColor.clayDeep)
                    .padding(.top, 3)
                HStack(spacing: 7) {
                    roleChip
                    if let n = me.advisorName?.nilIfBlank {
                        Text(n).font(.sz(12)).foregroundStyle(MeiliColor.ink3).lineLimit(1)
                    }
                }
                .padding(.top, 6)
            }
            Spacer()
            IconSquareButton(icon: MeiliIcons.settings) { onOpenSettings() }
        }
        .padding(.top, 10)
        .padding(.bottom, 18)
    }

    private var roleChip: some View {
        HStack(spacing: 5) {
            MeiliIcon(MeiliIcons.profile, size: 13)
            Text(me.role == "store_manager" ? "店长" : "陪伴师").font(.sz(11, weight: .bold))
        }
        .foregroundStyle(MeiliColor.sageDeep)
        .padding(.horizontal, 11).padding(.vertical, 4)
        .background(MeiliColor.sageTint).clipShape(Capsule())
    }

    // MARK: 陪伴卡

    private var companionCard: some View {
        VStack(alignment: .leading, spacing: 0) {
            // 紧凑横排:左小圆钮 + 右计时/状态
            HStack(spacing: 16) {
                CompactCompanionButton(live: rec.isLive, starting: rec.state == .starting,
                                       enabled: rec.state != .uploading, onTap: { rec.toggle() })
                VStack(alignment: .leading, spacing: 3) {
                    ElapsedText(ticker: rec.ticker)
                    HStack(spacing: 7) {
                        if rec.isLive { Circle().fill(MeiliColor.rose).frame(width: 8, height: 8) }
                        Text(statusText).font(MeiliFont.body).foregroundStyle(MeiliColor.ink2).lineLimit(2)
                    }
                }
                Spacer(minLength: 0)
            }

            Rectangle().fill(MeiliColor.lineSoft).frame(height: 1).padding(.vertical, 15)

            SectionLabel("陪伴设备", icon: MeiliIcons.wifi).padding(.bottom, 9)
            // 点1:陪伴笔在左、手机在右;按账号权限门控(F8:只显示开通的来源,后端 403 只是兜底)
            HStack(spacing: 11) {
                if me.allowPenRec != 0 {
                    sourceCell(.pen, icon: MeiliIcons.pen, title: "陪伴笔", sub: penSub, dot: rec.penConnected)
                }
                if me.allowPhoneRec != 0 {
                    sourceCell(.phone, icon: MeiliIcons.phone, title: "手机麦克风", sub: "手机采集", dot: false)
                }
            }
            .onAppear {
                // 只开一种权限时,把来源钉到可用的那个
                if me.allowPenRec == 0 && rec.source == .pen { rec.setSource(.phone) }
                if me.allowPhoneRec == 0 && rec.source == .phone { rec.setSource(.pen) }
            }

            if rec.state == .uploading || queue.pendingCount > 0 {
                syncBadge.padding(.top, 13)
            }
        }
        .padding(MeiliMetric.cardPad)
        .background(
            RadialGradient(colors: [MeiliColor.clayTint, MeiliColor.surface],
                           center: .topLeading, startRadius: 0, endRadius: 360)
        )
        .clipShape(RoundedRectangle(cornerRadius: MeiliRadius.lg, style: .continuous))
        .overlay { RoundedRectangle(cornerRadius: MeiliRadius.lg, style: .continuous).strokeBorder(MeiliColor.lineSoft, lineWidth: 1) }
        .shadow(color: Color(hex: 0x785A44, alpha: 0.09), radius: 13, y: 8)
        .padding(.bottom, MeiliMetric.cardGap)
    }

    private var penSub: String {
        guard rec.penConnected else { return "未连接" }
        let who = rec.penSuffix.map { "·尾号\($0)" } ?? ""
        if let b = rec.penBattery { return "已连接\(who) · 电量\(b)%" }
        return "已连接\(who)"
    }

    private func sourceCell(_ s: CompanionSource, icon: MeiliGlyph, title: String, sub: String, dot: Bool) -> some View {
        let on = rec.source == s
        return Button {
            rec.setSource(s)
            if s == .pen && !rec.penConnected { showPenScan = true }
        } label: {
            VStack(spacing: 5) {
                MeiliIcon(icon, size: 22).foregroundStyle(MeiliColor.clayDeep)
                Text(title).font(.sz(13, weight: .heavy)).foregroundStyle(MeiliColor.ink).padding(.top, 5)
                HStack(spacing: 5) {
                    if dot {
                        ZStack {
                            Circle().fill(MeiliColor.leafSoft).frame(width: 13, height: 13)
                            Circle().fill(MeiliColor.leaf).frame(width: 7, height: 7)
                        }
                    }
                    Text(sub).font(.sz(11)).foregroundStyle(MeiliColor.ink3).lineLimit(1)
                }
                .padding(.top, 4)
            }
            .frame(maxWidth: .infinity)
            .padding(.horizontal, 10).padding(.vertical, 14)
            .background(on ? MeiliColor.surface : MeiliColor.surfaceSoft)
            .clipShape(RoundedRectangle(cornerRadius: MeiliRadius.sm, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: MeiliRadius.sm, style: .continuous)
                    .strokeBorder(on ? MeiliColor.clay : MeiliColor.line, lineWidth: 1.5)
            }
        }
        .buttonStyle(.plain)
        .disabled(rec.isLive || rec.state == .uploading)
        .opacity(rec.isLive && !on ? 0.5 : 1)
    }

    /// 上传状态 badge(审计 F1):失败时红字可点重试;正常时显示后台上传中。
    private var syncBadge: some View {
        Button { UploadQueue.shared.kick() } label: {
            HStack(spacing: 5) {
                MeiliIcon(queue.failedCount > 0 ? MeiliIcons.warn : MeiliIcons.sync, size: 13)
                    .foregroundStyle(queue.failedCount > 0 ? MeiliColor.roseText : MeiliColor.clayDeep)
                Text(queue.failedCount > 0
                     ? "\(queue.failedCount) 段上传失败 · 点击重试"
                     : "\(max(queue.pendingCount, 1)) 段后台上传中…")
                    .font(.sz(11.5, weight: .bold))
                    .foregroundStyle(queue.failedCount > 0 ? MeiliColor.roseText : MeiliColor.clayDeep)
            }
            .padding(.horizontal, 11).padding(.vertical, 5)
            .background(queue.failedCount > 0 ? MeiliColor.roseSoft : MeiliColor.clayTint)
            .clipShape(Capsule())
        }
        .buttonStyle(.plain)
    }

    // MARK: 提醒 tile

    private var reminderTile: some View {
        Button { onOpenReminders() } label: {
            HStack(spacing: 15) {
                ZStack(alignment: .topTrailing) {
                    MeiliIcon(MeiliIcons.reminder, size: MeiliMetric.iconLg).foregroundStyle(MeiliColor.clayDeep)
                        .frame(width: 50, height: 50).background(MeiliColor.surface)
                        .clipShape(RoundedRectangle(cornerRadius: MeiliRadius.md, style: .continuous))
                        .shadow(color: Color(hex: 0x785A44, alpha: 0.06), radius: 5, y: 2)
                    if vm.reminderCount > 0 {
                        Text(vm.reminderCount > 99 ? "99+" : "\(vm.reminderCount)")
                            .font(.sz(10, weight: .bold)).foregroundStyle(.white)
                            .padding(.horizontal, vm.reminderCount > 9 ? 4 : 0)
                            .frame(minWidth: 16, minHeight: 16)
                            .background(MeiliColor.rose).clipShape(Capsule())
                            .offset(x: 6, y: -6)
                    }
                }
                VStack(alignment: .leading, spacing: 3) {
                    Text("提醒").font(MeiliFont.rowTitle).foregroundStyle(MeiliColor.ink)
                    Text("未绑定 / 报告待看 / 需跟进").font(.sz(11.5)).foregroundStyle(MeiliColor.ink2)
                }
                Spacer()
                MeiliIcon(MeiliIcons.chevRight, size: 22).foregroundStyle(MeiliColor.clay)
            }
            .padding(MeiliMetric.s4)
            .background(MeiliColor.clayTint)
            .clipShape(RoundedRectangle(cornerRadius: MeiliRadius.lg, style: .continuous))
            .overlay { RoundedRectangle(cornerRadius: MeiliRadius.lg, style: .continuous).strokeBorder(MeiliColor.claySoft, lineWidth: 1) }
        }
        .buttonStyle(.plain)
        .padding(.bottom, MeiliMetric.s3)
    }

    // MARK: 今天的接诊与待整理 tile

    private var receptionTile: some View {
        Button { onOpenReception() } label: {
            HStack(spacing: 15) {
                MeiliIcon(MeiliIcons.reception, size: MeiliMetric.iconLg).foregroundStyle(MeiliColor.clayDeep)
                    .frame(width: 50, height: 50).background(MeiliColor.surface)
                    .clipShape(RoundedRectangle(cornerRadius: MeiliRadius.md, style: .continuous))
                    .shadow(color: Color(hex: 0x785A44, alpha: 0.06), radius: 5, y: 2)
                VStack(alignment: .leading, spacing: 3) {
                    Text("今天的接诊与待整理").font(MeiliFont.rowTitle).foregroundStyle(MeiliColor.ink)
                    Text(vm.receptionSubtitle).font(.sz(11.5)).foregroundStyle(MeiliColor.ink2)
                        .lineLimit(2).fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 4)
                MeiliIcon(MeiliIcons.chevRight, size: 22).foregroundStyle(MeiliColor.clay)
            }
            .padding(MeiliMetric.s4)
            .background(MeiliColor.sageTint)
            .clipShape(RoundedRectangle(cornerRadius: MeiliRadius.lg, style: .continuous))
            .overlay { RoundedRectangle(cornerRadius: MeiliRadius.lg, style: .continuous).strokeBorder(MeiliColor.sageSoft, lineWidth: 1) }
        }
        .buttonStyle(.plain)
        .padding(.bottom, MeiliMetric.s3)
    }

    private var statusText: String {
        switch rec.state {
        case .uploading: return "正在保存这次陪伴…"
        case .recording: return "陪伴进行中"
        case .paused: return "陪伴已暂停"
        case .starting: return "正在准备…"
        case .idle: return rec.source == .pen && !rec.penConnected ? "请先连接陪伴笔" : "点一下 · 开启今天的陪伴"
        }
    }

}

/// 紧凑陪伴小圆钮(80)。android 端对应 CompactCompanionButton。
private struct CompactCompanionButton: View {
    let live: Bool
    var starting: Bool = false
    var enabled: Bool = true
    let onTap: () -> Void
    @State private var pulse = false
    private var active: Bool { live || starting }

    var body: some View {
        Button { if enabled { onTap() } } label: {
            ZStack {
                Circle().fill(active ? MeiliColor.companionLiveGradient(diameter: 80) : MeiliColor.companionGradient(diameter: 80))
                Circle().strokeBorder(.white.opacity(0.45), lineWidth: 1.5)
                content
            }
            .frame(width: 80, height: 80)
            .opacity(enabled ? 1 : 0.5)
            .scaleEffect(active && pulse ? 1.05 : 1)
            .shadow(color: (active ? MeiliColor.roseDeep : MeiliColor.clay).opacity(0.3), radius: 12, y: 8)
        }
        .buttonStyle(PressScaleButtonStyle(scale: 0.96))
        .disabled(!enabled)
        .onAppear { sync() }
        .onChange(of: active) { _ in sync() }
    }

    @ViewBuilder private var content: some View {
        if starting {
            ProgressView().progressViewStyle(.circular).tint(.white)
        } else if live {
            RoundedRectangle(cornerRadius: 7, style: .continuous).fill(.white).frame(width: 22, height: 22)
        } else {
            MeiliIcon(MeiliIcons.companion, size: 30, lineWidth: 1.7).foregroundStyle(.white)
        }
    }

    private func sync() {
        if active {
            withAnimation(.easeInOut(duration: 1.3).repeatForever(autoreverses: true)) { pulse = true }
        } else {
            withAnimation(.easeOut(duration: 0.3)) { pulse = false }
        }
    }
}

/// 计时文本(独立观察 ticker;审计 P7:每秒 tick 只重绘这一个 Text)。
struct ElapsedText: View {
    @ObservedObject var ticker: RecordingManager.RecordTicker
    var body: some View {
        Text(ticker.label)
            .font(MeiliFont.serif(32)).monospacedDigit().foregroundStyle(MeiliColor.ink)
    }
}
