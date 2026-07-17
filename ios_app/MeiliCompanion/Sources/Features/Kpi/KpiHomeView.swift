import SwiftUI

/// KPI 积分·员工端(原生,2026-07-16 起替代网页壳)。
/// 网页版 index.html 的原生复刻:登录(静默优先)→ 提报 / 积分规则 / 我的记录 三段。
struct KpiHomeView: View {
    let onSwitchSystem: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @StateObject private var vm = KpiHomeViewModel()
    @State private var switcherOpen = false

    var body: some View {
        ZStack(alignment: .bottom) {
            MeiliColor.bg.ignoresSafeArea()
            VStack(spacing: 0) {
                MeiliTopBar(title: "KPI积分",
                            subtitle: vm.empName.isEmpty ? "晋升积分 · 业绩看板" : "\(vm.empName) · 贡献提报",
                            onBack: { dismiss() }) {
                    TopBarIconButton(icon: MeiliIcons.workspace) { switcherOpen = true }
                }
                .padding(.horizontal, MeiliMetric.screenH)

                switch vm.phase {
                case .loading:
                    Spacer(); ProgressView().tint(MeiliColor.clay); Spacer()
                case .manualLogin:
                    manualLogin
                case .ready:
                    segments
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
            SystemSwitcherSheet(currentKey: "kpi") { key in
                switcherOpen = false
                onSwitchSystem(key)
            }
        }
        .sheet(isPresented: Binding(get: { vm.editing != nil },
                                    set: { if !$0 { vm.editing = nil } })) {
            if let rec = vm.editing {
                KpiEditSheet(vm: vm, record: rec)
            }
        }
    }

    // ---- 手动登录兜底(姓名映射不到 / 密码被本人改过) ----
    private var manualLogin: some View {
        ScrollView {
            VStack(spacing: MeiliMetric.s3) {
                MeiliCard {
                    Text("登录 KPI 积分")
                        .font(.sz(15, weight: .bold)).foregroundStyle(MeiliColor.ink)
                    Text(vm.loginError ?? "没能自动登录(账号未配置或密码已改),请手动登录一次,之后会记住")
                        .font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3)
                        .padding(.top, 2).padding(.bottom, 10)
                    MeiliField(label: "账号(拼音)", text: $vm.manualUser, placeholder: "如 lilele")
                    Spacer().frame(height: MeiliMetric.s2)
                    MeiliField(label: "密码", text: $vm.manualPass, placeholder: "初始 123456", isSecure: true)
                    Spacer().frame(height: MeiliMetric.s3)
                    MeiliButton(vm.loggingIn ? "登录中…" : "登 录", block: true,
                                enabled: !vm.manualUser.isEmpty && !vm.manualPass.isEmpty && !vm.loggingIn) {
                        vm.manualLogin()
                    }
                }
            }
            .padding(.horizontal, MeiliMetric.screenH)
            .padding(.top, 30)
        }
        .scrollDismissesKeyboard(.interactively)
    }

    // ---- 三段切换 ----
    private var segments: some View {
        HStack(spacing: MeiliMetric.s2) {
            segChip("📝 提报", .submit)
            segChip("📋 规则", .rules)
            segChip("🧾 我的记录", .records)
        }
        .padding(.horizontal, MeiliMetric.screenH)
        .padding(.vertical, 8)
    }

    private func segChip(_ label: String, _ tab: KpiHomeViewModel.Tab) -> some View {
        let selected = vm.tab == tab
        return Button {
            vm.tab = tab
            if tab == .records, vm.recordsPage == nil { vm.loadRecords(page: 1) }
        } label: {
            Text(label).font(.sz(12.5, weight: .bold))
                .foregroundStyle(selected ? MeiliColor.clayDeep : MeiliColor.ink2)
                .frame(maxWidth: .infinity).padding(.vertical, 9)
                .background(selected ? MeiliColor.clayTint : MeiliColor.surfaceSoft)
                .clipShape(RoundedRectangle(cornerRadius: MeiliRadius.sm, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: MeiliRadius.sm, style: .continuous)
                        .strokeBorder(selected ? MeiliColor.clay : MeiliColor.line, lineWidth: 1.5)
                }
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder private var content: some View {
        switch vm.tab {
        case .submit: submitTab
        case .rules: rulesTab
        case .records: recordsTab
        }
    }

    // ---- 提报 ----
    private var submitTab: some View {
        ScrollView {
            LazyVStack(spacing: MeiliMetric.s2) {
                MeiliCard(tight: true) {
                    FieldLabelRow(text: "贡献类别", required: true, note: nil)
                    OptionChips(options: vm.cats.map { "\($0.icon) \($0.name)" },
                                selected: vm.pickCat.map { ["\($0.icon) \($0.name)"] }.map(Set.init) ?? []) { label in
                        vm.selectCategory(label)
                    }
                }
                if let cat = vm.pickCat {
                    MeiliCard(tight: true) {
                        FieldLabelRow(text: "贡献事件", required: true, note: "分值按规则自动计算")
                        VStack(spacing: MeiliMetric.s2) {
                            ForEach(cat.events) { ev in
                                eventRow(ev, selected: vm.pickEvent?.id == ev.id)
                            }
                        }
                    }
                }
                if let ev = vm.pickEvent {
                    MeiliCard(tight: true) {
                        HStack {
                            FieldLabelRow(text: "数量(\(ev.unit))", required: true, note: nil)
                            Spacer()
                            HStack(spacing: 14) {
                                MeiliButton("－", kind: .ghost, size: .xs, enabled: vm.qty > 1) { vm.qty -= 1 }
                                Text("\(vm.qty)").font(.sz(16, weight: .bold)).foregroundStyle(MeiliColor.ink)
                                MeiliButton("＋", kind: .ghost, size: .xs, enabled: vm.qty < 999) { vm.qty += 1 }
                            }
                        }
                        HStack {
                            Text("本次积分").font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3)
                            Spacer()
                            Text("\(ev.pts * vm.qty) 分")
                                .font(.sz(17, weight: .heavy))
                                .foregroundStyle(ev.pts >= 0 ? MeiliColor.leafText : MeiliColor.roseText)
                        }
                        .padding(.top, 6)
                        Spacer().frame(height: MeiliMetric.s2)
                        FieldLabelRow(text: "日期", required: true, note: nil)
                        DatePicker("", selection: $vm.date, displayedComponents: .date)
                            .datePickerStyle(.compact).labelsHidden().tint(MeiliColor.clay)
                        Spacer().frame(height: MeiliMetric.s2)
                        FieldLabelRow(text: "备注", required: false, note: nil)
                        PlainInput(placeholder: "补充说明(选填)", text: $vm.note)
                    }
                    MeiliButton(vm.submitting ? "提交中…" : "提交提报", block: true, enabled: !vm.submitting) {
                        vm.submit()
                    }
                }
            }
            .padding(.horizontal, MeiliMetric.screenH)
            .padding(.bottom, 28)
        }
        .scrollDismissesKeyboard(.interactively)
    }

    private func eventRow(_ ev: KpiEvent, selected: Bool) -> some View {
        Button { vm.pickEvent = ev } label: {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(ev.name).font(.sz(13, weight: .bold))
                        .foregroundStyle(selected ? MeiliColor.clayDeep : MeiliColor.ink)
                    if !ev.desc.isEmpty {
                        Text(ev.desc).font(MeiliFont.labelSm).foregroundStyle(MeiliColor.ink4)
                    }
                }
                Spacer()
                Text("\(ev.pts >= 0 ? "+" : "")\(ev.pts)分/\(ev.unit)")
                    .font(.sz(12.5, weight: .heavy))
                    .foregroundStyle(ev.pts >= 0 ? MeiliColor.leafText : MeiliColor.roseText)
            }
            .padding(.horizontal, MeiliMetric.s3).padding(.vertical, 10)
            .background(selected ? MeiliColor.clayTint : MeiliColor.surfaceSoft)
            .clipShape(RoundedRectangle(cornerRadius: MeiliRadius.sm, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: MeiliRadius.sm, style: .continuous)
                    .strokeBorder(selected ? MeiliColor.clay : MeiliColor.lineSoft,
                                  lineWidth: MeiliMetric.borderField)
            }
        }
        .buttonStyle(.plain)
    }

