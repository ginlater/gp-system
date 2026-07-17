import SwiftUI

/// 回访话术 / 高情商话术(规格驱动原生表单)。
/// android 端对应 `ui/followup/FollowupScreen.kt`(v3)。
///
/// 对齐网页版体验的三块:
///  1. 生成结果**内联在表单下方**同一滚动页——生成时自动滚到结果,随时能滑回上面改表单;
///  2. 话术做轻量 markdown 渲染(### 标题/**粗体**/列表),不露原始符号;
///  3. 「导入历史」完整回归:顾客档案列表(收藏⭐/导入回填/查看历史话术)+ 战果登记。
/// 页内 A－/A＋ 字号(在全局字体档位上再乘系数,持久化)。
struct FollowupView: View {
    let systemKey: String
    let onSwitchSystem: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @StateObject private var vm = FollowupViewModel()
    @ObservedObject private var theme = ThemeManager.shared
    @State private var switcherOpen = false

    var body: some View {
        ZStack(alignment: .bottom) {
            MeiliColor.bg.ignoresSafeArea()
            VStack(spacing: 0) {
                MeiliTopBar(title: vm.sys.displayName,
                            subtitle: vm.quotaText.isEmpty ? "填写情况 · AI 生成话术" : vm.quotaText,
                            onBack: { dismiss() }) {
                    TopBarIconButton(icon: MeiliIcons.workspace) { switcherOpen = true }
                    // A± 调的是全局字体档位(与设置页同一份,持久化;安卓是页内系数,iOS 统一走全局更一致)
                    FontZoomButton(text: "A－") { theme.setFontScale(max(0.8, theme.fontScale - 0.1)) }
                    FontZoomButton(text: "A＋") { theme.setFontScale(min(1.5, theme.fontScale + 0.1)) }
                }
                .padding(.horizontal, MeiliMetric.screenH)

                if let err = vm.loginError {
                    TeachErrorRetry(message: err) { vm.loadQuota() }
                    Spacer()
                } else if vm.spec == nil {
                    Spacer()
                    ProgressView().tint(MeiliColor.clay)
                    Spacer()
                } else {
                    formWithResult
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
        .onAppear { vm.initLoad(systemKey: systemKey) }
        .sheet(isPresented: $switcherOpen) {
            SystemSwitcherSheet(currentKey: systemKey) { key in
                switcherOpen = false
                onSwitchSystem(key)
            }
        }
        .sheet(isPresented: $vm.historyOpen) {
            FollowupHistorySheet(vm: vm)
        }
        .sheet(isPresented: Binding(get: { vm.businessFor != nil },
                                    set: { if !$0 { vm.businessFor = nil } })) {
            if let c = vm.businessFor {
                FollowupBusinessSheet(customer: c, vm: vm)
            }
        }
    }

    // ============================================================
    // 表单 + 内联结果(同一滚动列表)
    // ============================================================

    private var formWithResult: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: MeiliMetric.s2) {
                    modelCard
                    customerCard
                    advisorCard

                    // ---- 其余公共字段(分支前) ----
                    let preCommon = (vm.spec?.common ?? []).filter {
                        !Self.handledCommonIds.contains($0.id ?? "") && $0.renderAfterBranches != true
                    }
                    ForEach(Array(preCommon.enumerated()), id: \.offset) { _, f in
                        FieldCard(f: f, vm: vm)
                    }

                    natureCard

                    // ---- 分支字段(级联) ----
                    let visible = vm.visibleBranchFields()
                    ForEach(Array(visible.enumerated()), id: \.offset) { _, f in
                        FieldCard(f: f, vm: vm)
                    }

                    // ---- 分支后公共字段(noteDetail) ----
                    let postCommon = (vm.spec?.common ?? []).filter { $0.renderAfterBranches == true }
                    ForEach(Array(postCommon.enumerated()), id: \.offset) { _, f in
                        FieldCard(f: f, vm: vm)
                    }

                    MeiliButton(vm.generating ? "正在生成…" : "生成话术",
                                icon: MeiliIcons.spark, block: true,
                                enabled: !vm.generating) {
                        vm.generate()
                    }

                    if vm.generating || !vm.output.isEmpty || vm.genError != nil {
                        resultCard.id("result")
                    }
                }
                .padding(.horizontal, MeiliMetric.screenH)
                .padding(.bottom, 28)
            }
            .scrollDismissesKeyboard(.interactively)
            // 点了生成 → 自动滚到结果区;用户仍可随时往上滑回表单
            .onChange(of: vm.generating) { generating in
                if generating {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) {
                        withAnimation { proxy.scrollTo("result", anchor: .top) }
                    }
                }
            }
        }
    }

    /// 已在专属卡里渲染的公共字段 id(通用渲染跳过)。
    private static let handledCommonIds: Set<String> = [
        "modelSelect", "visitNature", "customerName", "customerPhoneSuffix", "consultantName", "companyName",
    ]

    // ---- AI 模型(胶囊上带 剩余/总额度) ----
    private var modelCard: some View {
        MeiliCard(tight: true) {
            FieldLabelRow(text: "AI 模型", required: false, note: nil)
            OptionChips(
                options: FollowupViewModel.models.map { "\($0.label)\(vm.quotaSuffix($0.key))" },
                selected: Set(FollowupViewModel.models.filter { $0.key == vm.model }
                    .map { "\($0.label)\(vm.quotaSuffix($0.key))" })) { label in
                if let m = FollowupViewModel.models.first(where: { "\($0.label)\(vm.quotaSuffix($0.key))" == label }) {
                    vm.setModel(m.key)
                }
            }
        }
    }

    // ---- 顾客姓名 + 手机尾号 + 导入历史(一行,对齐网页 customer-bar) ----
    private var customerCard: some View {
        MeiliCard(tight: true) {
            HStack(spacing: MeiliMetric.s2) {
                PlainInput(placeholder: "顾客姓名*",
                           text: Binding(get: { vm.texts["customerName"] ?? "" },
                                         set: { vm.setText("customerName", $0) }))
                PlainInput(placeholder: "尾号4位",
                           keyboard: .numberPad,
                           text: Binding(get: { vm.texts["customerPhoneSuffix"] ?? "" },
                                         set: { vm.setText("customerPhoneSuffix", String($0.filter(\.isNumber).prefix(4))) }))
                    .frame(width: 92)
                MeiliButton("导入历史", kind: .soft, size: .small) { vm.openHistory() }
            }
        }
    }

    // ---- 顾问 / 门店(自动带出,只读) ----
    private var advisorCard: some View {
        MeiliCard(tight: true) {
            HStack {
                VStack(alignment: .leading, spacing: 3) {
                    Text("顾问姓名").font(MeiliFont.label).foregroundStyle(MeiliColor.ink3)
                    Text((vm.texts["consultantName"] ?? "").nilIfBlank ?? "—")
                        .font(.sz(13.5, weight: .bold)).foregroundStyle(MeiliColor.ink)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                VStack(alignment: .leading, spacing: 3) {
                    Text("所属门店").font(MeiliFont.label).foregroundStyle(MeiliColor.ink3)
                    Text((vm.texts["companyName"] ?? "").nilIfBlank ?? "—")
                        .font(.sz(13.5, weight: .bold)).foregroundStyle(MeiliColor.ink)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }

    // ---- 消息性质 ----
    private var natureCard: some View {
        MeiliCard(tight: true) {
            FieldLabelRow(text: "本次消息性质", required: true, note: "决定下方要填的内容")
            OptionChips(options: vm.branches.compactMap(\.nature),
                        selected: [vm.natureText]) { nature in
                if let i = vm.branches.firstIndex(where: { $0.nature == nature }) {
                    vm.selectNature(i)
                }
            }
        }
    }

    // ---- 生成结果(内联;可滑回上方改表单) ----
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
            } else if vm.output.isEmpty {
                Text("AI 正在思考,首段通常几秒内出现…")
                    .font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3)
            } else {
                MarkdownLiteView(text: vm.output, accent: MeiliColor.clayDeep)
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
                copyButtons
            }
        }
    }

    /// 话术含 版本1/2/3 时按版本分开复制;否则整篇复制。
    private var copyButtons: some View {
        let versions = FollowupView.splitVersions(vm.output)
        return FlowWrap(spacing: MeiliMetric.s2) {
            if versions.count >= 2 {
                ForEach(Array(versions.enumerated()), id: \.offset) { _, v in
                    MeiliButton("复制\(v.label)", size: .small) {
                        UIPasteboard.general.string = v.content
                        vm.toast = "\(v.label) 已复制"
                    }
                }
                MeiliButton("复制全部", kind: .ghost, size: .small) {
                    UIPasteboard.general.string = FollowupView.plainCopyText(vm.output)
                    vm.toast = "已复制全部"
                }
            } else {
                MeiliButton("复制话术", size: .small, enabled: !vm.output.isEmpty) {
                    UIPasteboard.general.string = FollowupView.plainCopyText(vm.output)
                    vm.toast = "话术已复制"
                }
            }
            MeiliButton("重新生成", kind: .soft, size: .small) { vm.generate() }
        }
    }

    // ============================================================
    // 复制辅助
    // ============================================================

    /// 复制时去掉 markdown 符号(**加粗、#标题),微信里直接可发。
    static func plainCopyText(_ md: String) -> String {
        md.replacingOccurrences(of: "**", with: "")
            .split(separator: "\n", omittingEmptySubsequences: false)
            .map { line -> String in
                var s = String(line)
                while s.hasPrefix("#") { s.removeFirst() }
                return s.trimmingCharacters(in: .whitespaces)
            }
            .joined(separator: "\n")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// 把话术按「版本1/2/3」标记拆成 (标签, 正文) 列表(正文已去 markdown,不含版本标题行)。
    /// 找不到 ≥2 个版本标记时返回空列表(按整篇复制处理)。
    static func splitVersions(_ text: String) -> [(label: String, content: String)] {
        guard let re = try? NSRegularExpression(pattern: #"(?m)^\s*\*{0,2}版本\s*([0-9一二三])[^\n]*"#) else { return [] }
        let ns = text as NSString
        let marks = re.matches(in: text, range: NSRange(location: 0, length: ns.length))
        guard marks.count >= 2 else { return [] }
        var out: [(String, String)] = []
        for (i, m) in marks.enumerated() {
            let start = m.range.location + m.range.length
            let end = i + 1 < marks.count ? marks[i + 1].range.location : ns.length
            let label = "版本\(ns.substring(with: m.range(at: 1)))"
            let content = plainCopyText(ns.substring(with: NSRange(location: start, length: end - start)))
            if !content.isEmpty { out.append((label, content)) }
        }
        return out
    }
}

// ============================================================
// 规格字段渲染
// ============================================================

/// 单个规格字段卡:选项组=胶囊 chips / 文本=输入框 / 自定义输入级联。
struct FieldCard: View {
    let f: FormField
    @ObservedObject var vm: FollowupViewModel

    var body: some View {
        let id = f.id ?? ""
        MeiliCard(tight: true) {
            let title = [f.optionsSection, f.label].compactMap { $0?.nilIfBlank }.joined(separator: " · ")
            FieldLabelRow(text: title.isEmpty ? id : title, required: f.required == true, note: f.sublabelNote)

            let opts = vm.resolvedOptions(f)
            if f.isOptions && opts.isEmpty {
                // 无选项的下拉退化成文本输入
                PlainInput(placeholder: f.placeholder ?? f.label ?? "",
                           text: Binding(get: { vm.texts[id] ?? "" },
                                         set: { vm.setText(id, $0) }))
            } else if f.isOptions {
                OptionChips(options: opts, selected: Set(vm.selections[id] ?? [])) { opt in
                    vm.toggleOption(groupId: id, option: opt, multi: f.multi == true)
                }
                // 自定义文本框:对应选项被选中才出现
                if let ci = f.customInput, let ciId = ci.id,
                   (ci.showWhen ?? []).contains(where: { (vm.selections[id] ?? []).contains($0) }) {
                    Spacer().frame(height: MeiliMetric.s2)
                    PlainInput(placeholder: ci.placeholder ?? "",
                               text: Binding(get: { vm.texts[ciId] ?? "" },
                                             set: { vm.setText(ciId, $0) }))
                }
            } else {
                let single = f.isText || id.hasSuffix("Addressing")
                PlainInput(placeholder: f.placeholder ?? "",
                           multiline: !single,
                           text: Binding(
                               get: { vm.texts[id] ?? "" },
                               set: { v in
                                   vm.setText(id, id == "customerPhoneSuffix"
                                              ? String(v.filter(\.isNumber).prefix(4)) : v)
                               }))
            }
        }
    }
}

/// 字段标题行:标题 + 必填/选填 + 可选说明。
struct FieldLabelRow: View {
    let text: String
    let required: Bool
    let note: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack(alignment: .firstTextBaseline, spacing: 0) {
                Text(text)
                    .font(.sz(13.5, weight: .bold)).foregroundStyle(MeiliColor.ink)
                Text(required ? " 必填" : " 选填")
                    .font(MeiliFont.labelSm)
                    .foregroundStyle(required ? MeiliColor.roseText : MeiliColor.ink4)
            }
            if let note = note?.nilIfBlank {
                Text(note).font(MeiliFont.labelSm).foregroundStyle(MeiliColor.ink4)
            }
        }
        .padding(.bottom, 6)
    }
}

