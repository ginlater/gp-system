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

    // ★2.2.1 防"删了又冒出来"(对齐安卓 PendingViewModel.kt):点删除的瞬间若已有一个刷新请求
    //   在路上(它拿的是删除前的数据),回来就会把删掉的那行【又盖回列表】——顾问再点删除就打到
    //   已不存在的 id,报"录音不存在"。记下刚删的 id,15 秒内任何列表更新都把它们过滤掉。
    private var recentlyDeleted: [Int: Date] = [:]

    private func filterDeleted(_ list: [PendingRecording]) -> [PendingRecording] {
        recentlyDeleted = recentlyDeleted.filter { Date().timeIntervalSince($0.value) < 15 }
        guard !recentlyDeleted.isEmpty else { return list }
        return list.filter { recentlyDeleted[$0.id] == nil }
    }

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
                recordings = filterDeleted(recs).sorted { sortKey($0) > sortKey($1) }   // 录制时间倒序
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
            // ★对齐安卓2.1.6:显示"日期 · 几点–几点 · 时长"——顾问靠时间段回忆是哪位顾客
            let durLabel = file.durationSec > 0
                ? (file.durationSec >= 60 ? "\(file.durationSec / 60)分\(String(format: "%02d", file.durationSec % 60))秒" : "\(file.durationSec)秒")
                : ""
            guard let ra = file.recordedAt, ra.count >= 19 else {
                return [file.recordedAt ?? file.name, durLabel].filter { !$0.isEmpty }.joined(separator: " · ")
            }
            let day = String(ra.prefix(10))
            var range = String(ra.dropFirst(11).prefix(5))
            if file.durationSec > 0 {
                let f = DateFormatter()
                f.locale = Locale(identifier: "en_US_POSIX")
                f.dateFormat = "yyyy-MM-dd HH:mm:ss"
                if let st = f.date(from: ra) {
                    let hf = DateFormatter()
                    hf.locale = Locale(identifier: "en_US_POSIX")
                    hf.dateFormat = "HH:mm"
                    range += " – " + hf.string(from: st.addingTimeInterval(TimeInterval(file.durationSec)))
                }
            }
            return [day, range, durLabel].filter { !$0.isEmpty }.joined(separator: " · ")
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
        // ★2.2.2(对齐安卓2.1.9):走 90s 缓存——刚连上笔时自动补传已经读过一次清单,这里秒开
        PenController.shared.fetchFileList(allowCache: true) { [weak self] files in
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
        // ★2.2.1(#6,对齐安卓2.1.9):预检按每批30分批——几十段一次性一个请求,弱网必超时,
        //   顾问看到"网络异常"。任一批失败整体判失败给重试(不半渲染,防引导重复导入)。
        var statusByName: [String: String] = [:]
        var idx = 0
        while idx < candidates.count {
            let batch = Array(candidates[idx ..< min(idx + 30, candidates.count)])
            guard let preview = try? await ConsultantRepo.penSyncPreview(batch.map { ($0.name, $0.recordedAt) }) else {
                // D8:预检失败(弱网)绝不能当"全是新的"展示——那会引导用户全选重复导入
                syncPreviewFailed = true
                syncLoading = false
                return
            }
            for i in preview.items ?? [] {
                if let n = i.name { statusByName[n] = i.status ?? "new" }
            }
            idx += 30
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
    /// ★2.2.1:真删掉了 → 记入"刚删"名单(15s 内轮询不许刷回来)+ 取消这段还在传/在搬运的任务
    ///   (不取消的话后台 URLSession 传完,手机麦段无 pen_file 墓碑兜不住 → 删掉的录音复活)。
    func requestDelete(_ rid: Int) {
        Task {
            do {
                let r = try await ConsultantRepo.requestDeleteRecording(rid)
                if let e = r.error {
                    if e.contains("不存在") { removeDeletedRow(rid) }
                    else { RecordingManager.shared.toast = e }
                } else if r.deleted == true {
                    recentlyDeleted[rid] = Date()
                    RecordingManager.shared.cancelTasks(placeholderId: rid)
                    recordings.removeAll { $0.id == rid }
                    RecordingManager.shared.toast = "已删除"
                    load()
                } else {
                    RecordingManager.shared.toast = "已提交删除申请，等待管理员审批"
                    load()
                }
            } catch let err {
                let msg = (err as? APIError)?.errorDescription ?? ""
                // 服务端说"录音不存在"=这段其实早删掉了(列表是旧的)→ 不吓唬顾问,静默移除该行
                if msg.contains("不存在") { removeDeletedRow(rid) }
                else { RecordingManager.shared.toast = msg.isEmpty ? "删除失败，请重试" : msg }
            }
        }
    }

    private func removeDeletedRow(_ rid: Int) {
        recentlyDeleted[rid] = Date()
        recordings.removeAll { $0.id == rid }
        load()
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

    /// ★2.2.1(对齐安卓 syncingWhenLabel):「同步中」占位行的时间标签——
    /// "2026-07-12 · 15:40 – 15:45 · 4分23秒"。占位建的时候就带了开始时刻和时长,音频虽然
    /// 还在路上,顾问已经能靠时段认出是哪位顾客并直接绑定。缺哪样省哪样,拿不到时间返回 nil。
    var syncingWhenLabel: String? {
        guard let ra = recordedAt, ra.count >= 16 else { return nil }
        let day = String(ra.prefix(10))
        let startHm = String(ra.dropFirst(11).prefix(5))
        let durLabel = durationLabel?.nilIfBlank
        var durSec = 0
        if let l = durLabel {
            if let m = l.range(of: #"(\d+)分(\d+)秒"#, options: .regularExpression) {
                let parts = l[m].split(whereSeparator: { !$0.isNumber })
                if parts.count == 2 { durSec = (Int(parts[0]) ?? 0) * 60 + (Int(parts[1]) ?? 0) }
            } else if let m = l.range(of: #"(\d+)秒"#, options: .regularExpression) {
                durSec = Int(l[m].dropLast(1)) ?? 0
            }
        }
        var range = startHm
        if durSec > 0 {
            let f = DateFormatter()
            f.locale = Locale(identifier: "en_US_POSIX")
            f.dateFormat = "yyyy-MM-dd HH:mm:ss"
            if let st = f.date(from: ra) {
                let hf = DateFormatter()
                hf.locale = Locale(identifier: "en_US_POSIX")
                hf.dateFormat = "HH:mm"
                range += " – " + hf.string(from: st.addingTimeInterval(TimeInterval(durSec)))
            }
        }
        return [day, range, durLabel].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · ")
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
