import SwiftUI

/// KPI 员工端 ViewModel:静默登录 → 规则/提报/记录。
@MainActor
final class KpiHomeViewModel: ObservableObject {
    enum Phase { case loading, manualLogin, ready }
    enum Tab { case submit, rules, records }

    @Published var phase: Phase = .loading
    @Published var tab: Tab = .submit
    @Published var empName = ""
    @Published var toast: String?

    // 手动登录兜底
    @Published var manualUser = ""
    @Published var manualPass = ""
    @Published var loggingIn = false
    @Published var loginError: String?

    // 规则(提报共用)
    @Published var cats: [KpiCategory] = []
    @Published var pickCat: KpiCategory?
    @Published var pickEvent: KpiEvent?
    @Published var qty = 1
    @Published var date = Date()
    @Published var note = ""
    @Published var submitting = false

    // 记录
    @Published var range = KpiDateRange.presets[0]
    @Published var recordsPage: KpiRecordsPage?
    @Published var recordsLoading = false
    @Published var editing: KpiRecord?

    private let repo = KpiEmployeeRepository()
    private var inited = false

    func initLoad() {
        if inited { return }
        inited = true
        Task {
            // 1) 现有 cookie 会话直接用;2) 静默登录;3) 落手动登录
            var name = await repo.me()
            if name == nil { name = await repo.autoLogin() }
            if let name {
                empName = name
                phase = .ready
                loadConfig()
            } else {
                phase = .manualLogin
            }
        }
    }

    func manualLogin() {
        loggingIn = true
        Task {
            do {
                empName = try await repo.login(username: manualUser.trimmingCharacters(in: .whitespaces),
                                               password: manualPass)
                phase = .ready
                loadConfig()
            } catch {
                loginError = (error as? SubsystemError)?.message ?? "登录失败"
            }
            loggingIn = false
        }
    }

    func loadConfig() {
        Task {
            do { cats = try await repo.config() }
            catch { toast = (error as? SubsystemError)?.message ?? "加载规则失败" }
        }
    }

    /// 类别 chips 用「icon 名称」做标签,点回来解析。
    func selectCategory(_ label: String) {
        guard let cat = cats.first(where: { "\($0.icon) \($0.name)" == label }) else { return }
        if pickCat?.name == cat.name {
            pickCat = nil
        } else {
            pickCat = cat
        }
        pickEvent = nil
        qty = 1
    }

    func submit() {
        guard let cat = pickCat, let ev = pickEvent, !submitting else { return }
        submitting = true
        let fmt = DateFormatter()
        fmt.dateFormat = "yyyy-MM-dd"
        Task {
            do {
                try await repo.addRecord(date: fmt.string(from: date), category: cat.name,
                                         event: ev, qty: qty, note: note)
                toast = "已提报:\(ev.name) ×\(qty),+\(ev.pts * qty) 分"
                pickEvent = nil
                qty = 1
                note = ""
                recordsPage = nil   // 下次进记录页重新拉
            } catch {
                toast = (error as? SubsystemError)?.message ?? "提交失败"
            }
            submitting = false
        }
    }

    func loadRecords(page: Int) {
        recordsLoading = recordsPage == nil
        Task {
            do {
                recordsPage = try await repo.records(start: range.start, end: range.end, page: page)
            } catch {
                toast = (error as? SubsystemError)?.message ?? "加载失败"
            }
            recordsLoading = false
        }
    }

    func startEdit(_ rec: KpiRecord) { editing = rec }

    func saveEdit(record: KpiRecord, qty: Int, note: String) {
        // 事件对象要从规则里找回(pts 校验需要规则分值)
        guard let cat = cats.first(where: { $0.name == record.category }),
              let ev = cat.events.first(where: { $0.name == record.event }) else {
            toast = "规则里找不到该事件,可能已被调整;请撤回后重新提报"
            return
        }
        Task {
            do {
                try await repo.updateRecord(id: record.id, date: record.date, category: record.category,
                                            event: ev, qty: qty, note: note)
                editing = nil
                toast = "已修改"
                loadRecords(page: recordsPage?.page ?? 1)
            } catch {
                toast = (error as? SubsystemError)?.message ?? "修改失败"
            }
        }
    }

    func withdraw(_ rec: KpiRecord) {
        Task {
            do {
                try await repo.withdrawRecord(id: rec.id)
                toast = "已撤回"
                loadRecords(page: recordsPage?.page ?? 1)
            } catch {
                toast = (error as? SubsystemError)?.message ?? "撤回失败"
            }
        }
    }
}
