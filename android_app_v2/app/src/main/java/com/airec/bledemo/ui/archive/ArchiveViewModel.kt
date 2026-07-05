package com.airec.bledemo.ui.archive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.airec.bledemo.data.model.SessionRow
import com.airec.bledemo.data.model.StatusCounts
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
 * 「美丽档案」状态机 —— 对齐 web index.html(`/` 页) 的接诊/会话列表。
 *
 * 顾问只看到自己名下的接诊（服务端按 advisor_name 自动隔离），每行 → 一份分析报告。
 *  - 列表：GET /api/sessions（服务端分页 page_size=10）
 *  - 状态筛选 pill 计数：GET /api/sessions/status_counts
 *
 * 进档案立刻展示数据（init 即拉第一页 + 计数），不是空态、不需要先搜索。
 * 红线：对外一律「接诊记录 / 分析报告 / 查看报告 / 点评」，绝不出现录音/录制字样。
 */

/** 接诊列表（美丽档案）UI 状态。 */
data class ArchiveListState(
    val sessions: List<SessionRow> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val pageSize: Int = 10,
    /** 状态桶筛选；null = 全部。 */
    val statusFilter: String? = null,
    /** 各状态桶计数（pill 用）；null = 尚未拉到。 */
    val statusCounts: StatusCounts? = null,
    /** 顾客姓名搜索关键字。 */
    val customerQuery: String = "",
    // ── ⏱时间筛选（对齐 index.html 的 ⏱时间 toggle）──
    /** ⏱时间筛选是否展开。展开才显示时间类型/日期/时间从-到。 */
    val timeFilterOpen: Boolean = false,
    /** 时间类型：recording=录音时间 / analysis=最后分析时间（默认 recording）。 */
    val timeType: String = "recording",
    /** 日期 'YYYY-MM-DD'，null=未选。 */
    val date: String? = null,
    /** 时间从 'HH:MM'，null=未选。 */
    val timeFrom: String? = null,
    /** 时间到 'HH:MM'，null=未选。 */
    val timeTo: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
    /** 一次性轻提示（取消分析成功/失败等）；屏内底部条消费后回 clearToast。 */
    val toast: ArchiveToast? = null,
) {
    /** 总页数（至少 1）。 */
    val totalPages: Int get() = if (total <= 0) 1 else (total + pageSize - 1) / pageSize
}

/** 屏内一次性提示：danger=true 走玫瑰底（失败），否则墨底（成功）。 */
data class ArchiveToast(val text: String, val danger: Boolean = false)

/** 状态筛选 pill 选项：value=后端桶名(null=全部) + 展示文案。与 index.html 的 .filter-pills 一致。 */
data class StatusFilterOption(val value: String?, val label: String)

val ArchiveStatusFilters: List<StatusFilterOption> = listOf(
    StatusFilterOption(null, "全部"),
    StatusFilterOption("running", "分析中"),
    StatusFilterOption("queued", "排队中"),
    StatusFilterOption("done", "已完成"),
    StatusFilterOption("failed", "失败"),
    StatusFilterOption("stuck", "中断"),
    StatusFilterOption("idle", "未分析"),
)

