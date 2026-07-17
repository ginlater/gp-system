import SwiftUI

/// teach 网课首页 ViewModel:静默登录 + /teach/me/stats(课程列表/进度/打卡/时长)+ 打卡。
/// android 端对应 `ui/teach/TeachHomeViewModel.kt`。
///
/// 静默登录失败(凭证缺失/密码不同步/账号停用)作为整页错误展示 + 重试;
/// 从课件/测验页返回时(onAppear)静默刷新一次,测验分数与解锁状态即时更新。
@MainActor
final class TeachHomeViewModel: ObservableObject {
    @Published var loading = false
    @Published var error: String?
    @Published var stats: TeachStats?
    @Published var checkinBusy = false
    @Published var toast: String?

    private let repo = TeachRepository()

    /// 拉课程与统计。silent=true:后台刷新(不清空已展示内容、不整页转圈;失败也不打扰)。
    func load(silent: Bool = false) {
        if loading { return }
        if !silent { loading = stats == nil; error = nil }
        Task {
            do {
                let r = try await repo.stats()
                loading = false
                error = nil
                stats = r
            } catch {
                loading = false
                if !(silent && stats != nil) {
                    self.error = (error as? SubsystemError)?.message ?? "网络异常"
                }
            }
        }
    }

    /// 每日打卡(幂等)。成功后就地更新打卡卡片,不整页刷新。
    func checkin() {
        if checkinBusy { return }
        checkinBusy = true
        Task {
            do {
                let r = try await repo.checkin()
                var s = stats
                if var ck = s?.checkin {
                    ck.todaySigned = r.todaySigned ?? true
                    if let v = r.streak { ck.streak = v }
                    if let v = r.totalDays { ck.totalDays = v }
                    s?.checkin = ck
                }
                stats = s
                toast = "打卡成功，已连续 \(r.streak ?? 1) 天"
            } catch {
                toast = (error as? SubsystemError)?.message ?? "打卡失败,请重试"
            }
            checkinBusy = false
        }
    }
}
