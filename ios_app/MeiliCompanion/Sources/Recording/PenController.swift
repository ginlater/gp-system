import Foundation

/// 声云陪伴笔控制器。android 端对应 `soni/SoniPenController.java`(已上线,本类逐字段对齐其连接判定)。
/// 真机包 PNode SDK(libPNote.a,仅 iphoneos);模拟器用桩(陪伴笔恒不可用)。
///
/// 连接判定(关键,对齐 android):
///  - `linkUp`        = cmd=2 链路状态(连上/断开)
///  - `verifiedConnected` = 收到 cmd≥3 真业务回包才 true(光扫描发现/链路就绪都不算)
///  - 对外"真在线" `isConnected` = verifiedConnected && linkUp && 最近 12s 内有回包
///  - 连上后 7s 内无真回包 → 判假连接、断开(没开机的笔回不了真包 → 不会误显示已连)
/// 录音:startRecord → deviceRecord 累积裸 opus → stopRecord → OggOpusWriter 包 .ogg → 上传。
///
/// ⚠ 线程模型:SDK 的 delegate 回调在 SDK 后台线程触发,主线程也会调命令。所有成员状态 + opus +
///   finishUpload + SDK 命令 + 定时器**一律在专用串行队列 `q` 上序列化**,杜绝 opus/状态的数据竞争
///   (并发读写 Data 会 EXC_BAD_ACCESS 崩 + 音频乱码)。回调入口立即 `q.async` 转入;回 UI 用 main.async。

#if targetEnvironment(simulator)

/// 模拟器桩:无 libPNote,陪伴笔不可用。
final class PenController {
    static let shared = PenController()
    weak var manager: RecordingManager?
    var isConnected: Bool { false }
    func setup() {}
    func startSearch() {}
    func stopSearch() {}
    func connect(name: String, address: String?) {}
    func syncTime() {}
    func refreshStatus() {}
    func startRecord() {}
    func stopRecord() {}
    func endRecordingOnDisconnect() {}
    func forgetAndDisconnect() {}
    func fetchFileList(_ completion: @escaping ([PenFile]) -> Void) { completion([]) }
    func startSync(files: [PenFile]) {}
    var isSyncBusy: Bool { false }
}

#else

final class PenController: NSObject, WindBleDelegate {
    static let shared = PenController()
    weak var manager: RecordingManager?

    private var pen: PNode { PNode.shared() }

    /// 专用串行队列:所有成员状态 + opus + SDK 命令 + 定时器全部在此序列化。
    private let q = DispatchQueue(label: "com.meili.pen.controller")

    // ── 连接判定(只在 q 上读写)──
    private var linkUp = false
    private var verifiedConnected = false
    private var lastRx = Date(timeIntervalSince1970: 0)
    private var connectingAddress: String?
    private var hbMissed = 0
    private var handshakeWork: DispatchWorkItem?
    private var heartbeatWork: DispatchWorkItem?
    private let handshakeTimeout: TimeInterval = 7
    private let staleRx: TimeInterval = 12
    private let hbInterval: TimeInterval = 8
    private static let kLastMac = "pen_last_mac"
    private static let kKnownMacs = "pen_known_macs"   // 用过的所有笔(用户有多支换着用),任一支开机都自动连

    /// 已知笔 MAC 集合(含历史 kLastMac,平滑迁移)。
    private var knownMacs: Set<String> {
        var s = Set(UserDefaults.standard.stringArray(forKey: Self.kKnownMacs) ?? [])
        if let last = UserDefaults.standard.string(forKey: Self.kLastMac) { s.insert(last) }
        return s
    }
    private func rememberMac(_ mac: String) {
        var s = knownMacs; s.insert(mac)
        UserDefaults.standard.set(Array(s), forKey: Self.kKnownMacs)
        UserDefaults.standard.set(mac, forKey: Self.kLastMac)
    }
    private func forgetMac(_ mac: String) {
        var s = knownMacs; s.remove(mac)
        UserDefaults.standard.set(Array(s), forKey: Self.kKnownMacs)
        if UserDefaults.standard.string(forKey: Self.kLastMac) == mac {
            UserDefaults.standard.removeObject(forKey: Self.kLastMac)
        }
    }

    // ── 录音(只在 q 上读写)──
    private var recording = false
    private var recPaused = false                                 // 笔处于暂停(cmd3 state=2)
    private var penNotRecordingCount = 0                          // 心跳cmd9连续"笔不在录"计数(笔自行停了的检测)
    private var finishing = false   // 一次性收尾哨兵:防 finishUpload 双触发 → 双上传 / 并发读 opus
    private var opus = Data()
    private var recordedAt: String?
    private var recordStartAt: Date?                              // 墙钟开始(防截断闸比值用)
    private var penFileName: String?                              // 本段笔机身文件名(cmd=11,补下载用)
    private var sn: String?                                       // 笔SN(cmd=7,上传审计/管理员绑定)

    // ── 卡流看门狗(对齐 android streamStallWatch:>6s 无新帧=流不完整)──
    private var lastFrameAt = Date(timeIntervalSince1970: 0)
    private var streamIncomplete = false
    private var stallWork: DispatchWorkItem?
    private let stallThreshold: TimeInterval = 6

