import Foundation

/* ===================================================================
 * kpi-agent(kpi.beautyshining.com,店长晋升积分系统)原生数据层。
 * 2026-07-16 起 iOS 原生化(原网页壳下线);服务端 server.py 全契约核对:
 *  - 员工:POST /api/login {username,password} → Set-Cookie kpi_emp(7天,进程内 session,
 *    服务重启会话作废→靠 401 自动重登);GET /api/me {name};GET /api/config {cats};
 *    GET /api/records?start&end&page&page_size(name 服务端锁定为本人)
 *      → {total,sum_pts,page,page_size,items,day_totals};
 *    POST /api/records(pts 必须 = 规则分×数量,服务端校验);PUT /api/records/{id};
 *    POST /api/records/{id}/withdraw。
 *  - 管理:POST /api/admin/login → Cookie kpi_admin;GET /api/stats(看板);
 *    /api/records?name= 可查任何人;DELETE /api/admin/records/{id};
 *    /api/admin/employees 列表/新增/重置密码/停用;/api/admin/events 增改删。
 * 账号:员工=拼音(lilele 等)/初始密码 123456,老板=diaojie。
 * App 免登录:工牌顾问姓名 → 拼音账号(KpiRepository 映射)+ 统一初始密码。
 * =================================================================== */

// ---- 模型 ----

/// 五维类别(名称/色/icon + 事件清单)。
struct KpiCategory: Identifiable {
    var id: String { name }
    let name: String
    let color: String
    let icon: String
    let events: [KpiEvent]
}

struct KpiEvent: Identifiable {
    let id: Int
    let name: String
    let pts: Int
    let unit: String
    let desc: String

    init?(dict: [String: JSONValue]) {
        guard let id = dict["id"]?.doubleValue else { return nil }
        self.id = Int(id)
        name = dict["name"]?.stringValue ?? ""
        pts = Int(dict["pts"]?.doubleValue ?? 0)
        unit = dict["unit"]?.stringValue ?? "次"
        desc = dict["desc"]?.stringValue ?? ""
    }
}

struct KpiRecord: Identifiable {
    let id: Int
    var name: String
    var date: String
    var category: String
    var event: String
    var qty: Int
    var pts: Int
    var note: String

    init?(dict: [String: JSONValue]) {
        guard let id = dict["id"]?.doubleValue else { return nil }
        self.id = Int(id)
        name = dict["name"]?.stringValue ?? ""
        date = dict["date"]?.stringValue ?? ""
        category = dict["category"]?.stringValue ?? ""
        event = dict["event"]?.stringValue ?? ""
        qty = Int(dict["qty"]?.doubleValue ?? 1)
        pts = Int(dict["pts"]?.doubleValue ?? 0)
        note = dict["note"]?.stringValue ?? ""
    }
}

struct KpiRecordsPage {
    var total = 0
    var sumPts = 0
    var page = 1
    var pageSize = 20
    var items: [KpiRecord] = []
    var dayTotals: [String: Int] = [:]
}

struct KpiStatsRanking: Identifiable {
    var id: String { name }
    let name: String
    let total: Int
    let perCat: [String: Int]
}

struct KpiStats {
    var totalValidPts = 0
    var recordCount = 0
    var scoredEmployees = 0
    var todayCount = 0
    var ranking: [KpiStatsRanking] = []
}

struct KpiEmployee: Identifiable {
    let id: Int
    let name: String
    let username: String
    var active: Bool
    let recordCount: Int
    let totalPts: Int

    init?(dict: [String: JSONValue]) {
        guard let id = dict["id"]?.doubleValue else { return nil }
        self.id = Int(id)
        name = dict["name"]?.stringValue ?? ""
        username = dict["username"]?.stringValue ?? ""
        active = (dict["active"]?.doubleValue ?? 0) != 0
        recordCount = Int(dict["record_count"]?.doubleValue ?? 0)
        totalPts = Int(dict["total_pts"]?.doubleValue ?? 0)
    }
}

// ---- 会话核心(cookie;员工与管理是同域两个不同 cookie,互不干扰) ----

enum KpiAgentHTTP {
    static let base = URL(string: "https://kpi.beautyshining.com/")!

    /// 专用 session:cookie 落 shared storage(App 重启仍在;服务端重启才需重登)。
    static let session: URLSession = {
        let cfg = URLSessionConfiguration.default
        cfg.timeoutIntervalForRequest = 30
        cfg.httpCookieStorage = HTTPCookieStorage.shared
        cfg.requestCachePolicy = .reloadIgnoringLocalCacheData
        return URLSession(configuration: cfg)
    }()

