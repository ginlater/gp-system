package com.airec.bledemo.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.airec.bledemo.data.auth.AuthManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 登录屏状态。
 *
 * @param username 账号输入（工号 / 手机号）
 * @param password 密码输入
 * @param loading 登录请求进行中（按钮转圈、输入禁用）
 * @param error 顶部错误提示；null 表示无错误
 * @param loggedIn 登录成功（一次性信号，Screen 据此回调 onLoggedIn）
 */
data class LoginUiState(
    val username: String = "",
    val password: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val loggedIn: Boolean = false,
) {
    /** 账号、密码都非空且不在请求中，方可点「登录」。 */
    val canSubmit: Boolean
        get() = username.isNotBlank() && password.isNotEmpty() && !loading
}

/**
 * 登录（SPEC §4.1 / warm_2 #login）。
 *
 * 调 [AuthManager.login]（内部 POST /login 存 Cookie + GET /api/me 实判会话）。
 * 任意有效角色（顾问 / 店长 / 管理员 / 超管）登录成功 → loggedIn=true（由 GateScreen 再按角色分流）；
 * 仅凭证错误 / 网络异常给到顶部错误提示。
 */
class LoginViewModel(
    private val auth: AuthManager = AuthManager(),
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun onUsernameChange(value: String) {
        _state.update { it.copy(username = value, error = null) }
    }

    fun onPasswordChange(value: String) {
        _state.update { it.copy(password = value, error = null) }
    }

    fun login() {
        val current = _state.value
        if (!current.canSubmit) return

        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            @Suppress("DEPRECATION")
            when (val result = auth.login(current.username, current.password)) {
                // 四类有效角色（含 admin/super）都算登录成功；进 App 后由 GateScreen 按角色分流。
                is AuthManager.LoginResult.Success,
                is AuthManager.LoginResult.NotConsultant ->
                    _state.update { it.copy(loading = false, loggedIn = true) }

                is AuthManager.LoginResult.InvalidCredentials ->
                    _state.update { it.copy(loading = false, error = result.message) }

                is AuthManager.LoginResult.Error ->
                    _state.update {
                        it.copy(loading = false, error = result.message.ifBlank { "登录失败，请稍后重试" })
                    }
            }
        }
    }

    /** 登录成功信号已被 Screen 消费，避免重组时重复回调。 */
    fun onLoggedInHandled() {
        _state.update { it.copy(loggedIn = false) }
    }
}
