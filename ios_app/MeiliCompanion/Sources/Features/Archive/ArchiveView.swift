import SwiftUI

/// 「报告」屏(底栏报告 tab)。android 端对应 `ui/archive/ArchiveScreen.kt`。
///
/// 顾问只看自己名下接诊,每行 → 一份分析报告。单行:头像 + 姓名(+会员号) + 状态pill + 服务日期 + 综合分。
/// 顶部:刷新 + 设置;筛选:搜索(占满) + 日期 chip + 状态 chip。底部服务端分页。
/// 红线:对外只说「接诊 / 分析报告」,绝不出现录音字样。
struct ArchiveView: View {
    var onOpenReport: (Int) -> Void = { _ in }
    var onOpenSettings: () -> Void = {}

    @StateObject private var vm = ArchiveViewModel()
    @State private var showStatusSheet = false
    @State private var showDateSheet = false

    var body: some View {
        ScrollView {
            VStack(spacing: MeiliMetric.cardGap) {
                MeiliTopBar(title: "报告", subtitle: "你名下的接诊分析报告") {
                    MeiliButton("刷新", kind: .ghost, size: .small) { vm.refresh() }
                    TopBarIconButton(icon: MeiliIcons.settings) { onOpenSettings() }
                }
                filterRow
                content
            }
            .padding(.horizontal, MeiliMetric.screenH)
            .padding(.bottom, MeiliMetric.bottomNavInset)
        }
        .background(MeiliColor.bg)
        .scrollDismissesKeyboard(.interactively)
        .onAppear { vm.onAppear() }
        .sheet(isPresented: $showStatusSheet) { statusSheet }
        .sheet(isPresented: $showDateSheet) { dateSheet }
    }

    // MARK: 筛选行

    private var filterRow: some View {
        HStack(spacing: 7) {
            HStack(spacing: 8) {
                MeiliIcon(MeiliIcons.search, size: 18).foregroundStyle(MeiliColor.ink3)
                TextField("搜会员姓名 / 卡号",
                          text: Binding(get: { vm.customerQuery }, set: { vm.setCustomerQuery($0) }))
                    .font(MeiliFont.body).foregroundStyle(MeiliColor.ink).tint(MeiliColor.clay)
                    .autocorrectionDisabled()
                    .submitLabel(.search)
            }
            .padding(.horizontal, 14)
            .frame(height: 44)
            .background(MeiliColor.surface)
            .clipShape(Capsule())
            .overlay(Capsule().strokeBorder(MeiliColor.line, lineWidth: 1))

            chip(text: vm.date.map(shortDate) ?? "日期", leading: MeiliIcons.reception,
                 active: vm.date != nil) { showDateSheet = true }
            chip(text: activeStatusLabel ?? "状态",
                 trailing: activeStatusLabel == nil ? MeiliIcons.chevDown : nil,
                 active: activeStatusLabel != nil) { showStatusSheet = true }
        }
    }