    static func request(_ path: String, method: String = "GET", body: [String: Any]? = nil) -> URLRequest {
        var req = URLRequest(url: URL(string: path, relativeTo: base)!)
        req.httpMethod = method
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        if let body {
            req.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
            req.httpBody = try? JSONSerialization.data(withJSONObject: body)
        }
        return req
    }

    static func send(_ req: URLRequest) async throws -> (Data, Int) {
        do {
            let (data, resp) = try await session.data(for: req)
            return (data, (resp as? HTTPURLResponse)?.statusCode ?? 0)
        } catch {
            throw SubsystemError("网络异常：\(error.localizedDescription)")
        }
    }

    static func json(_ data: Data) -> [String: JSONValue] {
        (try? SubsystemHTTP.plainDecoder.decode([String: JSONValue].self, from: data)) ?? [:]
    }

    static func jsonList(_ data: Data) -> [[String: JSONValue]] {
        (try? SubsystemHTTP.plainDecoder.decode([[String: JSONValue]].self, from: data)) ?? []
    }

    static func errorText(_ data: Data) -> String? {
        SubsystemHTTP.errorText(data)   // kpi 用 {detail}
    }

    /// config 响应解析({cats:{类别:{color,icon,events[]}}};按五维固定顺序排)。
    static func parseConfig(_ data: Data) -> [KpiCategory] {
        guard case .object(let cats)? = json(data)["cats"] else { return [] }
        let order = ["个人业绩", "客户经营", "团队培养", "门店运营", "特殊贡献"]
        var out: [KpiCategory] = []
        for name in order {
            guard case .object(let c)? = cats[name] else { continue }
            let events: [KpiEvent]
            if case .array(let evs)? = c["events"] {
                events = evs.compactMap { v -> KpiEvent? in
                    if case .object(let d) = v { return KpiEvent(dict: d) }
                    return nil
                }
            } else { events = [] }
            out.append(KpiCategory(name: name,
                                   color: c["color"]?.stringValue ?? "#1D9E75",
                                   icon: c["icon"]?.stringValue ?? "⭐",
                                   events: events))
        }
        return out
    }

    static func parseRecordsPage(_ data: Data) -> KpiRecordsPage {
        let obj = json(data)
        var page = KpiRecordsPage()
        page.total = Int(obj["total"]?.doubleValue ?? 0)
        page.sumPts = Int(obj["sum_pts"]?.doubleValue ?? 0)
        page.page = Int(obj["page"]?.doubleValue ?? 1)
        page.pageSize = Int(obj["page_size"]?.doubleValue ?? 20)
        if case .array(let arr)? = obj["items"] {
            page.items = arr.compactMap { v in
                if case .object(let d) = v { return KpiRecord(dict: d) }
                return nil
            }
        }
        if case .object(let dt)? = obj["day_totals"] {
            for (k, v) in dt { page.dayTotals[k] = Int(v.doubleValue ?? 0) }
        }
        return page
    }
}

// ---- 员工端仓库 ----

/// 员工端:静默登录(姓名→拼音账号 + 初始密码)+ 记录 CRUD。
/// 密码被个人改过时静默登录失败 → UI 落手动登录表单(cookie 常驻后不再打扰)。
struct KpiEmployeeRepository {
    static let initialPassword = "123456"

    /// 探测会话:已登录返回姓名,否则 nil。
    func me() async -> String? {
        guard let (data, status) = try? await KpiAgentHTTP.send(KpiAgentHTTP.request("api/me")),
              status == 200 else { return nil }
        return KpiAgentHTTP.json(data)["name"]?.stringValue
    }

    /// 手动/静默登录。成功返回员工姓名。
    func login(username: String, password: String) async throws -> String {
        let (data, status) = try await KpiAgentHTTP.send(
            KpiAgentHTTP.request("api/login", method: "POST",
                                 body: ["username": username, "password": password]))
        guard status == 200 else {
            throw SubsystemError(KpiAgentHTTP.errorText(data) ?? "登录失败（HTTP \(status)）")
        }
        return KpiAgentHTTP.json(data)["name"]?.stringValue ?? username
    }

    /// 静默登录:工牌顾问姓名 → 拼音账号 + 初始密码。映射不到/密码改过返回 nil(UI 落手动登录)。
    func autoLogin() async -> String? {
        let username = await KpiRepository().resolveUsername(AuthManager.lastMe?.advisorName)
        guard !username.isEmpty else { return nil }
        return try? await login(username: username, password: Self.initialPassword)
    }