    // ── 断开自动重连(对齐 android closeSuccess 按 MAC 循环扫连)──
    private var reconnectWork: DispatchWorkItem?
    private let reconnectInterval: TimeInterval = 8

    // ── 机身下载机(两种任务共用一条下载通道:补取截断段 / 从陪伴笔同步)──
    private enum DownloadJob { case recovery, sync(PenFile) }
    private var downloadJob: DownloadJob?
    private var pendingRecovery: (fileName: String, partial: Data, recordedAt: String?)?
    private var syncQueue: [PenFile] = []                         // 「从陪伴笔同步」待下载队列
    private var downloading = false
    private var download = Data()
    private var downloadStallWork: DispatchWorkItem?              // 15s 无数据=下载死了
    private var recoveryDeadlineWork: DispatchWorkItem?           // 总兜底:超时放弃补下载,直传诚实部分件
    private let downloadStallTimeout: TimeInterval = 15
    private let recoveryDeadline: TimeInterval = 600

    private var currentDownloadFileName: String? {
        switch downloadJob {
        case .recovery: return pendingRecovery?.fileName
        case .sync(let f): return f.name
        case nil: return nil
        }
    }

    // ── 机身文件列表(cmd=4,「从陪伴笔同步」用)──
    private var fileListEntries: [PenFile] = []
    private var fileListCompletion: (([PenFile]) -> Void)?
    private var fileListTimeout: DispatchWorkItem?

    func setup() {
        pen.delegate = self
        PNode.setShowLog(true)   // 联调期打开 SDK 内部日志(经 logInfo 回调进 penlog.txt)
        PenLog.d("PenController.setup lastMac=\(UserDefaults.standard.string(forKey: Self.kLastMac) ?? "无")")
        q.async { self.loadPersistedSyncQueue() }   // 上次没传完的同步任务,连上笔自动续
    }

    /// 对外"真在线"判据(= android isPenAlive)。q.sync 读,避免裸读跨线程状态。
    var isConnected: Bool {
        q.sync { verifiedConnected && linkUp && Date().timeIntervalSince(lastRx) < staleRx }
    }

    // 命令一律转 q:串行 + 不卡主线程(android 踩过"SDK 把 BLE 跑主线程"坑)。
    func startSearch() { q.async { PenLog.d("cmd→ startSearch"); self.pen.startSearch() } }
    func stopSearch() { q.async { self.pen.stopSearch() } }
    func connect(name: String, address: String?) {
        q.async {
            PenLog.d("cmd→ connect name=\(name) addr=\(address ?? "nil")")
            self.connectingAddress = address
            if !address.isNilOrEmpty { self.pen.connectDeviceAndAddress(name, address!) }
            else { self.pen.connectDevice(name) }
        }
    }
    func syncTime() { q.async { self.pen.syncTime() } }
    func refreshStatus() { q.async { if self.linkUp { self.pen.getCBC() } } }

    /// App 点击开始:发命令给笔。真正进入录音态由 cmd=3 record_state=1 驱动(笔/App 殊途同归)。
    func startRecord() { q.async { self.pen.startRecord() } }
    /// App 点击停止:发命令给笔。结束上传由 cmd=3 record_state=0 驱动。
    func stopRecord() { q.async { self.pen.stopRecord() } }

    /// 录音中陪伴笔断开/关机:结束本段。
    /// 机身文件才是完整真相(距离断连/关蓝牙时笔还在录!),有文件名一律标记流不完整 →
    /// 收尾走"等重连从机身补下载全段";10min 兜底退回直传诚实部分件。
    func endRecordingOnDisconnect() {
        q.async {
            guard self.recording else { return }
            self.recording = false
            if self.penFileName?.isEmpty == false { self.streamIncomplete = true }
            self.finishUpload()
        }
    }

    /// SN 被后端拒绝(绑给了别的顾问):断开、停止重连、遗忘这支笔(只忘这一支,别的照常自动连)。
    func forgetAndDisconnect() {
        q.async {
            PenLog.d("SN被拒 → 断开并遗忘该笔 \(self.connectingAddress ?? "?")")
            if let a = self.connectingAddress, !a.isEmpty { self.forgetMac(a) }
            self.stopReconnect()
            self.pen.closeConnect()
        }
    }