/// 选项胶囊组(单/多选样式一致,选中=陶土 tint 底+描边)。
struct OptionChips: View {
    let options: [String]
    let selected: Set<String>
    let onTap: (String) -> Void

    var body: some View {
        FlowWrap(spacing: MeiliMetric.s2) {
            ForEach(options, id: \.self) { text in
                let isSel = selected.contains(text)
                Button { onTap(text) } label: {
                    Text(text)
                        .font(.sz(11.5, weight: .semibold))
                        .foregroundStyle(isSel ? MeiliColor.clayDeep : MeiliColor.ink2)
                        .padding(.horizontal, 11).padding(.vertical, 6)
                        .background(isSel ? MeiliColor.clayTint : MeiliColor.surfaceSoft)
                        .clipShape(Capsule())
                        .overlay {
                            Capsule().strokeBorder(isSel ? MeiliColor.clay : MeiliColor.lineSoft,
                                                   lineWidth: MeiliMetric.borderField)
                        }
                }
                .buttonStyle(.plain)
            }
        }
    }
}

/// 无 label 的圆角输入框(表单密集处用;聚焦陶土描边)。
struct PlainInput: View {
    var placeholder: String = ""
    var multiline: Bool = false
    var keyboard: UIKeyboardType = .default
    @Binding var text: String
    @FocusState private var focused: Bool

    var body: some View {
        Group {
            if multiline {
                TextField(placeholder, text: $text, axis: .vertical)
                    .lineLimit(3...10)
            } else {
                TextField(placeholder, text: $text)
            }
        }
        .font(.sz(13))
        .foregroundStyle(MeiliColor.ink)
        .tint(MeiliColor.clay)
        .keyboardType(keyboard)
        .focused($focused)
        .padding(.horizontal, 12).padding(.vertical, 10)
        .background(focused ? MeiliColor.white : MeiliColor.surfaceSoft)
        .clipShape(RoundedRectangle(cornerRadius: MeiliRadius.sm, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: MeiliRadius.sm, style: .continuous)
                .strokeBorder(focused ? MeiliColor.clay : MeiliColor.line,
                              lineWidth: MeiliMetric.borderField)
        }
    }
}