    func config() async throws -> [KpiCategory] {
        let (data, status) = try await KpiAgentHTTP.send(KpiAgentHTTP.request("api/config"))
        guard status == 200 else { throw SubsystemError(KpiAgentHTTP.errorText(data) ?? "加载规则失败") }
        return KpiAgentHTTP.parseConfig(data)
    }

    func records(start: String?, end: String?, page: Int, pageSize: Int = 20) async throws -> KpiRecordsPage {
        var path = "api/records?page=\(page)&page_size=\(pageSize)"
        if let start { path += "&start=\(start)" }
        if let end { path += "&end=\(end)" }
        let (data, status) = try await KpiAgentHTTP.send(KpiAgentHTTP.request(path))
        guard status == 200 else { throw SubsystemError(KpiAgentHTTP.errorText(data) ?? "加载记录失败") }
        return KpiAgentHTTP.parseRecordsPage(data)
    }

    /// 提报(pts = 规则分 × 数量,服务端二次校验)。
    func addRecord(date: String, category: String, event: KpiEvent, qty: Int, note: String) async throws {
        let body: [String: Any] = [
            "name": "", "date": date, "category": category, "event": event.name,
            "qty": qty, "pts": event.pts * qty, "note": note, "evidence": "",
            "ts": ISO8601DateFormatter().string(from: Date()),
        ]
        let (data, status) = try await KpiAgentHTTP.send(
            KpiAgentHTTP.request("api/records", method: "POST", body: body))
        guard status == 200 else { throw SubsystemError(KpiAgentHTTP.errorText(data) ?? "提交失败") }
    }

    func updateRecord(id: Int, date: String, category: String, event: KpiEvent, qty: Int, note: String) async throws {
        let body: [String: Any] = [
            "name": "", "date": date, "category": category, "event": event.name,
            "qty": qty, "pts": event.pts * qty, "note": note, "evidence": "",
            "ts": ISO8601DateFormatter().string(from: Date()),
        ]
        let (data, status) = try await KpiAgentHTTP.send(
            KpiAgentHTTP.request("api/records/\(id)", method: "PUT", body: body))
        guard status == 200 else { throw SubsystemError(KpiAgentHTTP.errorText(data) ?? "修改失败") }
    }

    func withdrawRecord(id: Int) async throws {
        let (data, status) = try await KpiAgentHTTP.send(
            KpiAgentHTTP.request("api/records/\(id)/withdraw", method: "POST", body: [:]))
        guard status == 200 else { throw SubsystemError(KpiAgentHTTP.errorText(data) ?? "撤回失败") }
    }
}

// ---- 管理端仓库 ----

/// 管理后台:固定管理员账号自动登录(与原网页壳注入同一份)。
struct KpiAdminRepository {
    /// 确保管理员会话:探 /api/stats,401 才登录(幂等)。
    func ensureLogin() async throws {
        let (_, status) = try await KpiAgentHTTP.send(KpiAgentHTTP.request("api/stats"))
        if status == 200 { return }
        let (data, s2) = try await KpiAgentHTTP.send(
            KpiAgentHTTP.request("api/admin/login", method: "POST",
                                 body: ["username": KpiModule.adminUser, "password": KpiModule.adminPass]))
        guard s2 == 200 else {
            throw SubsystemError(KpiAgentHTTP.errorText(data) ?? "管理员登录失败(密码可能已改),请联系工程师")
        }
    }

    func stats(start: String?, end: String?) async throws -> KpiStats {
        var path = "api/stats"
        var q: [String] = []
        if let start { q.append("start=\(start)") }
        if let end { q.append("end=\(end)") }
        if !q.isEmpty { path += "?" + q.joined(separator: "&") }
        let (data, status) = try await KpiAgentHTTP.send(KpiAgentHTTP.request(path))
        guard status == 200 else { throw SubsystemError(KpiAgentHTTP.errorText(data) ?? "加载看板失败") }
        let obj = KpiAgentHTTP.json(data)
        var s = KpiStats()
        s.totalValidPts = Int(obj["total_valid_pts"]?.doubleValue ?? 0)
        s.recordCount = Int(obj["record_count"]?.doubleValue ?? 0)
        s.scoredEmployees = Int(obj["scored_employees"]?.doubleValue ?? 0)
        s.todayCount = Int(obj["today_count"]?.doubleValue ?? 0)
        if case .array(let arr)? = obj["ranking"] {
            s.ranking = arr.compactMap { v in
                guard case .object(let d) = v else { return nil }
                var per: [String: Int] = [:]
                if case .object(let pc)? = d["per_cat"] {
                    for (k, x) in pc { per[k] = Int(x.doubleValue ?? 0) }
                }
                return KpiStatsRanking(name: d["name"]?.stringValue ?? "",
                                       total: Int(d["total"]?.doubleValue ?? 0),
                                       perCat: per)
            }
        }
        return s
    }

