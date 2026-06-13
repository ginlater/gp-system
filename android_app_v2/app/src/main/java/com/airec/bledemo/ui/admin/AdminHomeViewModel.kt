package com.airec.bledemo.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.airec.bledemo.data.auth.AuthManager
import com.airec.bledemo.data.model.Me
import com.airec.bledemo.data.repo.ApiResult
import com.airec.bledemo.data.repo.ConsultantRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 管理台首页 ViewModel。
 *
 * 职责很轻：
 *  - 拉管理员信息 [Me]（顶部展示姓名 + 角色；复用 /api/me）。失败不阻断（卡片名退回默认）。
 *  - 退出登录 [AuthManager.logout]（先 /logout 再清本地 Cookie，失败也清本地），完成后回调切回登录。
 *
 * 取实例：Composable 里 `viewModel()` 默认无参构造即可。
 */
class AdminHomeViewModel(
    private val repo: ConsultantRepository = ConsultantRepository(),
    private val auth: AuthManager = AuthManager(),
) : ViewModel() {

    private val _state = MutableStateFlow(AdminHomeUiState())
    val state: StateFlow<AdminHomeUiState> = _state.asStateFlow()

    init {
        loadMe()
    }

    fun loadMe() {
        _state.update { it.copy(meLoading = true) }
        viewModelScope.launch {
            when (val r = repo.me()) {
                is ApiResult.Success -> _state.update { it.copy(me = r.data, meLoading = false) }
                is ApiResult.Failure -> _state.update { it.copy(meLoading = false) }
            }
        }
    }

    /** 退出登录。完成后触发 [onDone]（上层把导航起点切回登录）。 */
    fun logout(onDone: () -> Unit) {
        if (_state.value.loggingOut) return
        _state.update { it.copy(loggingOut = true) }
        viewModelScope.launch {
            auth.logout()
            _state.update { it.copy(loggingOut = false) }
            onDone()
        }
    }
}

/**
 * 管理台首页 UI 状态。
 *
 * @param me 管理员信息（顶部姓名/角色）
 * @param loggingOut 正在退出登录（按钮置 loading / 防重复）
 */
data class AdminHomeUiState(
    val me: Me? = null,
    val meLoading: Boolean = false,
    val loggingOut: Boolean = false,
) {
    /** 顶部展示名；缺省退回用户名 / 默认。 */
    val displayName: String
        get() = me?.advisorName?.takeIf { it.isNotBlank() }
            ?: me?.username?.takeIf { it.isNotBlank() }
            ?: "管理员"

    /** 角色中文。 */
    val roleLabel: String
        get() = when (me?.role) {
            "super" -> "超级管理员"
            "admin" -> "管理员"
            "store_manager" -> "店长"
            else -> "管理员"
        }

    /** 顶部头像首字。 */
    val avatarChar: String
        get() = displayName.trim().take(1).ifBlank { "管" }
}
