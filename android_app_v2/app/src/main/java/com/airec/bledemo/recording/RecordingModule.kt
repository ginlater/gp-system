package com.airec.bledemo.recording

import android.content.Context
import android.util.Log
import com.airec.bledemo.data.net.NetworkModule
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * 录音引擎接线层单例。
 *
 * 全 App 共享同一个 [RecordingControllerImpl]（陪伴首页 / 待整理同步都要用同一引擎实例，
 * 否则各拿各的 PenController、蓝牙连接/补传队列会打架）。仿 [NetworkModule] 的 init 模式：
 * 必须先在 [com.airec.bledemo.MeiliActivity.onCreate] 调一次 [init]，之后各处取 [controller]。
 *
 * 上传上下文（会话 Cookie + 上传地址）登录后才有，故由 [refreshUploadContext] 在
 * 回前台 / 进首页时按需注入；引擎本身（手机麦走 Intent extra、陪伴笔自发录音/后台补传）都靠它。
 */
object RecordingModule {

    private const val TAG = "RecordingModule"

    // 与 ConsultantActivity.uploadUrlFor(START_URL) 完全一致：base 去掉 /consultant 再拼 /api/consultant/upload。
    private const val UPLOAD_URL = "https://gp.aibeautyfulwomen.com/api/consultant/upload"

    @Volatile
    private var impl: RecordingControllerImpl? = null

    /** 共享的录音控制器。未 [init] 先取会抛错（属编程错误，应在入口 init）。 */
    val controller: RecordingController
        get() = impl ?: error("RecordingModule.init() 未调用：请在 MeiliActivity.onCreate 先 init")

    @Synchronized
    fun init(context: Context) {
        if (impl == null) {
            impl = RecordingControllerImpl(context.applicationContext).also {
                // 引擎连上 / 查到笔录音状态时，回来重注最新会话 Cookie（Cookie 轮换后笔自发录音也能上传）。
                it.onNeedContextRefresh = { refreshUploadContext() }
            }
        }
    }

    /**
     * 刷新上传上下文：从 [NetworkModule] 的 CookieJar 取当前会话 Cookie，连同上传地址注入引擎。
     * 无 Cookie（未登录）时不覆盖，避免把已有上下文清空。回前台 / 进陪伴首页时调，保证 Cookie 最新。
     */
    fun refreshUploadContext() {
        val c = impl ?: return
        if (!NetworkModule.isInitialized()) return
        val cookie = runCatching {
            NetworkModule.cookieJar.cookieHeader(NetworkModule.BASE_URL.toHttpUrl())
        }.getOrElse {
            Log.w(TAG, "refreshUploadContext: 取 Cookie 失败 ${it.message}")
            ""
        }
        if (cookie.isNotEmpty()) {
            c.setUploadContext(cookie, UPLOAD_URL)
        }
    }

    /** 网络恢复 → 通知引擎清退避、立刻重推待传（网络监听层回调）。 */
    fun onNetworkAvailable() {
        impl?.onNetworkAvailable()
    }
}
