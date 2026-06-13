package com.airec.bledemo.ui.admin.ops

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.airec.bledemo.data.model.OpsDashboardResponse
import com.airec.bledemo.data.repo.AdminRepository
import com.airec.bledemo.data.repo.ApiResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 运营看板 ViewModel（管理台 · GET /api/admin/ops_dashboard）。
 *
 * SWR：init 即拉一次；下拉/重试调 [load] 重拉。保留已有数据做"刷新中"叠加（loading 期间不清空旧数据）。
 * 默认视图（不带 store_id）；门店筛选后续 wave 再加（接口已留 storeId 形参）。
 */
class AdminOpsViewModel(
    private val repo: AdminRepository = AdminRepository(),
) : ViewModel() {

    private val _state = MutableStateFlow(AdminOpsUiState())
    val state: StateFlow<AdminOpsUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val r = repo.opsDashboard()) {
                is ApiResult.Success -> {
                    val d = r.data
                    // 后端在无权/未登录时也可能回 200 + {"error":...}（防御性处理）
                    if (d.error != null && d.todayStats == null && d.companies == null) {
                        _state.update { it.copy(loading = false, error = d.error) }
                    } else {
                        _state.update { it.copy(loading = false, error = null, data = d) }
                    }
                }
                is ApiResult.Failure -> _state.update { it.copy(loading = false, error = r.message) }
            }
        }
    }
}

/**
 * 运营看板 UI 状态。
 *
 * @param data ops_dashboard 响应（admin/店长 走 today_stats…；super 走 companies）
 */
data class AdminOpsUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val data: OpsDashboardResponse? = null,
) {
    /** 首次加载（还没有任何数据）。 */
    val isInitialLoading: Boolean get() = loading && data == null
}
