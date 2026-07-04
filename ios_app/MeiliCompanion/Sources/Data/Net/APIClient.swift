import Foundation
import os

/// 网络核心。android 端对应 `data/net/NetworkModule.kt`(OkHttp+Retrofit+Moshi)。
///
/// - base = https://gp.aibeautyfulwomen.com(SPEC §7,不改后端)。
/// - 会话鉴权:`HTTPCookieStorage.shared` 自动持久化 Cookie(等价 android 的 PrefsCookieJar);
///   登录后系统自动带,App 重启仍在(持久 cookie 落盘)。
/// - JSON 解析:`.convertFromSnakeCase`,故模型用 camelCase 即可对上后端 snake_case key。
enum APIError: LocalizedError {
    case transport(Error)
    case http(status: Int, data: Data)
    case decoding(Error)
    case noResponse

    var isUnauthorized: Bool {
        if case .http(let s, _) = self { return s == 401 }
        return false
    }
    var errorDescription: String? {
        switch self {
        case .transport(let e): return "网络异常：\(e.localizedDescription)"
        case .http(let s, let d):
            // 服务端业务报错(400/409 带 {"error":"..."})直接给用户看真话——
            // 此前一律吞成"服务异常:HTTP n",隔天绑定被7天窗口拒绝时用户完全不知道原因
            if let obj = try? JSONSerialization.jsonObject(with: d) as? [String: Any],
               let e = obj["error"] as? String, !e.isEmpty {
                return e
            }
            return "服务异常：HTTP \(s)"
        case .decoding: return "数据解析失败"
        case .noResponse: return "无响应"
        }
    }
}

final class APIClient {
    static let shared = APIClient()

    let baseURL = URL(string: "https://gp.aibeautyfulwomen.com/")!
    private let session: URLSession
    let decoder: JSONDecoder

    // 401 自动重登的单飞护栏(对齐 android `reAuthing` volatile flag):
    // 并发多个请求同时 401 时共享同一次重登,避免重复打 /login。
    private let reauthSlot = OSAllocatedUnfairLock<Task<Bool, Never>?>(initialState: nil)

    init() {
        let cfg = URLSessionConfiguration.default
        cfg.httpCookieStorage = HTTPCookieStorage.shared
        cfg.httpCookieAcceptPolicy = .always
        cfg.httpShouldSetCookies = true
        cfg.timeoutIntervalForRequest = 60
        cfg.timeoutIntervalForResource = 180   // 上传音频留足时间
        cfg.requestCachePolicy = .reloadIgnoringLocalCacheData
        session = URLSession(configuration: cfg)

        decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
    }

    // ───────────── URL 构造 ─────────────

    private func makeURL(_ path: String, query: [URLQueryItem]) -> URL {
        let base = baseURL.appendingPathComponent(path)
        guard !query.isEmpty,
              var comps = URLComponents(url: base, resolvingAgainstBaseURL: false) else { return base }
        comps.queryItems = query
        return comps.url ?? base
    }

    /// 把 [key: value?] 过滤掉 nil 后转 query items。
    static func q(_ dict: [String: String?]) -> [URLQueryItem] {
        dict.compactMap { k, v in v.map { URLQueryItem(name: k, value: $0) } }
    }

    // ───────────── 底层发送 ─────────────

    private func dataResponse(_ req: URLRequest) async throws -> (Data, HTTPURLResponse) {
        do {
            let (data, resp) = try await session.data(for: req)
            guard let http = resp as? HTTPURLResponse else { throw APIError.noResponse }
            return (data, http)
        } catch let e as APIError {
            throw e
        } catch {
            throw APIError.transport(error)
        }
    }

    private func decode<T: Decodable>(_ data: Data) throws -> T {
        do { return try decoder.decode(T.self, from: data) }
        catch { throw APIError.decoding(error) }
    }

    /// 发送并解码 2xx;非 2xx 抛 `.http(status,data)`(供上层识别 401 等)。
    /// 遇 401 且本地有缓存凭据 → 静默重登一次并重放原请求(顾问无感,对齐 android 2.0.57)。
    private func send<T: Decodable>(_ req: URLRequest) async throws -> T {
        let (data, http) = try await dataResponse(req)
        if http.statusCode == 401, CredentialStore.hasCredentials {
            if await reauthenticateOnce() {
                // 重登成功 → 用新 cookie 重放原请求一次(不再二次重登,防循环)。
                let (data2, http2) = try await dataResponse(req)
                guard (200..<300).contains(http2.statusCode) else {
                    throw APIError.http(status: http2.statusCode, data: data2)
                }
                return try decode(data2)
            }
        }
        guard (200..<300).contains(http.statusCode) else {
            throw APIError.http(status: http.statusCode, data: data)
        }
        return try decode(data)
    }

    // ───────────── 401 自动重登 ─────────────

    /// 单飞:并发 401 共享一次重登,返回是否成功。失败=无凭据/重登也被拒(交由上层落登录页)。
    private func reauthenticateOnce() async -> Bool {
        // 锁内决定:已有在飞的就复用,否则建一个并放进槽位(读+建原子完成,无竞态)。
        let task: Task<Bool, Never> = reauthSlot.withLock { slot in
            if let t = slot { return t }
            let t = Task { await self.performReauth() }
            slot = t
            return t
        }
        let ok = await task.value
        // 该批重登完成 → 清空槽位(此刻 slot 必为本 task,无条件清安全)。
        reauthSlot.withLock { $0 = nil }
        return ok
    }

