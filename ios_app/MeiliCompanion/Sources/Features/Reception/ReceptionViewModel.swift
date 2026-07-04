import SwiftUI

/// 'yyyy-MM-dd' 日期工具(对齐 android ReceptionViewModel 的 Calendar 口径)。
enum DateHelper {
    private static var fmt: DateFormatter {
        let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd"; f.locale = Locale(identifier: "en_US_POSIX"); return f
    }
    static func today() -> String { fmt.string(from: Date()) }
    static func string(from d: Date) -> String { fmt.string(from: d) }
    static func date(from s: String) -> Date? { fmt.date(from: s) }
    static func addDays(_ date: String, _ delta: Int) -> String? {
        guard let d = fmt.date(from: date),
              let n = Calendar.current.date(byAdding: .day, value: delta, to: d) else { return nil }
        return fmt.string(from: n)
    }
    static func cmp(_ a: String, _ b: String) -> Int {
        guard let da = fmt.date(from: a), let db = fmt.date(from: b) else { return a < b ? -1 : (a > b ? 1 : 0) }
        return da < db ? -1 : (da > db ? 1 : 0)
    }
}

/// 今日接诊状态机。android 端对应 `ui/reception/ReceptionViewModel.kt`(今日接诊部分)。
/// 本轮:日期切换 + 列表 + 新增(搜已有/建新) + 移除。待整理/笔同步另起。
@MainActor
final class ReceptionViewModel: ObservableObject {
    @Published var date: String = DateHelper.today()
    @Published var items: [TodayReception] = []
    @Published var loading = false
    @Published var error: String?
    @Published var toast: String?

    // 新增弹层
    @Published var showAdd = false
    @Published var addExistingMode = true
    @Published var addQuery = ""
    @Published var addResults: [Customer] = []
    @Published var addSearching = false
    @Published var newName = ""
    @Published var newPhoneTail = ""
    @Published var newMemberCard = ""
    @Published var addSubmitting = false
    @Published var addError: String?

    private var loaded = false
    private var searchTask: Task<Void, Never>?
    let backfillDaysBack = 7

    var isToday: Bool { date == DateHelper.today() }
    var canPrev: Bool {
        guard let minDate = DateHelper.addDays(DateHelper.today(), -backfillDaysBack) else { return true }
        return DateHelper.cmp(date, minDate) > 0
    }

    func onAppear() {
        startAutoRefresh()
        guard !loaded else { return }
        loaded = true
        refresh()
    }

    // ── 自动刷新(F6,对齐 android:看"今天"时每 20s 静默刷新,分析状态不用手动重进)──
    private var autoTimer: Timer?

    deinit { autoTimer?.invalidate() }   // 复查 B10:VM 释放不留僵尸 Timer

    func startAutoRefresh() {
        stopAutoRefresh()
        autoTimer = Timer.scheduledTimer(withTimeInterval: 20, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self, self.isToday else { return }
                self.silentRefresh()
            }
        }
    }
    func stopAutoRefresh() { autoTimer?.invalidate(); autoTimer = nil }

    /// 静默刷新:不动 loading 标志,列表原地更新。
    private func silentRefresh() {
        Task {
            if let r = try? await ConsultantRepo.todayReception(date) {
                items = r.items ?? []
            }
        }
    }

    func refresh() {
        loading = true; error = nil
        Task {
            do {
                let r = try await ConsultantRepo.todayReception(date)
                items = r.items ?? []
                date = r.date ?? date
                loading = false
            } catch let err {
                loading = false
                error = (err as? APIError)?.errorDescription ?? "调取失败"
            }
        }
    }

    func prevDay() { if let d = DateHelper.addDays(date, -1) { select(d) } }
    func nextDay() { if !isToday, let d = DateHelper.addDays(date, 1) { select(d) } }
    func goToday() { select(DateHelper.today()) }
    /// 日历直选任意过去日期(不限 7 天;不选未来)。
    func selectDate(_ d: Date) {
        let s = DateHelper.string(from: d)
        guard DateHelper.cmp(s, DateHelper.today()) <= 0 else { return }
        select(s)
    }
    private func select(_ d: String) { guard d != date else { return }; date = d; refresh() }

    // 新增
    func openAdd() {
        addExistingMode = true; addQuery = ""; addResults = []; newName = ""; newPhoneTail = ""; newMemberCard = ""
        addError = nil; showAdd = true
    }
    func setAddQuery(_ q: String) {
        addQuery = q
        searchTask?.cancel()
        if q.trimmingCharacters(in: .whitespaces).isEmpty { addResults = []; return }
        searchTask = Task {
            try? await Task.sleep(nanoseconds: 300_000_000)
            if Task.isCancelled { return }
            addSearching = true
            addResults = (try? await ConsultantRepo.customerLookup(q)) ?? []
            addSearching = false
        }
    }
    func addExisting(_ c: Customer) {
        guard let cid = c.cid else { return }
        submitAdd { try await ConsultantRepo.addTodayReception(customerId: cid, date: self.date) }
    }
    func setNewPhoneTail(_ v: String) { newPhoneTail = String(v.filter(\.isNumber).prefix(4)) }
    func addNew() {
        let name = newName.trimmingCharacters(in: .whitespaces)
        guard !name.isEmpty else { addError = "请填写顾客姓名"; return }
        guard newPhoneTail.count == 4 else { addError = "请填写手机尾号（4 位数字）"; return }
        let card = newMemberCard.trimmingCharacters(in: .whitespaces).nilIfBlank
        submitAdd { try await ConsultantRepo.addTodayReception(name: name, phoneTail: self.newPhoneTail, memberCard: card, date: self.date) }
    }
    private func submitAdd(_ block: @escaping () async throws -> SimpleResult) {
        guard !addSubmitting else { return }
        addSubmitting = true; addError = nil
        Task {
            do {
                let r = try await block()
                addSubmitting = false
                if let e = r.error { addError = e; return }
                showAdd = false; toast = "已加入今日接诊"; refresh()
            } catch let err {
                addSubmitting = false
                addError = (err as? APIError)?.errorDescription ?? "加入失败"
            }
        }
    }

    // 移除
    func remove(_ item: TodayReception) {
        Task {
            do {
                let r = try await ConsultantRepo.removeTodayReception(item.id)
                toast = r.error ?? "已移出今日接诊"
                if r.error == nil { refresh() }
            } catch let err {
                toast = (err as? APIError)?.errorDescription ?? "移除失败"
            }
        }
    }
}

/// 某条接诊状态 → (文案, pill)。
extension TodayReception {
    var statusInfo: (String, PillKind) {
        switch analysisStatus {
        case "done": return ("已分析", .ok)
        case "running", "queued": return ("分析中", .run)
        case "failed", "stuck": return ("分析失败", .danger)
        case "cancelled": return ("已中断分析", .warn)   // C11:原来落"待分析",与预览页文案打架
        default:
            return (recordingCount ?? 0 > 0) ? ("待分析", .warn) : ("待陪伴", .neutral)
        }
    }
}
