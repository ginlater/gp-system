import SwiftUI

/// 「客户详情」(美丽档案)状态机。android 端对应 `ui/customer/CustomerDetailViewModel.kt`。
/// profile / value 各自独立加载,互不阻塞。
@MainActor
final class CustomerDetailViewModel: ObservableObject {
    let customerId: Int

    @Published var profileLoading = false
    @Published var profileError: String?
    @Published var profile: CustomerProfileResponse?
    @Published var valueLoading = false
    @Published var valueGenerating = false
    @Published var valueError: String?
    @Published var value: CustomerValueResponse?

    private var loaded = false

    init(customerId: Int) { self.customerId = customerId }

    var hasValueContent: Bool { value?.content != nil && value?.error == nil }
    var valueDoneCount: Int { value?.doneCount ?? 0 }

    func onAppear() {
        guard !loaded else { return }
        loaded = true
        loadProfile()
        loadValue()
    }

    func loadProfile() {
        profileLoading = true; profileError = nil
        Task {
            do {
                let d = try await ConsultantRepo.customerProfile(customerId, range: "all")
                profileLoading = false
                if let e = d.error { profileError = e } else { profile = d }
            } catch let err {
                profileLoading = false
                profileError = (err as? APIError)?.errorDescription ?? "调取失败"
            }
        }
    }

    func loadValue() {
        valueLoading = true; valueError = nil
        Task {
            do {
                let d = try await ConsultantRepo.customerValue(customerId)
                valueLoading = false
                value = d
                if let e = d.error { valueError = e }
            } catch let err {
                valueLoading = false
                valueError = (err as? APIError)?.errorDescription ?? "调取失败"
            }
        }
    }

    func generateValue() {
        guard !valueGenerating else { return }
        valueGenerating = true; valueError = nil
        Task {
            do {
                let d = try await ConsultantRepo.generateCustomerValue(customerId)
                valueGenerating = false
                if let e = d.error { valueError = e } else { value = d }
            } catch let err {
                valueGenerating = false
                valueError = (err as? APIError)?.errorDescription ?? "生成失败"
            }
        }
    }
}
