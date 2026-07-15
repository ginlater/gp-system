package com.airec.bledemo.ui.teach

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.airec.bledemo.data.model.TeachStats
import com.airec.bledemo.data.repo.ApiResult
import com.airec.bledemo.data.teach.TeachRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * teach 网课首页 ViewModel：静默登录 + /teach/me/stats（课程列表/进度/打卡/时长）+ 打卡。
 *
 * 静默登录失败（凭证缺失/密码不同步/账号停用）作为整页错误展示 + 重试；
 * 从课件/测验页返回时（ON_RESUME）静默刷新一次，测验分数与解锁状态即时更新。
 */
class TeachHomeViewModel(
    private val repo: TeachRepository = TeachRepository(),
) : ViewModel() {

    private val _state = MutableStateFlow(TeachHomeUiState())
    val state: StateFlow<TeachHomeUiState> = _state.asStateFlow()

    init {
        load()
    }

    /**
     * 拉课程与统计。
     * @param silent true = 后台刷新（不清空已展示内容、不整页转圈；失败也不打扰）
     */
    fun load(silent: Boolean = false) {
        if (_state.value.loading) return
        if (!silent) _state.update { it.copy(loading = it.stats == null, error = null) }
        viewModelScope.launch {
            when (val r = repo.stats()) {
                is ApiResult.Success -> _state.update { it.copy(loading = false, error = null, stats = r.data) }
                is ApiResult.Failure -> _state.update {
                    if (silent && it.stats != null) it.copy(loading = false)
                    else it.copy(loading = false, error = r.message)
                }
            }
        }
    }

    /** 每日打卡（幂等）。成功后就地更新打卡卡片，不整页刷新。 */
    fun checkin() {
        if (_state.value.checkinBusy) return
        _state.update { it.copy(checkinBusy = true) }
        viewModelScope.launch {
            when (val r = repo.checkin()) {
                is ApiResult.Success -> _state.update { st ->
                    val stats = st.stats?.copy(
                        checkin = st.stats.checkin?.copy(
                            todaySigned = r.data.todaySigned ?: true,
                            streak = r.data.streak ?: st.stats.checkin.streak,
                            totalDays = r.data.totalDays ?: st.stats.checkin.totalDays,
                        ),
                    )
                    st.copy(checkinBusy = false, stats = stats ?: st.stats, toast = "打卡成功，已连续 ${r.data.streak ?: 1} 天")
                }
                is ApiResult.Failure -> _state.update { it.copy(checkinBusy = false, toast = r.message) }
            }
        }
    }

    /** Screen 冒完 toast 后回执清空。 */
    fun toastShown() {
        _state.update { it.copy(toast = null) }
    }
}

/**
 * teach 首页 UI 状态。
 *
 * @param loading 整页加载中（首次进入）
 * @param error 整页错误（静默登录失败/拉取失败），非空时展示错误 + 重试
 * @param stats 课程列表 + 打卡 + 学习时长
 * @param checkinBusy 打卡请求进行中（按钮防重复）
 * @param toast 一次性提示文案（打卡结果等），Screen 冒完调 [TeachHomeViewModel.toastShown]
 */
data class TeachHomeUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val stats: TeachStats? = null,
    val checkinBusy: Boolean = false,
    val toast: String? = null,
)
