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
import com.airec.bledemo.recording.RecordingState
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
 * @param penBusy E3：笔正在录音——机身列表拉不动，提示结束后再同步
 * @param loadFailed E2/E3：机身列表超时无响应，或去重预检失败——给重试，不再按本地标记瞎渲染
 */
data class PenSyncUiState(
    val visible: Boolean = false,
    val loading: Boolean = false,
    /** 仅【未导入】片段，已按录音时间倒序（最近的在最上）。 */
    val rows: List<PenSyncRow> = emptyList(),
    val page: Int = 1,                          // 当前页（1-based）
    val importing: Boolean = false,
    val penUnavailable: Boolean = false,
    val penBusy: Boolean = false,
    val loadFailed: Boolean = false,
    /** ★2.1.9:读取中的进度提示（文件多时显示"已读到 N 段…"，避免顾问以为卡死）。 */
    val loadingHint: String? = null,
) {
    val selectableRows: List<PenSyncRow> get() = rows.filter { it.importable }
    val selectedCount: Int get() = rows.count { it.selected && it.importable }
    val allSelected: Boolean
        get() = selectableRows.isNotEmpty() && selectableRows.all { it.selected }

    val totalPages: Int get() = if (rows.isEmpty()) 1 else (rows.size + PEN_SYNC_PAGE_SIZE - 1) / PEN_SYNC_PAGE_SIZE

    /**
     * ★2.1.9 按日期分组（对齐 iOS，2026-07-04 就有的需求，安卓一直没做）：
     * 本页的行按"录音日期"归组，顺序保持 rows 的倒序（最近的日期在上）。
     * 顾问点日期头即可一键勾选/取消那一天——笔里攒了几十段时不用一条条点。
     */
    val pageGroups: List<Pair<String, List<PenSyncRow>>>
        get() = pageRows
            .groupBy { it.file.recordedAt.take(10).ifBlank { "时间未知" } }
            .toList()
    /** 当前页要展示的行（分页）。 */
    val pageRows: List<PenSyncRow> get() = rows.drop((page - 1) * PEN_SYNC_PAGE_SIZE).take(PEN_SYNC_PAGE_SIZE)
}

/**
 * 「从陪伴笔同步」每页条数。弹窗里（全选 + 本页若干段 + 翻页器 + 导入按钮）必须一屏装得下，
 * 因为 ModalBottomSheet 对自定义内容不可靠地滚动（会吞掉滑动手势）。每页 4 段刚好留出翻页器和
 * 导入按钮的可见空间，机身片段多时翻页看，避免"显示不全 / 翻页器掉屏外"。
 */
