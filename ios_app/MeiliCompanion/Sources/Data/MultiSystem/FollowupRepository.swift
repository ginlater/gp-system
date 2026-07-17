import Foundation

/// 回访话术 / 高情商话术 —— 同一套 followup-agent 代码的两处部署。
/// App 里做成**一个模块、两个系统实例**:除 base URL / 名称 / key 外全部复用。
/// android 端对应 `data/followup/FollowupModule.kt` 的 ScriptSystem。
enum ScriptSystem: String, CaseIterable {
    case followup
    case higheq

    /// /api/me systems 里的 key。
    var key: String { rawValue }

    var baseURL: URL {
        switch self {
        case .followup: return URL(string: "https://www.aibeautyfulwomen.com/")!
        // 2026-07-15 起走 https 域名(huifang-prod nginx 反代 dev-machine :8000),无需 ATS 例外
        case .higheq: return URL(string: "https://intelligent.beautyshining.com/")!
        }
    }

    var displayName: String {
        switch self {
        case .followup: return "回访话术"
        case .higheq: return "高情商话术"
        }
    }
}

/// followup-agent token 管理(actor = android loginMutex,按系统各一份)。
///
/// 账号绑定优先:老顾问的回访/高情商历史数据在独立 staff 账号名下(/api/me 下发
/// scriptAccount/scriptPassword),用它登录数据才接得上;没绑定的(新开号)退回统一密码。
actor ScriptAuth {
    private static let followupShared = ScriptAuth(.followup)
    private static let higheqShared = ScriptAuth(.higheq)
    static func of(_ sys: ScriptSystem) -> ScriptAuth {
        sys == .followup ? followupShared : higheqShared
    }

    private let sys: ScriptSystem
    private var token: String?

    private init(_ sys: ScriptSystem) { self.sys = sys }

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
        let me = AuthManager.lastMe
        let bindU = me?.scriptAccount?.nilIfBlank
        let bindP = me?.scriptPassword?.nilIfBlank
        let cred = CredentialStore.load()
        let u = bindU ?? cred?.u
        let p = bindU != nil ? bindP : cred?.p
        guard let u = u?.nilIfBlank, let p = p?.nilIfBlank else {
            throw SubsystemError("本机没有登录凭证，请退出后重新登录一次")
        }
        // ⚠️ 密码字段名是 key 不是 password(server.py 契约)
        let req = SubsystemHTTP.jsonRequest(
            sys.baseURL.appendingPathComponent("login/account"),
            body: ["username": u, "key": p])
        let (data, status) = try await SubsystemHTTP.send(req)
        let body = try? SubsystemHTTP.plainDecoder.decode(FuLoginResp.self, from: data)
        if (200..<300).contains(status), let t = body?.token, !t.isEmpty {
            token = t
            return t
        }
        if status == 401 {
            throw SubsystemError("\(sys.displayName)密码与工牌不一致，请联系管理员同步账号")
        }
        throw SubsystemError(SubsystemHTTP.errorText(data) ?? "\(sys.displayName)登录失败（HTTP \(status)）")
    }
}

/// 回访/高情商数据仓库(按 ScriptSystem 实例化)。
/// android 端对应 `data/followup/FollowupRepository.kt`。
struct FollowupRepository {
    static let defaultModel = "claude_sonnet"

    let sys: ScriptSystem

    /// 生成流事件。
    enum GenEvent {
        case content(String)
        /// 命中违禁词、服务端已中止并自动重写:UI 应清空已收文本重新累积。
        case restart([String])
        /// 重写后仍残留的敏感词告警(附在结果尾部提示即可)。
        case warning([String])
        case failed(String)
        case done
    }

    /// 供网页壳(AgentWeb)拿 token 注入 localStorage 用。
    func ensureLogin() async throws -> String {
        try await ScriptAuth.of(sys).ensureToken()
    }

    /// 本月额度。
    func quota() async throws -> FuQuota {
        try await call("quota", method: "GET")
    }

    /// 按公司动态下发的表单选项(类目 → 选项数组;后台改选项 App 即时生效)。
    func projectOptions() async throws -> [String: [String]] {
        try await call("project-options", method: "GET")
    }

    /// 顾问名单(顾问姓名/所属门店自动带出)。
    func employees() async throws -> FuEmployees {
        try await call("employees/list", method: "GET")
    }

    /// 改密(统一密码同步)。
    func changePassword(old: String, new: String) async throws {
        let _: SubsystemOkResp = try await call(
            "change-password", body: ["old_password": old, "new_password": new])
    }

    /// 导入历史:顾客档案列表。
    func customers() async throws -> FuCustomers {
        try await call("customers", method: "GET")
    }

    /// 收藏/取消收藏。(中文姓名由 appendingPathComponent 统一做百分号编码)
    func favorite(name: String, favorited: Bool) async throws {
        let _: SubsystemOkResp = try await call(
            "customers/\(name)/favorite", body: ["favorited": favorited])
    }

