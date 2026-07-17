import SwiftUI

/// 销售话术(原生,2026-07-16 起替代网页壳)。
/// 网页版 beauty_consultant.html 的原生复刻:表单填写/自由描述两模式 →
/// SSE 流式生成 → 顾问思考(可折叠)+ 话术正文 → 复制;历史记录 sheet。
struct ChatView: View {
    let onSwitchSystem: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @StateObject private var vm = ChatViewModel()
    @State private var switcherOpen = false

    var body: some View {
        ZStack(alignment: .bottom) {
            MeiliColor.bg.ignoresSafeArea()
            VStack(spacing: 0) {
                MeiliTopBar(title: "销售话术",
                            subtitle: vm.quotaText.isEmpty ? "填写顾客信息 · 一键生成话术" : vm.quotaText,
                            onBack: { dismiss() }) {
                    TopBarIconButton(icon: MeiliIcons.workspace) { switcherOpen = true }
                    TopBarIconButton(icon: MeiliIcons.clock) { vm.openHistory() }
                }
                .padding(.horizontal, MeiliMetric.screenH)

                if let err = vm.loginError {
                    TeachErrorRetry(message: err) { vm.loadQuota() }
                    Spacer()
                } else {
                    content
                }
            }

            if let t = vm.toast {
                Text(t).font(MeiliFont.bodySm).foregroundStyle(.white)
                    .padding(.horizontal, 16).padding(.vertical, 11)
                    .background(MeiliColor.inkSurface).clipShape(Capsule())
                    .padding(.bottom, 30)
                    .task(id: t) {
                        try? await Task.sleep(nanoseconds: 2_400_000_000)
                        if !Task.isCancelled { vm.toast = nil }
                    }
            }
        }
        .toolbar(.hidden, for: .navigationBar)
        .onAppear { vm.initLoad() }
        .sheet(isPresented: $switcherOpen) {
            SystemSwitcherSheet(currentKey: "chat") { key in
                switcherOpen = false
                onSwitchSystem(key)
            }
        }
        .sheet(isPresented: $vm.historyOpen) {
            ChatHistorySheet(vm: vm)
        }
    }

