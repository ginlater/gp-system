import SwiftUI

/// 扫描选笔(对齐 android `soni/SoniScanActivity`)。点「陪伴笔」未连接时弹出:
/// 列出附近声云陪伴笔,点选连接;真连上(verifiedConnected)后自动关闭。
struct PenScanSheet: View {
    @ObservedObject var rec: RecordingManager
    @Environment(\.dismiss) private var dismiss

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
        .onAppear { rec.startPenScan() }
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
                        ForEach(rec.penDevices) { d in deviceRow(d) }
                    }
                    .padding(.horizontal, MeiliMetric.screenH)
                }
            }
        }
    }

    private func deviceRow(_ d: PenDevice) -> some View {
        Button { rec.connectPen(d) } label: {
            HStack(spacing: 13) {
                MeiliIcon(MeiliIcons.pen, size: 22).foregroundStyle(MeiliColor.clayDeep)
                    .frame(width: 44, height: 44).background(MeiliColor.clayTint)
                    .clipShape(RoundedRectangle(cornerRadius: MeiliRadius.md, style: .continuous))
                VStack(alignment: .leading, spacing: 2) {
                    // 双笔同名:标题带 MAC 尾号,一眼分清哪支是哪支
                    Text("\(d.name) · 尾号\(String(d.address.replacingOccurrences(of: ":", with: "").suffix(2)))")
                        .font(MeiliFont.rowTitle).foregroundStyle(MeiliColor.ink).lineLimit(1)
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
                    .strokeBorder(MeiliColor.line, lineWidth: 1)
            }
        }
        .buttonStyle(.plain)
    }
}
