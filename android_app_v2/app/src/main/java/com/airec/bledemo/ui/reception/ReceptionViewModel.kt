package com.airec.bledemo.ui.reception

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.airec.bledemo.data.model.Customer
import com.airec.bledemo.data.model.TodayReception
import com.airec.bledemo.data.repo.ApiResult
import com.airec.bledemo.data.repo.ConsultantRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 今日接诊（warm_2 #reception）状态机。
 *
 * 职责：
 *  - 按选中日期拉今日接诊列表（[ConsultantRepository.todayReception]），每 20s 自动刷新（仅看「今天」时静默轮询）。
 *  - 改接诊日期（前一天 / 后一天 / 选日期，受补登窗口约束）。
 *  - 新增 / 补登：搜本公司顾客（[ConsultantRepository.customerLookup]，name/手机尾号/会员号）后选已有人，或填姓名+尾号建新人
 *    （[ConsultantRepository.addTodayReception]）。
 *  - 删除某条接诊（[ConsultantRepository.removeTodayReception]，后端会拦截「今天已绑录音」的人）。
 *
 * 日期一律走 [java.util.Calendar] / [SimpleDateFormat]（minSdk 24，不能用 java.time）。
 */
class ReceptionViewModel(
    private val repo: ConsultantRepository = ConsultantRepository(),
) : ViewModel() {

    private val _ui = MutableStateFlow(ReceptionUiState(date = todayStr()))
    val ui: StateFlow<ReceptionUiState> = _ui.asStateFlow()

    private var pollJob: Job? = null

    init {
        refresh()
        startAutoRefresh()
    }

    // ─────────────────────────── 列表加载 / 自动刷新 ───────────────────────────

    /** 手动刷新（带 loading 态）。 */
    fun refresh() = load(silent = false)

    private fun load(silent: Boolean) {
        viewModelScope.launch {
            if (!silent) _ui.update { it.copy(loading = true, errorMsg = null) }
            when (val r = repo.todayReception(_ui.value.date)) {
                is ApiResult.Success -> _ui.update {
                    it.copy(
                        loading = false,
                        items = r.data.items ?: emptyList(),
                        date = r.data.date ?: it.date,
                        errorMsg = null,
                        lastRefreshedAt = System.currentTimeMillis(),
                    )
                }
                is ApiResult.Failure -> _ui.update {
                    it.copy(loading = false, errorMsg = if (silent) it.errorMsg else r.message)
                }
            }
        }
    }

    /** 每 20s 静默刷新；仅当当前看的是「今天」时轮询（看历史补登日不打扰）。 */
    private fun startAutoRefresh() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                delay(AUTO_REFRESH_MS)
                if (_ui.value.isToday) load(silent = true)
            }
        }
    }

    // ─────────────────────────── 改接诊日期 ───────────────────────────

    fun selectDate(date: String) {
        if (date == _ui.value.date) return
        _ui.update { it.copy(date = date) }
        refresh()
    }

    fun prevDay() = shiftDay(-1)

    /** 后一天：不允许越过今天。 */
    fun nextDay() {
        if (_ui.value.isToday) return
        shiftDay(+1)
    }

    private fun shiftDay(delta: Int) {
        val target = addDays(_ui.value.date, delta) ?: return
        // 不越过今天，也不早于补登窗口下界
        if (cmpDate(target, todayStr()) > 0) return
        if (cmpDate(target, minBackfillDate) < 0) return
        selectDate(target)
    }

    /** 回到今天（对齐 web setReceptionDate(_todayStr())）。 */
    fun goToday() = selectDate(todayStr())

    val minBackfillDate: String get() = addDays(todayStr(), -BACKFILL_DAYS_BACK) ?: todayStr()
    val maxDate: String get() = todayStr()

    // ─────────────────────────── 改接诊日期（每条）───────────────────────────

    fun openEditDate(item: TodayReception) {
        // 原日期回退到当前查看日期（web 用 x.service_date）
        _ui.update { it.copy(editDate = EditDateState(item = item, target = item.serviceDate ?: it.date)) }
    }

    /** 不可改期的行点了日期 chip：弹一句原因，别让按钮看起来坏了。 */
    fun onEditDateBlocked(item: TodayReception) {
        val reason = item.editDateBlockReason ?: return
        _ui.update { it.copy(toast = reason) }
    }

    fun closeEditDate() {
        _ui.update { it.copy(editDate = null) }
    }

    fun onEditDatePicked(date: String) {
        _ui.update { st -> st.editDate?.let { st.copy(editDate = it.copy(target = date, errorMsg = null)) } ?: st }
    }

    /**
     * 改某条接诊的接诊日期。后端会自动解绑挂该接诊的录音；对已锁定/已分析返回 409、
     * 目标日已有同顾客返回 409——这些后端 error 文案直接抛回弹层。
     */
    fun confirmEditDate() {
        val s = _ui.value.editDate ?: return
        if (s.submitting) return
        val orig = s.item.serviceDate
        // 与原日期相同：直接关（对齐 web confirmEditDate）
        if (orig != null && s.target == orig) {
            closeEditDate()
            return
        }
        viewModelScope.launch {
            _ui.update { st -> st.editDate?.let { st.copy(editDate = it.copy(submitting = true, errorMsg = null)) } ?: st }
            when (val r = repo.changeTodayReceptionDate(s.item.id, s.target)) {
                is ApiResult.Success -> {
                    _ui.update { it.copy(editDate = null, toast = "已改接诊日期") }
                    refresh()
                }
                is ApiResult.Failure -> _ui.update { st ->
                    st.editDate?.let { st.copy(editDate = it.copy(submitting = false, errorMsg = r.message)) } ?: st
                }
            }
        }
    }

    // ─────────────────────────── 新增 / 补登弹层 ───────────────────────────

    fun openAddSheet() {
        _ui.update {
            it.copy(
                addSheet = AddSheetState(targetDate = it.date),
            )
        }
    }

    fun closeAddSheet() {
        _ui.update { it.copy(addSheet = null) }
    }

    fun switchAddMode(mode: AddMode) {
        _ui.update { st -> st.addSheet?.let { st.copy(addSheet = it.copy(mode = mode, errorMsg = null)) } ?: st }
    }

    fun onSearchQueryChange(q: String) {
        _ui.update { st -> st.addSheet?.let { st.copy(addSheet = it.copy(query = q)) } ?: st }
        doSearch(q)
    }

    private var searchJob: Job? = null
    private fun doSearch(q: String) {
        searchJob?.cancel()
        if (q.isBlank()) {
            _ui.update { st -> st.addSheet?.let { st.copy(addSheet = it.copy(results = emptyList(), searching = false)) } ?: st }
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            _ui.update { st -> st.addSheet?.let { st.copy(addSheet = it.copy(searching = true)) } ?: st }
            // 对齐 web doSearch：走 customer_lookup（name/手机尾号/会员号三字段搜，返回 phone_tail）
            when (val r = repo.customerLookup(q)) {
                is ApiResult.Success -> _ui.update { st ->
                    st.addSheet?.let { st.copy(addSheet = it.copy(results = r.data, searching = false)) } ?: st
                }
                is ApiResult.Failure -> _ui.update { st ->
                    st.addSheet?.let { st.copy(addSheet = it.copy(searching = false, errorMsg = r.message)) } ?: st
                }
            }
        }
    }

    /** 选已有顾客加入今日接诊。 */
    fun addExisting(customer: Customer) {
        val cid = customer.cid ?: return
        val date = _ui.value.addSheet?.targetDate ?: _ui.value.date
        submitAdd { repo.addTodayReception(customerId = cid, date = date) }
    }

    fun onNewNameChange(v: String) {
        _ui.update { st -> st.addSheet?.let { st.copy(addSheet = it.copy(newName = v)) } ?: st }
    }

    fun onNewPhoneTailChange(v: String) {
        val digits = v.filter { it.isDigit() }.take(4)
        _ui.update { st -> st.addSheet?.let { st.copy(addSheet = it.copy(newPhoneTail = digits)) } ?: st }
    }

    fun onNewMemberCardChange(v: String) {
        _ui.update { st -> st.addSheet?.let { st.copy(addSheet = it.copy(newMemberCard = v)) } ?: st }
    }

    /** 新建顾客并加入今日接诊。 */
    fun addNew() {
        val s = _ui.value.addSheet ?: return
        val name = s.newName.trim()
        val tail = s.newPhoneTail.trim()
        if (name.isEmpty()) {
            setAddError("请填写顾客姓名")
            return
        }
        if (tail.length != 4) {
            setAddError("请填写手机尾号（4 位数字）")
            return
        }
        val card = s.newMemberCard.trim().ifEmpty { null }
        submitAdd {
            repo.addTodayReception(
                name = name,
                phoneTail = tail,
                memberCard = card,
                date = s.targetDate,
            )
        }
    }

    private fun submitAdd(block: suspend () -> ApiResult<*>) {
        if (_ui.value.addSheet?.submitting == true) return
        viewModelScope.launch {
            _ui.update { st -> st.addSheet?.let { st.copy(addSheet = it.copy(submitting = true, errorMsg = null)) } ?: st }
            when (val r = block()) {
                is ApiResult.Success -> {
                    _ui.update { it.copy(addSheet = null, toast = "已加入今日接诊") }
                    refresh()
                }
                is ApiResult.Failure -> _ui.update { st ->
                    st.addSheet?.let { st.copy(addSheet = it.copy(submitting = false, errorMsg = r.message)) } ?: st
                }
            }
        }
    }

    private fun setAddError(msg: String) {
        _ui.update { st -> st.addSheet?.let { st.copy(addSheet = it.copy(errorMsg = msg)) } ?: st }
    }

    // ─────────────────────────── 删除 ───────────────────────────

    fun askRemove(item: TodayReception) {
        _ui.update { it.copy(confirmRemove = item) }
    }

    fun cancelRemove() {
        _ui.update { it.copy(confirmRemove = null) }
    }

    fun confirmRemove() {
        val item = _ui.value.confirmRemove ?: return
        viewModelScope.launch {
            _ui.update { it.copy(confirmRemove = null) }
            when (val r = repo.removeTodayReception(item.id)) {
                is ApiResult.Success -> {
                    _ui.update { it.copy(toast = "已移出今日接诊") }
                    refresh()
                }
                is ApiResult.Failure -> _ui.update { it.copy(toast = r.message) }
            }
        }
    }

    fun toastShown() {
        _ui.update { it.copy(toast = null) }
    }

    companion object {
        private const val AUTO_REFRESH_MS = 20_000L
        private const val SEARCH_DEBOUNCE_MS = 300L
        private const val BACKFILL_DAYS_BACK = 7   // 与后端 webapp.py BACKFILL_DAYS_BACK 对齐

        private val fmt: SimpleDateFormat
            get() = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        fun todayStr(): String = fmt.format(Date())

        /** date(YYYY-MM-DD) 加 delta 天；解析失败返回 null。 */
        fun addDays(date: String, delta: Int): String? {
            val d = runCatching { fmt.parse(date) }.getOrNull() ?: return null
            val cal = Calendar.getInstance().apply {
                time = d
                add(Calendar.DAY_OF_MONTH, delta)
            }
            return fmt.format(cal.time)
        }

        /** a 与 b 比较：a>b 返回正、相等 0、a<b 负。解析失败按字符串比。 */
        fun cmpDate(a: String, b: String): Int {
            val da = runCatching { fmt.parse(a) }.getOrNull()
            val db = runCatching { fmt.parse(b) }.getOrNull()
            return if (da != null && db != null) da.compareTo(db) else a.compareTo(b)
        }

        /** date − today 的天数差（负=过去、正=未来）；解析失败返回 null。对齐 web _diffDaysFromToday。 */
        fun diffDaysFromToday(date: String): Int? {
            val d = runCatching { fmt.parse(date) }.getOrNull() ?: return null
            val t = runCatching { fmt.parse(todayStr()) }.getOrNull() ?: return null
            return Math.round((d.time - t.time) / 86_400_000.0).toInt()
        }
    }
}

