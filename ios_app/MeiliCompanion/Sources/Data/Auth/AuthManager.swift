import Foundation

/// 登录态管理。android 端对应 `data/auth/AuthManager.kt`。
///
/// 登录走 POST /login(表单 username+password)。/login 失败也回 200 渲染 login.html,
/// 故不能只看 HTTP 码 —— 登录后统一用 GET /api/me 校验会话。
/// 成功后服务端 Set-Cookie,HTTPCookieStorage 自动持久化,无需手动存 token。
enum LoginResult {
    case success(Me)
    case invalidCredentials(String)
    case error(String)
}

struct AuthManager {
    private let api = APIClient.shared

    func login(username: String, password: String) async -> LoginResult {
        let user = username.trimmingCharacters(in: .whitespacesAndNewlines)
        do {
            _ = try await api.postFormStatus("login", fields: [
                "username": user,
                "password": password,
            ])
        } catch {
            return .error((error as? APIError)?.errorDescription ?? "网络异常")
        }
        switch await fetchMe() {
        case .ok(let me):
            // 拿到会话才存凭据 → 供后续 401 自动重登(顾问无感,对齐 android 2.0.57)。
            CredentialStore.save(username: user, password: password)
            return .success(me)   // 四类角色一律放行,进 App 后按 role 分流
        case .unauthorized:
            clearCookies()
            return .invalidCredentials("用户名或密码错误")
        case .failure(let msg):
            return .error(msg)
        }
    }

    /// 用 /api/me 实判登录态。返回 nil 表示未登录/异常。
    func currentUser() async -> Me? {
        if case .ok(let me) = await fetchMe() { return me }
        return nil
    }

    /// 退出登录:调用 /logout 并清空本地 Cookie(即便 /logout 失败也清本地)。
    func logout() async {
        _ = try? await api.getStatus("logout")
        clearCookies()
        CredentialStore.clear()   // 清缓存凭据,防退出后被 401 自动重登回去
    }

    private enum MeResult { case ok(Me); case unauthorized; case failure(String) }

    private func fetchMe() async -> MeResult {
        do {
            let me: Me = try await api.get("api/me")
            if me.error != nil || me.id == nil { return .unauthorized }
            return .ok(me)
        } catch let e as APIError {
            return e.isUnauthorized ? .unauthorized : .failure(e.errorDescription ?? "网络异常")
        } catch {
            return .failure("网络异常")
        }
    }

    func clearCookies() {
        let storage = HTTPCookieStorage.shared
        guard let host = api.baseURL.host else { return }
        storage.cookies?.forEach { c in
            let d = c.domain.hasPrefix(".") ? String(c.domain.dropFirst()) : c.domain
            if host == d || host.hasSuffix(d) || d.hasSuffix(host) { storage.deleteCookie(c) }
        }
    }
}
