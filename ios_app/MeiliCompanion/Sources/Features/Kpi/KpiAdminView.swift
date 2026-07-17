import SwiftUI

/// KPI 记分考核·管理后台(原生,2026-07-16 起替代网页壳;老板用)。
/// 网页版 admin.html 的原生复刻:看板 / 记录管理 / 员工管理 / 积分规则 四段。
/// 登录:固定管理员账号自动登(与原网页壳注入同一份凭证)。
struct KpiAdminView: View {
    let onSwitchSystem: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @StateObject private var vm = KpiAdminViewModel()
    @State private var switcherOpen = false

    var body: some View {
        ZStack(alignment: .bottom) {
            MeiliColor.bg.ignoresSafeArea()
            VStack(spacing: 0) {
                MeiliTopBar(title: "KPI记分考核", subtitle: "记分考核 · 管理后台", onBack: { dismiss() }) {
                    TopBarIconButton(icon: MeiliIcons.workspace) { switcherOpen = true }
                }
                .padding(.horizontal, MeiliMetric.screenH)

                switch vm.phase {
                case .loading:
                    Spacer(); ProgressView().tint(MeiliColor.clay); Spacer()
                case .error(let msg):
                    TeachErrorRetry(message: msg) { vm.initLoad(force: true) }
                    Spacer()
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
            SystemSwitcherSheet(currentKey: "kpi_admin") { key in
                switcherOpen = false
                onSwitchSystem(key)
            }
        }
        .sheet(isPresented: $vm.addEmployeeOpen) { KpiAddEmployeeSheet(vm: vm) }
        .sheet(isPresented: Binding(get: { vm.editingEvent != nil },
                                    set: { if !$0 { vm.editingEvent = nil } })) {
            if let ctx = vm.editingEvent {
                KpiEventEditSheet(vm: vm, context: ctx)
            }
        }
    }

    private var segments: some View {
        HStack(spacing: MeiliMetric.s2) {
            seg("📊 看板", .stats)
            seg("🧾 记录", .records)
            seg("👥 员工", .employees)
            seg("📋 规则", .rules)
        }
        .padding(.horizontal, MeiliMetric.screenH)
        .padding(.vertical, 8)
    }

    private func seg(_ label: String, _ tab: KpiAdminViewModel.Tab) -> some View {
        let selected = vm.tab == tab
        return Button { vm.switchTab(tab) } label: {
            Text(label).font(.sz(12, weight: .bold))
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
        case .stats: statsTab
        case .records: recordsTab
        case .employees: employeesTab
        case .rules: rulesTab
        }
    }

    // ---- 看板 ----
    private var statsTab: some View {
        ScrollView {
            LazyVStack(spacing: MeiliMetric.s2) {
                KpiRangePicker(range: $vm.range) { vm.loadStats() }
                if let s = vm.stats {
                    HStack(spacing: MeiliMetric.s2) {
                        statTile("总有效分", "\(s.totalValidPts)", MeiliColor.leafText)
                        statTile("记录数", "\(s.recordCount)", MeiliColor.clayDeep)
                    }
                    HStack(spacing: MeiliMetric.s2) {
                        statTile("得分人数", "\(s.scoredEmployees)", MeiliColor.honeyText)
                        statTile("今日提报", "\(s.todayCount)", MeiliColor.sageDeep)
                    }
                    ForEach(Array(s.ranking.enumerated()), id: \.element.id) { i, r in
                        rankRow(index: i + 1, r: r)
                    }
                } else {
                    HStack { Spacer(); ProgressView().tint(MeiliColor.clay); Spacer() }.padding(.top, 30)
                }
            }
            .padding(.horizontal, MeiliMetric.screenH)
            .padding(.bottom, 28)
        }
    }

    private func statTile(_ label: String, _ value: String, _ color: Color) -> some View {
        MeiliCard(tight: true) {
            Text(value).font(MeiliFont.summaryBig).foregroundStyle(color)
            Text(label).font(MeiliFont.labelSm).foregroundStyle(MeiliColor.ink3)
        }
    }

    private func rankRow(index: Int, r: KpiStatsRanking) -> some View {
        MeiliCard(tight: true) {
            HStack {
                Text("\(index)").font(.sz(15, weight: .heavy))
                    .foregroundStyle(index <= 3 ? MeiliColor.honeyText : MeiliColor.ink3)
                    .frame(width: 26)
                Text(r.name).font(.sz(14, weight: .bold)).foregroundStyle(MeiliColor.ink)
                Spacer()
                Text("\(r.total) 分").font(.sz(15, weight: .heavy))
                    .foregroundStyle(r.total >= 0 ? MeiliColor.leafText : MeiliColor.roseText)
            }
            // 五维分布
            FlowWrap(spacing: 6) {
                ForEach(r.perCat.sorted(by: { $0.key < $1.key }), id: \.key) { cat, pts in
                    if pts != 0 {
                        Text("\(cat) \(pts)")
                            .font(MeiliFont.labelSm).foregroundStyle(MeiliColor.ink3)
                            .padding(.horizontal, 8).padding(.vertical, 3)
                            .background(MeiliColor.surfaceSoft).clipShape(Capsule())
                    }
                }
            }
            .padding(.top, 4)
        }
    }

    // ---- 记录管理 ----
    private var recordsTab: some View {
        ScrollView {
            LazyVStack(spacing: MeiliMetric.s2) {
                MeiliCard(tight: true) {
                    FieldLabelRow(text: "按员工筛选", required: false, note: nil)
                    OptionChips(options: ["全部"] + vm.employees.map(\.name),
                                selected: [vm.recordName.isEmpty ? "全部" : vm.recordName]) { pick in
                        vm.recordName = pick == "全部" ? "" : pick
                        vm.loadRecords(page: 1)
                    }
                }
                KpiRangePicker(range: $vm.range) { vm.loadRecords(page: 1) }
                if let page = vm.recordsPage {
                    MeiliCard(tight: true) {
                        HStack {
                            Text("共 \(page.total) 条").font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3)
                            Spacer()
                            Text("合计 \(page.sumPts) 分").font(.sz(14, weight: .heavy))
                                .foregroundStyle(page.sumPts >= 0 ? MeiliColor.leafText : MeiliColor.roseText)
                        }
                    }
                    ForEach(page.items) { rec in
                        KpiAdminRecordRow(rec: rec) { vm.deleteRecord(rec) }
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
                } else {
                    HStack { Spacer(); ProgressView().tint(MeiliColor.clay); Spacer() }.padding(.top, 30)
                }
            }
            .padding(.horizontal, MeiliMetric.screenH)
            .padding(.bottom, 28)
        }
    }

    // ---- 员工管理 ----
    private var employeesTab: some View {
        ScrollView {
            LazyVStack(spacing: MeiliMetric.s2) {
                MeiliButton("＋ 新增员工", kind: .soft, block: true) { vm.addEmployeeOpen = true }
                ForEach(vm.employees) { emp in
                    KpiEmployeeRow(emp: emp,
                                   onToggle: { vm.toggleEmployee(emp) },
                                   onResetPw: { vm.resetPassword(emp) })
                }
            }
            .padding(.horizontal, MeiliMetric.screenH)
            .padding(.bottom, 28)
        }
    }

    // ---- 规则管理 ----
    private var rulesTab: some View {
        ScrollView {
            LazyVStack(spacing: MeiliMetric.cardGap) {
                ForEach(vm.cats) { cat in
                    VStack(spacing: MeiliMetric.s2) {
                        KpiRuleCard(cat: cat, trailing: { ev in
                            AnyView(
                                Button {
                                    vm.editingEvent = KpiAdminViewModel.EventEditContext(category: cat.name, event: ev)
                                } label: {
                                    MeiliIcon(MeiliIcons.pen, size: 15).foregroundStyle(MeiliColor.ink4)
                                }
                                .buttonStyle(.plain)
                                .padding(.leading, 6)
                            )
                        })
                        MeiliButton("＋ 在「\(cat.name)」下加事件", kind: .ghost, size: .xs) {
                            vm.editingEvent = KpiAdminViewModel.EventEditContext(category: cat.name, event: nil)
                        }
                    }
                }
            }
            .padding(.horizontal, MeiliMetric.screenH)
            .padding(.bottom, 28)
        }
    }
}

// ---- 行组件 ----

private struct KpiAdminRecordRow: View {
    let rec: KpiRecord
    let onDelete: () -> Void
    @State private var confirm = false

    var body: some View {
        KpiRecordRow(rec: rec, dayTotal: nil, showName: true)
            .overlay(alignment: .bottomTrailing) {
                Button { confirm = true } label: {
                    MeiliIcon(MeiliIcons.trash, size: 16).foregroundStyle(MeiliColor.roseText)
                        .padding(10)
                }
                .buttonStyle(.plain)
            }
            .confirmationDialog("删除这条记录?(不可恢复)", isPresented: $confirm, titleVisibility: .visible) {
                Button("删除", role: .destructive) { onDelete() }
            }
    }
}

private struct KpiEmployeeRow: View {
    let emp: KpiEmployee
    let onToggle: () -> Void
    let onResetPw: () -> Void
    @State private var confirmToggle = false

    var body: some View {
        MeiliCard(tight: true) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text(emp.name).font(.sz(14, weight: .bold)).foregroundStyle(MeiliColor.ink)
                        StatusPill(text: emp.active ? "在职" : "停用",
                                   kind: emp.active ? .ok : .neutral)
                    }
                    Text("\(emp.username) · \(emp.recordCount) 条记录 · 累计 \(emp.totalPts) 分")
                        .font(MeiliFont.labelSm).foregroundStyle(MeiliColor.ink4)
                }
                Spacer()
            }
            HStack(spacing: MeiliMetric.s2) {
                MeiliButton("重置密码为123456", kind: .ghost, size: .xs, action: onResetPw)
                MeiliButton(emp.active ? "停用" : "启用", kind: .ghost, size: .xs) { confirmToggle = true }
            }
            .padding(.top, 6)
        }
        .confirmationDialog(emp.active ? "停用「\(emp.name)」?停用后不能登录和提报。" : "启用「\(emp.name)」?",
                            isPresented: $confirmToggle, titleVisibility: .visible) {
            Button(emp.active ? "停用" : "启用", role: emp.active ? .destructive : nil) { onToggle() }
        }
    }
}

