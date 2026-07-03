import SwiftUI

/// 客户详情(美丽档案)。android 端对应 `ui/customer/CustomerDetailScreen.kt`。
/// 头部 + 累积标签(画像积累) + 客户价值预测 + 陪伴时间线。顾客标签从报告页挪来这里展示。
struct CustomerDetailView: View {
    let customerId: Int
    var onOpenReport: (Int) -> Void = { _ in }

    @Environment(\.dismiss) private var dismiss
    @StateObject private var vm: CustomerDetailViewModel

    init(customerId: Int, onOpenReport: @escaping (Int) -> Void = { _ in }) {
        self.customerId = customerId
        self.onOpenReport = onOpenReport
        _vm = StateObject(wrappedValue: CustomerDetailViewModel(customerId: customerId))
    }

    private var info: CustomerProfileInfo? { vm.profile?.info }
    private var name: String { info?.name?.nilIfBlank ?? "顾客" }

    var body: some View {
        ScrollView {
            VStack(spacing: MeiliMetric.cardGap) {
                MeiliTopBar(title: "美丽档案", subtitle: "TA 的陪伴时间线与画像", onBack: { dismiss() })

                if vm.profileLoading && vm.profile == nil {
                    MeiliCard { loadingRow("正在调取档案…") }
                } else if let e = vm.profileError, vm.profile == nil {
                    MeiliCard { emptyHint(MeiliIcons.warn, "调取失败", e) }
                } else {
                    headerCard
                    tagsCard
                    valueCard
                    timelineCard
                }
            }
            .padding(.horizontal, MeiliMetric.screenH)
            .padding(.bottom, 28)
        }
        .background(MeiliColor.bg)
        .toolbar(.hidden, for: .navigationBar)
        .onAppear { vm.onAppear() }
    }

    // MARK: 头部

