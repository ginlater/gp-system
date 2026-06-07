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

import com.airec.bledemo.ConsultantActivity;
import com.airec.bledemo.R;

/**
 * 录音笔(BLE)录音期间的「保活」前台 Service。
 *
 * 存在的唯一理由：录音笔音频走蓝牙实时流进 App 进程。一旦 App 被切后台 / 锁屏 / 息屏，系统会挂起进程
 * → BLE 回调停 → 实时流静默卡死或断连 → 录到一半丢（"显示9分钟、实际35秒"的头号原因）。
 * 用「前台 Service + PARTIAL_WAKE_LOCK」在整段录音期间托住进程，让 BLE 回调持续流动。
 *
 * 与 {@link PhoneMicService} 的区别：本 Service 不碰麦克风、不录音，只保活；音频由 PenController 经蓝牙取。
 * 所以 foregroundServiceType = connectedDevice（蓝牙设备），不是 microphone。
 *
 * start()/stop() 均幂等、吞异常：后台启动受限(Android 12+ 未进电池白名单且 App 在后台)等情况只退化到
 * "无保活"(等同改前)，绝不让录音崩。
 */
public class PenKeepAliveService extends Service {

    private static final String TAG = "PenKeepAlive";

    public static final String ACTION_START = "com.aibeautyfulwomen.gongpai.PEN_KEEPALIVE_START";
    public static final String ACTION_STOP  = "com.aibeautyfulwomen.gongpai.PEN_KEEPALIVE_STOP";

    private static final int NOTIFICATION_ID = 5207;
    private static final String CHANNEL_ID = "pen_rec_channel";
    // 兜底：录音再长也不至于无限保活；超过这个时长自动收（防 PenController 某条结束路径漏调 stop 导致 wakelock 泄漏）。
    private static final long MAX_KEEPALIVE_MS = 3 * 60 * 60 * 1000L; // 3h

    private PowerManager.WakeLock wakeLock;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable maxStop = this::stopSelfClean;

    /** 录音开始：起前台保活（幂等、吞异常）。 */
    public static void start(Context ctx) {
        try {
            Intent i = new Intent(ctx, PenKeepAliveService.class).setAction(ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Throwable t) {
            Log.w(TAG, "start keepalive failed (退化到无保活): " + t.getMessage());
        }
    }

    /** 录音结束：停前台保活（幂等、吞异常）。 */
    public static void stop(Context ctx) {
        try {
            ctx.startService(new Intent(ctx, PenKeepAliveService.class).setAction(ACTION_STOP));
        } catch (Throwable t) {
            // 停不掉也无所谓：MAX_KEEPALIVE_MS 兜底会自停。
            Log.w(TAG, "stop keepalive failed: " + t.getMessage());
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelfClean();
            return START_NOT_STICKY;
        }
        // START（或被系统 STICKY 重启拉起）：进前台 + 持唤醒锁
        startForegroundCompat();
        acquireWakeLock();
        handler.removeCallbacks(maxStop);
        handler.postDelayed(maxStop, MAX_KEEPALIVE_MS);
        return START_STICKY;
    }

    private void stopSelfClean() {
        handler.removeCallbacks(maxStop);
        releaseWakeLock();
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

    private void startForegroundCompat() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null
                && nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "录音笔录音保活", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }

        Intent open = new Intent(this, ConsultantActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlags);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        Notification n = b
                .setContentTitle("录音笔录音进行中")
                .setContentText("请勿划掉本应用，保持录音笔开机并靠近手机")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();

        // connectedDevice 类型常量 + 三参 startForeground 都是 API 30(R) 起；24~29 用两参，靠 manifest 里的 type。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(maxStop);
        releaseWakeLock();
    }
}
