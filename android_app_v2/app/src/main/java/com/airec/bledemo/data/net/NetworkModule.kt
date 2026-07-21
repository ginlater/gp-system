package com.airec.bledemo.data.net

import android.content.Context
import com.airec.bledemo.data.api.AdminApi
import com.airec.bledemo.data.api.ConsultantApi
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

/**
 * 网络单例：OkHttpClient(含 PrefsCookieJar + logging-interceptor) + Moshi(反射) + Retrofit。
 *
 * - base = https://gp.beautyshining.com（SPEC §7，不改后端）。
 * - Moshi 用 KotlinJsonAdapterFactory 反射（不接 KSP/codegen）。
 * - Cookie 会话由 PrefsCookieJar 持久化；登录后自动带。
 * - 必须先 init(context) 一次（Application.onCreate 里调），再取 api / cookieJar。
 */
object NetworkModule {

    const val BASE_URL = "https://gp.beautyshining.com/"

    /** 接诊上传地址（手机麦 + 录音笔补传共用）的单一事实源：base + api/consultant/upload。 */
    val uploadUrl: String get() = BASE_URL.trimEnd('/') + "/api/consultant/upload"

    @Volatile
    private var initialized = false

    /** Application Context（CredentialStore 等需要，init 时存）。 */
    lateinit var appContext: Context
        private set

    lateinit var cookieJar: PrefsCookieJar
        private set

    lateinit var moshi: Moshi
        private set

    lateinit var okHttpClient: OkHttpClient
        private set

    lateinit var retrofit: Retrofit
        private set

    lateinit var api: ConsultantApi
        private set

    /** 管理台端点（运营看板等）。同一 retrofit/cookie，会话 Cookie 自动带。 */
    lateinit var adminApi: AdminApi
        private set

    @Synchronized
    fun init(context: Context) {
        if (initialized) return

        appContext = context.applicationContext
        cookieJar = PrefsCookieJar(context.applicationContext)

        val logging = HttpLoggingInterceptor().apply {
            // 仅记录请求行 + 头；不打 body（音频/转写体积大、含隐私）。
            level = HttpLoggingInterceptor.Level.HEADERS
        }

        okHttpClient = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)   // 上传音频留足时间
            .followRedirects(true)                 // /login 成功是 302，跟随后能拿 Set-Cookie
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()

        moshi = Moshi.Builder()
            // 报告(analysis_result)逐字段容错解析，单个 AI 飘类型的字段不连累整份 → 必须在反射工厂之前
            .add(SessionReportAdapterFactory())
            .add(KotlinJsonAdapterFactory())       // Kotlin data class 反射适配
            .build()

        retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi).asLenient())
            .build()

        api = retrofit.create(ConsultantApi::class.java)
        adminApi = retrofit.create(AdminApi::class.java)
        initialized = true
    }

    fun isInitialized(): Boolean = initialized
}