/** 改接诊日期弹层状态。 */
data class EditDateState(
    val item: TodayReception,
    val target: String,
    val submitting: Boolean = false,
    val errorMsg: String? = null,
) {
    /** 已绑录音段数（>0 时确认会自动解绑，给个提示）。 */
    val recordingCount: Int get() = item.recordingCount ?: 0

    /** 可选日期下界：原日期 − 7 天（对齐 web openEditDate）。 */
    val minDate: String get() = item.serviceDate?.let { ReceptionViewModel.addDays(it, -7) } ?: ReceptionViewModel.todayStr()

    /** 可选日期上界：今天（不能选未来）。 */
    val maxDate: String get() = ReceptionViewModel.todayStr()
}

/**
 * 某条接诊是否「可改日期 / 可移除」：未完成且未运行分析，且接诊日在最近 7 天内（含今天）。
 * 对齐 web `editable = !isDone && !isRunning && _canEditOnDate(service_date)`。
 */
val TodayReception.editable: Boolean
    get() {
        val st = analysisStatus
        val isDone = st == "done"
        val isRunning = st == "running" || st == "queued"
        if (isDone || isRunning) return false
        val d = serviceDate ?: return false
        val diff = ReceptionViewModel.diffDaysFromToday(d) ?: return false
        return diff in -7..0
    }

