import Foundation

/* ===================================================================
 * 扣子销售话术(sale-agent,chat.aibeautyfulwomen.com)。
 * android 端对应 `data/chat/ChatRepository.kt`;iOS 已全原生化(2026-07-16,
 * 原网页壳 AgentWebView 下线),表单/生成/历史全走本仓库。
 *
 * 契约(上服务器核对 server.py):
 *  - POST /api/login {username,password} → {token},JWT 24h,无单设备互踢;
 *  - POST /api/generate_stream {prompt,mode:"free",model?} → SSE:
 *      data: {"type":"delta","content":…} 增量(内嵌【思考】/【话术】字面标记)
 *      data: {"type":"compliance_restart","words":[…]} 违禁词重写,清空重收
 *      data: {"type":"compliance_warning","words":[…]}
 *      data: {"type":"done","reply","thinking","script","history_id"} 终帧(已切好三段)
 *      data: {"type":"error","message"}
 *  - GET /api/quota → {username,used,total};429 = 额度用尽;
 *  - GET /api/history?page&page_size(&favorite=true) → {items,total,page,total_pages};
 *  - PATCH /api/history/{id} {favorite/liked/disliked/feedback}。
 *  - 表单模式没有独立 mode:网页把表单拼成自然语言(buildFormDescription)后仍走 mode:"free"。
 * =================================================================== */

struct ChatLoginResp: Decodable {
    var token: String?
    var username: String?
    var detail: String?
}

struct ChatQuota: Decodable {
    var username: String?
    var used: Int?
    var total: Int?
}

/// 历史条目(字段宽松解析:服务端 db 字段随版本演进,缺啥都不崩)。
struct ChatHistoryItem {
    var id: String
    var prompt: String
    var reply: String
    var thinking: String
    var script: String
    var favorite: Bool
    var createdAt: String

    init?(dict: [String: JSONValue]) {
        guard let id = dict["id"]?.stringValue ?? dict["history_id"]?.stringValue else { return nil }
        self.id = id
        prompt = dict["prompt"]?.stringValue ?? ""
        reply = dict["reply"]?.stringValue ?? ""
        thinking = dict["thinking"]?.stringValue ?? ""
        script = dict["script"]?.stringValue ?? ""
        if case .bool(let b) = dict["favorite"] ?? .null { favorite = b }
        else { favorite = (dict["favorite"]?.doubleValue ?? 0) != 0 }
        createdAt = dict["created_at"]?.stringValue ?? dict["ts"]?.stringValue ?? ""
    }
}

struct ChatHistoryPage: Decodable {
    var items: [[String: JSONValue]]?
    var total: Int?
    var page: Int?
    var totalPages: Int?

    enum CodingKeys: String, CodingKey {
        case items, total, page
        case totalPages = "total_pages"
    }
}

/// sale-agent token 管理(actor = android loginMutex)。
actor ChatAuth {
    static let shared = ChatAuth()
    static let base = URL(string: "https://chat.aibeautyfulwomen.com/")!
    private var token: String?

    func clear() { token = nil }

    func ensureToken() async throws -> String {
        if let token { return token }
        return try await login()
    }

    func refreshAfter401() async throws -> String {
        token = nil
        return try await login()
    }

    private func login() async throws -> String {
        guard let cred = CredentialStore.load() else {
            throw SubsystemError("本机没有登录凭证，请退出后重新登录一次")
        }
        let req = SubsystemHTTP.jsonRequest(
            ChatAuth.base.appendingPathComponent("api/login"),
            body: ["username": cred.u, "password": cred.p])
        let (data, status) = try await SubsystemHTTP.send(req)
        let body = try? SubsystemHTTP.plainDecoder.decode(ChatLoginResp.self, from: data)
        if (200..<300).contains(status), let t = body?.token, !t.isEmpty {
            token = t
            return t
        }
        if status == 401 || status == 403 {
            throw SubsystemError("销售话术密码与工牌不一致，请联系管理员同步账号")
        }
        throw SubsystemError(SubsystemHTTP.errorText(data) ?? "销售话术登录失败（HTTP \(status)）")
    }
}

/// 扣子销售话术数据仓库。
struct ChatRepository {

    enum GenEvent {
        case delta(String)
        case restart([String])
        case warning([String])
        /// 终帧:服务端已把 思考/话术 切好。
        case done(reply: String?, thinking: String?, script: String?)
        case failed(String)
    }

    func ensureLogin() async throws -> String {
        try await ChatAuth.shared.ensureToken()
    }

    /// 额度({used}/{total})。
    func quota() async throws -> ChatQuota {
        try await call("api/quota", method: "GET")
    }

