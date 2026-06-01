package com.airec.bledemo;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import com.airec.blesdk.AIRECBleCallback;
import com.airec.blesdk.AIRECBleDevice;
import com.airec.blesdk.AIRECBleFile;
import com.airec.blesdk.AIRECBleManager;
import com.airec.bledemo.net.Uploader;
import com.airec.bledemo.recording.PhoneMicService;
import com.airec.bledemo.recording.RecordingBus;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 蓝牙录音笔（AIREC）录音控制器 —— B 模式（设备本地录音 → 蓝牙下载文件 → 上传）。
 *
 * 为什么是 B 模式：录音笔机身自己录音、存自己存储里，跟手机进程/蓝牙连不连无关。即便 App 被杀、
 * 蓝牙断开、手机重启，录音都还在笔里，重连后补下载即可——物理上不丢。这是对抗"后台被杀中断"的终极兜底。
 *
 * 状态统一通过 {@link RecordingBus} 上报，复用 ConsultantActivity 既有的录音条 UI 与上传成功后的绑定流程
 * （与手机麦克风路径同一管线）。
 *
 * ⚠️ 未经真机验证的假设（见类内 TODO 标注），需要连上真录音笔后核对。
 */
public class PenController {

    private static final String TAG = "PenController";

    /** 后端 /api/consultant/upload 接受的扩展名（不含 opus）。 */
    private static final String[] ACCEPTED_EXT = {"webm", "mp3", "wav", "m4a", "mp4", "ogg", "aac", "amr"};

    public interface Listener {
        /** 录音笔未连接，需要 UI 去打开扫描/连接页。 */
        void onPenNeedConnect();
    }

    private final Context appCtx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    // 一次录音的上下文
    private volatile String cookie;
    private volatile String uploadUrl;
    private volatile boolean waitingForFile = false;
    private volatile String pendingFileName = null;
    private long startElapsedMs = 0;

    public PenController(Context ctx, Listener l) {
        this.appCtx = ctx.getApplicationContext();
        this.listener = l;
    }

    public boolean isConnected() {
        try { return AIRECBleManager.getInstance().isConnected(); }
        catch (Exception e) { return false; }
    }

    public AIRECBleCallback getCallback() { return callback; }

    /** 让 SDK 的回调指向本控制器（进入接诊页 / 开始用笔录音前调用）。 */
    public void activate() {
        AIRECBleManager.getInstance().setCallback(callback);
        App.setMainCallback(callback); // ScanActivity 连接成功后会切回这个
    }

    /** 开始用录音笔录音。未连接则请求 UI 去连接。 */
    public void startRecording(String cookie, String uploadUrl) {
        if (!isConnected()) {
            if (listener != null) listener.onPenNeedConnect();
            return;
        }
        this.cookie = cookie;
        this.uploadUrl = uploadUrl;
        this.waitingForFile = false;
        this.pendingFileName = null;
        this.startElapsedMs = SystemClock.elapsedRealtime();
        activate();
        try {
            AIRECBleManager.getInstance().startRecord();
            post(PhoneMicService.STATE_RECORDING, "录音中…（录音笔）", 0, -1);
        } catch (Exception e) {
            Log.e(TAG, "startRecord failed", e);
            post(PhoneMicService.STATE_ERROR, "录音笔启动失败：" + e.getMessage(), 0, -1);
        }
    }

    /** 结束录音（笔停止后会触发取文件→下载→上传链路）。 */
    public void stopRecording() {
        try {
            post(PhoneMicService.STATE_UPLOADING, "正在保存录音笔文件…", elapsedSec(), -1);
            AIRECBleManager.getInstance().endRecord();
        } catch (Exception e) {
            Log.e(TAG, "endRecord failed", e);
            post(PhoneMicService.STATE_ERROR, "录音笔停止失败：" + e.getMessage(), elapsedSec(), -1);
        }
    }

    // ============ SDK 回调 ============

    private final AIRECBleCallback callback = new AIRECBleCallback() {
        @Override
        public void onRecordStateChanged(boolean recording, String fileName) {
            if (recording) {
                if (startElapsedMs == 0) startElapsedMs = SystemClock.elapsedRealtime();
                post(PhoneMicService.STATE_RECORDING, "录音中…（录音笔）", elapsedSec(), -1);
            } else {
                // 录音已停。设备把这段写入文件，文件名可能由 fileName 给出（也可能为空）。
                // TODO(真机核对)：fileName 是否就是设备文件列表里的文件名；保存到文件是否需要等待。
                pendingFileName = fileName;
                waitingForFile = true;
                post(PhoneMicService.STATE_UPLOADING, "正在获取录音笔文件…", elapsedSec(), -1);
                // 略等设备落盘后再拉取文件列表
                main.postDelayed(() -> {
                    try { AIRECBleManager.getInstance().fetchFileList(); }
                    catch (Exception e) { Log.e(TAG, "fetchFileList failed", e); }
                }, 1500);
            }
        }

        @Override
        public void onFileListUpdated(List<AIRECBleFile> files) {
            if (!waitingForFile || files == null || files.isEmpty()) return;
            AIRECBleFile target = pickRecorded(files, pendingFileName);
            if (target == null) {
                Log.w(TAG, "未能在文件列表里定位刚录的文件，pendingFileName=" + pendingFileName);
                return; // 等下一次列表刷新；也可能需要再次 fetch
            }
            waitingForFile = false;
            post(PhoneMicService.STATE_UPLOADING, "下载录音中…", elapsedSec(), -1);
            try { AIRECBleManager.getInstance().downloadFile(target); }
            catch (Exception e) {
                Log.e(TAG, "downloadFile failed", e);
                post(PhoneMicService.STATE_ERROR, "下载录音失败：" + e.getMessage(), elapsedSec(), -1);
            }
        }

        @Override
        public void onFileDownloadProgress(AIRECBleFile file, int progress) {
            post(PhoneMicService.STATE_UPLOADING, "下载录音 " + progress + "%", elapsedSec(), -1);
        }

        @Override
        public void onFileDownloadComplete(AIRECBleFile file, String localPath) {
            processAndUpload(file, localPath);
        }

        @Override
        public void onFileDownloadFailed(AIRECBleFile file, String reason) {
            post(PhoneMicService.STATE_ERROR, "下载录音失败：" + reason, elapsedSec(), -1);
        }
    };

