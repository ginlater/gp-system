package com.airec.bledemo.data.chat

import com.airec.bledemo.data.auth.CredentialStore
import com.airec.bledemo.data.net.NetworkModule
import com.airec.bledemo.data.repo.ApiResult
import com.squareup.moshi.Json
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
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

/* ===================================================================
 * 扣子销售话术(sale-agent,chat.aibeautyfulwomen.com)。
 *
 * 契约(2026-07-15 上服务器核对):
 *  - POST /api/login {username,password} → {token,username},JWT 24h,无单设备互踢;
 *  - POST /api/generate_stream {prompt,mode:"free"} → SSE:
 *      data: {"type":"delta","content":…} 增量(内嵌【思考】/【话术】字面标记)
 *      data: {"type":"compliance_restart","words":[…]} 违禁词重写,清空重收
 *      data: {"type":"compliance_warning","words":[…]}
 *      data: {"type":"done","reply","thinking","script","history_id"} 终帧(已切好三段)
 *      data: {"type":"error","message"}
 *  - GET /api/quota → {username,used,total};429 = 额度用尽。
 *  - 无会话上下文(单轮生成器),不用管 conversation id。
 * =================================================================== */

data class ChatLoginReq(
    @Json(name = "username") val username: String,
    @Json(name = "password") val password: String,
)

data class ChatLoginResp(
    @Json(name = "token") val token: String? = null,
    @Json(name = "username") val username: String? = null,
    @Json(name = "detail") val detail: String? = null,
)

data class ChatQuota(
    @Json(name = "username") val username: String? = null,
    @Json(name = "used") val used: Int? = null,
    @Json(name = "total") val total: Int? = null,
)

interface ChatApi {
    @POST("api/login")
    suspend fun login(@Body req: ChatLoginReq): Response<ChatLoginResp>

    @GET("api/quota")
    suspend fun quota(@Header("Authorization") bearer: String): Response<ChatQuota>

    /** 改密(统一密码同步用)。体 {old_password,new_password}。 */
    @POST("api/change-password")
    suspend fun changePassword(
        @Header("Authorization") bearer: String,
        @Body body: Map<String, @JvmSuppressWildcards Any?>,
    ): Response<okhttp3.ResponseBody>
}

/** sale-agent 网络单例。 */
object ChatModule {
    const val BASE_URL = "https://chat.aibeautyfulwomen.com/"

    val api: ChatApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(NetworkModule.okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(NetworkModule.moshi).asLenient())
            .build()
            .create(ChatApi::class.java)
    }

    @Volatile
    var token: String? = null

    fun clear() {
        token = null
    }
}

