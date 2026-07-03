import SwiftUI

/// 提醒(SPEC §4.8)。android 端对应 `ui/reminders/RemindersViewModel.kt`。
/// 拉 reminders 按 scope 拆 personal / escalation(店长可见,可「已跟进」)。
@MainActor
final class RemindersViewModel: ObservableObject {
    @Published var loading = false
    @Published var error: String?
    @Published var personal: [Reminder] = []
    @Published var escalation: [Reminder] = []
    @Published var handledIds: Set<Int> = []
    @Published var handling: Set<Int> = []

    private var loaded = false
    var isEmpty: Bool { !loading && error == nil && personal.isEmpty && escalation.isEmpty }

    func onAppear() { guard !loaded else { return }; loaded = true; load() }

    func load() {
        loading = true; error = nil
        Task {
            do {
                let r = try await ConsultantRepo.reminders()
                loading = false
                let items = r.items ?? []
                personal = items.filter { $0.scope != "escalation" }
                escalation = items.filter { $0.scope == "escalation" }
            } catch let err {
                loading = false
                error = (err as? APIError)?.errorDescription ?? "调取失败"
            }
        }
    }

    func handle(_ rid: Int) {
        guard !handling.contains(rid) else { return }
        handling.insert(rid); error = nil
        Task {
            do {
                _ = try await ConsultantRepo.handleReminder(rid)
                handling.remove(rid); handledIds.insert(rid)
            } catch let err {
                handling.remove(rid)
                error = (err as? APIError)?.errorDescription ?? "处理失败"
            }
        }
    }
}

extension Reminder {
    /// 文案。
    var displayMessage: String { message?.nilIfBlank ?? "你有一条待处理的提醒" }
    /// 路由按 ref_type/ref_id:ref_type=session → 查看报告(ref_id);ref_type=recording → 去绑定(ref_id)。
    var reportTarget: Int? { refType == "session" ? refId : sessionId }
    var bindTarget: Int? {
        if reportTarget != nil { return nil }
        if refType == "recording", let rid = refId { return rid }
        return nil
    }
}