    /// 改密(统一密码同步)。
    func changePassword(old: String, new: String) async throws {
        let _: SubsystemOkResp = try await call(
            "api/change-password",
            body: ["old_password": old, "new_password": new])
    }

    /// 历史列表(倒序分页;favoriteOnly 只看收藏)。
    func history(page: Int, pageSize: Int = 10, favoriteOnly: Bool = false) async throws -> (items: [ChatHistoryItem], totalPages: Int) {
        var path = "api/history?page=\(page)&page_size=\(pageSize)"
        if favoriteOnly { path += "&favorite=true" }
        let resp: ChatHistoryPage = try await call(path, method: "GET")
        let items = (resp.items ?? []).compactMap(ChatHistoryItem.init(dict:))
        return (items, resp.totalPages ?? 1)
    }

    /// 收藏/取消收藏历史条目。
    func setFavorite(id: String, favorite: Bool) async throws {
        let _: SubsystemOkResp = try await call(
            "api/history/\(id)", method: "PATCH", body: ["favorite": favorite])
    }

    /// SSE 流式生成(单轮,无上下文)。取消消费 Task 即断开。
    func generateStream(prompt: String) -> AsyncStream<GenEvent> {
        AsyncStream { continuation in
            let task = Task {
                await runGenerate(prompt: prompt, continuation: continuation)
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    private func runGenerate(prompt: String, continuation: AsyncStream<GenEvent>.Continuation) async {
        do {
            var token = try await ChatAuth.shared.ensureToken()
            let body: [String: Any] = ["prompt": prompt, "mode": "free"]
            func buildReq(_ t: String) -> URLRequest {
                SubsystemHTTP.jsonRequest(ChatAuth.base.appendingPathComponent("api/generate_stream"),
                                          bearer: t, body: body, accept: "text/event-stream")
            }
            var (bytes, resp) = try await SubsystemHTTP.sseSession.bytes(for: buildReq(token))
            var status = (resp as? HTTPURLResponse)?.statusCode ?? 0
            if status == 401 {
                token = try await ChatAuth.shared.refreshAfter401()
                (bytes, resp) = try await SubsystemHTTP.sseSession.bytes(for: buildReq(token))
                status = (resp as? HTTPURLResponse)?.statusCode ?? 0
            }
            guard (200..<300).contains(status) else {
                var raw = Data()
                for try await b in bytes { raw.append(b); if raw.count > 4096 { break } }
                let msg = SubsystemHTTP.errorText(raw)
                    ?? (status == 429 ? "本月生成额度已用完" : "生成失败（HTTP \(status)）")
                continuation.yield(.failed(msg))
                return
            }

            var doneEmitted = false
            for try await line in bytes.lines {
                guard line.hasPrefix("data:") else { continue }
                let payload = String(line.dropFirst(5)).trimmingCharacters(in: .whitespaces)
                guard let obj = try? JSONSerialization.jsonObject(with: Data(payload.utf8)) as? [String: Any] else { continue }
                switch obj["type"] as? String {
                case "delta":
                    continuation.yield(.delta("\(obj["content"] ?? "")"))
                case "compliance_restart":
                    continuation.yield(.restart(Self.words(obj)))
                case "compliance_warning":
                    continuation.yield(.warning(Self.words(obj)))
                case "done":
                    doneEmitted = true
                    continuation.yield(.done(reply: obj["reply"] as? String,
                                             thinking: obj["thinking"] as? String,
                                             script: obj["script"] as? String))
                    return
                case "error":
                    continuation.yield(.failed("\(obj["message"] ?? "生成出错")"))
                default:
                    break
                }
            }
            if !doneEmitted { continuation.yield(.done(reply: nil, thinking: nil, script: nil)) }
        } catch {
            if !Task.isCancelled {
                continuation.yield(.failed((error as? SubsystemError)?.message ?? "网络异常"))
            }
        }
    }

    private static func words(_ obj: [String: Any]) -> [String] {
        (obj["words"] as? [Any])?.map { "\($0)" } ?? []
    }

    private func call<T: Decodable>(_ path: String, method: String = "POST",
                                    body: [String: Any]? = nil) async throws -> T {
        try await subsystemWithAuth(
            ensureToken: { try await ChatAuth.shared.ensureToken() },
            refreshToken: { try await ChatAuth.shared.refreshAfter401() },
            request: { token in
                SubsystemHTTP.jsonRequest(URL(string: path, relativeTo: ChatAuth.base)!,
                                          method: method, bearer: token, body: body)
            },
            decoder: SubsystemHTTP.plainDecoder)
    }
}
