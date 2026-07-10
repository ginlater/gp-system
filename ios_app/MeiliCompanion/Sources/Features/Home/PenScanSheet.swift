import SwiftUI

/// 扫描选笔(对齐 android `soni/SoniScanActivity`)。点「陪伴笔」未连接时弹出:
/// 列出附近声云陪伴笔,点选连接;真连上(verifiedConnected)后自动关闭。
/// ★对齐安卓2.1.5:开页拉「笔归属表」——我的笔标「我的笔」置顶,同事的笔置灰禁点(防连错;
///   误连仍有服务端 report-sn 裁决兜底,拉不到归属表时静默降级为旧行为)。
struct PenScanSheet: View {
    @ObservedObject var rec: RecordingManager
    @Environment(\.dismiss) private var dismiss
    @State private var myPenSn: String? = nil
    @State private var owners: [String: String] = [:]   // SN(大写) → 归属人

    var body: some View {
        ZStack {
            MeiliColor.bg.ignoresSafeArea()
            VStack(spacing: 0) {
                header
                Rectangle().fill(MeiliColor.line).frame(height: 1)
                content
                Spacer(minLength: 0)
            }
        }
        .presentationDetents([.medium, .large])
        .onAppear {
            rec.startPenScan()
            Task { await loadBindings() }
        }
        .onDisappear { rec.stopPenScan() }
        .onChange(of: rec.penConnected) { c in if c { dismiss() } }
    }

    private var header: some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 3) {
                Text("连接陪伴笔").font(MeiliFont.titleSm).foregroundStyle(MeiliColor.ink)
                Text("打开录音笔并靠近手机").font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3)
            }
            Spacer()
            Button { dismiss() } label: {
                Text("完成").font(.sz(14, weight: .bold)).foregroundStyle(MeiliColor.clayDeep)
            }
        }
        .padding(.horizontal, MeiliMetric.screenH).padding(.top, 20).padding(.bottom, 14)
    }

    @ViewBuilder private var content: some View {
        if rec.penConnecting {
            VStack(spacing: 14) {
                ProgressView().scaleEffect(1.2)
                Text("正在连接…").font(MeiliFont.body).foregroundStyle(MeiliColor.ink2)
                Text("连上后将自动开始").font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3)
            }
            .frame(maxWidth: .infinity).padding(.top, 50)
        } else {
            VStack(spacing: 0) {
                HStack(spacing: 7) {
                    if rec.penScanning { ProgressView().scaleEffect(0.8) }
                    Text(rec.penDevices.isEmpty ? "正在搜索附近的陪伴笔…" : "发现 \(rec.penDevices.count) 支 · 点选连接")
                        .font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3)
                    Spacer()
                }
                .padding(.horizontal, MeiliMetric.screenH).padding(.vertical, 14)

                ScrollView {
                    VStack(spacing: 10) {
                        ForEach(sortedDevices) { d in deviceRow(d) }
                    }
                    .padding(.horizontal, MeiliMetric.screenH)
                }
            }
        }
    }

    /// 我的笔永远排第一,其余保持发现顺序。
    private var sortedDevices: [PenDevice] {
        rec.penDevices.filter { isMine($0) } + rec.penDevices.filter { !isMine($0) }
    }

    /// 声云笔 SN 由 MAC 派生:s + 去冒号 MAC(大写)。
    private func sn(of d: PenDevice) -> String {
        "s" + d.address.replacingOccurrences(of: ":", with: "").uppercased()
    }

    private func isMine(_ d: PenDevice) -> Bool {
        guard let mine = myPenSn, !mine.isEmpty else { return false }
        return mine.caseInsensitiveCompare(sn(of: d)) == .orderedSame
    }

    /// 归属人(不是我的且被别人绑定时返回名字;我的/无主返回 nil)。
    private func owner(of d: PenDevice) -> String? {
        if isMine(d) { return nil }
        let o = owners[sn(of: d)]
        return (o?.isEmpty ?? true) ? nil : o
    }

    private func loadBindings() async {
        guard let b = try? await ConsultantRepo.penBindings() else { return }
        var m: [String: String] = [:]
        (b.assignments ?? [:]).forEach { m[$0.key.uppercased()] = $0.value }
        await MainActor.run {
            myPenSn = b.mine
            owners = m
        }
    }

    private func deviceRow(_ d: PenDevice) -> some View {
        let mine = isMine(d)
        let who = owner(of: d)
        let tail = String(d.address.replacingOccurrences(of: ":", with: "").suffix(2))
        let suffix = mine ? "（我的笔）" : (who.map { "（已分配给\($0)）" } ?? "")
        return Button {
            if who == nil { rec.connectPen(d) }   // 他人的笔禁点(视觉+行为双保险)
        } label: {
            HStack(spacing: 13) {
                MeiliIcon(MeiliIcons.pen, size: 22).foregroundStyle(MeiliColor.clayDeep)
                    .frame(width: 44, height: 44).background(MeiliColor.clayTint)
                    .clipShape(RoundedRectangle(cornerRadius: MeiliRadius.md, style: .continuous))
                VStack(alignment: .leading, spacing: 2) {
                    // 双笔同名:标题带 MAC 尾号,一眼分清哪支是哪支
                    Text("\(d.name) · 尾号\(tail)\(suffix)")
                        .font(MeiliFont.rowTitle)
                        .foregroundStyle(mine ? MeiliColor.clayDeep : MeiliColor.ink)
                        .lineLimit(1)
                    Text(d.address).font(.sz(11)).foregroundStyle(MeiliColor.ink3).lineLimit(1)
                }
                Spacer()
                MeiliIcon(MeiliIcons.chevRight, size: 20).foregroundStyle(MeiliColor.clay)
            }
            .padding(13)
            .background(MeiliColor.surface)
            .clipShape(RoundedRectangle(cornerRadius: MeiliRadius.md, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: MeiliRadius.md, style: .continuous)
                    .strokeBorder(mine ? MeiliColor.clay : MeiliColor.line, lineWidth: mine ? 1.5 : 1)
            }
            .opacity(who != nil ? 0.45 : 1)
        }
        .buttonStyle(.plain)
        .disabled(who != nil)
    }
}