    /// 在 q 上调用。`finishing` 一次性哨兵 + opus 值快照,杜绝双上传与并发读。
    /// 防截断闸(对齐 android finishSessionEnqueue):流不完整/流秒数远小于墙钟 → 不直传截断件,
    /// 回落「从笔机身补下载全段」;补不到才退回直传诚实部分件(时长=实际流,不虚报)。
    private func finishUpload() {
        guard !finishing else { return }
        finishing = true
        stopStallWatch()
        let snapshot = opus          // Data 值拷贝,后续 wrap 用快照,opus 可安全复用
        let at = recordedAt
        let wall = recordStartAt.map { Int(Date().timeIntervalSince($0)) } ?? 0
        let streamSec = OggOpusWriter.seconds(snapshot.count)
        let suspectTruncated = streamIncomplete || (wall >= 30 && streamSec < Int(Double(wall) * 0.6))
                PenLog.d("收尾 wall=\(wall)s stream=\(streamSec)s incomplete=\(streamIncomplete) → \(suspectTruncated ? "疑截断,补下载" : "直传")")
        if suspectTruncated, let fn = penFileName, !fn.isEmpty {
            // 上一段还有没救完的 → 先把它的部分件交出去,别丢
            // (部分件不带 pen_file:带了会被后端按 (上传人,pen_file) 去重,挡住之后真正的全段)
            if let old = pendingRecovery {
                cancelActiveDownload(requeueSync: true)
                deliver(old.partial, old.recordedAt, penFile: nil)
            }
            pendingRecovery = (fn, snapshot, at)
            DispatchQueue.main.async { self.manager?.penRecoveryStarted() }
            scheduleRecoveryDeadline()
            if linkUp { startRecoveryDownload() }   // 断连时:自动重连成功(markPenResponded)后再触发
            return
        }
        deliver(snapshot, at, penFile: penFileName)
    }

    /// 在 q 上调用:把一段裸 opus 包成 .ogg 落盘并回调上传。
    private func deliver(_ raw: Data, _ at: String?, penFile: String?) {
        let snValue = sn
        guard raw.count >= 40, let ogg = OggOpusWriter.wrap(raw) else {
            DispatchQueue.main.async { self.manager?.penDidFinish(fileURL: nil, durationSec: 0, recordedAt: at, penFile: penFile, sn: snValue) }
            return
        }
        let dur = OggOpusWriter.seconds(raw.count)
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("pen_\(Int(Date().timeIntervalSince1970))_\(raw.count).ogg")
        do {
            try ogg.write(to: url)
            DispatchQueue.main.async { self.manager?.penDidFinish(fileURL: url, durationSec: dur, recordedAt: at, penFile: penFile, sn: snValue) }
        } catch {
            DispatchQueue.main.async { self.manager?.penDidFinish(fileURL: nil, durationSec: 0, recordedAt: at, penFile: penFile, sn: snValue) }
        }
    }

    // MARK: - 机身补下载(均在 q 上)

    /// 开始从笔机身下载全段。前提:linkUp、笔不在录音(录音中笔拒绝文件传输)。
    private func startRecoveryDownload() {
        guard let rec = pendingRecovery, !downloading, !recording, linkUp else { return }
        beginDownload(.recovery, fileName: rec.fileName)
    }

    /// 在 q 上:起一个下载任务(补取/同步共用)。停录后笔要缓 2s 才接受文件命令。
    private func beginDownload(_ job: DownloadJob, fileName: String) {
        downloading = true
        downloadJob = job
        download.removeAll(keepingCapacity: true)
        PenLog.d("★下载开始 file=\(fileName)")
        q.asyncAfter(deadline: .now() + 2) { [weak self] in
            guard let self, self.downloading else { return }
            self.pen.startGetFile(fileName, "0")
            self.bumpDownloadStall()
        }
    }

    /// 当前下载失败 → 按任务类型收尾。
    private func failCurrentDownload() {
        switch downloadJob {
        case .recovery: failRecovery()
        case .sync(let f): failSync(f)
        case nil: break
        }
    }

    /// 中止进行中的下载(录音开始让路/断线/新收尾接管)。同步任务塞回队首,补取任务保留在 pendingRecovery。
    private func cancelActiveDownload(requeueSync: Bool, sendStop: Bool = true) {
        guard downloading else { return }
        if sendStop { pen.stopGetFile(currentDownloadFileName ?? "") }
        if requeueSync, case .sync(let f) = downloadJob, !syncQueue.contains(f) {
            syncQueue.insert(f, at: 0)
        }
        downloading = false
        downloadJob = nil
        downloadStallWork?.cancel()
        download.removeAll(keepingCapacity: false)
        persistSyncQueue()
    }

    // ── 「从陪伴笔同步」──

    /// 拉取机身文件列表(cmd=4,10s 超时返回已收到的)。回调在主线程。
    func fetchFileList(_ completion: @escaping ([PenFile]) -> Void) {
        q.async {
            guard self.linkUp else { DispatchQueue.main.async { completion([]) }; return }
            self.fileListEntries = []
            self.fileListCompletion = completion
            self.pen.getRecordFileList()
            self.fileListTimeout?.cancel()
            let w = DispatchWorkItem { [weak self] in self?.finishFileList() }
            self.fileListTimeout = w
            self.q.asyncAfter(deadline: .now() + 10, execute: w)
        }
    }

    private func finishFileList() {
        guard let cb = fileListCompletion else { return }
        fileListCompletion = nil
        fileListTimeout?.cancel()
        let entries = fileListEntries
        PenLog.d("cmd4 文件列表完成,共\(entries.count)个")
        DispatchQueue.main.async { cb(entries) }
    }