/** 不可改期时的原因（点 chip 时弹给用户，对齐后端 409/400），可改返回 null。 */
val TodayReception.editDateBlockReason: String?
    get() {
        val st = analysisStatus
        if (st == "done") return "已完成分析，不能再改接诊日期"
        if (st == "running" || st == "queued") return "正在分析中，不能改接诊日期"
        val d = serviceDate ?: return "缺少接诊日期，暂不能修改"
        val diff = ReceptionViewModel.diffDaysFromToday(d) ?: return "接诊日期异常，暂不能修改"
        if (diff !in -7..0) return "只能修改最近 7 天内的接诊日期"
        return null
    }

/** 新增弹层的两种模式：搜已有顾客 / 建新顾客。 */
enum class AddMode { Existing, New }

data class AddSheetState(
    val targetDate: String,
    val mode: AddMode = AddMode.Existing,
    // 搜已有
    val query: String = "",
    val results: List<Customer> = emptyList(),
    val searching: Boolean = false,
    // 建新人
    val newName: String = "",
    val newPhoneTail: String = "",
    val newMemberCard: String = "",
    // 公共
    val submitting: Boolean = false,
    val errorMsg: String? = null,
)

data class ReceptionUiState(
    val date: String,
    val items: List<TodayReception> = emptyList(),
    val loading: Boolean = false,
    val errorMsg: String? = null,
    val lastRefreshedAt: Long = 0L,
    val addSheet: AddSheetState? = null,
    val confirmRemove: TodayReception? = null,
    val editDate: EditDateState? = null,
    val toast: String? = null,
) {
    val isToday: Boolean get() = date == ReceptionViewModel.todayStr()
}
