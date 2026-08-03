package com.aibeautyfulwomen.gongpai;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.MediaRecorder;
import android.os.IBinder;
import android.webkit.CookieManager;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class RecordingService extends Service {
    public static final String ACTION_START = "com.aibeautyfulwomen.gongpai.START_RECORDING";
    public static final String ACTION_STOP_UPLOAD = "com.aibeautyfulwomen.gongpai.STOP_UPLOAD";
    public static final String BROADCAST_STATUS = "com.aibeautyfulwomen.gongpai.RECORDING_STATUS";

    public static final String EXTRA_COOKIE = "cookie";
    public static final String EXTRA_UPLOAD_URL = "upload_url";
    public static final String EXTRA_STATE = "state";
    public static final String EXTRA_MESSAGE = "message";

    public static final String STATE_RECORDING = "recording";
    public static final String STATE_UPLOADING = "uploading";
    public static final String STATE_IDLE = "idle";
    public static final String STATE_ERROR = "error";

    private static final int NOTIFICATION_ID = 5206;
    private static final String DEFAULT_UPLOAD_URL = "https://gp.aibeautyfulwomen.com/api/consultant/upload";

    private MediaRecorder recorder;
    private File currentFile;
    private long startedAtMs;
    private String uploadUrl = DEFAULT_UPLOAD_URL;
    private volatile boolean uploading;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_START.equals(action)) {
            uploadUrl = intent.getStringExtra(EXTRA_UPLOAD_URL) == null
                    ? DEFAULT_UPLOAD_URL : intent.getStringExtra(EXTRA_UPLOAD_URL);
            startRecording();
        } else if (ACTION_STOP_UPLOAD.equals(action)) {
            String cookie = intent.getStringExtra(EXTRA_COOKIE);
            if (cookie == null) {
                cookie = CookieManager.getInstance().getCookie("https://gp.aibeautyfulwomen.com/consultant");
            }
            stopAndUpload(cookie);
        }
        return START_STICKY;
    }

    private void startRecording() {
        if (recorder != null || uploading) {
            broadcast(STATE_RECORDING, "正在录音");
            return;
        }
        try {
            File dir = new File(getExternalFilesDir(null), "recordings");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException("无法创建录音目录");
            }
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            currentFile = new File(dir, "gongpai_" + ts + ".m4a");

            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(64000);
            recorder.setAudioSamplingRate(44100);
            recorder.setOutputFile(currentFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            startedAtMs = System.currentTimeMillis();
            startForeground(NOTIFICATION_ID, buildNotification("正在录音，切后台也会继续"));
            broadcast(STATE_RECORDING, "正在录音");
        } catch (Exception e) {
            cleanupRecorder();
            broadcast(STATE_ERROR, "录音启动失败：" + e.getMessage());
            stopSelf();
        }
    }

    private void stopAndUpload(final String cookie) {
        if (recorder == null || currentFile == null) {
            broadcast(STATE_IDLE, "没有正在录音");
            stopSelf();
            return;
        }
        final File file = currentFile;
        final int durationSec = Math.max(1, (int) ((System.currentTimeMillis() - startedAtMs) / 1000L));
        try {
            recorder.stop();
        } catch (Exception ignored) {
        }
        cleanupRecorder();
        uploading = true;
        startForeground(NOTIFICATION_ID, buildNotification("录音完成，正在上传"));
        broadcast(STATE_UPLOADING, "正在上传");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    uploadRecording(file, durationSec, cookie);
                    broadcast(STATE_IDLE, "上传成功");
                    file.delete();
                } catch (Exception e) {
                    broadcast(STATE_ERROR, "上传失败：" + e.getMessage());
                } finally {
                    uploading = false;
                    stopForeground(true);
                    stopSelf();
                }
            }
        }, "recording-upload").start();
    }

    private Notification buildNotification(String text) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, openIntent, 0);
        Notification.Builder builder = new Notification.Builder(this)
                .setContentTitle("工牌接诊")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true);
        return builder.build();
    }

    private void uploadRecording(File file, int durationSec, String cookie) throws Exception {
        if (cookie == null || cookie.trim().length() == 0) {
            throw new IllegalStateException("未检测到登录状态，请先在页面登录顾问账号");
        }
        String boundary = "----GongpaiBoundary" + System.currentTimeMillis();
        HttpURLConnection conn = (HttpURLConnection) new URL(uploadUrl).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(120000);
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Cookie", cookie);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        OutputStream raw = new BufferedOutputStream(conn.getOutputStream());
        writeFormField(raw, boundary, "duration_sec", String.valueOf(durationSec));
        writeFileField(raw, boundary, "audio", file, "audio/mp4");
        raw.write(("--" + boundary + "--\r\n").getBytes("UTF-8"));
        raw.flush();
        raw.close();

        int code = conn.getResponseCode();
        InputStream body = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String text = readSmall(body);
        conn.disconnect();
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP " + code + " " + text);
        }
    }

    private void writeFormField(OutputStream out, String boundary, String name, String value) throws Exception {
        out.write(("--" + boundary + "\r\n").getBytes("UTF-8"));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes("UTF-8"));
        out.write((value + "\r\n").getBytes("UTF-8"));
    }

    private void writeFileField(OutputStream out, String boundary, String name, File file, String contentType) throws Exception {
        out.write(("--" + boundary + "\r\n").getBytes("UTF-8"));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + file.getName() + "\"\r\n").getBytes("UTF-8"));
        out.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes("UTF-8"));
        InputStream in = new BufferedInputStream(new FileInputStream(file));
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        in.close();
        out.write("\r\n".getBytes("UTF-8"));
    }

    private String readSmall(InputStream in) throws Exception {
        if (in == null) {
            return "";
        }
        byte[] buf = new byte[4096];
        int n = in.read(buf);
        in.close();
        if (n <= 0) {
            return "";
        }
        return new String(buf, 0, n, "UTF-8");
    }

    private void broadcast(String state, String message) {
        Intent intent = new Intent(BROADCAST_STATUS);
        intent.putExtra(EXTRA_STATE, state);
        intent.putExtra(EXTRA_MESSAGE, message);
        sendBroadcast(intent);
    }

    private void cleanupRecorder() {
        if (recorder != null) {
            try {
                recorder.release();
            } catch (Exception ignored) {
            }
            recorder = null;
        }
    }

    @Override
    public void onDestroy() {
        cleanupRecorder();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