    /// cmd=4 文件列表(data 是数组,可能分多包,finish=1 结束)。
    private func handleFileList(_ map: [String: Any]) {
        if let arr = map["data"] as? [[String: Any]] {
            for e in arr {
                var name = str(e["name"])
                if name.isEmpty { name = str(e["fileName"]) }
                guard !name.isEmpty else { continue }
                let size = intVal(e["size"]) ?? intVal(e["file_size"]) ?? 0
                let dur = intVal(e["duration"]) ?? intVal(e["dur"]) ?? intVal(e["time"]) ?? 0
                fileListEntries.append(PenFile(name: name, sizeBytes: size, durationSec: dur,
                                               recordedAt: Self.parseRecordedAt(from: name)))
            }
        }
        let finish = str(map["finish"])
        if finish == "1" || finish == "true" { finishFileList() }
    }

    // ── 同步队列持久化:App 被杀/重启不丢,连上笔自动续传 ──
    private static let kSyncQueue = "pen_sync_queue"

    private var currentSyncFile: PenFile? {
        if case .sync(let f) = downloadJob { return f }
        return nil
    }

    /// 在 q 上:把(在下的+排队的)同步任务落盘。
    private func persistSyncQueue() {
        let all = (currentSyncFile.map { [$0] } ?? []) + syncQueue
        if let data = try? JSONEncoder().encode(all) {
            UserDefaults.standard.set(data, forKey: Self.kSyncQueue)
        }
    }

    /// 启动时恢复上次没传完的队列(连上笔后 kickSync 自动续)。
    private func loadPersistedSyncQueue() {
        guard let data = UserDefaults.standard.data(forKey: Self.kSyncQueue),
              let files = try? JSONDecoder().decode([PenFile].self, from: data),
              !files.isEmpty else { return }
        syncQueue = files
        PenLog.d("★恢复上次未完成的同步队列 \(files.count)个,连上笔后自动续传")
    }

    /// 是否有同步/补取下载在跑或在排队(笔忙着传文件时没法回答列表查询,弹层要显示"同步中"而非"暂无")。
    var isSyncBusy: Bool {
        q.sync { downloading || !syncQueue.isEmpty || pendingRecovery != nil }
    }

    /// 把选中的机身文件排队下载→包.ogg→上传(带 pen_file 去重)。补取任务优先。
    func startSync(files: [PenFile]) {
        q.async {
            for f in files where !self.syncQueue.contains(f) && self.currentSyncFile != f {
                self.syncQueue.append(f)
            }
            PenLog.d("★同步排队 \(files.count)个,队列共\(self.syncQueue.count)个")
            self.persistSyncQueue()
            self.kickSync()
        }
    }

    /// 在 q 上:笔空闲且无下载在跑时,起下一个下载(补取截断段优先于同步)。
    private func kickSync() {
        guard !downloading, !recording, linkUp else { return }
        if pendingRecovery != nil { startRecoveryDownload(); return }
        guard !syncQueue.isEmpty else { return }
        let f = syncQueue.removeFirst()
        beginDownload(.sync(f), fileName: f.name)
        persistSyncQueue()   // 在下的那个也在盘上(currentSyncFile),中途被杀不丢
    }

    private func completeSync(_ f: PenFile) {
        downloading = false
        downloadJob = nil
        downloadStallWork?.cancel()
        persistSyncQueue()   // 这个下完了,从盘上去掉
        let data = download
        download.removeAll(keepingCapacity: false)
        let remaining = syncQueue.count
        PenLog.d("★同步下载完成 \(f.name) \(data.count)B,剩余\(remaining)")
        let snValue = sn
        if data.count >= 40, let ogg = OggOpusWriter.wrap(data) {
            let url = FileManager.default.temporaryDirectory.appendingPathComponent("sync_\(Int(Date().timeIntervalSince1970))_\(data.count).ogg")
            do {
                try ogg.write(to: url)
                let dur = OggOpusWriter.seconds(data.count)
                DispatchQueue.main.async {
                    self.manager?.penSyncFileReady(fileURL: url, durationSec: dur,
                        recordedAt: f.recordedAt, penFile: f.name, sn: snValue, remaining: remaining)
                }
            } catch {
                DispatchQueue.main.async { self.manager?.penSyncFileFailed(f.name, remaining: remaining) }
            }
        } else {
            DispatchQueue.main.async { self.manager?.penSyncFileFailed(f.name, remaining: remaining) }
        }
        q.asyncAfter(deadline: .now() + 1) { [weak self] in self?.kickSync() }
    }

    private func failSync(_ f: PenFile) {
        downloading = false
        downloadJob = nil
        downloadStallWork?.cancel()
        persistSyncQueue()   // 失败=放弃这个(可重新导入),从盘上去掉
        download.removeAll(keepingCapacity: false)
        let remaining = syncQueue.count
        PenLog.d("★同步下载失败 \(f.name),剩余\(remaining)")
        DispatchQueue.main.async { self.manager?.penSyncFileFailed(f.name, remaining: remaining) }
        q.asyncAfter(deadline: .now() + 1) { [weak self] in self?.kickSync() }
    }

    /// 下载数据看门狗:15s 无新数据 → 判下载死亡,按任务类型收尾。
    private func bumpDownloadStall() {
        downloadStallWork?.cancel()
        let w = DispatchWorkItem { [weak self] in
            guard let self, self.downloading else { return }
            PenLog.d("★下载 \(Int(self.downloadStallTimeout))s 无数据 → 放弃")
            self.pen.stopGetFile(self.currentDownloadFileName ?? "")
            self.failCurrentDownload()
        }
        downloadStallWork = w
        q.asyncAfter(deadline: .now() + downloadStallTimeout, execute: w)
    }

