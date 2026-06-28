package com.airec.bledemo.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.airec.bledemo.data.auth.AuthManager
import com.airec.bledemo.data.model.Me
import com.airec.bledemo.data.repo.ApiResult
import com.airec.bledemo.data.repo.ConsultantRepository
import com.airec.bledemo.recording.CompanionSource
import com.airec.bledemo.recording.RecordingController
import com.airec.bledemo.recording.RecordingState
import android.os.SystemClock
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 陪伴首页 ViewModel（warm_2 #home 的状态来源）。
 *
 * 三路数据：
 *  1. **问候 / 陪伴师身份**：[AuthManager.currentUser] → /api/me 的 advisor_name + store。
 *  2. **陪伴笔绑定 / 待传计数**：[ConsultantRepository.penBinding] + [ConsultantRepository.pending]
 *     给「陪伴笔状态卡」「待整理入口副标题」兜底（与笔本地状态互补）。
 *  3. **实时陪伴状态机 / 笔在线 / 待传 / 失败 / 进度**：来自 [RecordingController] 的 StateFlow
 *     （进行中呼吸态、计时、同步中、同步失败 badge 均以引擎为准）。
 *
 * 红线：对外文案全走「陪伴」系；本类只在内部沿用后端 key（pen/recording）。
 *
 * 取实例：Composable 里 `viewModel()` 默认无参构造即可（repo/auth 取 NetworkModule 默认）；
 * [RecordingController] 由【整合期】接线注入（见 [attachController]），未注入前 UI 用安全默认态、不崩。
 */
