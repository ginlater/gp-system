package com.airec.bledemo.ui.customer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
 * 「客户」搜索屏状态机 —— 对齐 redesign 原型 #custsearch（方案A 新增 tab）。
 *
 * 搜本公司顾客 → 进 TA 的历史陪伴与画像（[CustomerDetailScreen]）。
 *  - 列表/搜索：GET /api/customers/search（[ConsultantRepository.customersSearch]，去抖 ~300ms）
 *  - 空关键字默认：GET /api/consultant/customer_lookup（[ConsultantRepository.customerLookup]）拉一批本人可见顾客，
 *    进 tab 即有内容、不是空白屏。
 *
 * 红线：对外一律「陪伴」系词汇，绝不出现录音/录制（meta 里「陪伴 N 次」靠模型字段才显示，
 * 现有 [Customer] 不带次数 → 此屏 meta 省略次数，仅 [CustomerDetailScreen] 用 session_count 展示）。
 */

/** 「客户」搜索屏 UI 状态。 */
data class CustomerListState(
    /** 搜索结果 / 默认顾客列表。 */
    val customers: List<Customer> = emptyList(),
    /** 搜索关键字（姓名 / 会员卡号）。 */
    val query: String = "",
    val loading: Boolean = false,
    val error: String? = null,
)

class CustomerViewModel(
    private val repo: ConsultantRepository = ConsultantRepository(),
) : ViewModel() {

    private val _state = MutableStateFlow(CustomerListState())
    val state: StateFlow<CustomerListState> = _state.asStateFlow()

    /** 搜索去抖任务。 */
    private var searchJob: Job? = null

    init {
        // 进客户 tab 即展示一批本人可见顾客（空关键字默认），不必先输入再搜。
        loadDefault()
    }

    /**
     * 空关键字默认列表：拉一批本人可见顾客（customer_lookup(null)）。
     * stale-while-revalidate：有数据时不清空，只置 loading。
     */
    fun loadDefault() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val r = repo.customerLookup(null)) {
                is ApiResult.Success ->
                    _state.update { it.copy(loading = false, error = null, customers = r.data) }
                is ApiResult.Failure ->
                    _state.update { it.copy(loading = false, error = r.message) }
            }
        }
    }

    /** 搜索输入变更：去抖 ~300ms → 空则回默认列表，否则 customersSearch。 */
    fun setQuery(q: String) {
        _state.update { it.copy(query = q) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(300)
            if (q.isBlank()) {
                loadDefault()
            } else {
                search(q)
            }
        }
    }

    private fun search(q: String) {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val r = repo.customersSearch(q)) {
                is ApiResult.Success ->
                    _state.update { it.copy(loading = false, error = null, customers = r.data) }
                is ApiResult.Failure ->
                    _state.update { it.copy(loading = false, error = r.message) }
            }
        }
    }
}
