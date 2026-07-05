package com.airec.bledemo.recording

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.airec.bledemo.data.auth.AuthManager
import com.airec.bledemo.net.Uploader
import com.airec.bledemo.soni.SoniPenController
import com.airec.bledemo.soni.SoniScanActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [RecordingController] 的实现骨架——桥接到现有 Java 引擎，不改引擎内部逻辑（复用而非重写）。
 *
 * 已直接接上的部分：
 *  - [RecordingBus] 状态订阅 → 翻译成 [RecordingState] / 各 StateFlow（含切回前台时同步最近快照）。
 *  - 手机麦克风：起 / 停前台 [PhoneMicService]（走它的 ACTION_START/ACTION_STOP Intent）。
 *  - 陪伴笔：转发到 [PenController]（startRecording/stopRecording/pause/resume/连接判定/同步/补传重试）。
 *  - 待传 / 失败 / 进度：经 PenController.Listener 回调驱动 StateFlow。
 *
 * 留待【集成阶段】接的真实链路（已用 TODO 标注）：
 *  - Cookie / 上传 URL 的真实值：现在由 [setUploadContext] 注入并缓存，但谁来调、何时调（登录态就绪 /
 *    进接诊页）由集成层接 PersistentCookieJar 决定。
 *  - 前台通知点击意图：现仍指向旧 ConsultantActivity（在 PhoneMicService/PenKeepAliveService 里硬编码），
 *    集成阶段改指向新的 Compose MainActivity 入口。
 *  - connectPen / openPenManager：现走旧 ScanActivity（TODO 改 Compose 扫描页 / 由集成层注入跳转）。
 *  - syncPenFiles 回调：已把 PenController.onPenFileList 的 JSON 解析成 [PenFile] 列表回调；
 *    上传 URL 必须先 setUploadContext 才能用（否则引擎静默忽略，见 TODO）。
 *
 * @param appContext  Application Context（起 Service / 构造 PenController 用）。
 */