class HomeViewModel(
    private val repo: ConsultantRepository = ConsultantRepository(),
    private val auth: AuthManager = AuthManager(),
) : ViewModel() {

    // ───────────────────────── 头部问候 / 身份 ─────────────────────────

    private val _header = MutableStateFlow(HomeHeader())
    val header: StateFlow<HomeHeader> = _header.asStateFlow()

    // ───────────────────────── 陪伴笔绑定 / 待传（后端兜底） ─────────────────────────

    /** 后端记录的待整理（未绑定）片段数，用于「未选择顾客的陪伴」入口副标题。 */
    private val _pendingOnServer = MutableStateFlow(0)
    val pendingOnServer: StateFlow<Int> = _pendingOnServer.asStateFlow()

    /** 今日接诊客人列表（含每位的分析状态/录音数），算入口副标题统计用。 */
    private val _todayReception =
        MutableStateFlow<List<com.airec.bledemo.data.model.TodayReception>>(emptyList())

    /** 「今天的接诊与待整理」入口副标题的 4 个实时数字：待绑定 / 待分析 / 报告 / 客人数。 */
    val receptionStats: StateFlow<ReceptionStats> =
        combine(_pendingOnServer, _todayReception) { pending, items ->
            ReceptionStats(
                pendingBind = pending,
                waitingAnalysis = items.count { (it.recordingCount ?: 0) > 0 && it.analysisStatus != "done" },
                reportsDone = items.count { it.analysisStatus == "done" },
                customers = items.size,
                loaded = true,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReceptionStats())

    // ───────────────────────── 提醒未读计数（顶部铃铛红点 badge） ─────────────────────────

    /**
     * 顶部铃铛红点上的未读提醒数（对齐 web loadReminders 的 reminderDot）。
     * 计数口径：店长 = 升级项数 + 个人提醒数；顾问 = 个人提醒数（personal_count 缺省退回 count）。
     * 0 时不显示红点；>99 由 UI 显示「99+」。
     */
    // 红点数读共享单一真相（提醒列表也写它）→ 列表清空/处理后红点同步，不再各拉各的对不上。
    val reminderCount: StateFlow<Int> = com.airec.bledemo.ui.reminders.ReminderBadge.count

    /** 当前用户是否店长（决定铃铛 badge 是否把升级项计入；对齐 web 的 IS_MANAGER）。null=尚未拉到 /api/me。 */
    private var isManager: Boolean? = null

    /** 是否已绑定陪伴笔 SN（未绑定时同步入口仍可点，但提示先连接）。 */
    private val _penBoundSn = MutableStateFlow<String?>(null)
    val penBoundSn: StateFlow<String?> = _penBoundSn.asStateFlow()

    // ───────────────────────── 当前选中的陪伴来源（手机/陪伴笔分段） ─────────────────────────

    private val _source = MutableStateFlow(CompanionSource.Pen)
    val source: StateFlow<CompanionSource> = _source.asStateFlow()

    // ───────────────────────── 临时提示（错误/成功 toast 文案，消费后清空） ─────────────────────────

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    // 录完一段后待提示绑定的 recordingId（>0 时 HomeScreen 弹「现在绑定顾客」对话框；可在上传完成前绑定）。
    private val _pendingBindRecId = MutableStateFlow<Long?>(null)
    val pendingBindRecId: StateFlow<Long?> = _pendingBindRecId.asStateFlow()

    /** 用户点「现在绑定」→ 清提示（上层负责导航到绑定页）。 */
    fun consumeBindPrompt() { _pendingBindRecId.value = null }

    /** 用户点「稍后」→ 清提示并留一条 Toast 兜底（顾问总忘绑定）。 */
    fun dismissBindPromptLater() {
        _pendingBindRecId.value = null
        _toast.value = SAVED_BIND_HINT
    }

    fun consumeToast() {
        _toast.value = null
    }

    // ───────────────────────── 录音引擎接线（整合期注入） ─────────────────────────

    /**
     * 录音/陪伴引擎控制器。
     *
     * TODO(整合期接线): 由集成层在 MainActivity/Application 构造单例 [com.airec.bledemo.recording.RecordingControllerImpl]
     *  后通过 [attachController] 注入（或换成 DI/SavedStateHandle 拿）。注入前所有调用都是安全 no-op，
     *  UI 用 [defaultRecordingState] / penConnected=false / 计数=0 兜底，保证编译与预览不崩。
     */
    private var controller: RecordingController? = null

    /** 已就"完成"提示过的最近片段 id（防止每次回首页重弹/重新强制跳绑定）。 */
    private var lastFinishedId = -1L

    /** 上次见到的失败段数（用于检测「新增失败」弹一次提示）；-1 = 尚未首帧（首帧只记不弹）。 */
    private var lastFailedSeen = -1

    /** 进首页是否已首拉过（切回 tab 不再重复联网，消除切换卡顿；手动 refresh 仍可强拉）。 */
    private var everLoaded = false

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle())
    private val _penConnected = MutableStateFlow(false)
    private val _livePending = MutableStateFlow(0)
    private val _failed = MutableStateFlow(0)
    private val _progress = MutableStateFlow(0)
    private val _penBattery = MutableStateFlow<com.airec.bledemo.recording.PenBattery?>(null)

    // 陪伴计时：引擎不按秒推时长（手机麦/笔都靠墙钟自走，引擎 durSec 只在重连时用于"只向前对齐"）。
    // 故 UI 计时由本地墙钟驱动（对齐网页端 appStartTimer：进行中每 250ms 走一秒，结束清零）。
    private val _elapsedSec = MutableStateFlow(0)
    private var timerJob: Job? = null
    private var ticking = false

    /** 圆钮/计时/状态文案/同步 badge 的合成 UI 状态。 */
    val companion: StateFlow<CompanionUiState> =
        combine(
            _state,
            _penConnected,
            _livePending,
            _failed,
            _progress,
        ) { st, penOn, pending, failed, progress ->
            CompanionUiState(
                state = st,
                penConnected = penOn,
                pendingCount = pending,
                failedCount = failed,
                progressPercent = progress,
            )
        }.combine(_elapsedSec) { base, elapsed ->
            base.copy(elapsedSec = elapsed)
        }.combine(_penBattery) { base, batt ->
            base.copy(penBattery = batt)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = CompanionUiState(),
        )

    /**
     * 整合期把真实 [RecordingController] 接进来：转发它的 5 条 StateFlow 到本 VM。
     * 幂等；可在 Activity onResume 调（控制器内部 attach/detach 自管监听）。
     */
    fun attachController(rc: RecordingController) {
        // 幂等：底栏切走再切回会让 HomeScreen 重入组合、重复调本方法；接同一个控制器就别重复 collect。
        if (controller === rc) return
        controller = rc
        rc.attach()
        viewModelScope.launch {
            rc.state.collect { st ->
                _state.value = st
                driveTimer(st)
                if (st is RecordingState.Idle) {
                    // 陪伴结束、引擎回传新片段 id → 一次性提示「可去待整理绑定」+ 刷新待整理计数。
                    // 绑定不是串行步骤：片段已落到待整理，随时可绑；故这里只提示，不强制跳转
                    // （旧实现按"状态里带 id"派生跳转，会导致每次回首页都被弹回绑定页 = 卡死）。
                    if (st.lastRecordingId > 0 && st.lastRecordingId != lastFinishedId) {
                        lastFinishedId = st.lastRecordingId
                        // 绑定提示改由 bindPrompt 事件统一弹对话框（含手机麦/陪伴笔）；这里只刷新待整理计数。
                        loadPending()
                    }
                    // 注：错误/连接/归属等文案不再从 Idle.errorMessage 弹（会被 StateFlow 去重或随后的 Idle 覆盖吞掉），
                    //     改由下方 penEvents 一次性事件流可靠弹出。
                }
            }
        }
        viewModelScope.launch { rc.penConnected.collect { _penConnected.value = it } }
        viewModelScope.launch { rc.penBattery.collect { _penBattery.value = it } }
        viewModelScope.launch { rc.pendingCount.collect { _livePending.value = it } }
        viewModelScope.launch {
            rc.failedCount.collect { failed ->
                // 失败段数新增 → 主动提示一次（对齐旧宿主：失败必须大声说，别让用户以为存上了）。
                // 只在增量时弹；attach 同步快照（lastFailedSeen=-1 起步）首帧不误弹。
                if (lastFailedSeen >= 0 && failed > lastFailedSeen) {
                    _toast.value = "有 $failed 段没保存成功（陪伴笔可能没存上），请检查后重录"
                }
                lastFailedSeen = failed
                _failed.value = failed
            }
        }
        viewModelScope.launch { rc.progressPercent.collect { _progress.value = it } }
        // 一次性陪伴笔提示（连接成功/错误/归属拒绝/开机自录被关）→ 直接弹 Toast（不被 _state 去重吞掉）。
        viewModelScope.launch { rc.penEvents.collect { _toast.value = it } }
        // 后台机身片段落地（补传成功/建占位）→ 刷新首页「待整理」计数（对齐旧宿主 onPenUploaded→loadPending）。
        viewModelScope.launch { rc.penListChanged.collect { loadPending() } }
        // 录完一段（手机麦/陪伴笔）拿到可绑定 recordingId → 弹「现在绑定顾客」对话框（不必等上传完成）。
        viewModelScope.launch {
            rc.bindPrompt.collect { rid ->
                if (rid > 0) {
                    _pendingBindRecId.value = rid
                    loadPending()
                }
            }
        }
        // 来源默认：优先用持久化的上次选择；没有记录时才退回「按笔是否在线」（与 warm_2 默认选陪伴笔一致）。
        _source.value = rc.lastSource()
            ?: if (rc.isPenConnected()) CompanionSource.Pen else CompanionSource.Phone
    }

    // ───────────────────────── 拉取（进首页/下拉刷新调用） ─────────────────────────

    fun refresh() {
        loadHeader()
        loadPenBinding()
        loadPending()
        loadTodayReception()
        loadReminderCount()
    }

    /** 进首页调用：仅首次联网拉取；切回 tab（VM 仍在）时直接用已缓存状态，不再转圈联网。 */
    fun refreshOnEnter() {
        if (everLoaded) return
        everLoaded = true
        refresh()
    }

    /**
     * 回前台 / 轮询时只刷新提醒未读计数（对齐 web 的 setInterval(loadReminders,120s) + pageshow/visibilitychange）。
     * 不重拉问候/笔绑定/待整理——那些进首页首拉一次即可，避免回前台整屏抖动。
     */
    fun refreshReminders() {
        loadReminderCount()
    }

    private fun loadHeader() {
        viewModelScope.launch {
            val me: Me? = auth.currentUser()
            if (me != null) {
                isManager = me.role == "store_manager"
                _header.value = HomeHeader(
                    advisorName = me.advisorName?.takeIf { it.isNotBlank() }
                        ?: me.username?.takeIf { it.isNotBlank() },
                    storeName = null, // /api/me 只给 store_id；店名留空，由 reception 页补全（不臆造）
                    loaded = true,
                )
            }
        }
    }

    /**
     * 拉一次 reminders 取未读计数 → 顶部铃铛红点。
     * 复用 [ConsultantRepository.reminders]（不新增 api/repo）；只取计数字段，不持有列表。
     * 注意：店长拉 reminders 会被后端把升级项置已读——这与 web 行为一致（loadReminders 也会触发已读）。
     */
    private fun loadReminderCount() {
        viewModelScope.launch {
            // /api/me 还没回来时先确定角色，保证店长首帧就把升级项计入（不依赖 loadHeader 的时序）。
            if (isManager == null) {
                isManager = auth.currentUser()?.role == "store_manager"
            }
            when (val r = repo.reminders()) {
                is ApiResult.Success -> {
                    val d = r.data
                    val personal = d.personalCount ?: d.count ?: 0
                    val escalation = d.escalationCount ?: 0
                    com.airec.bledemo.ui.reminders.ReminderBadge.set(
                        if (isManager == true) personal + escalation else personal
                    )
                    // 前台拉到提醒时，对「新出现的」弹本地系统通知（已见集合去重，不会重复弹）
                    com.airec.bledemo.notify.ReminderNotifier.notifyNew(d.items.orEmpty())
                }
                is ApiResult.Failure -> Unit // 静默：取不到不打断首页（红点保持上次值）
            }
        }
    }

    private fun loadPenBinding() {
        viewModelScope.launch {
            when (val r = repo.penBinding()) {
                is ApiResult.Success -> _penBoundSn.value = r.data.penSn?.takeIf { it.isNotBlank() }
                is ApiResult.Failure -> Unit // 静默：笔绑定取不到不打断首页
            }
        }
    }

    private fun loadPending() {
        viewModelScope.launch {
            when (val r = repo.pending()) {
                is ApiResult.Success -> _pendingOnServer.value = r.data.size
                is ApiResult.Failure -> Unit
            }
        }
    }

    /** 拉今日接诊（客人数 / 已绑未分析 / 已出报告），供首页入口副标题统计。 */
    private fun loadTodayReception() {
        viewModelScope.launch {
            when (val r = repo.todayReception()) {
                is ApiResult.Success -> _todayReception.value = r.data.items ?: emptyList()
                is ApiResult.Failure -> Unit // 静默，不打断首页
            }
        }
    }

    // ───────────────────────── 交互 ─────────────────────────

    /**
     * 切换陪伴来源（手机/陪伴笔分段）。选陪伴笔但未连接 → 提示并尝试连接。
     * 进行中/暂停/保存中/唤醒中禁止切来源（对齐 web: if(__appRecording||uploading||starting)return）。
     * 选定后把来源持久化（下次进首页用它作默认，不再「按笔是否在线重置」）。
     */
    fun pickSource(src: CompanionSource) {
        // 状态守卫：陪伴进行中 / 暂停 / 保存中（含 starting 唤醒/连接中）一律不许换来源。
        val st = companion.value.state
        if (st is RecordingState.Recording || st is RecordingState.Paused || st is RecordingState.Uploading) {
            return
        }
        _source.value = src
        controller?.saveSource(src)
        if (src == CompanionSource.Pen && controller?.isPenConnected() == false) {
            _toast.value = "陪伴笔尚未连接，正在尝试连接…"
            controller?.connectPen()
        }
    }

    /**
     * 点击大圆钮，按状态精确分流（对齐网页端 appToggleRecord）：
     *  - Uploading（保存中）→ **忽略**：保存中点击不得误开新一段（按钮此时也应呈 disabled）。
     *  - Recording / Paused（含 starting「正在唤醒/连接」）→ stopCompanion：进行中=结束，唤醒中=取消。
     *  - Idle → startCompanion：按当前来源开启。
     * 实际是否进入进行中态由引擎回调驱动（笔需先确认真开录），UI 不自行假设。
     */
    fun toggleCompanion() {
        val rc = controller
        if (rc == null) {
            // TODO(整合期接线): 控制器未注入。注入后此分支不会走到。
            _toast.value = "陪伴功能初始化中，请稍候再试"
            return
        }
        when (companion.value.state) {
            is RecordingState.Uploading -> Unit // 保存中：忽略（web: if(uploading)return）
            is RecordingState.Recording, is RecordingState.Paused -> rc.stopCompanion()
            is RecordingState.Idle -> rc.startCompanion(_source.value)
        }
    }

    /** 「从陪伴笔同步」入口的前置校验文案（真正拉列表/弹 sheet 由 onOpenPenSync 回调上层处理）。 */
    fun onPenSyncClicked(): Boolean {
        if (controller?.isPenConnected() == false && _penBoundSn.value == null) {
            _toast.value = "请先连接陪伴笔再同步机身片段"
            return false
        }
        return true
    }

    /** 同步失败 badge 点击 → 立刻重推待补传队列。 */
    fun retryUploads() {
        controller?.retryPenUploads()
        _toast.value = "已重新尝试同步"
    }

    // ───────────────────────── 陪伴计时（墙钟自走，对齐网页端 appStartTimer） ─────────────────────────

    /** 据引擎状态驱动计时：进行中→自走；启动中/空闲→清零停；暂停/上传→冻结当前值。 */
    private fun driveTimer(st: RecordingState) {
        when (st) {
            is RecordingState.Recording -> {
                if (st.starting) {
                    stopTicking(reset = true)            // 「正在唤醒陪伴笔…」阶段不计时
                } else if (!st.timeSynced) {
                    // 重连到已在录的笔、真实时长还没同步：不跑从 0 往上跳的假计时；UI 改显「正在同步小伙伴时间…」。
                    stopTicking(reset = true)
                } else {
                    // 只向前对齐：引擎报的已录秒数明显更大时（重连到已在录的笔）跳上去，否则继续自走。
                    if (!ticking || st.durationSec > _elapsedSec.value + 2) {
                        startTicking(seedSec = maxOf(_elapsedSec.value, st.durationSec))
                    }
                }
            }
            is RecordingState.Paused, is RecordingState.Uploading -> stopTicking(reset = false)
            is RecordingState.Idle -> stopTicking(reset = true)
        }
    }

    private fun startTicking(seedSec: Int) {
        timerJob?.cancel()
        ticking = true
        val startMs = SystemClock.elapsedRealtime()
        _elapsedSec.value = seedSec
        timerJob = viewModelScope.launch {
            while (true) {
                _elapsedSec.value = seedSec + ((SystemClock.elapsedRealtime() - startMs) / 1000).toInt()
                delay(250)
            }
        }
    }

    private fun stopTicking(reset: Boolean) {
        timerJob?.cancel()
        timerJob = null
        ticking = false
        if (reset) _elapsedSec.value = 0
    }

    override fun onCleared() {
        timerJob?.cancel()
        controller?.detach()
        super.onCleared()
    }

    private companion object {
        // 录完一段（手机麦 / 陪伴笔）统一提示，样式一致：原生 Toast，提示去「待整理」绑定。
        const val SAVED_BIND_HINT = "已保存 · 可在「待整理」绑定顾客"
    }
}

