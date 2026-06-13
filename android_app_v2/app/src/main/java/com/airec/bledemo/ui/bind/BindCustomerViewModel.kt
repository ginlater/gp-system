package com.airec.bledemo.ui.bind

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.airec.bledemo.data.model.Customer
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
 * 绑定 / 换绑顾客（web consultant.html 的 #addMask/#bindMask + #rebindMask + #unbindMask
 * 三个弹窗合并到一屏，SPEC §4.4 / warm_2 #m-bind + #m-rebind）的状态机。
 *
 * 本屏只拿到一个 [recordingId]（nav 限定，不能加参数），故首屏自我探测这段片段当前状态：
 *  - 在「待整理」列表里出现（未绑定）→ [Mode.Binding]：绑定到接诊顾客；同时携带其
 *    delete_request_status，可「申请删除 / 撤回删除申请」（web 把申请删除归在绑定侧流程）。
 *  - 不在待整理（已绑定到某顾客）→ [Mode.Rebinding]：可「换绑到别的顾客」或「退回未归档」。
 *
 * 选人来源对齐 web（rbLoadCandidates）：默认用当日接诊名单 [todayReception]（显示已绑段数、
 * 过滤已锁定接诊包），输入关键词时叠加 [rebindCandidates] 搜索本人接待过的顾客；两者按 cid 去重。
 *
 * 各动作用到的 repo 现有方法：
 *  - 绑定：[ConsultantRepository.bind]（in_day=false 的顾客先 [addTodayReception] 补登）
 *  - 换绑：[ConsultantRepository.directRebind]（成功带 new_session_id → 用它跳新接诊包）
 *  - 退回未归档：[ConsultantRepository.unbind]（reason 必填）
 *  - 申请删除 / 撤回：[ConsultantRepository.deleteRequest] / [ConsultantRepository.deleteRequestWithdraw]
 *  - 新增当日顾客 / 删除误建：[ConsultantRepository.addDayCustomer] / [ConsultantRepository.removeDayCustomer]
 *
 * 产品红线：对外只说「陪伴片段 / 陪伴师」，不出现"录音"。
 */
