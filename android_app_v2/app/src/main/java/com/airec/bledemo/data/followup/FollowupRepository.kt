package com.airec.bledemo.data.followup

import com.airec.bledemo.data.auth.CredentialStore
import com.airec.bledemo.data.model.FuLoginReq
import com.airec.bledemo.data.model.FuQuota
import com.airec.bledemo.data.net.NetworkModule
import com.airec.bledemo.data.repo.ApiResult
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response

/**
 * 回访话术 / 高情商话术数据仓库(followup-agent,按 [ScriptSystem] 实例化)。
 *
 * 统一密码静默登录:用工牌缓存的手机号+密码调 POST /login/account(密码字段叫 key)。
 * JWT 7 天、无单设备互踢 → 401 只有过期一种,静默重登一次再重试。
 *
 * 生成走 SSE(POST /generate, stream=true):
 *   data: {"content": "增量"} | data: [DONE] | data: {"error": "..."}
 *   data: {"compliance_restart": {"words": [...]}}  ← 命中违禁词服务端重写,客户端清空已收内容
 *   data: {"compliance_warning": {"words": [...]}}  ← 结尾残留敏感词告警
 */
class FollowupRepository(
    private val sys: ScriptSystem,
    private val creds: CredentialStore = CredentialStore(),
) {
    private val api get() = FollowupModule.api(sys)

    /** 生成流事件(followup 与 chat 模块共用此形状)。 */
    sealed class GenEvent {
        data class Content(val delta: String) : GenEvent()
        /** 命中违禁词、服务端已中止并自动重写:UI 应清空已收文本重新累积。 */
        data class Restart(val words: List<String>) : GenEvent()
        /** 重写后仍残留的敏感词告警(附在结果尾部提示即可)。 */
        data class Warning(val words: List<String>) : GenEvent()
        data class Failed(val message: String) : GenEvent()
        object Done : GenEvent()
    }

    // ─────────────────────────── 登录 ───────────────────────────

    suspend fun ensureLogin(): ApiResult<String> {
        FollowupModule.token(sys)?.let { return ApiResult.Success(it) }
        return loginLocked()
    }

    private suspend fun loginLocked(): ApiResult<String> = loginMutex.withLock {
        FollowupModule.token(sys)?.let { return ApiResult.Success(it) }
        // 账号绑定优先:老顾问的回访/高情商历史数据在独立 staff 账号名下(/api/me 下发),
        // 用它登录数据才接得上;没绑定的(新开号)退回统一密码。
        val me = com.airec.bledemo.data.auth.AuthManager.lastMe
        val bindU = me?.scriptAccount?.takeIf { it.isNotBlank() }
        val bindP = me?.scriptPassword?.takeIf { it.isNotBlank() }
        val u = bindU ?: creds.username()?.takeIf { it.isNotBlank() }
        val p = (if (bindU != null) bindP else creds.password())?.takeIf { it.isNotBlank() }
        if (u == null || p == null) return ApiResult.Failure("本机没有登录凭证，请退出后重新登录一次")
        return try {
            val resp = api.login(FuLoginReq(u, p))
            val body = resp.body()
            when {
                resp.isSuccessful && !body?.token.isNullOrBlank() -> {
                    FollowupModule.setToken(sys, body!!.token)
                    ApiResult.Success(body.token!!)
                }
                resp.code() == 401 -> ApiResult.Failure(
                    "${sys.displayName}密码与工牌不一致，请联系管理员同步账号", resp.code(),
                )
                else -> ApiResult.Failure(
                    errorText(resp) ?: "${sys.displayName}登录失败（HTTP ${resp.code()}）", resp.code(),
                )
            }
        } catch (e: Exception) {
            ApiResult.Failure(e.message ?: "网络异常", cause = e)
        }
    }

    // ─────────────────────────── 业务 ───────────────────────────

    /** 本月额度。 */
    suspend fun quota(): ApiResult<FuQuota> = withAuth { api.quota(it) }

    /** 按公司动态下发的表单选项(类目 → 选项数组)。 */
    suspend fun projectOptions(): ApiResult<Map<String, List<String>>> =
        withAuth { api.projectOptions(it) }

    /** 顾问名单(顾问姓名/所属门店自动带出)。 */
    suspend fun employees(): ApiResult<com.airec.bledemo.data.model.FuEmployees> =
        withAuth { api.employees(it) }

    /** 改密(统一密码同步)。 */
    suspend fun changePassword(old: String, new: String): ApiResult<okhttp3.ResponseBody> =
        withAuth { api.changePassword(it, mapOf("old_password" to old, "new_password" to new)) }

    /** 导入历史:顾客档案列表。 */
    suspend fun customers(): ApiResult<com.airec.bledemo.data.model.FuCustomers> =
        withAuth { api.customers(it) }

    /** 收藏/取消收藏。 */
    suspend fun favorite(name: String, favorited: Boolean): ApiResult<okhttp3.ResponseBody> =
        withAuth { api.favoriteCustomer(it, name, mapOf("favorited" to favorited)) }

    /** 战果登记(写最新版本)。body 字段对齐服务端 BusinessRequest。 */
    suspend fun recordBusiness(name: String, versionIdx: Int, body: Map<String, Any?>): ApiResult<okhttp3.ResponseBody> =
        withAuth { api.recordBusiness(it, name, versionIdx, body) }

    /** 生成后保存顾客+话术(与网页端历史互通;失败不打扰,fire-and-forget)。 */
    suspend fun saveCustomer(name: String, data: Map<String, Any?>, script: String, model: String) {
        runCatching {
            withAuth {
                api.saveCustomer(
                    it,
                    mapOf(
                        "name" to name,
                        "savedAt" to java.text.SimpleDateFormat(
                            "yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US,
                        ).format(java.util.Date()),
                        "data" to data,
                        "lastScript" to script,
                        "favorited" to false,
                        "model" to model,
                    ),
                )
            }
        }
    }

    /**
     * SSE 流式生成。收集期间逐事件回调;取消收集即断开连接。
     * @param customerData 表单数据(字段名对齐网页 collectData,服务端原样喂 prompt)
     */
    fun generateStream(
        customerData: Map<String, Any?>,
        model: String = DEFAULT_MODEL,
    ): Flow<GenEvent> = flow {
        // 1) 确保 token(带一次 401 重登)
        var token = when (val r = ensureLogin()) {
            is ApiResult.Success -> r.data
            is ApiResult.Failure -> {
                emit(GenEvent.Failed(r.message)); return@flow
            }
        }

        val bodyJson = mapAdapter.toJson(
            mapOf("model" to model, "customer_data" to customerData, "stream" to true),
        )

        fun buildReq(t: String) = Request.Builder()
            .url(sys.baseUrl.trimEnd('/') + "/generate")
            .header("Authorization", "Bearer $t")
            .header("Accept", "text/event-stream")
            .post(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        var resp = FollowupModule.sseClient.newCall(buildReq(token)).execute()
        if (resp.code == 401) {
            resp.close()
            FollowupModule.setToken(sys, null)
            token = when (val r = loginLocked()) {
                is ApiResult.Success -> r.data
                is ApiResult.Failure -> {
                    emit(GenEvent.Failed(r.message)); return@flow
                }
            }
            resp = FollowupModule.sseClient.newCall(buildReq(token)).execute()
        }

        resp.use { response ->
            if (!response.isSuccessful) {
                val raw = runCatching { response.body?.string() }.getOrNull()
                emit(GenEvent.Failed(sseErrorText(raw) ?: "生成失败（HTTP ${response.code}）"))
                return@flow
            }
            val source = response.body?.source() ?: run {
                emit(GenEvent.Failed("响应为空")); return@flow
            }
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]") {
                    emit(GenEvent.Done); return@flow
                }
                val map = runCatching { mapAdapter.fromJson(payload) }.getOrNull() ?: continue
                when {
                    map.containsKey("content") -> {
                        emit(GenEvent.Content(map["content"]?.toString().orEmpty()))
                        // 高情商变体把残留敏感词直接附在 content 帧里(compliance_warn_words)
                        (map["compliance_warn_words"] as? List<*>)?.let { w ->
                            if (w.isNotEmpty()) emit(GenEvent.Warning(w.map { it.toString() }))
                        }
                    }
                    map.containsKey("error") -> emit(GenEvent.Failed(map["error"]?.toString() ?: "生成出错"))
                    map.containsKey("compliance_restart") -> emit(GenEvent.Restart(wordsOf(map["compliance_restart"])))
                    map.containsKey("compliance_warning") -> emit(GenEvent.Warning(wordsOf(map["compliance_warning"])))
                }
            }
            // 流被服务端断开而没给 [DONE]:当作完成(已收内容仍可用)
            emit(GenEvent.Done)
        }
    }.flowOn(Dispatchers.IO)

    // ─────────────────────────── 通用 ───────────────────────────

    private suspend fun <T> withAuth(call: suspend (bearer: String) -> Response<T>): ApiResult<T> {
        var token = when (val r = ensureLogin()) {
            is ApiResult.Success -> r.data
            is ApiResult.Failure -> return r
        }
        return try {
            var resp = call("Bearer $token")
            if (resp.code() == 401) {
                FollowupModule.setToken(sys, null)
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

    private fun <T> errorText(resp: Response<T>): String? =
        sseErrorText(runCatching { resp.errorBody()?.string() }.getOrNull())

    private fun sseErrorText(raw: String?): String? = try {
        if (raw.isNullOrBlank()) null
        else mapAdapter.fromJson(raw)?.let { (it["error"] ?: it["detail"]) as? String }
    } catch (_: Exception) {
        null
    }

    @Suppress("UNCHECKED_CAST")
    private fun wordsOf(v: Any?): List<String> =
        ((v as? Map<String, Any?>)?.get("words") as? List<*>)?.map { it.toString() } ?: emptyList()

    companion object {
        const val DEFAULT_MODEL = "claude_sonnet"
        private val loginMutex = Mutex()
        private val mapAdapter by lazy {
            NetworkModule.moshi.adapter<Map<String, Any?>>(
                Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java),
            ).lenient()
        }
    }
}