    /// 补下载成功(cmd=5 state=0):下载件若比部分件更全就用下载件。
    private func completeRecovery() {
        guard let rec = pendingRecovery else { return }
        downloading = false
        downloadJob = nil
        downloadStallWork?.cancel()
        recoveryDeadlineWork?.cancel()
        pendingRecovery = nil
        q.asyncAfter(deadline: .now() + 1) { [weak self] in self?.kickSync() }
        let full = download.count > rec.partial.count ? download : rec.partial
                PenLog.d("★补下载完成 下载=\(download.count)B 实时流=\(rec.partial.count)B → 用\(download.count > rec.partial.count ? "下载件" : "部分件")")
        download.removeAll(keepingCapacity: false)
        deliver(full, rec.recordedAt, penFile: rec.fileName)
    }

    /// 补下载失败/超时:直传诚实部分件(时长=实际流秒数,不虚报墙钟)。
    /// 部分件不带 pen_file,给之后可能的完整版(手动同步/重试)留入库通道。
    private func failRecovery() {
        guard let rec = pendingRecovery else { return }
        downloading = false
        downloadJob = nil
        downloadStallWork?.cancel()
        recoveryDeadlineWork?.cancel()
        pendingRecovery = nil
        q.asyncAfter(deadline: .now() + 1) { [weak self] in self?.kickSync() }
        download.removeAll(keepingCapacity: false)
        deliver(rec.partial, rec.recordedAt, penFile: nil)
    }

    /// 总兜底:10 分钟内没救回来(反复断连等) → 放弃,直传部分件,不让「保存中」无限挂。
    private func scheduleRecoveryDeadline() {
        recoveryDeadlineWork?.cancel()
        let w = DispatchWorkItem { [weak self] in
            guard let self, self.pendingRecovery != nil else { return }
                        PenLog.d("★补下载总兜底超时 → 直传部分件")
            if self.downloading { self.pen.stopGetFile(self.pendingRecovery?.fileName ?? "") }
            self.failRecovery()
        }
        recoveryDeadlineWork = w
        q.asyncAfter(deadline: .now() + recoveryDeadline, execute: w)
    }

    // MARK: - 卡流看门狗(均在 q 上)

    private func startStallWatch() {
        stopStallWatch()
        scheduleStallTick()
    }
    private func stopStallWatch() { stallWork?.cancel(); stallWork = nil }
    private func scheduleStallTick() {
        let w = DispatchWorkItem { [weak self] in
            guard let self, self.recording else { return }
            if self.recPaused { self.lastFrameAt = Date() }   // 暂停中无帧是正常的,不算卡流
            if Date().timeIntervalSince(self.lastFrameAt) > self.stallThreshold {
                if !self.streamIncomplete {
                    self.streamIncomplete = true
                    PenLog.d("★卡流看门狗:>\(Int(self.stallThreshold))s 无新帧 → 标记本段流不完整(收尾走补下载)")
                }
                // 无帧可能=笔已被按键停掉(笔自停不发 cmd3=0)→ 立即主动查录音状态,
                // cmd9 回"没在录"一次即收尾(有卡流佐证不用等两次),把检测延迟压到 ~8s 内
                self.pen.getRecordState()
            }
            self.scheduleStallTick()
        }
        stallWork = w
        q.asyncAfter(deadline: .now() + 2, execute: w)
    }

    // MARK: - 断开自动重连(均在 q 上)

    /// 断开/失联后每 8s 重新扫描;扫到上次那支(kLastMac)由 handleDeviceFound 自动连;连上即停。
    private func scheduleReconnect() {
        guard UserDefaults.standard.string(forKey: Self.kLastMac) != nil else { return }
        reconnectWork?.cancel()
        let w = DispatchWorkItem { [weak self] in
            guard let self, !self.linkUp else { return }
                        PenLog.d("★自动重连:重新扫描找上次那支笔…")
            self.pen.startSearch()
            self.scheduleReconnect()
        }
        reconnectWork = w
        q.asyncAfter(deadline: .now() + reconnectInterval, execute: w)
    }
    private func stopReconnect() { reconnectWork?.cancel(); reconnectWork = nil }

    // MARK: - 真连接判定 / 心跳(均在 q 上)

    private func markPenResponded() {
        lastRx = Date()
        guard !verifiedConnected else { return }
        verifiedConnected = true
        handshakeWork?.cancel()
        stopReconnect()
        if let a = connectingAddress, !a.isEmpty {
            rememberMac(a)   // 记住这支笔(多支都记) → 任一支开机自动连
        }
                PenLog.d("★verified=true 收到真回包(cmd≥3)→ 标记已连接")
        DispatchQueue.main.async { self.manager?.penConnected = true }
        startHeartbeat()
        // 重连回来:有没救完的段/没同步完的队列 → 续上(笔在录音则等录完,handleRecordState 会续)
        q.asyncAfter(deadline: .now() + 3) { [weak self] in
            self?.kickSync()
        }
    }

