import Foundation

/// 多系统整合(teach/回访/高情商/扣子/KPI)网络公共件。
/// android 端对应各 Module 里复用的 OkHttp/Moshi 配置 + ApiResult。
///
/// 与工牌(APIClient,Cookie 会话)不同:子系统走 Bearer token / 独立会话,
/// 域名各异,故独立成一套小工具;错误信息统一从 {"error"} / {"detail"} 里取给用户看真话。
struct SubsystemError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
    init(_ message: String) { self.message = message }
}

enum SubsystemHTTP {
    /// 常规请求(登录/列表/改密)。
    static let session: URLSession = {
        let cfg = URLSessionConfiguration.default
        cfg.timeoutIntervalForRequest = 60
        cfg.requestCachePolicy = .reloadIgnoringLocalCacheData
        return URLSession(configuration: cfg)
    }()

    /// SSE 生成专用:LLM 首字节可能 3-10s、整篇几十秒,超时放到 200s(对齐 android sseClient)。
    static let sseSession: URLSession = {
        let cfg = URLSessionConfiguration.default
        cfg.timeoutIntervalForRequest = 200
        cfg.timeoutIntervalForResource = 600
        cfg.requestCachePolicy = .reloadIgnoringLocalCacheData
        return URLSession(configuration: cfg)
    }()

    /// snake_case 响应用(teach / quota 等服务端 snake key)。
    static let snakeDecoder: JSONDecoder = {
        let d = JSONDecoder()
        d.keyDecodingStrategy = .convertFromSnakeCase
        return d
    }()

    /// 原样 key(followup customers 等服务端本就 camelCase)。
    static let plainDecoder = JSONDecoder()

    /// 构造 JSON 请求。body 用 JSONSerialization(字段名原样,不做 snake 转换)。
    static func jsonRequest(_ url: URL, method: String = "POST",
                            bearer: String? = nil, body: [String: Any]? = nil,
                            accept: String = "application/json") -> URLRequest {
        var req = URLRequest(url: url)
        req.httpMethod = method
        req.setValue(accept, forHTTPHeaderField: "Accept")
        if let bearer { req.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization") }
        if let body {
            req.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
            req.httpBody = try? JSONSerialization.data(withJSONObject: body)
        }
        return req
    }

    static func send(_ req: URLRequest, session: URLSession = session) async throws -> (Data, Int) {
        do {
            let (data, resp) = try await session.data(for: req)
            guard let http = resp as? HTTPURLResponse else { throw SubsystemError("无响应") }
            return (data, http.statusCode)
        } catch let e as SubsystemError {
            throw e
        } catch {
            throw SubsystemError("网络异常：\(error.localizedDescription)")
        }
    }

    /// 从错误响应体取 {"error"} / {"detail"} 文案(取不到返回 nil)。
    static func errorText(_ data: Data) -> String? {
        guard let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        return (obj["error"] as? String) ?? (obj["detail"] as? String)
    }
}

/// 带鉴权的通用调用骨架:确保 token → 请求 → 401 重登一次重试 → 解码。
/// android 端对应各 Repository 的 withAuth。
func subsystemWithAuth<T: Decodable>(
    ensureToken: () async throws -> String,
    refreshToken: () async throws -> String,
    request: (String) -> URLRequest,
    decoder: JSONDecoder = SubsystemHTTP.snakeDecoder
) async throws -> T {
    var token = try await ensureToken()
    var (data, status) = try await SubsystemHTTP.send(request(token))
    if status == 401 {
        token = try await refreshToken()
        (data, status) = try await SubsystemHTTP.send(request(token))
    }
    guard (200..<300).contains(status) else {
        throw SubsystemError(SubsystemHTTP.errorText(data) ?? "请求失败（HTTP \(status)）")
    }
    do { return try decoder.decode(T.self, from: data) }
    catch { throw SubsystemError("数据解析失败") }
}

/// 大量子系统 POST 端点统一返回 {ok / error / ...}。
struct SubsystemOkResp: Decodable {
    var ok: Bool?
    var error: String?
}
