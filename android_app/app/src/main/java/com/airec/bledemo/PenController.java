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
        /** 连上后查到的录音笔当前录音状态（用户可能在 App 退出期间笔仍在物理录音）。 */
        void onPenRecordStatus(boolean recording);
        /** 录音笔上报的当前录音时长（秒），用于同步计时。 */
        void onPenRecordDuration(int durationSec);
        /** 录音笔连接状态变化（连上/断开），用于刷新网页连接指示。 */
        void onPenConnected(boolean connected);
        /** 录音暂停状态变化（暂停/继续）。 */
        void onPenPaused(boolean paused);
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
    private volatile String autoConnectMac = null;   // App 打开时静默自动连接的目标笔 MAC
    private volatile boolean penPaused = false;       // 当前是否暂停
    private volatile boolean appInitiatedPauseResume = false;  // 区分 App 主动 vs 笔上按键触发的暂停
    private volatile int downloadRetries = 0;          // 下载失败重试计数（笔回 0xFD=文件未就绪时重试）
    private static final int MAX_DOWNLOAD_RETRIES = 3;
    // ★状态机门控：只认"用户在 App 里发起的录音会话"，无视笔自发的录音状态帧（如声控/VOX 自动分段）
    private volatile boolean userRecording = false;    // 用户点了开始、本次会话进行中
    private volatile boolean stopping = false;         // 用户点了结束、正在收尾这一段
    private volatile boolean stopHandled = false;      // 结束后的那次"录音停止"是否已处理（防兜底重复）

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

    /**
     * App 打开/回到前台时，静默自动连接上次那支笔（不弹扫描页）。
     * 需调用方已确保有 BLE 扫描/连接权限。已连接或正在自动连则忽略。
     */
    public void autoConnect(String savedMac) {
        if (savedMac == null || savedMac.isEmpty()) return;
        if (isConnected() || autoConnectMac != null) return;
        autoConnectMac = savedMac;
        activate();
        try {
            AIRECBleManager.getInstance().startScan();
            Log.d(TAG, "autoConnect scanning for " + savedMac);
        } catch (Exception e) {
            Log.e(TAG, "autoConnect startScan failed", e);
            autoConnectMac = null;
            return;
        }
        // 超时停扫（扫不到就放弃，用户可手动连）
        main.postDelayed(() -> {
            if (autoConnectMac != null) {
                try { AIRECBleManager.getInstance().stopScan(); } catch (Exception ignored) {}
                autoConnectMac = null;
                Log.d(TAG, "autoConnect timeout");
            }
        }, 12000);
    }

    /**
     * 检测并初始化录音笔，使其满足 App 使用条件（每次连接都检查，不达标才设，幂等）：
     *  - 不自动关机（idleShutdown=0）：避免接诊中途笔自己关机丢录音；
     *  - 不分段（segmentDuration=0）：避免长录音被切成多段，导致只下到一段丢内容；
     *  - 开机不自动录音（powerOnRecord=false）：避免笔开机自己录、产生意外文件。
     * 需在参数已拉到（fetchAllDeviceInfo / onInitParamUpdated 后）调用，读到的是缓存值。
     */
    private void ensurePenConfigured() {
        try {
            AIRECBleManager mgr = AIRECBleManager.getInstance();
            if (!mgr.isConnected()) return;
            // ★关声控/VOX（最关键）：出厂默认开着，会"有声就录、静音就停"每3-4秒自动分段，
            //   导致笔不停自发录音、App 状态栏疯狂闪、结束按钮压不住。必须关掉。
            // 幂等：只在读到≠目标值时下发（避免 set→SDK刷新→onInitParamUpdated→再set 的死循环）。
            if (mgr.getNoiseSwitch())          { mgr.setNoiseSwitch(false);   Log.d(TAG, "笔初始化：关闭声控(VOX)"); }
            if (mgr.getIdleShutdown() != 0)    { mgr.setIdleShutdown(0);      Log.d(TAG, "笔初始化：关闭自动关机"); }
            if (mgr.getSegmentDuration() != 0) { mgr.setSegmentDuration(0);   Log.d(TAG, "笔初始化：关闭分段(整段录)"); }
            if (mgr.getPowerOnRecord())        { mgr.setPowerOnRecord(false); Log.d(TAG, "笔初始化：关闭开机自动录音"); }
        } catch (Exception e) {
            Log.e(TAG, "ensurePenConfigured failed", e);
        }
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
        this.penPaused = false;
        this.downloadRetries = 0;
        this.stopping = false;
        this.stopHandled = false;
        this.userRecording = true;   // ★开门：本次是用户发起的录音会话
        this.startElapsedMs = SystemClock.elapsedRealtime();
        activate();
        try {
            AIRECBleManager.getInstance().startRecord();
            post(PhoneMicService.STATE_RECORDING, "录音中…（录音笔）", 0, -1);
        } catch (Exception e) {
            Log.e(TAG, "startRecord failed", e);
            userRecording = false;
            post(PhoneMicService.STATE_ERROR, "录音笔启动失败：" + e.getMessage(), 0, -1);
        }
    }

    /**
     * 采纳一段“笔已在录、但 App 这边没发起”的录音（用户退出 App 期间笔仍在录的场景）：
     * 补齐上传所需的 cookie / uploadUrl，使之后停止录音能正常下载并上传。
     */
    public void adoptRecording(String cookie, String uploadUrl) {
        this.cookie = cookie;
        this.uploadUrl = uploadUrl;
        this.waitingForFile = false;
        this.pendingFileName = null;
        this.stopping = false;
        this.stopHandled = false;
        this.userRecording = true;   // 采纳=视为用户会话，后续结束才走上传
        if (this.startElapsedMs == 0) this.startElapsedMs = SystemClock.elapsedRealtime();
    }

    /** 暂停录音。 */
    public void pause() {
        if (penPaused) return;
        appInitiatedPauseResume = true;
        penPaused = true;
        try { AIRECBleManager.getInstance().pauseRecord(); }
        catch (Exception e) { Log.e(TAG, "pauseRecord failed", e); }
        if (listener != null) main.post(() -> listener.onPenPaused(true));
    }

    /** 继续录音。 */
    public void resume() {
        if (!penPaused) return;
        appInitiatedPauseResume = true;
        penPaused = false;
        try { AIRECBleManager.getInstance().resumeRecord(); }
        catch (Exception e) { Log.e(TAG, "resumeRecord failed", e); }
        if (listener != null) main.post(() -> listener.onPenPaused(false));
    }

    /** 结束录音（笔停止后会触发取文件→下载→上传链路）。 */
    public void stopRecording() {
        if (!userRecording && !stopping) {
            // 用户其实没在录（不该发生，防御）→ 直接回 idle，不下载不上传
            post(PhoneMicService.STATE_IDLE, "已结束", 0, -1);
            return;
        }
        penPaused = false;
        downloadRetries = 0;
        stopping = true;          // ★放行随后那次"录音停止"帧去走上传
        stopHandled = false;
        try {
            post(PhoneMicService.STATE_UPLOADING, "正在保存录音笔文件…", elapsedSec(), -1);
            AIRECBleManager.getInstance().endRecord();
        } catch (Exception e) {
            Log.e(TAG, "endRecord failed", e);
            userRecording = false; stopping = false;
            post(PhoneMicService.STATE_ERROR, "录音笔停止失败：" + e.getMessage(), elapsedSec(), -1);
            return;
        }
        // 兜底：4s 内若笔没回"录音停止"帧，主动取文件列表走上传，避免卡在"保存中"
        main.postDelayed(() -> {
            if (stopping && !stopHandled) {
                Log.w(TAG, "endRecord 后笔未回停止帧，兜底取文件");
                stopHandled = true;
                userRecording = false;
                waitingForFile = true;
                try { AIRECBleManager.getInstance().fetchFileList(); }
                catch (Exception ignored) {}
            }
        }, 4000);
    }

    // ============ SDK 回调 ============

    private final AIRECBleCallback callback = new AIRECBleCallback() {
        @Override
        public void onRecordStateChanged(boolean recording, String fileName) {
            // ★门控：只认用户发起的会话。idle 时笔自发的录音帧（声控/VOX 自动分段）一律忽略，
            //   不 post、不 fetchFileList、不上传——杜绝"陪伴进行中↔保存中"闪烁与 fetchFileList 刷屏。
            if (!userRecording && !stopping) {
                Log.d(TAG, "忽略笔自发录音帧 recording=" + recording);
                return;
            }
            if (recording) {
                // 用户会话中：保持"录音中"显示（stopping 期间忽略，避免结束后被自发 true 拉回）
                if (!stopping) {
                    if (startElapsedMs == 0) startElapsedMs = SystemClock.elapsedRealtime();
                    post(PhoneMicService.STATE_RECORDING, "录音中…（录音笔）", elapsedSec(), -1);
                }
            } else {
                // 录音停止帧：
                if (!stopping) {
                    // 用户还没点结束，却收到停止（理论上关了声控不会有；防御性忽略，不半截上传）
                    Log.d(TAG, "会话中笔自发停止帧，忽略");
                    return;
                }
                if (stopHandled) return;   // 已被兜底处理过
                stopHandled = true;
                userRecording = false;     // ★关门：会话结束，后续自发帧再次被忽略
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
            downloadRetries = 0;
            processAndUpload(file, localPath);
        }

        @Override
        public void onFileDownloadFailed(AIRECBleFile file, String reason) {
            // 暂停过的录音，笔可能还没把文件落盘好（回 0xFD），隔几秒重试取列表+下载。
            if (downloadRetries < MAX_DOWNLOAD_RETRIES) {
                downloadRetries++;
                Log.w(TAG, "download failed (" + reason + ")，第 " + downloadRetries + " 次重试");
                post(PhoneMicService.STATE_UPLOADING, "录音保存中，重试…", elapsedSec(), -1);
                waitingForFile = true;
                main.postDelayed(() -> {
                    try { AIRECBleManager.getInstance().fetchFileList(); }
                    catch (Exception e) { Log.e(TAG, "retry fetchFileList failed", e); }
                }, 2500);
            } else {
                downloadRetries = 0;
                post(PhoneMicService.STATE_ERROR, "下载录音失败：" + reason, elapsedSec(), -1);
            }
        }

        @Override
        public void onDeviceFound(AIRECBleDevice device) {
            // 静默自动连接：扫到上次那支笔就直接连
            if (autoConnectMac != null && device != null && autoConnectMac.equals(device.getAddress())) {
                autoConnectMac = null;
                try {
                    AIRECBleManager.getInstance().stopScan();
                    AIRECBleManager.getInstance().connect(device);
                    Log.d(TAG, "autoConnect matched, connecting " + device.getAddress());
                } catch (Exception e) { Log.e(TAG, "autoConnect connect failed", e); }
            }
        }

        @Override
        public void onConnected(AIRECBleDevice device) {
            autoConnectMac = null;
            if (listener != null) main.post(() -> listener.onPenConnected(true));
            // 连上后查状态+参数（AIREC 需点时间稳定）。参数回来后在 onInitParamUpdated 里按需初始化设置。
            main.postDelayed(() -> {
                try { AIRECBleManager.getInstance().fetchAllDeviceInfo(); }
                catch (Exception e) { Log.e(TAG, "fetchAllDeviceInfo failed", e); }
            }, 600);
            // 兜底：万一 onInitParamUpdated 没回调，3.5s 后也按当前缓存值检测并初始化
            main.postDelayed(() -> ensurePenConfigured(), 3500);
        }

        @Override
        public void onInitParamUpdated() {
            ensurePenConfigured();
        }

        @Override
        public void onDisconnected(AIRECBleDevice device, String reason) {
            if (listener != null) main.post(() -> listener.onPenConnected(false));
        }

        @Override
        public void onRecordStatusQueried(boolean recording, boolean paused, String fileName) {
            if (recording && !TextUtils.isEmpty(fileName)) pendingFileName = fileName;
            penPaused = recording && paused;
            if (listener != null) main.post(() -> {
                listener.onPenRecordStatus(recording);
                if (recording) listener.onPenPaused(paused);
            });
        }

        @Override
        public void onRecordPaused() {
            // 暂停状态切换通知：App 主动发的已处理过；笔上按键触发的则在此 toggle。
            if (appInitiatedPauseResume) {
                appInitiatedPauseResume = false;
                return;
            }
            penPaused = !penPaused;
            final boolean p = penPaused;
            if (listener != null) main.post(() -> listener.onPenPaused(p));
        }

        @Override
        public void onRecordDurationUpdated(long durationSec) {
            // 只在用户会话内同步时长；笔自发录音的时长 tick 一律忽略
            if (!userRecording) return;
            if (listener != null) main.post(() -> listener.onPenRecordDuration((int) durationSec));
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
                // 一次会话收尾：复位所有门控，回到"忽略笔自发帧"的 idle
                startElapsedMs = 0;
                stopping = false;
                stopHandled = false;
                userRecording = false;
                waitingForFile = false;
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