// ---- 新增员工 sheet ----
private struct KpiAddEmployeeSheet: View {
    @ObservedObject var vm: KpiAdminViewModel
    @State private var name = ""
    @State private var username = ""
    @State private var password = "123456"

    var body: some View {
        ZStack {
            MeiliColor.bg.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: MeiliMetric.s2) {
                    Text("新增员工").font(MeiliFont.sheetH3).foregroundStyle(MeiliColor.ink)
                        .padding(.top, 22)
                    Text("姓名要与工牌顾问姓名一致,App 才能自动登录")
                        .font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3)
                        .padding(.bottom, 8)
                    MeiliField(label: "姓名", text: $name, placeholder: "如 李乐乐")
                    MeiliField(label: "账号(拼音)", text: $username, placeholder: "如 lilele")
                    MeiliField(label: "初始密码", text: $password, placeholder: "至少 6 位")
                    MeiliButton("创建", block: true,
                                enabled: !name.isEmpty && username.count >= 2 && password.count >= 6) {
                        vm.addEmployee(name: name, username: username, password: password)
                    }
                    .padding(.top, MeiliMetric.s2)
                }
                .padding(.horizontal, MeiliMetric.screenH)
            }
            .scrollDismissesKeyboard(.interactively)
        }
        .presentationDetents([.medium])
        .presentationDragIndicator(.visible)
    }
}