class ArchiveViewModel(
    private val repo: ConsultantRepository = ConsultantRepository(),
) : ViewModel() {

    private val _state = MutableStateFlow(ArchiveListState())
    val state: StateFlow<ArchiveListState> = _state.asStateFlow()

    /** 搜索去抖任务。 */
    private var searchJob: Job? = null

    init {
        // 进档案即展示本人接诊（第一页）+ 计数，不必先输入再搜。
        load(1)
        loadStatusCounts()
    }

    /**
     * 拉某页接诊。stale-while-revalidate：有数据时不清空列表，只置 loading，
     * 避免翻页/切筛选时白屏闪烁。
     */
    fun load(page: Int) {
        val cur = _state.value
        _state.update { it.copy(loading = true, error = null, page = page) }
        viewModelScope.launch {
            when (val r = repo.sessions(
                page = page,
                pageSize = cur.pageSize,
                customer = cur.customerQuery,
                status = cur.statusFilter,
                date = cur.date,
                timeFrom = cur.timeFrom,
                timeTo = cur.timeTo,
                timeType = cur.timeType,
            )) {
                is ApiResult.Success -> {
                    val d = r.data
                    if (d.error != null) {
                        _state.update { it.copy(loading = false, error = d.error) }
                    } else {
                        _state.update {
                            it.copy(
                                loading = false,
                                error = null,
                                sessions = d.sessions ?: emptyList(),
                                total = d.total ?: 0,
                                page = d.page ?: page,
                                pageSize = d.pageSize ?: it.pageSize,
                            )
                        }
                    }
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(loading = false, error = r.message) }
            }
        }
    }

    /** 拉状态 pill 计数（与列表同款 customer/时间筛选，对齐 refreshCounts）。失败静默：pill 仍可见，只是无数字。 */
    fun loadStatusCounts() {
        val cur = _state.value
        viewModelScope.launch {
            when (val r = repo.sessionStatusCounts(
                customer = cur.customerQuery,
                date = cur.date,
                timeFrom = cur.timeFrom,
                timeTo = cur.timeTo,
                timeType = cur.timeType,
            )) {
                is ApiResult.Success ->
                    if (r.data.error == null) {
                        _state.update { it.copy(statusCounts = r.data.counts) }
                    }
                is ApiResult.Failure -> { /* 计数失败不打扰主流程 */ }
            }
        }
    }

    /** 点状态 pill：设置筛选，回到第 1 页并刷新计数（同一筛选重复点不重拉）。 */
    fun setStatus(bucket: String?) {
        if (bucket == _state.value.statusFilter) return
        _state.update { it.copy(statusFilter = bucket) }
        load(1)
        loadStatusCounts()
    }

    /** 顾客搜索输入变更：去抖 ~300ms → 回第 1 页 + 刷新计数。 */
    fun setCustomerQuery(q: String) {
        _state.update { it.copy(customerQuery = q) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(300)
            load(1)
            loadStatusCounts()
        }
    }

    // ─────────────── ⏱时间筛选（对齐 index.html toggleTimeFilter / resetFilter）───────────────

    /**
     * 切换 ⏱时间筛选展开/收起。
     * 收起时按 web `toggleTimeFilter` 语义：清空 date/time_from/time_to 并回第 1 页 + 刷新计数；
     * 展开仅亮出输入区，不改已选值（保持与 web 一致：展开本身不触发请求）。
     */
    fun toggleTimeFilter() {
        val opening = !_state.value.timeFilterOpen
        if (opening) {
            _state.update { it.copy(timeFilterOpen = true) }
        } else {
            _state.update { it.copy(timeFilterOpen = false, date = null, timeFrom = null, timeTo = null) }
            load(1)
            loadStatusCounts()
        }
    }

    /** 时间类型 recording/analysis 切换 → 回第 1 页 + 刷新计数（仅在有 date/time 时才真正改变结果，对齐后端）。 */
    fun setTimeType(type: String) {
        if (type == _state.value.timeType) return
        _state.update { it.copy(timeType = type) }
        load(1)
        loadStatusCounts()
    }

    /** 选/清日期（'YYYY-MM-DD' 或 null）→ 回第 1 页 + 刷新计数。 */
    fun setDate(date: String?) {
        _state.update { it.copy(date = date?.takeIf { d -> d.isNotBlank() }) }
        load(1)
        loadStatusCounts()
    }

    /** 选/清「时间从」（'HH:MM' 或 null）→ 回第 1 页 + 刷新计数。 */
    fun setTimeFrom(time: String?) {
        _state.update { it.copy(timeFrom = time?.takeIf { t -> t.isNotBlank() }) }
        load(1)
        loadStatusCounts()
    }

    /** 选/清「时间到」（'HH:MM' 或 null）→ 回第 1 页 + 刷新计数。 */
    fun setTimeTo(time: String?) {
        _state.update { it.copy(timeTo = time?.takeIf { t -> t.isNotBlank() }) }
        load(1)
        loadStatusCounts()
    }

    /** 重置全部筛选（对齐 web resetFilter）：清顾客 + 时间区间 + 时间类型(→recording) + 状态(→全部) + 收起时间筛选，回第 1 页。 */
    fun resetFilter() {
        searchJob?.cancel()
        _state.update {
            it.copy(
                customerQuery = "",
                statusFilter = null,
                timeFilterOpen = false,
                timeType = "recording",
                date = null,
                timeFrom = null,
                timeTo = null,
            )
        }
        load(1)
        loadStatusCounts()
    }

    /**
     * 取消分析（running/queued 行）。成功后重拉当前页 + 计数（对齐 web cancelAnalysis）。
     * 失败不再静默：把后端文案塞进 state.toast（屏内底部条），对齐 web 的 alert(e.message)。
     * 二次确认由屏内 dialog 负责，这里只做实际请求。
     */
    fun cancelAnalysis(sessionId: Long) {
        viewModelScope.launch {
            when (val r = repo.cancelAnalysis(sessionId)) {
                is ApiResult.Success -> {
                    _state.update { it.copy(toast = ArchiveToast("已取消分析")) }
                    load(_state.value.page)
                    loadStatusCounts()
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(toast = ArchiveToast(r.message ?: "取消失败，请重试", danger = true)) }
            }
        }
    }

    /** 行级申请删除：按 session 申请删其下所有录音（走管理员审批）。reason 可空。 */
    fun requestDelete(sessionId: Long, reason: String?) {
        viewModelScope.launch {
            when (val r = repo.sessionDeleteRequest(sessionId, reason?.takeIf { it.isNotBlank() })) {
                is ApiResult.Success -> {
                    // D10：<5分钟免审批直删时后端回 deleted=true——行已消失，别再说"待审批"
                    val msg = if (r.data.deleted == true) "已删除" else "已申请删除，待审批"
                    _state.update { it.copy(toast = ArchiveToast(msg)) }
                    load(_state.value.page)
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(toast = ArchiveToast(r.message ?: "申请失败，请重试", danger = true)) }
            }
        }
    }

    /** 屏内底部条消费完一次性提示后清掉，避免重组重复弹。 */
    fun onToastShown() {
        _state.update { it.copy(toast = null) }
    }

    /** 翻页：在 [1, totalPages] 范围内 page+delta。 */
    fun goPage(delta: Int) {
        val cur = _state.value
        val next = (cur.page + delta).coerceIn(1, cur.totalPages)
        if (next != cur.page) load(next)
    }

    /** 下拉/手动刷新：重拉当前页 + 计数。 */
    fun refresh() {
        load(_state.value.page)
        loadStatusCounts()
    }
}