    private func scheduleHandshakeTimeout() {
        handshakeWork?.cancel()
        let w = DispatchWorkItem { [weak self] in
            guard let self, !self.verifiedConnected else { return }
                        PenLog.d("★握手7s超时(无真回包)→ 判假连接、断开、报未连")
            self.pen.closeConnect()
            DispatchQueue.main.async { self.manager?.penConnected = false }
        }
        handshakeWork = w
        q.asyncAfter(deadline: .now() + handshakeTimeout, execute: w)
    }

    private func startHeartbeat() { hbMissed = 0; stopHeartbeat(); scheduleHeartbeat() }
    private func stopHeartbeat() { heartbeatWork?.cancel(); heartbeatWork = nil }

    private func scheduleHeartbeat() {
        let w = DispatchWorkItem { [weak self] in
            guard let self, self.linkUp else { self?.stopHeartbeat(); return }
            let idle = Date().timeIntervalSince(self.lastRx)
            // 录音中且近期有音频帧/回包 → 笔正忙着传音频,别发查询打扰它(对齐 android busy 分支),只续期。
            if self.recording && idle < self.staleRx {
                self.hbMissed = 0
                self.scheduleHeartbeat()
                return
            }
            if idle > self.hbInterval + 4 {
                self.hbMissed += 1
                if self.hbMissed >= 2 {
                                        PenLog.d("★心跳连续2次无回包→判失联、断开、报未连、进自动重连")
                    let wasRecording = self.recording
                    self.verifiedConnected = false
                    self.linkUp = false
                    self.pen.closeConnect()
                    DispatchQueue.main.async {
                        self.manager?.penConnected = false
                        if wasRecording { self.manager?.penDisconnectedWhileRecording() }
                    }
                    self.stopHeartbeat()
                    self.scheduleReconnect()
                    return
                }
            } else {
                self.hbMissed = 0
            }
            self.pen.getRecordState()
            self.scheduleHeartbeat()
        }
        heartbeatWork = w
        q.asyncAfter(deadline: .now() + hbInterval, execute: w)
    }

    // MARK: - WindBleDelegate(回调入口立即转 q;参数用 IUO 防 nil 桥接崩)

    func deviceRecord(_ recordData: Data!) {
        guard let d = recordData else { return }
        q.async {
            self.lastRx = Date()   // ★音频帧=笔在线证明(对齐 android"帧证实活性");录音时防心跳误判失联→断开
            self.lastFrameAt = Date()   // 卡流看门狗依据
            guard self.recording else { return }
            self.opus.append(d)
        }
    }

    /// 机身文件下载数据(补下载路径):累积 + 续期下载看门狗。
    func deviceFileData(_ fileData: Data!) {
        guard let d = fileData else { return }
        q.async {
            self.lastRx = Date()
            guard self.downloading else { return }
            self.download.append(d)
            self.bumpDownloadStall()
        }
    }

    func deviceDataEvent(_ data: String!) {
        PenLog.d("rx: \(data ?? "nil")")
        guard let data, let d = data.data(using: .utf8),
              let map = try? JSONSerialization.jsonObject(with: d) as? [String: Any] else {
            PenLog.d("⚠️ rx 非JSON,已丢弃!")   // 若大量出现=事件格式假设错,连接判定全失效
            return
        }
        q.async { self.handleEvent(map) }
    }

    func logInfo(_ log: String!) {
        PenLog.d("sdk: \(log ?? "nil")")
    }

    // MARK: - cmd 分发(均在 q 上,对齐 android handleEvent)

    private func handleEvent(_ map: [String: Any]) {
        let cmd = str(map["cmd"])
        let data = map["data"] as? [String: Any]
        switch cmd {
        case "1": handleDeviceFound(data)
        case "2": handleConnectState(data)
        case "3": markPenResponded(); handleRecordState(data)
        case "5": markPenResponded(); handleFileState(data)     // 机身文件传输状态(补下载)
        case "6": markPenResponded(); handleBattery(data)
        case "7": markPenResponded(); handleSn(data)
        case "8": markPenResponded(); handleButtonEvent(data)   // 笔上实体按键,必须应答否则笔断开
        case "9": markPenResponded(); handleRecordingStateQuery(data)   // 后连/重连镜像"笔仍在录"
        case "11": markPenResponded(); handleRecordName(data)   // 录音中的机身文件名(补下载凭据)
        case "4": markPenResponded(); handleFileList(map)       // 机身文件列表(data 是数组,finish 在顶层)
        case "10", "14": markPenResponded()
        default: break
        }
    }

    // cmd=8 笔上实体按键:App 必须应答对应 *BtnBackRecord,笔才执行(不应答 → 笔等不到确认会自动断开)。
    private func handleButtonEvent(_ data: [String: Any]?) {
        guard let data else { return }
        let ev = str(data["event"])
                PenLog.d("cmd8 按键 event=\(ev) → 应答 BtnBackRecord")
        switch ev {
        case "1": pen.startBtnBackRecord()      // 笔随后回 cmd=3 state=1 → 镜像开始录音
        case "3": pen.stopBtnBackRecord()       // 笔随后回 cmd=3 state=0 → 收尾上传
        case "5": pen.pauseBtnBackRecord()
        case "7": pen.continueBtnBackRecord()
        default: break
        }
    }

