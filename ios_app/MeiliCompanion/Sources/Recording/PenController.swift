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
    func appDidBecomeActive() {}
    func appDidEnterBackground() {}
    func setAutoConnectSuppressed(_ s: Bool) {}
    func directConnectSystemPen() {}
    func confirmStopOrForceFinish() {}
    var currentRecordingPenFile: String? { nil }
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

    /// 已知笔 MAC 集合(含历史 kLastMac,平滑迁移)。内存缓存(审计 L18:扫描期高频读盘)。
    private var knownMacsCache: Set<String>?
    private var knownMacs: Set<String> {
        if let c = knownMacsCache { return c }
        var s = Set(UserDefaults.standard.stringArray(forKey: Self.kKnownMacs) ?? [])
        if let last = UserDefaults.standard.string(forKey: Self.kLastMac) { s.insert(last) }
        knownMacsCache = s
        return s
    }
    private func rememberMac(_ mac: String) {
        var s = knownMacs; s.insert(mac)
        knownMacsCache = s
        UserDefaults.standard.set(Array(s), forKey: Self.kKnownMacs)
        UserDefaults.standard.set(mac, forKey: Self.kLastMac)
    }
    private func forgetMac(_ mac: String) {
        var s = knownMacs; s.remove(mac)
        knownMacsCache = s
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

    // ── 断开自动重连(对齐 android closeSuccess 按 MAC 循环扫连;审计 P4 加指数退避)──
    private var reconnectWork: DispatchWorkItem?
    private var reconnectDelay: TimeInterval = 8      // 8→16→32→60 封顶,verify 成功复位

    // ── 连接闸门(审计 L3:防"连A期间又连B/同一支反复重连打断自己";SN-deny 按实连笔遗忘)──
    private var connectGate = false                   // 连接尝试进行中,忽略一切 cmd1 自动连
    private var connectGateWork: DispatchWorkItem?
    private var gateGen = 0                           // 闸门代际:旧尝试的10s释放闭包不得碰新尝试
    private var lastVerifiedMac: String?              // 当前实连的笔(verify 时定格)
    private var autoConnectSuppressed = false         // 扫描选笔 sheet 打开期间暂停自动连(用户要自己挑)

    // ── 重连优先原笔(双笔场景:上一段还等着从原笔补取/镜像,连错另一支就拿不到)──
    private var deferredFound: (name: String, address: String)?
    private var deferredFoundWork: DispatchWorkItem?
    private var recoveryMismatchNotified = false      // 连错笔提示只弹一次/每次连接

    /// 双笔场景:扫描 sheet 打开时暂停"命中已知笔自动连",让用户自己选。
    func setAutoConnectSuppressed(_ s: Bool) {
        q.async { self.autoConnectSuppressed = s }
    }

    // ── 机身下载机(两种任务共用一条下载通道:补取截断段 / 从陪伴笔同步)──
    private enum DownloadJob { case recovery, sync(PenFile) }
    private var downloadJob: DownloadJob?
    private var downloadGen = 0   // 下载代际(审计 L12:防旧任务的延迟闭包污染新任务)
    private var pendingRecovery: (fileName: String, partial: Data, recordedAt: String?, penMac: String?)?
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
        // 系统蓝牙状态感知(审计 L4):关→停重连+精准提示;开→立即踢扫描
        PenBluetoothWatch.shared.onStateChange = { [weak self] state in
            guard let self else { return }
            switch state {
            case .poweredOn:
                self.q.async {
                    guard !self.linkUp, !self.knownMacs.isEmpty else { return }
                    PenLog.d("蓝牙已打开 → 立即重扫")
                    self.reconnectDelay = 8
                    self.pen.startSearch()
                    self.scheduleReconnect()
                }
            case .poweredOff, .unauthorized:
                self.q.async { self.stopReconnect() }
                DispatchQueue.main.async {
                    self.manager?.penBluetoothUnavailable(state == .unauthorized)
                }
            default: break
            }
        }
        PenBluetoothWatch.shared.start()
        // 冷启动救援:重装/被杀重开时笔可能还挂在系统蓝牙上,扫是扫不到的,直接连它
        q.asyncAfter(deadline: .now() + 2.5) { [weak self] in
            guard let self, !self.knownMacs.isEmpty else { return }
            self.tryDirectConnectSystemPen("冷启动")
        }
    }

    /// 对外"真在线"判据(= android isPenAlive)。q.sync 读,避免裸读跨线程状态。
    var isConnected: Bool {
        q.sync { verifiedConnected && linkUp && Date().timeIntervalSince(lastRx) < staleRx }
    }

    // 命令一律转 q:串行 + 不卡主线程(android 踩过"SDK 把 BLE 跑主线程"坑)。
    func startSearch() { q.async { PenLog.d("cmd→ startSearch"); self.pen.startSearch() } }
    func stopSearch() { q.async { self.pen.stopSearch() } }
    func connect(name: String, address: String?) {
        q.async { self.attemptConnect(name: name, address: address, manual: true) }
    }

    /// 在 q 上:发起一次连接尝试(手动/自动共用)。闸门防并发:连接进行中忽略后续触发,
    /// 10s 未 verify 自动释放;手动连接先停重连循环(审计 L6),15s 未成再自动续上。
    private func attemptConnect(name: String, address: String?, manual: Bool) {
        if connectGate {
            PenLog.d("cmd→ connect 忽略(闸门:已有连接尝试进行中) name=\(name)")
            return
        }
        connectGate = true
        stopReconnect()
        PenLog.d("cmd→ connect name=\(name) addr=\(address ?? "nil") \(manual ? "手动" : "自动")")
        // 复查 P#1:已连着 X 时手动切 Y——先断 X,否则 SDK 静默无视且闸门永久卡死
        if manual, linkUp {
            PenLog.d("已连着别的笔 → 先断开再连新选的")
            linkUp = false
            verifiedConnected = false
            stopHeartbeat()
            pen.closeConnect()
            DispatchQueue.main.async { self.manager?.penConnected = false }
        }
        connectingAddress = address
        connectGateWork?.cancel()
        gateGen += 1
        let g = gateGen
        let w = DispatchWorkItem { [weak self] in
            // 代际校验:旧尝试的10s释放闭包不得碰新尝试;释放本身幂等,无条件放(复查 P#1:
            // 原先的 !verifiedConnected 守卫在"已连X再连Y"时把闸门永远锁死)
            guard let self, self.gateGen == g else { return }
            if self.connectGate {
                PenLog.d("连接尝试 10s 未验证 → 释放闸门")
                self.connectGate = false
            }
        }
        connectGateWork = w
        q.asyncAfter(deadline: .now() + 10, execute: w)
        // 手动连接失败的兜底:15s 还没连上就恢复自动重连循环
        q.asyncAfter(deadline: .now() + 15) { [weak self] in
            guard let self, !self.verifiedConnected, !self.linkUp else { return }
            self.scheduleReconnect()
        }
        if !address.isNilOrEmpty { pen.connectDeviceAndAddress(name, address!) }
        else { pen.connectDevice(name) }
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

    /// 停止 5s 未见 cmd3=0(RecordingManager 兜底调):先问笔真停没(cmd9)再决定——
    /// 还在录=stopRecord 丢包 → 重发;真停了/链路无响应 → 强制收尾保已录部分。
    /// 复查 B6:不再"笔没停却被当停"劈成部分件+复活镜像段。
    func confirmStopOrForceFinish() {
        q.async {
            guard self.recording else { return }   // cmd3=0 已正常处理过
            self.stopConfirmPending = true
            PenLog.d("★停止5s未确认 → cmd9 问笔真停没")
            self.pen.getRecordState()
            self.q.asyncAfter(deadline: .now() + 3) { [weak self] in
                guard let self, self.recording, self.stopConfirmPending else { return }
                self.stopConfirmPending = false
                PenLog.d("★cmd9 也无响应 → 按链路失效强制收尾")
                self.recording = false
                if self.penFileName?.isEmpty == false { self.streamIncomplete = true }
                self.finishUpload()
            }
        }
    }

    /// App 回前台(审计 B3/B6/L8):通知笔 + 立即状态对账——挂起期间定时器全部停走,
    /// 笔是否已停/是否在录/是否失联,由这一次 getRecordState(cmd9) 即时校准;顺带踢下载队列。
    func appDidBecomeActive() {
        q.async {
            self.pen.sendAppShowState(1)
            if self.linkUp {
                self.pen.getRecordState()
                self.pen.getCBC()
            } else if !self.knownMacs.isEmpty {
                PenLog.d("回前台未连接 → 立即重扫")
                self.pen.startSearch()
                self.scheduleReconnect()
            }
            self.kickSync()
        }
    }

    /// App 退后台:通知笔(固件可能依此调整心跳/缓存策略)。
    func appDidEnterBackground() {
        q.async { self.pen.sendAppShowState(2) }
    }

    /// SN 被后端拒绝(绑给了别的顾问):断开、停止重连、遗忘这支笔(只忘这一支,别的照常自动连)。
    func forgetAndDisconnect() {
        q.async {
            PenLog.d("SN被拒 → 断开并遗忘该笔 \(self.connectingAddress ?? "?")")
            // 按"当前实连的笔"遗忘(审计 L3:connectingAddress 可能已被后续尝试覆盖,忘错笔会死循环)
            if let a = self.lastVerifiedMac ?? self.connectingAddress, !a.isEmpty { self.forgetMac(a) }
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
        // 复查 B5:墙钟扣掉暂停时长,暂停多的干净段不再被误判"疑截断"白走几分钟补下载
        let pauseSec = Int(penPausedAccum + (penPauseBeganAt.map { Date().timeIntervalSince($0) } ?? 0))
        let wall = max(0, (recordStartAt.map { Int(Date().timeIntervalSince($0)) } ?? 0) - pauseSec)
        let streamSec = OggOpusWriter.seconds(snapshot.count)
        let suspectTruncated = streamIncomplete || (wall >= 30 && streamSec < Int(Double(wall) * 0.6))
                PenLog.d("收尾 wall=\(wall)s stream=\(streamSec)s incomplete=\(streamIncomplete) → \(suspectTruncated ? "疑截断,补下载" : "直传")")
        if suspectTruncated, let fn = penFileName, !fn.isEmpty {
            // 上一段还有没救完的:
            //  - 同一机身文件(断连→重连镜像继续→停止):新补取的全段已覆盖旧部分件,旧的直接丢弃
            //  - 不同文件:先把旧部分件交出去,别丢(不带 pen_file,防后端去重挡住之后的全段)
            if let old = pendingRecovery {
                cancelActiveDownload(requeueSync: true)
                if old.fileName != fn {
                    deliver(old.partial, old.recordedAt, penFile: nil, truncated: true)
                } else {
                    PenLog.d("旧部分件与本段同文件(\(fn)) → 由新补取覆盖,不重复交付")
                }
            }
            pendingRecovery = (fn, snapshot, at, lastVerifiedMac)   // 记下是哪支笔的文件(审计 L9)
            recoveryAttempts = 0
            updatePenWorkKeepAlive()
            DispatchQueue.main.async { self.manager?.penRecoveryStarted() }
            scheduleRecoveryDeadline()
            if linkUp { startRecoveryDownload() }   // 断连时:自动重连成功(markPenResponded)后再触发
            return
        }
        // 复查 P#11:干净收尾但同一机身文件还有旧补取挂着 → 本次直传不带 pen_file,
        // 否则后端去重会把之后补取上来的更全那份挡掉
        let collide = (pendingRecovery != nil && pendingRecovery?.fileName == penFileName)
        deliver(snapshot, at, penFile: collide ? nil : penFileName)
    }

    /// 在 q 上调用:把一段裸 opus 包成 .ogg 落盘并回调上传。
    private func deliver(_ raw: Data, _ at: String?, penFile: String?, truncated: Bool = false) {
        let snValue = sn
        guard raw.count >= 40, let ogg = OggOpusWriter.wrap(raw) else {
            DispatchQueue.main.async { self.manager?.penDidFinish(fileURL: nil, durationSec: 0, recordedAt: at, penFile: penFile, sn: snValue, truncated: truncated) }
            return
        }
        let dur = OggOpusWriter.seconds(raw.count)
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("pen_\(Int(Date().timeIntervalSince1970))_\(raw.count).ogg")
        do {
            try ogg.write(to: url)
            DispatchQueue.main.async { self.manager?.penDidFinish(fileURL: url, durationSec: dur, recordedAt: at, penFile: penFile, sn: snValue, truncated: truncated) }
        } catch {
            DispatchQueue.main.async { self.manager?.penDidFinish(fileURL: nil, durationSec: 0, recordedAt: at, penFile: penFile, sn: snValue, truncated: truncated) }
        }
    }

    // MARK: - 机身补下载(均在 q 上)

    /// 开始从笔机身下载全段。前提:linkUp、笔不在录音(录音中笔拒绝文件传输)。
    private func startRecoveryDownload() {
        guard let rec = pendingRecovery, !downloading, !recording, linkUp else { return }
        beginDownload(.recovery, fileName: rec.fileName)
    }

    /// 在 q 上:起一个下载任务(补取/同步共用)。停录后笔要缓 2s 才接受文件命令。
    /// downloadAccepting(复查 P#8):cancel→立即新下载的 2s 窗口内,旧传输的残余字节/终态
    /// 不得记到新任务头上——发出 startGetFile 之前一律不收数据/不认 cmd5。
    private var downloadAccepting = false

    private func beginDownload(_ job: DownloadJob, fileName: String) {
        downloading = true
        downloadJob = job
        downloadGen += 1
        downloadAccepting = false
        let gen = downloadGen
        download.removeAll(keepingCapacity: true)
        PenLog.d("★下载开始 file=\(fileName)")
        updatePenWorkKeepAlive()
        q.asyncAfter(deadline: .now() + 2) { [weak self] in
            // 代际校验(审计 L12):旧任务被取消、新任务已开时,旧闭包不得发旧文件名
            guard let self, self.downloading, self.downloadGen == gen else { return }
            self.downloadAccepting = true
            self.pen.startGetFile(fileName, "0")
            self.bumpDownloadStall()
        }
    }

    /// 补取/同步/等重连期间持有保活(复查 B2/B5:这些工作可长达几分钟-10分钟,
    /// 期间 App 必须活着才能自动重连+收数据;工作清空即释放,不再泄漏)。
    private func updatePenWorkKeepAlive() {
        // B11:只为"干得动"的工作保活——只剩别的笔的任务(原笔不在线)时不值得后台常驻放静音耗电,
        // 原笔回来由用户打开 App 触发 kickSync 续传
        let executableSync = syncQueue.contains { $0.penMac == nil || $0.penMac == lastVerifiedMac }
        let need = downloading || pendingRecovery != nil || executableSync
        DispatchQueue.main.async {
            need ? RecordKeepAlive.acquire("penwork") : RecordKeepAlive.release("penwork")
        }
    }

    /// 当前下载失败 → 按任务类型收尾。补取(复查 P#7):一次卡死/错误不永久弃,
    /// 重试最多 3 次(每次隔 5s),最终放弃交给 10min 总兜底或次数上限。
    private var recoveryAttempts = 0
    private var syncAttempts: [String: Int] = [:]     // 复查 D3:同步下载重试计数(按机身文件名)
    private var stopConfirmPending = false            // 复查 B6:停止5s未确认,先问笔真停没
    private var penPausedAccum: TimeInterval = 0      // 复查 B5:本段累计暂停时长(墙钟扣除用)
    private var penPauseBeganAt: Date?
    private var lastDownloadDataAt = Date(timeIntervalSince1970: 0)   // 复查 D4:下载最近出数据时刻

    // 复查 B2:SN↔MAC 映射——直连时 cmd2 不带地址,靠 cmd7 上报的 SN 反查是哪支笔
    private static let kSnMacMap = "pen_sn_mac_map"
    private lazy var snMacMap: [String: String] =
        (UserDefaults.standard.dictionary(forKey: Self.kSnMacMap) as? [String: String]) ?? [:]
    private func rememberSnMac(_ sn: String, _ mac: String) {
        guard snMacMap[sn] != mac else { return }
        snMacMap[sn] = mac
        UserDefaults.standard.set(snMacMap, forKey: Self.kSnMacMap)
    }

    private func failCurrentDownload() {
        switch downloadJob {
        case .recovery:
            recoveryAttempts += 1
            if recoveryAttempts < 3, pendingRecovery != nil {
                PenLog.d("★补取第\(recoveryAttempts)次失败 → 5s后重试")
                downloading = false
                downloadJob = nil
                downloadStallWork?.cancel()
                download.removeAll(keepingCapacity: false)
                updatePenWorkKeepAlive()
                q.asyncAfter(deadline: .now() + 5) { [weak self] in self?.kickSync() }
            } else {
                failRecovery()
            }
        case .sync(let f):
            // 复查 D3:同步下载也重试(≤3次)——15s 卡流一次就丢任务太脆,大文件下到90%全作废
            let n = (syncAttempts[f.name] ?? 0) + 1
            syncAttempts[f.name] = n
            if n < 3 {
                PenLog.d("★同步下载第\(n)次失败 → 5s后重试 \(f.name)")
                downloading = false
                downloadJob = nil
                downloadStallWork?.cancel()
                download.removeAll(keepingCapacity: false)
                if !syncQueue.contains(where: { $0.name == f.name }) { syncQueue.insert(f, at: 0) }
                persistSyncQueue()
                updatePenWorkKeepAlive()
                q.asyncAfter(deadline: .now() + 5) { [weak self] in self?.kickSync() }
            } else {
                syncAttempts.removeValue(forKey: f.name)
                failSync(f)
            }
        case nil: break
        }
    }

    /// 中止进行中的下载(录音开始让路/断线/新收尾接管)。同步任务塞回队首,补取任务保留在 pendingRecovery。
    private func cancelActiveDownload(requeueSync: Bool, sendStop: Bool = true) {
        guard downloading else { return }
        if sendStop { pen.stopGetFile(currentDownloadFileName ?? "") }
        if requeueSync, case .sync(let f) = downloadJob, !syncQueue.contains(where: { $0.name == f.name }) {
            syncQueue.insert(f, at: 0)
        }
        downloading = false
        downloadJob = nil
        downloadStallWork?.cancel()
        download.removeAll(keepingCapacity: false)
        persistSyncQueue()
        updatePenWorkKeepAlive()
    }

    // ── 「从陪伴笔同步」──

    /// 拉取机身文件列表(cmd=4,10s 超时返回已收到的)。回调在主线程。
    func fetchFileList(_ completion: @escaping ([PenFile]) -> Void) {
        q.async {
            guard self.linkUp else { DispatchQueue.main.async { completion([]) }; return }
            // 重入(审计 L14):先把上一个等待中的回调以空结果放行,别让旧 UI 卡加载态
            if let old = self.fileListCompletion {
                self.fileListCompletion = nil
                DispatchQueue.main.async { old([]) }
            }
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
                                               recordedAt: Self.parseRecordedAt(from: name),
                                               penMac: lastVerifiedMac))   // 记住属于哪支笔(P#5)
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
              var files = try? JSONDecoder().decode([PenFile].self, from: data),
              !files.isEmpty else { return }
        // D2:老数据无 addedAt → 现在补章;超3天没传出去的任务判过期丢弃(多半那支笔不用了)
        files = files.map { f -> PenFile in
            var x = f; if x.addedAt == nil { x.addedAt = Date() }; return x
        }
        let cutoff = Date().addingTimeInterval(-3 * 86400)
        let dropped = files.filter { ($0.addedAt ?? Date()) < cutoff }
        if !dropped.isEmpty {
            files.removeAll { ($0.addedAt ?? Date()) < cutoff }
            PenLog.d("★丢弃过期同步任务 \(dropped.count)个(>3天未能执行)")
        }
        syncQueue = files
        persistSyncQueue()
        updatePenWorkKeepAlive()
        PenLog.d("★恢复上次未完成的同步队列 \(files.count)个,连上笔后自动续传")
    }

    /// 是否有同步/补取下载在跑或在排队(笔忙着传文件时没法回答列表查询,弹层要显示"同步中"而非"暂无")。
    /// D2:只算"当前这支笔干得动"的任务——别的笔的任务挂起不该把这支笔的弹层堵成永远"同步中"。
    var isSyncBusy: Bool {
        q.sync {
            downloading || pendingRecovery != nil
                || syncQueue.contains { $0.penMac == nil || $0.penMac == lastVerifiedMac }
        }
    }

    /// 笔正在录的机身文件名(D7:同步弹层要把"正在录的这段"从可导入列表里滤掉)。
    var currentRecordingPenFile: String? {
        q.sync { recording ? penFileName : nil }
    }

    /// 把选中的机身文件排队下载→包.ogg→上传(带 pen_file 去重)。补取任务优先。
    func startSync(files: [PenFile]) {
        q.async {
            for f in files where !self.syncQueue.contains(where: { $0.name == f.name })
                    && self.currentSyncFile?.name != f.name {
                var nf = f
                nf.addedAt = Date()   // D2:入队时刻,>3天没传出去判过期
                self.syncQueue.append(nf)
            }
            PenLog.d("★同步排队 \(files.count)个,队列共\(self.syncQueue.count)个")
            self.persistSyncQueue()
            self.updatePenWorkKeepAlive()
            self.kickSync()
        }
    }

    /// 在 q 上:笔空闲且无下载在跑时,起下一个下载(补取截断段优先于同步)。
    /// 审计 L9:补取只在"连的是原来那支笔"时启动——双笔场景向错的笔要文件必失败,
    /// 错笔时挂起等待(总兜底 10min 到点仍会退回直传部分件),同步队列照常走。
    private func kickSync() {
        guard !downloading, !recording, linkUp else { return }
        if let rec = pendingRecovery {
            if rec.penMac == nil || rec.penMac == lastVerifiedMac {
                startRecoveryDownload()
                return
            }
            PenLog.d("★补取挂起:当前连的笔(\(lastVerifiedMac ?? "?"))不是该文件所在笔(\(rec.penMac ?? "?"))")
            if !recoveryMismatchNotified, lastVerifiedMac != nil {   // 身份未知(直连待SN反查)不弹空尾号
                recoveryMismatchNotified = true
                let pref = String((rec.penMac ?? "").replacingOccurrences(of: ":", with: "").suffix(2))
                let cur = String((lastVerifiedMac ?? "").replacingOccurrences(of: ":", with: "").suffix(2))
                DispatchQueue.main.async {
                    self.manager?.toast = "当前连的是尾号\(cur)；上段录音在尾号\(pref)那支里，它开机靠近后会自动补取"
                }
            }
        }
        // 复查 P#5:同步任务也认笔——只出队"属于当前这支笔"的(penMac nil=旧数据,放行),
        // 连错笔时挂起等原笔,不再被 cmd5 state=1 打成永久失败丢任务
        guard let idx = syncQueue.firstIndex(where: { $0.penMac == nil || $0.penMac == lastVerifiedMac }) else {
            if !syncQueue.isEmpty {
                PenLog.d("★同步挂起:队列 \(syncQueue.count) 个都属于别的笔,等原笔上线")
            }
            return
        }
        let f = syncQueue.remove(at: idx)
        beginDownload(.sync(f), fileName: f.name)
        persistSyncQueue()   // 在下的那个也在盘上(currentSyncFile),中途被杀不丢
    }

    private func completeSync(_ f: PenFile) {
        syncAttempts.removeValue(forKey: f.name)
        downloading = false
        downloadJob = nil
        downloadStallWork?.cancel()
        persistSyncQueue()   // 这个下完了,从盘上去掉
        updatePenWorkKeepAlive()
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
        updatePenWorkKeepAlive()
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
        recoveryAttempts = 0
        updatePenWorkKeepAlive()
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
        recoveryAttempts = 0
        updatePenWorkKeepAlive()
        q.asyncAfter(deadline: .now() + 1) { [weak self] in self?.kickSync() }
        download.removeAll(keepingCapacity: false)
        deliver(rec.partial, rec.recordedAt, penFile: nil, truncated: true)   // D1:标记诚实部分件
    }

    /// 总兜底:10 分钟内没救回来(反复断连等) → 放弃,直传部分件,不让「保存中」无限挂。
    private func scheduleRecoveryDeadline(_ interval: TimeInterval? = nil) {
        recoveryDeadlineWork?.cancel()
        let w = DispatchWorkItem { [weak self] in
            guard let self, self.pendingRecovery != nil else { return }
            // 复查 D4:到点但下载正在出数据 → 顺延续命,别把 6 分钟级大文件拦腰击杀
            // (死传输有 15s 卡流看门狗兜,这里只管"反复断连永远救不回"的场景)
            if self.downloading, Date().timeIntervalSince(self.lastDownloadDataAt) < 30 {
                PenLog.d("★补取兜底到点但下载有进度 → 顺延120s")
                self.scheduleRecoveryDeadline(120)
                return
            }
            PenLog.d("★补下载总兜底超时 → 直传部分件")
            if self.downloading { self.pen.stopGetFile(self.pendingRecovery?.fileName ?? "") }
            self.failRecovery()
        }
        recoveryDeadlineWork = w
        q.asyncAfter(deadline: .now() + (interval ?? recoveryDeadline), execute: w)
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

    // MARK: - 僵尸连接直连(均在 q 上)

    /// App 被杀/重装后笔常还挂在系统蓝牙上——挂着就不广播,扫描永远搜不到("明明连着却显示未连接")。
    /// SDK 内部类 BluetoothDataManager 有厂家遗留的死代码入口 connectByList:
    /// retrieveConnectedPeripherals(服务AE20) → 对每个已连接外设直接 connectBluetooth:,完全绕开扫描。
    /// 纯运行时反射调用,厂家换版本删了此方法就静默退化为无操作。
    private func tryDirectConnectSystemPen(_ why: String) {
        guard !linkUp, !connectGate else { return }
        guard PenBluetoothWatch.shared.isPoweredOn else { return }
        guard let cls = NSClassFromString("BluetoothDataManager") as? NSObject.Type else { return }
        let shareSel = Selector(("shareBluetoothDataManager"))
        let listSel = Selector(("connectByList"))
        guard cls.responds(to: shareSel),
              let mgr = cls.perform(shareSel)?.takeUnretainedValue() as? NSObject,
              mgr.responds(to: listSel) else {
            PenLog.d("直连救援不可用(SDK 内部接口变了) 由=\(why)")
            return
        }
        // 复查 B2/B3:直连也走闸门(防与扫描自动连并发张冠李戴);身份不预填——
        // cmd2 不带地址,真实身份等 cmd7 SN 反查(snMacMap)定格,期间按"未知笔"处理
        connectGate = true
        stopReconnect()
        connectingAddress = nil
        connectGateWork?.cancel()
        gateGen += 1
        let g = gateGen
        let w = DispatchWorkItem { [weak self] in
            guard let self, self.gateGen == g else { return }
            if self.connectGate {
                PenLog.d("直连10s未verify → 闸门释放,恢复重连")
                self.connectGate = false
                self.scheduleReconnect()
            }
        }
        connectGateWork = w
        q.asyncAfter(deadline: .now() + 10, execute: w)
        PenLog.d("★直连系统已连接的笔(connectByList) 由=\(why)")
        _ = mgr.perform(listSel)
    }

    /// 扫描超时救援入口(RecordingManager 检测到笔挂在系统蓝牙上时调,绕过选笔暂停)。
    func directConnectSystemPen() { q.async { self.tryDirectConnectSystemPen("扫描超时救援") } }

    // MARK: - 断开自动重连(均在 q 上)

    /// 断开/失联后重新扫描;扫到已知笔由 handleDeviceFound 自动连;连上即停。
    /// 审计 P4:指数退避 8→16→32→60s 封顶(BLE 扫描是耗电大项,笔不在身边时别整天全速扫);
    /// 审计 L10:门槛用 knownMacs(多支笔任一支都触发),不再只认"最后一支"。
    private func scheduleReconnect() {
        guard !knownMacs.isEmpty else { return }
        guard PenBluetoothWatch.shared.isPoweredOn else { return }   // 蓝牙关着,扫也白扫(L4)
        // 复查 P#4:已排定就不重排不加档——原先握手超时/cmd2断开/15s兜底连环调用,
        // 一次失败连扣三档直接 60s 慢启动("断了半天不重连")
        guard reconnectWork == nil else { return }
        let delay = reconnectDelay
        let w = DispatchWorkItem { [weak self] in
            guard let self else { return }
            self.reconnectWork = nil
            guard !self.linkUp else { return }
            PenLog.d("★自动重连(间隔\(Int(delay))s):重新扫描找已知的笔…")
            self.reconnectDelay = min(60, self.reconnectDelay * 2)   // 真扫了一轮才加档
            if !self.autoConnectSuppressed { self.tryDirectConnectSystemPen("重连tick") }
            self.pen.startSearch()
            self.scheduleReconnect()
        }
        reconnectWork = w
        q.asyncAfter(deadline: .now() + delay, execute: w)
    }
    private func stopReconnect() { reconnectWork?.cancel(); reconnectWork = nil }

    // MARK: - 真连接判定 / 心跳(均在 q 上)

    private func markPenResponded() {
        lastRx = Date()
        guard !verifiedConnected else { return }
        verifiedConnected = true
        handshakeWork?.cancel()
        stopReconnect()
        reconnectDelay = 8                        // 退避复位
        connectGate = false                       // 闸门释放
        connectGateWork?.cancel()
        lastVerifiedMac = connectingAddress       // 定格"当前实连的笔"(SN-deny 按它遗忘)
        if let a = connectingAddress, !a.isEmpty {
            rememberMac(a)   // 记住这支笔(多支都记) → 任一支开机自动连
        }
                deferredFoundWork?.cancel()
        deferredFoundWork = nil
        deferredFound = nil
        recoveryMismatchNotified = false
        PenLog.d("★verified=true 收到真回包(cmd≥3)→ 标记已连接")
        // 双笔身份:把 MAC 尾号报给 UI(两支笔都叫 CB08,不显示尾号用户不知道连的是哪支)
        let suffix = String((connectingAddress ?? "").replacingOccurrences(of: ":", with: "").suffix(2))
        DispatchQueue.main.async {
            self.manager?.penConnected = true
            self.manager?.penSuffix = suffix.isEmpty ? nil : suffix
        }
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
            PenLog.d("★握手\(Int(self.handshakeTimeout))s超时(无真回包)→ 判假连接、断开、报未连、续重连")
            self.pen.closeConnect()
            // 审计 L2:必须重置链路状态并续上重连——否则 SDK 不回 cmd2=false 时,
            // linkUp 永久 true、重连链静默死透,"断了不自己回来"
            self.linkUp = false
            self.verifiedConnected = false
            self.connectGate = false
            DispatchQueue.main.async { self.manager?.penConnected = false }
            self.scheduleReconnect()
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
            // 录音/下载中且近期有数据 → 笔正忙着传,别发查询打扰它(审计 L11),只续期。
            if (self.recording || self.downloading) && idle < self.staleRx {
                self.hbMissed = 0
                self.scheduleHeartbeat()
                return
            }
            if idle > self.hbInterval + 4 {
                self.hbMissed += 1
                // 审计 L5:录音中放宽到 3 次(~30s)——BLE 射频波动 20-30s 常可自愈,
                // 主动断开会把"卡一下"升级成"断段+补取几分钟",是"容易断"的体感放大器
                let missLimit = self.recording ? 3 : 2
                if self.hbMissed >= missLimit {
                    PenLog.d("★心跳连续\(self.hbMissed)次无回包→判失联、断开、报未连、进自动重连")
                    let wasRecording = self.recording
                    self.verifiedConnected = false
                    self.linkUp = false
                    self.pen.closeConnect()
                    DispatchQueue.main.async {
                        self.manager?.penConnected = false
                        if wasRecording { self.manager?.penDisconnectedWhileRecording() }
                    }
                    self.stopHeartbeat()
                    self.reconnectDelay = 8   // 新断开=新形势,退避从头算
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
    /// downloadAccepting 守卫(复查 P#8):新任务发出 startGetFile 前,旧传输残余字节不得混入。
    func deviceFileData(_ fileData: Data!) {
        guard let d = fileData else { return }
        q.async {
            self.lastRx = Date()
            guard self.downloading, self.downloadAccepting else { return }
            self.download.append(d)
            self.lastDownloadDataAt = Date()
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
        if !linkUp, !autoConnectSuppressed, knownMacs.contains(address) {
            // 有段落等着从"原笔"补取时优先它:先缓 6s 等原笔广播,别抢连另一支(双笔场景连错拿不到文件)
            if let pref = pendingRecovery?.penMac, pref != address {
                PenLog.d("cmd1 发现 \(address) 但补取属于 \(pref) → 暂缓6s等原笔")
                deferredFound = (name, address)
                if deferredFoundWork == nil {
                    let w = DispatchWorkItem { [weak self] in
                        guard let self else { return }
                        self.deferredFoundWork = nil
                        guard !self.linkUp, !self.autoConnectSuppressed,
                              let cand = self.deferredFound else { return }
                        PenLog.d("等原笔6s未出现 → 先连发现的 \(cand.address)")
                        self.deferredFound = nil
                        self.attemptConnect(name: cand.name, address: cand.address, manual: false)
                    }
                    deferredFoundWork = w
                    q.asyncAfter(deadline: .now() + 6, execute: w)
                }
                return
            }
            deferredFoundWork?.cancel()
            deferredFoundWork = nil
            deferredFound = nil
            PenLog.d("cmd1 命中已知的笔(\(address)) → 自动连接")
            attemptConnect(name: name, address: address, manual: false)   // 走闸门,防并发连接(L3)
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
            // 审计 L7:握手有效窗口仅~5s且首轮命令可能整批丢,+5s 未验证补发一轮
            q.asyncAfter(deadline: .now() + 5) { [weak self] in
                guard let self, self.linkUp, !self.verifiedConnected else { return }
                PenLog.d("握手5s未验证 → 补发一轮查询")
                self.pen.getRecordState()
                self.pen.getSn()
            }
            scheduleHandshakeTimeout()
        } else {
            PenLog.d("cmd2 断开 → 报未连、进自动重连")
            connectGate = false
            connectGateWork?.cancel()
            reconnectDelay = 8   // 新一次断开=新形势,退避从头算(复查 P#4)
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
                if let p = penPauseBeganAt { penPausedAccum += Date().timeIntervalSince(p); penPauseBeganAt = nil }
                PenLog.d("cmd3 恢复录音")
                lastFrameAt = Date()
                DispatchQueue.main.async { self.manager?.penRecordingResumed() }
                return
            }
            if !recording {
                recording = true
                recPaused = false
                penPausedAccum = 0
                penPauseBeganAt = nil
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
                // 审计 L15:文件名是断流补取的唯一凭据,尽早拿 + 没拿到再补一次
                q.asyncAfter(deadline: .now() + 0.6) { [weak self] in
                    guard let self, self.recording else { return }
                    self.pen.getFileNameOnlyRecording()
                }
                q.asyncAfter(deadline: .now() + 2.5) { [weak self] in
                    guard let self, self.recording, self.penFileName == nil else { return }
                    self.pen.getFileNameOnlyRecording()
                }
                                PenLog.d("cmd3 录音开始 → App 进入录音态")
                DispatchQueue.main.async { self.manager?.penRecordingStarted() }
            } else {
                // 复查 B4:已在录(重复 cmd3=1)→ 幂等补通知,防 manager 之前拒绝过镜像
                DispatchQueue.main.async { self.manager?.penMirrorRecordingIfIdle() }
            }
        case "2":
            if recording, !recPaused {
                recPaused = true
                penPauseBeganAt = Date()
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
        guard let data, downloading, downloadAccepting else { return }   // P#8:旧传输终态不认
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
        // 复查 P#2:笔离机录音→暂停→回手机旁恢复:App 当"全新一段",但机身是续写同一文件——
        // 文件真实开始远早于本段 recordStartAt 就说明头部不在流里,必须走补取全段,
        // 否则只传尾段还带 pen_file,之后全量件被后端去重挡掉=头部永久丢失
        if let at0 = Self.parseRecordedAt(from: name),
           let realStart = Self.wallClockFormatter.date(from: at0),
           let segStart = recordStartAt,
           segStart.timeIntervalSince(realStart) > 5, !streamIncomplete {
            streamIncomplete = true
            PenLog.d("★检测到续录段(文件开始早于本段 \(Int(segStart.timeIntervalSince(realStart)))s) → 标记流不完整,收尾走补取全段")
        }
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
        if stopConfirmPending {
            stopConfirmPending = false
            if rs == "1" {
                PenLog.d("★停止确认:笔还在录(stop 丢包) → 重发停止")
                pen.stopRecord()
                q.asyncAfter(deadline: .now() + 5) { [weak self] in
                    guard let self, self.recording else { return }
                    PenLog.d("★重发停止仍未回 → 强制收尾")
                    self.recording = false
                    if self.penFileName?.isEmpty == false { self.streamIncomplete = true }
                    self.finishUpload()
                }
            } else {
                PenLog.d("★停止确认:笔已停(cmd3=0 丢了) → 收尾")
                recording = false
                recPaused = false
                finishUpload()
            }
            return
        }
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
                if let p = penPauseBeganAt { penPausedAccum += Date().timeIntervalSince(p); penPauseBeganAt = nil }
                PenLog.d("cmd9 笔已恢复录音 → App 回录音态")
                DispatchQueue.main.async { self.manager?.penRecordingResumed() }
            } else {
                // 复查 B4:manager 可能拒绝过镜像(当时手机麦在录)——空闲后幂等补通知,失同步不再永久化
                DispatchQueue.main.async { self.manager?.penMirrorRecordingIfIdle() }
            }
            return
        }
        PenLog.d("cmd9 笔在录而App不在 → 镜像进入录音态(头部缺失,收尾走补下载)")
        recording = true
        recPaused = false
        penPausedAccum = 0
        penPauseBeganAt = nil
        finishing = false
        opus.removeAll(keepingCapacity: true)
        recordedAt = PhoneMicRecorder.wallClock()   // 占位;cmd11 文件名到手后校准为真实开始时刻
        recordStartAt = Date()
        penFileName = nil
        streamIncomplete = true                     // 断线期间的头部拿不到 → 流必不完整
        lastFrameAt = Date()
        startStallWatch()
        cancelActiveDownload(requeueSync: true)
        // 审计 L15:文件名是断流补取的唯一凭据,尽早拿 + 没拿到再补一次
        q.asyncAfter(deadline: .now() + 0.6) { [weak self] in
            guard let self, self.recording else { return }
            self.pen.getFileNameOnlyRecording()
        }
        q.asyncAfter(deadline: .now() + 2.5) { [weak self] in
            guard let self, self.recording, self.penFileName == nil else { return }
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
        // 复查 B2:SN↔MAC 学习与反查——正常连接(有地址)学映射;直连(身份未知)用 SN 定格身份
        if let mac = lastVerifiedMac, !mac.isEmpty {
            rememberSnMac(v, mac)
        } else if verifiedConnected, let mac = snMacMap[v], !mac.isEmpty {
            PenLog.d("★直连身份反查:SN \(v) → \(mac)")
            lastVerifiedMac = mac
            connectingAddress = mac
            rememberMac(mac)
            let suffix = String(mac.replacingOccurrences(of: ":", with: "").suffix(2))
            DispatchQueue.main.async { self.manager?.penSuffix = suffix.isEmpty ? nil : suffix }
            kickSync()   // 身份定格 → 被挂起的补取/同步立刻按新身份重判
        }
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
