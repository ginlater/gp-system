package com.airec.bledemo.recording;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import com.airec.bledemo.MeiliActivity;
import com.airec.bledemo.R;

/**
 * 陪伴笔(BLE)「保活」前台 Service —— 两段式持有（v23 方案，自 android_app 移植），把进程托在高优先级、系统极少杀。
 *
 * 为什么要保活：陪伴笔音频走蓝牙进 App 进程。App 被切后台/锁屏/息屏 → 系统挂起或杀掉进程
 * → BLE 连接断、实时流停 → 录音断/丢，重连还得冷启。前台 Service 是安卓官方唯一认可的高优先级保活。
 *
 * 两个独立"持有"，FGS 在【任一】为真时挂着；前台通知常驻是代价(像微信/音乐播放器)：
 *   - CONN(已连接)：只要笔连着就挂 FGS，让进程在后台不被杀、蓝牙不断。**不持唤醒锁**(空闲连接不耗 CPU，省电)。
 *   - REC(录音/补传)：录音或后台补传时，额外持 PARTIAL_WAKE_LOCK，保证息屏也能持续流动 BLE 数据。
 * 录音结束 → 撤 REC(放唤醒锁)，但只要还连着 CONN 仍在 → FGS 继续 → 后台不被杀。笔断开 → 撤 CONN → FGS 停。
 *
 * foregroundServiceType = connectedDevice(维持蓝牙设备连接)，不是 microphone(本服务不碰麦克风)。
 * start/stop 全幂等、吞异常：后台启动受限(Android 12+ 未进白名单且 App 在后台)只退化到"无保活"，绝不崩。
 */
public class PenKeepAliveService extends Service {

    private static final String TAG = "PenKeepAlive";

    // 录音/补传：FGS + 唤醒锁
    public static final String ACTION_REC_ON   = "com.aibeautyfulwomen.gongpai.PEN_KA_REC_ON";
    public static final String ACTION_REC_OFF  = "com.aibeautyfulwomen.gongpai.PEN_KA_REC_OFF";
    // 已连接(空闲)：仅 FGS，不持唤醒锁
    public static final String ACTION_CONN_ON  = "com.aibeautyfulwomen.gongpai.PEN_KA_CONN_ON";
    public static final String ACTION_CONN_OFF = "com.aibeautyfulwomen.gongpai.PEN_KA_CONN_OFF";
    // 强停(全部撤)
    public static final String ACTION_STOP     = "com.aibeautyfulwomen.gongpai.PEN_KEEPALIVE_STOP";

    private static final int NOTIFICATION_ID = 5207;
    private static final String CHANNEL_ID = "pen_rec_channel";
    // 防泄漏兜底：无任何事件超过这个时长自动收(连续录音也封顶；正常使用期间每次事件都会 re-arm)。
    private static final long MAX_KEEPALIVE_MS = 3 * 60 * 60 * 1000L; // 3h

    // 两个独立持有(static：跨 onStartCommand / 服务重启可见)
    private static volatile boolean recHold = false;
    private static volatile boolean connHold = false;
    private boolean foreground = false;

    private PowerManager.WakeLock wakeLock;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable maxStop = this::stopSelfClean;

