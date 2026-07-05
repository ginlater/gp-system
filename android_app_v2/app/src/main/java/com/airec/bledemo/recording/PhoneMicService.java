package com.airec.bledemo.recording;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import com.airec.bledemo.MeiliActivity;
import com.airec.bledemo.R;
import com.airec.bledemo.net.Uploader;

import java.io.File;

/**
 * 手机麦克风原生录音前台 Service。
 *
 * 它存在的唯一理由：用「前台 Service」承载 MediaRecorder，使录音在 App 切后台 / 锁屏 / 息屏时
 * 不被系统挂起（这是普通 WebView getUserMedia 做不到的）。录音存为 m4a(AAC)，停止后带登录 Cookie
 * 上传到接诊后端的 /api/consultant/upload。
 *
 * 注意：仍无法 100% 对抗国产 ROM 的「划掉最近任务 / 激进省电」杀进程——那要靠阶段二的蓝牙录音笔兜底。
 */
public class PhoneMicService extends Service {

    private static final String TAG = "PhoneMicService";

    public static final String ACTION_START = "com.aibeautyfulwomen.gongpai.PHONE_MIC_START";
    public static final String ACTION_STOP  = "com.aibeautyfulwomen.gongpai.PHONE_MIC_STOP";

    public static final String EXTRA_COOKIE      = "cookie";
    public static final String EXTRA_UPLOAD_URL  = "upload_url";

    // 状态通过 RecordingBus 回传给接诊页
    public static final String STATE_STARTING  = "starting";   // 已发开始命令、等录音笔确认真的开录（防休眠空录）
    public static final String STATE_RECORDING = "recording";
    public static final String STATE_UPLOADING = "uploading";
    public static final String STATE_IDLE      = "idle";
    public static final String STATE_ERROR     = "error";

    private static final int NOTIFICATION_ID = 5206;
    private static final String CHANNEL_ID = "rec_channel";