    private func chip(text: String, leading: MeiliGlyph? = nil, trailing: MeiliGlyph? = nil,
                      active: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 5) {
                if let leading { MeiliIcon(leading, size: 14) }
                Text(text).font(.sz(12, weight: .bold)).lineLimit(1)
                if let trailing { MeiliIcon(trailing, size: 13) }
            }
            .padding(.horizontal, 14).padding(.vertical, 11)
            .foregroundStyle(active ? MeiliColor.clayDeep : MeiliColor.ink2)
            .background(active ? MeiliColor.clayTint : MeiliColor.surfaceSoft)
            .clipShape(Capsule())
            .overlay(Capsule().strokeBorder(active ? MeiliColor.claySoft : MeiliColor.line, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }

    private var activeStatusLabel: String? {
        reportStatusFilters.first { $0.value == vm.statusFilter && $0.value != nil }?.label
    }

    // MARK: 列表 / 状态

    @ViewBuilder private var content: some View {
        if vm.loading && vm.sessions.isEmpty {
            MeiliCard { inlineLoading("正在调取接诊记录…") }
        } else if let e = vm.error, vm.sessions.isEmpty {
            MeiliCard { emptyHint(icon: MeiliIcons.warn, title: "调取失败", sub: e) }
        } else if vm.sessions.isEmpty {
            MeiliCard {
                emptyHint(icon: MeiliIcons.album, title: "暂无接诊报告",
                          sub: vm.hasActiveFilter ? "换个会员姓名 / 卡号 / 日期 / 状态筛选试试"
                                                  : "完成接诊并绑定顾客后，会在这里看到分析报告")
            }
        } else {
            MeiliCard(tight: true) {
                VStack(spacing: 0) {
                    ForEach(Array(vm.sessions.enumerated()), id: \.offset) { idx, row in
                        if idx > 0 {
                            Rectangle().fill(MeiliColor.lineSoft).frame(height: 1)
                        }
                        reportRow(row)
                    }
                }
            }
            pager
        }
    }

    private func reportRow(_ row: SessionRow) -> some View {
        let name = (row.customer.flatMap { $0.isEmpty ? nil : $0 }) ?? "—"
        let (label, kind) = statusPill(row)
        return Button {
            if let id = row.id { onOpenReport(id) }
        } label: {
            HStack(spacing: 12) {
                MeiliAvatar(name: name)
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 7) {
                        Text(name).font(MeiliFont.rowTitle).foregroundStyle(MeiliColor.ink).lineLimit(1)
                        if let card = row.memberCard, !card.isEmpty {
                            Text(card).font(.sz(11)).foregroundStyle(MeiliColor.ink3).lineLimit(1)
                        }
                    }
                    HStack(spacing: 7) {
                        StatusPill(text: label, kind: kind)
                        if let d = row.serviceDate, !d.isEmpty {
                            Text(d).font(.sz(11)).foregroundStyle(MeiliColor.ink3).lineLimit(1)
                        }
                    }
                }
                Spacer(minLength: 8)
                reportScore(row)
            }
            .padding(.vertical, 14)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func reportScore(_ row: SessionRow) -> some View {
        let score = row.displayStatus == "done" ? row.overallScore : nil
        return Group {
            if let s = score {
                Text("\(Int(s.rounded()))")
                    .font(MeiliFont.serif(23)).foregroundStyle(MeiliColor.clay).lineLimit(1)
            } else {
                Text("—").font(.sz(14, weight: .bold)).foregroundStyle(MeiliColor.ink4)
            }
        }
        .frame(width: 40, height: 26)
    }

    private func statusPill(_ row: SessionRow) -> (String, PillKind) {
        switch row.displayStatus {
        case "done": return ("已完成", .ok)
        case "running": return ("分析中", .run)
        case "queued": return ("排队中", .run)
        case "failed", "stuck", "cancelled": return ("失败", .danger)
        default: return ("未分析", .neutral)
        }
    }

    private var pager: some View {
        HStack {
            MeiliButton("上一页", kind: .ghost, size: .xs, enabled: vm.page > 1) { vm.goPage(-1) }
            Text("第 \(vm.page) / \(vm.totalPages) 页 · 共 \(vm.total) 条")
                .font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3)
                .padding(.horizontal, 14)
            MeiliButton("下一页", kind: .ghost, size: .xs, enabled: vm.page < vm.totalPages) { vm.goPage(1) }
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 2)
    }

    // MARK: 状态筛选 sheet

