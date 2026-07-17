import Foundation

/// teach 网课 token 管理(actor = android 的 loginMutex:并发 401 只重登一次)。
///
/// 核心是「统一密码静默登录」:App 登录工牌时缓存的手机号+密码(CredentialStore)
/// 与 teach 是同一套,进网课模块时用它悄悄换 Bearer token,顾问全程无感。
actor TeachAuth {
    static let shared = TeachAuth()
    private var token: String?

    func clear() { token = nil }

    func ensureToken() async throws -> String {
        if let token { return token }
        return try await login()
    }

    /// 401(被网页端登录挤掉 / 过期)后强制重登。
    func refreshAfter401() async throws -> String {
        token = nil
        return try await login()
    }

    private func login() async throws -> String {
        guard let cred = CredentialStore.load() else {
            throw SubsystemError("本机没有登录凭证，请退出后重新登录一次")
        }
        let req = SubsystemHTTP.jsonRequest(
            TeachRepository.base.appendingPathComponent("teach/login"),
            body: ["username": cred.u, "password": cred.p])
        let (data, status) = try await SubsystemHTTP.send(req)
        let body = try? SubsystemHTTP.plainDecoder.decode(TeachLoginResp.self, from: data)
        if (200..<300).contains(status), let t = body?.token, !t.isEmpty {
            token = t
            return t
        }
        switch status {
        case 401: throw SubsystemError("网课密码与工牌不一致，请联系管理员同步账号")
        case 403: throw SubsystemError(SubsystemHTTP.errorText(data) ?? "网课账号已停用，请联系管理员")
        default: throw SubsystemError(SubsystemHTTP.errorText(data) ?? "网课登录失败（HTTP \(status)）")
        }
    }
}

/// teach 网课数据仓库。android 端对应 `data/teach/TeachRepository.kt`。
struct TeachRepository {
    static let base = URL(string: "https://teach.aibeautyfulwomen.com/")!

    /// 最近一次 stats 成功结果(课件页查章标题等轻量读取用;进程内缓存,logout 清)。
    nonisolated(unsafe) static var lastStats: TeachStats?

    /// 课件正文 URL(静态 html、免鉴权;原生渲染由 CoursewareParser 拉取解析)。
    static func coursewareUrl(_ chapterKey: String) -> URL {
        base.appendingPathComponent("\(chapterKey).html")
    }

    /// 课程列表 + 进度 + 打卡 + 学习时长。成功后缓存进 lastStats。
    func stats() async throws -> TeachStats {
        let r: TeachStats = try await call("teach/me/stats", method: "GET")
        TeachRepository.lastStats = r
        return r
    }

    /// 每日打卡(幂等)。
    func checkin() async throws -> TeachCheckinResp {
        try await call("teach/checkin")
    }

    /// 学习时长心跳(读课件期间每 30s 发;服务端 25s 节流,偶发丢弃无妨)。
    func heartbeat(chapter: String) async throws -> TeachHeartbeatResp {
        try await call("teach/heartbeat", body: ["chapter": chapter])
    }

    /// 取一套测验题。quizIndex ∈ 1/2/3。
    func quiz(chapter: String, quizIndex: Int) async throws -> TeachQuizResp {
        try await call("teach/quiz/\(chapter)/\(quizIndex)", method: "GET")
    }

    /// 提交测验。answers 与题目一一对应:单选传 "A"~"D",情境题传作答文本。
    func submitQuiz(chapter: String, quizIndex: Int, answers: [String]) async throws -> TeachSubmitResp {
        try await call("teach/quiz/submit",
                       body: ["chapter": chapter, "quiz_index": quizIndex, "answers": answers])
    }

    /// 改密(统一密码同步)。
    func changePassword(old: String, new: String) async throws {
        let _: SubsystemOkResp = try await call(
            "teach/change-password",
            body: ["old_password": old, "new_password": new])
    }

    private func call<T: Decodable>(_ path: String, method: String = "POST",
                                    body: [String: Any]? = nil) async throws -> T {
        try await subsystemWithAuth(
            ensureToken: { try await TeachAuth.shared.ensureToken() },
            refreshToken: { try await TeachAuth.shared.refreshAfter401() },
            request: { token in
                SubsystemHTTP.jsonRequest(TeachRepository.base.appendingPathComponent(path),
                                          method: method, bearer: token, body: body)
            })
    }
}
