package com.airec.bledemo.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.airec.bledemo.MeiliActivity
import com.airec.bledemo.R
import com.airec.bledemo.data.model.Reminder

/**
 * 提醒本地通知：把后端「提醒/升级项」以手机系统通知的形式弹出来。
 *
 * 设计：
 *  - 只对「没见过的提醒 id」弹一次（已见集合持久化在 SharedPreferences），避免每次轮询重复弹。
 *  - 提醒消失（已处理/已查看）后从已见集合里清掉 → 日后若同 id 复现可再次提醒（后端 id 单调，基本不会复现）。
 *  - 文案直接用后端 message（已包含「6月14日 21:29 的录音待绑定 / 张姐 的报告未查看」等具体信息）。
 *  - 点击通知打开 App（MeiliActivity）。深链到具体绑定/报告页可后续再做。
 *
 * 不依赖 FCM：本地通知由 App 进程（前台 HomeViewModel 轮询 / 后台 WorkManager 周期任务）触发。
 * App 被系统杀死且无后台任务窗口时不保证即时——这是本地方案的已知天花板。
 */
object ReminderNotifier {

    private const val CHANNEL_ID = "reminders"
    private const val PREFS = "reminder_notify_prefs"
    private const val KEY_SEEN = "seen_ids"
    private const val BATTERY_NOTIF_ID = 990001

    @Volatile
    private var appCtx: Context? = null

    // 电量提醒去重：-1=未提醒 / 10=已提醒过10%档 / 5=已提醒过5%档。充电或回到>10%即重置。
    @Volatile
    private var batteryNotifiedLevel = -1

    /**
     * 陪伴笔电量回调（SoniPenController cmd=6 收到电量时触发）。
     * 低于 10% 发一次「该充电」、低于 5% 发一次「剩5%」通知；同一档不重复弹，充电/回升后重置。
     * @param level 0–100 电量百分比（>100 视为充电中）
     * @param charging 是否充电中（声云笔 "110"=充电中）
     */
    fun onPenBattery(level: Int, charging: Boolean) {
        val c = appCtx ?: return
        if (charging || level !in 0..100) { batteryNotifiedLevel = -1; return }
        if (level > 10) { batteryNotifiedLevel = -1; return }   // 电量正常 → 重置, 下次低电再提醒
        ensureChannel(c)
        if (!NotificationManagerCompat.from(c).areNotificationsEnabled()) return
        if (level <= 5) {
            if (batteryNotifiedLevel != 5) {
                batteryNotifiedLevel = 5
                postBattery(c, "陪伴笔电量不足", "电量剩余5%，快去充电吧，充电时长在1.5～2小时就可以充满了哦～")
            }
        } else { // 6..10
            if (batteryNotifiedLevel == -1) {
                batteryNotifiedLevel = 10
                postBattery(c, "陪伴笔该充电啦", "陪伴笔电量已低于 10%，建议尽快充电，别耽误美丽陪伴的记录哦～")
            }
        }
    }

    private fun postBattery(context: Context, title: String, text: String) {
        val tapIntent = android.content.Intent(context, MeiliActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = android.app.PendingIntent.getActivity(
            context, BATTERY_NOTIF_ID, tapIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val n = android.app.Notification.Builder(context, CHANNEL_ID).let { b ->
            b.setSmallIcon(R.mipmap.ic_launcher)
            b.setContentTitle(title)
            b.setContentText(text)
            b.setStyle(android.app.Notification.BigTextStyle().bigText(text))
            b.setAutoCancel(true)
            b.setContentIntent(pi)
            b.build()
        }
        try {
            NotificationManagerCompat.from(context).notify(BATTERY_NOTIF_ID, n)
        } catch (_: SecurityException) {
        }
    }

    /** Application.onCreate 调一次：存 app context（供无 Context 的 ViewModel 用）+ 建渠道。 */
    fun init(context: Context) {
        appCtx = context.applicationContext
        ensureChannel(context.applicationContext)
    }

    /** 便捷重载：前台 HomeViewModel 拉到 reminders 后直接调（用已存的 app context）。 */
    fun notifyNew(items: List<Reminder>): Int {
        val c = appCtx ?: return 0
        return notifyNew(c, items)
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "陪伴提醒",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "录音待绑定、报告未查看等提醒"
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    /**
     * 对比已见集合，给「新出现的提醒」各弹一条系统通知，并把当前全部 id 同步为新的已见集合。
     * 返回实际弹出的条数。需调用方已持有列表（来自 /api/consultant/reminders）。
     */
    fun notifyNew(context: Context, items: List<Reminder>): Int {
        if (items.isEmpty()) {
            // 没有任何提醒 → 清空已见集合（都已处理），让日后新提醒能再弹
            saveSeen(context, emptySet())
            return 0
        }
        ensureChannel(context)
        val seen = loadSeen(context)
        val currentIds = items.map { it.id.toString() }.toSet()
        var posted = 0
        val canNotify = NotificationManagerCompat.from(context).areNotificationsEnabled()
        for (r in items) {
            val idStr = r.id.toString()
            if (seen.contains(idStr)) continue
            if (canNotify) {
                try {
                    postOne(context, r)
                    posted++
                } catch (_: SecurityException) {
                    // 没有 POST_NOTIFICATIONS 运行时权限：跳过弹窗，但仍记为已见，避免拿到权限后一次性补弹历史
                }
            }
        }
        // 已见集合 = 当前在册的全部 id（消失的自动移除）
        saveSeen(context, currentIds)
        return posted
    }

    private fun postOne(context: Context, r: Reminder) {
        val title = when {
            r.scope == "escalation" -> "店长待跟进"
            r.kind == "unbound" -> "录音待绑定"
            r.kind == "unviewed" -> "报告待查看"
            else -> "陪伴提醒"
        }
        val text = r.message ?: "您有一条待处理的提醒"

        val tapIntent = Intent(context, MeiliActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open", "reminders")
        }
        val pi = PendingIntent.getActivity(
            context,
            r.id.toInt(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val n = Notification.Builder(context, CHANNEL_ID).let { b ->
            b.setSmallIcon(R.mipmap.ic_launcher)
            b.setContentTitle(title)
            b.setContentText(text)
            b.setStyle(Notification.BigTextStyle().bigText(text))
            b.setAutoCancel(true)
            b.setContentIntent(pi)
            b.build()
        }
        NotificationManagerCompat.from(context).notify(notifId(r.id), n)
    }

    /** 用提醒 id 当通知 id（同一提醒重复弹会覆盖而非堆叠）。取低 31 位避免溢出。 */
    private fun notifId(id: Long): Int = (id and 0x7fffffff).toInt()

    private fun loadSeen(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_SEEN, emptySet()) ?: emptySet()

    private fun saveSeen(context: Context, ids: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_SEEN, ids).apply()
    }
}