    private var headerCard: some View {
        MeiliCard {
            HStack(spacing: 14) {
                MeiliAvatar(name: name, size: 56)
                VStack(alignment: .leading, spacing: 5) {
                    Text(name).font(MeiliFont.titleSm).foregroundStyle(MeiliColor.ink)
                    HStack(spacing: 8) {
                        if let m = info?.memberCard?.nilIfBlank { StatusPill(text: m, kind: .clay) }
                        if let t = info?.phoneTail?.nilIfBlank {
                            Text("尾号\(t)").font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3)
                        }
                    }
                    if let n = vm.profile?.sessionCount {
                        Text("已陪伴 \(n) 次").font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink2)
                    }
                }
                Spacer()
            }
        }
    }

    // MARK: 累积标签(顾客标签落点)

    @ViewBuilder private var tagsCard: some View {
        let tags = (vm.profile?.accumulatedTags ?? []).filter { ($0.tag?.nilIfBlank) != nil }
        if !tags.isEmpty {
            MeiliCard {
                SectionLabel("累积标签 · 画像积累", icon: MeiliIcons.star)
                Spacer().frame(height: 10)
                FlowWrap(spacing: 8) {
                    ForEach(Array(tags.enumerated()), id: \.offset) { _, t in
                        HStack(spacing: 5) {
                            Text(t.tag ?? "").font(.sz(11.5, weight: .bold))
                            if let c = t.count, c > 1 {
                                Text("×\(c)").font(.sz(10.5, weight: .bold)).foregroundStyle(MeiliColor.clayDeep.opacity(0.7))
                            }
                        }
                        .padding(.horizontal, 11).padding(.vertical, 5)
                        .foregroundStyle(MeiliColor.clayDeep)
                        .background(MeiliColor.clayTint).clipShape(Capsule())
                    }
                }
            }
        }
    }

    // MARK: 客户价值预测(渲染对齐 web /customer:彩色维度卡 + markdown)

    @ViewBuilder private var valueCard: some View {
        VStack(alignment: .leading, spacing: 11) {
            HStack(spacing: 6) {
                MeiliIcon(MeiliIcons.spark, size: 16).foregroundStyle(MeiliColor.clay)
                Text("客户价值预测").font(.sz(13, weight: .heavy)).foregroundStyle(MeiliColor.ink)
                Spacer()
                if vm.value?.stale == true {
                    Text("可能已过期").font(.sz(10.5, weight: .bold)).foregroundStyle(MeiliColor.honeyText)
                }
            }
            .padding(.leading, 2)

            if vm.valueGenerating {
                MeiliCard { loadingRow("正在生成（约 20–40 秒）…") }
            } else if vm.hasValueContent, let c = vm.value?.content {
                if let s = c.valueRebuild?.nilIfBlank {
                    ValueDimCard(title: "客户价值评估", icon: MeiliIcons.gem, color: Color(hex: 0xB8860B), content: s)
                }
                if let s = c.battlePlan?.nilIfBlank {
                    ValueDimCard(title: "攻坚作战方案", icon: MeiliIcons.target, color: Color(hex: 0xD9534F), content: s)
                }
                if let s = c.projectPlan?.nilIfBlank {
                    ValueDimCard(title: "项目规划 · 竞品/项目/学习", icon: MeiliIcons.doc, color: Color(hex: 0x4A90D9), content: s)
                }
                if let s = c.bizPlan?.nilIfBlank {
                    ValueDimCard(title: "经营规划 · 下一步/回店", icon: MeiliIcons.trend, color: Color(hex: 0x2E9E6B), content: s)
                }
                if let am = c.advisorMatch, !am.isEmpty { advisorMatchCard(am) }
            } else if vm.valueLoading {
                MeiliCard { loadingRow("正在调取价值预测…") }
            } else {
                MeiliCard {
                    VStack(alignment: .leading, spacing: 10) {
                        Text(vm.valueDoneCount > 0 ? "还没有生成 TA 的价值预测" : "TA 还没有已完成的陪伴分析，暂不能生成")
                            .font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3)
                        if let e = vm.valueError { MeiliBanner(message: e) }
                        if vm.valueDoneCount > 0 {
                            MeiliButton("生成客户价值预测", kind: .soft, size: .small, icon: MeiliIcons.spark) { vm.generateValue() }
                        }
                    }
                }
            }
        }
    }

    private func advisorMatchCard(_ am: [AdvisorMatch]) -> some View {
        let color = Color(hex: 0x7B5EC7)
        return VStack(spacing: 0) {
            ValueCardHeader(title: "顾问匹配度", icon: MeiliIcons.heart, color: color)
            VStack(alignment: .leading, spacing: 14) {
                ForEach(Array(am.enumerated()), id: \.offset) { _, a in
                    HStack(alignment: .top, spacing: 10) {
                        Text(String((a.advisor ?? "?").prefix(1)))
                            .font(MeiliFont.serif(16)).foregroundStyle(.white)
                            .frame(width: 34, height: 34).background(color).clipShape(Circle())
                        VStack(alignment: .leading, spacing: 4) {
                            if let n = a.advisor?.nilIfBlank {
                                Text(n).font(.sz(13.5, weight: .bold)).foregroundStyle(MeiliColor.ink)
                            }
                            if let s = a.assessment?.nilIfBlank { MarkdownLiteView(text: s, accent: color) }
                        }
                    }
                }
            }
            .padding(14).frame(maxWidth: .infinity, alignment: .leading).background(MeiliColor.surface)
        }
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay { RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(MeiliColor.lineSoft, lineWidth: 1) }
    }

    // MARK: 陪伴时间线

    @ViewBuilder private var timelineCard: some View {
        let sessions = vm.profile?.sessions ?? []
        MeiliCard {
            SectionLabel("陪伴时间线", icon: MeiliIcons.album)
            Spacer().frame(height: 6)
            if sessions.isEmpty {
                Text("还没有陪伴记录").font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3).padding(.vertical, 10)
            } else {
                VStack(spacing: 0) {
                    ForEach(Array(sessions.enumerated()), id: \.offset) { idx, s in
                        if idx > 0 { Rectangle().fill(MeiliColor.lineSoft).frame(height: 1) }
                        timelineRow(s)
                    }
                }
            }
        }
    }

    private func timelineRow(_ s: ProfileSession) -> some View {
        Button {
            if s.isDone, let id = s.id { onOpenReport(id) }
        } label: {
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 8) {
                    Text(s.serviceDate?.nilIfBlank ?? "—").font(MeiliFont.rowTitle).foregroundStyle(MeiliColor.ink)
                    let (label, kind) = statusInfo(s.analysisStatus)
                    StatusPill(text: label, kind: kind)
                    Spacer()
                    if s.isDone {
                        HStack(spacing: 3) {
                            Text("看报告").font(.sz(11, weight: .bold)).foregroundStyle(MeiliColor.clay)
                            MeiliIcon(MeiliIcons.chevRight, size: 12).foregroundStyle(MeiliColor.clay)
                        }
                    }
                }
                if let a = s.advisor?.nilIfBlank {
                    Text("陪伴师 \(a)\(s.storeName?.nilIfBlank.map { " · \($0)" } ?? "")")
                        .font(.sz(11)).foregroundStyle(MeiliColor.ink3)
                }
                let chips = (s.projectHits ?? []) + (s.tags ?? [])
                if !chips.isEmpty {
                    FlowWrap(spacing: 6) {
                        ForEach(Array(chips.prefix(6).enumerated()), id: \.offset) { _, t in
                            Text(t).font(.sz(10.5, weight: .bold)).foregroundStyle(MeiliColor.sageDeep)
                                .padding(.horizontal, 8).padding(.vertical, 3)
                                .background(MeiliColor.sageTint).clipShape(Capsule())
                        }
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.vertical, 13)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!s.isDone)
    }

    private func statusInfo(_ s: String?) -> (String, PillKind) {
        switch s {
        case "done": return ("已完成", .ok)
        case "running", "queued": return ("分析中", .run)
        case "failed", "stuck": return ("失败", .danger)
        default: return ("待分析", .warn)
        }
    }

    // MARK: 小件

    private func loadingRow(_ text: String) -> some View {
        HStack(spacing: 10) { ProgressView().tint(MeiliColor.clay); Text(text).font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3) }
            .frame(maxWidth: .infinity).padding(.vertical, 18)
    }
    private func emptyHint(_ icon: MeiliGlyph, _ title: String, _ sub: String?) -> some View {
        VStack(spacing: 0) {
            MeiliIcon(icon, size: 30).foregroundStyle(MeiliColor.ink4)
                .frame(width: 58, height: 58).background(MeiliColor.surfaceSoft)
                .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
            Text(title).font(.sz(14.5, weight: .bold)).foregroundStyle(MeiliColor.ink2).padding(.top, 14)
            if let sub, !sub.isEmpty {
                Text(sub).font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3).multilineTextAlignment(.center).padding(.top, 6)
            }
        }
        .frame(maxWidth: .infinity).padding(.vertical, 30)
    }
}
