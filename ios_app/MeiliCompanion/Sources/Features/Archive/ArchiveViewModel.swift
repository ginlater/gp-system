import SwiftUI

/// 「报告」(美丽档案)状态机。android 端对应 `ui/archive/ArchiveViewModel.kt`。
/// 顾问只看自己名下接诊(服务端按 advisor_name 隔离),每行 → 一份分析报告。
@MainActor
final class ArchiveViewModel: ObservableObject {
    @Published var sessions: [SessionRow] = []
    @Published var total = 0
    @Published var page = 1
    @Published var statusFilter: String? = nil
    @Published var statusCounts: StatusCounts? = nil
    @Published var customerQuery = ""
    @Published var date: String? = nil
    @Published var loading = false
    @Published var error: String? = nil

    let pageSize = 10
    private var searchTask: Task<Void, Never>?
    private var loaded = false

    var totalPages: Int { total <= 0 ? 1 : (total + pageSize - 1) / pageSize }
    var hasActiveFilter: Bool { !customerQuery.isEmpty || statusFilter != nil || date != nil }

    func onAppear() {
        guard !loaded else { return }
        loaded = true
        load(1)
        loadCounts()
    }

    func load(_ toPage: Int) {
        page = toPage
        loading = true
        error = nil
        Task {
            do {
                let r = try await ConsultantRepo.sessions(
                    page: toPage, pageSize: pageSize,
                    customer: customerQuery, status: statusFilter, date: date)
                loading = false
                if let e = r.error { error = e; return }
                sessions = r.sessions ?? []
                total = r.total ?? 0
                page = r.page ?? toPage
            } catch let err {
                loading = false
                error = (err as? APIError)?.errorDescription ?? "调取失败"
            }
        }
    }

    func loadCounts() {
        Task {
            if let r = try? await ConsultantRepo.sessionStatusCounts(customer: customerQuery, date: date),
               r.error == nil {
                statusCounts = r.counts
            }
        }
    }

    func setStatus(_ bucket: String?) {
        guard bucket != statusFilter else { return }
        statusFilter = bucket
        load(1)
        loadCounts()
    }

    func setCustomerQuery(_ q: String) {
        customerQuery = q
        searchTask?.cancel()
        searchTask = Task {
            try? await Task.sleep(nanoseconds: 300_000_000)
            if Task.isCancelled { return }
            load(1)
            loadCounts()
        }
    }

    func setDate(_ d: String?) {
        date = (d?.isEmpty == true) ? nil : d
        load(1)
        loadCounts()
    }

    func goPage(_ delta: Int) {
        let next = min(max(1, page + delta), totalPages)
        if next != page { load(next) }
    }

    func refresh() {
        load(page)
        loadCounts()
    }
}

/// 状态筛选选项(对齐 android ReportStatusFilters)。
struct ReportStatusOption: Identifiable {
    let value: String?
    let label: String
    var id: String { value ?? "all" }
}

let reportStatusFilters: [ReportStatusOption] = [
    .init(value: nil, label: "全部"),
    .init(value: "done", label: "已完成"),
    .init(value: "queued", label: "排队中"),
    .init(value: "running", label: "分析中"),
    .init(value: "failed", label: "失败"),
]