    private static void send(Context ctx, String action, boolean mayGoForeground) {
        try {
            Intent i = new Intent(ctx, PenKeepAliveService.class).setAction(action);
            // 只有"开启"类动作用 startForegroundService(随后必 startForeground)；
            // "关闭"类用普通 startService(可能直接 stopSelf，不能欠 startForeground 的债，否则崩)。
            if (mayGoForeground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Throwable t) {
            Log.w(TAG, "send " + action + " failed (退化到无保活): " + t.getMessage());
        }
    }

    /** 录音/补传开始：FGS + 唤醒锁。 */
    public static void recOn(Context c)  { send(c, ACTION_REC_ON, true); }
    /** 录音/补传结束：放唤醒锁；若仍连着，FGS 由 CONN 继续托住。 */
    public static void recOff(Context c) { send(c, ACTION_REC_OFF, false); }
    /** 笔已连接：挂 FGS(不持锁)，后台不被杀。 */
    public static void connOn(Context c) { send(c, ACTION_CONN_ON, true); }
    /** 笔断开(去抖后)：撤 CONN，无其它持有则停服务。 */
    public static void connOff(Context c){ send(c, ACTION_CONN_OFF, false); }
    /** 强停(退出/异常兜底)。 */
    public static void stop(Context c)   { send(c, ACTION_STOP, false); }

    /** 兼容旧 API（备份壳 ConsultantActivity 走的老 PenController 还在用）：start=录音级开。 */
    public static void start(Context c)  { recOn(c); }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) { recHold = false; connHold = false; stopSelfClean(); return START_NOT_STICKY; }
        if (ACTION_REC_ON.equals(action))        recHold = true;
        else if (ACTION_REC_OFF.equals(action))  recHold = false;
        else if (ACTION_CONN_ON.equals(action))  connHold = true;
        else if (ACTION_CONN_OFF.equals(action)) connHold = false;
        else if (action == null) {
            // 系统 STICKY 重启拉起：先当连接级保活。H5(批次六)：加 10 分钟确认窗——笔关机/不在店时
            // 没有任何真实事件来校正，原来"保持连接中"通知空挂满 3 小时白耗电,还引导用户去限制 App。
            connHold = true;
            sawRealEventSinceRestart = false;
            handler.removeCallbacks(restartConfirm);
            handler.postDelayed(restartConfirm, 10 * 60 * 1000L);
        }
        if (action != null) sawRealEventSinceRestart = true;   // H5:任何真实事件即确认
        reconcile();
        return START_STICKY;
    }

    // H5(批次六):STICKY 重启后的确认窗——10 分钟内没有任何真实事件(笔未回连)就自停,不空挂 3 小时
    private volatile boolean sawRealEventSinceRestart = true;
    private final Runnable restartConfirm = () -> {
        if (!sawRealEventSinceRestart) {
            Log.w(TAG, "STICKY 重启 10 分钟无真实事件（笔未回连）→ 自停，不再空挂保活");
            recHold = false;
            connHold = false;
            stopSelfClean();
        }
    };

    /** 按当前两个持有重算：任一为真→挂前台(录音时再持锁)；都为假→停。 */
    private void reconcile() {
        if (recHold || connHold) {
            if (!foreground) {
                // C3:启 FGS 被拒(Android12+ 后台未白名单)→已在 startForegroundCompat 内退化,不再往下持锁
                if (startForegroundCompat()) foreground = true; else return;
            } else updateNotification();              // 已在前台：只刷新文案(已就绪/进行中)
            if (recHold) acquireWakeLock(); else releaseWakeLock();   // 唤醒锁仅录音时持，空闲连接省电
            handler.removeCallbacks(maxStop);
            handler.postDelayed(maxStop, MAX_KEEPALIVE_MS);
        } else {
            stopSelfClean();
        }
    }

    private void stopSelfClean() {
        handler.removeCallbacks(maxStop);
        handler.removeCallbacks(restartConfirm);
        // H3(批次六):maxStop 兜底自停也清 static 持有——原来留脏 recHold/connHold,之后任一 OFF 类
        // 事件会按脏持有把 FGS+唤醒锁重新挂起(无人记账的"挂了不撤")。控制器侧 H1 已改"每次真发",
        // 队列一有动静就能重挂,不会因此失联。
        recHold = false;
        connHold = false;
        releaseWakeLock();
        foreground = false;
        try { stopForeground(true); } catch (Exception ignored) {}
        stopSelf();
    }

    private void acquireWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) return;
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gongpai:pen-rec");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(MAX_KEEPALIVE_MS);   // 带超时，再保险一层防泄漏
        } catch (Throwable t) {
            Log.w(TAG, "acquireWakeLock failed: " + t.getMessage());
        }
    }

    private void releaseWakeLock() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Throwable ignored) {}
        wakeLock = null;
    }

    private void ensureChannel(NotificationManager nm) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null
                && nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "美丽陪伴 · 保持连接", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MeiliActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlags);

        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        // ★顾客可能瞄到手机：用"陪伴"系词汇，不出现"录音"二字(见产品红线)。
        String text = recHold ? "陪伴进行中，请勿划掉本应用" : "保持连接中，请勿划掉本应用";
        return b
                .setContentTitle("美丽陪伴")
                .setContentText(text)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    /** C3:返回是否真的进了前台。STICKY 重启在后台未白名单时 startForeground 会抛
     *  ForegroundServiceStartNotAllowedException(Android12+),这里吞掉退化"无保活",绝不让自愈重启变崩溃循环。 */
    private boolean startForegroundCompat() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        ensureChannel(nm);
        Notification n = buildNotification();
        try {
            // connectedDevice 类型常量 + 三参 startForeground 都是 API 30(R) 起；24~29 用两参，靠 manifest 里的 type。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "startForeground 被拒→退化无保活(不崩): " + t.getMessage());
            recHold = false; connHold = false;
            try { stopSelf(); } catch (Throwable ignore) {}
            return false;
        }
    }

    private void updateNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification());
        } catch (Throwable ignored) {}
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(maxStop);
        releaseWakeLock();
    }
}