    // cmd=1 设备发现:上报扫描列表给 UI + 自动重连上次连过那支。
    private func handleDeviceFound(_ data: [String: Any]?) {
        guard let data else { return }
        let name = str(data["name"])
        let address = str(data["address"])
        PenLog.d("cmd1 发现设备 name=\(name) addr=\(address)")
        guard !address.isEmpty else { return }
        DispatchQueue.main.async { self.manager?.penFound(name: name, address: address) }
        if !linkUp, knownMacs.contains(address) {
            PenLog.d("cmd1 命中已知的笔(\(address)) → 自动连接")
            connectingAddress = address
            pen.connectDeviceAndAddress(name, address)
        }
    }

    // cmd=2 连接状态
    private func handleConnectState(_ data: [String: Any]?) {
        guard let data else { return }
        let cs = str(data["connect_state"])
        let connected = (cs == "true" || cs == "1")
        if connected {
                        PenLog.d("cmd2 链路就绪(linkUp) —— 还需真回包才算已连")
            linkUp = true
            verifiedConnected = false
            lastRx = Date(timeIntervalSince1970: 0)
            pen.stopSearch()
            q.asyncAfter(deadline: .now() + 2) { [weak self] in
                guard let self, self.linkUp else { return }
                self.pen.syncTime()
                self.pen.getSn()
                self.pen.getCBC()
                self.pen.getRecordState()
            }
            scheduleHandshakeTimeout()
        } else {
                        PenLog.d("cmd2 断开 → 报未连、进自动重连")
            let wasRecording = recording
            linkUp = false
            verifiedConnected = false
            handshakeWork?.cancel()
            stopHeartbeat()
            // 下载中断线:下载作废(协议无续传),补下载/同步任务保留,重连后重下
            cancelActiveDownload(requeueSync: true, sendStop: false)
            DispatchQueue.main.async {
                self.manager?.penConnected = false
                if wasRecording { self.manager?.penDisconnectedWhileRecording() }
            }
            scheduleReconnect()
        }
    }

    // cmd=3 录音态(1开始/0停止/2暂停)。App 点击与笔上按键殊途同归,都以这里为权威。
    private func handleRecordState(_ data: [String: Any]?) {
        guard let data else { return }
        let rs = str(data["record_state"])
        switch rs {
        case "1":
            if recording, recPaused {
                recPaused = false
                PenLog.d("cmd3 恢复录音")
                lastFrameAt = Date()
                DispatchQueue.main.async { self.manager?.penRecordingResumed() }
                return
            }
            if !recording {
                recording = true
                recPaused = false
                penNotRecordingCount = 0
                finishing = false                          // 新一段:复位收尾哨兵
                opus.removeAll(keepingCapacity: true)
                recordedAt = PhoneMicRecorder.wallClock()
                recordStartAt = Date()
                penFileName = nil
                streamIncomplete = false
                lastFrameAt = Date()
                startStallWatch()
                // 笔录音时拒绝文件传输 → 下载让路,录完再续
                cancelActiveDownload(requeueSync: true)
                // 记下本段机身文件名,断流时补下载的凭据(录音中才能查)
                q.asyncAfter(deadline: .now() + 1.5) { [weak self] in
                    guard let self, self.recording else { return }
                    self.pen.getFileNameOnlyRecording()
                }
                                PenLog.d("cmd3 录音开始 → App 进入录音态")
                DispatchQueue.main.async { self.manager?.penRecordingStarted() }
            }
        case "2":
            if recording, !recPaused {
                recPaused = true
                PenLog.d("cmd3 暂停录音")
                DispatchQueue.main.async { self.manager?.penRecordingPaused() }
            }
        case "0":
            if recording {
                recording = false
                recPaused = false
                PenLog.d("cmd3 录音停止 → 结束并上传")
                DispatchQueue.main.async { self.manager?.penRecordingStopped() }
                finishUpload()
                // 若有让路/遗留的补下载/同步任务,笔已空闲 → 续上
                q.asyncAfter(deadline: .now() + 3) { [weak self] in
                    self?.kickSync()
                }
            }
        default:
            break
        }
    }

    /// cmd=5 机身文件传输状态:0完成 4传输中 1文件不在 2offset过大 3其他停止。
    private func handleFileState(_ data: [String: Any]?) {
        guard let data, downloading else { return }
        let st = str(data["record_file_state"])
        switch st {
        case "4": bumpDownloadStall()             // 有进度=活着,续期看门狗
        case "0":
            switch downloadJob {
            case .recovery: completeRecovery()
            case .sync(let f): completeSync(f)
            case nil: break
            }
        case "1", "2", "3":
            PenLog.d("cmd5 下载失败 state=\(st)")
            failCurrentDownload()
        default: break
        }
    }

