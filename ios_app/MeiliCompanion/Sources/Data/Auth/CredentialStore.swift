import Foundation
import Security

/// 登录凭据本地缓存(供 401 自动重登,顾问无感)。android 端对应 `data/auth/CredentialStore.kt`。
/// android 那边用 XOR+Base64 混淆 SharedPreferences(避开部分 ROM 的 Keystore 崩);
/// iOS 直接用 Keychain(系统级加密,稳定),`AfterFirstUnlock` 让锁屏后台上传也能读凭据重登。
enum CredentialStore {
    private static let service = "com.aibeautyfulwomen.gongpai.ios.cred"
    private static let kUser = "u"
    private static let kPass = "p"

    /// 登录成功后保存(供后续 401 静默重登)。
    static func save(username: String, password: String) {
        set(kUser, username)
        set(kPass, password)
    }

    /// 取出缓存凭据;任一为空返回 nil。
    static func load() -> (u: String, p: String)? {
        guard let u = get(kUser), !u.isEmpty,
              let p = get(kPass), !p.isEmpty else { return nil }
        return (u, p)
    }

    static var hasCredentials: Bool { load() != nil }

    /// 退出登录时清除。
    static func clear() {
        delete(kUser)
        delete(kPass)
    }

    // ── Keychain 原语 ──

    private static func set(_ account: String, _ value: String) {
        delete(account)
        let q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecValueData as String: Data(value.utf8),
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock,
        ]
        SecItemAdd(q as CFDictionary, nil)
    }

    private static func get(_ account: String) -> String? {
        let q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess,
              let d = out as? Data else { return nil }
        return String(data: d, encoding: .utf8)
    }

    private static func delete(_ account: String) {
        SecItemDelete([
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ] as CFDictionary)
    }
}
