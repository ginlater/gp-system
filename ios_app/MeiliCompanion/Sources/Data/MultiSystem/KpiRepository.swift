import Foundation

/// KPI 积分(kpi.beautyshining.com)桥接:把工牌顾问姓名映射到 KPI 员工的拼音账号,
/// 使「KPI积分」员工端网页壳能自动登录(统一密码 123456)。
/// android 端对应 `data/kpi/KpiModule.kt`。
///
/// 为什么要映射:KPI 员工账号是拼音(lilele 等),与工牌手机号账号无共同字段,
/// 唯一桥梁是中文姓名。姓名→账号 的对照由管理后台 /api/admin/employees 提供
/// (App 内置管理员账号,与 kpi_admin 格子同一份);走独立 URLSession(独立 cookie),
/// 不污染工牌/WebView 的会话。
enum KpiModule {
    static let base = URL(string: "https://kpi.beautyshining.com/")!
    static let adminUser = "diaojie"
    static let adminPass = "123456"
    static let staffPass = "123456"
}

final class KpiRepository {
    /// 姓名 → 拼音账号 映射缓存(进程内;logout 清)。
    nonisolated(unsafe) private static var nameToUsername: [String: String]?

    static func clearCache() { nameToUsername = nil }

    /// 独立 cookie 的 session(拉映射用管理员会话,与工牌/网页壳互不干扰)。
    private static let session: URLSession = {
        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = 30
        return URLSession(configuration: cfg)
    }()

    /// 返回该顾问对应的 KPI 拼音账号;查不到 / 拉取失败一律返回空串(降级到手动登录页,不报错)。
    func resolveUsername(_ advisorName: String?) async -> String {
        let name = advisorName?.trimmingCharacters(in: .whitespaces) ?? ""
        if name.isEmpty { return "" }
        if let map = KpiRepository.nameToUsername { return map[name] ?? "" }
        guard let map = await fetchMap() else { return "" }
        KpiRepository.nameToUsername = map
        return map[name] ?? ""
    }

    /// 管理员登录 + 拉员工名单,建 name→username。任何异常返回 nil(交由调用方降级)。
    private func fetchMap() async -> [String: String]? {
        let loginReq = SubsystemHTTP.jsonRequest(
            KpiModule.base.appendingPathComponent("api/admin/login"),
            body: ["username": KpiModule.adminUser, "password": KpiModule.adminPass])
        guard let (_, loginStatus) = try? await SubsystemHTTP.send(loginReq, session: KpiRepository.session),
              (200..<300).contains(loginStatus) else { return nil }

        var listReq = URLRequest(url: KpiModule.base.appendingPathComponent("api/admin/employees"))
        listReq.setValue("application/json", forHTTPHeaderField: "Accept")
        guard let (data, status) = try? await SubsystemHTTP.send(listReq, session: KpiRepository.session),
              (200..<300).contains(status),
              let list = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { return nil }

        var map: [String: String] = [:]
        for e in list {
            if let n = e["name"] as? String, !n.isEmpty,
               let u = e["username"] as? String, !u.isEmpty {
                map[n] = u
            }
        }
        return map
    }
}
