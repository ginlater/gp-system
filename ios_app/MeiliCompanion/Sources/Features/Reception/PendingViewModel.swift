import SwiftUI

/// 待整理状态机(SPEC §4.3)。android 端对应 `ui/pending/PendingViewModel.kt` 的列表部分。
/// 本轮:未绑定陪伴片段列表 + 客户端分页 + 试听 + 去绑定。陪伴笔同步/删除申请/误录确认 后续补。
@MainActor
final class PendingViewModel: ObservableObject {
    @Published var recordings: [PendingRecording] = []
    @Published var page = 1
    @Published var loading = false
    @Published var error: String?

    let pageSize = 5
    private var loaded = false

    var totalPages: Int { recordings.isEmpty ? 1 : (recordings.count - 1) / pageSize + 1 }
    var pagedRecordings: [PendingRecording] {
        let p = min(max(page, 1), totalPages)
        return Array(recordings.dropFirst((p - 1) * pageSize).prefix(pageSize))
    }

    func onAppear() { guard !loaded else { return }; loaded = true; load() }

    func load() {
        if recordings.isEmpty { loading = true }
        error = nil
        Task {
            do {
                let recs = try await ConsultantRepo.pending()
                loading = false
                recordings = recs.sorted { sortKey($0) > sortKey($1) }   // 录制时间倒序
                page = min(page, totalPages)
            } catch let err {
                loading = false
                if recordings.isEmpty { error = (err as? APIError)?.errorDescription ?? "调取失败" }
            }
        }
    }

    func refresh() { load() }
    func goPage(_ delta: Int) { page = min(max(1, page + delta), totalPages) }

    // ── 「从陪伴笔同步」(android 对应 PendingViewModel.openPenSync/onPenFiles/importSelected)──

    struct PenSyncRow: Identifiable {
        let file: PenFile
        var checked = false
        var id: String { file.name }
        var title: String {
            let d = file.recordedAt ?? file.name
            let dur = file.durationSec > 0 ? String(format: " · %02d:%02d", file.durationSec / 60, file.durationSec % 60) : ""
            return d + dur
        }
        var sizeLabel: String {
            file.sizeBytes > 0 ? String(format: "%.1f MB", Double(file.sizeBytes) / 1024 / 1024) : ""
        }
    }

    @Published var syncSheet = false
    @Published var syncLoading = false
    @Published var syncUnavailable = false
    @Published var syncBusy = false          // 有下载在跑:笔没法回答列表查询,显示"同步中"而非"暂无"
    @Published var syncRecordingBusy = false // D7:笔正在录音,录音中笔拒绝文件传输
    @Published var syncPreviewFailed = false // D8:预检失败,禁导入防重复
    @Published var syncRows: [PenSyncRow] = []

    var syncSelectedCount: Int { syncRows.filter(\.checked).count }
    var syncAllChecked: Bool { !syncRows.isEmpty && syncRows.allSatisfy(\.checked) }

    func openPenSync() {
        syncSheet = true
        refreshPenSync()
    }

    /// 弹层内容刷新。忙态(未连/录音中/传输中)每 2s 自查:传完/连上自动加载列表,
    /// 不用关掉重开(此前弹层打开时查一次就定格,下载完了还永远显示"同步中")。
    private var syncRecheckTask: Task<Void, Never>?

    private func refreshPenSync() {
        syncRecheckTask?.cancel()
        syncPreviewFailed = false
        syncUnavailable = !PenController.shared.isConnected
        guard !syncUnavailable else { syncRows = []; scheduleSyncRecheck(); return }
        // D7:笔录音中禁同步——录音中笔拒绝文件传输,列表查询也会超时误显示"暂无"
        syncRecordingBusy = RecordingManager.shared.isLive && RecordingManager.shared.source == .pen
        guard !syncRecordingBusy else { syncRows = []; scheduleSyncRecheck(); return }
        // 有同步/补取在传:笔忙着传文件回答不了列表查询(会超时空列表),显示"同步中"等自查
        syncBusy = PenController.shared.isSyncBusy
        guard !syncBusy else { syncRows = []; scheduleSyncRecheck(); return }
        guard syncRows.isEmpty else { return }   // 列表已加载就别打扰用户勾选
        syncLoading = true
        PenController.shared.fetchFileList { [weak self] files in
            Task { @MainActor in await self?.onPenFiles(files) }
        }
    }

