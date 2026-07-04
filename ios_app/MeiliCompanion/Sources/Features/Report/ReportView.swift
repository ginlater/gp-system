import SwiftUI

/// 分析报告(SPEC §6 / warm_2 #report)。android 端对应 `ui/report/ReportScreen.kt` + `ReportParts.kt`。
/// 头部(状态在右上) + 原始音频(全程+逐字转写) + 各 PART 折叠卡 + 点评。本轮只读渲染。
struct ReportView: View {
    let sessionId: Int
    @Environment(\.dismiss) private var dismiss
    @StateObject private var vm: ReportViewModel
    // 审计 P3:@State 持有(不观察)——播放 4Hz 心跳只重绘 PlayerBar 子视图,整页不再跟着抖
    @State private var player = AudioPlayer()
    @State private var showScoring = false
    @State private var showTasks = false
    @State private var confirmReanalyze = false
    @State private var playingCase: Int? = nil

    init(sessionId: Int) {
        self.sessionId = sessionId
        _vm = StateObject(wrappedValue: ReportViewModel(sessionId: sessionId))
    }

    var body: some View {
        ScrollView {
            VStack(spacing: MeiliMetric.cardGap) {
                MeiliTopBar(title: vm.headerTitle, subtitle: vm.headerSubtitle, onBack: { dismiss() }) {
                    if vm.detail != nil { headerStatus }     // 点8:任务状态放右上角
                }

                if vm.loading && vm.detail == nil {
                    loadingCard
                } else if let e = vm.error, vm.detail == nil {
                    errorCard(e)
                } else {
                    // PART03(失分根因/root_cause)后端已不生成 → 从原04起整体往前挪一号
                    audioFold
                    part01; part02; part04; part05; part06
                    part07; part08; part09; part11
                    evaluationsSection
                    deleteRequestArea
                }
            }
            .padding(.horizontal, MeiliMetric.screenH)
            .padding(.bottom, 28)
        }
        .background(MeiliColor.bg)
        .toolbar(.hidden, for: .navigationBar)
        .onAppear { vm.onAppear(); if let u = vm.audioURL { player.load(u) } }
        .onChange(of: vm.audioURL) { url in if let u = url { player.load(u) } }
        .onDisappear { player.stop() }
        .sheet(isPresented: $showScoring) {
            if let sc = vm.report?.scoring { ScoringDetailSheet(scoring: sc) }
        }
        .sheet(isPresented: $showTasks) { TaskPanelSheet(vm: vm, confirmReanalyze: $confirmReanalyze) }
        .alert("重新分析这次陪伴？", isPresented: $confirmReanalyze) {
            Button("再想想", role: .cancel) {}
            Button("开始重新分析") { vm.reanalyze() }
        } message: {
            Text("将重跑全部分析任务，预计 2–6 分钟。完成前报告会显示「分析进行中」。")
        }
        .overlay(alignment: .bottom) { reportToast }
    }

    // MARK: 删除申请(F9,session 级:整次陪伴不想要了)

    @State private var confirmDeleteSession = false

    private var deleteRequestArea: some View {
        MeiliButton("申请删除本次陪伴", kind: .ghost, block: true) { confirmDeleteSession = true }
            .alert("申请删除本次陪伴？", isPresented: $confirmDeleteSession) {
                Button("取消", role: .cancel) {}
                Button("提交删除申请", role: .destructive) { vm.requestDeleteSession() }
            } message: {
                Text("提交后等待管理员审批；删除不可恢复。")
            }
    }

    @ViewBuilder private var reportToast: some View {
        if let t = vm.toast {
            Text(t).font(MeiliFont.bodySm).foregroundStyle(.white)
                .padding(.horizontal, 16).padding(.vertical, 11)
                .background(MeiliColor.inkSurface).clipShape(Capsule())
                .padding(.bottom, 24)
                .task(id: t) {
                    try? await Task.sleep(nanoseconds: 2_200_000_000)
                    if !Task.isCancelled { vm.toast = nil }
                }
        }
    }