const val PEN_SYNC_PAGE_SIZE = 12   // ★2.1.9:4→12(配合日期分组,少翻页)
const val PEN_PREVIEW_BATCH = 30    // ★2.1.9:服务端去重预检分批大小(防大请求超时)

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

    // ★2.2.0 防"删了又冒出来"：待整理列表每几秒静默刷新一次。点删除的瞬间若已有一个刷新请求在路上
    //   (它拿的是删除前的数据)，回来就会把删掉的那行【又盖回列表】——顾问再点删除就打到已不存在的 id,
    //   报"录音不存在"。这里记下刚删的 id，之后 15 秒内任何列表更新都把它们过滤掉。
    private val recentlyDeleted = mutableMapOf<Long, Long>()   // rid → 删除时刻(ms)

    private fun pruneDeleted() {
        val now = System.currentTimeMillis()
        recentlyDeleted.entries.removeAll { now - it.value > 15_000 }
    }

    private fun filterDeleted(list: List<PendingRecording>): List<PendingRecording> {
        pruneDeleted()
        if (recentlyDeleted.isEmpty()) return list
        return list.filter { it.id !in recentlyDeleted.keys }
    }

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
                        val sorted = filterDeleted(r.data).sortedByDescending { rec -> recSortKey(rec) }   // 录制时间倒序，最新在最上
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
                    // 不足5分钟→后端免审批直接删(deleted=true)；否则进入审批。
                    if (r.data.deleted == true) {
                        // ★2.2.0：真删掉了 → ① 记入"刚删"名单(15s 内轮询不许刷回来)
                        //   ② 通知引擎取消这段还在搬运/待传的任务(不再传、不再复活、省蓝牙省费用)
                        recentlyDeleted[rid] = System.currentTimeMillis()
                        controller?.cancelPenTaskByPlaceholder(rid)
                        _state.update { st -> st.copy(recordings = st.recordings.filter { it.id != rid }) }
                        showToast("已删除", ToastIcon.Check)
                    } else showToast("已提交删除申请，等待审批", ToastIcon.Check)
                    load(initial = false)
                }
                is ApiResult.Failure -> {
                    setBusy(rid, false)
                    // ★2.2.0：服务端说"录音不存在"= 这段其实早就删掉了（列表是旧的）→ 不吓唬顾问，
                    //   静默把这行移除并刷新，不再弹红色错误。
                    if (r.message.contains("不存在")) {
                        recentlyDeleted[rid] = System.currentTimeMillis()
                        _state.update { st -> st.copy(recordings = st.recordings.filter { it.id != rid }) }
                        load(initial = false)
                    } else {
                        showToast(r.message, ToastIcon.Warn, danger = true)
                    }
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
                    // D8：服务端不再自动分析（确认后接诊包解锁），别说"照常分析"让人干等
                    showToast(if (action == "unbind") "已解绑，已回到待整理可拆分重传" else "已确认，请到接诊包点「开始分析」", ToastIcon.Check)
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

    // E3：机身列表拉取超时看门狗（BLE 无响应时别永久转圈）。
    private var penListTimeoutJob: kotlinx.coroutines.Job? = null

    /** 打开 sheet：先确认陪伴笔已连接且空闲，再拉机身片段并查去重状态。也用于「重试」。 */
    fun openPenSync() {
        val c = controller
        // 未连接（或无引擎）→ 直接给「未连接」提示，区别于「连着但机身没文件」
        // （对齐 web 开 sheet 前的 appPenConnected 守卫；否则引擎对未连接也返回空列表，会被误显示成"没有文件"）。
        if (c == null || !c.isPenConnected()) {
            _penSync.update { it.copy(visible = true, loading = false, rows = emptyList(), penUnavailable = true, penBusy = false, loadFailed = false) }
            return
        }
        // E3：笔在录音（含开始中）时列表命令会被笔忽略/顶掉，别发了干等——明说"结束后再同步"
        val st = c.state.value
        if (st is RecordingState.Recording) {
            _penSync.update { it.copy(visible = true, loading = false, rows = emptyList(), penUnavailable = false, penBusy = true, loadFailed = false) }
            return
        }
        _penSync.update { it.copy(visible = true, loading = true, penUnavailable = false, penBusy = false, loadFailed = false) }
        c.syncPenFiles { files ->
            penListTimeoutJob?.cancel()
            if (files == null) {
                // ★2.1.9:拉取失败/笔未连接 → 明说并给重试(旧版回空列表,被渲染成"笔里没东西",顾问一脸懵)
                _penSync.update { it.copy(loading = false, rows = emptyList(), loadFailed = true) }
                return@syncPenFiles
            }
            // 回调可能在非主线程；用 viewModelScope 切回协程上下文做后续网络查询。
            viewModelScope.launch { onPenFiles(files) }
        }
        // ★2.1.9 超时改成【进度感知】，不再一刀切：
        //   笔吐清单是分批的，文件多时要十几秒——只要还在陆续到达，就继续等并显示"已读到 N 段…"；
        //   只有【20s 后仍一条都没收到】或【读到一半彻底卡住 15s 不动】才判失败给重试。
        //   （引擎侧还有一层：8s 无回应自动重发命令 ×2，多数"命令被笔忙时吞掉"已在那层救回来。）
        penListTimeoutJob?.cancel()
        penListTimeoutJob = viewModelScope.launch {
            var waited = 0
            var lastProgress = 0
            var stalled = 0
            while (waited < 90_000) {
                delay(2_000); waited += 2_000
                val st0 = _penSync.value
                if (!st0.visible || !st0.loading) return@launch   // 已经拿到清单/关掉了
                val p = controller?.penFileListProgress() ?: 0
                if (p > lastProgress) { lastProgress = p; stalled = 0 } else stalled += 2_000
                if (waited >= 8_000 && p > 0) {
                    _penSync.update { it.copy(loadingHint = "笔里文件较多，正在读取（已读到 $p 段）…") }
                }
                if (p == 0 && waited >= 20_000) break            // 一条都没来 → 真失败
                if (p > 0 && stalled >= 15_000) break            // 读到一半彻底卡住 → 失败
            }
            if (_penSync.value.visible && _penSync.value.loading) {
                _penSync.update { it.copy(loading = false, rows = emptyList(), loadFailed = true, loadingHint = null) }
            }
        }
    }

    private suspend fun onPenFiles(files: List<PenFile>) {
        if (files.isEmpty()) {
            _penSync.update { it.copy(loading = false, rows = emptyList(), penUnavailable = false) }
            return
        }
        // E2：去重预检失败不再按本地标记瞎渲染——换机/重装会全按"新"引导重复导入，
        // 本机已传但服务端放行重导的截断件反被隐藏。失败就明说，给「重试」。
        // ★2.1.9 分批查重(每批 30)：笔里攒了几十段时,一次性把全部文件名塞进一个请求
        //   → 请求大、服务端查得久、弱网必超时(顾问看到的"网络异常")。拆批后每个请求都小而快。
        val statusByName: MutableMap<String, String?> = mutableMapOf()
        for (chunk in files.chunked(PEN_PREVIEW_BATCH)) {
            when (val r = repo.penSyncPreview(chunk.map { PenSyncQueryItem(name = it.name, ra = it.recordedAt.ifBlank { null }) })) {
                is ApiResult.Success ->
                    r.data.items.orEmpty().forEach { statusByName[it.name.orEmpty()] = it.status }
                is ApiResult.Failure -> {
                    // 任一批失败仍判失败(状态不全就不能瞎渲染,会重复导入),但只需重试一次、且请求已变小更容易成功
                    _penSync.update { it.copy(loading = false, rows = emptyList(), loadFailed = true) }
                    return
                }
            }
        }
        // ★★2.2.0 紧急下线（2026-07-13 事故）：这里原本按服务端预检的 uploaded/deleted 状态
        //   顺手删除笔上的文件——但服务端的判定【含"录音时刻相差90秒内"的模糊匹配】，
        //   它本是给"列表要不要显示"用的(误判顶多少显示一条,无害)，我却拿它当【删除依据】。
        //   真实事故：顾问蓝牙关掉后单独用笔录了30分钟，这段的开始时刻恰好落在前面几段测试录音的
        //   90秒窗口内 → 服务端回"uploaded" → 这里把【从未上传的原件】从笔里删了，音频永久丢失。
        //   删除不可逆，绝不能建立在模糊匹配上。改为：只删【我们自己完整上传成功、文件名精确匹配】的段
        //   （引擎侧 fullyUploadedNames + cleanupOldFiles 已经在做，安全且够用）。
        //   —— 服务端补上"精确匹配"标记之前，这里一律不删。

        val rows = files.map { f ->
            // 以服务端判定为准（本地 uploaded 标记只在服务端没提到该文件时兜底不了什么——没提到即当 new，
            // 上传侧仍有 pen_file 排重，最多被服务端吞掉一次重复，不会重复入库）。
            val st = statusByName[f.name] ?: "new"
            // 对齐网页端：未传【默认不勾】，由顾问自己选要传的（避免误把一堆机身片段全导进来）。
            PenSyncRow(file = f, status = st, selected = false)
        }
            // 只显示【未导入】的（已导入 / 已删除 都不展示）
            .filter { it.status == "new" }
            // 从最近的开始，从上往下（按录音时间倒序；时间为空的排末尾）
            .sortedByDescending { it.file.recordedAt }
        _penSync.update { it.copy(loading = false, rows = rows, page = 1, penUnavailable = false, loadFailed = false, loadingHint = null) }
    }

    /** 同步 sheet 翻页。 */
    fun penNextPage() = _penSync.update { if (it.page < it.totalPages) it.copy(page = it.page + 1) else it }
    fun penPrevPage() = _penSync.update { if (it.page > 1) it.copy(page = it.page - 1) else it }

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

    /** ★2.1.9：点日期头一键勾选/取消那一天（对齐 iOS）。那天全选中 → 再点取消全选。 */
    fun togglePenDay(day: String) {
        _penSync.update { s ->
            fun dayOf(r: PenSyncRow) = r.file.recordedAt.take(10).ifBlank { "时间未知" }
            val inDay = s.rows.filter { it.importable && dayOf(it) == day }
            if (inDay.isEmpty()) return@update s
            val turnOn = !inDay.all { it.selected }
            s.copy(rows = s.rows.map {
                if (it.importable && dayOf(it) == day) it.copy(selected = turnOn) else it
            })
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