    private var content: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: MeiliMetric.s2) {
                    modeCard
                    if vm.freeMode {
                        freeCard
                    } else {
                        formCards
                    }
                    MeiliButton(vm.generating ? "正在生成…" : "生成话术",
                                icon: MeiliIcons.spark, block: true,
                                enabled: !vm.generating) {
                        vm.generate()
                    }
                    if vm.generating || !vm.rawOutput.isEmpty || vm.genError != nil {
                        resultCard.id("result")
                    }
                }
                .padding(.horizontal, MeiliMetric.screenH)
                .padding(.bottom, 28)
            }
            .scrollDismissesKeyboard(.interactively)
            .onChange(of: vm.generating) { generating in
                if generating {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) {
                        withAnimation { proxy.scrollTo("result", anchor: .top) }
                    }
                }
            }
        }
    }

    // ---- 输入方式 ----
    private var modeCard: some View {
        MeiliCard(tight: true) {
            FieldLabelRow(text: "输入方式", required: false, note: nil)
            OptionChips(options: ["表单填写", "自由描述"],
                        selected: [vm.freeMode ? "自由描述" : "表单填写"]) { opt in
                vm.freeMode = (opt == "自由描述")
            }
        }
    }

    private var freeCard: some View {
        MeiliCard(tight: true) {
            FieldLabelRow(text: "描述这位顾客", required: true,
                          note: "她的年龄/项目/困扰/生活状态/你观察到的点,越具体话术越准")
            PlainInput(placeholder: "如:张姐,35岁左右,常客,今天做身体SPA,肩颈很僵,最近带娃很累…",
                       multiline: true,
                       text: $vm.freeDesc)
        }
    }

    // ---- 表单模式 ----
    @ViewBuilder private var formCards: some View {
        MeiliCard(tight: true) {
            FieldLabelRow(text: "顾客称呼", required: true, note: nil)
            PlainInput(placeholder: "如：张姐、王女士", text: $vm.form.name)
            Spacer().frame(height: MeiliMetric.s2)
            FieldLabelRow(text: "年龄段", required: true, note: nil)
            OptionChips(options: ChatFormSpec.ageOptions, selected: [vm.form.age]) {
                vm.form.age = vm.form.age == $0 ? "" : $0
            }
            Spacer().frame(height: MeiliMetric.s2)
            FieldLabelRow(text: "顾客类型", required: true, note: nil)
            OptionChips(options: ChatFormSpec.visitOptions, selected: [vm.form.visitType]) {
                vm.form.visitType = vm.form.visitType == $0 ? "" : $0
            }
        }

        MeiliCard(tight: true) {
            FieldLabelRow(text: "本次到店项目", required: true, note: "可多选;选了哪类,下面就出现哪类的困扰卡")
            OptionChips(options: ChatFormSpec.projectOptions, selected: Set(vm.form.projects)) { opt in
                if let i = vm.form.projects.firstIndex(of: opt) {
                    vm.form.projects.remove(at: i)
                } else {
                    vm.form.projects.append(opt)
                }
                pruneHiddenCards()
            }
        }

        // ---- 条件卡:面部 / 身体 / 塑形 / 养生(与网页 updateProjectCards 一致) ----
        if ChatFormSpec.showsCard("面部护理", projects: vm.form.projects) {
            concernCard(title: "面部状态", options: ChatFormSpec.concernOptions,
                        selected: $vm.form.concerns,
                        observeLabel: "美容师观察到的亮点",
                        observePlaceholder: "如：轮廓很好、皮肤纹理细腻、T区有光泽",
                        observe: $vm.form.highlights)
        }
        if ChatFormSpec.showsCard("身体SPA", projects: vm.form.projects) {
            concernCard(title: "身体状态", options: ChatFormSpec.bodyConcernOptions,
                        selected: $vm.form.bodyConcerns,
                        observeLabel: "美容师触诊 / 身体观察",
                        observePlaceholder: "如：肩颈条索明显、背部肌肉很硬、小腿偏紧",
                        observe: $vm.form.bodyObserve)
        }
        if ChatFormSpec.showsCard("美体塑形", projects: vm.form.projects) {
            concernCard(title: "美体塑形诉求", options: ChatFormSpec.shapeOptions,
                        selected: $vm.form.shapeNeeds,
                        observeLabel: "美容师体型测量 / 观察",
                        observePlaceholder: "如：腰腹有点松、产后3个月正在恢复期",
                        observe: $vm.form.shapeObserve)
        }
        if ChatFormSpec.showsCard("养生调理", projects: vm.form.projects) {
            concernCard(title: "养生调理诉求", options: ChatFormSpec.wellnessOptions,
                        selected: $vm.form.wellNeeds,
                        observeLabel: "美容师养生观察",
                        observePlaceholder: "如：面色偏暗、常年手脚凉、最近压力大",
                        observe: $vm.form.wellObserve)
        }

        MeiliCard(tight: true) {
            FieldLabelRow(text: "顾客透露的生活状态", required: false, note: "选填,话术会更精准")
            PlainInput(placeholder: "如：经常熬夜、带娃很累、最近压力大", text: $vm.form.lifestyle)
            Spacer().frame(height: MeiliMetric.s2)
            FieldLabelRow(text: "本次希望引导的方向", required: false, note: nil)
            OptionChips(options: ChatFormSpec.goalOptions, selected: [vm.form.goal]) {
                vm.form.goal = vm.form.goal == $0 ? "" : $0
            }
            Spacer().frame(height: MeiliMetric.s2)
            FieldLabelRow(text: "开场风格", required: true, note: nil)
            OptionChips(options: ChatFormSpec.styleOptions, selected: [vm.form.style]) {
                vm.form.style = vm.form.style == $0 ? "" : $0
            }
        }

        MeiliCard(tight: true) {
            FieldLabelRow(text: "特殊情况 / 抗拒点备注", required: false,
                          note: "填了这里,成交环节会针对性地拆解她的顾虑")
            PlainInput(placeholder: "如：老会员只想置换旧项目;怕被推销;时间紧",
                       multiline: true, text: $vm.form.special)
        }
    }

    /// 项目取消勾选后清掉对应卡的数据(与网页隐藏即清空一致,避免幽灵数据混入话术)。
    private func pruneHiddenCards() {
        let p = vm.form.projects
        if !ChatFormSpec.showsCard("面部护理", projects: p) { vm.form.concerns = []; vm.form.highlights = "" }
        if !ChatFormSpec.showsCard("身体SPA", projects: p) { vm.form.bodyConcerns = []; vm.form.bodyObserve = "" }
        if !ChatFormSpec.showsCard("美体塑形", projects: p) { vm.form.shapeNeeds = []; vm.form.shapeObserve = "" }
        if !ChatFormSpec.showsCard("养生调理", projects: p) { vm.form.wellNeeds = []; vm.form.wellObserve = "" }
    }

    private func concernCard(title: String, options: [String], selected: Binding<[String]>,
                             observeLabel: String, observePlaceholder: String,
                             observe: Binding<String>) -> some View {
        MeiliCard(tight: true) {
            FieldLabelRow(text: "\(title)(可多选)", required: false, note: nil)
            OptionChips(options: options, selected: Set(selected.wrappedValue)) { opt in
                if let i = selected.wrappedValue.firstIndex(of: opt) {
                    selected.wrappedValue.remove(at: i)
                } else {
                    selected.wrappedValue.append(opt)
                }
            }
            Spacer().frame(height: MeiliMetric.s2)
            FieldLabelRow(text: observeLabel, required: false, note: nil)
            PlainInput(placeholder: observePlaceholder, text: observe)
        }
    }

    // ---- 生成结果 ----
    private var resultCard: some View {
        MeiliCard {
            HStack {
                Text(vm.generating ? "正在生成…" : "生成结果")
                    .font(.sz(13.5, weight: .bold)).foregroundStyle(MeiliColor.ink)
                Spacer()
                if vm.generating { ProgressView().tint(MeiliColor.clay).scaleEffect(0.8) }
            }
            Spacer().frame(height: MeiliMetric.s2)

            if let err = vm.genError {
                Text(err).font(.sz(13)).foregroundStyle(MeiliColor.roseText)
            } else if vm.rawOutput.isEmpty {
                Text("AI 正在思考,首段通常几秒内出现…")
                    .font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3)
            } else {
                // 💭 顾问思考(可折叠,灰底)
                if !vm.thinking.isEmpty {
                    Button { vm.thinkingExpanded.toggle() } label: {
                        HStack {
                            Text("💭 顾问思考过程").font(.sz(12, weight: .bold)).foregroundStyle(MeiliColor.ink2)
                            Spacer()
                            Text(vm.thinkingExpanded ? "收起" : "展开")
                                .font(MeiliFont.labelSm).foregroundStyle(MeiliColor.clayDeep)
                        }
                    }
                    .buttonStyle(.plain)
                    if vm.thinkingExpanded {
                        Text(vm.thinking)
                            .font(.sz(12)).foregroundStyle(MeiliColor.ink3).lineSpacing(4)
                            .padding(MeiliMetric.s2)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(MeiliColor.surfaceSoft)
                            .clipShape(RoundedRectangle(cornerRadius: MeiliRadius.sm, style: .continuous))
                    }
                    Spacer().frame(height: MeiliMetric.s2)
                }
                // 话术正文(有【话术】标记按切分显示;没有则显示原始流)
                ScriptText(vm.script ?? vm.rawOutput)
            }

            if !vm.warnWords.isEmpty {
                Spacer().frame(height: MeiliMetric.s2)
                StatusPill(text: "含敏感词提醒:\(vm.warnWords.joined(separator: "、"))",
                           kind: .warn, icon: MeiliIcons.warn)
            }

            Spacer().frame(height: MeiliMetric.s3)
            if vm.generating {
                MeiliButton("停止", kind: .ghost) { vm.stopGenerate() }
            } else {
                FlowWrap(spacing: MeiliMetric.s2) {
                    MeiliButton("复制话术", size: .small, enabled: !(vm.script ?? vm.rawOutput).isEmpty) {
                        UIPasteboard.general.string = FollowupView.plainCopyText(vm.script ?? vm.rawOutput)
                        vm.toast = "话术已复制"
                    }
                    MeiliButton("重新生成", kind: .soft, size: .small) { vm.generate() }
                }
            }
        }
    }
}

