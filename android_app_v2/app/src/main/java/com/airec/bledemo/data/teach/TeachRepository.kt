package com.airec.bledemo.data.teach

import com.airec.bledemo.data.api.TeachApi
import com.airec.bledemo.data.auth.CredentialStore
import com.airec.bledemo.data.model.TeachCheckinResp
import com.airec.bledemo.data.model.TeachHeartbeatReq
import com.airec.bledemo.data.model.TeachHeartbeatResp
import com.airec.bledemo.data.model.TeachLoginReq
import com.airec.bledemo.data.model.TeachQuizResp
import com.airec.bledemo.data.model.TeachQuizSubmitReq
import com.airec.bledemo.data.model.TeachStats
import com.airec.bledemo.data.model.TeachSubmitResp
import com.airec.bledemo.data.net.NetworkModule
import com.airec.bledemo.data.repo.ApiResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.Response

/**
 * teach 网课数据仓库（多系统整合 P1）。
 *
 * 核心是「统一密码静默登录」：老板拍板 App 登录工牌时缓存的手机号+密码（[CredentialStore]）
 * 与 teach 是同一套，进网课模块时用它悄悄换 Bearer token，顾问全程无感、不用二次登录。
 *
 * 401 处理：teach 是单设备策略（每次登录踢旧 session），App 的 token 可能被网页端登录挤掉；
 * 收到 401 就静默重登一次再重试原请求（与工牌上传 401 自动重登同思路）。
 * 登录本身加 [Mutex]，并发请求同时 401 时只登一次，避免互相踢。
 */
class TeachRepository(
    private val api: TeachApi = TeachModule.api,
    private val creds: CredentialStore = CredentialStore(),
) {

    // ─────────────────────────── 登录 / token ───────────────────────────

    /**
     * 确保有 token（无则用统一密码静默登录）。
     * 失败文案面向顾问：凭证缺失/密码不同步/账号停用都直说，别让人对着转圈猜。
     */
    suspend fun ensureLogin(): ApiResult<String> {
        TeachModule.token?.let { return ApiResult.Success(it) }
        return loginLocked()
    }

    private suspend fun loginLocked(): ApiResult<String> = loginMutex.withLock {
        // 拿锁期间别人可能已登好
        TeachModule.token?.let { return ApiResult.Success(it) }

        val u = creds.username()?.takeIf { it.isNotBlank() }
        val p = creds.password()?.takeIf { it.isNotBlank() }
        if (u == null || p == null) {
            return ApiResult.Failure("本机没有登录凭证，请退出后重新登录一次")
        }
        return try {
            val resp = api.login(TeachLoginReq(u, p))
            val body = resp.body()
            when {
                resp.isSuccessful && !body?.token.isNullOrBlank() -> {
                    TeachModule.token = body!!.token
                    ApiResult.Success(body.token!!)
                }
                resp.code() == 401 -> ApiResult.Failure(
                    "网课密码与工牌不一致，请联系管理员同步账号", resp.code(),
                )
                resp.code() == 403 -> ApiResult.Failure(
                    errorText(resp) ?: "网课账号已停用，请联系管理员", resp.code(),
                )
                else -> ApiResult.Failure(errorText(resp) ?: "网课登录失败（HTTP ${resp.code()}）", resp.code())
            }
        } catch (e: Exception) {
            ApiResult.Failure(e.message ?: "网络异常", cause = e)
        }
    }

    // ─────────────────────────── 业务接口 ───────────────────────────

    /** 课程列表 + 进度 + 打卡 + 学习时长。成功后缓存进 [TeachModule.lastStats]。 */
    suspend fun stats(): ApiResult<TeachStats> =
        withAuth { api.stats(it) }.also { r ->
            (r as? ApiResult.Success)?.data?.let { TeachModule.lastStats = it }
        }

    /** 每日打卡（幂等）。 */
    suspend fun checkin(): ApiResult<TeachCheckinResp> = withAuth { api.checkin(it) }

    /** 学习时长心跳（读课件期间每 30s 发；服务端 25s 节流，偶发丢弃无妨）。 */
    suspend fun heartbeat(chapter: String): ApiResult<TeachHeartbeatResp> =
        withAuth { api.heartbeat(it, TeachHeartbeatReq(chapter)) }

    /** 取一套测验题。 */
    suspend fun quiz(chapter: String, quizIndex: Int): ApiResult<TeachQuizResp> =
        withAuth { api.quiz(it, chapter, quizIndex) }

    /** 提交测验。 */
    suspend fun submitQuiz(chapter: String, quizIndex: Int, answers: List<String>): ApiResult<TeachSubmitResp> =
        withAuth { api.submitQuiz(it, TeachQuizSubmitReq(chapter, quizIndex, answers)) }

    /** 改密(统一密码同步)。 */
    suspend fun changePassword(old: String, new: String): ApiResult<okhttp3.ResponseBody> =
        withAuth { api.changePassword(it, mapOf("old_password" to old, "new_password" to new)) }

    // ─────────────────────────── 通用包装 ───────────────────────────

    /**
     * 带鉴权调用：确保 token → 请求 → 401 则重登一次重试 → 解析。
     * 服务端业务错误统一是 {"error": "..."}，尽量取给用户看。
     */
    private suspend fun <T> withAuth(call: suspend (bearer: String) -> Response<T>): ApiResult<T> {
        var token = when (val r = ensureLogin()) {
            is ApiResult.Success -> r.data
            is ApiResult.Failure -> return r
        }
        return try {
            var resp = call("Bearer $token")
            if (resp.code() == 401) {
                // token 过期 / 被其它设备挤下线 → 静默重登一次
                TeachModule.token = null
                token = when (val r = loginLocked()) {
                    is ApiResult.Success -> r.data
                    is ApiResult.Failure -> return r
                }
                resp = call("Bearer $token")
            }
            val body = resp.body()
            when {
                resp.isSuccessful && body != null -> ApiResult.Success(body)
                resp.isSuccessful -> ApiResult.Failure("响应为空", resp.code())
                else -> ApiResult.Failure(errorText(resp) ?: "请求失败（HTTP ${resp.code()}）", resp.code())
            }
        } catch (e: Exception) {
            ApiResult.Failure(e.message ?: "网络异常", cause = e)
        }
    }

    /** 从错误响应体里取 {"error": "..."} 文案（取不到返回 null）。 */
    private fun <T> errorText(resp: Response<T>): String? = try {
        val raw = resp.errorBody()?.string()
        if (raw.isNullOrBlank()) null
        else {
            val map = NetworkModule.moshi
                .adapter<Map<String, Any?>>(
                    com.squareup.moshi.Types.newParameterizedType(
                        Map::class.java, String::class.java, Any::class.java,
                    ),
                )
                .lenient()
                .fromJson(raw)
            map?.get("error") as? String
        }
    } catch (_: Exception) {
        null
    }

    private companion object {
        /** 登录互斥：并发 401 只重登一次（teach 每次登录会换 session_id，并发登会互相踢）。 */
        val loginMutex = Mutex()
    }
}