    private var statusSheet: some View {
        VStack(alignment: .leading, spacing: 0) {
            sheetHeader("按状态筛选", "只看某种状态的报告。")
            ForEach(reportStatusFilters) { opt in
                radioRow(label: opt.label, selected: opt.value == vm.statusFilter) {
                    vm.setStatus(opt.value); showStatusSheet = false
                }
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(MeiliMetric.cardPad)
        .presentationDetents([.height(430)])
        .presentationDragIndicator(.visible)
    }

    private func radioRow(label: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                ZStack {
                    Circle()
                        .strokeBorder(selected ? .clear : MeiliColor.ink4, lineWidth: 2)
                        .background(Circle().fill(selected ? MeiliColor.clay : .clear))
                        .frame(width: 20, height: 20)
                    if selected { Circle().fill(.white).frame(width: 8, height: 8) }
                }
                Text(label).font(.sz(14, weight: .bold))
                    .foregroundStyle(selected ? MeiliColor.clayDeep : MeiliColor.ink)
                Spacer()
            }
            .padding(14)
            .background(selected ? MeiliColor.clayTint : MeiliColor.surface)
            .clipShape(RoundedRectangle(cornerRadius: MeiliRadius.sm, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: MeiliRadius.sm, style: .continuous)
                    .strokeBorder(selected ? MeiliColor.clay : MeiliColor.line, lineWidth: 1)
            }
        }
        .buttonStyle(.plain)
        .padding(.bottom, 10)
    }

    // MARK: 日期 sheet

    private var dateSheet: some View {
        DateFilterSheet(
            initial: vm.date,
            onPick: { vm.setDate($0); showDateSheet = false },
            onClear: { vm.setDate(nil); showDateSheet = false }
        )
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }

    // MARK: 公共小件

    private func sheetHeader(_ title: String, _ sub: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(MeiliFont.sheetH3).foregroundStyle(MeiliColor.ink)
            Text(sub).font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3)
        }
        .padding(.bottom, 16)
    }

    private func inlineLoading(_ text: String) -> some View {
        HStack(spacing: 10) {
            ProgressView().tint(MeiliColor.clay)
            Text(text).font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 22)
    }

    private func emptyHint(icon: MeiliGlyph, title: String, sub: String?) -> some View {
        VStack(spacing: 0) {
            MeiliIcon(icon, size: 30).foregroundStyle(MeiliColor.ink4)
                .frame(width: 58, height: 58)
                .background(MeiliColor.surfaceSoft)
                .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
            Text(title).font(.sz(14.5, weight: .bold)).foregroundStyle(MeiliColor.ink2)
                .padding(.top, 14)
            if let sub, !sub.isEmpty {
                Text(sub).font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3)
                    .multilineTextAlignment(.center).padding(.top, 6)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 36)
    }

    private func shortDate(_ date: String) -> String {
        let parts = date.split(separator: "-")
        return parts.count == 3 ? "\(parts[1])-\(parts[2])" : date
    }
}

/// 日期筛选 sheet:图形日历 + 确定/全部日期。
private struct DateFilterSheet: View {
    let initial: String?
    let onPick: (String) -> Void
    let onClear: () -> Void
    @State private var picked: Date = Date()

    var body: some View {
        VStack(spacing: 16) {
            Text("按服务日期筛选").font(MeiliFont.sheetH3).foregroundStyle(MeiliColor.ink)
                .frame(maxWidth: .infinity, alignment: .leading)
            DatePicker("", selection: $picked, displayedComponents: .date)
                .datePickerStyle(.graphical)
                .tint(MeiliColor.clay)
            HStack(spacing: 10) {
                MeiliButton("全部日期", kind: .ghost) { onClear() }
                MeiliButton("确定", block: true) { onPick(ymd(picked)) }
            }
        }
        .padding(MeiliMetric.cardPad)
        .onAppear { if let s = initial, let d = parseYMD(s) { picked = d } }
    }

    private func ymd(_ d: Date) -> String {
        let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd"; f.locale = Locale(identifier: "en_US_POSIX")
        return f.string(from: d)
    }
    private func parseYMD(_ s: String) -> Date? {
        let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd"; f.locale = Locale(identifier: "en_US_POSIX")
        return f.date(from: s)
    }
}