/** 扣子销售话术数据仓库:统一密码静默登录 + SSE 流式生成。 */
class ChatRepository(
    private val api: ChatApi = ChatModule.api,
    private val creds: CredentialStore = CredentialStore(),
) {

    sealed class ChatEvent {
        data class Delta(val content: String) : ChatEvent()
        data class Restart(val words: List<String>) : ChatEvent()
        data class Warning(val words: List<String>) : ChatEvent()
        /** 终帧:服务端已把 思考/话术 切好。 */
        data class Done(val reply: String?, val thinking: String?, val script: String?) : ChatEvent()
        data class Failed(val message: String) : ChatEvent()
    }

    suspend fun ensureLogin(): ApiResult<String> {
        ChatModule.token?.let { return ApiResult.Success(it) }
        return loginLocked()
    }

    private suspend fun loginLocked(): ApiResult<String> = loginMutex.withLock {
        ChatModule.token?.let { return ApiResult.Success(it) }
        val u = creds.username()?.takeIf { it.isNotBlank() }
        val p = creds.password()?.takeIf { it.isNotBlank() }
        if (u == null || p == null) return ApiResult.Failure("本机没有登录凭证，请退出后重新登录一次")
        return try {
            val resp = api.login(ChatLoginReq(u, p))
            val body = resp.body()
            when {
                resp.isSuccessful && !body?.token.isNullOrBlank() -> {
                    ChatModule.token = body!!.token
                    ApiResult.Success(body.token!!)
                }
                resp.code() == 401 -> ApiResult.Failure("销售话术密码与工牌不一致，请联系管理员同步账号", resp.code())
                else -> ApiResult.Failure(errorDetail(resp) ?: "销售话术登录失败（HTTP ${resp.code()}）", resp.code())
            }
        } catch (e: Exception) {
            ApiResult.Failure(e.message ?: "网络异常", cause = e)
        }
    }

    /** 额度({used}/{total})。 */
    suspend fun quota(): ApiResult<ChatQuota> {
        var token = when (val r = ensureLogin()) {
            is ApiResult.Success -> r.data
            is ApiResult.Failure -> return r
        }
        return try {
            var resp = api.quota("Bearer $token")
            if (resp.code() == 401) {
                ChatModule.token = null
                token = when (val r = loginLocked()) {
                    is ApiResult.Success -> r.data
                    is ApiResult.Failure -> return r
                }
                resp = api.quota("Bearer $token")
            }
            val body = resp.body()
            if (resp.isSuccessful && body != null) ApiResult.Success(body)
            else ApiResult.Failure(errorDetail(resp) ?: "请求失败（HTTP ${resp.code()}）", resp.code())
        } catch (e: Exception) {
            ApiResult.Failure(e.message ?: "网络异常", cause = e)
        }
    }

    /** 改密(统一密码同步)。 */
    suspend fun changePassword(old: String, new: String): ApiResult<okhttp3.ResponseBody> {
        var token = when (val r = ensureLogin()) {
            is ApiResult.Success -> r.data
            is ApiResult.Failure -> return r
        }
        return try {
            var resp = api.changePassword("Bearer $token", mapOf("old_password" to old, "new_password" to new))
            if (resp.code() == 401) {
                ChatModule.token = null
                token = when (val r = loginLocked()) {
                    is ApiResult.Success -> r.data
                    is ApiResult.Failure -> return r
                }
                resp = api.changePassword("Bearer $token", mapOf("old_password" to old, "new_password" to new))
            }
            val body = resp.body()
            if (resp.isSuccessful && body != null) ApiResult.Success(body)
            else ApiResult.Failure(errorDetail(resp) ?: "改密失败（HTTP ${resp.code()}）", resp.code())
        } catch (e: Exception) {
            ApiResult.Failure(e.message ?: "网络异常", cause = e)
        }
    }

    /** SSE 流式生成(单轮,无上下文)。取消收集即断开。 */
    fun generateStream(prompt: String): Flow<ChatEvent> = flow {
        var token = when (val r = ensureLogin()) {
            is ApiResult.Success -> r.data
            is ApiResult.Failure -> {
                emit(ChatEvent.Failed(r.message)); return@flow
            }
        }

        val bodyJson = mapAdapter.toJson(mapOf("prompt" to prompt, "mode" to "free"))
        fun buildReq(t: String) = Request.Builder()
            .url(ChatModule.BASE_URL + "api/generate_stream")
            .header("Authorization", "Bearer $t")
            .header("Accept", "text/event-stream")
            .post(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        var resp = com.airec.bledemo.data.followup.FollowupModule.sseClient.newCall(buildReq(token)).execute()
        if (resp.code == 401) {
            resp.close()
            ChatModule.token = null
            token = when (val r = loginLocked()) {
                is ApiResult.Success -> r.data
                is ApiResult.Failure -> {
                    emit(ChatEvent.Failed(r.message)); return@flow
                }
            }
            resp = com.airec.bledemo.data.followup.FollowupModule.sseClient.newCall(buildReq(token)).execute()
        }

        resp.use { response ->
            if (!response.isSuccessful) {
                val raw = runCatching { response.body?.string() }.getOrNull()
                val msg = runCatching { mapAdapter.fromJson(raw ?: "")?.get("detail") as? String }.getOrNull()
                emit(ChatEvent.Failed(msg ?: "生成失败（HTTP ${response.code}）"))
                return@flow
            }
            val source = response.body?.source() ?: run {
                emit(ChatEvent.Failed("响应为空")); return@flow
            }
            var doneEmitted = false
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                val map = runCatching { mapAdapter.fromJson(payload) }.getOrNull() ?: continue
                when (map["type"]) {
                    "delta" -> emit(ChatEvent.Delta(map["content"]?.toString().orEmpty()))
                    "compliance_restart" -> emit(ChatEvent.Restart(words(map)))
                    "compliance_warning" -> emit(ChatEvent.Warning(words(map)))
                    "done" -> {
                        doneEmitted = true
                        emit(
                            ChatEvent.Done(
                                reply = map["reply"] as? String,
                                thinking = map["thinking"] as? String,
                                script = map["script"] as? String,
                            ),
                        )
                        return@flow
                    }
                    "error" -> emit(ChatEvent.Failed(map["message"]?.toString() ?: "生成出错"))
                }
            }
            if (!doneEmitted) emit(ChatEvent.Done(null, null, null))
        }
    }.flowOn(Dispatchers.IO)

    private fun <T> errorDetail(resp: Response<T>): String? = try {
        val raw = resp.errorBody()?.string()
        if (raw.isNullOrBlank()) null else mapAdapter.fromJson(raw)?.get("detail") as? String
    } catch (_: Exception) {
        null
    }

    private fun words(map: Map<String, Any?>): List<String> =
        (map["words"] as? List<*>)?.map { it.toString() } ?: emptyList()

    private companion object {
        val loginMutex = Mutex()
        val mapAdapter by lazy {
            NetworkModule.moshi.adapter<Map<String, Any?>>(
                Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java),
            ).lenient()
        }
    }
}
