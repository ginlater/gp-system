import SwiftUI

/// 登录状态机。android 端对应 `ui/login/LoginViewModel.kt`。
@MainActor
final class LoginViewModel: ObservableObject {
    @Published var username = ""
    @Published var password = ""
    @Published var loading = false
    @Published var error: String?

    private let auth = AuthManager()
    var onSuccess: ((Me) -> Void)?

    var canSubmit: Bool {
        !loading
            && !username.trimmingCharacters(in: .whitespaces).isEmpty
            && !password.isEmpty
    }

    func login() async {
        guard canSubmit else { return }
        loading = true
        error = nil
        let result = await auth.login(username: username, password: password)
        loading = false
        switch result {
        case .success(let me): onSuccess?(me)
        case .invalidCredentials(let m): error = m
        case .error(let m): error = m
        }
    }
}