    /// 战果登记(覆盖写指定版本;字段=服务端 BusinessRequest)。
    func recordBusiness(name: String, versionIdx: Int, body: [String: Any]) async throws {
        let _: SubsystemOkResp = try await call(
            "customers/\(name)/versions/\(versionIdx)/business", body: body)
    }

    /// 生成后保存顾客+话术(与网页端历史互通;失败不打扰,调用方 fire-and-forget)。
    func saveCustomer(name: String, data: [String: Any], script: String, model: String) async {
        let fmt = DateFormatter()
        fmt.dateFormat = "yyyy-MM-dd'T'HH:mm:ss"
        fmt.locale = Locale(identifier: "en_US_POSIX")
        let body: [String: Any] = [
            "name": name,
            "savedAt": fmt.string(from: Date()),
            "data": data,
            "lastScript": script,
            "favorited": false,
            "model": model,
        ]
        let _: SubsystemOkResp? = try? await call("customers", body: body)
    }

    /// SSE 流式生成。取消消费 Task 即断开连接。
    /// SSE 帧:data:{"content":…} / [DONE] / {"error"} / {"compliance_restart"} / {"compliance_warning"};
    /// 高情商变体把残留敏感词直接附在 content 帧里(compliance_warn_words)。
    func generateStream(customerData: [String: Any], model: String) -> AsyncStream<GenEvent> {
        AsyncStream { continuation in
            let task = Task {
                await runGenerate(customerData: customerData, model: model, continuation: continuation)
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    private func runGenerate(customerData: [String: Any], model: String,
                             continuation: AsyncStream<GenEvent>.Continuation) async {
        do {
            var token = try await ScriptAuth.of(sys).ensureToken()
            let body: [String: Any] = ["model": model, "customer_data": customerData, "stream": true]
            func buildReq(_ t: String) -> URLRequest {
                SubsystemHTTP.jsonRequest(sys.baseURL.appendingPathComponent("generate"),
                                          bearer: t, body: body, accept: "text/event-stream")
            }

            var (bytes, resp) = try await SubsystemHTTP.sseSession.bytes(for: buildReq(token))
            var status = (resp as? HTTPURLResponse)?.statusCode ?? 0
            if status == 401 {
                token = try await ScriptAuth.of(sys).refreshAfter401()
                (bytes, resp) = try await SubsystemHTTP.sseSession.bytes(for: buildReq(token))
                status = (resp as? HTTPURLResponse)?.statusCode ?? 0
            }
            guard (200..<300).contains(status) else {
                var raw = Data()
                for try await b in bytes { raw.append(b); if raw.count > 4096 { break } }
                continuation.yield(.failed(SubsystemHTTP.errorText(raw) ?? "生成失败（HTTP \(status)）"))
                return
            }

            for try await line in bytes.lines {
                guard line.hasPrefix("data:") else { continue }
                let payload = String(line.dropFirst(5)).trimmingCharacters(in: .whitespaces)
                if payload == "[DONE]" {
                    continuation.yield(.done)
                    return
                }
                guard let obj = try? JSONSerialization.jsonObject(with: Data(payload.utf8)) as? [String: Any] else { continue }
                if let content = obj["content"] {
                    continuation.yield(.content("\(content)"))
                    if let warn = obj["compliance_warn_words"] as? [Any], !warn.isEmpty {
                        continuation.yield(.warning(warn.map { "\($0)" }))
                    }
                } else if let err = obj["error"] {
                    continuation.yield(.failed("\(err)"))
                } else if let restart = obj["compliance_restart"] {
                    continuation.yield(.restart(Self.words(restart)))
                } else if let warning = obj["compliance_warning"] {
                    continuation.yield(.warning(Self.words(warning)))
                }
            }
            // 流被服务端断开而没给 [DONE]:当作完成(已收内容仍可用)
            continuation.yield(.done)
        } catch {
            if !Task.isCancelled {
                continuation.yield(.failed((error as? SubsystemError)?.message ?? "网络异常"))
            }
        }
    }

    private static func words(_ v: Any) -> [String] {
        ((v as? [String: Any])?["words"] as? [Any])?.map { "\($0)" } ?? []
    }

    private func call<T: Decodable>(_ path: String, method: String = "POST",
                                    body: [String: Any]? = nil) async throws -> T {
        try await subsystemWithAuth(
            ensureToken: { try await ScriptAuth.of(sys).ensureToken() },
            refreshToken: { try await ScriptAuth.of(sys).refreshAfter401() },
            request: { token in
                SubsystemHTTP.jsonRequest(sys.baseURL.appendingPathComponent(path),
                                          method: method, bearer: token, body: body)
            },
            decoder: SubsystemHTTP.plainDecoder)
    }
}
