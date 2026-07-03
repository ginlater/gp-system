import SwiftUI

/// 陪伴首页数据(提醒未读数 + 今天的接诊统计)。android 端对应 `ui/home/HomeViewModel.kt` 的
/// reminderCount / receptionStats。录音态由 RecordingManager 提供。
@MainActor
final class HomeViewModel: ObservableObject {
    struct ReceptionStats: Equatable {
        var pendingBind = 0      // 待绑定(未绑定片段数)
        var waitingAnalysis = 0  // 已绑录音但还没出报告
        var reportsDone = 0      // 已生成报告
        var customers = 0        // 今日接诊客人数
        var loaded = false
    }

    @Published var reminderCount = 0
    @Published var stats = ReceptionStats()

    private var loaded = false
    private var reminderTimer: Timer?

    func onAppear() {
        guard !loaded else { refresh(); return }
        loaded = true
        refresh()
        // 前台每 60s 轮询提醒 → 新提醒弹系统通知(android 对应 HomeViewModel 轮询 + ReminderNotifier)
        reminderTimer?.invalidate()
        reminderTimer = Timer.scheduledTimer(withTimeInterval: 60, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.pollReminders() }
        }
    }

    private func pollReminders() {
        Task {
            if let r = try? await ConsultantRepo.reminders() {
                reminderCount = r.count ?? (r.items?.count ?? 0)
                ReminderNotifier.notifyNew(r.items ?? [])
            }
        }
    }

    func refresh() {
        pollReminders()
        Task {
            async let pend = ConsultantRepo.pending()
            async let recep = ConsultantRepo.todayReception(nil)
            let pending = (try? await pend) ?? []
            let items = (try? await recep)?.items ?? []
            stats = ReceptionStats(
                pendingBind: pending.count,
                waitingAnalysis: items.filter { ($0.recordingCount ?? 0) > 0 && $0.analysisStatus != "done" }.count,
                reportsDone: items.filter { $0.analysisStatus == "done" }.count,
                customers: items.count,
                loaded: true
            )
        }
    }

    var receptionSubtitle: String {
        guard stats.loaded else { return "接诊记录、分析报告，以及待绑定的陪伴" }
        return "\(stats.pendingBind) 段待绑定 · \(stats.waitingAnalysis) 位待分析 · \(stats.reportsDone) 份报告 · 共 \(stats.customers) 位顾客"
    }
}

/// 时段问候(早上好/中午好/下午好/晚上好)。
func greetingNow() -> String {
    let h = Calendar.current.component(.hour, from: Date())
    switch h {
    case 5...10: return "早上好"
    case 11...12: return "中午好"
    case 13...17: return "下午好"
    default: return "晚上好"
    }
}
