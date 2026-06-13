package com.airec.bledemo.data.auth

import com.airec.bledemo.data.api.ConsultantApi
import com.airec.bledemo.data.model.Me
import com.airec.bledemo.data.net.NetworkModule
import com.airec.bledemo.data.net.PrefsCookieJar

/**
 * 登录态管理。
 *
 * 登录走 POST /login（表单 x-www-form-urlencoded，字段 username + password，见 webapp.py /login）。
 * 成功后服务端 302 重定向 + Set-Cookie，PrefsCookieJar 自动持久化，无需手动存 token。
 *
 * 登录"成功"判定：/login 失败时仍返回 200 渲染 login.html（带"用户名或密码错误"），
 * 因此不能只看 HTTP 码——登录后用 GET /api/me 校验是否真的拿到会话。
 */
class AuthManager(
    private val api: ConsultantApi = NetworkModule.api,
    private val cookieJar: PrefsCookieJar = NetworkModule.cookieJar,
) {

    sealed class LoginResult {
        /** 登录成功，附带 /api/me。 */
        data class Success(val me: Me) : LoginResult()
        /** 用户名/密码错误，或登录后 /api/me 仍 401。 */
        data class InvalidCredentials(val message: String) : LoginResult()
        /** 已弃用：admin/super 现在也放行（统一返回 [Success]，由 GateScreen 按角色路由）。保留以免破坏调用方 when 穷尽。 */
        @Deprecated("admin/super 现已放行，登录一律返回 Success；按角色分流交给 GateScreen")
        data class NotConsultant(val me: Me) : LoginResult()
        /** 网络/服务异常。 */
        data class Error(val message: String, val cause: Throwable? = null) : LoginResult()
    }

    /**
     * 登录。成功后 Cookie 已落盘；返回 /api/me 用于上层取 advisor_name / role。
     */
    suspend fun login(username: String, password: String): LoginResult {
        return try {
            val resp = api.login(username.trim(), password)
            // /login 表单错误也回 200，故不以此判定；统一用 /api/me 校验会话。
            if (!resp.isSuccessful) {
                return LoginResult.Error("登录请求失败：HTTP ${resp.code()}")
            }
            when (val meResult = fetchMe()) {
                is MeResult.Ok -> {
                    // 四类有效角色（consultant / store_manager / admin / super）一律放行；
                    // 进 App 后由 GateScreen 按 role 路由（顾问端主壳 vs 管理台）。
                    LoginResult.Success(meResult.me)
                }
                is MeResult.Unauthorized -> {
                    // 没拿到会话 → 用户名或密码错误
                    cookieJar.clear()
                    LoginResult.InvalidCredentials("用户名或密码错误")
                }
                is MeResult.Error -> LoginResult.Error(meResult.message, meResult.cause)
            }
        } catch (e: Exception) {
            LoginResult.Error(e.message ?: "网络异常", e)
        }
    }

    /** 退出登录：调用 /logout 并清空本地 Cookie（即便 /logout 失败也清本地）。 */
    suspend fun logout() {
        try {
            api.logout()
        } catch (_: Exception) {
            // 忽略：本地清空才是关键
        } finally {
            cookieJar.clear()
        }
    }

    /** 粗判：本地是否还有未过期会话 Cookie（无网时也可用，决定是否进登录页）。 */
    fun isLoggedIn(): Boolean = cookieJar.hasSessionCookie()

    /** 用 /api/me 实判登录态（有网时更准）。返回 null 表示未登录/异常。 */
    suspend fun currentUser(): Me? =
        when (val r = fetchMe()) {
            is MeResult.Ok -> r.me
            else -> null
        }

    private sealed class MeResult {
        data class Ok(val me: Me) : MeResult()
        object Unauthorized : MeResult()
        data class Error(val message: String, val cause: Throwable? = null) : MeResult()
    }

    private suspend fun fetchMe(): MeResult {
        return try {
            val resp = api.me()
            when {
                resp.code() == 401 -> MeResult.Unauthorized
                resp.isSuccessful -> {
                    val me = resp.body()
                    if (me == null || me.error != null || me.id == null) MeResult.Unauthorized
                    else MeResult.Ok(me)
                }
                else -> MeResult.Error("HTTP ${resp.code()}")
            }
        } catch (e: Exception) {
            MeResult.Error(e.message ?: "网络异常", e)
        }
    }
}