    /// cmd=11 录音中的机身文件名 → 补下载凭据。
    private func handleRecordName(_ data: [String: Any]?) {
        guard let data else { return }
        var name = str(data["recordName"])
        if name.isEmpty { name = str(data["fileName"]) }
        guard !name.isEmpty else { return }
        penFileName = name
        // 文件名自带真实开始时刻(note20260703-174600.opus) → 校准 recordedAt(镜像段头部缺失时尤其重要)
        if let at = Self.parseRecordedAt(from: name) {
            recordedAt = at
            // 计时同步:镜像段 UI 秒数从 0 起跑不对,校准为真实已录时长
            if recording, let d = Self.wallClockFormatter.date(from: at) {
                let el = Int(Date().timeIntervalSince(d))
                if el > 1 {
                    DispatchQueue.main.async { self.manager?.penElapsedCalibrated(el) }
                }
            }
        }
        PenLog.d("cmd11 本段机身文件名=\(name) recordedAt=\(recordedAt ?? "?")")
    }

    private static let wallClockFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd HH:mm:ss"
        f.locale = Locale(identifier: "en_US_POSIX")
        return f
    }()

    /// 从机身文件名解析录音开始时刻:noteYYYYMMDD-HHMMSS.opus → "YYYY-MM-DD HH:MM:SS"。
    private static func parseRecordedAt(from fileName: String) -> String? {
        guard let m = fileName.range(of: #"\d{8}-\d{6}"#, options: .regularExpression) else { return nil }
        let s = fileName[m]                       // 20260703-174600
        let d = s.prefix(8), t = s.suffix(6)
        return "\(d.prefix(4))-\(d.dropFirst(4).prefix(2))-\(d.suffix(2)) \(t.prefix(2)):\(t.dropFirst(2).prefix(2)):\(t.suffix(2))"
    }

    /// cmd=9 录音状态查询回包(心跳每8s一发,是"笔的真实状态"权威源):
    ///  - 笔在录而 App 不在录 → 镜像进入录音态(头部缺失,收尾走补下载);
    ///  - App 在录而笔已不录(笔上按键自停,cmd3=0 可能不发!) → 连续2次确认后自动收尾;
    ///  - 暂停中(cmd3=2 明确过)不误判为停止。
    private func handleRecordingStateQuery(_ data: [String: Any]?) {
        guard let data else { return }
        let rs = str(data["recordState"])
        if rs != "1" {
            if recording && !recPaused {
                penNotRecordingCount += 1
                // 流也停了(卡流佐证)→ 一次即收尾;流还在只是查询抖动 → 要连续两次防误判
                let need = streamIncomplete ? 1 : 2
                if penNotRecordingCount >= need {
                    PenLog.d("cmd9 \(penNotRecordingCount)次显示笔已不录(App仍在录音态,佐证=\(streamIncomplete ? "流已停" : "连续确认")) → 笔自行停了,自动收尾")
                    penNotRecordingCount = 0
                    recording = false
                    if penFileName?.isEmpty == false { streamIncomplete = true }  // 尾部可能缺 → 补下载兜底
                    DispatchQueue.main.async { self.manager?.penRecordingStopped() }
                    finishUpload()
                }
            }
            return
        }
        penNotRecordingCount = 0
        if recording {
            if recPaused {
                recPaused = false
                PenLog.d("cmd9 笔已恢复录音 → App 回录音态")
                DispatchQueue.main.async { self.manager?.penRecordingResumed() }
            }
            return
        }
        PenLog.d("cmd9 笔在录而App不在 → 镜像进入录音态(头部缺失,收尾走补下载)")
        recording = true
        recPaused = false
        finishing = false
        opus.removeAll(keepingCapacity: true)
        recordedAt = PhoneMicRecorder.wallClock()   // 占位;cmd11 文件名到手后校准为真实开始时刻
        recordStartAt = Date()
        penFileName = nil
        streamIncomplete = true                     // 断线期间的头部拿不到 → 流必不完整
        lastFrameAt = Date()
        startStallWatch()
        cancelActiveDownload(requeueSync: true)
        q.asyncAfter(deadline: .now() + 1.5) { [weak self] in
            guard let self, self.recording else { return }
            self.pen.getFileNameOnlyRecording()
        }
        DispatchQueue.main.async { self.manager?.penRecordingStarted() }
    }

    private func handleBattery(_ data: [String: Any]?) {
        guard let data, let v = intVal(data["cbc"]) else { return }
        let pct = (v == 110) ? 100 : v
        ReminderNotifier.onPenBattery(v)   // 低电量系统通知(≤10%/≤5% 各一次,110=充电中重置)
        DispatchQueue.main.async { self.manager?.penBattery = pct }
    }

    private func handleSn(_ data: [String: Any]?) {
        guard let data else { return }
        let v = str(data["sn"])
        guard !v.isEmpty, v != "00000000" else { return }
        let isNew = (v != sn)
        sn = v
        // 上报 SN 给后端做绑定校验(准/拒决策在后端,fail-open);每支笔本次运行只报一次
        if isNew { DispatchQueue.main.async { self.manager?.penSnReported(v) } }
    }

    // MARK: - helpers

    private func str(_ v: Any?) -> String {
        guard let v else { return "" }
        let s = String(describing: v)
        return s == "null" ? "" : s
    }
    private func intVal(_ v: Any?) -> Int? {
        if let n = v as? NSNumber { return n.intValue }
        if let s = v as? String { return Int(s) }
        return nil
    }
}

private extension Optional where Wrapped == String {
    var isNilOrEmpty: Bool { self?.isEmpty ?? true }
}

#endif