class RecordingControllerImpl(
    appContext: Context,
) : RecordingController {

    private val appCtx: Context = appContext.applicationContext

    // ============ StateFlow 暴露 ============

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle())
    override val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val _penConnected = MutableStateFlow(false)
    override val penConnected: StateFlow<Boolean> = _penConnected.asStateFlow()

    private val _pendingCount = MutableStateFlow(0)
    override val pendingCount: StateFlow<Int> = _pendingCount.asStateFlow()

    private val _failedCount = MutableStateFlow(0)
    override val failedCount: StateFlow<Int> = _failedCount.asStateFlow()

    private val _progressPercent = MutableStateFlow(0)
    override val progressPercent: StateFlow<Int> = _progressPercent.asStateFlow()

    private val _penBattery = MutableStateFlow<PenBattery?>(null)
    override val penBattery: StateFlow<PenBattery?> = _penBattery.asStateFlow()

    // 一次性面向用户的陪伴笔提示（连接成功/错误/归属拒绝/开机自录被关）——独立于去重的 _state，绝不被覆盖吞掉。
    private val _penEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val penEvents: SharedFlow<String> = _penEvents.asSharedFlow()

    // 后台机身片段落地（补传成功/建占位）→ 让 UI 刷新「待整理」。
    private val _penListChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    override val penListChanged: SharedFlow<Unit> = _penListChanged.asSharedFlow()

    // 录完即拿到可绑定 recordingId(占位)→ 弹绑定对话框(不必等上传)。
    private val _bindPrompt = MutableSharedFlow<Long>(extraBufferCapacity = 4)
    override val bindPrompt: SharedFlow<Long> = _bindPrompt.asSharedFlow()

    // 连接/查状态时回调上层重注上传上下文（Cookie 轮换兜底）；由 RecordingModule.init 接上。
    override var onNeedContextRefresh: (() -> Unit)? = null

    // 「连接成功」提示去抖：仅 false→true 才弹一次；断开（含链路悄悄掉，由巡检复位）后再连上能再弹。
    @Volatile private var penWasConnected = false

    // ============ 上传上下文（集成阶段注入；陪伴笔自发录音也要用，缓存在此） ============

    @Volatile private var cookie: String? = null

    // 上传 URL 单一事实源在 [NetworkModule.uploadUrl]；这里取它作默认兜底，登录后 setUploadContext 再覆盖会话级。
    @Volatile private var uploadUrl: String? = com.airec.bledemo.data.net.NetworkModule.uploadUrl

    // 手机麦遗留补传扫描的并发护栏（回前台会多次触发 refreshUploadContext，避免重入并发补传）。
    @Volatile private var phoneMicScanning = false

    // 上传遇 401 自动重登的并发护栏 + 后台协程作用域。
    private val authScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var reAuthing = false

    /**
     * 上传遇 401（登录失效）→ 用本地保存的凭证悄悄重新登录，成功后刷新上传上下文（拿到新 Cookie 注回引擎，
     * 待补传段会用新 Cookie 重试）。顾问全程无感，不再手动重登。并发护栏避免多段同时 401 时重复登录。
     */
    private fun reAuthOnExpired() {
        if (reAuthing) return
        reAuthing = true
        authScope.launch {
            try {
                val ok = AuthManager().reAuthenticate()
                if (ok) {
                    Log.i(TAG, "401 自动重登成功 → 刷新上传上下文重试")
                    onNeedContextRefresh?.invoke()
                } else {
                    Log.w(TAG, "401 自动重登失败（无凭证或被拒）")
                }
            } finally {
                reAuthing = false
            }
        }
    }

    // ============ 当前来源（仅用于 Recording 态打标，引擎本身以镜像为准） ============

    @Volatile private var currentSource: CompanionSource = CompanionSource.Phone

    // ============ 录音时间「同步中」判定（重连到已在录的笔时，先别显示从0跳的假计时） ============
    // 本进程内用户主动开的录 → 时长天然从0真实自走，无需「同步中」。
    @Volatile private var sessionStartedByUser = false
    // 陪伴笔已回报过真实时长(onPenRecordDuration) → 时长可信。
    @Volatile private var penTimeSynced = false

    /** 给即将下发的状态打 timeSynced：进行中(非starting)按"用户主动开或已收到真实时长"判定；回 Idle 时复位两个标志。 */
    private fun applyTimeSync(st: RecordingState): RecordingState = when {
        st is RecordingState.Idle -> {
            sessionStartedByUser = false
            penTimeSynced = false
            st
        }
        st is RecordingState.Recording && !st.starting ->
            // 手机麦时长永远本地真实自走（ViewModel 墙钟），不存在「同步中」——只有陪伴笔重连到「已在录」才可能未同步。
            // 已知真实时长(非笔 / 用户主动开 / 笔回报过 / 本帧 durSec 已非0) → 同步好；否则=笔重连占位0=同步中。
            st.copy(timeSynced = st.source != CompanionSource.Pen || sessionStartedByUser || penTimeSynced || st.durationSec > 0)
        else -> st
    }

    // 手动「从陪伴笔同步」的一次性回调：拉到列表后回调并清空。
    @Volatile private var penFilesCallback: ((List<PenFile>) -> Unit)? = null

    // ============ 「未连接点开启陪伴 → 连上后自动开录」意图（对齐旧宿主 pendingRecordAfterConnect） ============

    // 笔未连接时点了「开启陪伴」：置此标志、postState starting（UI 显示「正在连接陪伴笔…」）、打开扫描。
    // 连上(onPenConnected(true) 或 onPenRecordStatus)后若仍为真：清标志并 penController.startRecording —
    // 笔已在录则 PenController 内部识别为重复开始(标记 app-initiated + 回推 recording)，空闲则真开录，两路都对。
    @Volatile private var pendingStartAfterConnect = false

    private val mainHandler = Handler(Looper.getMainLooper())

    // 连上后迟迟没真开录的兜底超时：清意图、回 Idle 报连接不稳（避免「正在连接」永久卡死）。
    private val pendingConnectTimeout = Runnable {
        if (pendingStartAfterConnect) {
            pendingStartAfterConnect = false
            postIdleError("陪伴笔连接不稳定，请确认它已开机靠近后重试")
        }
    }

    /** 回 Idle 并把错误文案既落到状态行(供页面显示)、又经一次性事件流可靠弹 Toast(不被去重/覆盖吞掉)。 */
    private fun postIdleError(msg: String) {
        _penEvents.tryEmit(msg)
        _state.value = RecordingState.Idle(errorMessage = msg)
    }

    // ============ 陪伴笔控制器（复用现有 Java，监听桥接到 StateFlow） ============

    private val penListener = object : SoniPenController.Listener {
        override fun onPenNeedConnect() {
            // 笔未连接 → 需要 UI 去打开扫描 / 连接页。
            // TODO(集成): 触发跳转到（Compose 化的）陪伴笔扫描页；现仅回退到 connectPen()。
            connectPen()
        }

        override fun onPenRecordStatus(recording: Boolean) {
            // 连上后查到的笔真实录音状态——状态本身由 RecordingBus 驱动，这里无需重复改 _state。
            // ★笔在录（接管中途会话/笔自发）：来源必须切到 Pen——进程重启后 currentSource 默认手机麦，
            //   不切的话点「结束陪伴」会走 stopPhoneMic，笔上的录音根本停不下来（真机已踩）。
            if (recording) currentSource = CompanionSource.Pen
            // 重注上传上下文：笔自发录音（声控/笔上操作）也要用同样的会话 Cookie（对齐旧宿主 onPenRecordStatus）。
            onNeedContextRefresh?.invoke()
            // 若有「连上后自动开录」意图（用户在笔未连时点了开启陪伴）→ 现在兑现。
            consumePendingStartIfAny()
        }

        override fun onPenRecordDuration(durationSec: Int) {
            // 笔报来真实时长 = 时间已同步（重连场景据此从「同步中」切回真实计时）。
            penTimeSynced = true
            val cur = _state.value
            if (cur is RecordingState.Recording) {
                _state.value = cur.copy(durationSec = durationSec, timeSynced = true)
            } else if (cur is RecordingState.Paused) {
                _state.value = cur.copy(durationSec = durationSec)
            }
        }

        override fun onPenConnected(connected: Boolean) {
            _penConnected.value = connected
            if (connected) {
                // 首次 false→true：弹「陪伴笔已连接」一次（对齐旧宿主 Toast）+ 重注上传上下文。
                if (!penWasConnected) {
                    penWasConnected = true
                    _penEvents.tryEmit("陪伴笔已连接")
                    onNeedContextRefresh?.invoke()
                }
                // 真连上（首个真回包）→ 若有「连上后自动开录」意图则兑现。
                consumePendingStartIfAny()
            } else {
                penWasConnected = false
                _penBattery.value = null   // 断开即清电量，别留旧值
                if (pendingStartAfterConnect) {
                    // 还没把「要开录」落地就断了 → 清意图(防泄漏到下次连接幽灵开录)、撤兜底、回 Idle。
                    pendingStartAfterConnect = false
                    mainHandler.removeCallbacks(pendingConnectTimeout)
                    postIdleError("没连上陪伴笔，请确认它已开机后重试")
                }
            }
        }

        override fun onPenPaused(paused: Boolean) {
            // 暂停态变化：RecordingBus 不一定单独上报 paused，这里据笔回调对齐。
            val cur = _state.value
            if (paused && cur is RecordingState.Recording) {
                _state.value = RecordingState.Paused(cur.durationSec, currentSource)
            } else if (!paused && cur is RecordingState.Paused) {
                _state.value = RecordingState.Recording(cur.durationSec, currentSource)
            }
        }

        override fun onPenPendingChanged(pending: Int, failed: Int) {
            _pendingCount.value = pending
            _failedCount.value = failed
        }

        override fun onPenBattery(percent: Int, charging: Boolean) {
            _penBattery.value = PenBattery(percent, charging)
        }

        override fun onPenAutoStopped() {
            // 录满 90 分钟自动结束这一段 → 一次性提示顾问（这段已正常保存上传；要继续得手动再开启）。
            _penEvents.tryEmit("已录满 90 分钟，已自动保存并结束这一段。要继续请点「开启陪伴」💛")
        }

        override fun onPenUploaded(recordingId: Long) {
            // 一段后台上传成功 → 通知 UI 刷新「待整理」列表与首页待整理计数（对齐旧宿主 → loadPending）。
            // 绑定提示已在「建占位时」(onPenBindPrompt)弹过，这里不再重复提示。
            _penListChanged.tryEmit(Unit)
        }

        override fun onPenBindPrompt(recordingId: Long) {
            // 一段录音刚建好占位、拿到可绑定 recordingId（不必等上传完成）→ 弹绑定对话框。
            if (recordingId > 0) _bindPrompt.tryEmit(recordingId)
        }

        override fun onPenPlaceholderCreated() {
            // 已建占位片段 → 让「待整理」立刻显示出来（同上）。
            _penListChanged.tryEmit(Unit)
        }

        override fun onPenProgress(percent: Int) {
            _progressPercent.value = percent
        }

        override fun onPenFileList(filesJson: String) {
            val cb = penFilesCallback
            penFilesCallback = null
            cb?.invoke(parsePenFiles(filesJson))
        }

        // 声云笔没有「开机自动录制」设置项，杰理版的 onPenPowerOnRecordDisabled 在此引擎不存在。
    }

    private val penController: SoniPenController by lazy {
        SoniPenController(appCtx, penListener).also {
            // 上传遇 401(登录失效) → 引擎不丢段退避重试；这里用本地凭证自动重登拿新 Cookie 注回引擎（顾问无感）。
            it.onAuthExpired = Runnable { reAuthOnExpired() }
        }
    }

    // ============ RecordingBus 监听（引擎单向上报 → 翻译成 RecordingState） ============

    private val busListener = RecordingBus.Listener { busState, message, durSec, recId ->
        // 错误文案走一次性事件流可靠弹出（不依赖 Idle.errorMessage 在去重的 _state 里存活）。
        if (busState == PhoneMicService.STATE_ERROR && !message.isNullOrBlank()) {
            _penEvents.tryEmit(message)
        }
        // 手机麦绑定提示：D12 停录建占位后 UPLOADING 即带占位 recId（录完立刻能绑，不等传完）；
        // 上传成功的 IDLE 也带 recId 兜底（占位失败降级时仍按老路弹）。
        // 去重：同一 recId 只提示一次（attach 同步快照会重放最近 Idle；占位/回填是同一行 id）。
        if ((busState == PhoneMicService.STATE_IDLE || busState == PhoneMicService.STATE_UPLOADING) &&
            recId > 0 && recId != lastBusBindRecId
        ) {
            lastBusBindRecId = recId
            _bindPrompt.tryEmit(recId)
            _penListChanged.tryEmit(Unit)   // 占位已入待整理 → 顺手刷新列表
        }
        _state.value = applyTimeSync(mapBusState(busState, message, durSec, recId))
    }

    @Volatile private var lastBusBindRecId: Long = -1L

    override fun attach() {
        RecordingBus.setListener(busListener)
        // 切回前台：用 Bus 的最近快照同步一次，避免 UI 错过中途状态。
        // ★"假录音中"兜底：显示进行中但陪伴笔其实没在录(黑屏笔结束、idle 没传到→快照残留)→当场拉回 Idle。
        _state.value = applyTimeSync(
            withRecordingConsistency(
                mapBusState(RecordingBus.lastState, RecordingBus.lastMessage, RecordingBus.lastDurSec, -1L),
            ),
        )
        // 同步一次待传 / 失败 / 连接快照。
        _pendingCount.value = penController.pendingCount()
        _failedCount.value = penController.pendingFailedCount()
        _penConnected.value = penController.isPenAlive()
        penController.setAppForeground(true)   // SDK 要求把 App 前后台状态告诉笔（按键交互逻辑依赖）
        startConsistencyWatch()
    }

    override fun detach() {
        RecordingBus.clear(busListener)
        penController.setAppForeground(false)
        stopConsistencyWatch()
    }

    /**
     * "假录音中"一致性兜底（对齐旧宿主 bridgeGetState/onResume + 每 3s 核对）：
     * 仅当**来源是陪伴笔**且引擎 isRecording()=false 时，把残留的「进行中」拉回 Idle——
     * 手机麦由前台 Service 可靠驱动、不走这条，绝不误杀手机录音。
     */
    private fun withRecordingConsistency(s: RecordingState): RecordingState =
        if (s is RecordingState.Recording && !s.starting &&
            currentSource == CompanionSource.Pen && !penController.isRecording()
        ) {
            RecordingState.Idle()
        } else {
            s
        }

    private var consistencyWatch: Runnable? = null

    /**
     * 每 3s 核对（对齐 web 的轮询自愈）：
     *  1. 兜底回拉引擎快照（连接/待传/失败）——防 push 回调漏报导致指示停留旧值。
     *  2. 链路悄悄掉了（笔关机/走远，常无 onPenConnected(false)）→ 复位连接提示去抖，下次真连上能再弹。
     *  3. 「假录音中」：显示进行中但笔其实没在录 → 拉回 Idle。
     */
    private fun startConsistencyWatch() {
        stopConsistencyWatch()
        val r = object : Runnable {
            override fun run() {
                val alive = penController.isPenAlive()
                _penConnected.value = alive
                _pendingCount.value = penController.pendingCount()
                _failedCount.value = penController.pendingFailedCount()
                if (!alive) penWasConnected = false
                val corrected = withRecordingConsistency(_state.value)
                if (corrected !== _state.value) _state.value = corrected
                mainHandler.postDelayed(this, 3000)
            }
        }
        consistencyWatch = r
        mainHandler.postDelayed(r, 3000)
    }

    private fun stopConsistencyWatch() {
        consistencyWatch?.let { mainHandler.removeCallbacks(it) }
        consistencyWatch = null
    }

    // ============ 能力实现 ============

    override fun getSources(): List<CompanionSource> {
        // 陪伴笔在线则两种都可用；否则只手机。引擎 getSources 原是逗号串，这里直接据连接判定即可。
        return if (penController.isPenAlive()) {
            listOf(CompanionSource.Phone, CompanionSource.Pen)
        } else {
            listOf(CompanionSource.Phone)
        }
    }

    override fun startCompanion(source: CompanionSource) {
        currentSource = source
        when (source) {
            CompanionSource.Phone -> {
                sessionStartedByUser = true   // 手机麦由用户主动开：时长从 0 真实自走，永不进入「同步中」
                penTimeSynced = false
                startPhoneMic()
            }
            CompanionSource.Pen -> {
                sessionStartedByUser = true   // 用户主动开录：时长从 0 真实自走，不进入「同步中」
                penTimeSynced = false
                // 笔未真连接 → 记下「连上后自动开录」意图、显示「正在连接陪伴笔…」(starting)、打开扫描连接页。
                // 连上后由 onPenConnected(true)/onPenRecordStatus 兑现意图（对齐旧宿主 pendingRecordAfterConnect），
                // 用户无需再点第二次。
                if (!penController.isPenAlive()) {
                    pendingStartAfterConnect = true
                    _state.value = RecordingState.Recording(
                        durationSec = 0,
                        source = CompanionSource.Pen,
                        starting = true,
                        message = "正在连接陪伴笔…",
                    )
                    mainHandler.removeCallbacks(pendingConnectTimeout)
                    mainHandler.postDelayed(pendingConnectTimeout, PENDING_CONNECT_TIMEOUT_MS)
                    connectPen()
                    return
                }
                // 已真在线：把上传上下文喂进去（笔自发录音也要用），再发开始。
                penController.startRecording(cookie ?: "", uploadUrl ?: "")
                if (cookie.isNullOrEmpty() || uploadUrl.isNullOrEmpty()) {
                    Log.w(TAG, "startCompanion(Pen): 缺上传上下文（集成层应在进首页前 setUploadContext）")
                }
            }
        }
    }

    /**
     * 兑现「连上后自动开录」意图（连上的两个信号——onPenConnected(true)/onPenRecordStatus——任一触发）。
     * 幂等：清标志后再发，避免两个回调各发一次。直接复用 [PenController.startRecording]：
     * 笔已在录会被识别为重复开始（标记 app-initiated + 回推 recording 纠正 UI），空闲则真开录。
     */
    private fun consumePendingStartIfAny() {
        if (!pendingStartAfterConnect) return
        pendingStartAfterConnect = false
        mainHandler.removeCallbacks(pendingConnectTimeout)
        currentSource = CompanionSource.Pen
        penController.startRecording(cookie ?: "", uploadUrl ?: "")
    }

    override fun stopCompanion() {
        // 取消「连上后自动开录」意图：用户在「正在连接陪伴笔…」时点圆钮=取消（对齐 web starting 态点击=stop）。
        if (pendingStartAfterConnect) {
            pendingStartAfterConnect = false
            mainHandler.removeCallbacks(pendingConnectTimeout)
            _state.value = RecordingState.Idle()
            return
        }
        when (currentSource) {
            CompanionSource.Phone -> stopPhoneMic()
            CompanionSource.Pen -> penController.stopRecording()
        }
    }

    override fun pause() {
        // 仅陪伴笔支持暂停。
        penController.pause()
    }

    override fun resume() {
        penController.resume()
    }

    override fun isPenConnected(): Boolean = penController.isPenAlive()

    override fun lastSource(): CompanionSource? {
        val raw = appCtx.getSharedPreferences(PREFS_COMPANION, Context.MODE_PRIVATE)
            .getString(KEY_LAST_SOURCE, null) ?: return null
        return CompanionSource.entries.firstOrNull { it.wireValue == raw }
    }

    override fun saveSource(source: CompanionSource) {
        appCtx.getSharedPreferences(PREFS_COMPANION, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_SOURCE, source.wireValue)
            .apply()
    }

    override fun connectPen() {
        // 声云陪伴笔扫描/连接页。先 activate 确保引擎单例指向本控制器。
        penController.activate()
        try {
            val i = Intent(appCtx, SoniScanActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appCtx.startActivity(i)
        } catch (t: Throwable) {
            Log.w(TAG, "connectPen: 打开扫描连接页失败 ${t.message}")
        }
    }

    override fun openPenManager() {
        // TODO(集成): 打开陪伴笔管理 / 调试页。同 connectPen，留集成层接入。
        Log.d(TAG, "openPenManager: TODO 跳转陪伴笔管理页（集成阶段接入）")
    }

    override fun pendingInfo(): PendingInfo =
        PendingInfo(penController.pendingCount(), penController.pendingFailedCount())

    override fun syncPenFiles(onPenFiles: (List<PenFile>) -> Unit) {
        // TODO(集成): 上传 URL 必须先 setUploadContext，否则 uploadPenFiles 阶段引擎会静默忽略。
        //  拉列表本身只需连接；导入勾选时才用到上下文。
        penFilesCallback = onPenFiles
        penController.requestPenFileList()
    }

    override fun uploadPenFiles(fileNames: List<String>) {
        // 引擎吃 JSON 文件名数组；上传 URL 未注入时它会直接 return（见 PenController.uploadPenFiles）。
        val json = buildString {
            append('[')
            fileNames.forEachIndexed { i, n ->
                if (i > 0) append(',')
                append('"').append(jsonEscape(n)).append('"')
            }
            append(']')
        }
        penController.uploadPenFiles(json)
    }

    override fun retryPenUploads() {
        penController.retryPenUploads()
    }

    override fun setUploadContext(cookie: String, uploadUrl: String) {
        if (cookie.isNotEmpty()) this.cookie = cookie
        if (uploadUrl.isNotEmpty()) this.uploadUrl = uploadUrl
        // 透传给引擎：陪伴笔自发录音 / 后台补传需要它。
        penController.setUploadContext(this.cookie, this.uploadUrl)
    }

    override fun onNetworkAvailable() {
        penController.onNetworkAvailable()
    }

    /**
     * 手机麦遗留补传扫描（对齐陪伴笔的扫描补传）：进程被系统杀（vivo 划后台）或上传失败时，
     * `rec_*.m4a` 会留在 filesDir/recordings 无人回收。回前台 / 进首页(refreshUploadContext)时调一次，
     * 带最新 Cookie 尽力补传，成功即删；并主动提示顾问有几段未同步（问题 #3/#5）。
     *
     * 安全护栏：① 正在手机麦录音/上传时不扫（别动正在写的文件）；② 跳过 20s 内改过的文件（可能正被写）；
     * ③ 401/拒绝=永久失败，保留文件 + 提示重新登录后停止（问题 #7）。
     */
    fun retryLeftoverPhoneMic() {
        val url = uploadUrl ?: return
        val ck = cookie ?: return
        if (ck.isEmpty()) return
        // 正在手机麦录音/上传 → 不扫，避免碰当前文件。
        val st = _state.value
        if (st is RecordingState.Recording || st is RecordingState.Uploading) return
        if (phoneMicScanning) return
        phoneMicScanning = true
        kotlin.concurrent.thread(name = "phonemic-retry", isDaemon = true) {
            try {
                val dir = java.io.File(appCtx.filesDir, "recordings")
                val files = dir.listFiles { f ->
                    // A1:容器改 AAC_ADTS(.aac)后,旧遗留 .m4a 也一并补传兜底(过渡期两种都扫)
                    f.isFile && f.name.startsWith("rec_") && (f.name.endsWith(".aac") || f.name.endsWith(".m4a"))
                } ?: return@thread
                val now = System.currentTimeMillis()
                // >1KB 且 20s 内没改过（不是正被写的当前录音）才算遗留。
                val stale = files.filter { it.length() > 1024L && now - it.lastModified() > 20_000L }
                    .sortedBy { it.lastModified() }
                if (stale.isEmpty()) return@thread
                _penEvents.tryEmit("检测到 ${stale.size} 段未同步的录音，正在自动补传…")
                var failed = 0
                for (f in stale) {
                    val durSec = runCatching {
                        val mmr = android.media.MediaMetadataRetriever()
                        mmr.setDataSource(f.absolutePath)
                        val ms = mmr.extractMetadata(
                            android.media.MediaMetadataRetriever.METADATA_KEY_DURATION,
                        )?.toLongOrNull() ?: 0L
                        mmr.release()
                        (ms / 1000L).toInt()
                    }.getOrDefault(0)
                    val upName = "rec-" + System.currentTimeMillis() +
                        (if (f.name.endsWith(".aac")) ".aac" else ".m4a")
                    val upMime = if (f.name.endsWith(".aac")) "audio/aac" else "audio/mp4"
                    // D12：文件名里嵌的占位 id（rec_<ts>_p<pid>.aac）——补传带上它回填同一占位行，
                    // 不再新建重复行、"同步中"占位也能转正。
                    val pid = Regex("_p(\\d+)\\.").find(f.name)?.groupValues?.get(1)?.toLongOrNull() ?: -1L
                    var r = Uploader.upload(f, durSec, ck, url, upName, upMime, null, pid)
                    // 手机麦补传遇 401：先用本地凭证自动重登一次，成功就用新 Cookie 立刻重传这一段。
                    if (!r.ok && r.error?.contains("登录已失效") == true) {
                        val relogged = runCatching { runBlocking { AuthManager().reAuthenticate() } }.getOrDefault(false)
                        if (relogged) {
                            val freshCk = runCatching {
                                val nm = com.airec.bledemo.data.net.NetworkModule
                                nm.cookieJar.cookieHeader(nm.BASE_URL.toHttpUrl())
                            }.getOrNull()
                            if (!freshCk.isNullOrEmpty()) {
                                cookie = freshCk
                                r = Uploader.upload(f, durSec, freshCk, url, upName, upMime, null, pid)
                            }
                        }
                    }
                    when {
                        r.ok -> f.delete()
                        !r.transientFail -> {
                            // 401/拒绝等永久失败（自动重登也没救回）：保留文件，提示后停止本轮。
                            _penEvents.tryEmit(r.error ?: "补传失败，请重新登录后重试")
                            return@thread
                        }
                        else -> failed++
                    }
                }
                if (failed > 0) {
                    _penEvents.tryEmit("还有 $failed 段录音没传成功，请保持网络畅通，回到首页会自动重试")
                } else {
                    _penListChanged.tryEmit(Unit)   // 有补传成功 → 刷新待整理
                }
            } catch (t: Throwable) {
                Log.w(TAG, "retryLeftoverPhoneMic 失败: ${t.message}")
            } finally {
                phoneMicScanning = false
            }
        }
    }

    override fun autoConnectPen(savedMac: String?) {
        if (savedMac.isNullOrEmpty()) return
        penController.autoConnect(savedMac)
    }

    override fun activate() {
        penController.activate()
    }

    // ============ 手机麦克风：起 / 停前台 Service ============

    private fun startPhoneMic() {
        val url = uploadUrl
        val intent = Intent(appCtx, PhoneMicService::class.java).apply {
            action = PhoneMicService.ACTION_START
            // PhoneMicService 在 START 时只取 upload_url，stop 时取 cookie。
            putExtra(PhoneMicService.EXTRA_UPLOAD_URL, url)
        }
        startServiceCompat(intent)
    }

    private fun stopPhoneMic() {
        val intent = Intent(appCtx, PhoneMicService::class.java).apply {
            action = PhoneMicService.ACTION_STOP
            putExtra(PhoneMicService.EXTRA_COOKIE, cookie)
            putExtra(PhoneMicService.EXTRA_UPLOAD_URL, uploadUrl)
        }
        // ★STOP 必须用普通 startService（对齐旧宿主）：service 可能根本没在跑（UI 残留态点停止），
        //   startForegroundService 拉起后它直接自杀、不调 startForeground → 系统按规杀进程
        //   （ForegroundServiceDidNotStartInTimeException，真机已踩）。
        try {
            appCtx.startService(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "stopPhoneMic startService failed: ${t.message}")
        }
    }

    private fun startServiceCompat(intent: Intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appCtx.startForegroundService(intent)
            } else {
                appCtx.startService(intent)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "startService failed: ${t.message}")
        }
    }

    // ============ Bus 状态串 → RecordingState ============

    private fun mapBusState(busState: String?, message: String?, durSec: Int, recId: Long): RecordingState =
        when (busState) {
            PhoneMicService.STATE_RECORDING ->
                RecordingState.Recording(durSec, currentSource, starting = false, message = message)

            PhoneMicService.STATE_STARTING ->
                RecordingState.Recording(durSec, currentSource, starting = true, message = message)

            PhoneMicService.STATE_UPLOADING ->
                RecordingState.Uploading(durSec, message)

            // 引擎没有独立的 paused 串（暂停经 onPenPaused 回调驱动），保留容错分支。
            "paused" ->
                RecordingState.Paused(durSec, currentSource)

            PhoneMicService.STATE_ERROR ->
                // 错误不是独立屏：落回 Idle 并携带文案给 UI 提示（如「录音笔没开机/没响应」）。
                RecordingState.Idle(errorMessage = message, lastRecordingId = -1L)

            PhoneMicService.STATE_IDLE ->
                RecordingState.Idle(lastRecordingId = recId, message = message)

            else ->
                RecordingState.Idle()
        }

    // ============ 工具 ============

    /** PenController 产出的机身文件 JSON（[{name,ra,size,dur,uploaded}]）→ [PenFile] 列表。 */
    private fun parsePenFiles(json: String?): List<PenFile> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val name = o.optString("name", "")
                    if (name.isEmpty()) continue
                    add(
                        PenFile(
                            name = name,
                            recordedAt = o.optString("ra", ""),
                            sizeBytes = o.optLong("size", 0L),
                            durationSec = o.optInt("dur", 0),
                            uploaded = o.optBoolean("uploaded", false),
                        ),
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "parsePenFiles failed: ${e.message}")
            emptyList()
        }
    }

    private fun jsonEscape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        private const val TAG = "RecordingController"

        // 「连上后自动开录」兜底超时：连上+真开录全程的上限（对齐旧宿主 4s 兜底，放宽到 8s 容握手）。
        private const val PENDING_CONNECT_TIMEOUT_MS = 8000L

        // 来源选择持久化（替代「按笔是否在线重置」，保住用户上次选择）。
        private const val PREFS_COMPANION = "companion_prefs"
        private const val KEY_LAST_SOURCE = "last_source"
    }
}
