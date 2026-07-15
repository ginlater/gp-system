package com.airec.bledemo.data.kpi

import com.airec.bledemo.data.net.NetworkModule
import com.airec.bledemo.data.repo.ApiResult
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * KPI 积分(kpi.beautyshining.com)桥接:把工牌顾问姓名映射到 KPI 员工的拼音账号,
 * 使「KPI积分」员工端也能免登录(用统一密码 123456 自动登)。
 *
 * 为什么要映射:KPI 员工账号是拼音(lilele 等),与工牌手机号账号无共同字段,
 * 唯一桥梁是中文姓名。姓名→账号 的对照由管理后台 /api/admin/employees 提供。
 * 该请求用 App 内置的管理员账号(与 kpi_admin 格子同一份,不额外增加密码泄露面),
 * 走独立 OkHttp(独立 cookie),不污染工牌/WebView 的会话。
 *
 * 安全:员工密码统一 123456 是该系统既有的弱口令,非本模块引入;映射只含姓名+拼音账号,
 * 不含密码。若日后收紧,应改为 KPI 服务端提供「按姓名换员工会话」的受控接口。
 */
object KpiModule {
    const val BASE_URL = "https://kpi.beautyshining.com/"
    const val ADMIN_USER = "diaojie"
    const val ADMIN_PASS = "123456"
    const val STAFF_PASS = "123456"

    /** 独立 cookie 的 client(拉映射用管理员会话,与工牌/网页壳互不干扰)。 */
    val client: OkHttpClient by lazy {
        NetworkModule.okHttpClient.newBuilder()
            .cookieJar(MemoryCookieJar())
            .build()
    }

    /** 极简内存 CookieJar(仅本模块两个同 host 请求间传 session,进程内)。 */
    private class MemoryCookieJar : CookieJar {
        private val byName = LinkedHashMap<String, Cookie>()
        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { byName[it.name] = it }
        }
        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> = byName.values.toList()
    }

    @Volatile
    var nameToUsername: Map<String, String>? = null

    fun clear() {
        nameToUsername = null
    }
}

/** 姓名 → 拼音账号 解析(供 KPI 员工端自动登录)。 */
class KpiRepository {

    /**
     * 返回该顾问对应的 KPI 拼音账号;查不到 / 拉取失败一律返回空串(降级到手动登录页,不报错)。
     */
    suspend fun resolveUsername(advisorName: String?): ApiResult<String> {
        val name = advisorName?.trim().orEmpty()
        if (name.isBlank()) return ApiResult.Success("")
        val map = ensureMap() ?: return ApiResult.Success("")
        return ApiResult.Success(map[name].orEmpty())
    }

    private suspend fun ensureMap(): Map<String, String>? {
        KpiModule.nameToUsername?.let { return it }
        return mutex.withLock {
            KpiModule.nameToUsername?.let { return it }
            withContext(Dispatchers.IO) { fetchMap() }?.also { KpiModule.nameToUsername = it }
        }
    }

    /** 管理员登录 + 拉员工名单,建 name→username。任何异常返回 null(交由调用方降级)。 */
    private fun fetchMap(): Map<String, String>? = try {
        val loginResp = KpiModule.client.newCall(
            Request.Builder()
                .url(KpiModule.BASE_URL + "api/admin/login")
                .post(
                    mapAdapter.toJson(mapOf("username" to KpiModule.ADMIN_USER, "password" to KpiModule.ADMIN_PASS))
                        .toRequestBody("application/json; charset=utf-8".toMediaType()),
                )
                .build(),
        ).execute()
        loginResp.close()
        if (!loginResp.isSuccessful) null
        else {
            KpiModule.client.newCall(
                Request.Builder().url(KpiModule.BASE_URL + "api/admin/employees").build(),
            ).execute().use { r ->
                val body = r.body?.string()
                if (!r.isSuccessful || body == null) null
                else listAdapter.fromJson(body)
                    ?.mapNotNull { e ->
                        val n = e["name"] as? String
                        val u = e["username"] as? String
                        if (!n.isNullOrBlank() && !u.isNullOrBlank()) n to u else null
                    }
                    ?.toMap()
            }
        }
    } catch (_: Exception) {
        null
    }

    private companion object {
        val mutex = Mutex()
        val mapAdapter by lazy {
            NetworkModule.moshi.adapter<Map<String, Any?>>(
                Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java),
            )
        }
        val listAdapter by lazy {
            NetworkModule.moshi.adapter<List<Map<String, Any?>>>(
                Types.newParameterizedType(
                    List::class.java,
                    Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java),
                ),
            ).lenient()
        }
    }
}
