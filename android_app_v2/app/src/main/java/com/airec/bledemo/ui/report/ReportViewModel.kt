package com.airec.bledemo.ui.report

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.airec.bledemo.data.auth.AuthManager
import com.airec.bledemo.data.model.CustomerTagsResponse
import com.airec.bledemo.data.model.Evaluation
import com.airec.bledemo.data.model.SessionDetail
import com.airec.bledemo.data.model.SessionRecording
import com.airec.bledemo.data.model.Task
import com.airec.bledemo.data.repo.ApiResult
import com.airec.bledemo.data.repo.ConsultantRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 报告屏一次性提示（toast）。Screen 消费后调用 [ReportViewModel.onToastShown] 清掉。
 */
data class ReportToast(val text: String, val danger: Boolean = false)

/**
 * 一次性跳播请求（Case 时间戳点击触发）。AudioFold 消费后 seek 到 [seconds] 并清掉。
 * [stamp] 仅用于让同一秒数的重复点击也能触发（LaunchedEffect key）。
 */
data class ReportSeek(val seconds: Int, val stamp: Long = System.currentTimeMillis())

/**
 * 报告屏整体 UI 状态。
 *
 * @param loading 首次/刷新加载中
 * @param error 加载失败文案（null = 无错）
 * @param detail 会话详情（含 11 个 PART report、recordings、evaluations）
 * @param isManager 当前用户是否店长/管理员/超管（决定「分割」是否显示；splitRecording @manager_required）
 * @param tags PART 10 动态标签（独立接口；可空，缺则退回 report.customer_tags）
 * @param segIndex 当前选中的音频段落下标（多段录音；0 起；seek/切段都基于它）
 * @param audioUrls 各段播放地址缓存（key=段下标；懒取，点开/切段时填）
 * @param audioUrlLoading 当前选中段正在取播放地址
 * @param seekToken 一次性跳播请求（Case 时间戳点击 → AudioFold 消费后 seek；含目标秒 + 段下标）
 * @param tasks 11 个分析任务（重跑弹层用；点「重跑」时拉）
 * @param tasksLoading 任务列表加载中
 * @param rerunSubmitting 重跑/补齐提交中
 * @param evaluations 老板/专家点评列表（独立 [ConsultantRepository.evaluations] 拉，提交/删除后刷新）
 * @param evalSubmitting 点评提交/删除中
 * @param reanalyzing 重新分析提交中
 * @param confirming 说话人确认/解绑提交中
 * @param toast 一次性提示
 */
data class ReportUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val detail: SessionDetail? = null,
    val isManager: Boolean = false,
    val tags: CustomerTagsResponse? = null,
    val segIndex: Int = 0,
    val audioUrls: Map<Int, String> = emptyMap(),
    val audioUrlLoading: Boolean = false,
    val seekToken: ReportSeek? = null,
    val tasks: List<Task> = emptyList(),
    val tasksLoading: Boolean = false,
    val rerunSubmitting: Boolean = false,
    val evaluations: List<Evaluation> = emptyList(),
    val evalSubmitting: Boolean = false,
    val reanalyzing: Boolean = false,
    val confirming: Boolean = false,
    val toast: ReportToast? = null,
) {
    /** 全部音频段落（多段陪伴；可能 0/1/n 段）。 */
    val recordings: List<SessionRecording>
        get() = detail?.recordings ?: emptyList()

    /** 当前选中段（越界则退回第一段）。 */
    val currentRecording: SessionRecording?
        get() = recordings.getOrNull(segIndex) ?: recordings.firstOrNull()

    /** 当前段的播放地址（已懒取到才有）。 */
    val currentAudioUrl: String?
        get() = audioUrls[segIndex]

    /** 当前段是否触发了说话人>2 警告且未确认（report.html: asr_speaker_warning && !speaker_confirmed）。 */
    val needsSpeakerConfirm: Boolean
        get() = currentRecording?.let { (it.asrSpeakerWarning ?: 0) != 0 && (it.speakerConfirmed ?: 0) == 0 } == true

    /** 主播放片段（取第一段有 id 的录音；播放地址只对有权收听者签发）。 */
    val primaryRecording: SessionRecording?
        get() = detail?.recordings?.firstOrNull()

    /** 头部标题「张陪伴师 × 刘佳佳」，缺字段优雅退化。 */
    val headerTitle: String
        get() {
            val advisor = detail?.advisor?.takeIf { it.isNotBlank() }
            val customer = detail?.customer?.takeIf { it.isNotBlank() }
            return when {
                advisor != null && customer != null -> "$advisor × $customer"
                customer != null -> customer
                advisor != null -> advisor
                else -> "陪伴分析报告"
            }
        }

    /** 副标题「陪伴全维度分析报告 · 2026-06-08」。 */
    val headerSubtitle: String
        get() {
            val date = detail?.serviceDate?.takeIf { it.isNotBlank() }
            return if (date != null) "陪伴全维度分析报告 · $date" else "陪伴全维度分析报告"
        }

    /** 完成任务数（done）。 */
    val tasksDone: Int get() = tasks.count { it.isDone }

    /** 待处理任务数（非 done）。 */
    val tasksPending: Int get() = tasks.count { !it.isDone }
}

