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
    // F11：电量提醒拆独立 DEFAULT 级 channel——用户可单独关电量嗡嗡，不连业务提醒一起哑
    private const val BATTERY_CHANNEL_ID = "pen_battery"
    private const val PREFS = "reminder_notify_prefs"
    private const val KEY_SEEN = "seen_ids"
    // F3：已见集是否已播种（首拉存量只记不弹的标记，区别于"真的一条提醒都没有过"）
    private const val KEY_SEEDED = "seen_seeded"
    // F5：电量提醒档位落盘（vivo 杀进程冷启不再重弹）
    private const val KEY_BATTERY_LEVEL = "battery_notified_level"
    private const val BATTERY_NOTIF_ID = 990001
    private const val GROUP_KEY = "meili_reminders"

    // 一批最多弹几条（F3：重装/换账号首拉存量几百条时不能 heads-up 轰炸）
    private const val MAX_POST_PER_BATCH = 5

    // F6：已见集读改写全程持锁——前台协程与 WorkManager 并发时不再丢更新/双弹
    private val seenLock = Any()

    @Volatile
    private var appCtx: Context? = null

    /**
     * 陪伴笔电量回调（SoniPenController cmd=6 收到电量时触发）。
     * 低于 10% 发一次「该充电」、低于 5% 发一次「剩5%」通知；同一档不重复弹。
     * F5：档位落盘（进程被杀重启不重弹）+ 复位阈值 ≥15%（滞回，10↔11 抖动不再反复弹）。
     * @param level 0–100 电量百分比（>100 视为充电中）
     * @param charging 是否充电中（声云笔 "110"=充电中）
     */
    fun onPenBattery(level: Int, charging: Boolean) {
        val c = appCtx ?: return
        val sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val notified = sp.getInt(KEY_BATTERY_LEVEL, -1)
        fun save(v: Int) = sp.edit().putInt(KEY_BATTERY_LEVEL, v).apply()
        if (charging || level !in 0..100) { if (notified != -1) save(-1); return }
        if (level >= 15) { if (notified != -1) save(-1); return }   // F5:≥15% 才复位(滞回)
        if (level in 11..14) return                                  // 滞回带：不弹也不复位
        ensureChannel(c)
        if (!NotificationManagerCompat.from(c).areNotificationsEnabled()) return
        if (level <= 5) {
            if (notified != 5) {
                save(5)
                postBattery(c, "陪伴笔电量不足", "电量剩余5%，快去充电吧，充电时长在1.5～2小时就可以充满了哦～")
            }
        } else { // 6..10
            if (notified == -1) {
                save(10)
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
        // F11：电量走独立 DEFAULT 级 channel（不 heads-up、可单独关）
        val n = android.app.Notification.Builder(context, BATTERY_CHANNEL_ID).let { b ->
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
            // F11：电量提醒独立 channel（DEFAULT：有声不悬浮，可在系统设置单独关掉）
            if (nm.getNotificationChannel(BATTERY_CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    BATTERY_CHANNEL_ID,
                    "陪伴笔电量",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "陪伴笔低电量充电提醒"
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    /**
     * 登出/换账号时调（F3）：清已见集与播种标记——下个账号首拉按"播种不弹"处理，
     * 不再把上个账号的已见状态错配到新账号（也不会存量全弹）。
     */
    fun clearSeen() {
        val c = appCtx ?: return
        synchronized(seenLock) {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_SEEN).remove(KEY_SEEDED).apply()
        }
    }

    /**
     * 对比已见集合，给「新出现的提醒」弹系统通知。返回实际弹出的条数。
     *
     * F3：首次运行（重装/清数据/换账号后）只播种不弹——存量几百条不能 heads-up 轰炸；单批弹出上限 5 条+分组。
     * F4：无通知权限时不把未弹的记为已见——点了"允许"后最近的新提醒还能弹（上限兜底防洪）。
     * F6：全程持锁，前台协程与 WorkManager 并发不再丢更新。
     * F7：已见集只并集不清空（服务端 LIMIT 截断边界震荡时消失又重现的 id 不再重复弹）；超限按 id 数值保最新。
     */
    fun notifyNew(context: Context, items: List<Reminder>): Int = synchronized(seenLock) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (items.isEmpty()) return 0   // F7：空列表不清已见集（可能是截断/瞬时空，清了会重复弹）
        ensureChannel(context)
        val seen = loadSeen(context)
        val currentIds = items.map { it.id.toString() }.toSet()
        // F3：从没播种过（首装/清数据/登出后首拉）→ 存量全部记已见、一条不弹
        if (!sp.getBoolean(KEY_SEEDED, false) && seen.isEmpty()) {
            saveSeen(context, currentIds)
            return 0
        }
        val canNotify = NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (!canNotify) return 0   // F4：权限未给/未决——不弹也不记已见，拿到权限后新提醒仍能弹
        var posted = 0
        // 新提醒按 id 降序（最新优先），单批最多弹 MAX_POST_PER_BATCH 条；没弹到的也记已见（老积压不补弹）
        val fresh = items.filter { it.id.toString() !in seen }.sortedByDescending { it.id }
        for (r in fresh) {
            if (posted >= MAX_POST_PER_BATCH) break
            try {
                postOne(context, r)
                posted++
            } catch (_: SecurityException) {
            }
        }
        saveSeen(context, seen + currentIds)   // F7：并集，不整体覆盖
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
            b.setGroup(GROUP_KEY)   // F3：多条收进一组，通知栏不刷屏
            b.build()
        }
        NotificationManagerCompat.from(context).notify(notifId(r.id), n)
    }

    /** 用提醒 id 当通知 id（同一提醒重复弹会覆盖而非堆叠）。取低 31 位避免溢出。 */
    private fun notifId(id: Long): Int = (id and 0x7fffffff).toInt()

    private fun loadSeen(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_SEEN, emptySet()) ?: emptySet()

    /** 保存已见集（并置播种标记）。F7：超 800 条按 id 数值只留最新 500，防无限膨胀。 */
    private fun saveSeen(context: Context, ids: Set<String>) {
        val trimmed = if (ids.size > 800) {
            ids.sortedByDescending { it.toLongOrNull() ?: 0L }.take(500).toSet()
        } else {
            ids
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_SEEN, trimmed).putBoolean(KEY_SEEDED, true).apply()
    }
}
