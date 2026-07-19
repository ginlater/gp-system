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
    var penMac: String? = nil // 属于哪支笔(双笔场景防向错笔要文件;旧持久化数据无此键=放行)
    var addedAt: Date? = nil  // 入队时刻(D2:>3天没传出去判过期丢弃,别永远堵着弹层/保活)
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
            // ★2026-07-19 苹果 2.5.4 整改:删掉了静音音频保活(RecordKeepAlive)。
            // 苹果两拒 build 10,判词「uses background audio to keep the app alive」——
            // 静音 AVAudioEngine 假装播放来占后台,是它明令禁止且机器可检出的滥用。
            // 笔录音的后台存活改依赖 bluetooth-central 模式(BLE 实时流回调本就能唤醒 App);
            // audio 模式保留,但只给手机麦克风的真录音用(官方认可的后台录音用途)。
        }
    }
    // ★2.2.1 对齐(安卓478a331):默认值 .phone→.pen。手机麦与 App 同进程——进程死了手机录音必然
    //   一起死;重启后"还在录"的只可能是笔。默认 .phone 会在镜像回调(cmd9)到达前的窗口里让用户
    //   误开手机麦双录/点「结束」停错设备。手机路径由用户 setSource 显式选择,不依赖默认值。
    @Published var source: CompanionSource = .pen
    /// 高频计时专用小对象(审计 P7):只有计时文本观察它,每秒 tick 不再让 MainShell 全壳重绘。
    @MainActor final class RecordTicker: ObservableObject {
        @Published var elapsed = 0
        var label: String { String(format: "%02d:%02d", elapsed / 60, elapsed % 60) }
    }
    let ticker = RecordTicker()

    var elapsed: Int {
        get { ticker.elapsed }
        set { ticker.elapsed = newValue }
    }
    @Published var pendingUploads = 0
    @Published var toast: String?

    /// 录完上传成功后拿到的可绑定片段 id(对齐 android `bindPrompt` SharedFlow)。
    /// MainShell 监听此值 → 直接跳绑定页(强制绑定,无「稍后」对话框,android v2.0.60)。
    @Published var bindPrompt: Int?

    // 陪伴笔状态(由 PenController 回填;模拟器恒未连接)
    @Published var penConnected = false
    @Published var penBattery: Int?
    @Published var penSuffix: String?   // 实连笔的 MAC 尾号(双笔都叫 CB08,靠它区分连的是哪支)

    // 陪伴笔扫描/选笔(扫描 sheet 驱动)
    @Published var penScanning = false
    @Published var penDevices: [PenDevice] = []
    @Published var penConnecting = false

    private let phone = PhoneMicRecorder()
    private var scanStopGen = 0
    private var penStartGen = 0
    private var timer: Timer?
    private var recordedAt: String?

    private init() {
        PenController.shared.manager = self
        PenController.shared.setup()
        phone.onInterrupted = { [weak self] in self?.phoneInterrupted() }
        phone.onPauseGap = { [weak self] began in self?.phoneGap(began) }
        // 上传队列回调:成功弹绑定/提示;首次失败告知已入重传队列(不再丢)
        UploadQueue.shared.onUploaded = { [weak self] rid, prompt in
            guard let self, prompt else { return }
            if let rid, rid > 0 {
                // bind-before-upload:录完已经按占位 id 跳过绑定页 → 上传回填的是同一行,不二次弹
                if rid != self.lastPromptedRid { self.bindPrompt = rid }
            } else {
                self.toast = "陪伴已保存，去「待整理」绑定顾客"
            }
        }
        UploadQueue.shared.onFirstFailure = { [weak self] in
            self?.toast = "上传暂时失败，已加入重传队列，网络恢复后自动补传"
        }
        // 上次会话没消费掉的录音段占位 → 取消;同步占位跟随队列复活(D10)
        restoreSyncPlaceholders()
        cancelLeftoverPlaceholders()
    }

    /// B10:来电中断的静默间隙——recorder 停采但墙钟照走,不扣的话时长虚高、报告时间轴错位。
    func phoneGap(_ began: Bool) {
        guard source == .phone, state == .recording else { return }
        if began {
            if pauseBeganAt == nil { pauseBeganAt = Date() }
        } else if let p = pauseBeganAt {
            pausedAccum += Date().timeIntervalSince(p)
            pauseBeganAt = nil
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
        if s == .pen && !penConnected {
            PenController.shared.startSearch()   // 后台找笔 + autoConnect 已知的笔
            // 审计 P4:扫描别无限开着(耗电),30s 没连上就停;代际防旧 Task 掐掉新扫描(复查 B11)
            scanStopGen += 1
            let gen = scanStopGen
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: 30_000_000_000)
                if gen == scanStopGen && !penConnected && !penScanning {
                    PenController.shared.stopSearch()
                }
            }
        }
    }

    // MARK: 陪伴笔扫描/连接(扫描选笔 sheet 驱动,对齐 android SoniScanActivity)

    func startPenScan() {
        penDevices = []
        penConnecting = false
        penScanning = true
        PenController.shared.setAutoConnectSuppressed(true)   // 用户要自己挑,暂停自动抢连
        PenController.shared.startSearch()
        // 僵尸连接检测(审计 L1):15s 无果先查"系统级已连接外设"——App 被杀后笔可能仍挂在
        // 系统蓝牙上(连着就不广播,永远扫不到),命中给精准提示;查不到给通用提示
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 15_000_000_000)
            guard penScanning && penDevices.isEmpty && !penConnected else { return }
            if !PenBluetoothWatch.shared.isPoweredOn {
                toast = "手机蓝牙没有打开，请到控制中心/设置里打开蓝牙"
            } else if let name = PenBluetoothWatch.shared.findSystemConnectedPen() {
                PenController.shared.directConnectSystemPen()
                toast = "「\(name)」还挂在系统蓝牙上，正在直接连接…（10秒没反应就把笔关机再开机）"
            } else {
                toast = "一直搜不到？请确认笔已开机、在手机附近；必要时把笔关机再开机"
            }
        }
    }
    func stopPenScan() {
        penScanning = false
        penConnecting = false   // 审计 L13:关 sheet 时清连接中状态,防常驻扫描
        PenController.shared.setAutoConnectSuppressed(false)
        PenController.shared.stopSearch()
    }

    /// ★对齐安卓2.1.3①:蓝牙未开/未授权的**常驻全局红条**(西财店案:toast 一闪而过,
    /// 启动时蓝牙本来就关着更是只闪一次——顾问全程不知道,以为是笔坏了)。
    /// 只在用过笔的手机上显示(hasKnownPen,对齐安卓"须 last_mac 才显示"),不打扰纯手机麦用户。
    enum BtIssue { case off, unauthorized }
    @Published var btIssue: BtIssue?

    /// 系统蓝牙不可用(审计 L4):精准提示,替代对着空气扫描。
    func penBluetoothUnavailable(_ unauthorized: Bool, hasKnownPen: Bool = true) {
        penConnected = false
        toast = unauthorized ? "请到 设置→美业私教 里允许蓝牙权限，才能连接陪伴笔"
                             : "手机蓝牙已关闭，请打开蓝牙后陪伴笔会自动重连"
        if hasKnownPen { btIssue = unauthorized ? .unauthorized : .off }
    }

    /// 蓝牙恢复可用 → 撤红条。
    func penBluetoothRestored() { btIssue = nil }
    func connectPen(_ d: PenDevice) {
        penConnecting = true
        penScanning = false
        PenController.shared.setAutoConnectSuppressed(false)
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
        // 复查 P#3/B6:必须含 .paused——暂停中断连否则成僵尸会话,永远停在「已暂停」
        guard source == .pen, state == .recording || state == .starting || state == .paused else { return }
        if let p = pauseBeganAt { pausedAccum += Date().timeIntervalSince(p); pauseBeganAt = nil }
        stopTimer()
        let dur = elapsed
        elapsed = 0
        state = .uploading
        toast = "陪伴笔已断开；重连后将自动补取这段完整内容"
        Task { await ensurePlaceholder(durationSec: dur) }   // 断连收尾也要有「处理中」行可盯
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
                // 复查 S3:权限弹窗期间笔上开录会把 source 翻成 .pen——此时麦克风回调要自弃,
                // 否则 mic 成孤儿(永不 stop、m4a 无限增长)
                guard self.source == .phone else {
                    if ok { self.phone.stop { _ in } }
                    return
                }
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
            // 复查 B4:笔不响应开始命令时别永远卡「开始中」(toggle 对 starting 无效,只能杀App)
            penStartGen += 1
            let gen = penStartGen
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: 6_000_000_000)
                guard gen == self.penStartGen, self.state == .starting, self.source == .pen else { return }
                self.state = .idle
                self.toast = "陪伴笔没有响应，请确认笔在身边后重试"
            }
        }
    }

    /// 笔上报录音开始(cmd=3 record_state=1):App 点击 or 笔上按键殊途同归 → App 进入录音态。
    func penRecordingStarted() {
        guard state == .idle || state == .starting else {
            // 手机麦正在录时笔又开录(双笔/误按):笔那段会独立保存,给个明白话
            if isLive && source == .phone { toast = "陪伴笔在独立录制，那段会单独进「待整理」" }
            return
        }
        source = .pen
        recordedAt = PhoneMicRecorder.wallClock()
        segmentPlaceholderMade = false   // 新一段:允许再建占位
        beginTimer()
        state = .recording
        // 双笔场景:明确告诉用户是哪支在录,别对着另一支干等
        if let s = penSuffix { toast = "陪伴笔(尾号\(s))开始录制" }
    }

    /// 幂等镜像兜底(复查 B4):笔在录而 App 之前拒过镜像(手机麦占用)或卡在「开始中」——
    /// 心跳每 8s 仍上报"笔在录",这里把空闲/等开始的 App 拉回录音态,失同步不再永久化。
    func penMirrorRecordingIfIdle() {
        guard state == .idle || (state == .starting && source == .pen) else { return }
        penRecordingStarted()
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
    /// 复查 B5:不清 pausedAccum——校准值本身是含暂停的墙钟差,已有的暂停累计不能抹。
    func penElapsedCalibrated(_ seconds: Int) {
        guard source == .pen, state == .recording || state == .paused else { return }
        if seconds > elapsed {
            segStartAt = Date().addingTimeInterval(-Double(seconds))
            elapsed = max(0, seconds - Int(pausedAccum))
        }
    }

    /// 笔上报录音停止(cmd=3 record_state=0):笔按键停时 App 同步结束(App 点击停已自行处理)。
    func penRecordingStopped() {
        guard source == .pen, isLive else { return }
        hapticRecordStop()
        stopTimer()
        let dur = elapsed
        elapsed = 0
        state = .uploading
        Task { await ensurePlaceholder(durationSec: dur) }   // 立即占位:未归档几秒内冒「处理中」,补下载再久也有行可盯
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
            Task { await ensurePlaceholder(durationSec: dur) }
            phone.stop { [weak self] fileURL in
                guard let self else { return }
                Task { await self.upload(fileURL, durationSec: dur, recordedAt: at) }
            }
        case .pen:
            state = .uploading
            Task { await ensurePlaceholder(durationSec: dur) }
            PenController.shared.stopRecord()
            // 兜底:5s 没等到停止确认(cmd=3=0)→ 先问笔真停没,再决定重发停止/强制收尾
            // (复查 B6:stopRecord 丢包时笔其实还在录,直接按停收尾会劈成部分件+复活镜像段)
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: 5_000_000_000)
                if self.state == .uploading { PenController.shared.confirmStopOrForceFinish() }
            }
        }
    }

    /// 陪伴笔录完(PenController 包好 .ogg 后回调,主线程)。truncated=诚实部分件——
    /// D1:带标记入库(服务端记 truncate_note),之后从笔同步到完整版可自动替换,不再被去重堵死。
    func penDidFinish(fileURL: URL?, durationSec: Int, recordedAt: String?,
                      penFile: String? = nil, sn: String? = nil, truncated: Bool = false) {
        Task { await upload(fileURL, durationSec: durationSec, recordedAt: recordedAt,
                            contentType: "audio/ogg", penFile: penFile, sn: sn, truncated: truncated) }
    }

    // ── 「从陪伴笔同步」占位:导入时逐段建「处理中」行(带 recorded_at),下载再久也能看到在同步谁 ──
    private var syncPlaceholders: [String: Int] = [:]   // 机身文件名 → 占位 id

    /// 导入选中时逐段建占位(pen 来源)。哨兵占坑防 TOCTOU 双占位(审计 U8)。
    func registerSyncPlaceholder(for f: PenFile) async {
        guard syncPlaceholders[f.name] == nil else { return }
        syncPlaceholders[f.name] = -1   // 占坑
        // D6:占位带机身文件名 → 服务端预检可精确屏蔽"正在传的段",不再赌±90s时刻吻合
        // duration_sec(2.2.1):机身文件列表本来就有时长 →「同步中」行直接显示时段·时长
        if let id = try? await ConsultantRepo.placeholder(recordedAt: f.recordedAt, source: nil,
                                                          penFile: f.name, durationSec: f.durationSec).id {
            syncPlaceholders[f.name] = id
        } else {
            syncPlaceholders.removeValue(forKey: f.name)
        }
        persistPlaceholders()
    }

    /// 「从陪伴笔同步」:单个机身文件下载完成 → 上传回填它自己的占位行(不弹绑定,多段会轰炸)。
    func penSyncFileReady(fileURL: URL?, durationSec: Int, recordedAt: String?,
                          penFile: String?, sn: String?, remaining: Int) {
        let raw = penFile.flatMap { syncPlaceholders.removeValue(forKey: $0) }
        persistPlaceholders()
        let pid = (raw ?? 0) > 0 ? raw : nil   // 过滤 -1 哨兵
        Task {
            await upload(fileURL, durationSec: durationSec, recordedAt: recordedAt,
                         contentType: "audio/ogg", penFile: penFile, sn: sn,
                         usePlaceholder: false, explicitPlaceholderId: pid, promptBind: false)
            if remaining == 0 { toast = "陪伴笔同步完成，请到「待整理」绑定顾客" }
        }
    }

    /// ★2.2.2:连上笔后自动把「机身上还没传上来的段」补传回来(对齐安卓的自动补传路径)。
    ///
    /// 病案(刘亚红 2026-07-13):她点开 App 5 秒后直接按笔上按键开录,App 还没连上笔就被 iOS 挂起
    /// (后台不录音就没有保活)——笔自己录了 12 分钟,App 全程不在场,回来时笔已停,那段录音
    /// 就一直躺在笔里,直到 3 小时后她自己想起来点「从陪伴笔同步」才回来。
    /// iOS 原来只有手动同步这一条路;这里补上自动的:连上笔且空闲 → 拉机身列表 → 服务端预检
    /// 查出「从没传上来过」的 → 自动建占位 + 排队下载上传。顾问什么都不用做。
    ///
    /// 安全边界(不敢乱传):① 只补最近 7 天的(超 7 天服务端本来就不可绑,重导无意义);
    /// ② 正在录的那段不碰;③ 已在队列/在传的不碰;④ 一律走服务端 sync-preview 判定,只补
    /// status=new 的(已上传/顾问删过的一律不补,墓碑挡着);⑤ 一轮最多 20 段,防笔里积压太多时刷屏。
    func autoImportUnuploaded(_ files: [PenFile]) async {
        guard !files.isEmpty else { return }
        let cur = PenController.shared.currentRecordingPenFile
        let busy = PenController.shared.queuedSyncNames
            .union(UploadQueue.shared.pendingPenFiles)
            .union(syncPlaceholders.keys)
        let cutoff = Date().addingTimeInterval(-7 * 86400)
        let candidates = files.filter { f in
            f.name != cur && f.sizeBytes > 0 && !busy.contains(f.name)
                && (PenFileLedger.fileTimestamp(f.name).map { $0 > cutoff } ?? false)
        }
        guard !candidates.isEmpty else { return }

        // 预检分批(每批30),任一批失败即整体放弃——宁可不补,也不能凭猜测重复导入
        var statusByName: [String: String] = [:]
        var idx = 0
        while idx < candidates.count {
            let batch = Array(candidates[idx ..< min(idx + 30, candidates.count)])
            guard let p = try? await ConsultantRepo.penSyncPreview(batch.map { ($0.name, $0.recordedAt) }) else {
                PenLog.d("★自动补传:预检失败(弱网/未登录)→ 本轮放弃,下次连上再试")
                return
            }
            for i in p.items ?? [] { if let n = i.name { statusByName[n] = i.status ?? "new" } }
            idx += 30
        }
        let fresh = candidates
            .filter { (statusByName[$0.name] ?? "") == "new" }
            .sorted { ($0.recordedAt ?? "") > ($1.recordedAt ?? "") }
        guard !fresh.isEmpty else {
            PenLog.d("★自动补传:笔上没有未上传的段(都传过了)")
            return
        }
        let picked = Array(fresh.prefix(20))
        PenLog.d("★自动补传:发现 \(fresh.count) 段从未上传的录音 → 自动导入 \(picked.count) 段")
        for f in picked { await registerSyncPlaceholder(for: f) }
        PenController.shared.startSync(files: picked)
        toast = "发现 \(picked.count) 段还没上传的陪伴录音，正在自动同步…"
    }

    /// ★2.2.1 对齐:顾问删掉"同步中"的行 → 取消这段一切在途任务(对齐安卓 cancelPenTaskByPlaceholder)。
    /// 覆盖两条腿:① UploadQueue 里待传/在传的(手机麦段无 pen_file,墓碑兜不住,必须掐);
    /// ② 还没从笔里搬完的同步下载(掐了省蓝牙省流量,墓碑按 pen_file 精确兜底做双保险)。
    func cancelTasks(placeholderId: Int) {
        guard placeholderId > 0 else { return }
        UploadQueue.shared.cancel(placeholderId: placeholderId)
        if let name = syncPlaceholders.first(where: { $0.value == placeholderId })?.key {
            syncPlaceholders.removeValue(forKey: name)
            persistPlaceholders()
            PenController.shared.cancelSync(fileName: name)
        }
    }

    /// 同步某个文件下载失败(跳过,继续其余;清掉它的占位防僵尸)。
    func penSyncFileFailed(_ name: String, remaining: Int) {
        if let pid = syncPlaceholders.removeValue(forKey: name), pid > 0 {
            Task { try? await ConsultantRepo.cancelPlaceholder(pid) }
        }
        persistPlaceholders()
        toast = remaining == 0 ? "同步失败，可稍后重试" : "有片段同步失败，继续同步其余…"
    }

    // ── 占位链路(对齐验收清单§5:录完几秒内冒「处理中」占位,上传回填同一行,失败 cancel 防僵尸)──
    // FIFO 队列:补取段可能几分钟后才上传,期间新段先传——占位交叉消费也无碍
    // (upload 自带 recorded_at 会覆盖占位行的,最终每行数据都正确)。
    // 落盘:App 被杀时未消费的占位号留在盘上,下次启动统一 cancel(死会话的段等不到上传;
    //      真音频在 UploadQueue/笔机身,不受影响)——根治「后台同步中」僵尸行。
    private static let kPendingPlaceholders = "pending_placeholder_ids"
    private static let kSyncPlaceholders = "pen_sync_placeholders"   // 机身文件名→占位id
    private var placeholderIds: [Int] = []
    private var segmentPlaceholderMade = false   // 每段只建一个(App点停+笔回停会双触发 ensure)

    /// D10:同步占位与录音段占位分账——同步任务队列被杀后会恢复续传,它们的占位必须跟着活下来,
    /// 不能在启动时被一锅端(否则「后台同步中」行集体消失,几分钟后又冒出无占位的新行)。
    private func persistPlaceholders() {
        UserDefaults.standard.set(placeholderIds, forKey: Self.kPendingPlaceholders)
        let alive = syncPlaceholders.filter { $0.value > 0 }
        UserDefaults.standard.set(alive, forKey: Self.kSyncPlaceholders)
    }

    /// 启动恢复:同步占位跟随持久化的同步队列复活(孤儿由服务端±5s兄弟修复+3h TTL兜底)。
    private func restoreSyncPlaceholders() {
        if let m = UserDefaults.standard.dictionary(forKey: Self.kSyncPlaceholders) as? [String: Int] {
            syncPlaceholders = m.filter { $0.value > 0 }
        }
    }

    /// 启动清理:上次会话没消费掉的占位号,统一取消(服务器还有 ±5s 兄弟匹配 + 2h TTL 双保险)。
    func cancelLeftoverPlaceholders() {
        let leftovers = UserDefaults.standard.array(forKey: Self.kPendingPlaceholders) as? [Int] ?? []
        guard !leftovers.isEmpty else { return }
        UserDefaults.standard.removeObject(forKey: Self.kPendingPlaceholders)
        Task {
            for pid in leftovers { _ = try? await ConsultantRepo.cancelPlaceholder(pid) }
            PenLog.d("已清理上次会话遗留占位 \(leftovers.count) 个")
        }
    }

    private var lastPromptedRid = 0   // bind-before-upload:占位已跳过绑定页,上传回填同行不二次弹

    /// durationSec(2.2.1 对齐):>0 时「同步中」行能显示时段·时长——实时录音传会话时长。
    private func ensurePlaceholder(durationSec: Int = 0) async {
        guard !segmentPlaceholderMade else { return }
        segmentPlaceholderMade = true
        if let id = try? await ConsultantRepo.placeholder(
            recordedAt: recordedAt, source: source == .phone ? "phone" : nil,
            durationSec: durationSec).id {
            placeholderIds.append(id)
            persistPlaceholders()
            // bind-before-upload(对齐安卓):占位一到手立刻跳绑定页,不等上传/补取传完——
            // 先绑好顾客,音频落地后服务端自动归位到该顾客并接力转写
            lastPromptedRid = id
            bindPrompt = id
        }
    }

    /// 上传统一走落盘重传队列(后台会话,锁屏/被杀系统代传;失败自动重试,不再丢音频)。
    private func upload(_ fileURL: URL?, durationSec: Int, recordedAt: String?,
                        contentType: String = "audio/m4a",
                        penFile: String? = nil, sn: String? = nil,
                        usePlaceholder: Bool = true, explicitPlaceholderId: Int? = nil,
                        promptBind: Bool = true, truncated: Bool = false) async {
        let pid = explicitPlaceholderId
            ?? (usePlaceholder ? (placeholderIds.isEmpty ? nil : placeholderIds.removeFirst()) : nil)
        persistPlaceholders()   // 占位号交给 UploadQueue(队列自己落盘)后,从"未消费"名单移除
        guard let fileURL else {
            if state == .uploading { state = .idle }
            toast = "陪伴内容丢失，请重试"
            if let pid { try? await ConsultantRepo.cancelPlaceholder(pid) }
            return
        }
        UploadQueue.shared.enqueue(fileURL: fileURL, durationSec: durationSec, recordedAt: recordedAt,
                                   contentType: contentType, penFile: penFile, sn: sn,
                                   placeholderId: pid, promptBind: promptBind, truncated: truncated)
        if state == .uploading { state = .idle }
    }

    // ── 触感反馈:开始/结束录制手机震动(对齐安卓) ──
    private func hapticRecordStart() {
        UINotificationFeedbackGenerator().notificationOccurred(.success)
    }
    private func hapticRecordStop() {
        UIImpactFeedbackGenerator(style: .heavy).impactOccurred()
    }

    /// 连续录音上限:录满 90 分钟自动结束并保存上传(对齐 android onPenAutoStopped)。
    /// ★2.2.1:算的是【整条连续录音链】——本段墙钟 + 前序段(笔60分钟自动切段)总时长。
    private static let maxRecordSec = 90 * 60

    /// ★2.2.1(对齐安卓2.1.7 chainRecSec):连续录音链前序段总秒数(不含当前段)。
    /// 接管"笔自己在录"的段后由 PenController 回溯机身文件列表得出;App 点开始=新接诊,清零。
    private var chainPriorSec = 0

    /// PenController 回溯出链长(主线程回调)。下一拍计时 tick(≤1s)即按 elapsed+chain 裁决。
    func penChainComputed(_ sec: Int) {
        guard source == .pen, isLive else { return }
        chainPriorSec = sec
    }

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
        chainPriorSec = 0   // 新一段从0算;接管段的链长稍后由 penChainComputed 回填
        hapticRecordStart()
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self, self.state == .recording, let start = self.segStartAt else { return }
                self.elapsed = max(0, Int(Date().timeIntervalSince(start) - self.pausedAccum))
                // ★2.2.1:按【整条连续录音链】裁决——只看当前段的话,笔60分钟自动切段永远够不到90分钟
                if self.elapsed + self.chainPriorSec >= Self.maxRecordSec {
                    let chained = self.chainPriorSec > 0
                    self.stop()   // 正常收尾:保存+上传,与手动停一致
                    self.toast = chained
                        ? "陪伴笔连续录音累计已满 90 分钟（含先前自动切的段），已自动保存并结束。要继续请点「开启陪伴」💛"
                        : "已录满 90 分钟，已自动保存并结束这一段。要继续请点「开启陪伴」💛"
                }
            }
        }
    }
    private func stopTimer() { timer?.invalidate(); timer = nil }
}