    // ============ 下载完成后：必要时转码 → 上传 ============

    private void processAndUpload(final AIRECBleFile file, final String localPath) {
        final int durSec = (file != null && file.getDurationSec() > 0) ? (int) file.getDurationSec() : elapsedSec();
        final String startCookie = cookie;
        final String startUrl = uploadUrl;
        worker.submit(() -> {
            try {
                if (TextUtils.isEmpty(localPath) || !new File(localPath).exists()) {
                    post(PhoneMicService.STATE_ERROR, "下载文件不存在", durSec, -1);
                    return;
                }
                String uploadPath;
                String name, mime;
                // 录音笔下载的是私有分帧(5B50/4B41 同步头 + SILK-WB opus)格式，
                // 用 libopus 跳 2 字节同步头逐帧解码成 16kHz 单声道 wav 再上传。
                // （已用真机样本离线验证：408/408 帧解出干净语音，削顶 0%。）
                String wav = OpusBridge.decodeToWav(localPath, localPath + ".wav");
                if (wav != null && new File(wav).exists() && new File(wav).length() > 44) {
                    uploadPath = wav;
                    name = stripExt(baseName(localPath)) + ".wav";
                    mime = "audio/wav";
                } else {
                    // 不是私有格式：按扩展名当标准音频直传；不认识就报错
                    String ext = extOf(localPath);
                    if (isAccepted(ext)) {
                        uploadPath = localPath;
                        name = baseName(localPath);
                        mime = mimeFor(ext);
                    } else {
                        post(PhoneMicService.STATE_ERROR, "录音解码失败（未知格式）", durSec, -1);
                        return;
                    }
                }
                Uploader.Result r = Uploader.upload(new File(uploadPath), durSec, startCookie, startUrl, name, mime);
                if (r.ok) {
                    post(PhoneMicService.STATE_IDLE, "已上传，请选择顾客", durSec, r.recordingId);
                } else {
                    post(PhoneMicService.STATE_ERROR, "上传失败：" + r.error, durSec, -1);
                }
            } catch (Exception e) {
                Log.e(TAG, "processAndUpload failed", e);
                post(PhoneMicService.STATE_ERROR, "处理录音失败：" + e.getMessage(), durSec, -1);
            } finally {
                startElapsedMs = 0;
            }
        });
    }

    // ============ 工具 ============

    /**
     * 在文件列表里定位刚录的那段：优先按文件名精确匹配，否则按文件名倒序取最新。
     * （文件名通常含时间戳，倒序即最新；与 Demo MainActivity 的排序一致。getCreateTime() 返回 String，不用它比大小。）
     */
    private AIRECBleFile pickRecorded(List<AIRECBleFile> files, String name) {
        if (!TextUtils.isEmpty(name)) {
            for (AIRECBleFile f : files) {
                if (f != null && name.equals(f.getFileName())) return f;
            }
        }
        AIRECBleFile newest = null;
        for (AIRECBleFile f : files) {
            if (f == null || f.getFileName() == null) continue;
            if (newest == null || f.getFileName().compareTo(newest.getFileName()) > 0) {
                newest = f;
            }
        }
        return newest;
    }

    private int elapsedSec() {
        if (startElapsedMs == 0) return 0;
        return (int) Math.max(0, (SystemClock.elapsedRealtime() - startElapsedMs) / 1000);
    }

    private static boolean isAccepted(String ext) {
        if (ext == null) return false;
        for (String e : ACCEPTED_EXT) if (e.equals(ext)) return true;
        return false;
    }

    private static String mimeFor(String ext) {
        if (ext == null) return "application/octet-stream";
        switch (ext) {
            case "mp3": return "audio/mpeg";
            case "wav": return "audio/wav";
            case "m4a":
            case "mp4": return "audio/mp4";
            case "aac": return "audio/aac";
            case "amr": return "audio/amr";
            case "ogg": return "audio/ogg";
            case "webm": return "audio/webm";
            default: return "application/octet-stream";
        }
    }

    private static String extOf(String path) {
        if (path == null) return "";
        String n = baseName(path);
        int dot = n.lastIndexOf('.');
        return dot >= 0 ? n.substring(dot + 1).toLowerCase() : "";
    }

    private static String baseName(String path) {
        if (path == null) return "";
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static String stripExt(String name) {
        if (name == null) return "rec";
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(0, dot) : name;
    }

    private void post(String state, String msg, int durSec, long recId) {
        RecordingBus.post(state, msg, durSec, recId);
    }
}
