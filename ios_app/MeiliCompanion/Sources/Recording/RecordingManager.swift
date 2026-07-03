import SwiftUI

/// 陪伴来源。
enum CompanionSource: String { case phone, pen }

/// 扫描发现的陪伴笔(选笔列表用)。android 端对应 SoniScanActivity 的设备项。
struct PenDevice: Identifiable, Equatable {
    var id: String { address }
    let name: String
    let address: String
}

/// 陪伴笔机身文件(「从陪伴笔同步」用)。android 端对应 PenFile。
/// Codable:同步队列要落盘持久化,App 被杀重启后自动续传。
struct PenFile: Identifiable, Equatable, Codable {
    let name: String          // 机身文件名(pen_file 去重键)
    let sizeBytes: Int
    let durationSec: Int
    let recordedAt: String?   // 从文件名解析的录音开始时刻
    var id: String { name }
}

/// 录音状态机。android 端对应 `recording/RecordingController.kt` 的 RecordingState。
enum RecordingState: Equatable { case idle, starting, recording, paused, uploading }

/// 录音引擎统一入口(手机麦 + 声云陪伴笔)。android 端对应 `recording/RecordingControllerImpl.kt`。
/// 任何 tab 的 FAB / 陪伴首页圆钮都驱动这一个实例,状态全局同步。
@MainActor
final class RecordingManager: ObservableObject {
    static let shared = RecordingManager()

    @Published var state: RecordingState = .idle {
        didSet {
            let live = state == .recording || state == .paused
            // 录音中防自动锁屏(审计 B7:自动锁屏是后台风险最高频触发源)
            UIApplication.shared.isIdleTimerDisabled = live
            // 笔录音期间静音保活(审计 B1);手机麦有自己的会话不需要
            if source == .pen && live { RecordKeepAlive.start() }
            else if !live && !PenController.shared.isSyncBusy { RecordKeepAlive.stop() }
        }
    }
    @Published var source: CompanionSource = .phone
    @Published var elapsed: Int = 0
    @Published var pendingUploads = 0
    @Published var toast: String?

    /// 录完上传成功后拿到的可绑定片段 id(对齐 android `bindPrompt` SharedFlow)。
    /// MainShell 监听此值 → 直接跳绑定页(强制绑定,无「稍后」对话框,android v2.0.60)。
    @Published var bindPrompt: Int?

    // 陪伴笔状态(由 PenController 回填;模拟器恒未连接)
    @Published var penConnected = false
    @Published var penBattery: Int?

    // 陪伴笔扫描/选笔(扫描 sheet 驱动)
    @Published var penScanning = false
    @Published var penDevices: [PenDevice] = []
    @Published var penConnecting = false

    private let phone = PhoneMicRecorder()
    private var timer: Timer?
    private var recordedAt: String?

    private init() {
        PenController.shared.manager = self
        PenController.shared.setup()
        phone.onInterrupted = { [weak self] in self?.phoneInterrupted() }
        // 上传队列回调:成功弹绑定/提示;首次失败告知已入重传队列(不再丢)
        UploadQueue.shared.onUploaded = { [weak self] rid, prompt in
            guard let self, prompt else { return }
            if let rid, rid > 0 { self.bindPrompt = rid }
            else { self.toast = "陪伴已保存，去「待整理」绑定顾客" }
        }
        UploadQueue.shared.onFirstFailure = { [weak self] in
            self?.toast = "上传暂时失败，已加入重传队列，网络恢复后自动补传"
        }
    }

    /// 手机麦录音被来电/Siri/闹钟中断且无法恢复 → 收尾保存已录部分,界面不再假装在录。
    func phoneInterrupted() {
        guard source == .phone, state == .recording else { return }
        toast = "录音被来电或系统打断，已保存已录到的部分"
        stop()
    }

    var isLive: Bool { state == .recording || state == .paused }
    var elapsedLabel: String { String(format: "%02d:%02d", elapsed / 60, elapsed % 60) }

    func setSource(_ s: CompanionSource) {
        guard !isLive, state != .uploading else { return }
        source = s
        if s == .pen && !penConnected { PenController.shared.startSearch() }   // 后台找笔 + autoConnect 上次那支
    }