    /// 记录(管理员可按姓名查任何人;name=nil 查全部)。
    func records(name: String?, start: String?, end: String?, page: Int, pageSize: Int = 20) async throws -> KpiRecordsPage {
        var path = "api/records?page=\(page)&page_size=\(pageSize)"
        if let name, !name.isEmpty {
            path += "&name=\(name.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? name)"
        }
        if let start { path += "&start=\(start)" }
        if let end { path += "&end=\(end)" }
        let (data, status) = try await KpiAgentHTTP.send(KpiAgentHTTP.request(path))
        guard status == 200 else { throw SubsystemError(KpiAgentHTTP.errorText(data) ?? "加载记录失败") }
        return KpiAgentHTTP.parseRecordsPage(data)
    }

    func deleteRecord(id: Int) async throws {
        let (data, status) = try await KpiAgentHTTP.send(
            KpiAgentHTTP.request("api/admin/records/\(id)", method: "DELETE"))
        guard status == 200 else { throw SubsystemError(KpiAgentHTTP.errorText(data) ?? "删除失败") }
    }

    func employees() async throws -> [KpiEmployee] {
        let (data, status) = try await KpiAgentHTTP.send(KpiAgentHTTP.request("api/admin/employees"))
        guard status == 200 else { throw SubsystemError(KpiAgentHTTP.errorText(data) ?? "加载员工失败") }
        return KpiAgentHTTP.jsonList(data).compactMap(KpiEmployee.init(dict:))
    }

    func addEmployee(name: String, username: String, password: String) async throws {
        let (data, status) = try await KpiAgentHTTP.send(
            KpiAgentHTTP.request("api/admin/employees", method: "POST",
                                 body: ["name": name, "username": username, "password": password]))
        guard status == 200 else { throw SubsystemError(KpiAgentHTTP.errorText(data) ?? "新增失败") }
    }

    func resetEmployeePassword(id: Int, password: String) async throws {
        let (data, status) = try await KpiAgentHTTP.send(
            KpiAgentHTTP.request("api/admin/employees/\(id)/password", method: "POST",
                                 body: ["password": password]))
        guard status == 200 else { throw SubsystemError(KpiAgentHTTP.errorText(data) ?? "重置失败") }
    }

    func toggleEmployee(id: Int) async throws -> Bool {
        let (data, status) = try await KpiAgentHTTP.send(
            KpiAgentHTTP.request("api/admin/employees/\(id)/toggle", method: "POST", body: [:]))
        guard status == 200 else { throw SubsystemError(KpiAgentHTTP.errorText(data) ?? "操作失败") }
        return (KpiAgentHTTP.json(data)["active"]?.doubleValue ?? 0) != 0
    }

    func config() async throws -> [KpiCategory] {
        let (data, status) = try await KpiAgentHTTP.send(KpiAgentHTTP.request("api/config"))
        guard status == 200 else { throw SubsystemError(KpiAgentHTTP.errorText(data) ?? "加载规则失败") }
        return KpiAgentHTTP.parseConfig(data)
    }

    func addEvent(category: String, name: String, pts: Int, unit: String, desc: String) async throws {
        let (data, status) = try await KpiAgentHTTP.send(
            KpiAgentHTTP.request("api/admin/events", method: "POST",
                                 body: ["category": category, "name": name, "pts": pts, "unit": unit, "desc": desc]))
        guard status == 200 else { throw SubsystemError(KpiAgentHTTP.errorText(data) ?? "新增失败") }
    }

    func updateEvent(id: Int, name: String, pts: Int, unit: String, desc: String) async throws {
        let (data, status) = try await KpiAgentHTTP.send(
            KpiAgentHTTP.request("api/admin/events/\(id)", method: "PUT",
                                 body: ["name": name, "pts": pts, "unit": unit, "desc": desc]))
        guard status == 200 else { throw SubsystemError(KpiAgentHTTP.errorText(data) ?? "修改失败") }
    }

    func deleteEvent(id: Int) async throws {
        let (data, status) = try await KpiAgentHTTP.send(
            KpiAgentHTTP.request("api/admin/events/\(id)", method: "DELETE"))
        guard status == 200 else { throw SubsystemError(KpiAgentHTTP.errorText(data) ?? "删除失败") }
    }
}
