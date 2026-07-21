package com.airec.bledemo.data.teach

import com.airec.bledemo.data.api.TeachApi
import com.airec.bledemo.data.model.TeachStats
import com.airec.bledemo.data.net.NetworkModule
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * teach 网课网络单例（多系统整合 P1）。
 *
 * 与工牌（NetworkModule）分开的原因：不同域名 + 不同鉴权（Bearer 而非 Cookie）。
 * 但复用 NetworkModule 的 OkHttpClient/Moshi（超时、日志、重试配置一致），
 * 所以要求 NetworkModule.init 已调（Application.onCreate 里，恒成立）。
 *
 * token / lastStats 是进程内缓存：
 *  - token：/teach/login 换的 Bearer，401 时由 TeachRepository 静默重登刷新;
 *  - lastStats：最近一次 /teach/me/stats 成功结果（课件页查章标题等轻量读取用）。
 * 工牌退出登录时一并 [clear]（统一密码换人了，teach 会话必须跟着作废）。
 */
object TeachModule {

    const val BASE_URL = "https://teach.beautyshining.com/"

    /** 课件正文 URL（静态 html、免鉴权，WebView 直接加载）。 */
    fun coursewareUrl(chapterKey: String): String = "$BASE_URL$chapterKey.html"

    val api: TeachApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(NetworkModule.okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(NetworkModule.moshi).asLenient())
            .build()
            .create(TeachApi::class.java)
    }

    @Volatile
    var token: String? = null

    @Volatile
    var lastStats: TeachStats? = null

    /** 工牌退出登录 / 换账号时调：作废 teach 会话与缓存。 */
    fun clear() {
        token = null
        lastStats = null
    }
}
