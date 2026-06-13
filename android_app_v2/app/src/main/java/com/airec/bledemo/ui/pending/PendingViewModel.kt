package com.airec.bledemo.ui.pending

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.airec.bledemo.data.model.NeedsConfirmRecording
import com.airec.bledemo.data.model.PendingRecording
import com.airec.bledemo.data.model.PenSyncQueryItem
import com.airec.bledemo.data.repo.ApiResult
import com.airec.bledemo.data.repo.ConsultantRepository
import com.airec.bledemo.recording.PenFile
import com.airec.bledemo.recording.RecordingController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 一段「陪伴笔机身记录」在同步 sheet 里的展示项：
 * 设备侧文件信息（[file]）+ 服务端去重状态（[status]：new | uploaded | deleted）+ 勾选态。
 */
data class PenSyncRow(
    val file: PenFile,
    val status: String? = null,       // new | uploaded | deleted（来自 sync-preview）
    val selected: Boolean = false,
) {
    /** 已导入过（uploaded）或服务端已删（deleted）的不可再选，置灰。 */
    val importable: Boolean get() = status == null || status == "new"
}

/**
 * 「从陪伴笔同步」sheet 的状态。
 *
 * @param visible 是否展示 sheet
 * @param loading 正在拉机身列表 / 查询去重状态
 * @param rows 机身片段行（含勾选 + 去重状态）
 * @param importing 正在提交导入
 * @param penUnavailable 取不到陪伴笔（未连接 / 无 controller）——sheet 内给提示
 */
data class PenSyncUiState(
    val visible: Boolean = false,
    val loading: Boolean = false,
    val rows: List<PenSyncRow> = emptyList(),
    val importing: Boolean = false,
    val penUnavailable: Boolean = false,
) {
    val selectableRows: List<PenSyncRow> get() = rows.filter { it.importable }
    val selectedCount: Int get() = rows.count { it.selected && it.importable }
    val allSelected: Boolean
        get() = selectableRows.isNotEmpty() && selectableRows.all { it.selected }
}

/**
 * 一条 toast（对应 warm_2 .toast）：文案 + 可选语义图标 + 是否危险态。
 * 一次性消费：Screen 弹出后调用 [PendingViewModel.toastShown] 清空。
 */
data class ToastEvent(
    val message: String,
    val icon: ToastIcon = ToastIcon.None,
    val danger: Boolean = false,
)

enum class ToastIcon { None, Check, Play, Sync, Warn }

/**
 * 待整理屏状态。
 *
 * @param loading 首屏 / 刷新加载中
 * @param recordings 未绑定的陪伴片段（含同步中占位、跨日、删除审批中）
 * @param needsConfirm 多说话人误录待确认片段（逐条可操作：仍然分析 / 解绑拆分重传）
 * @param crossDayDates 与当前接诊日不一致的待整理日期（跨日提示 banner，列出具体日期）
 * @param busyIds 正在提交后端操作（申请删除 / 撤回 / 知道了 / 误录确认）的片段 id——按钮置忙
 * @param error 加载失败文案（顶部 danger banner）
 */
data class PendingUiState(
    val loading: Boolean = false,
    val recordings: List<PendingRecording> = emptyList(),   // 全量(缓存)；跨日提示/计数按全量算
    val page: Int = 1,                                       // 当前页(1-based)
    val needsConfirm: List<NeedsConfirmRecording> = emptyList(),
    val crossDayDates: List<String> = emptyList(),
    val busyIds: Set<Long> = emptySet(),
    val error: String? = null,
) {
    val needsConfirmCount: Int get() = needsConfirm.size

    val isEmpty: Boolean get() = !loading && recordings.isEmpty() && error == null

    /** 总页数（每页 [PAGE_SIZE] 段，对齐网页端的 5/页）。 */
    val totalPages: Int get() = if (recordings.isEmpty()) 1 else (recordings.size - 1) / PAGE_SIZE + 1

    /** 当前页应展示的片段：客户端分页，全量已缓存，翻页不再联网。 */
    val pagedRecordings: List<PendingRecording>
        get() = recordings.drop((page.coerceIn(1, totalPages) - 1) * PAGE_SIZE).take(PAGE_SIZE)

    companion object { const val PAGE_SIZE = 5 }
}

