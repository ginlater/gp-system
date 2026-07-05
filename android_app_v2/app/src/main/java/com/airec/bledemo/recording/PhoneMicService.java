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
    private static final int MAX_REC_MS = 90 * 60 * 1000;   // C4:手机麦单段最长90分钟(对齐笔),防忘停录到没电
    private static final String CHANNEL_ID = "rec_channel";

    private MediaRecorder recorder;
    private File currentFile;
    private long startElapsedMs;
    private long startWallMs;                 // D12:录音真实开始墙钟(建占位/recorded_at 用)
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
        } else {
            // H2(复审):STICKY 空 intent 重启——MediaRecorder 早随进程死,重启毫无意义,
            //   且经 startForegroundService 拉起却不调 startForeground 会被系统按时限击杀(本工程实锤过)。
            stopSelf();
        }
        // H2(复审):不再 STICKY——被杀后的恢复本来就走 retryLeftoverPhoneMic 补传扫描,
        //   系统自动重启只会带来 FGS 超时崩溃循环或僵尸服务,纯负收益。
        return START_NOT_STICKY;
    }

    private void startRecording() {
        if (recording) {
            broadcast(STATE_RECORDING, "正在录音", elapsedSec(), -1);
            return;
        }
        if (uploading) {
            // Z1(复审/P0):上传中收到开始命令,原来假广播"正在录音"——UI 进录音态、计时自走,
            //   麦克风根本没开,下一位顾客整段从未采集。老实报错,让顾问稍等或重试。
            broadcast(STATE_ERROR, "上一段录音还在保存中，请稍等几秒再点开始", 0, -1);
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
            recorder.setOnInfoListener((mr, what, extra) -> onRecorderInfo(what, extra));
            recorder.setMaxDuration(MAX_REC_MS);   // C4:到点触发 onInfo→自动停录保存
            recorder.setOutputFile(currentFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();

            recording = true;
            startElapsedMs = SystemClock.elapsedRealtime();
            startWallMs = System.currentTimeMillis();
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
        final long recStart = startWallMs > 0 ? startWallMs : System.currentTimeMillis() - durSec * 1000L;
        new Thread(() -> {
            // D12:对齐笔路径——停录先建占位(几秒即回)，拿到 id 立刻弹绑定，不必等整段传完(弱网可能要几分钟)。
            //   占位 source=phone(服务端按手机麦权限校验/回填口径)；随后上传带 placeholder_id 回填同一行。
            File up = file;
            long pid = -1;
            if (cookie != null && !cookie.isEmpty() && uploadUrl != null) {
                String phUrl = uploadUrl.endsWith("/upload")
                        ? uploadUrl.substring(0, uploadUrl.length() - 7) + "/placeholder"
                        : uploadUrl.replace("/upload", "/placeholder");
                pid = Uploader.createPlaceholder(cookie, phUrl, recStart, null, "phone");
                if (pid > 0) {
                    // 占位 id 嵌进文件名(rec_<ts>_p<pid>.aac)：上传中途被杀/失败后，补传扫描能带
                    // placeholder_id 回填同一占位行，不再新建重复行、占位也不会永挂"同步中"。
                    String newName = up.getName().replaceFirst("\\.(aac|m4a)$", "_p" + pid + ".$1");
                    File renamed = new File(up.getParentFile(), newName);
                    if (!newName.equals(up.getName()) && up.renameTo(renamed)) up = renamed;
                    // 拿到占位 recordingId 即弹绑定（UPLOADING 带 recId，由 RecordingControllerImpl 消费）
                    broadcast(STATE_UPLOADING, "上传中…", durSec, pid);
                }
            }
            String upName = "rec-" + System.currentTimeMillis() + (up.getName().endsWith(".m4a") ? ".m4a" : ".aac");
            String upMime = up.getName().endsWith(".m4a") ? "audio/mp4" : "audio/aac";
            String recordedAt = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(new java.util.Date(recStart));
            // B1(复审):带 source=phone——服务端按手机录音权限校验,只开手机权限的顾问不再恒403
            Uploader.Result r = Uploader.upload(up, durSec, cookie, uploadUrl, upName, upMime, null, pid, recordedAt, null, false, "phone");
            uploading = false;
            if (r.ok) {
                long rid = r.recordingId > 0 ? r.recordingId : pid;
                broadcast(STATE_IDLE, "已上传，请选择顾客", durSec, rid);
                up.delete();
            } else {
                // 上传失败：保留本地文件与占位行（待整理显示"同步中"可重试），回前台补传扫描按文件名里的 pid 回填。
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

    /** C4:录满90分钟 MediaRecorder 自动停(MAX_DURATION_REACHED)→保存已录,留补传扫描上传,提示可继续。 */
    private void onRecorderInfo(int what, int extra) {
        if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
            Log.w(TAG, "手机麦录满90分钟 → 自动停录保存");
            if (!recording) return;
            recording = false;
            final int durSec = elapsedSec();
            try { recorder.stop(); } catch (Exception ignored) {}
            safeReleaseRecorder();
            // 文件保留,回前台 retryLeftoverPhoneMic 补传(与 onRecorderError 同路径)
            broadcast(STATE_ERROR, "已录满 90 分钟，已自动保存，请到「待整理」查看；要继续请重新开始录制", durSec, -1);
            stopForeground(true);
            stopSelf();
        } else {
            Log.w(TAG, "MediaRecorder info what=" + what + " extra=" + extra);
        }
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
