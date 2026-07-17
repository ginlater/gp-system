import SwiftUI

/// KPI 管理后台 ViewModel:自动登录 → 看板/记录/员工/规则。
@MainActor
final class KpiAdminViewModel: ObservableObject {
    enum Phase { case loading, error(String), ready }
    enum Tab { case stats, records, employees, rules }

    struct EventEditContext: Identifiable {
        var id: String { "\(category)-\(event?.id ?? -1)" }
        let category: String
        let event: KpiEvent?   // nil = 新增
    }

    @Published var phase: Phase = .loading
    @Published var tab: Tab = .stats
    @Published var toast: String?

    @Published var range = KpiDateRange.presets[0]
    @Published var stats: KpiStats?
    @Published var recordName = ""
    @Published var recordsPage: KpiRecordsPage?
    @Published var employees: [KpiEmployee] = []
    @Published var cats: [KpiCategory] = []

    @Published var addEmployeeOpen = false
    @Published var editingEvent: EventEditContext?

    private let repo = KpiAdminRepository()
    private var inited = false

    func initLoad(force: Bool = false) {
        if inited && !force { return }
        inited = true
        phase = .loading
        Task {
            do {
                try await repo.ensureLogin()
                phase = .ready
                loadStats()
                loadEmployees()   // 记录筛选/员工页共用
                loadConfig()
            } catch {
                phase = .error((error as? SubsystemError)?.message ?? "登录失败")
            }
        }
    }

    func switchTab(_ t: Tab) {
        tab = t
        switch t {
        case .stats: if stats == nil { loadStats() }
        case .records: if recordsPage == nil { loadRecords(page: 1) }
        case .employees: if employees.isEmpty { loadEmployees() }
        case .rules: if cats.isEmpty { loadConfig() }
        }
    }

    func loadStats() {
        stats = nil
        Task {
            do { stats = try await repo.stats(start: range.start, end: range.end) }
            catch { toast = (error as? SubsystemError)?.message ?? "加载看板失败" }
        }
    }

    func loadRecords(page: Int) {
        Task {
            do {
                recordsPage = try await repo.records(name: recordName.nilIfBlank, start: range.start,
                                                     end: range.end, page: page)
            } catch {
                toast = (error as? SubsystemError)?.message ?? "加载记录失败"
            }
        }
    }

    func deleteRecord(_ rec: KpiRecord) {
        Task {
            do {
                try await repo.deleteRecord(id: rec.id)
                toast = "已删除"
                loadRecords(page: recordsPage?.page ?? 1)
                stats = nil   // 看板数据失效
            } catch {
                toast = (error as? SubsystemError)?.message ?? "删除失败"
            }
        }
    }

    func loadEmployees() {
        Task {
            do { employees = try await repo.employees() }
            catch { toast = (error as? SubsystemError)?.message ?? "加载员工失败" }
        }
    }

    func addEmployee(name: String, username: String, password: String) {
        Task {
            do {
                try await repo.addEmployee(name: name.trimmingCharacters(in: .whitespaces),
                                           username: username.trimmingCharacters(in: .whitespaces),
                                           password: password)
                addEmployeeOpen = false
                toast = "已创建「\(name)」"
                loadEmployees()
            } catch {
                toast = (error as? SubsystemError)?.message ?? "创建失败"
            }
        }
    }

    func resetPassword(_ emp: KpiEmployee) {
        Task {
            do {
                try await repo.resetEmployeePassword(id: emp.id, password: "123456")
                toast = "「\(emp.name)」密码已重置为 123456"
            } catch {
                toast = (error as? SubsystemError)?.message ?? "重置失败"
            }
        }
    }

    func toggleEmployee(_ emp: KpiEmployee) {
        Task {
            do {
                _ = try await repo.toggleEmployee(id: emp.id)
                loadEmployees()
            } catch {
                toast = (error as? SubsystemError)?.message ?? "操作失败"
            }
        }
    }

    func loadConfig() {
        Task {
            do { cats = try await repo.config() }
            catch { toast = (error as? SubsystemError)?.message ?? "加载规则失败" }
        }
    }

    func saveEvent(context: EventEditContext, name: String, pts: Int, unit: String, desc: String) {
        Task {
            do {
                if let ev = context.event {
                    try await repo.updateEvent(id: ev.id, name: name, pts: pts, unit: unit, desc: desc)
                } else {
                    try await repo.addEvent(category: context.category, name: name, pts: pts, unit: unit, desc: desc)
                }
                editingEvent = nil
                toast = "已保存"
                loadConfig()
            } catch {
                toast = (error as? SubsystemError)?.message ?? "保存失败"
            }
        }
    }

    func deleteEvent(_ ev: KpiEvent) {
        Task {
            do {
                try await repo.deleteEvent(id: ev.id)
                editingEvent = nil
                toast = "已删除"
                loadConfig()
            } catch {
                toast = (error as? SubsystemError)?.message ?? "删除失败"
            }
        }
    }
}