    // ---- 规则 ----
    private var rulesTab: some View {
        ScrollView {
            LazyVStack(spacing: MeiliMetric.cardGap) {
                ForEach(vm.cats) { cat in
                    KpiRuleCard(cat: cat)
                }
            }
            .padding(.horizontal, MeiliMetric.screenH)
            .padding(.bottom, 28)
        }
    }

    // ---- 我的记录 ----
    private var recordsTab: some View {
        ScrollView {
            LazyVStack(spacing: MeiliMetric.s2) {
                KpiRangePicker(range: $vm.range) { vm.loadRecords(page: 1) }
                if vm.recordsLoading {
                    HStack { Spacer(); ProgressView().tint(MeiliColor.clay); Spacer() }.padding(.top, 30)
                } else if let page = vm.recordsPage {
                    MeiliCard(tight: true) {
                        HStack {
                            Text("共 \(page.total) 条").font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3)
                            Spacer()
                            Text("合计 \(page.sumPts) 分")
                                .font(.sz(14, weight: .heavy))
                                .foregroundStyle(page.sumPts >= 0 ? MeiliColor.leafText : MeiliColor.roseText)
                        }
                    }
                    ForEach(page.items) { rec in
                        KpiRecordRow(rec: rec, dayTotal: page.dayTotals[rec.date]) {
                            vm.startEdit(rec)
                        } onWithdraw: {
                            vm.withdraw(rec)
                        }
                    }
                    if page.items.isEmpty {
                        Text("该区间没有记录").font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3)
                            .padding(.vertical, 24)
                    }
                    HStack {
                        MeiliButton("上一页", kind: .ghost, size: .xs, enabled: page.page > 1) {
                            vm.loadRecords(page: page.page - 1)
                        }
                        Spacer()
                        Text("第 \(page.page) 页").font(MeiliFont.labelSm).foregroundStyle(MeiliColor.ink3)
                        Spacer()
                        MeiliButton("下一页", kind: .ghost, size: .xs,
                                    enabled: page.page * page.pageSize < page.total) {
                            vm.loadRecords(page: page.page + 1)
                        }
                    }
                    .padding(.top, MeiliMetric.s2)
                }
            }
            .padding(.horizontal, MeiliMetric.screenH)
            .padding(.bottom, 28)
        }
    }
}