    private func scheduleSyncRecheck() {
        syncRecheckTask?.cancel()
        syncRecheckTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            guard let self, !Task.isCancelled, self.syncSheet else { return }
            self.refreshPenSync()
        }
    }

    /// 拿到机身列表 → 后端去重预检,只留 status=new 的(已上传/已删除的不展示)。
    private func onPenFiles(_ files: [PenFile]) async {
        // D7:正在录的那个机身文件不能出现在列表(导入它=和实时流双传,还白耗几分钟下载)
        let cur = PenController.shared.currentRecordingPenFile
        let candidates = files.filter { $0.name != cur }
        guard let preview = try? await ConsultantRepo.penSyncPreview(candidates.map { ($0.name, $0.recordedAt) }) else {
            // D8:预检失败(弱网)绝不能当"全是新的"展示——那会引导用户全选重复导入
            syncPreviewFailed = true
            syncLoading = false
            return
        }
        var statusByName: [String: String] = [:]
        for i in preview.items ?? [] {
            if let n = i.name { statusByName[n] = i.status ?? "new" }
        }
        syncRows = candidates
            .filter { (statusByName[$0.name] ?? "new") == "new" }
            .sorted {
                // D11:时间未知的沉底(裸文件名'n'>数字会浮顶),正常按时刻倒序
                switch ($0.recordedAt, $1.recordedAt) {
                case let (a?, b?): return a > b
                case (nil, _?): return false
                case (_?, nil): return true
                default: return $0.name > $1.name
                }
            }
            .map { PenSyncRow(file: $0) }   // 默认不勾选,避免误导入一堆
        syncLoading = false
    }

    func toggleSyncRow(_ id: String) {
        guard let i = syncRows.firstIndex(where: { $0.id == id }) else { return }
        syncRows[i].checked.toggle()
    }
    func toggleSyncAll() {
        let target = !syncAllChecked
        for i in syncRows.indices { syncRows[i].checked = target }
    }

    // ── 按日期分组:点日期头一键勾选那一天(用户需求 2026-07-04)──

    private func dateOf(_ r: PenSyncRow) -> String {
        r.file.recordedAt.map { String($0.prefix(10)) } ?? "时间未知"
    }

    /// 分组保持 syncRows 的倒序(最近日期在上)。
    var syncSections: [(date: String, rows: [PenSyncRow])] {
        var order: [String] = []
        var groups: [String: [PenSyncRow]] = [:]
        for r in syncRows {
            let d = dateOf(r)
            if groups[d] == nil { order.append(d) }
            groups[d, default: []].append(r)
        }
        return order.map { ($0, groups[$0]!) }
    }

    func dateAllChecked(_ date: String) -> Bool {
        let rows = syncRows.filter { dateOf($0) == date }
        return !rows.isEmpty && rows.allSatisfy(\.checked)
    }

    func toggleSyncDate(_ date: String) {
        let target = !dateAllChecked(date)
        for i in syncRows.indices where dateOf(syncRows[i]) == date {
            syncRows[i].checked = target
        }
    }

    /// 撤回删除申请(审批前随时可撤)。
    func withdrawDelete(_ rid: Int) {
        Task {
            do {
                _ = try await ConsultantRepo.withdrawDeleteRequest(rid)
                RecordingManager.shared.toast = "已撤回删除申请"
                load()
            } catch {
                RecordingManager.shared.toast = "撤回失败，请重试"
            }
        }
    }

    /// 删除申请被拒 → 点「知道了」关掉红条。
    func dismissReject(_ rid: Int) {
        Task {
            _ = try? await ConsultantRepo.dismissDeleteReject(rid)
            load()
        }
    }

    /// 删除待整理片段:后端裁决——≤5分钟直接删(deleted=true),>5分钟生成审批单(2026-07-04 用户拍板)。
    func requestDelete(_ rid: Int) {
        Task {
            do {
                let r = try await ConsultantRepo.requestDeleteRecording(rid)
                if let e = r.error {
                    RecordingManager.shared.toast = e
                } else if r.deleted == true {
                    RecordingManager.shared.toast = "已删除"
                } else {
                    RecordingManager.shared.toast = "已提交删除申请，等待管理员审批"
                }
                load()
            } catch let err {
                RecordingManager.shared.toast = (err as? APIError)?.errorDescription ?? "删除失败，请重试"
            }
        }
    }

    /// 导入选中:先逐段建「处理中」占位(能看到在同步谁),再交给 PenController 后台逐个下载+上传。
    func importSelected() {
        let sel = syncRows.filter(\.checked).map(\.file)
        guard !sel.isEmpty else { return }
        syncRecheckTask?.cancel()
        syncSheet = false
        RecordingManager.shared.toast = "已导入 \(sel.count) 段到待整理，正在同步…"
        Task {
            for f in sel { await RecordingManager.shared.registerSyncPlaceholder(for: f) }
            PenController.shared.startSync(files: sel)
            load()   // 占位已建好 → 立刻刷出「后台同步中」行
            try? await Task.sleep(nanoseconds: 10_000_000_000)
            load()
        }
    }

    private func sortKey(_ r: PendingRecording) -> String {
        r.recordedAt ?? (r.serviceDate.map { "\($0) \(r.startHm ?? "")" }) ?? r.createdAt ?? ""
    }
}

extension PendingRecording {
    /// 删除申请审批中。
    var deletePending: Bool { deleteRequestStatus == "pending" }
    /// 删除申请被拒且还没点「知道了」。
    var deleteRejected: Bool { deleteRequestStatus == "rejected" }

    /// 超过绑定窗口(7天,用户拍板 2026-07-04):不允许再绑定,只能删除。
    var isBeyondBindWindow: Bool {
        guard !isProcessing else { return false }
        let day = recordedAt.map { String($0.prefix(10)) } ?? serviceDate
        guard let day, !day.isEmpty,
              let minDay = DateHelper.addDays(DateHelper.today(), -7) else { return false }
        return day < minDay
    }

    /// 非当天且尚未绑定(用户需求 2026-07-04:标红提醒顾问尽快绑定,别越攒越久)。
    var isStaleUnbound: Bool {
        guard !isProcessing, deleteRequestStatus != "pending" else { return false }
        let day = recordedAt.map { String($0.prefix(10)) } ?? serviceDate
        guard let day, !day.isEmpty else { return false }
        return day < DateHelper.today()
    }

    /// 待整理片段状态 → (文案, pill)。
    var pendingStatus: (String, PillKind) {
        if deletePending { return ("删除审批中", .danger) }
        if isProcessing { return ("后台同步中", .clay) }
        switch asrStatus {
        case "done": return ("待绑定", .warn)
        case "failed": return ("识别失败", .danger)
        case "awaiting_intake": return ("待绑定", .warn)
        default: return ("识别中", .run)
        }
    }
}