// ---- 事件新增/编辑 sheet ----
private struct KpiEventEditSheet: View {
    @ObservedObject var vm: KpiAdminViewModel
    let context: KpiAdminViewModel.EventEditContext

    @State private var name: String
    @State private var pts: String
    @State private var unit: String
    @State private var desc: String
    @State private var confirmDelete = false

    init(vm: KpiAdminViewModel, context: KpiAdminViewModel.EventEditContext) {
        self.vm = vm
        self.context = context
        _name = State(initialValue: context.event?.name ?? "")
        _pts = State(initialValue: context.event.map { String($0.pts) } ?? "")
        _unit = State(initialValue: context.event?.unit ?? "次")
        _desc = State(initialValue: context.event?.desc ?? "")
    }

    var body: some View {
        ZStack {
            MeiliColor.bg.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: MeiliMetric.s2) {
                    Text(context.event == nil ? "新增事件 · \(context.category)" : "编辑事件 · \(context.category)")
                        .font(MeiliFont.sheetH3).foregroundStyle(MeiliColor.ink)
                        .padding(.top, 22).padding(.bottom, 6)
                    MeiliField(label: "事件名称", text: $name, placeholder: "如 新客成交")
                    MeiliField(label: "分值(负数=扣分)", text: $pts, placeholder: "如 10 或 -10", keyboard: .numbersAndPunctuation)
                    MeiliField(label: "单位", text: $unit, placeholder: "次 / 单 / 人 / 个")
                    MeiliField(label: "说明", text: $desc, placeholder: "选填")
                    MeiliButton("保存", block: true,
                                enabled: !name.isEmpty && Int(pts) != nil && !unit.isEmpty) {
                        vm.saveEvent(context: context, name: name, pts: Int(pts) ?? 0, unit: unit, desc: desc)
                    }
                    .padding(.top, MeiliMetric.s2)
                    if context.event != nil {
                        MeiliButton("删除该事件", kind: .ghost, block: true) { confirmDelete = true }
                    }
                }
                .padding(.horizontal, MeiliMetric.screenH)
            }
            .scrollDismissesKeyboard(.interactively)
        }
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
        .confirmationDialog("删除「\(name)」?已提报的历史记录不受影响。",
                            isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("删除", role: .destructive) {
                if let ev = context.event { vm.deleteEvent(ev) }
            }
        }
    }
}