/// 话术正文:轻量渲染——段落 + (动作提示)着色(对齐网页 script-action-inline)。
struct ScriptText: View {
    let text: String
    init(_ text: String) { self.text = text }

    var body: some View {
        Text(attributed)
            .font(.sz(13.5)).lineSpacing(5)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var attributed: AttributedString {
        // 去掉裸 markdown 符号(**、行首 #/>)后,把中文括号里的动作提示染成鼠尾草色
        let cleaned = FollowupView.plainCopyText(text)
        var out = AttributedString()
        var rest = Substring(cleaned)
        while let open = rest.firstIndex(of: "（") {
            var head = AttributedString(String(rest[..<open]))
            head.foregroundColor = MeiliColor.ink
            out += head
            if let close = rest[open...].firstIndex(of: "）") {
                var action = AttributedString(String(rest[open...close]))
                action.foregroundColor = MeiliColor.sageDeep
                out += action
                rest = rest[rest.index(after: close)...]
            } else {
                var tail = AttributedString(String(rest[open...]))
                tail.foregroundColor = MeiliColor.ink
                out += tail
                rest = Substring("")
            }
        }
        var tail = AttributedString(String(rest))
        tail.foregroundColor = MeiliColor.ink
        out += tail
        return out
    }
}

// ---- 历史记录 sheet ----
struct ChatHistorySheet: View {
    @ObservedObject var vm: ChatViewModel
    @State private var expanded: String?

