import SwiftUI

/// 回访话术 / 高情商话术 ViewModel —— 规格驱动。
/// android 端对应 `ui/followup/FollowupViewModel.kt`。
///
/// 表单结构不手写:bundle 里的 followup_form_spec.json 是从网页 index.html 离线提取的
/// 完整规格(全部字段/选项/级联显隐),本 VM 只负责状态与收集,与网页 collectData 同构:
///  - 只收集**当前可见**字段(级联隐藏的不进 customer_data,与网页 pickValue 一致);
///  - 同 id 多节选项组自动合并;
///  - 选「自定义」出现的文本框按 customInput.id 收集。
/// 生成走 FollowupRepository 的 SSE 流式。
@MainActor
final class FollowupViewModel: ObservableObject {
    /// AI 模型档位(与网页 modelSelect 一致)。
    static let models: [(key: String, label: String)] = [
        ("claude_sonnet", "至尊版"),
        ("deepseek_v4_pro", "专家版"),
        ("deepseek_v4_pro_lite", "进阶版"),
        ("doubao_seed", "标准版"),
        ("doubao_seed_lite", "极速版"),
    ]

    /// 模型 → 额度组(配额按组计数:doubao_seed 归 doubao 组,其余同名)。
    static func quotaGroupOf(_ model: String) -> String {
        model == "doubao_seed" ? "doubao" : model
    }

    /// 动态选项替换时保留的静态兜底项。
    static let staticKeep: Set<String> = ["自定义", "都不是", "其它", "其他"]

    // ---- 表单态 ----
    @Published var spec: FormSpecFile?
    @Published var natureIndex = 0
    @Published var selections: [String: [String]] = [:]
    @Published var texts: [String: String] = [:]
    @Published var dynamicOptions: [String: [String]] = [:]
    @Published var model = "claude_sonnet"

    // ---- 生成态 ----
    @Published var generating = false
    @Published var output = ""
    @Published var warnWords: [String] = []
    @Published var genError: String?

    // ---- 导入历史 / 战果 ----
    @Published var historyOpen = false
    @Published var historyLoading = false
    @Published var customers: [FuCustomer] = []
    @Published var businessFor: FuCustomer?

    // ---- 杂项 ----
    @Published var quotaText = ""
    @Published var quotaGroups: [String: FuQuotaGroup] = [:]
    @Published var quotaBypass = false
    @Published var loginError: String?
    @Published var toast: String?

    private(set) var sys: ScriptSystem = .followup
    private var repo: FollowupRepository { FollowupRepository(sys: sys) }
    private var inited = false
    private var genTask: Task<Void, Never>?

    var branches: [FormBranch] { spec?.branches ?? [] }
    var natureText: String { branches.indices.contains(natureIndex) ? (branches[natureIndex].nature ?? "") : "" }

    /// 首次进入:定系统、读规格、预填顾问姓名、拉额度。重复调忽略。
    func initLoad(systemKey: String) {
        if inited { return }
        inited = true
        sys = ScriptSystem(rawValue: systemKey) ?? .followup

        // 读 bundle 规格 + 预选默认值
        if let url = Bundle.main.url(forResource: "followup_form_spec", withExtension: "json"),
           let data = try? Data(contentsOf: url),
           let file = try? SubsystemHTTP.plainDecoder.decode(FormSpecFile.self, from: data) {
            spec = file
            selections = Self.defaultSelections(file)
        }
        texts["consultantName"] = AuthManager.lastMe?.advisorName ?? ""

        // 动态选项(todayProject/痛点/护理项目等按公司下发);失败退回规格里的静态选项
        Task {
            if let opts = try? await repo.projectOptions() {
                dynamicOptions = opts
            }
        }
        // 顾问姓名/所属门店自动带出:名单里匹配到本人 → 门店跟着填(与网页同源)
        Task {
            if let emps = try? await repo.employees() {
                let myName = AuthManager.lastMe?.advisorName ?? ""
                if let mine = (emps.employees ?? []).first(where: { $0.name == myName }),
                   let store = mine.store {
                    texts["companyName"] = store
                }
            }
        }
        loadQuota()
    }