/** 头部问候 / 身份。 */
data class HomeHeader(
    val advisorName: String? = null,
    val storeName: String? = null,
    val loaded: Boolean = false,
)

/** 「今天的接诊与待整理」入口副标题统计。 */
data class ReceptionStats(
    val pendingBind: Int = 0,     // 待绑定的陪伴（未绑定片段数）
    val waitingAnalysis: Int = 0, // 已绑录音但还没出报告的客人数
    val reportsDone: Int = 0,     // 已生成报告（analysis_status=done）的客人数
    val customers: Int = 0,       // 今日接诊客人总数
    val loaded: Boolean = false,
)

/** 圆钮舞台 + 来源 + badge 的合成态。 */
data class CompanionUiState(
    val state: RecordingState = RecordingState.Idle(),
    val penConnected: Boolean = false,
    val pendingCount: Int = 0,
    val failedCount: Int = 0,
    val progressPercent: Int = 0,
    /** 显示用墙钟计时（秒）；由 ViewModel 在进行中每秒自走，结束清零。 */
    val elapsedSec: Int = 0,
    /** 陪伴笔电量（连接后由 cmd=6 上报；null=未知）。 */
    val penBattery: com.airec.bledemo.recording.PenBattery? = null,
) {
    /** 唤醒/连接中（已发开始命令、等陪伴笔确认真开录）：视觉=「正在唤醒」可取消，**不算已在录**。 */
    val starting: Boolean get() = (state as? RecordingState.Recording)?.starting == true

    /** 重连到已在录的笔、真实时长还没同步好：计时位置应显示「正在同步小伙伴时间…」而非假计时。
     *  仅陪伴笔会有此态——手机麦时长永远本地真实自走，不进入「同步中」。 */
    val syncingTime: Boolean get() =
        (state as? RecordingState.Recording)?.let {
            it.source == CompanionSource.Pen && !it.starting && !it.timeSynced
        } == true

    /**
     * 已在录（呼吸态 + 停止方块 + 红点）：仅真正进行中 / 暂停才算；
     * starting 阶段排除在外（圆钮此时呈「正在唤醒」而非「已在录」，避免笔还没确认就显示录音中）。
     */
    val live: Boolean get() =
        (state is RecordingState.Recording && !starting) || state is RecordingState.Paused

    val uploading: Boolean get() = state is RecordingState.Uploading
}