class BindCustomerViewModel(
    private val recordingId: Long,
    private val repo: ConsultantRepository = ConsultantRepository(),
) : ViewModel() {

    /** 这段片段当前状态：未绑定=绑定流程；已绑定=换绑/退回流程。 */
    enum class Mode { Binding, Rebinding }

    /** 顶部分段：选已有顾客 / 新增顾客。 */
    enum class Tab { Existing, New }

    /** 待确认的绑定/换绑意图（弹确认框时携带）。 */
    data class PendingBind(
        val customerId: Long,
        val name: String,
        val needsBackfill: Boolean,
    )

    /** 选人列表里的一行（融合当日接诊名单 + 搜索候选）。 */
    data class Pick(
        val customerId: Long,
        val name: String,
        val phoneTail: String? = null,
        val memberCard: String? = null,
        /** 已在该片段当天本人接诊（无需补登）。 */
        val inDay: Boolean = false,
        /** 名下当日已绑定的陪伴段数（来自接诊名单 recording_count）。 */
        val boundCount: Int = 0,
        /** 接诊包已锁定（分析进行/完成）——不能再往里塞，仅展示。 */
        val locked: Boolean = false,
        /** 本次新建、名下还没有片段 → 可「删除误建」。 */
        val justCreated: Boolean = false,
    )

    data class UiState(
        val mode: Mode = Mode.Binding,
        val booting: Boolean = true,            // 首屏探测片段状态中
        val tab: Tab = Tab.Existing,
        // 该片段服务日期；空=今天
        val serviceDate: String? = null,
        // 已绑定模式下：当前所属顾客（换绑时从列表里排除自己）
        val currentCustomerId: Long? = null,
        val currentCustomerName: String? = null,
        // 片段简介（时段·时长），展示用
        val recLabel: String? = null,
        // 删除申请状态：null | pending | rejected
        val deleteRequestStatus: String? = null,
        val deleteRejectReason: String? = null,
        // —— 选人列表 ——
        val query: String = "",
        val picks: List<Pick> = emptyList(),
        val searching: Boolean = false,
        // —— 新增顾客表单 ——
        val newName: String = "",
        val newPhoneTail: String = "",
        // —— 换绑/退回理由 ——
        val rebindReason: String = "",         // 换绑理由（可选）
        // —— 弹层态 ——
        val pendingBind: PendingBind? = null,   // 非空=绑定/换绑确认弹窗
        val unbindSheet: Boolean = false,       // 退回未归档 sheet
        val unbindReason: String = "",          // 退回理由（必填）
        val deleteSheet: Boolean = false,       // 申请删除 sheet
        val deleteReason: String = "",          // 申请删除理由（可选）
        // —— 流程态 ——
        val submitting: Boolean = false,        // 提交中（禁按钮 / 防重复）
        val error: String? = null,              // toast 文案（danger）
        val toast: String? = null,              // toast 文案（成功 check）
        val boundSessionId: Long? = null,       // 非空=绑定/换绑成功，screen 据此回调跳预览
        val finished: Boolean = false,          // 退回/删除成功后无 session 可跳，仅返回
    ) {
        /** 新增顾客可提交：姓名必填；手机尾号填了就必须 4 位数字。 */
        val canSubmitNew: Boolean
            get() = newName.isNotBlank() &&
                (newPhoneTail.isEmpty() || newPhoneTail.length == 4) &&
                !submitting

        /** 删除审批中：除「撤回」外其余操作应禁用（与待整理屏一致）。 */
        val deletePending: Boolean get() = deleteRequestStatus == "pending"
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    init {
        boot()
    }

    // ───────────── 首屏：探测片段状态 + 拉选人列表 ─────────────

    /** 用现有 [ConsultantRepository.pending] 判断这段在不在未归档池：在=绑定流程，不在=换绑流程。 */
    private fun boot() {
        viewModelScope.launch {
            when (val r = repo.pending()) {
                is ApiResult.Success -> {
                    val rec = r.data.firstOrNull { it.id == recordingId }
                    if (rec != null) {
                        _state.update {
                            it.copy(
                                booting = false,
                                mode = Mode.Binding,
                                serviceDate = rec.serviceDate ?: it.serviceDate,
                                recLabel = recLabelOf(rec.recordedAt, rec.durationLabel),
                                deleteRequestStatus = rec.deleteRequestStatus,
                                deleteRejectReason = rec.deleteRejectReason,
                            )
                        }
                    } else {
                        // 不在未归档池 → 已绑定，进入换绑/退回流程
                        _state.update { it.copy(booting = false, mode = Mode.Rebinding) }
                    }
                }
                // 拉不到也不阻塞：默认按绑定流程（与原行为一致），列表照常加载
                is ApiResult.Failure -> _state.update { it.copy(booting = false, mode = Mode.Binding) }
            }
            loadPicks() // 两种模式都要选人列表
        }
    }

    private fun recLabelOf(recordedAt: String?, durationLabel: String?): String? {
        val parts = listOfNotNull(recordedAt?.takeIf { it.isNotBlank() }, durationLabel?.takeIf { it.isNotBlank() })
        return parts.joinToString(" · ").ifBlank { null }
    }

    // ───────────── 分段切换 ─────────────

    fun selectTab(tab: Tab) {
        if (_state.value.tab == tab) return
        _state.update { it.copy(tab = tab, error = null) }
    }

    // ───────────── 选人：当日接诊名单 + 搜索候选 ─────────────

    fun onQueryChange(q: String) {
        _state.update { it.copy(query = q) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(280) // 输入防抖（对齐 web rbSearchTimer）
            loadPicks()
        }
    }

    /**
     * 对齐 web rbLoadCandidates：
     *  - 关键词为空：优先展示当日接诊名单（todayReception，带已绑段数/锁定态），
     *    再补 rebindCandidates 里不在名单的本人接待过顾客；
     *  - 有关键词：只用 rebindCandidates 搜索（服务端按本人隔离）。
     * 两路均带 service_date（rebind_candidates 回的为准），并排除当前所属顾客本人。
     */
    fun loadPicks() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.update { it.copy(searching = true) }
            val q = _state.value.query.trim().ifBlank { null }

            // 1) 搜索候选（rebind_candidates 同时给 service_date / in_day）
            val candResult = repo.rebindCandidates(rid = recordingId, q = q)
            val candidates = (candResult as? ApiResult.Success)?.data?.items ?: emptyList()
            val svcDate = (candResult as? ApiResult.Success)?.data?.serviceDate

            // 2) 仅在无关键词时叠加当日接诊名单（带已绑段数/锁定）
            val reception = if (q == null) {
                (repo.todayReception(date = svcDate ?: _state.value.serviceDate) as? ApiResult.Success)
                    ?.data?.items ?: emptyList()
            } else {
                emptyList()
            }

            if (candResult is ApiResult.Failure && reception.isEmpty()) {
                _state.update { it.copy(searching = false, error = candResult.message) }
                return@launch
            }

            // in_day 标记取自候选（更准）
            val inDayIds = candidates.filter { it.inDay == true }.mapNotNull { it.cid }.toSet()

            val fromReception = reception.mapNotNull { r ->
                val cid = r.customerId ?: return@mapNotNull null
                Pick(
                    customerId = cid,
                    name = r.name ?: "未命名顾客",
                    phoneTail = r.phoneTail,
                    memberCard = r.memberCard,
                    inDay = true, // 在当日名单里即已接诊
                    boundCount = r.recordingCount ?: 0,
                    locked = r.locked == true,
                )
            }
            val seen = fromReception.map { it.customerId }.toMutableSet()
            val fromCandidates = candidates.mapNotNull { c ->
                val cid = c.cid ?: return@mapNotNull null
                if (cid in seen) return@mapNotNull null
                seen += cid
                Pick(
                    customerId = cid,
                    name = c.name ?: "未命名顾客",
                    phoneTail = c.phoneTail,
                    memberCard = c.memberCard,
                    inDay = cid in inDayIds || c.inDay == true,
                    boundCount = 0,
                    locked = false,
                )
            }
            val cur = _state.value.currentCustomerId
            val merged = (fromReception + fromCandidates).filterNot { it.customerId == cur }

            _state.update {
                it.copy(
                    searching = false,
                    picks = merged,
                    serviceDate = svcDate ?: it.serviceDate,
                )
            }
        }
    }

    // ───────────── 选中顾客 → 确认（绑定 or 换绑共用） ─────────────

    /** 点候选「绑定/换绑到这里」：弹确认。locked 的接诊包不可再加入。 */
    fun onPick(p: Pick) {
        if (p.locked) {
            _state.update { it.copy(error = "「${p.name}」的接诊包已锁定，无法再加入") }
            return
        }
        _state.update {
            it.copy(
                pendingBind = PendingBind(
                    customerId = p.customerId,
                    name = p.name,
                    needsBackfill = !p.inDay,
                ),
            )
        }
    }

    // ───────────── 新增顾客表单（新增 → 选中，可删误建） ─────────────

    fun onNewNameChange(v: String) = _state.update { it.copy(newName = v) }

    fun onNewPhoneTailChange(v: String) {
        val digits = v.filter { it.isDigit() }.take(4)
        _state.update { it.copy(newPhoneTail = digits) }
    }

    /**
     * 「新增并选中」（对齐 web rbAddNewCustomer）：建当日顾客 + 自动补登接诊，
     * 然后切回「选已有」并把新顾客置顶选中、标 justCreated（可「删除误建」），不直接绑定。
     */
    fun onSubmitNew() {
        val s = _state.value
        if (!s.canSubmitNew) return
        viewModelScope.launch {
            _state.update { it.copy(submitting = true, error = null) }
            val r = repo.addDayCustomer(
                rid = recordingId,
                name = s.newName.trim(),
                phoneTail = s.newPhoneTail.ifBlank { null },
            )
            when (r) {
                is ApiResult.Success -> {
                    val cid = r.data.customerId
                    if (cid == null) {
                        _state.update { it.copy(submitting = false, error = "新增成功但未返回顾客，请重试") }
                        return@launch
                    }
                    val name = r.data.name ?: s.newName.trim()
                    val newPick = Pick(
                        customerId = cid,
                        name = name,
                        phoneTail = s.newPhoneTail.ifBlank { null },
                        inDay = true,        // 已补登当天接诊
                        boundCount = 0,
                        justCreated = true,  // 名下尚无片段 → 可删除误建
                    )
                    _state.update {
                        it.copy(
                            submitting = false,
                            tab = Tab.Existing,
                            newName = "",
                            newPhoneTail = "",
                            // 置顶插入，去掉可能已存在的同 id
                            picks = listOf(newPick) + it.picks.filterNot { p -> p.customerId == cid },
                            toast = "已新增「$name」并补登当天接诊，已替您选中",
                        )
                    }
                }
                is ApiResult.Failure -> _state.update {
                    it.copy(submitting = false, error = r.message)
                }
            }
        }
    }

    /** 删除误建顾客（web rbRemoveCustomer，名下有片段则后端拒绝）。用 [ConsultantRepository.removeDayCustomer]。 */
    fun removeMisCreated(p: Pick) {
        if (_state.value.submitting) return
        viewModelScope.launch {
            _state.update { it.copy(submitting = true, error = null) }
            when (val r = repo.removeDayCustomer(rid = recordingId, customerId = p.customerId)) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        submitting = false,
                        picks = it.picks.filterNot { x -> x.customerId == p.customerId },
                        toast = "已删除「${p.name}」",
                    )
                }
                is ApiResult.Failure -> _state.update { it.copy(submitting = false, error = r.message) }
            }
        }
    }

    // ───────────── 换绑理由 ─────────────

    fun onRebindReasonChange(v: String) = _state.update { it.copy(rebindReason = v) }

    // ───────────── 确认弹窗：取消 / 确认（绑定 or 换绑） ─────────────

    fun dismissConfirm() {
        if (_state.value.submitting) return
        _state.update { it.copy(pendingBind = null) }
    }

    /**
     * 确认：
     *  - [Mode.Binding]：必要时先 [addTodayReception] 补登，再 [bind] 拿 session_id。
     *  - [Mode.Rebinding]：[directRebind]（reason 可选），成功取 new_session_id 跳新接诊包。
     */
    fun confirmBind() {
        val pb = _state.value.pendingBind ?: return
        if (_state.value.submitting) return
        if (_state.value.mode == Mode.Rebinding) {
            confirmRebind(pb)
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(submitting = true, error = null) }
            // in_day=false 的顾客：bind 前先补登到该片段当天接诊白名单
            if (pb.needsBackfill) {
                val add = repo.addTodayReception(customerId = pb.customerId, date = _state.value.serviceDate)
                if (add is ApiResult.Failure) {
                    _state.update { it.copy(submitting = false, error = add.message) }
                    return@launch
                }
            }
            when (val r = repo.bind(rid = recordingId, customerId = pb.customerId)) {
                is ApiResult.Success -> {
                    val sid = r.data.sessionId
                    if (sid == null) {
                        _state.update {
                            it.copy(submitting = false, pendingBind = null, error = "绑定成功但未返回接诊包，请回列表确认")
                        }
                        return@launch
                    }
                    _state.update {
                        it.copy(
                            submitting = false, pendingBind = null,
                            toast = "已绑定到${pb.name}", boundSessionId = sid,
                        )
                    }
                }
                is ApiResult.Failure -> _state.update {
                    it.copy(submitting = false, pendingBind = null, error = r.message)
                }
            }
        }
    }

    /** 换绑（仅这一段）。in_day=false 的目标先补登，再 [directRebind]。 */
    private fun confirmRebind(pb: PendingBind) {
        viewModelScope.launch {
            _state.update { it.copy(submitting = true, error = null) }
            if (pb.needsBackfill) {
                val add = repo.addTodayReception(customerId = pb.customerId, date = _state.value.serviceDate)
                if (add is ApiResult.Failure) {
                    _state.update { it.copy(submitting = false, error = add.message) }
                    return@launch
                }
            }
            val reason = _state.value.rebindReason.trim().ifBlank { null }
            when (val r = repo.directRebind(rid = recordingId, toCustomerId = pb.customerId, reason = reason)) {
                is ApiResult.Success -> {
                    // 成功带 new_session_id → 跳目标顾客的新接诊包；缺省则仅返回
                    val sid = r.data.newSessionId ?: r.data.sessionId
                    _state.update {
                        it.copy(
                            submitting = false, pendingBind = null,
                            toast = "已换绑到${pb.name}",
                            boundSessionId = sid,
                            finished = sid == null,
                        )
                    }
                }
                is ApiResult.Failure -> _state.update {
                    it.copy(submitting = false, pendingBind = null, error = r.message)
                }
            }
        }
    }

    // ───────────── 退回未归档（unbind，reason 必填） ─────────────

    fun openUnbind() = _state.update { it.copy(unbindSheet = true, unbindReason = "", error = null) }
    fun dismissUnbind() { if (!_state.value.submitting) _state.update { it.copy(unbindSheet = false) } }
    fun onUnbindReasonChange(v: String) = _state.update { it.copy(unbindReason = v) }

    /** 确认退回未归档：[ConsultantRepository.unbind]（reason 必填）。成功后回到列表。 */
    fun confirmUnbind() {
        val reason = _state.value.unbindReason.trim()
        if (reason.isEmpty()) {
            _state.update { it.copy(error = "请填写退回理由") }
            return
        }
        if (_state.value.submitting) return
        viewModelScope.launch {
            _state.update { it.copy(submitting = true, error = null) }
            when (val r = repo.unbind(rid = recordingId, reason = reason)) {
                is ApiResult.Success -> _state.update {
                    it.copy(submitting = false, unbindSheet = false, toast = "已退回未归档片段", finished = true)
                }
                is ApiResult.Failure -> _state.update { it.copy(submitting = false, error = r.message) }
            }
        }
    }

    // ───────────── 申请删除 / 撤回（未归档片段） ─────────────

    fun openDeleteRequest() = _state.update { it.copy(deleteSheet = true, deleteReason = "", error = null) }
    fun dismissDeleteRequest() { if (!_state.value.submitting) _state.update { it.copy(deleteSheet = false) } }
    fun onDeleteReasonChange(v: String) = _state.update { it.copy(deleteReason = v) }

    /** 发起删除申请（走审批，reason 可选）：[ConsultantRepository.deleteRequest]。 */
    fun confirmDeleteRequest() {
        if (_state.value.submitting) return
        viewModelScope.launch {
            _state.update { it.copy(submitting = true, error = null) }
            val reason = _state.value.deleteReason.trim().ifBlank { null }
            when (val r = repo.deleteRequest(rid = recordingId, reason = reason)) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        submitting = false, deleteSheet = false,
                        deleteRequestStatus = "pending",
                        toast = "已提交删除申请，等待管理员审批",
                    )
                }
                is ApiResult.Failure -> _state.update { it.copy(submitting = false, error = r.message) }
            }
        }
    }

    /** 撤回删除申请（真调 [ConsultantRepository.deleteRequestWithdraw]，非假动作）。 */
    fun withdrawDeleteRequest() {
        if (_state.value.submitting) return
        viewModelScope.launch {
            _state.update { it.copy(submitting = true, error = null) }
            when (val r = repo.deleteRequestWithdraw(rid = recordingId)) {
                is ApiResult.Success -> _state.update {
                    it.copy(submitting = false, deleteRequestStatus = null, deleteRejectReason = null, toast = "已撤回删除申请")
                }
                is ApiResult.Failure -> _state.update { it.copy(submitting = false, error = r.message) }
            }
        }
    }

    // ───────────── 一次性事件消费 ─────────────

    fun consumeError() = _state.update { it.copy(error = null) }
    fun consumeToast() = _state.update { it.copy(toast = null) }

    companion object {
        /** recordingId 由路由参数传入，需用 factory 注入到 ViewModel 构造。 */
        fun factory(recordingId: Long) = viewModelFactory {
            initializer { BindCustomerViewModel(recordingId) }
        }
    }
}