    /// 网页里带预选默认值的组,进页面即选中(与网页一致):
    /// 「是/否」型提及行默认「否」;节气行默认「带入当前节气」。
    private static func defaultSelections(_ spec: FormSpecFile) -> [String: [String]] {
        var out: [String: [String]] = [:]
        let all = (spec.common ?? []) + (spec.branches ?? []).flatMap { $0.fields ?? [] }
        for f in all {
            guard let id = f.id, f.isOptions, f.multi != true, out[id] == nil else { continue }
            let opts = f.options ?? []
            if opts.contains("带入当前节气") { out[id] = ["带入当前节气"] }
            else if opts.count == 2, opts.contains("是"), opts.contains("否") { out[id] = ["否"] }
        }
        return out
    }

    func loadQuota() {
        Task {
            do {
                let q = try await repo.quota()
                let groups = q.groups ?? [:]
                let bypass = q.bypass == true
                let g = groups[Self.quotaGroupOf(model)]
                if bypass || g?.limit == -1 {
                    quotaText = "本月不限次"
                } else if let g {
                    quotaText = "本月剩余 \((g.limit ?? 0) - (g.used ?? 0)) 次"
                } else {
                    quotaText = ""
                }
                quotaGroups = groups
                quotaBypass = bypass
                loginError = nil
            } catch {
                loginError = (error as? SubsystemError)?.message ?? "网络异常"
            }
        }
    }

    /// 模型胶囊上的余量后缀:" 98/100" / " 不限";没拉到额度时为空串。
    func quotaSuffix(_ model: String) -> String {
        if quotaBypass { return " 不限" }
        guard let g = quotaGroups[Self.quotaGroupOf(model)], let limit = g.limit else { return "" }
        if limit == -1 { return " 不限" }
        return " \(max(0, limit - (g.used ?? 0)))/\(limit)"
    }

    // ─────────────────────────── 表单交互 ───────────────────────────

    func selectNature(_ index: Int) { natureIndex = index }

    func setModel(_ m: String) {
        model = m
        loadQuota()   // 各模型组额度不同,切换后刷新
    }

    /// 点选项:单选=点已选取消/点新的替换;多选=切换。与网页行为一致。
    func toggleOption(groupId: String, option: String, multi: Bool) {
        let cur = selections[groupId] ?? []
        if multi {
            selections[groupId] = cur.contains(option) ? cur.filter { $0 != option } : cur + [option]
        } else {
            selections[groupId] = cur.contains(option) ? [] : [option]
        }
    }

    func setText(_ id: String, _ value: String) { texts[id] = value }

    /// 当前分支的可见字段(级联显隐,与网页一致)。
    func visibleBranchFields() -> [FormField] {
        guard branches.indices.contains(natureIndex) else { return [] }
        return (branches[natureIndex].fields ?? []).filter { f in
            (f.visibleWhen?.matches(selections) ?? true) &&
                (f.parentVisibleWhen?.matches(selections) ?? true)
        }
    }

    /// 字段实际选项:动态类目命中则用服务端下发 + 保留静态兜底项(自定义/都不是)。
    func resolvedOptions(_ f: FormField) -> [String] {
        if let cat = f.dynamicCat, let dyn = dynamicOptions[cat], !dyn.isEmpty {
            return dyn + (f.options ?? []).filter { Self.staticKeep.contains($0) }
        }
        return f.options ?? []
    }

    // ─────────────────────────── 生成 ───────────────────────────

