package com.airec.bledemo.ui.session

import android.media.MediaPlayer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.airec.bledemo.data.model.Customer
import com.airec.bledemo.data.model.PreviewRecording
import com.airec.bledemo.data.model.SessionPreview
import com.airec.bledemo.data.model.TaskProgress
import com.airec.bledemo.data.repo.ApiResult
import com.airec.bledemo.data.repo.ConsultantRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 会话预览 + 开始分析（SPEC §4.6 / warm_2 #m-pkg「接诊包」）的 ViewModel。
 *
 * 关键映射：本屏路由只带 [sessionId]，但后端 `session/preview` / `start_analysis`
 * 以 customer_id + date 为键。因此先用 [ConsultantRepository.session] 把
 * sessionId 解析成 customerId + serviceDate，再驱动 preview / 各项操作。
 *
 * 状态机（来自 sessions.analysis_status + locked）：
 *  - 待开始（pending/null，未锁）：可加入/移除/换绑/退回片段、可「确认并开始分析」。
 *  - 分析中（queued/running，已锁）：只读，显示进度条 + 已等时长 + 正在跑任务名，可「取消分析」/「查看进度」。
 *  - 已完成（done）：可「查看报告」。
 *  - 失败（failed/cancelled）：可重新「开始分析」（后端幂等，仅补跑失败/缺失）。
 *  - 录音有变更（outdated）：单独成态，提示需重跑，可「重新分析」（不并进待开始）。
 *
 * 全程只调 ConsultantRepository 现有方法：session / sessionPreview / sessionPreviewRemove /
 * bind / directRebind / unbind / confirmSpeakers / rebindCandidates / startAnalysis /
 * cancelAnalysis。不新增 api/repo/model。
 */