/**
 * 分析报告（SPEC §6 / warm_2 #report）。
 *
 * 拉 [ConsultantRepository.session]（11 个 PART + recordings + evaluations），
 * PART 10 动态标签走 [ConsultantRepository.sessionCustomerTags]。
 * 原始音频地址 [ConsultantRepository.recordingUrl] 懒取（点开折叠时）。
 * 重跑弹层走 [ConsultantRepository.sessionTasks]/[rerunTask]/[fillMissingTasks]。
 *
 * 产品红线：本层沿用后端真实 key（recording/transcript），UI 对外一律「陪伴 / 逐字转写」。
 */
class ReportViewModel(
    private val sessionId: Long,
    private val repo: ConsultantRepository = ConsultantRepository(),
    private val auth: AuthManager = AuthManager(),
) : ViewModel() {

    private val _state = MutableStateFlow(ReportUiState())
    val state: StateFlow<ReportUiState> = _state.asStateFlow()

    init {
        load()
        detectRole()
    }

    /**
     * 取当前用户角色，决定「分割」是否显示。
     * splitRecording 后端 @manager_required，顾问账号调用会 403 → 仅店长/管理员/超管显示分割入口。
     * 失败默认 false（不显示，安全侧）。
     */
    private fun detectRole() {
        viewModelScope.launch {
            val me = auth.currentUser()
            val isManager = me?.let {
                it.role == "store_manager" || it.isAdminOrSuper
            } ?: false
            _state.update { it.copy(isManager = isManager) }
        }
    }

    /** 加载会话详情 + 动态标签 + 点评列表。 */
    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val r = repo.session(sessionId)) {
                is ApiResult.Success ->
                    _state.update {
                        // 多段录音：若有未确认说话人的段，自动定位到它（对齐 report.html autoOpenIfNeedsConfirm）。
                        val recs = r.data.recordings ?: emptyList()
                        val warnIdx = recs.indexOfFirst {
                            (it.asrSpeakerWarning ?: 0) != 0 && (it.speakerConfirmed ?: 0) == 0
                        }
                        it.copy(
                            loading = false,
                            detail = r.data,
                            error = r.data.error,
                            segIndex = if (warnIdx >= 0) warnIdx else it.segIndex.coerceIn(0, maxOf(0, recs.lastIndex)),
                        )
                    }
                is ApiResult.Failure ->
                    _state.update { it.copy(loading = false, error = r.message) }
            }
            // 动态标签：失败不打断主报告，仅当成功时填入
            when (val t = repo.sessionCustomerTags(sessionId)) {
                is ApiResult.Success -> _state.update { it.copy(tags = t.data) }
                is ApiResult.Failure -> Unit
            }
        }
        // 任务执行状态：进报告即拉，让顶部「任务执行状态 X/11 完成」面板直接有数（对齐 report.html 进页即 loadTaskStatus）。
        loadTasks()
        loadEvaluations()
    }

    /** 切换音频段落（多段录音；切换后懒取该段地址）。 */
    fun selectSegment(index: Int) {
        val recs = _state.value.recordings
        if (index !in recs.indices || index == _state.value.segIndex) return
        _state.update { it.copy(segIndex = index) }
        ensureAudioUrl()
    }

    /** 点开「原始音频」折叠 / 切段：拉当前段播放地址（已取过则跳过）。 */
    fun ensureAudioUrl() {
        val cur = _state.value
        val idx = cur.segIndex
        if (cur.audioUrls[idx] != null || cur.audioUrlLoading) return
        val rid = cur.currentRecording?.id ?: return
        _state.update { it.copy(audioUrlLoading = true) }
        viewModelScope.launch {
            when (val r = repo.recordingUrl(rid)) {
                is ApiResult.Success ->
                    _state.update { it.copy(audioUrlLoading = false, audioUrls = it.audioUrls + (idx to r.data)) }
                is ApiResult.Failure ->
                    _state.update {
                        it.copy(audioUrlLoading = false, toast = ReportToast(r.message, danger = true))
                    }
            }
        }
    }

    /**
     * Case 时间戳跳播：切到目标段（若与当前不同）、拉地址、发一次性 [ReportSeek]。
     * Screen 负责展开原始音频折叠并把 seekToken 传给 AudioFold 执行真正 seek。
     */
    fun seekTo(seconds: Int, segIndex: Int) {
        val recs = _state.value.recordings
        val idx = segIndex.coerceIn(0, maxOf(0, recs.lastIndex))
        if (idx != _state.value.segIndex && idx in recs.indices) {
            _state.update { it.copy(segIndex = idx) }
        }
        ensureAudioUrl()
        _state.update { it.copy(seekToken = ReportSeek(seconds)) }
    }

    /** AudioFold 消费完 seekToken 后清掉。 */
    fun onSeekConsumed() {
        _state.update { it.copy(seekToken = null) }
    }

    /**
     * 拉 11 个任务（顶部「任务执行状态」面板 + 重跑/补齐后刷新 + 15s 轮询都走它）。
     * stale-while-revalidate：已有任务时不置 loading、失败不弹 toast（轮询不打扰、不闪屏）。
     */
    fun loadTasks() {
        val hasTasks = _state.value.tasks.isNotEmpty()
        if (!hasTasks) _state.update { it.copy(tasksLoading = true) }
        viewModelScope.launch {
            when (val r = repo.sessionTasks(sessionId)) {
                is ApiResult.Success ->
                    _state.update { it.copy(tasksLoading = false, tasks = r.data) }
                is ApiResult.Failure ->
                    _state.update {
                        if (hasTasks) it.copy(tasksLoading = false)
                        else it.copy(tasksLoading = false, toast = ReportToast(r.message, danger = true))
                    }
            }
        }
    }

    /** 提交重跑所选任务（逐个调 rerunTask，全成功后提示）。 */
    fun rerunTasks(taskIds: List<String>, onDone: () -> Unit) {
        if (taskIds.isEmpty() || _state.value.rerunSubmitting) return
        _state.update { it.copy(rerunSubmitting = true) }
        viewModelScope.launch {
            var firstError: String? = null
            for (id in taskIds) {
                val r = repo.rerunTask(sessionId, id)
                if (r is ApiResult.Failure && firstError == null) firstError = r.message
            }
            _state.update {
                it.copy(
                    rerunSubmitting = false,
                    toast = if (firstError != null) {
                        ReportToast(firstError, danger = true)
                    } else {
                        ReportToast("已提交重跑 ${taskIds.joinToString("、")}，正在排队…")
                    },
                )
            }
            onDone()
            loadTasks()
        }
    }

    /** 补齐缺失任务。 */
    fun fillMissing(onDone: () -> Unit) {
        if (_state.value.rerunSubmitting) return
        _state.update { it.copy(rerunSubmitting = true) }
        viewModelScope.launch {
            val r = repo.fillMissingTasks(sessionId)
            _state.update {
                it.copy(
                    rerunSubmitting = false,
                    toast = when (r) {
                        is ApiResult.Success -> ReportToast("已提交补齐缺失任务，正在排队…")
                        is ApiResult.Failure -> ReportToast(r.message, danger = true)
                    },
                )
            }
            onDone()
            loadTasks()
        }
    }

    /**
     * 重新/补跑整个会话分析（report.html「重跑 / 重新分析」入口 → POST .../analyze）。
     * 触发后提示排队中，并刷新详情/任务，让顶部状态切到「分析进行中」。
     */
    fun reanalyze(model: String? = null) {
        if (_state.value.reanalyzing) return
        _state.update { it.copy(reanalyzing = true) }
        viewModelScope.launch {
            val r = repo.analyzeSession(sessionId, model)
            _state.update {
                it.copy(
                    reanalyzing = false,
                    toast = when (r) {
                        is ApiResult.Success -> ReportToast("已触发重新分析，正在排队…")
                        is ApiResult.Failure -> ReportToast(r.message, danger = true)
                    },
                )
            }
            if (r is ApiResult.Success) {
                load()
            }
        }
    }

    // ───────────── 老板/专家点评 ─────────────

    /** 拉点评列表（独立接口，提交/删除后刷新）。失败静默（不打断主报告）。 */
    fun loadEvaluations() {
        viewModelScope.launch {
            when (val r = repo.evaluations(sessionId)) {
                is ApiResult.Success -> _state.update { it.copy(evaluations = r.data) }
                is ApiResult.Failure -> Unit
            }
        }
    }

    /** 提交一条点评（report.html submitComment → POST .../evaluate）。 */
    fun addEvaluation(comment: String, onDone: () -> Unit = {}) {
        val text = comment.trim()
        if (text.isEmpty()) {
            _state.update { it.copy(toast = ReportToast("请填写点评内容", danger = true)) }
            return
        }
        if (_state.value.evalSubmitting) return
        _state.update { it.copy(evalSubmitting = true) }
        viewModelScope.launch {
            when (val r = repo.addEvaluation(sessionId, text)) {
                is ApiResult.Success -> {
                    _state.update { it.copy(evalSubmitting = false, toast = ReportToast("已保存点评")) }
                    onDone()
                    loadEvaluations()
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(evalSubmitting = false, toast = ReportToast(r.message, danger = true)) }
            }
        }
    }

    /** 删除一条点评（report.html deleteComment → DELETE /api/evaluation/<eid>）。 */
    fun deleteEvaluation(eid: Long) {
        if (_state.value.evalSubmitting) return
        _state.update { it.copy(evalSubmitting = true) }
        viewModelScope.launch {
            when (val r = repo.deleteEvaluation(eid)) {
                is ApiResult.Success -> {
                    // 本地先摘掉，再以服务端为准刷新
                    _state.update {
                        it.copy(evalSubmitting = false, evaluations = it.evaluations.filterNot { e -> e.id == eid })
                    }
                    loadEvaluations()
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(evalSubmitting = false, toast = ReportToast(r.message, danger = true)) }
            }
        }
    }

    // ───────────── 说话人确认 / 解绑（speakerWarnBox）─────────────

    /**
     * 说话人>2 警告的处置（report.html confirmSpeakers）。
     * action="keep" 仍然照常分析（可能触发重跑）；action="unbind" 解绑此录音段。
     * 成功后刷新详情，让警告条消失 / 段落变化。
     */
    fun confirmSpeakers(action: String) {
        val rec = _state.value.currentRecording ?: return
        if (_state.value.confirming) return
        _state.update { it.copy(confirming = true) }
        viewModelScope.launch {
            when (val r = repo.confirmSpeakers(rec.id, action)) {
                is ApiResult.Success -> {
                    _state.update {
                        it.copy(
                            confirming = false,
                            toast = ReportToast(if (action == "keep") "已确认，分析将照常进行" else "已解绑此片段"),
                        )
                    }
                    load()
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(confirming = false, toast = ReportToast(r.message, danger = true)) }
            }
        }
    }

    // ───────────── 分割切段（仅店长/管理员；@manager_required）─────────────

    /**
     * 把当前段在 [atSeconds] 秒处切成两段（report.html splitCurrentRec → POST .../split）。
     * 仅 [ReportUiState.isManager] 为真时 UI 才暴露入口；后端 @manager_required，顾问调用会 403。
     */
    fun splitCurrent(atSeconds: Double) {
        if (!_state.value.isManager) return
        val rec = _state.value.currentRecording ?: return
        if (atSeconds < 0.5) {
            _state.update { it.copy(toast = ReportToast("请把进度拖到要切分的位置再分割", danger = true)) }
            return
        }
        _state.update { it.copy(confirming = true) }
        viewModelScope.launch {
            when (val r = repo.splitRecording(rec.id, atSeconds)) {
                is ApiResult.Success -> {
                    _state.update {
                        it.copy(confirming = false, audioUrls = emptyMap(), toast = ReportToast("已分割为两段"))
                    }
                    load()
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(confirming = false, toast = ReportToast(r.message, danger = true)) }
            }
        }
    }

    /** 申请删除本次接诊的所有录音（按 session，走管理员审批）。 */
    fun requestDelete() {
        viewModelScope.launch {
            when (val r = repo.sessionDeleteRequest(sessionId)) {
                is ApiResult.Success ->
                    _state.update { it.copy(toast = ReportToast("已申请删除本次接诊录音，待审批")) }
                is ApiResult.Failure ->
                    _state.update { it.copy(toast = ReportToast(r.message, danger = true)) }
            }
        }
    }

    /** Screen 消费完 toast 后清掉，避免重组重复弹。 */
    fun onToastShown() {
        _state.update { it.copy(toast = null) }
    }
}