/**
 * 待整理（SPEC §4.3 / warm_2 #pending）。
 *
 * 列表 = [ConsultantRepository.pending]；跨日提示 = [ConsultantRepository.pendingDates]；
 * 误录提示 = [ConsultantRepository.needsConfirm]；「从陪伴笔同步」= 设备侧 [RecordingController.syncPenFiles]
 * 拉机身片段 + [ConsultantRepository.penSyncPreview] 查去重状态 + [RecordingController.uploadPenFiles] 导入。
 *
 * 试听走 [ConsultantRepository.recordingUrl]（全程，无 60s 上限），交由 Screen 的播放器消费。
 */
class PendingViewModel(
    private val repo: ConsultantRepository = ConsultantRepository(),
    private val controller: RecordingController? = null,
    /** 当前接诊日（判断跨日用），默认空串=不过滤；Screen 可注入今天日期。 */
    private val todayDate: String = "",
) : ViewModel() {

    private val _state = MutableStateFlow(PendingUiState(loading = true))
    val state: StateFlow<PendingUiState> = _state.asStateFlow()

    private val _penSync = MutableStateFlow(PenSyncUiState())
    val penSync: StateFlow<PenSyncUiState> = _penSync.asStateFlow()

    private val _toast = MutableStateFlow<ToastEvent?>(null)
    val toast: StateFlow<ToastEvent?> = _toast.asStateFlow()

    init {
        load(initial = true)
    }

    /**
     * 拉取待整理列表 + 跨日日期 + 误录列表。
     * @param initial 首屏；保留既有 error。
     * @param silent 自动轮询触发——绝不转圈、不清 error，纯后台替换数据（对齐网页端 5s 自动刷新）。
     */
    fun load(initial: Boolean = false, silent: Boolean = false) {
        val hasData = _state.value.recordings.isNotEmpty()
        // stale-while-revalidate：已有数据/静默轮询时不转圈、不清空，旧列表继续显示，后台静默刷新（消除切换/刷新卡顿）。
        if (!silent) _state.update { it.copy(loading = !hasData, error = if (initial) it.error else null) }
        viewModelScope.launch {
            when (val r = repo.pending()) {
                is ApiResult.Success ->
                    _state.update {
                        val sorted = r.data.sortedByDescending { rec -> recSortKey(rec) }   // 录制时间倒序，最新在最上
                        val tp = if (sorted.isEmpty()) 1 else (sorted.size - 1) / PendingUiState.PAGE_SIZE + 1
                        it.copy(loading = false, recordings = sorted, error = null, page = it.page.coerceIn(1, tp))
                    }
                is ApiResult.Failure ->
                    // 有旧数据/静默轮询则保留旧数据，不用刷新失败盖掉用户正看着的列表。
                    _state.update { it.copy(loading = false, error = if (hasData || silent) it.error else r.message) }
            }
        }
        viewModelScope.launch {
            (repo.pendingDates() as? ApiResult.Success)?.let { res ->
                // 与当前接诊日不一致的日期，去重后倒序（最近在前），对齐网页端 crossWarn。
                val cross = res.data.filter { it.isNotBlank() && it != todayDate }.distinct().sortedDescending()
                _state.update { it.copy(crossDayDates = cross) }
            }
        }
        viewModelScope.launch {
            (repo.needsConfirm() as? ApiResult.Success)?.let { res ->
                _state.update { it.copy(needsConfirm = res.data) }
            }
        }
    }

    /** 顶部「刷新」。 */
    fun refresh() {
        load(initial = false)
        showToast("已刷新", ToastIcon.Check)
    }

    /** 自动轮询（Screen 可见且无试听播放时每 ~5s 调用）。静默刷新，不打断用户。 */
    fun poll() = load(initial = false, silent = true)

    /** 翻页（纯客户端，全量已缓存，不联网）。 */
    fun goPage(delta: Int) {
        _state.update { it.copy(page = (it.page + delta).coerceIn(1, it.totalPages)) }
    }

    // ───────────────────────── 删除申请链 ─────────────────────────

    private fun setBusy(rid: Long, busy: Boolean) =
        _state.update { it.copy(busyIds = if (busy) it.busyIds + rid else it.busyIds - rid) }

    /** 发起删除申请（走审批）。对齐网页端 requestDelRec：成功才刷新，失败弹原因。 */
    fun requestDelete(rid: Long, reason: String?) {
        if (rid in _state.value.busyIds) return
        setBusy(rid, true)
        viewModelScope.launch {
            when (val r = repo.deleteRequest(rid, reason?.ifBlank { null })) {
                is ApiResult.Success -> {
                    setBusy(rid, false)
                    showToast("已提交删除申请，等待审批", ToastIcon.Check)
                    load(initial = false)
                }
                is ApiResult.Failure -> {
                    setBusy(rid, false)
                    showToast(r.message, ToastIcon.Warn, danger = true)
                }
            }
        }
    }

    /** 撤回自己未审批的删除申请（删除审批中的占位卡）。成功才刷新。 */
    fun withdrawDelete(rid: Long) {
        if (rid in _state.value.busyIds) return
        setBusy(rid, true)
        viewModelScope.launch {
            when (val r = repo.deleteRequestWithdraw(rid)) {
                is ApiResult.Success -> {
                    setBusy(rid, false)
                    showToast("已撤回删除申请", ToastIcon.Check)
                    load(initial = false)
                }
                is ApiResult.Failure -> {
                    setBusy(rid, false)
                    showToast(r.message, ToastIcon.Warn, danger = true)
                }
            }
        }
    }

    /** 关闭「删除被拒」提示（知道了）。对齐网页端 dismissDelReject：失败静默，照常刷新。 */
    fun dismissDeleteReject(rid: Long) {
        if (rid in _state.value.busyIds) return
        setBusy(rid, true)
        viewModelScope.launch {
            repo.deleteRequestDismiss(rid)   // 网页端忽略错误
            setBusy(rid, false)
            load(initial = false)
        }
    }

    // ───────────────────────── 误录（多说话人）确认 ─────────────────────────

    /**
     * 误录逐条确认。action：keep（仍然分析）| unbind（解绑拆分重传）。
     * 对齐网页端 confirmSpeakers：成功后同时刷新误录列表与待整理列表（unbind 会回到待整理）。
     */
    fun confirmSpeakers(rid: Long, action: String) {
        if (rid in _state.value.busyIds) return
        setBusy(rid, true)
        viewModelScope.launch {
            when (val r = repo.confirmSpeakers(rid, action)) {
                is ApiResult.Success -> {
                    setBusy(rid, false)
                    showToast(if (action == "unbind") "已解绑，已回到待整理可拆分重传" else "已确认，将照常分析", ToastIcon.Check)
                    load(initial = false)
                }
                is ApiResult.Failure -> {
                    setBusy(rid, false)
                    showToast(r.message, ToastIcon.Warn, danger = true)
                }
            }
        }
    }

    // ───────────────────────── 从陪伴笔同步 ─────────────────────────

    /** 打开 sheet：先确认陪伴笔已连接，再拉机身片段并查去重状态。 */
    fun openPenSync() {
        val c = controller
        // 未连接（或无引擎）→ 直接给「未连接」提示，区别于「连着但机身没文件」
        // （对齐 web 开 sheet 前的 appPenConnected 守卫；否则引擎对未连接也返回空列表，会被误显示成"没有文件"）。
        if (c == null || !c.isPenConnected()) {
            _penSync.update { it.copy(visible = true, loading = false, rows = emptyList(), penUnavailable = true) }
            return
        }
        _penSync.update { it.copy(visible = true, loading = true, penUnavailable = false) }
        c.syncPenFiles { files ->
            // 回调可能在非主线程；用 viewModelScope 切回协程上下文做后续网络查询。
            viewModelScope.launch { onPenFiles(files) }
        }
    }

    private suspend fun onPenFiles(files: List<PenFile>) {
        if (files.isEmpty()) {
            _penSync.update { it.copy(loading = false, rows = emptyList(), penUnavailable = false) }
            return
        }
        val statusByName: Map<String, String?> =
            when (val r = repo.penSyncPreview(files.map { PenSyncQueryItem(name = it.name, ra = it.recordedAt.ifBlank { null }) })) {
                is ApiResult.Success -> r.data.items.orEmpty().associate { it.name.orEmpty() to it.status }
                is ApiResult.Failure -> emptyMap()
            }
        val rows = files.map { f ->
            val st = statusByName[f.name] ?: if (f.uploaded) "uploaded" else "new"
            // 对齐网页端：未传【默认不勾】，由顾问自己选要传的（避免误把一堆机身片段全导进来）。
            PenSyncRow(file = f, status = st, selected = false)
        }
        _penSync.update { it.copy(loading = false, rows = rows, penUnavailable = false) }
    }

    fun closePenSync() {
        _penSync.update { it.copy(visible = false) }
    }

    fun togglePenRow(name: String) {
        _penSync.update { s ->
            s.copy(rows = s.rows.map { if (it.file.name == name && it.importable) it.copy(selected = !it.selected) else it })
        }
    }

    fun togglePenAll() {
        _penSync.update { s ->
            val turnOn = !s.allSelected
            s.copy(rows = s.rows.map { if (it.importable) it.copy(selected = turnOn) else it })
        }
    }

    /** 导入选中：经 [RecordingController.uploadPenFiles] 落入待整理，关 sheet + toast + 刷新。 */
    fun importSelected() {
        val sel = _penSync.value.rows.filter { it.selected && it.importable }
        if (sel.isEmpty()) return
        controller?.uploadPenFiles(sel.map { it.file.name })
        _penSync.update { it.copy(visible = false, importing = false) }
        showToast("已导入 ${sel.size} 段到待整理，正在同步…", ToastIcon.Sync)
        // 导入是后台异步下载+落占位，分档延时多刷几次让占位卡尽快出现（对齐网页端 3s/10s，单次立刷往往还没落地）。
        viewModelScope.launch {
            for (d in longArrayOf(3000, 10000)) {
                delay(d)
                load(initial = false, silent = true)
            }
        }
    }

    /** 是否能对「同步中」占位卡给「重试」入口（controller 存在才有意义）。 */
    val canRetryPenUploads: Boolean get() = controller != null

    /**
     * 立刻重推后台补传队列（对齐网页端 penRetryUploads）。延时多刷几次看结果。
     */
    fun retryPenUploads() {
        val c = controller ?: return
        c.retryPenUploads()
        showToast("已重新开始补传，请保持陪伴笔连着、贴近手机", ToastIcon.Sync)
        viewModelScope.launch {
            // 补传是后台异步，分几档延时刷新让占位卡尽快转正（对齐网页端 4/12/30s）。
            for (d in longArrayOf(4000, 12000, 30000)) {
                delay(d)
                load(initial = false, silent = true)
            }
        }
    }

    // ───────────────────────── toast ─────────────────────────

    fun showToast(message: String, icon: ToastIcon = ToastIcon.None, danger: Boolean = false) {
        _toast.value = ToastEvent(message, icon, danger)
    }

    fun toastShown() {
        _toast.value = null
    }
}

/**
 * 待整理片段排序键（对齐网页端）：优先录制时刻 recorded_at，
 * 退回 服务日期+开始时段，再退回 created_at。倒序排即「最新在最上」。
 */
private fun recSortKey(rec: PendingRecording): String =
    rec.recordedAt
        ?: rec.serviceDate?.let { "$it ${rec.startHm ?: ""}" }
        ?: rec.createdAt
        ?: ""