class SessionPreviewViewModel(
    private val repo: ConsultantRepository = ConsultantRepository(),
) : ViewModel() {

    /** 会话总状态（驱动按钮区与锁定态）。Outdated=录音有变更需重分析，单独成态。 */
    enum class AnalysisPhase { Idle, Running, Done, Failed, Outdated }

    /** 行内操作类型（同一行同一时刻只允许一个进行中，用于按钮转圈/禁点）。 */
    enum class RowOp { Remove, Bind, Rebind, Unbind, Confirm }

    /** 换绑弹层状态（候选搜索 + 提交）。 */
    data class RebindSheet(
        /** 目标录音 id（null=未打开）。 */
        val recordingId: Long? = null,
        val recordedAt: String? = null,
        val durationLabel: String? = null,
        val query: String = "",
        val searching: Boolean = false,
        val candidates: List<Customer> = emptyList(),
        val submitting: Boolean = false,
        val error: String? = null,
    ) {
        val visible: Boolean get() = recordingId != null
    }

    /** 退回未归档弹层状态（理由必填）。 */
    data class UnbindSheet(
        val recordingId: Long? = null,
        val recordedAt: String? = null,
        val durationLabel: String? = null,
        val reason: String = "",
        val submitting: Boolean = false,
        val error: String? = null,
    ) {
        val visible: Boolean get() = recordingId != null
    }

    data class UiState(
        val loading: Boolean = true,
        val loadError: String? = null,
        /** 解析出的会话 id（路由传入；首次成功 start 后也用它跳报告）。 */
        val sessionId: Long = -1L,
        val customerId: Long? = null,
        val customerName: String = "",
        val serviceDate: String = "",
        val locked: Boolean = false,
        val analysisStatus: String? = null,
        val phase: AnalysisPhase = AnalysisPhase.Idle,
        /** 11 任务级进度（分桶计数 + 名称 + 已等时长 + 后端进度文案）。 */
        val progress: TaskProgress? = null,
        /** 已绑定（本次将分析）的片段。 */
        val bound: List<PreviewRecording> = emptyList(),
        /** 本人同日未绑定、可加入的片段（「加入本接诊包」直接绑到本会话顾客）。 */
        val unbound: List<PreviewRecording> = emptyList(),
        /** 行内操作进行中的 recordingId + 操作类型。 */
        val rowOpId: Long? = null,
        val rowOp: RowOp? = null,
        /** 提交中（开始/取消分析），按钮转圈、禁重复点。 */
        val submitting: Boolean = false,
        /** 一次性提示（toast）。 */
        val toast: String? = null,
        /** start 成功 → 该值非空时，UI 导航到报告。 */
        val navigateToReport: Long? = null,
        /** 正在播放的 recordingId（null=无播放）。 */
        val playingId: Long? = null,
        /** 换绑弹层。 */
        val rebindSheet: RebindSheet = RebindSheet(),
        /** 退回未归档弹层。 */
        val unbindSheet: UnbindSheet = UnbindSheet(),
    ) {
        /** 是否可改动接诊包片段（加入/移除/换绑/退回）：未锁 + 非分析中。 */
        val editable: Boolean get() = !locked && phase != AnalysisPhase.Running
        val canStart: Boolean get() = editable && bound.isNotEmpty()
        val canCancel: Boolean get() = phase == AnalysisPhase.Running

        /** 11 任务进度的派生快捷取值（缺省兜底）。 */
        val progressTotal: Int get() = progress?.total ?: 11
        val progressDone: Int get() = progress?.done ?: 0

        /**
         * 开始前的本地预检：ASR 全完成、但存在「说话人警告未确认」的片段。
         * 对齐后端 start_analysis 被前置条件挡住时的 reason=speaker_unconfirmed，
         * 让用户先在卡片上「确认说话人」而非撞一个看不懂的失败。
         */
        val speakerUnconfirmed: List<PreviewRecording>
            get() = bound.filter { it.asrSpeakerWarning == 1 && (it.speakerConfirmed ?: 0) == 0 }

        /** 是否所有已绑定片段 ASR 都已 done（用于点 5 的原因判断）。 */
        val allAsrDone: Boolean
            get() = bound.isNotEmpty() && bound.all { it.asrStatus == "done" }

        /**
         * 触发分析前若仍有阻塞，返回原因码（对齐后端 reason）。null=可触发。
         *  - no_recording：接诊包内无片段
         *  - speaker_unconfirmed：有说话人警告未确认
         *  - asr_not_done：尚有片段在识别中/失败
         */
        val blockReason: String?
            get() = when {
                bound.isEmpty() -> "no_recording"
                speakerUnconfirmed.isNotEmpty() -> "speaker_unconfirmed"
                !allAsrDone -> "asr_not_done"
                else -> null
            }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var player: MediaPlayer? = null
    private var pollJob: Job? = null
    private var candSearchJob: Job? = null

    fun load(sessionId: Long) {
        _state.update { it.copy(sessionId = sessionId) }
        refresh(initial = true)
    }

    /** 重新拉取：sessionId → session 详情（取 customerId/date）→ preview。 */
    fun refresh(initial: Boolean = false) {
        val sid = _state.value.sessionId
        if (sid <= 0L) {
            _state.update { it.copy(loading = false, loadError = "无效的会话") }
            return
        }
        viewModelScope.launch {
            if (initial) _state.update { it.copy(loading = true, loadError = null) }
            // 1) 解析 customerId + serviceDate（已知则跳过 session 调用）
            var customerId = _state.value.customerId
            var date = _state.value.serviceDate.ifBlank { null }
            var name = _state.value.customerName
            if (customerId == null || date == null) {
                when (val r = repo.session(sid)) {
                    is ApiResult.Success -> {
                        customerId = r.data.customerId
                        date = r.data.serviceDate
                        name = r.data.customer ?: name
                    }
                    is ApiResult.Failure -> {
                        _state.update { it.copy(loading = false, loadError = r.message) }
                        return@launch
                    }
                }
            }
            if (customerId == null) {
                _state.update { it.copy(loading = false, loadError = "未找到顾客信息") }
                return@launch
            }
            // 2) 拉接诊包预览
            when (val r = repo.sessionPreview(customerId, date)) {
                is ApiResult.Success -> applyPreview(r.data, sid, customerId, date, name)
                is ApiResult.Failure ->
                    _state.update { it.copy(loading = false, loadError = r.message) }
            }
        }
    }

    private fun applyPreview(
        p: SessionPreview,
        sid: Long,
        customerId: Long,
        date: String?,
        fallbackName: String,
    ) {
        val status = p.analysisStatus
        val phase = phaseOf(status)
        _state.update {
            it.copy(
                loading = false,
                loadError = null,
                sessionId = p.sessionId ?: sid,
                customerId = p.customer?.cid ?: customerId,
                customerName = p.customer?.name ?: fallbackName,
                serviceDate = p.serviceDate ?: date.orEmpty(),
                locked = p.locked == true,
                analysisStatus = status,
                phase = phase,
                progress = p.taskProgress,
                bound = p.bound.orEmpty(),
                unbound = p.unbound.orEmpty(),
            )
        }
        // 分析中时轮询刷新进度
        if (phase == AnalysisPhase.Running) startPolling() else stopPolling()
    }

    private fun phaseOf(status: String?): AnalysisPhase = when (status) {
        "running", "queued" -> AnalysisPhase.Running
        "done" -> AnalysisPhase.Done
        "failed", "cancelled" -> AnalysisPhase.Failed
        "outdated" -> AnalysisPhase.Outdated
        else -> AnalysisPhase.Idle
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (true) {
                delay(5000)
                if (_state.value.phase != AnalysisPhase.Running) break
                refresh(initial = false)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    // ───────────── 片段操作（加入 / 移除 / 换绑 / 退回 / 确认说话人）─────────────

    private fun setRowOp(rid: Long?, op: RowOp?) =
        _state.update { it.copy(rowOpId = rid, rowOp = op) }

    private fun rowBusy(): Boolean = _state.value.rowOpId != null

    /** 把某段片段移出接诊包（session_id 置空），成功后刷新。 */
    fun removeFromPackage(recordingId: Long) {
        if (!_state.value.editable || rowBusy()) return
        viewModelScope.launch {
            setRowOp(recordingId, RowOp.Remove)
            when (val r = repo.sessionPreviewRemove(recordingId)) {
                is ApiResult.Success -> {
                    _state.update {
                        it.copy(
                            toast = "已移出本次分析",
                            bound = it.bound.filterNot { rec -> rec.id == recordingId },
                        )
                    }
                    setRowOp(null, null)
                    refresh(initial = false)
                }
                is ApiResult.Failure -> {
                    setRowOp(null, null)
                    _state.update { it.copy(toast = r.message) }
                }
            }
        }
    }

    /** 未绑定片段「加入本接诊包」：直接绑到本会话顾客（repo.bind），成功后刷新。 */
    fun addToPackage(recordingId: Long) {
        val cid = _state.value.customerId ?: return
        if (!_state.value.editable || rowBusy()) return
        viewModelScope.launch {
            setRowOp(recordingId, RowOp.Bind)
            when (val r = repo.bind(recordingId, cid)) {
                is ApiResult.Success -> {
                    setRowOp(null, null)
                    _state.update { it.copy(toast = "已加入本接诊包") }
                    refresh(initial = false)
                }
                is ApiResult.Failure -> {
                    setRowOp(null, null)
                    _state.update { it.copy(toast = r.message) }
                }
            }
        }
    }

    /**
     * 确认说话人（仅本人，action=keep 视警告为误判照常分析）。
     * 对齐 web needsConfirm 的「仍然分析」，让 speaker_unconfirmed 阻塞可就地解除。
     */
    fun confirmSpeakers(recordingId: Long) {
        if (!_state.value.editable || rowBusy()) return
        viewModelScope.launch {
            setRowOp(recordingId, RowOp.Confirm)
            when (val r = repo.confirmSpeakers(recordingId, "keep")) {
                is ApiResult.Success -> {
                    setRowOp(null, null)
                    _state.update { it.copy(toast = "已确认说话人，可开始分析") }
                    refresh(initial = false)
                }
                is ApiResult.Failure -> {
                    setRowOp(null, null)
                    _state.update { it.copy(toast = r.message) }
                }
            }
        }
    }

    // ── 换绑弹层 ──

    fun openRebind(rec: PreviewRecording) {
        if (!_state.value.editable) return
        _state.update {
            it.copy(
                rebindSheet = RebindSheet(
                    recordingId = rec.id,
                    recordedAt = rec.recordedAt,
                    durationLabel = rec.durationLabel,
                ),
            )
        }
        loadRebindCandidates("")
    }

    fun closeRebind() {
        candSearchJob?.cancel()
        _state.update { it.copy(rebindSheet = RebindSheet()) }
    }

    fun onRebindQueryChange(q: String) {
        _state.update { it.copy(rebindSheet = it.rebindSheet.copy(query = q, error = null)) }
        candSearchJob?.cancel()
        candSearchJob = viewModelScope.launch {
            delay(220)
            loadRebindCandidates(q)
        }
    }

    private fun loadRebindCandidates(q: String) {
        val rid = _state.value.rebindSheet.recordingId ?: return
        viewModelScope.launch {
            _state.update { it.copy(rebindSheet = it.rebindSheet.copy(searching = true)) }
            when (val r = repo.rebindCandidates(rid = rid, q = q.ifBlank { null })) {
                is ApiResult.Success -> {
                    // 不展示该录音当前所属顾客本人（对齐 web filter）
                    val curCid = _state.value.customerId
                    val items = (r.data.items ?: emptyList()).filter { it.cid != curCid }
                    _state.update {
                        // 防竞态：弹层已换目标则丢弃
                        if (it.rebindSheet.recordingId != rid) it
                        else it.copy(rebindSheet = it.rebindSheet.copy(searching = false, candidates = items))
                    }
                }
                is ApiResult.Failure ->
                    _state.update {
                        if (it.rebindSheet.recordingId != rid) it
                        else it.copy(rebindSheet = it.rebindSheet.copy(searching = false, error = r.message))
                    }
            }
        }
    }

    /** 提交换绑：只移动这一段到目标顾客（repo.directRebind）。成功后切到目标顾客并刷新。 */
    fun submitRebind(target: Customer) {
        val s = _state.value
        val rid = s.rebindSheet.recordingId ?: return
        val toCid = target.cid ?: return
        if (s.rebindSheet.submitting) return
        viewModelScope.launch {
            _state.update { it.copy(rebindSheet = it.rebindSheet.copy(submitting = true, error = null)) }
            // reason 留空 → 后端记「(顾问直接换绑)」审计；搜索词不能当理由用
            when (val r = repo.directRebind(rid, toCid, reason = null)) {
                is ApiResult.Success -> {
                    // 换绑后该段离开本接诊包；若后端给了新接诊包则跟随切换顾客
                    val newCid = r.data.customerId ?: toCid
                    _state.update {
                        it.copy(
                            rebindSheet = RebindSheet(),
                            customerId = newCid,
                            toast = "已换绑这一段",
                        )
                    }
                    refresh(initial = false)
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(rebindSheet = it.rebindSheet.copy(submitting = false, error = r.message)) }
            }
        }
    }

    // ── 退回未归档弹层 ──

    fun openUnbind(rec: PreviewRecording) {
        if (!_state.value.editable) return
        _state.update {
            it.copy(
                unbindSheet = UnbindSheet(
                    recordingId = rec.id,
                    recordedAt = rec.recordedAt,
                    durationLabel = rec.durationLabel,
                ),
            )
        }
    }

    fun closeUnbind() = _state.update { it.copy(unbindSheet = UnbindSheet()) }

    fun onUnbindReasonChange(reason: String) =
        _state.update { it.copy(unbindSheet = it.unbindSheet.copy(reason = reason, error = null)) }

    /** 提交退回：把该段退回未归档（repo.unbind，理由必填）。成功后刷新。 */
    fun submitUnbind() {
        val s = _state.value
        val rid = s.unbindSheet.recordingId ?: return
        val reason = s.unbindSheet.reason.trim()
        if (s.unbindSheet.submitting) return
        if (reason.isEmpty()) {
            _state.update { it.copy(unbindSheet = it.unbindSheet.copy(error = "请填写退回理由")) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(unbindSheet = it.unbindSheet.copy(submitting = true, error = null)) }
            when (val r = repo.unbind(rid, reason)) {
                is ApiResult.Success -> {
                    _state.update { it.copy(unbindSheet = UnbindSheet(), toast = "已退回未归档") }
                    refresh(initial = false)
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(unbindSheet = it.unbindSheet.copy(submitting = false, error = r.message)) }
            }
        }
    }

    // ───────────── 触发 / 取消分析 ─────────────

    /** 二次确认后：锁定接诊包 + 开始分析；成功跳报告。被前置条件挡住时回填可读原因。 */
    fun startAnalysis() {
        val s = _state.value
        val cid = s.customerId ?: return
        if (s.submitting || !s.canStart) return
        // 本地预检：把后端会挡的原因（说话人未确认/识别未完成）提前给出，避免撞失败
        s.blockReason?.let { code ->
            _state.update { it.copy(toast = reasonText(code)) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(submitting = true) }
            when (val r = repo.startAnalysis(cid, s.serviceDate.ifBlank { null })) {
                is ApiResult.Success -> {
                    val targetSid = r.data.sessionId ?: s.sessionId
                    _state.update {
                        it.copy(
                            submitting = false,
                            toast = r.data.msg ?: "已开始分析",
                            navigateToReport = targetSid,
                        )
                    }
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(submitting = false, toast = r.message) }
            }
        }
    }

    /** 取消正在进行的分析。 */
    fun cancelAnalysis() {
        val s = _state.value
        if (s.submitting || !s.canCancel) return
        viewModelScope.launch {
            _state.update { it.copy(submitting = true) }
            when (val r = repo.cancelAnalysis(s.sessionId)) {
                is ApiResult.Success -> {
                    _state.update { it.copy(submitting = false, toast = "已取消分析") }
                    refresh(initial = false)
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(submitting = false, toast = r.message) }
            }
        }
    }

    /** 试听某段片段（全程，无 60s 上限）。再次点同一段=停止。 */
    fun togglePlay(rec: PreviewRecording) {
        val url = rec.audioUrl
        if (url.isNullOrBlank()) {
            _state.update { it.copy(toast = "暂无可试听的音频") }
            return
        }
        if (_state.value.playingId == rec.id) {
            stopPlayback()
            return
        }
        stopPlayback()
        try {
            player = MediaPlayer().apply {
                setDataSource(url)
                setOnPreparedListener { start() }
                setOnCompletionListener { stopPlayback() }
                setOnErrorListener { _, _, _ ->
                    _state.update { it.copy(playingId = null, toast = "试听失败") }
                    true
                }
                prepareAsync()
            }
            _state.update { it.copy(playingId = rec.id) }
        } catch (e: Exception) {
            stopPlayback()
            _state.update { it.copy(toast = "试听失败") }
        }
    }

    private fun stopPlayback() {
        try {
            player?.stop()
            player?.release()
        } catch (_: Exception) {
        }
        player = null
        if (_state.value.playingId != null) _state.update { it.copy(playingId = null) }
    }

    fun consumeToast() = _state.update { it.copy(toast = null) }
    fun consumeNavigation() = _state.update { it.copy(navigateToReport = null) }

    override fun onCleared() {
        super.onCleared()
        stopPlayback()
        stopPolling()
    }

    companion object {
        /** 后端 reason 码 → 顾问可读文案（点 5）。 */
        fun reasonText(code: String?): String = when (code) {
            "asr_not_done" -> "还有片段在识别中，识别完成后才能开始分析。"
            "speaker_unconfirmed" -> "有片段提示说话人异常，请先在该片段上「确认说话人」再开始分析。"
            "no_recording" -> "接诊包内还没有陪伴片段，请先加入或去待整理绑定。"
            else -> "暂时无法开始分析，请稍后重试。"
        }
    }
}