// ============================================================
// 共用小组件(员工端/管理端复用)
// ============================================================

/// 规则卡:五维类别 → 事件清单(分/单位/说明)。
struct KpiRuleCard: View {
    let cat: KpiCategory
    var trailing: ((KpiEvent) -> AnyView)? = nil

    var body: some View {
        MeiliCard(tight: true) {
            Text("\(cat.icon) \(cat.name)")
                .font(.sz(14, weight: .heavy))
                .foregroundStyle(Color(hexString: cat.color) ?? MeiliColor.ink)
                .padding(.bottom, 6)
            VStack(spacing: 8) {
                ForEach(cat.events) { ev in
                    HStack(alignment: .top) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(ev.name).font(.sz(13, weight: .bold)).foregroundStyle(MeiliColor.ink)
                            if !ev.desc.isEmpty {
                                Text(ev.desc).font(MeiliFont.labelSm).foregroundStyle(MeiliColor.ink4)
                            }
                        }
                        Spacer()
                        Text("\(ev.pts >= 0 ? "+" : "")\(ev.pts)/\(ev.unit)")
                            .font(.sz(12.5, weight: .heavy))
                            .foregroundStyle(ev.pts >= 0 ? MeiliColor.leafText : MeiliColor.roseText)
                        if let trailing { trailing(ev) }
                    }
                    .padding(.vertical, 2)
                    if ev.id != cat.events.last?.id {
                        Rectangle().fill(MeiliColor.lineSoft).frame(height: 1)
                    }
                }
            }
        }
    }
}

/// 区间快捷选择(本月/上月/近3月/今年/全部)。
struct KpiRangePicker: View {
    @Binding var range: KpiDateRange
    let onChange: () -> Void

    var body: some View {
        MeiliCard(tight: true) {
            OptionChips(options: KpiDateRange.presets.map(\.label),
                        selected: [range.label]) { label in
                if let r = KpiDateRange.presets.first(where: { $0.label == label }) {
                    range = r
                    onChange()
                }
            }
        }
    }
}

/// 记录行:日期/事件/数量/分值 + 修改/撤回。
struct KpiRecordRow: View {
    let rec: KpiRecord
    var dayTotal: Int?
    var showName: Bool = false
    var onEdit: (() -> Void)?
    var onWithdraw: (() -> Void)?

