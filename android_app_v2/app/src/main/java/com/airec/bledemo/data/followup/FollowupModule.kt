package com.airec.bledemo.data.followup

import com.airec.bledemo.data.api.FollowupApi
import com.airec.bledemo.data.net.NetworkModule
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 回访话术 / 高情商话术 —— 同一套 followup-agent 代码的两处部署。
 * App 里做成**一个模块、两个系统实例**:除 base URL / 名称 / key 外全部复用。
 */
enum class ScriptSystem(
    val key: String,          // /api/me systems 里的 key
    val baseUrl: String,
    val displayName: String,
) {
    Followup("followup", "https://www.aibeautyfulwomen.com/", "回访话术"),
    HighEq("higheq", "http://43.136.130.133/", "高情商话术"),
    ;

    companion object {
        fun byKey(key: String): ScriptSystem? = entries.firstOrNull { it.key == key }
    }
}

/**
 * followup-agent 网络单例(按系统各持一份 Retrofit + token)。
 *
 * token:JWT 7 天、无单设备互踢,进程内缓存;401(过期)由 Repository 静默重登。
 * 工牌退出登录时 [clear](统一密码换人,全部作废)。
 */
object FollowupModule {

    private val apis = ConcurrentHashMap<String, FollowupApi>()
    private val tokens = ConcurrentHashMap<String, String>()

    /** SSE 生成专用 client:LLM 首字节可能 3-10s、整篇几十秒,readTimeout 放到 200s。 */
    val sseClient: OkHttpClient by lazy {
        NetworkModule.okHttpClient.newBuilder()
            .readTimeout(200, TimeUnit.SECONDS)
            .build()
    }

    fun api(sys: ScriptSystem): FollowupApi = apis.getOrPut(sys.key) {
        Retrofit.Builder()
            .baseUrl(sys.baseUrl)
            .client(NetworkModule.okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(NetworkModule.moshi).asLenient())
            .build()
            .create(FollowupApi::class.java)
    }

    fun token(sys: ScriptSystem): String? = tokens[sys.key]
    fun setToken(sys: ScriptSystem, token: String?) {
        if (token == null) tokens.remove(sys.key) else tokens[sys.key] = token
    }

    /** 工牌退出登录 / 换账号时调。 */
    fun clear() = tokens.clear()
}