    /// 与网页 collectData 同构:顶层公共 + visitNature + 当前分支可见字段。
    func generate() {
        if generating { return }

        if (texts["customerName"] ?? "").isEmpty {
            toast = "请先填写顾客姓名"
            return
        }
        // 必填校验(只查可见的;无选项的下拉退化成文本,认文本值)
        if let missing = visibleBranchFields().first(where: { f in
            guard f.required == true else { return false }
            if f.isOptions && !resolvedOptions(f).isEmpty {
                return (selections[f.id ?? ""] ?? []).isEmpty
            }
            return (texts[f.id ?? ""] ?? "").isEmpty
        }) {
            toast = "「\(missing.label ?? missing.id ?? "")」是必填项"
            return
        }

        var data: [String: Any] = [:]
        // 顶层公共字段(规格 common 里的 text/textarea 全收,选项组同分支规则)
        for f in spec?.common ?? [] { collectField(f, into: &data) }
        data["visitNature"] = [natureText]
        // 当前分支:只收可见字段
        for f in visibleBranchFields() { collectField(f, into: &data) }

        generating = true
        output = ""
        warnWords = []
        genError = nil
        let m = model
        genTask = Task { [weak self] in
            guard let self else { return }
            for await ev in self.repo.generateStream(customerData: data, model: m) {
                switch ev {
                case .content(let delta):
                    self.output += delta
                case .restart:
                    self.output = ""
                    self.toast = "内容触发合规重写，正在重新生成…"
                case .warning(let words):
                    self.warnWords = words
                case .failed(let msg):
                    self.generating = false
                    self.genError = msg
                case .done:
                    self.generating = false
                    self.loadQuota()
                    let name = self.texts["customerName"] ?? ""
                    let script = self.output
                    if !name.isEmpty, !script.isEmpty {
                        // 生成后自动存档(与网页端历史互通;失败不打扰)
                        Task { await self.repo.saveCustomer(name: name, data: data, script: script, model: m) }
                    }
                }
            }
        }
    }

    private func collectField(_ f: FormField, into out: inout [String: Any]) {
        guard let id = f.id else { return }
        if f.isOptions {
            let sel = selections[id] ?? []
            if !sel.isEmpty {
                // 同 id 多节:可能已收过,合并去重
                let prev = out[id] as? [String] ?? []
                var merged = prev
                for s in sel where !merged.contains(s) { merged.append(s) }
                out[id] = merged
            } else if let t = texts[id]?.nilIfBlank {
                // 无选项的下拉(consultantName 等)退化成文本输入,按字符串收
                out[id] = t
            }
            // 自定义文本框:对应选项被选中才收
            if let ci = f.customInput, let ciId = ci.id,
               (ci.showWhen ?? []).contains(where: { sel.contains($0) }),
               let t = texts[ciId]?.nilIfBlank {
                out[ciId] = t
            }
        } else if let t = texts[id]?.nilIfBlank {
            out[id] = t
        }
    }

    func stopGenerate() {
        genTask?.cancel()
        genTask = nil
        generating = false
    }

    // ─────────────────────────── 导入历史 / 收藏 / 战果 ───────────────────────────

    func openHistory() {
        historyOpen = true
        loadCustomers()
    }

    func loadCustomers() {
        historyLoading = true
        Task {
            do {
                customers = try await repo.customers().customers ?? []
            } catch {
                toast = (error as? SubsystemError)?.message ?? "加载失败"
            }
            historyLoading = false
        }
    }

    func toggleFavorite(_ c: FuCustomer) {
        guard let name = c.name else { return }
        let target = c.favorited != true
        // 乐观更新
        customers = customers.map { item in
            var it = item
            if it.name == name { it.favorited = target }
            return it
        }
        Task {
            do { try await repo.favorite(name: name, favorited: target) }
            catch { loadCustomers() }   // 失败回读
        }
    }

    /// 把历史顾客的表单数据回填(与网页「导入历史」等价):列表值进选项组,字符串进文本框。
    func importCustomer(_ c: FuCustomer) {
        for (k, v) in c.data ?? [:] {
            if let list = v.stringList {
                if k == "visitNature" {
                    if let n = list.first,
                       let i = branches.firstIndex(where: { $0.nature == n }) {
                        natureIndex = i
                    }
                } else {
                    selections[k] = list
                }
            } else if let s = v.stringValue, !s.isEmpty {
                texts[k] = s
            }
        }
        historyOpen = false
        toast = "已导入「\(c.name ?? "")」的资料"
    }

    /// 提交战果(写该顾客最新话术版本)。
    func submitBusiness(_ body: [String: Any]) {
        guard let c = businessFor, let name = c.name else { return }
        let idx = max(0, (c.scripts?.count ?? 1) - 1)
        Task {
            do {
                try await repo.recordBusiness(name: name, versionIdx: idx, body: body)
                businessFor = nil
                toast = "战果已登记"
            } catch {
                toast = "战果登记失败,请重试"
            }
            loadCustomers()
        }
    }
}