    @State private var confirmWithdraw = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(rec.date).font(.sz(12, weight: .bold)).foregroundStyle(MeiliColor.ink3)
                if showName {
                    Text(rec.name).font(.sz(12, weight: .bold)).foregroundStyle(MeiliColor.clayDeep)
                }
                if let dt = dayTotal {
                    Text("当日 \(dt) 分").font(MeiliFont.labelSm).foregroundStyle(MeiliColor.ink4)
                }
                Spacer()
                Text("\(rec.pts >= 0 ? "+" : "")\(rec.pts) 分")
                    .font(.sz(14, weight: .heavy))
                    .foregroundStyle(rec.pts >= 0 ? MeiliColor.leafText : MeiliColor.roseText)
            }
            Text("\(rec.category) · \(rec.event) ×\(rec.qty)")
                .font(.sz(13)).foregroundStyle(MeiliColor.ink)
            if !rec.note.isEmpty {
                Text(rec.note).font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3)
            }
            if onEdit != nil || onWithdraw != nil {
                HStack(spacing: MeiliMetric.s2) {
                    if let onEdit { MeiliButton("修改", kind: .ghost, size: .xs, action: onEdit) }
                    if onWithdraw != nil {
                        MeiliButton("撤回", kind: .ghost, size: .xs) { confirmWithdraw = true }
                    }
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
        .confirmationDialog("确认撤回这条提报?撤回后积分会扣除。", isPresented: $confirmWithdraw, titleVisibility: .visible) {
            Button("撤回", role: .destructive) { onWithdraw?() }
        }
    }
}

/// 日期区间预设。
struct KpiDateRange: Equatable {
    let label: String
    let start: String?
    let end: String?

    static var presets: [KpiDateRange] {
        let cal = Calendar.current
        let now = Date()
        let fmt = DateFormatter()
        fmt.dateFormat = "yyyy-MM-dd"
        func d(_ date: Date) -> String { fmt.string(from: date) }
        let monthStart = cal.date(from: cal.dateComponents([.year, .month], from: now))!
        let lastMonthStart = cal.date(byAdding: .month, value: -1, to: monthStart)!
        let lastMonthEnd = cal.date(byAdding: .day, value: -1, to: monthStart)!
        let threeMonthsAgo = cal.date(byAdding: .month, value: -3, to: now)!
        let yearStart = cal.date(from: cal.dateComponents([.year], from: now))!
        return [
            KpiDateRange(label: "本月", start: d(monthStart), end: d(now)),
            KpiDateRange(label: "上月", start: d(lastMonthStart), end: d(lastMonthEnd)),
            KpiDateRange(label: "近3月", start: d(threeMonthsAgo), end: d(now)),
            KpiDateRange(label: "今年", start: d(yearStart), end: d(now)),
            KpiDateRange(label: "全部", start: nil, end: nil),
        ]
    }
}

extension Color {
    /// "#RRGGBB" → Color(kpi 五维色)。
    init?(hexString: String) {
        var s = hexString.trimmingCharacters(in: .whitespaces)
        guard s.hasPrefix("#") else { return nil }
        s.removeFirst()
        guard let v = UInt(s, radix: 16), s.count == 6 else { return nil }
        self.init(hex: v)
    }
}

// ---- 修改提报 sheet ----
private struct KpiEditSheet: View {
    @ObservedObject var vm: KpiHomeViewModel
    let record: KpiRecord

    @State private var qty: Int
    @State private var note: String

    init(vm: KpiHomeViewModel, record: KpiRecord) {
        self.vm = vm
        self.record = record
        _qty = State(initialValue: record.qty)
        _note = State(initialValue: record.note)
    }

    var body: some View {
        ZStack {
            MeiliColor.bg.ignoresSafeArea()
            VStack(alignment: .leading, spacing: MeiliMetric.s3) {
                Text("修改提报").font(MeiliFont.sheetH3).foregroundStyle(MeiliColor.ink)
                    .padding(.top, 22)
                Text("\(record.date) · \(record.category) · \(record.event)")
                    .font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3)
                HStack {
                    Text("数量").font(.sz(13, weight: .bold)).foregroundStyle(MeiliColor.ink)
                    Spacer()
                    HStack(spacing: 14) {
                        MeiliButton("－", kind: .ghost, size: .xs, enabled: qty > 1) { qty -= 1 }
                        Text("\(qty)").font(.sz(16, weight: .bold)).foregroundStyle(MeiliColor.ink)
                        MeiliButton("＋", kind: .ghost, size: .xs, enabled: qty < 999) { qty += 1 }
                    }
                }
                PlainInput(placeholder: "备注(选填)", text: $note)
                MeiliButton("保存修改", block: true) {
                    vm.saveEdit(record: record, qty: qty, note: note)
                }
                Spacer()
            }
            .padding(.horizontal, MeiliMetric.screenH)
        }
        .presentationDetents([.medium])
        .presentationDragIndicator(.visible)
    }
}
