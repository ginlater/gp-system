import SwiftUI

/// App 全局状态(登录态 + 角色分流)。对应 android 的 Gate 路由逻辑(`ui/gate/`)。
///
/// 启动 → bootstrap() 拉 /api/me:已登录按 role 分流到主壳 / 管理台,未登录落登录页。
@MainActor
final class AppState: ObservableObject {
    enum Phase {
        case loading
        case login
        case main(Me)      // consultant / store_manager(单系统:直进录音首页,不打扰)
        case workspace(Me) // 多系统账号(/api/me systems ≥2):先落工作台宫格选系统
        case admin(Me)     // admin / super
    }

    @Published var phase: Phase = .loading
    private let auth = AuthManager()
    private var booted = false   // 防换肤重渲染时 .task 重跑 bootstrap

    /// 当前登录用户(便于各处读 advisorName / role)。
    var me: Me? {
        switch phase {
        case .main(let m), .workspace(let m), .admin(let m): return m
        default: return nil
        }
    }

    func bootstrap() async {
        guard !booted else { return }
        booted = true
        #if DEBUG
        // 调试自动登录:`simctl launch ... -autoLoginUser X -autoLoginPass Y`(UserDefaults 读 argv 的 -key value)。
        // 仅 DEBUG;用于在模拟器上无人值守登录后截图验证各鉴权页面。
        if let u = UserDefaults.standard.string(forKey: "autoLoginUser"),
           let p = UserDefaults.standard.string(forKey: "autoLoginPass"),
           !u.isEmpty, !p.isEmpty {
            if case .success(let me) = await auth.login(username: u, password: p) {
                // 验证 401 自动重登:`-simExpire YES` 登录后清 cookie(凭据保留)→
                // 进主壳后首个数据请求触发静默重登,数据应正常加载(顾问无感)。
                if UserDefaults.standard.bool(forKey: "simExpire") { auth.clearCookies() }
                route(me); return
            }
        }
        #endif
        if let me = await auth.currentUser() {
            route(me)
        } else {
            phase = .login
        }
    }

    func onLoggedIn(_ me: Me) { route(me) }

    func logout() async {
        await auth.logout()
        phase = .login
    }

    private func route(_ me: Me) {
        // 对齐 android GateScreen:admin→管理台 / ≥2系统→工作台 / 其余→录音主壳。
        // 旧服务端无 systems 字段 → multiSystem=false,绝大多数顾问体验不变。
        if me.isAdminOrSuper { phase = .admin(me) }
        else if me.multiSystem { phase = .workspace(me) }
        else { phase = .main(me) }
    }
}
