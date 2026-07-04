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
        guard audioURL == nil, !audioURLLoading, let rec = currentRecording else { return }
        audioURLLoading = true
        Task {
            // 后端已签发的直链优先,否则按 rid 换取
            if let direct = rec.audioUrl?.nilIfBlank {
                audioURL = direct
            } else {
                audioURL = (try? await ConsultantRepo.recordingUrl(rec.id))?.url
            }
            audioURLLoading = false
        }
    }

    // ── 多段音频切换(F3:一次接诊多段陪伴,原来只能听第一段)──

    @Published var segIndex = 0

    var currentRecording: SessionRecording? {
        recordings.indices.contains(segIndex) ? recordings[segIndex] : recordings.first
    }

    func selectSegment(_ i: Int) {
        guard i != segIndex, recordings.indices.contains(i) else { return }
        segIndex = i
        audioURL = nil
        ensureAudioURL()
    }

    // ── 说话人确认(F4:说话人>2 会卡住分析,顾问需当场处置)──

    var needsSpeakerConfirm: Bool {
        guard let r = currentRecording else { return false }
        return r.asrSpeakerWarning == 1 && (r.speakerConfirmed ?? 0) == 0
    }

    func confirmSpeakers() {
        guard let rid = currentRecording?.id, !opBusy else { return }
        opBusy = true
        Task {
            do {
                _ = try await ConsultantRepo.confirmSpeakers(recordingId: rid, action: "keep")
                opBusy = false
                toast = "已确认说话人"
                await load()
            } catch { opBusy = false; toast = "确认失败，请重试" }
        }
    }

    // ── 任务面板写操作(F2:重跑单任务/补齐缺失/重新分析)──

    @Published var opBusy = false
    @Published var toast: String?

    func rerunTask(_ tid: String) {
        guard !opBusy else { return }
        opBusy = true
        Task {
            do {
                _ = try await ConsultantRepo.rerunTask(sessionId: sessionId, taskId: tid)
                opBusy = false
                toast = "已提交重跑，正在排队…"
                refreshTasks()
            } catch { opBusy = false; toast = "提交失败，请重试" }
        }
    }

    func fillMissing() {
        guard !opBusy else { return }
        opBusy = true
        Task {
            do {
                _ = try await ConsultantRepo.fillMissingTasks(sessionId: sessionId)
                opBusy = false
                toast = "已提交补齐缺失任务"
                refreshTasks()
            } catch { opBusy = false; toast = "提交失败，请重试" }
        }
    }

    func reanalyze() {
        guard !opBusy else { return }
        opBusy = true
        Task {
            do {
                _ = try await ConsultantRepo.reanalyze(sessionId: sessionId)
                opBusy = false
                toast = "已触发重新分析，正在排队…"
                await load()
            } catch { opBusy = false; toast = "提交失败，请重试" }
        }
    }

    private func refreshTasks() {
        Task {
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            if let t = try? await ConsultantRepo.sessionTasks(sessionId) { tasks = t.tasks ?? [] }
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
