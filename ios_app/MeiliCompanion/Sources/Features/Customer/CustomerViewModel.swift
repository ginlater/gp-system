import SwiftUI

/// 「客户」搜索屏状态机。android 端对应 `ui/customer/CustomerViewModel.kt`。
/// 进 tab 默认拉本人可见顾客(customer_lookup);搜索去抖 300ms → customers/search。
@MainActor
final class CustomerViewModel: ObservableObject {
    @Published var customers: [Customer] = []
    @Published var query = ""
    @Published var loading = false
    @Published var error: String?

    private var searchTask: Task<Void, Never>?
    private var loaded = false

    func onAppear() {
        guard !loaded else { return }
        loaded = true
        loadDefault()
    }

    func loadDefault() {
        loading = true; error = nil
        Task {
            do { customers = try await ConsultantRepo.customerLookup(nil); loading = false }
            catch let err { error = (err as? APIError)?.errorDescription ?? "查找失败"; loading = false }
        }
    }

    func setQuery(_ q: String) {
        query = q
        searchTask?.cancel()
        searchTask = Task {
            try? await Task.sleep(nanoseconds: 300_000_000)
            if Task.isCancelled { return }
            if q.trimmingCharacters(in: .whitespaces).isEmpty { loadDefault() } else { search(q) }
        }
    }

    private func search(_ q: String) {
        loading = true; error = nil
        Task {
            do { customers = try await ConsultantRepo.customersSearch(q); loading = false }
            catch let err { error = (err as? APIError)?.errorDescription ?? "查找失败"; loading = false }
        }
    }
}