    // MARK: 头部状态(右上,点开任务面板 F2)

    @ViewBuilder private var headerStatus: some View {
        let (label, kind) = statusPill(vm.displayStatus)
        Button { showTasks = true } label: {
            VStack(alignment: .trailing, spacing: 3) {
                StatusPill(text: label, kind: kind)
                if vm.tasksTotal > 0 {
                    Text("\(vm.tasksDone)/\(vm.tasksTotal) 完成 ›")
                        .font(.sz(10, weight: .bold)).foregroundStyle(MeiliColor.ink3)
                }
            }
        }
        .buttonStyle(.plain)
    }

    // MARK: 原始音频

    private var audioFold: some View {
        Collapsible(title: "原始音频 · 逐字转写", subtitle: "全程可听 · 陪伴师/顾客分行") {
            VStack(alignment: .leading, spacing: 12) {
                // 多段陪伴:分段切换(F3,原来只能听第一段)
                if vm.recordings.count > 1 {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(Array(vm.recordings.enumerated()), id: \.element.id) { i, r in
                                Button {
                                    player.stop()
                                    vm.selectSegment(i)
                                } label: {
                                    VStack(spacing: 1) {
                                        Text("第\(i + 1)段").font(.sz(11.5, weight: .bold))
                                        if let d = r.durationLabel?.nilIfBlank {
                                            Text(d).font(.sz(9.5))
                                        }
                                    }
                                    .foregroundStyle(i == vm.segIndex ? .white : MeiliColor.clayDeep)
                                    .padding(.horizontal, 12).padding(.vertical, 6)
                                    .background(i == vm.segIndex ? MeiliColor.clay : MeiliColor.clayTint)
                                    .clipShape(Capsule())
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                }
                // 说话人>2 警告(F4:不确认会暂缓分析,顾问当场处置)
                if vm.needsSpeakerConfirm {
                    MeiliBanner(message: "这段检测到超过 2 位说话人，确认无误后才会继续分析", kind: .warn)
                    MeiliButton(vm.opBusy ? "提交中…" : "确认说话人无误", kind: .soft, size: .xs,
                                icon: MeiliIcons.check, enabled: !vm.opBusy) { vm.confirmSpeakers() }
                }
                AudioFoldContent(player: player, urlString: vm.audioURL,
                                 loading: vm.audioURLLoading, transcript: vm.currentRecording?.asrTranscript)
            }
        }
    }

    // MARK: PART 01 全维度评估总览

    @ViewBuilder private var part01: some View {
        if let o = vm.report?.overview {
            Collapsible(title: "全维度评估总览", badge: "01", initiallyOpen: true) {
                if let c = o.customerValue { overviewCell("顾客价值", c) }
                if let s = o.salesDiagnosis { overviewCell("成交诊断", s) }
                if let p = o.painSummary {
                    subhead("痛点总览")
                    if let tag = p.tag?.nilIfBlank { tagPill(tag, .clay) }
                    ForEach(Array((p.items ?? []).enumerated()), id: \.offset) { _, it in
                        if let t = it.text?.nilIfBlank { bulletRow(t) }
                    }
                }
                // 点1:质检评分 → 点击查看详情(不直接铺分阶段);去掉「可操作建议」
                if let score = vm.report?.scoring?.displayOverall ?? o.qualityScore?.score {
                    subhead("质检评分")
                    Button { showScoring = true } label: {
                        HStack(spacing: 8) {
                            qualityPill(score)
                            Text("查看详情").font(.sz(12, weight: .bold)).foregroundStyle(MeiliColor.clayDeep)
                            MeiliIcon(MeiliIcons.chevRight, size: 13).foregroundStyle(MeiliColor.clayDeep)
                            Spacer()
                        }
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    // MARK: PART 02

    @ViewBuilder private var part02: some View {
        if let p = vm.report?.persona {
            Collapsible(title: "顾客真实画像重建", badge: "02") {
                leadText(p.lead)
                ForEach(Array((p.signals ?? []).enumerated()), id: \.offset) { _, s in
                    VStack(alignment: .leading, spacing: 4) {
                        if let sig = s.signal?.nilIfBlank {
                            Text("「\(sig)」").font(MeiliFont.body).foregroundStyle(MeiliColor.ink)
                        }
                        if let i = s.interpretation?.nilIfBlank {
                            Text(i).font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink2)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .softBlock()
                }
                if let s = p.summary?.nilIfBlank { noteText(s) }
            }
        }
    }

    // MARK: PART 03

    @ViewBuilder private var part03: some View {
        if let r = vm.report?.rootCause {
            Collapsible(title: "接诊失分根因定位", badge: "03") {
                leadText(r.lead)
                if let h = r.headline?.nilIfBlank {
                    Text(h).font(MeiliFont.rowTitle).foregroundStyle(MeiliColor.clayDeep)
                }
                bulletList("产品维度", r.productDimension)
                bulletList("问题维度", r.problemDimension)
                if let g = r.gapNote?.nilIfBlank { noteText(g) }
            }
        }
    }

    // MARK: PART 04 痛点(点2:多几个颜色)

    @ViewBuilder private var part04: some View {
        if let pps = vm.report?.painPoints, !pps.isEmpty {
            Collapsible(title: "可攻破痛点 · 完整作战方案", badge: "03") {
                ForEach(Array(pps.enumerated()), id: \.offset) { _, pp in
                    VStack(alignment: .leading, spacing: 8) {
                        HStack(spacing: 8) {
                            if let t = pp.title?.nilIfBlank {
                                Text(t).font(MeiliFont.rowTitle).foregroundStyle(MeiliColor.clayDeep)
                            }
                            if let b = pp.badge?.nilIfBlank { tagPill(b, .warn) }
                        }
                        leadText(pp.lead)
                        ForEach(Array((pp.steps ?? []).enumerated()), id: \.offset) { i, st in
                            colorKV(st.label, st.body, i)
                        }
                        ForEach(Array((pp.strategyTable ?? []).enumerated()), id: \.offset) { i, s in
                            colorKV(s.strategy, s.logic, i + 2)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .softBlock()
                }
            }
        }
    }

    // MARK: PART 05 价值收割(点3:只留 step 标题)

    @ViewBuilder private var part05: some View {
        if let h = vm.report?.harvest {
            Collapsible(title: "项目后价值收割 · 黄金窗口", badge: "04") {
                leadText(h.intro)
                VStack(spacing: 7) {
                    ForEach(Array((h.steps ?? []).enumerated()), id: \.offset) { i, s in
                        if let t = s.title?.nilIfBlank {
                            HStack(alignment: .firstTextBaseline, spacing: 8) {
                                Text("step\(i + 1)").font(.sz(11, weight: .heavy)).foregroundStyle(.white)
                                    .padding(.horizontal, 7).padding(.vertical, 3)
                                    .background(MeiliColor.clay).clipShape(Capsule())
                                Text(t).font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                            }
                        }
                    }
                }
            }
        }
    }

    // MARK: PART 06 Case(点4:可播放语音)

    @ViewBuilder private var part06: some View {
        if let cs = vm.report?.cases, !cs.isEmpty {
            Collapsible(title: "关键 Case 复盘", badge: "05") {
                if let s = vm.report?.casesSummary?.nilIfBlank { noteText(s) }
                ForEach(Array(cs.enumerated()), id: \.offset) { idx, c in
                    VStack(alignment: .leading, spacing: 6) {
                        HStack(spacing: 8) {
                            tagPill(caseKindLabel(c.kind), caseKindPill(c.kind))
                            if let t = c.title?.nilIfBlank {
                                Text(t).font(MeiliFont.body).foregroundStyle(MeiliColor.ink).lineLimit(2)
                            }
                            Spacer(minLength: 4)
                            casePlayButton(c, idx)
                        }
                        if let q = c.quote?.nilIfBlank {
                            Text("「\(q)」").font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink2)
                        }
                        colorKV("表层", c.surface, 0)
                        colorKV("深层", c.deep, 1)
                        colorKV("正确做法", c.improve, 3)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .softBlock()
                }
            }
        }
    }

    /// 点4:可播放也可暂停。本 case 正在播 → 显示暂停条,点击暂停;否则跳到时间戳开播。
    @ViewBuilder private func casePlayButton(_ c: CaseReview, _ idx: Int) -> some View {
        if let ts = c.timestampSeconds {
            let isThis = player.playing && playingCase == idx
            Button {
                if isThis {
                    player.toggle()
                } else {
                    player.seek(ts)
                    if !player.playing { player.toggle() }
                    playingCase = idx
                }
            } label: {
                HStack(spacing: 4) {
                    if isThis {
                        HStack(spacing: 2.5) {
                            Capsule().fill(.white).frame(width: 3, height: 11)
                            Capsule().fill(.white).frame(width: 3, height: 11)
                        }
                    } else {
                        MeiliIcon(MeiliIcons.play, size: 11).foregroundStyle(.white)
                    }
                    Text(c.timestampLabel?.nilIfBlank ?? timeLabel(ts))
                        .font(.sz(10.5, weight: .bold)).foregroundStyle(.white)
                }
                .padding(.horizontal, 9).padding(.vertical, 5)
                .background(MeiliColor.primaryGradient).clipShape(Capsule())
            }
            .buttonStyle(PressScaleButtonStyle())
        }
    }

    // MARK: PART 07 训练路径(点5:多几个颜色)

    @ViewBuilder private var part07: some View {
        if let l = vm.report?.logicChain {
            Collapsible(title: "顾问能力训练路径", badge: "06") {
                colorKV("当前逻辑", l.badChain, 4)       // 玫瑰
                if let n = l.badChainNote?.nilIfBlank { noteText(n) }
                colorKV("应有逻辑", l.goodChain, 3)       // 叶绿
                colorKV("缺失环节", l.missingStep, 2)     // 蜜
                // 点3:阶段标题放左边,问题/要练 紧凑 inline
                VStack(spacing: 7) {
                    ForEach(Array((l.training ?? []).enumerated()), id: \.offset) { i, t in
                        HStack(alignment: .top, spacing: 10) {
                            if let s = t.stage?.nilIfBlank { tagPill(s, rotatingPill(i)) }
                            VStack(alignment: .leading, spacing: 3) {
                                inlineKV("问题", t.issue, paletteColor(i))
                                inlineKV("要练", t.skill, paletteColor(i + 1))
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .softBlock()
                    }
                }
            }
        }
    }

    // MARK: PART 08 下一步(点5:回店话术/痛点切入 只留标题)

    @ViewBuilder private var part08: some View {
        if let n = vm.report?.nextSteps {
            Collapsible(title: "下一步动作 · 回店规划", badge: "07") {
                let returns = (n.returnScripts ?? []).compactMap { $0.title?.nilIfBlank }
                if !returns.isEmpty {
                    subhead("回店话术 · 切入角度")
                    FlowWrap(spacing: 8) { ForEach(Array(returns.enumerated()), id: \.offset) { _, t in tagPill(t, .clay) } }
                }
                let pains = (n.painEntryScripts ?? []).compactMap { $0.painName?.nilIfBlank }
                if !pains.isEmpty {
                    subhead("痛点切入")
                    FlowWrap(spacing: 8) { ForEach(Array(pains.enumerated()), id: \.offset) { _, t in tagPill(t, .warn) } }
                }
                if let pp = n.priorityProjects, !pp.isEmpty {
                    subhead("优先项目")
                    ForEach(Array(pp.enumerated()), id: \.offset) { i, x in colorKV(x.name, x.desc, i) }
                }
                let qas = (n.objectionQa ?? []) + (n.medicalObjections ?? [])
                if !qas.isEmpty {
                    subhead("异议应答")
                    ForEach(Array(qas.enumerated()), id: \.offset) { _, x in kvRow(x.question, x.reply) }
                }
            }
        }
    }

    // MARK: PART 09

    @ViewBuilder private var part09: some View {
        if let es = vm.report?.externalSignals {
            let comps = es.flatten()
            if !comps.isEmpty {
                Collapsible(title: "竞品分析 · 售后学习清单", badge: "08") {
                    ForEach(Array(comps.enumerated()), id: \.offset) { _, c in
                        VStack(alignment: .leading, spacing: 4) {
                            HStack(spacing: 8) {
                                if let i = c.item?.nilIfBlank {
                                    Text(i).font(MeiliFont.body).foregroundStyle(MeiliColor.ink)
                                }
                                if let t = c.type?.nilIfBlank { tagPill(t, .neutral) }
                            }
                            kvRow("顾客提到", c.customerQuote)
                            kvRow("可学", c.competitorLearn)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .softBlock()
                    }
                }
            }
        }
    }

    // MARK: PART 11 成交诊断(点7:成交金额文本)

    @ViewBuilder private var part11: some View {
        if let d = vm.report?.dealDiagnosis {
            Collapsible(title: "成交诊断 · 维度判断", badge: "09") {
                // 点1:成交金额 与 已成交/风险 同款胶囊
                FlowWrap(spacing: 8) {
                    tagPill(d.dealResult == true ? "已成交" : "未成交", d.dealResult == true ? .ok : .neutral)
                    tagPill("成交金额：\(d.dealAmount?.nilIfBlank ?? "—")", .clay)
                    if let r = d.riskLevel?.nilIfBlank { tagPill("风险:\(riskLabel(r))", riskPill(r)) }
                }
                if let rt = d.riskText?.nilIfBlank { noteText(rt) }
                if let dims = d.dimensions {
                    dimensionRow("顾客被打动", dims.customerMoved)
                    dimensionRow("认可加大意愿", dims.customerAgreed)
                    dimensionRow("效果满意", dims.effectSatisfied)
                    dimensionRow("价格匹配", dims.priceMatched)
                    dimensionRow("紧迫感建立", dims.urgencyBuilt)
                }
            }
        }
    }

    // MARK: 点评

    @ViewBuilder private var evaluationsSection: some View {
        if !vm.evaluations.isEmpty {
            Collapsible(title: "老板 / 专家点评", initiallyOpen: true) {
                ForEach(vm.evaluations, id: \.rowID) { e in
                    VStack(alignment: .leading, spacing: 4) {
                        Text(e.comment ?? "").font(MeiliFont.body).foregroundStyle(MeiliColor.ink)
                        HStack(spacing: 6) {
                            if let a = e.author?.nilIfBlank {
                                Text(a).font(.sz(11, weight: .bold)).foregroundStyle(MeiliColor.clayDeep)
                            }
                            if let t = e.createdAt?.nilIfBlank {
                                Text(t).font(.sz(10.5)).foregroundStyle(MeiliColor.ink4)
                            }
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .softBlock()
                }
            }
        }
    }

    // MARK: 状态卡

    private var loadingCard: some View {
        MeiliCard {
            HStack(spacing: 10) {
                ProgressView().tint(MeiliColor.clay)
                Text("正在调取分析报告…").font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3)
            }
            .frame(maxWidth: .infinity).padding(.vertical, 20)
        }
    }
    private func errorCard(_ e: String) -> some View {
        MeiliCard {
            VStack(spacing: 8) {
                MeiliIcon(MeiliIcons.warn, size: 30).foregroundStyle(MeiliColor.roseText)
                Text("报告调取失败").font(.sz(14.5, weight: .bold)).foregroundStyle(MeiliColor.ink2)
                Text(e).font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3).multilineTextAlignment(.center)
            }
            .frame(maxWidth: .infinity).padding(.vertical, 28)
        }
    }

    // MARK: 渲染小件

    private func overviewCell(_ label: String, _ c: OverviewCell) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 8) {
                Text(label).font(.sz(11.5, weight: .heavy)).foregroundStyle(MeiliColor.ink2)
                if let tag = c.tag?.nilIfBlank { tagPill(tag, cellKindPill(c.tagKind)) }
            }
            if let n = c.note?.nilIfBlank { Text(n).font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink2) }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func qualityPill(_ s: Double) -> some View {
        Text("\(fmt1(s)) / 10")
            .font(.sz(12.5, weight: .bold)).foregroundStyle(.white)
            .padding(.horizontal, 12).padding(.vertical, 6)
            .background(s >= 8 ? MeiliColor.leaf : (s >= 6 ? MeiliColor.honey : MeiliColor.rose))
            .clipShape(Capsule())
    }

    private func subhead(_ t: String) -> some View {
        Text(t).font(.sz(11.5, weight: .heavy)).tracking(0.5).foregroundStyle(MeiliColor.sageDeep).padding(.top, 2)
    }
    @ViewBuilder private func leadText(_ s: String?) -> some View {
        if let s = s?.nilIfBlank { Text(s).font(MeiliFont.body).foregroundStyle(MeiliColor.ink) }
    }
    @ViewBuilder private func noteText(_ s: String?) -> some View {
        if let s = s?.nilIfBlank {
            Text(s).font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink2)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(10).background(MeiliColor.clayTint.opacity(0.5))
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        }
    }
    /// 带轮转颜色的 K-V(点2/5:多几个颜色)。
    @ViewBuilder private func colorKV(_ k: String?, _ v: String?, _ i: Int) -> some View {
        if let v = v?.nilIfBlank {
            VStack(alignment: .leading, spacing: 2) {
                if let k = k?.nilIfBlank {
                    Text(k).font(.sz(11, weight: .heavy)).foregroundStyle(paletteColor(i))
                }
                Text(v).font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
    /// 紧凑 inline K-V:标签 + 值同一行(点3)。
    @ViewBuilder private func inlineKV(_ k: String, _ v: String?, _ color: Color) -> some View {
        if let v = v?.nilIfBlank {
            (Text("\(k) ").font(.sz(11.5, weight: .heavy)).foregroundColor(color)
             + Text(v).font(.sz(12.5)).foregroundColor(MeiliColor.ink))
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
    @ViewBuilder private func kvRow(_ k: String?, _ v: String?) -> some View {
        if let v = v?.nilIfBlank {
            VStack(alignment: .leading, spacing: 2) {
                if let k = k?.nilIfBlank {
                    Text(k).font(.sz(11, weight: .bold)).foregroundStyle(MeiliColor.ink3)
                }
                Text(v).font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
    @ViewBuilder private func bulletList(_ title: String, _ items: [String]?) -> some View {
        let xs = (items ?? []).compactMap { $0.nilIfBlank }
        if !xs.isEmpty {
            subhead(title)
            ForEach(Array(xs.enumerated()), id: \.offset) { _, t in bulletRow(t) }
        }
    }
    private func bulletRow(_ t: String) -> some View {
        HStack(alignment: .top, spacing: 8) {
            Circle().fill(MeiliColor.clay).frame(width: 5, height: 5).padding(.top, 7)
            Text(t).font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink2)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
    private func tagPill(_ t: String, _ kind: PillKind) -> some View { StatusPill(text: t, kind: kind) }

    @ViewBuilder private func dimensionRow(_ label: String, _ d: DealDimension?) -> some View {
        if let d {
            HStack(alignment: .top, spacing: 8) {
                tagPill(dimLabel(d.status), dimPill(d.status))
                VStack(alignment: .leading, spacing: 2) {
                    Text(label).font(.sz(12.5, weight: .bold)).foregroundStyle(MeiliColor.ink)
                    if let n = d.note?.nilIfBlank { Text(n).font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3) }
                }
            }
        }
    }

    // MARK: 文案/色映射

    private let palette: [Color] = [MeiliColor.clayDeep, MeiliColor.sageDeep, MeiliColor.honeyText, MeiliColor.leafText, MeiliColor.roseText]
    private func paletteColor(_ i: Int) -> Color { palette[((i % palette.count) + palette.count) % palette.count] }
    private func rotatingPill(_ i: Int) -> PillKind {
        [PillKind.clay, .run, .warn, .ok, .danger][((i % 5) + 5) % 5]
    }
    private func fmt1(_ s: Double) -> String { String(format: "%.1f", s) }
    private func timeLabel(_ s: Double) -> String {
        let t = Int(s.isFinite ? s : 0); return String(format: "%d:%02d", t / 60, t % 60)
    }
    private func statusPill(_ s: String?) -> (String, PillKind) {
        switch s {
        case "done": return ("已完成", .ok)
        case "running": return ("分析中", .run)
        case "queued": return ("排队中", .run)
        case "failed", "stuck", "cancelled": return ("分析失败", .danger)
        default: return ("未分析", .neutral)
        }
    }
    private func cellKindPill(_ k: String?) -> PillKind {
        switch k { case "bad", "warn": return .danger; case "level": return .clay; default: return .neutral }
    }
    private func caseKindLabel(_ k: String?) -> String {
        switch k { case "good": return "做得好"; case "bad": return "失分"; case "miss": return "错失"; default: return "Case" }
    }
    private func caseKindPill(_ k: String?) -> PillKind {
        switch k { case "good": return .ok; case "bad": return .danger; case "miss": return .warn; default: return .neutral }
    }
    private func riskLabel(_ r: String) -> String {
        switch r { case "high": return "高"; case "medium": return "中"; case "low": return "低"; default: return r }
    }
    private func riskPill(_ r: String) -> PillKind {
        switch r { case "high": return .danger; case "medium": return .warn; default: return .ok }
    }
    private func dimLabel(_ s: String?) -> String {
        switch s { case "ok": return "达成"; case "partial": return "部分"; case "missing": return "缺失"; default: return "—" }
    }
    private func dimPill(_ s: String?) -> PillKind {
        switch s { case "ok": return .ok; case "partial": return .warn; case "missing": return .danger; default: return .neutral }
    }
}

/// 软底小块修饰(报告内重复用)。
private extension View {
    func softBlock() -> some View {
        self.padding(12).background(MeiliColor.surfaceSoft)
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}

/// 质检评分明细 sheet(点1:点击查看)。综合分 + 做得好/待改进 + 各阶段子项。
private struct ScoringDetailSheet: View {
    let scoring: Scoring
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HStack {
                    Text("质检评分明细").font(MeiliFont.sheetH3).foregroundStyle(MeiliColor.ink)
                    Spacer()
                    if let o = scoring.displayOverall {
                        Text("\(String(format: "%.1f", o)) / 10")
                            .font(MeiliFont.serif(22)).foregroundStyle(MeiliColor.clay)
                    }
                }
                highlights("做得好", scoring.goodHighlights, MeiliColor.leafText, MeiliColor.leafSoft)
                highlights("待改进", scoring.badHighlights, MeiliColor.roseText, MeiliColor.roseSoft)

                ForEach(Array((scoring.stages ?? []).enumerated()), id: \.offset) { _, st in
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Text(st.name ?? "—").font(MeiliFont.rowTitle).foregroundStyle(MeiliColor.ink)
                            Spacer()
                            if let s = st.computedScore {
                                Text(String(format: "%.1f", s)).font(MeiliFont.serif(17)).foregroundStyle(MeiliColor.clayDeep)
                            }
                        }
                        ForEach(Array((st.sub ?? []).enumerated()), id: \.offset) { _, sub in
                            VStack(alignment: .leading, spacing: 2) {
                                HStack {
                                    Text(sub.name ?? "—").font(.sz(12.5, weight: .bold)).foregroundStyle(MeiliColor.ink2)
                                    Spacer()
                                    if let s = sub.score {
                                        Text(String(format: "%.1f", s)).font(.sz(12.5, weight: .bold)).foregroundStyle(MeiliColor.sageDeep)
                                    }
                                }
                                if let d = sub.detail?.nilIfBlank {
                                    Text(d).font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3)
                                }
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(12).background(MeiliColor.surfaceSoft)
                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                }
            }
            .padding(MeiliMetric.cardPad)
        }
        .background(MeiliColor.bg)
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
    }

    @ViewBuilder private func highlights(_ title: String, _ items: [String]?, _ fg: Color, _ bg: Color) -> some View {
        let xs = (items ?? []).compactMap { $0.nilIfBlank }
        if !xs.isEmpty {
            VStack(alignment: .leading, spacing: 6) {
                Text(title).font(.sz(11.5, weight: .heavy)).foregroundStyle(fg)
                ForEach(Array(xs.enumerated()), id: \.offset) { _, t in
                    Text("· \(t)").font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink2)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .padding(12).background(bg).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
    }
}

// MARK: - 任务执行状态面板(F2,android 对应 ReportScreen.TaskSheet)

struct TaskPanelSheet: View {
    @ObservedObject var vm: ReportViewModel
    @Binding var confirmReanalyze: Bool
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("任务执行状态").font(MeiliFont.cardTitle).foregroundStyle(MeiliColor.ink)
                Spacer()
                Text("\(vm.tasksDone) / \(vm.tasksTotal) 完成")
                    .font(.sz(12, weight: .bold)).foregroundStyle(MeiliColor.ink3)
            }
            ScrollView {
                VStack(spacing: 0) {
                    ForEach(vm.tasks) { t in
                        HStack(spacing: 10) {
                            Circle().fill(taskColor(t)).frame(width: 8, height: 8)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(t.name ?? "任务 \(t.id)").font(MeiliFont.body).foregroundStyle(MeiliColor.ink).lineLimit(1)
                                HStack(spacing: 6) {
                                    Text(taskStatusText(t)).font(.sz(11)).foregroundStyle(taskColor(t))
                                    if let e = t.error?.nilIfBlank {
                                        Text(e).font(.sz(10.5)).foregroundStyle(MeiliColor.roseText).lineLimit(1)
                                    }
                                }
                            }
                            Spacer()
                            if !t.isRunning {
                                MeiliButton("重跑", kind: .ghost, size: .xs, enabled: !vm.opBusy) { vm.rerunTask(t.id) }
                            }
                        }
                        .padding(.vertical, 10)
                        Rectangle().fill(MeiliColor.lineSoft).frame(height: 1)
                    }
                }
            }
            MeiliButton(vm.opBusy ? "提交中…" : "补齐所有缺失任务", kind: .soft, block: true, enabled: !vm.opBusy) {
                vm.fillMissing()
            }
            MeiliButton("重新分析（全部重跑）", kind: .ghost, block: true, enabled: !vm.opBusy) {
                dismiss()
                confirmReanalyze = true
            }
        }
        .padding(18)
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .background(MeiliColor.bg)
        // 提示要显示在面板这一层(报告页底部的 toast 会被 sheet 挡住看不见)
        .overlay(alignment: .bottom) {
            if let t = vm.toast {
                Text(t).font(MeiliFont.bodySm).foregroundStyle(.white)
                    .padding(.horizontal, 16).padding(.vertical, 11)
                    .background(MeiliColor.inkSurface).clipShape(Capsule())
                    .padding(.bottom, 18)
                    .task(id: t) {
                        try? await Task.sleep(nanoseconds: 2_200_000_000)
                        if !Task.isCancelled { vm.toast = nil }
                    }
            }
        }
    }

    private func taskColor(_ t: AnalysisTask) -> Color {
        if t.isDone { return MeiliColor.leafText }
        if t.isRunning { return MeiliColor.sageDeep }
        if (t.status ?? "") == "failed" { return MeiliColor.roseText }
        return MeiliColor.ink4
    }
    private func taskStatusText(_ t: AnalysisTask) -> String {
        if t.isDone { return "已完成" }
        if t.isRunning { return "进行中" }
        if (t.status ?? "") == "failed" { return "失败" }
        return "待跑"
    }
}
