import SwiftUI

/// 会话预览 + 开始分析(SPEC §4.6)。android 端对应 `ui/session/SessionPreviewViewModel.kt`。
/// 本轮:按顾客+日期载入接诊包,加入/移除片段、开始/取消分析、确认说话人。换绑弹层后续补。
@MainActor
final class SessionPreviewViewModel: ObservableObject {
    enum Phase { case idle, running, done, failed, outdated }

    let customerId: Int
    @Published var loading = true
    @Published var loadError: String?
    @Published var sessionId: Int = -1
    @Published var customerName = ""
    @Published var serviceDate = ""
    @Published var phase: Phase = .idle
    @Published var progress: TaskProgress?
    @Published var bound: [PreviewRecording] = []
    @Published var unbound: [PreviewRecording] = []
    @Published var rowOpId: Int?
    @Published var submitting = false
    @Published var toast: String?
    @Published var navigateToReport: Int?

    private var loaded = false

    init(customerId: Int, date: String) {
        self.customerId = customerId
        self.serviceDate = date
    }

    var editable: Bool { phase != .running }
    var canStart: Bool { !bound.isEmpty && phase != .running && phase != .done }
    var canCancel: Bool { phase == .running }
    var progressTotal: Int { progress?.total ?? 10 }
    var progressDone: Int { progress?.done ?? 0 }
    var speakerUnconfirmed: [PreviewRecording] {
        bound.filter { $0.asrSpeakerWarning == 1 && ($0.speakerConfirmed ?? 0) == 0 }
    }
    private var blockReason: String? {
        if bound.isEmpty { return "no_recording" }
        if !speakerUnconfirmed.isEmpty { return "speaker_unconfirmed" }
        return nil
    }

    func onAppear() { guard !loaded else { return }; loaded = true; refresh(initial: true) }

    func refresh(initial: Bool = false) {
        if initial { loading = true; loadError = nil }
        Task {
            do {
                let p = try await ConsultantRepo.sessionPreview(customerId: customerId, date: serviceDate.isEmpty ? nil : serviceDate)
                loading = false
                if let e = p.error { loadError = e; return }
                sessionId = p.sessionId ?? sessionId
                customerName = p.customer?.name ?? customerName
                serviceDate = p.serviceDate ?? serviceDate
                phase = phaseOf(p.analysisStatus)
                progress = p.taskProgress
                bound = p.bound ?? []
                unbound = p.unbound ?? []
            } catch let err {
                loading = false
                loadError = (err as? APIError)?.errorDescription ?? "调取失败"
            }
        }
    }

    private func phaseOf(_ s: String?) -> Phase {
        switch s {
        case "running", "queued": return .running
        case "done": return .done
        case "failed", "cancelled": return .failed
        case "outdated": return .outdated
        default: return .idle
        }
    }

    func addToPackage(_ rid: Int) {
        guard editable, rowOpId == nil else { return }
        rowOpId = rid
        Task {
            do {
                _ = try await ConsultantRepo.bind(recordingId: rid, customerId: customerId)
                rowOpId = nil; toast = "已加入本接诊包"; refresh()
            } catch let err { rowOpId = nil; toast = (err as? APIError)?.errorDescription ?? "加入失败" }
        }
    }

    func removeFromPackage(_ rid: Int) {
        guard editable, rowOpId == nil else { return }
        rowOpId = rid
        Task {
            do {
                _ = try await ConsultantRepo.sessionPreviewRemove(recordingId: rid)
                rowOpId = nil; toast = "已退回待整理"; refresh()
            } catch let err { rowOpId = nil; toast = (err as? APIError)?.errorDescription ?? "退回失败" }
        }
    }

    func confirmSpeakers(_ rid: Int) {
        guard editable, rowOpId == nil else { return }
        rowOpId = rid
        Task {
            do {
                _ = try await ConsultantRepo.confirmSpeakers(recordingId: rid, action: "keep")
                rowOpId = nil; toast = "已确认说话人，可开始分析"; refresh()
            } catch let err { rowOpId = nil; toast = (err as? APIError)?.errorDescription ?? "确认失败" }
        }
    }

    // ── 换绑(免理由免审批,android 对应 SessionPreviewViewModel.RebindSheet)──

    @Published var rebindFor: PreviewRecording?      // 非 nil = 换绑弹层打开
    @Published var rebindQuery = ""
    @Published var rebindPicks: [Customer] = []
    @Published var rebindSearching = false
    private var rebindSearchTask: Task<Void, Never>?

    func openRebind(_ rec: PreviewRecording) {
        rebindFor = rec
        rebindQuery = ""
        rebindPicks = []
        loadRebindCandidates()
    }
    func closeRebind() { rebindFor = nil }

    func onRebindQueryChange(_ q: String) {
        rebindQuery = q
        rebindSearchTask?.cancel()
        rebindSearchTask = Task {
            try? await Task.sleep(nanoseconds: 280_000_000)
            if Task.isCancelled { return }
            loadRebindCandidates()
        }
    }

    func loadRebindCandidates() {
        guard let rec = rebindFor else { return }
        rebindSearching = true
        Task {
            let q = rebindQuery.trimmingCharacters(in: .whitespaces).nilIfBlank
            let r = try? await ConsultantRepo.rebindCandidates(rid: rec.id, q: q)
            // 排除当前所属顾客本人(换给自己没意义)
            rebindPicks = (r?.items ?? []).filter { $0.cid != customerId }
            rebindSearching = false
        }
    }

    /// 确认换绑:direct_rebind 免审批直接生效;成功就地刷新(原报告若已分析会被后端标作废)。
    func submitRebind(_ target: Customer) {
        guard let rec = rebindFor, let tid = target.cid, !submitting else { return }
        submitting = true
        Task {
            do {
                let r = try await ConsultantRepo.directRebind(recordingId: rec.id, toCustomerId: tid)
                submitting = false
                if let e = r.error { toast = e; return }
                toast = "已换绑到「\(target.name ?? "该顾客")」"
                rebindFor = nil
                refresh()
            } catch let err {
                submitting = false
                toast = (err as? APIError)?.errorDescription ?? "换绑失败"
            }
        }
    }

    func startAnalysis() {
        guard !submitting, canStart else { return }
        if let code = blockReason { toast = Self.reasonText(code); return }
        submitting = true
        Task {
            do {
                let r = try await ConsultantRepo.startAnalysis(customerId: customerId, date: serviceDate.isEmpty ? nil : serviceDate)
                submitting = false
                toast = r.msg ?? "已开始分析"
                phase = .running
                navigateToReport = r.sessionId ?? sessionId
            } catch let err { submitting = false; toast = (err as? APIError)?.errorDescription ?? "开始失败" }
        }
    }

    func cancelAnalysis() {
        guard !submitting, canCancel else { return }
        submitting = true
        Task {
            do {
                _ = try await ConsultantRepo.cancelAnalysis(sessionId: sessionId)
                submitting = false; toast = "已取消分析"; refresh()
            } catch let err { submitting = false; toast = (err as? APIError)?.errorDescription ?? "取消失败" }
        }
    }

    static func reasonText(_ code: String?) -> String {
        switch code {
        case "speaker_unconfirmed": return "有片段提示说话人异常，请先在该片段上「确认说话人」再开始分析。"
        case "no_recording": return "接诊包内还没有陪伴片段，请先加入或去待整理绑定。"
        default: return "暂时无法开始分析，请稍后重试。"
        }
    }
}