    /// 用本地凭据重新 POST /login,再 GET /api/me 验证会话(均直发,绕过本方法防递归)。
    private func performReauth() async -> Bool {
        guard let cred = CredentialStore.load() else { return false }
        // /login 失败也可能回 200,不据此判定;统一靠 /api/me 验证。
        _ = try? await dataResponse(formRequest("login", method: "POST",
            fields: ["username": cred.u, "password": cred.p]))
        var meReq = URLRequest(url: makeURL("api/me", query: []))
        meReq.setValue("application/json", forHTTPHeaderField: "Accept")
        guard let (data, http) = try? await dataResponse(meReq),
              (200..<300).contains(http.statusCode),
              let me = try? decoder.decode(Me.self, from: data),
              me.error == nil, me.id != nil else { return false }
        return true
    }

    // ───────────── GET ─────────────

    func get<T: Decodable>(_ path: String, query: [URLQueryItem] = []) async throws -> T {
        var req = URLRequest(url: makeURL(path, query: query))
        req.httpMethod = "GET"
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        return try await send(req)
    }

    /// 仅发起 GET 取状态码,丢弃 body(用于 /logout 等返回 HTML 的端点)。
    @discardableResult
    func getStatus(_ path: String, query: [URLQueryItem] = []) async throws -> Int {
        var req = URLRequest(url: makeURL(path, query: query))
        req.httpMethod = "GET"
        let (_, http) = try await dataResponse(req)
        return http.statusCode
    }

    // ───────────── POST 表单(x-www-form-urlencoded) ─────────────

    private func formRequest(_ path: String, method: String, fields: [String: String?]) -> URLRequest {
        var req = URLRequest(url: makeURL(path, query: []))
        req.httpMethod = method
        req.setValue("application/x-www-form-urlencoded; charset=utf-8", forHTTPHeaderField: "Content-Type")
        let body = fields.compactMap { k, v -> String? in
            guard let v else { return nil }
            let ek = k.addingPercentEncoding(withAllowedCharacters: .urlFormValue) ?? k
            let ev = v.addingPercentEncoding(withAllowedCharacters: .urlFormValue) ?? v
            return "\(ek)=\(ev)"
        }.joined(separator: "&")
        req.httpBody = body.data(using: .utf8)
        return req
    }

    func postForm<T: Decodable>(_ path: String, fields: [String: String?]) async throws -> T {
        try await send(formRequest(path, method: "POST", fields: fields))
    }

    /// 仅取状态码(用于 /login:失败也回 200,真正校验靠 /api/me)。
    @discardableResult
    func postFormStatus(_ path: String, fields: [String: String?]) async throws -> Int {
        let (_, http) = try await dataResponse(formRequest(path, method: "POST", fields: fields))
        return http.statusCode
    }

    // ───────────── POST / PATCH / DELETE JSON ─────────────

    func sendJSON<T: Decodable, B: Encodable>(_ path: String, method: String = "POST", body: B?) async throws -> T {
        var req = URLRequest(url: makeURL(path, query: []))
        req.httpMethod = method
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        if let body {
            let enc = JSONEncoder()
            enc.keyEncodingStrategy = .convertToSnakeCase
            req.httpBody = try enc.encode(body)
        }
        return try await send(req)
    }

    func postJSON<T: Decodable, B: Encodable>(_ path: String, body: B) async throws -> T {
        try await sendJSON(path, method: "POST", body: body)
    }

    // ───────────── 上传音频(multipart/form-data) ─────────────

    func uploadMultipart<T: Decodable>(_ path: String, fileURL: URL, fileField: String = "file",
                                       contentType: String = "audio/m4a",
                                       fields: [String: String?]) async throws -> T {
        var req = URLRequest(url: makeURL(path, query: []))
        req.httpMethod = "POST"
        let boundary = "Boundary-\(UUID().uuidString)"
        req.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        var body = Data()
        func append(_ s: String) { body.append(s.data(using: .utf8)!) }
        for (k, v) in fields {
            guard let v else { continue }
            append("--\(boundary)\r\nContent-Disposition: form-data; name=\"\(k)\"\r\n\r\n\(v)\r\n")
        }
        let fileData = try Data(contentsOf: fileURL)
        append("--\(boundary)\r\nContent-Disposition: form-data; name=\"\(fileField)\"; filename=\"\(fileURL.lastPathComponent)\"\r\nContent-Type: \(contentType)\r\n\r\n")
        body.append(fileData)
        append("\r\n--\(boundary)--\r\n")
        req.httpBody = body
        return try await send(req)
    }

    func delete<T: Decodable>(_ path: String) async throws -> T {
        var req = URLRequest(url: makeURL(path, query: []))
        req.httpMethod = "DELETE"
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        return try await send(req)
    }
}

private extension CharacterSet {
    /// 表单值允许直接出现的字符;其余(空格/中文/&/= 等)一律百分号编码。
    static let urlFormValue: CharacterSet = {
        var s = CharacterSet.alphanumerics
        s.insert(charactersIn: "-._~")
        return s
    }()
}
