import SwiftUI

/// 分析报告状态机(SPEC §6)。android 端对应 `ui/report/ReportViewModel.kt`。
/// 本轮:只读渲染(详情 + 标签 + 点评 + 任务 + 原始音频懒取);写操作(重跑/点评/分割/删除)后续补。
@MainActor
final class ReportViewModel: ObservableObject {
    let sessionId: Int

    @Published var loading = true
    @Published var error: String?
    @Published var detail: SessionDetail?
    @Published var tags: CustomerTagsResponse?
    @Published var evaluations: [Evaluation] = []
    @Published var tasks: [AnalysisTask] = []
    @Published var audioURL: String?
    @Published var audioURLLoading = false

    private var loaded = false

    init(sessionId: Int) { self.sessionId = sessionId }

    func onAppear() {
        guard !loaded else { return }
        loaded = true
        Task { await ConsultantRepo.reportViewEnter(sessionId) }   // 上报已查看
        Task { await load() }
    }

    func load() async {
        loading = true
        error = nil
        do {
            let d = try await ConsultantRepo.session(sessionId)
            detail = d
            error = d.error
        } catch let err {
            error = (err as? APIError)?.errorDescription ?? "调取失败"
        }
        loading = false
        // 标签 / 点评 / 任务:失败不打断主报告
        tags = try? await ConsultantRepo.sessionCustomerTags(sessionId)
        if let ev = try? await ConsultantRepo.evaluations(sessionId) { evaluations = ev.evaluations ?? [] }
        if let t = try? await ConsultantRepo.sessionTasks(sessionId) { tasks = t.tasks ?? [] }
        ensureAudioURL()   // 详情就绪后懒取主片段播放地址
    }

    func ensureAudioURL() {
        guard audioURL == nil, !audioURLLoading, let rid = primaryRecording?.id else { return }
        audioURLLoading = true
        Task {
            audioURL = (try? await ConsultantRepo.recordingUrl(rid))?.url
            audioURLLoading = false
        }
    }

    // 派生
    var report: SessionReport? { detail?.report }
    var recordings: [SessionRecording] { detail?.recordings ?? [] }
    var primaryRecording: SessionRecording? { recordings.first }
    var displayStatus: String? { detail?.displayStatus }
    var tasksDone: Int { tasks.filter { $0.isDone }.count }
    var tasksTotal: Int { tasks.count }

    var headerTitle: String {
        let a = detail?.advisor?.nilIfBlank
        let c = detail?.customer?.nilIfBlank
        switch (a, c) {
        case let (a?, c?): return "\(a) × \(c)"
        case (_, let c?): return c
        case (let a?, _): return a
        default: return "陪伴分析报告"
        }
    }
    var headerSubtitle: String {
        if let d = detail?.serviceDate?.nilIfBlank { return "陪伴全维度分析报告 · \(d)" }
        return "陪伴全维度分析报告"
    }
}
