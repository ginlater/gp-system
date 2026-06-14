package com.airec.bledemo.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.airec.bledemo.data.net.NetworkModule
import java.util.concurrent.TimeUnit

/**
 * 后台周期轮询 /api/consultant/reminders → 对新提醒弹本地通知（[ReminderNotifier]）。
 *
 * 靠持久化 Cookie（PrefsCookieJar）认证，所以即使是被系统重新拉起的新进程也能带上会话。
 * WorkManager 最小周期 15 分钟，且受 Doze / 厂商后台限制——App 被狠杀的机型不保证准点，
 * 属本地方案的已知天花板（要秒级即时需 FCM）。前台时由 HomeViewModel 拉取即时补弹。
 */
class ReminderPollWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            NetworkModule.init(applicationContext)   // 幂等；保证新进程里 api 可用
            val resp = NetworkModule.api.reminders()
            if (!resp.isSuccessful) {
                // 401（未登录）等：不重试刷接口，等下个周期
                return Result.success()
            }
            val items = resp.body()?.items.orEmpty()
            ReminderNotifier.notifyNew(applicationContext, items)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE = "reminder-poll"

        /** 在 Application.onCreate 调一次：登记 ~30 分钟一轮的周期任务（已存在则保留）。 */
        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<ReminderPollWorker>(30, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE,
                ExistingPeriodicWorkPolicy.KEEP,
                req,
            )
        }
    }
}