    // MARK: 陪伴笔扫描/连接(扫描选笔 sheet 驱动,对齐 android SoniScanActivity)

    func startPenScan() {
        penDevices = []
        penConnecting = false
        penScanning = true
        PenController.shared.startSearch()
        // 僵尸连接提示:App 被杀后笔可能仍挂在系统蓝牙上(连着就不广播,永远扫不到)
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 15_000_000_000)
            if penScanning && penDevices.isEmpty && !penConnected {
                toast = "一直搜不到？陪伴笔可能还挂在手机系统蓝牙上——把笔关机再开机后重试"
            }
        }
    }
    func stopPenScan() {
        penScanning = false
        PenController.shared.stopSearch()
    }
    func connectPen(_ d: PenDevice) {
        penConnecting = true
        penScanning = false
        PenController.shared.stopSearch()
        PenController.shared.connect(name: d.name, address: d.address)
        // 9.5s 连接超时(> 握手 7s):还没真连上 → 回退重扫,让用户重试。
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 9_500_000_000)
            if penConnecting && !penConnected { penConnecting = false; startPenScan() }
        }
    }
    /// PenController 回调:扫描发现一支笔(去重)。
    func penFound(name: String, address: String) {
        guard !address.isEmpty, !penDevices.contains(where: { $0.address == address }) else { return }
        penDevices.append(PenDevice(name: name.isEmpty ? "陪伴笔" : name, address: address))
    }

    /// PenController 回调:录音中陪伴笔断开/关机 → 立即结束这段、保存已录部分。
    /// 不再假装在录(对齐 android resetSessionOnLinkDown 的"本段结束"路径);
    /// 重连后是干净的空闲态,用户重新开始 = 新的一段从 0 计时。
    func penDisconnectedWhileRecording() {
        guard source == .pen, state == .recording || state == .starting else { return }
        stopTimer()
        elapsed = 0
        state = .uploading
        toast = "陪伴笔已断开；重连后将自动补取这段完整内容"
        Task { await ensurePlaceholder() }   // 断连收尾也要有「处理中」行可盯
        PenController.shared.endRecordingOnDisconnect()
    }

    func toggle() {
        switch state {
        case .idle: start()
        case .recording, .paused: stop()
        default: break   // starting / uploading：忽略
        }
    }

    func start() {
        guard state == .idle else { return }
        segmentPlaceholderMade = false   // 新一段:允许再建占位
        switch source {
        case .phone:
            state = .starting
            phone.start { [weak self] ok, wall in
                guard let self else { return }
                if ok {
                    self.recordedAt = wall
                    self.beginTimer()
                    self.state = .recording
                } else {
                    self.state = .idle
                    self.toast = "麦克风启动失败，请在设置里允许麦克风权限"
                }
            }
        case .pen:
            guard penConnected else { toast = "请先连接陪伴笔，靠近手机后重试"; return }
            state = .starting
            PenController.shared.startRecord()   // 真正进入录音态由 cmd=3=1 → penRecordingStarted 驱动
        }
    }

    /// 笔上报录音开始(cmd=3 record_state=1):App 点击 or 笔上按键殊途同归 → App 进入录音态。
    func penRecordingStarted() {
        guard state == .idle || state == .starting else { return }
        source = .pen
        recordedAt = PhoneMicRecorder.wallClock()
        segmentPlaceholderMade = false   // 新一段:允许再建占位
        beginTimer()
        state = .recording
    }
    /// 笔暂停(cmd=3 state=2):计时挂起,UI 显示"陪伴已暂停"。
    func penRecordingPaused() {
        guard source == .pen, state == .recording else { return }
        pauseBeganAt = Date()
        state = .paused
    }
    /// 笔恢复录音。
    func penRecordingResumed() {
        guard source == .pen, state == .paused else { return }
        if let p = pauseBeganAt { pausedAccum += Date().timeIntervalSince(p) }
        pauseBeganAt = nil
        state = .recording
    }
    /// 镜像段计时校准:把开始时刻校准为机身文件名里的真实开始时间(墙钟派生自动正确)。
    func penElapsedCalibrated(_ seconds: Int) {
        guard source == .pen, state == .recording || state == .paused else { return }
        if seconds > elapsed {
            segStartAt = Date().addingTimeInterval(-Double(seconds))
            pausedAccum = 0
            elapsed = seconds
        }
    }

    /// 笔上报录音停止(cmd=3 record_state=0):笔按键停时 App 同步结束(App 点击停已自行处理)。
    func penRecordingStopped() {
        guard source == .pen, isLive else { return }
        hapticRecordStop()
        stopTimer()
        elapsed = 0
        state = .uploading
        Task { await ensurePlaceholder() }   // 立即占位:未归档几秒内冒「处理中」,补下载再久也有行可盯
    }

    /// PenController 回调:本段流不完整/疑截断,转入后台从笔机身补取全段(可能要几分钟,
    /// 断连时会等自动重连)。立刻释放全局状态,不挡新录音;进度看待整理的「处理中」占位行。
    func penRecoveryStarted() {
        state = .idle
        toast = "正在从陪伴笔补取这段完整内容，稍后自动上传；期间可正常开始新的陪伴"
    }

    /// PenController 回调:连接后拿到笔 SN → 上报后端做绑定校验(fail-open,网络失败不拦)。
    /// 后端回 deny(这支笔绑给了别的顾问) → 提示 + 断开 + 遗忘该笔。
    func penSnReported(_ sn: String) {
        Task {
            guard let r = try? await ConsultantRepo.reportPenSn(sn) else { return }
            if r.decision == "deny" {
                toast = (r.message?.nilIfBlank) ?? "这支陪伴笔已绑定给其他顾问，无法使用"
                PenController.shared.forgetAndDisconnect()
            }
        }
    }

    func stop() {
        guard isLive else { return }
        hapticRecordStop()
        stopTimer()
        let dur = elapsed
        let at = recordedAt
        elapsed = 0
        switch source {
        case .phone:
            state = .uploading
            Task { await ensurePlaceholder() }
            phone.stop { [weak self] fileURL in
                guard let self else { return }
                Task { await self.upload(fileURL, durationSec: dur, recordedAt: at) }
            }
        case .pen:
            state = .uploading
            Task { await ensurePlaceholder() }
            PenController.shared.stopRecord()
            // 兜底:5s 内笔没回停止确认(cmd=3=0)→ 用已录 opus 强制收尾,不卡「保存中」、不丢音频。
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: 5_000_000_000)
                if self.state == .uploading { PenController.shared.endRecordingOnDisconnect() }
            }
        }
    }

    /// 陪伴笔录完(PenController 包好 .ogg 后回调,主线程)。
    func penDidFinish(fileURL: URL?, durationSec: Int, recordedAt: String?,
                      penFile: String? = nil, sn: String? = nil) {
        Task { await upload(fileURL, durationSec: durationSec, recordedAt: recordedAt,
                            contentType: "audio/ogg", penFile: penFile, sn: sn) }
    }

    // ── 「从陪伴笔同步」占位:导入时逐段建「处理中」行(带 recorded_at),下载再久也能看到在同步谁 ──
    private var syncPlaceholders: [String: Int] = [:]   // 机身文件名 → 占位 id

    /// 导入选中时逐段建占位(pen 来源)。
    func registerSyncPlaceholder(for f: PenFile) async {
        guard syncPlaceholders[f.name] == nil else { return }
        if let id = try? await ConsultantRepo.placeholder(recordedAt: f.recordedAt, source: nil).id {
            syncPlaceholders[f.name] = id
        }
    }

    /// 「从陪伴笔同步」:单个机身文件下载完成 → 上传回填它自己的占位行(不弹绑定,多段会轰炸)。
    func penSyncFileReady(fileURL: URL?, durationSec: Int, recordedAt: String?,
                          penFile: String?, sn: String?, remaining: Int) {
        let pid = penFile.flatMap { syncPlaceholders.removeValue(forKey: $0) }
        Task {
            await upload(fileURL, durationSec: durationSec, recordedAt: recordedAt,
                         contentType: "audio/ogg", penFile: penFile, sn: sn,
                         usePlaceholder: false, explicitPlaceholderId: pid, promptBind: false)
            if remaining == 0 { toast = "陪伴笔同步完成，请到「待整理」绑定顾客" }
        }
    }

    /// 同步某个文件下载失败(跳过,继续其余;清掉它的占位防僵尸)。
    func penSyncFileFailed(_ name: String, remaining: Int) {
        if let pid = syncPlaceholders.removeValue(forKey: name) {
            Task { try? await ConsultantRepo.cancelPlaceholder(pid) }
        }
        toast = remaining == 0 ? "同步失败，可稍后重试" : "有片段同步失败，继续同步其余…"
    }

    // ── 占位链路(对齐验收清单§5:录完几秒内冒「处理中」占位,上传回填同一行,失败 cancel 防僵尸)──
    // FIFO 队列:补取段可能几分钟后才上传,期间新段先传——占位交叉消费也无碍
    // (upload 自带 recorded_at 会覆盖占位行的,最终每行数据都正确)。
    private var placeholderIds: [Int] = []
    private var segmentPlaceholderMade = false   // 每段只建一个(App点停+笔回停会双触发 ensure)

    private func ensurePlaceholder() async {
        guard !segmentPlaceholderMade else { return }
        segmentPlaceholderMade = true
        if let id = try? await ConsultantRepo.placeholder(
            recordedAt: recordedAt, source: source == .phone ? "phone" : nil).id {
            placeholderIds.append(id)
        }
    }

    /// 上传统一走落盘重传队列(后台会话,锁屏/被杀系统代传;失败自动重试,不再丢音频)。
    private func upload(_ fileURL: URL?, durationSec: Int, recordedAt: String?,
                        contentType: String = "audio/m4a",
                        penFile: String? = nil, sn: String? = nil,
                        usePlaceholder: Bool = true, explicitPlaceholderId: Int? = nil,
                        promptBind: Bool = true) async {
        let pid = explicitPlaceholderId
            ?? (usePlaceholder ? (placeholderIds.isEmpty ? nil : placeholderIds.removeFirst()) : nil)
        guard let fileURL else {
            if state == .uploading { state = .idle }
            toast = "陪伴内容丢失，请重试"
            if let pid { try? await ConsultantRepo.cancelPlaceholder(pid) }
            return
        }
        UploadQueue.shared.enqueue(fileURL: fileURL, durationSec: durationSec, recordedAt: recordedAt,
                                   contentType: contentType, penFile: penFile, sn: sn,
                                   placeholderId: pid, promptBind: promptBind)
        if state == .uploading { state = .idle }
    }

    // ── 触感反馈:开始/结束录制手机震动(对齐安卓) ──
    private func hapticRecordStart() {
        UINotificationFeedbackGenerator().notificationOccurred(.success)
    }
    private func hapticRecordStop() {
        UIImpactFeedbackGenerator(style: .heavy).impactOccurred()
    }

    /// 单段录音上限:录满 90 分钟自动结束并保存上传(对齐 android onPenAutoStopped)。
    private static let maxRecordSec = 90 * 60

    // 计时墙钟化(审计 B4):挂起期间 Timer 停走,elapsed 若靠 +1 累加会失真、90分钟自动停失效。
    // 改为"开始时刻墙钟差值 - 暂停累计",每次 tick(含挂起后被唤醒的补跳)都得到真实值。
    private var segStartAt: Date?
    private var pausedAccum: TimeInterval = 0
    private var pauseBeganAt: Date?

    private func beginTimer() {
        elapsed = 0
        segStartAt = Date()
        pausedAccum = 0
        pauseBeganAt = nil
        hapticRecordStart()
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self, self.state == .recording, let start = self.segStartAt else { return }
                self.elapsed = max(0, Int(Date().timeIntervalSince(start) - self.pausedAccum))
                if self.elapsed >= Self.maxRecordSec {
                    self.stop()   // 正常收尾:保存+上传,与手动停一致
                    self.toast = "已录满 90 分钟，已自动保存并结束这一段。要继续请点「开启陪伴」💛"
                }
            }
        }
    }
    private func stopTimer() { timer?.invalidate(); timer = nil }
}