    private MediaRecorder recorder;
    private File currentFile;
    private long startElapsedMs;
    private volatile boolean recording;
    private volatile boolean uploading;
    private String uploadUrl;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_START.equals(action)) {
            uploadUrl = intent.getStringExtra(EXTRA_UPLOAD_URL);
            startRecording();
        } else if (ACTION_STOP.equals(action)) {
            String cookie = intent.getStringExtra(EXTRA_COOKIE);
            if (intent.hasExtra(EXTRA_UPLOAD_URL)) uploadUrl = intent.getStringExtra(EXTRA_UPLOAD_URL);
            stopAndUpload(cookie);
        }
        return START_STICKY;
    }

    private void startRecording() {
        if (recording || uploading) {
            broadcast(STATE_RECORDING, "正在录音", elapsedSec(), -1);
            return;
        }
        try {
            startForegroundCompat();

            File dir = new File(getFilesDir(), "recordings");
            if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("无法创建录音目录");
            // A1(P0):MPEG_4 的 moov 尾只在 stop() 写,录音中被 vivo 杀进程→整段永久不可解。
            //   改 AAC_ADTS 流式容器:每帧自带头,进程随时被杀,已落盘部分仍可解码/补传。
            currentFile = new File(dir, "rec_" + System.currentTimeMillis() + ".aac");

            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            // 问题 #4：语音场景单声道 16kHz / 32kbps 足够清晰且利于 ASR，文件比 44.1kHz 立体声小约 6 倍（弱网更易传成）。
            recorder.setAudioChannels(1);
            recorder.setAudioSamplingRate(16000);
            recorder.setAudioEncodingBitRate(32000);
            // 问题 #1：录音中途麦被抢占 / 编码器出错不再静默坏掉——装回调，出错即兜底通知顾问 + 保留已录文件供补传。
            recorder.setOnErrorListener((mr, what, extra) -> onRecorderError(what, extra));
            recorder.setOnInfoListener((mr, what, extra) ->
                    Log.w(TAG, "MediaRecorder info what=" + what + " extra=" + extra));
            recorder.setOutputFile(currentFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();

            recording = true;
            startElapsedMs = SystemClock.elapsedRealtime();
            broadcast(STATE_RECORDING, "录音中…", 0, -1);
        } catch (Exception e) {
            Log.e(TAG, "startRecording failed", e);
            safeReleaseRecorder();
            recording = false;
            broadcast(STATE_ERROR, "无法开始录音：" + e.getMessage(), 0, -1);
            stopForeground(true);
            stopSelf();
        }
    }

    private void stopAndUpload(final String cookie) {
        if (!recording) {
            broadcast(STATE_IDLE, "", 0, -1);
            return;
        }
        final int durSec = elapsedSec();
        recording = false;
        boolean stopOk = true;
        try {
            recorder.stop();
        } catch (Exception e) {
            // 问题 #2：stop() 在录音过短/异常时可能抛错。不再直接丢文件——
            // 文件里已写入的音频数据仍可能被服务端 ffmpeg 恢复，尽力上传；真不行也留着等补传扫描。
            Log.e(TAG, "recorder.stop failed", e);
            stopOk = false;
        }
        safeReleaseRecorder();

        final File file = currentFile;
        if (file == null || !file.exists() || file.length() <= 1024) {
            broadcast(STATE_ERROR, "录音过短或失败，请重试", 0, -1);
            stopForeground(true);
            stopSelf();
            return;
        }
        if (!stopOk) Log.w(TAG, "stop 失败但文件有内容(" + file.length() + "B)，尽力上传");
        uploadAndFinish(file, durSec, cookie);
    }

    /** 后台线程上传并收尾：成功删文件，失败保留(供回前台补传扫描重试)。stop 正常/异常都走这。 */
    private void uploadAndFinish(final File file, final int durSec, final String cookie) {
        uploading = true;
        broadcast(STATE_UPLOADING, "上传中…", durSec, -1);
        new Thread(() -> {
            Uploader.Result r = Uploader.upload(file, durSec, cookie, uploadUrl);
            uploading = false;
            if (r.ok) {
                broadcast(STATE_IDLE, "已上传，请选择顾客", durSec, r.recordingId);
                if (file != null) file.delete();
            } else {
                // 上传失败：保留本地文件，避免丢录音（401 文案已含「请重新登录」，回前台补传扫描会重试）。
                broadcast(STATE_ERROR, "上传失败：" + r.error, durSec, -1);
            }
            stopForeground(true);
            stopSelf();
        }, "rec-upload").start();
    }

    /** 问题 #1：MediaRecorder 运行期出错回调（麦被抢占/编码器挂）。停录、保留已录文件供补传、通知顾问。 */
    private void onRecorderError(int what, int extra) {
        Log.e(TAG, "MediaRecorder error what=" + what + " extra=" + extra);
        if (!recording) return;
        recording = false;
        final int durSec = elapsedSec();
        try { recorder.stop(); } catch (Exception ignored) {}   // 尽量写出文件尾(moov)，失败也保留文件
        safeReleaseRecorder();
        // 文件保留不删：回前台补传扫描(retryLeftoverPhoneMic)会尝试上传。
        broadcast(STATE_ERROR, "录音中断（麦克风可能被其他应用占用），已尽力保存，请重试", durSec, -1);
        stopForeground(true);
        stopSelf();
    }

    private int elapsedSec() {
        if (startElapsedMs == 0) return 0;
        return (int) Math.max(0, (SystemClock.elapsedRealtime() - startElapsedMs) / 1000);
    }

    private void safeReleaseRecorder() {
        if (recorder != null) {
            try { recorder.reset(); } catch (Exception ignored) {}
            try { recorder.release(); } catch (Exception ignored) {}
            recorder = null;
        }
    }

    private void broadcast(String state, String message, int durSec, long recId) {
        RecordingBus.post(state, message, durSec, recId);
    }

    private void startForegroundCompat() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null
                && nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.rec_channel_name), NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }

        Intent open = new Intent(this, MeiliActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlags);

        // 注意：Notification.Builder(Context, channelId) 是 API 26+ 才有的双参构造，
        // API 24/25 必须用旧构造，否则运行时 NoSuchMethodError。
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        Notification n = b
                .setContentTitle("接诊录音进行中")
                .setContentText("录音在后台继续，请勿划掉本应用")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        safeReleaseRecorder();
    }
}