    var body: some View {
        ZStack {
            MeiliColor.bg.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: MeiliMetric.s2) {
                    Text("历史记录").font(MeiliFont.sheetH3).foregroundStyle(MeiliColor.ink)
                        .padding(.top, 22)
                    Text("生成过的话术 · 点 ⭐ 收藏 · 点开可复制")
                        .font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3)
                        .padding(.bottom, 8)

                    if vm.historyLoading {
                        HStack { Spacer(); ProgressView().tint(MeiliColor.clay); Spacer() }
                            .padding(.vertical, 30)
                    } else if vm.historyItems.isEmpty {
                        Text("还没有历史记录").font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3)
                            .padding(.vertical, 20)
                    } else {
                        ForEach(vm.historyItems, id: \.id) { item in
                            historyRow(item)
                        }
                        // 分页
                        HStack {
                            MeiliButton("上一页", kind: .ghost, size: .xs, enabled: vm.historyPage > 1) {
                                vm.loadHistory(page: vm.historyPage - 1)
                            }
                            Spacer()
                            Text("\(vm.historyPage)/\(vm.historyTotalPages)")
                                .font(MeiliFont.labelSm).foregroundStyle(MeiliColor.ink3)
                            Spacer()
                            MeiliButton("下一页", kind: .ghost, size: .xs,
                                        enabled: vm.historyPage < vm.historyTotalPages) {
                                vm.loadHistory(page: vm.historyPage + 1)
                            }
                        }
                        .padding(.top, MeiliMetric.s2)
                    }
                }
                .padding(.horizontal, MeiliMetric.screenH)
                .padding(.bottom, 24)
            }
        }
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
    }

    private func historyRow(_ item: ChatHistoryItem) -> some View {
        VStack(alignment: .leading, spacing: MeiliMetric.s2) {
            HStack(spacing: 8) {
                Button { vm.toggleFavorite(item) } label: {
                    MeiliIcon(MeiliIcons.star, size: 20)
                        .foregroundStyle(item.favorite ? MeiliColor.honey : MeiliColor.ink4)
                }
                .buttonStyle(.plain)
                VStack(alignment: .leading, spacing: 2) {
                    Text(item.prompt.split(separator: "\n").first.map(String.init) ?? "(无描述)")
                        .font(.sz(12.5, weight: .bold)).foregroundStyle(MeiliColor.ink)
                        .lineLimit(1)
                    Text(String(item.createdAt.prefix(16)).replacingOccurrences(of: "T", with: " "))
                        .font(MeiliFont.labelSm).foregroundStyle(MeiliColor.ink4)
                }
                Spacer()
                MeiliButton(expanded == item.id ? "收起" : "查看", kind: .ghost, size: .xs) {
                    expanded = expanded == item.id ? nil : item.id
                }
            }
            if expanded == item.id {
                let content = item.script.isEmpty ? item.reply : item.script
                ScriptText(content)
                    .padding(MeiliMetric.s2)
                    .background(MeiliColor.surfaceSoft)
                    .clipShape(RoundedRectangle(cornerRadius: MeiliRadius.sm, style: .continuous))
                MeiliButton("复制话术", size: .xs) {
                    UIPasteboard.general.string = FollowupView.plainCopyText(content)
                    vm.toast = "话术已复制"
                }
            }
        }
        .padding(MeiliMetric.s3)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(MeiliColor.surface)
        .clipShape(RoundedRectangle(cornerRadius: MeiliRadius.md, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: MeiliRadius.md, style: .continuous)
                .strokeBorder(MeiliColor.lineSoft, lineWidth: MeiliMetric.borderThin)
        }
    }
}
