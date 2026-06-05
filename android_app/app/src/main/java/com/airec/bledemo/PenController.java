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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 蓝牙录音笔（AIREC）录音控制器 —— B 模式（设备本地录音 → 蓝牙下载文件 → 上传）。
 *
 * 设计（2026-06 重构为「镜像 + 异步」）：
 *  - 【镜像】以录音笔真实录音状态为准：笔一开始录音（不管是 App 发命令、还是笔上按按钮/声控自动），
 *    App 就显示「陪伴进行中」；笔一停，就把这段排进后台上传队列。App 是笔的一面镜子。
 *  - 【异步】结束录音后立刻回到可再次开始的状态，下载+解码+上传在后台队列里一条条慢慢做，
 *    不阻塞用户开下一段（"上一段还在传"时也能开新的）。受硬件限制：笔录音时无法下载(回 0xFD)，
 *    所以后台 worker 只在「笔没在录」时跑；笔一开始录就暂停 worker，录完再续。
 *  - 【防误传】每个上传任务记录开始墙上时间，下载到的文件时间戳必须 >= 它（拒收笔里更早的旧录音）。
 *
 * B 模式的好处：录音笔机身自己录音存自己存储，App 被杀/蓝牙断/手机重启都不丢，重连补下载即可。
 * 状态通过 {@link RecordingBus} 上报当前录音 UI；后台上传进度走 {@link Listener#onPenPendingChanged}。
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
        /** 后台待传/在传段数 + 累计保存失败段数变化（异步上传指示，不阻塞当前录音 UI）。 */
        void onPenPendingChanged(int pending, int failed);
        /** 一段后台上传成功（recordingId>0）→ 刷新未归档列表/绑定。 */
        void onPenUploaded(long recordingId);
        /** 已建占位片段(后端记录) → 刷新未归档列表，让它带服务日期立刻显示出来。 */
        void onPenPlaceholderCreated();
        /** 当前后台下载进度(0-100)，用于显示"保存中 N%"（大文件几分钟，给个进度）。 */
        void onPenProgress(int percent);
    }

    private final Context appCtx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    // 上传上下文（cookie/上传地址）：App 发起会传进来；笔自发录音也要用，所以单独保存、由接诊页刷新。
    private volatile String cookie;
    private volatile String uploadUrl;

    // 连接/自动连接
    private volatile String autoConnectMac = null;
    private volatile String lastConnectedMac = null;   // 最近连上的笔 MAC，用于"自动断开重连刷新文件列表"
    private volatile long lastReflushMs = 0;           // 上次自动重连刷新的时刻(限频)
    private volatile boolean penSettingsWritten = false;

    // ★真连接判定 + 在线活性：不信 isConnected()(只看GATT指针) 也不无条件信 SDK 的 onConnected(有3秒假兜底)。
    //   只认"最近收到过笔的真实业务回包"才算真在线。
    private volatile boolean verifiedConnected = false;   // App 层"已验证在线"
    private volatile long    lastRxMs = 0;                // 最近一次收到笔真实回包(elapsedRealtime)
    private volatile int     hbMissed = 0;                // 心跳连续未回次数
    private static final long HANDSHAKE_TIMEOUT_MS = 5000; // 连上后等"真回包"的握手超时
    private static final long STALE_RX_MS = 12000;        // 超过这么久没回包 → 视为不在线
    private static final long HB_INTERVAL_MS = 8000;      // 心跳间隔(空闲)
    private static final long HB_INTERVAL_BUSY_MS = 15000; // 录音/下载时放宽

    // 暂停（保留，网页已不暴露暂停）
    private volatile boolean penPaused = false;
    private volatile boolean appInitiatedPauseResume = false;

    // ★镜像状态：以笔为准
    private volatile boolean penRecording = false;   // 笔当前是否真的在录音
    private volatile boolean sessionActive = false;   // App 当前是否在显示一段录音（"陪伴进行中"）
    private volatile int     sessionGen = 0;          // 会话代号：每段开始/结束都+1，用于让"结束兜底"只作用于它自己那段
    private volatile boolean sessionAppInitiated = false; // 这段是不是"你在App点开始"发起的（失败时只对这种大声提示；笔自发空录静默丢）
    private volatile boolean appStartPending = false; // App 发了开始命令、还在等笔确认（用于休眠检测 + "启动中"显示）
    private volatile String  sessionFileName = null;  // 当前这段笔在录的文件名
    private volatile long    sessionStartWallMs = 0;  // 当前这段开始的墙上时间
    private long startElapsedMs = 0;                  // 当前这段计时起点（elapsedRealtime）
    // ★实时流捕获：录音时把蓝牙实时流(80字节KA块)拼成完整 .ops，结束时整包直传(opus端到端，不下载不解码)
    private volatile java.io.ByteArrayOutputStream sessionStreamBuf = null;
    private volatile boolean sessionStreamComplete = false;  // 本段流是否完整(蓝牙全程没断)
    private volatile long sessionStreamBytes = 0;
    private volatile int  sessionStreamFrames = 0;

    private static final long CONFIRM_TIMEOUT_MS = 5000; // 发开始命令后等笔确认的超时（超时判定休眠/关机）

    // ★后台上传队列
    private static final class UploadTask {
        final String fileName, cookie, uploadUrl, sn;
        final int durSec;
        final long startWallMs;
        final boolean appInitiated;   // 你主动点开始的？失败时只对这种大声提示
        volatile long placeholderId = -1;  // 已建的"占位片段"后端记录 id；上传时回填它(不新建)。-1=未建/降级
        volatile long firstAttemptMs = 0;  // 首次尝试定位文件的时刻；笔提交延迟时据此判断挂多久才放弃
        volatile String localOpsPath = null;  // ★非空=直传这个本地拼好的 .ops(实时流完整，不下载不解码)
        UploadTask(String fileName, String cookie, String uploadUrl, String sn, int durSec, long startWallMs, boolean appInitiated) {
            this.fileName = fileName; this.cookie = cookie; this.uploadUrl = uploadUrl;
            this.sn = sn; this.durSec = durSec; this.startWallMs = startWallMs; this.appInitiated = appInitiated;
        }
    }
    private final ConcurrentLinkedQueue<UploadTask> uploadQueue = new ConcurrentLinkedQueue<>();
    private volatile boolean workerBusy = false;       // 后台正在处理某条
    private volatile UploadTask currentTask = null;     // 当前后台任务
    private volatile UploadTask inflightTask = null;    // 已进入"解码+上传"阶段的任务（去重：别再被 kickWorker 重复处理）
    private volatile UploadTask pendingRecovery = null; // 录音中途断开(如笔被按键关机)的那段，存它信息，重连后自动补传
    private volatile int failedCount = 0;               // 累计"保存失败被放弃"的段数（本批，开新一段时清零）
    private volatile boolean waitingForFile = false;    // worker 正在等文件列表/定位文件
    private volatile String pendingFileName = null;     // worker 要找的文件名
    private volatile int fileListAttempts = 0;
    private volatile int downloadRetries = 0;
    private static final int MAX_FILELIST_ATTEMPTS = 4;
    private static final int MAX_DOWNLOAD_RETRIES = 3;
    private static final long DEFER_MAX_MS = 3 * 60 * 1000L; // 文件未出现在笔列表时的重试上限(跨过连录/笔忙/落盘延迟)，超时才放弃

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
        App.setMainCallback(callback);
    }

    /** 设置/刷新上传上下文：接诊页在进入/连接/开始时调用，确保笔自发录音也有 cookie/上传地址可用。 */
    public void setUploadContext(String cookie, String uploadUrl) {
        if (cookie != null && !cookie.isEmpty()) this.cookie = cookie;
        if (uploadUrl != null && !uploadUrl.isEmpty()) this.uploadUrl = uploadUrl;
    }

    /** 当前后台待传/在传段数。 */
    public int pendingCount() { return uploadQueue.size(); }

    /** 累计保存失败段数（本批）。 */
    public int pendingFailedCount() { return failedCount; }

    /** 把当前正在显示的这段标记为"用户主动发起"（只升级不下调）：失败时大声提示重录，而非当空录静默丢。 */
    public void markCurrentSessionAppInitiated() {
        if (sessionActive) sessionAppInitiated = true;
    }

    /** 任意"只有真设备才会回"的回调都调它：刷新在线证据；首次收到回包才真正点亮"已连接"。 */
    private void markPenResponded() {
        lastRxMs = SystemClock.elapsedRealtime();
        if (!verifiedConnected) {
            verifiedConnected = true;
            main.removeCallbacks(handshakeTimeout);
            Log.d(TAG, "收到笔真实回包 → 确认真连上");
            if (listener != null) main.post(() -> listener.onPenConnected(true));
            startHeartbeat();
            // ★重连后：把上次录音中途断开(笔关机)那段补传(它在笔存储里)。
            if (pendingRecovery != null) {
                UploadTask r = pendingRecovery; pendingRecovery = null;
                uploadQueue.add(r);
                Log.d(TAG, "重连后补传中断录音 file=" + r.fileName);
                notifyPending();
            }
            // 重连后重新尝试队列里所有任务(含之前"延迟提交、暂未找到"挪到队尾的)：
            // 文件这时多半已被笔提交进列表，能找到就下载上传，不丢。笔在录时 kickWorker 会自动等空闲。
            kickWorker();
        }
    }

    /** 对外的"真在线"判据：链路在 且 已验证 且 最近 STALE_RX_MS 内收到过回包。UI/能否开始录音都用它，不用裸 isConnected()。 */
    public boolean isPenAlive() {
        try {
            return verifiedConnected
                    && AIRECBleManager.getInstance().isConnected()
                    && (SystemClock.elapsedRealtime() - lastRxMs) < STALE_RX_MS;
        } catch (Exception e) { return false; }
    }

    /** 握手超时：连上后发了命令但 5s 内笔一个回包都没回 → 判定假连接（堵住 SDK 的 3s fallback-onConnected）。 */
    private final Runnable handshakeTimeout = new Runnable() {
        @Override public void run() {
            if (!verifiedConnected) {
                Log.w(TAG, "连上但笔无回包 → 判定假连接，断开");
                try { AIRECBleManager.getInstance().disconnect(); } catch (Exception ignored) {}
                if (listener != null) main.post(() -> listener.onPenConnected(false));
            }
        }
    };

    /** 主动心跳探活：笔关机时 Android 不保证及时回 onDisconnected，必须主动 ping，连续静默就判离线。 */
    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            try { if (!AIRECBleManager.getInstance().isConnected()) { stopHeartbeat(); return; } }
            catch (Exception e) { stopHeartbeat(); return; }
            long now = SystemClock.elapsedRealtime();
            boolean busy = penRecording || sessionActive || workerBusy || waitingForFile;
            // 录音/下载中有业务帧在流动就当心跳，不主动发命令抢通道(0xFD)
            if (busy && (now - lastRxMs) < HB_INTERVAL_BUSY_MS) {
                hbMissed = 0; main.postDelayed(this, HB_INTERVAL_BUSY_MS); return;
            }
            if ((now - lastRxMs) > (HB_INTERVAL_MS + 4000)) {
                hbMissed++;
                Log.w(TAG, "心跳未回包，连续 " + hbMissed + " 次");
                if (hbMissed >= 2) {           // 连续 2 次静默(≈12~16s) → 判离线
                    Log.w(TAG, "笔失联 → 断开，连接指示如实改未连接");
                    verifiedConnected = false;
                    try { AIRECBleManager.getInstance().disconnect(); } catch (Exception ignored) {}
                    if (listener != null) main.post(() -> listener.onPenConnected(false));
                    stopHeartbeat();
                    return;
                }
            } else {
                hbMissed = 0;
            }
            if (!busy) {
                try { AIRECBleManager.getInstance().fetchDeviceInfo(); } catch (Exception ignored) {}
            }
            main.postDelayed(this, busy ? HB_INTERVAL_BUSY_MS : HB_INTERVAL_MS);
        }
    };
    private void startHeartbeat() { hbMissed = 0; main.removeCallbacks(heartbeat); main.postDelayed(heartbeat, HB_INTERVAL_MS); }
    private void stopHeartbeat() { main.removeCallbacks(heartbeat); hbMissed = 0; }

    /**
     * App 打开/回到前台时，静默自动连接上次那支笔（不弹扫描页）。
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
        main.postDelayed(() -> {
            if (autoConnectMac != null) {
                try { AIRECBleManager.getInstance().stopScan(); } catch (Exception ignored) {}
                autoConnectMac = null;
                Log.d(TAG, "autoConnect timeout");
            }
        }, 12000);
    }

    /** 连上后只读取设备设置打日志，不再写任何设置（见交接文档：写声控关不掉、还有风险）。 */
    private void ensurePenConfigured() {
        try {
            AIRECBleManager mgr = AIRECBleManager.getInstance();
            if (!mgr.isConnected()) return;
            Log.d(TAG, "笔设置(只读不改): noise=" + mgr.getNoiseSwitch()
                    + " segDur=" + mgr.getSegmentDuration()
                    + " powerOnRec=" + mgr.getPowerOnRecord()
                    + " idle=" + mgr.getIdleShutdown());
        } catch (Exception e) {
            Log.e(TAG, "ensurePenConfigured(read) failed", e);
        }
    }

    // ============ App 主动控制 ============

    /** App 点「开启陪伴」：先确认笔真在线，再发开始命令、等笔确认真开录（双闸门防假连接空录）。 */
    public void startRecording(String cookie, String uploadUrl) {
        setUploadContext(cookie, uploadUrl);
        if (penRecording || sessionActive) {
            // 笔已经在录（声控/笔上操作），镜像已在显示 → 不重复发命令，但要回推真实状态纠正网页
            // （否则接诊页可能已乐观显示 starting，按钮卡 disabled）。
            Log.d(TAG, "startRecording: 笔已在录，忽略重复开始，回推 recording 纠正UI");
            markCurrentSessionAppInitiated();   // 用户确实点了开始 → 失败时大声提示而非静默丢
            post(PhoneMicService.STATE_RECORDING, "录音中…（录音笔）", elapsedSec(), -1);
            return;
        }
        if (!isConnected()) {
            if (listener != null) listener.onPenNeedConnect();
            return;
        }
        // ★闸门一(连接级)：近期有真回包(isPenAlive) → 直接开始；否则先验活一次，收到回包再真开录，
        //   验不到就报"没开机/没响应"并断开——绝不对一支假连接的笔空录。
        if (isPenAlive()) {
            doStartRecord();
        } else {
            preStartVerifyThenRecord();
        }
    }

    /** 真正发开始录音命令 + 起确认超时（闸门二：发 startRecord 后等笔回"已开录"帧）。 */
    private void doStartRecord() {
        appStartPending = true;
        sessionStartWallMs = System.currentTimeMillis();
        activate();
        try {
            AIRECBleManager.getInstance().startRecord();
            post(PhoneMicService.STATE_STARTING, "正在唤醒录音笔…", 0, -1);
            main.removeCallbacks(confirmTimeout);
            main.postDelayed(confirmTimeout, CONFIRM_TIMEOUT_MS);
        } catch (Exception e) {
            Log.e(TAG, "startRecord failed", e);
            appStartPending = false;
            post(PhoneMicService.STATE_ERROR, "录音笔启动失败：" + e.getMessage(), 0, -1);
        }
    }

    /** 录音前活性确认：链路在但近期没回包 → 发命令限时等回包，回来了(isPenAlive)才真开录，否则报错并断开。 */
    private void preStartVerifyThenRecord() {
        post(PhoneMicService.STATE_STARTING, "正在确认录音笔…", 0, -1);
        activate();
        try { AIRECBleManager.getInstance().fetchDeviceInfo(); } catch (Exception ignored) {}
        main.removeCallbacks(preStartTimeout);
        main.postDelayed(preStartTimeout, HANDSHAKE_TIMEOUT_MS);
    }
    private final Runnable preStartTimeout = new Runnable() {
        @Override public void run() {
            if (isPenAlive()) {
                doStartRecord();
            } else {
                Log.w(TAG, "录音前确认：笔无回包 → 判假连接/休眠，不开录");
                post(PhoneMicService.STATE_ERROR, "录音笔没开机/没响应，请确认它已开机并靠近后重试", 0, -1);
                try { AIRECBleManager.getInstance().disconnect(); } catch (Exception ignored) {}
                if (listener != null) main.post(() -> listener.onPenConnected(false));
            }
        }
    };

    /** 发开始命令后笔超时没确认开录 → 判定休眠/关机：报错、断开使连接指示如实。 */
    private final Runnable confirmTimeout = new Runnable() {
        @Override public void run() {
            if (appStartPending && !penRecording) {
                Log.w(TAG, "笔未在 " + CONFIRM_TIMEOUT_MS + "ms 内确认开录 → 判定休眠/关机");
                appStartPending = false;
                sessionStartWallMs = 0;
                post(PhoneMicService.STATE_ERROR, "启动失败：录音笔没有响应，请确认它已开机/唤醒后重试", 0, -1);
                try { AIRECBleManager.getInstance().disconnect(); } catch (Exception ignored) {}
            }
        }
    };

    /** App 点「结束陪伴」：发停止命令。笔停 → onRecordStateChanged(false) → 入队后台上传。 */
    public void stopRecording() {
        main.removeCallbacks(confirmTimeout);
        main.removeCallbacks(preStartTimeout);   // 启动确认期间点"取消"也能撤销
        if (appStartPending && !penRecording) {
            // 还没等到笔确认开录就点了结束 → 没真录，直接回 idle
            appStartPending = false;
            sessionStartWallMs = 0;
            post(PhoneMicService.STATE_IDLE, "已结束", 0, -1);
            return;
        }
        if (!penRecording && !sessionActive) {
            post(PhoneMicService.STATE_IDLE, "已结束", 0, -1);
            return;
        }
        penPaused = false;
        final String fnAtStop = sessionFileName;
        final int durAtStop = elapsedSec();
        final long startWallAtStop = sessionStartWallMs;
        final int genAtStop = sessionGen;   // ★记下"这一段"的代号
        try {
            AIRECBleManager.getInstance().endRecord();
        } catch (Exception e) {
            Log.e(TAG, "endRecord failed", e);
            post(PhoneMicService.STATE_ERROR, "录音笔停止失败：" + e.getMessage(), elapsedSec(), -1);
            return;
        }
        // 兜底：4s 内笔没回停止帧，手动收尾入队，避免卡在录音中。
        // ★只在"还是同一段"(代号没变、还在录)时才生效——否则会误伤后面新开的那一段（连录 bug）。
        main.postDelayed(() -> {
            if (sessionActive && sessionGen == genAtStop) {
                Log.w(TAG, "endRecord 后笔未回停止帧，兜底收尾入队 gen=" + genAtStop);
                finishSessionEnqueue(fnAtStop, durAtStop, startWallAtStop);
            }
        }, 4000);
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

    // ============ 会话收尾：入队后台上传，立刻回 idle ============

    /** 结束当前这段录音：复位会话显示状态、把这段排进后台上传队列、立刻回到可再开始。 */
    private void finishSessionEnqueue(String fileName, int durSec, long startWallMs) {
        final boolean appInit = sessionAppInitiated;   // 捕获本段是否你主动发起
        // ★先收口实时流：停止捕获、在复位前快照缓冲
        stopStreamCapture();
        final java.io.ByteArrayOutputStream streamBuf = sessionStreamBuf;
        final boolean streamComplete = sessionStreamComplete;
        final long streamBytes = sessionStreamBytes;
        sessionStreamBuf = null; sessionStreamComplete = false;
        sessionStreamBytes = 0; sessionStreamFrames = 0;

        sessionGen++;    // ★本段结束：作废任何挂起的结束兜底
        penRecording = false;
        sessionActive = false;
        appStartPending = false;
        sessionAppInitiated = false;
        startElapsedMs = 0;
        sessionFileName = null;
        sessionStartWallMs = 0;

        final boolean haveCtx = (cookie != null && uploadUrl != null);
        final String fn = !TextUtils.isEmpty(fileName) ? fileName : null;

        // ★优先：实时流完整 → 把拼好的 .ops 落地、直传(opus 端到端，不下载不解码)
        String localOps = null;
        if (haveCtx && streamComplete && streamBuf != null && streamBytes > 320) {
            localOps = writeStreamOps(streamBuf, fn, startWallMs);
        }

        if (localOps != null) {
            final UploadTask task = new UploadTask(fn != null ? fn : ("stream_" + startWallMs),
                    cookie, uploadUrl, penSn(), durSec, startWallMs, appInit);
            task.localOpsPath = localOps;
            uploadQueue.add(task);
            Log.d(TAG, "入队【直传实时流opus】 file=" + task.fileName + " bytes=" + streamBytes + " 队列=" + uploadQueue.size());
            enqueuePlaceholderAndKick(task, startWallMs, 300);   // 直传不占蓝牙，可更快启动
        } else if (fn != null && haveCtx) {
            // 回落：流不完整(蓝牙断过)/没捕获到 → 走原"从笔存储下载补全"路径
            final UploadTask task = new UploadTask(fn, cookie, uploadUrl, penSn(), durSec, startWallMs, appInit);
            uploadQueue.add(task);
            Log.d(TAG, "入队后台下载补全 file=" + fn + "(流不完整) 队列=" + uploadQueue.size());
            enqueuePlaceholderAndKick(task, startWallMs, 1200);
        } else {
            // 既无完整流也无文件名 → 这段传不了
            if (appInit) {
                failedCount++;
                Log.w(TAG, "缺文件名/上传上下文，app-initiated 计入失败 fn=" + fn);
                notifyPending();
            } else {
                Log.d(TAG, "缺文件名/上传上下文(笔自发空录)，静默丢 fn=" + fn);
            }
            post(PhoneMicService.STATE_IDLE, "已结束", 0, -1);
        }
    }

    /** 入队后的公共收尾：建占位 + 立刻回 idle + 延迟启动 worker。 */
    private void enqueuePlaceholderAndKick(final UploadTask task, final long recStart, long kickDelayMs) {
        notifyPending();
        post(PhoneMicService.STATE_IDLE, "已结束", 0, -1);   // 立刻回 idle，不阻塞；后台慢慢传
        final String phUrl = placeholderUrlFrom(task.uploadUrl);
        worker.submit(() -> {
            long pid = Uploader.createPlaceholder(task.cookie, phUrl, recStart);
            if (pid > 0) {
                task.placeholderId = pid;
                Log.d(TAG, "已建占位片段 id=" + pid);
                if (listener != null) main.post(listener::onPenPlaceholderCreated);
            }
        });
        main.postDelayed(this::kickWorker, kickDelayMs);
    }

    // ============ 实时流捕获(边录边流，结束整包直传 opus) ============

    private void startStreamCapture() {
        sessionStreamBuf = new java.io.ByteArrayOutputStream();
        sessionStreamComplete = true;   // 先假定完整，蓝牙断过则置 false
        sessionStreamBytes = 0; sessionStreamFrames = 0;
        try {
            AIRECBleManager.getInstance().setAudioStreamListener(this::onStreamFrame);
        } catch (Exception e) {
            Log.e(TAG, "setAudioStreamListener 失败，本段回落下载", e);
            sessionStreamComplete = false;
        }
    }

    /** 实时流回调：把 80 字节 KA 块原样拼接(不解码)。 */
    private void onStreamFrame(byte[] data) {
        if (data == null || data.length == 0) return;
        java.io.ByteArrayOutputStream buf = sessionStreamBuf;
        if (buf == null) return;
        try { synchronized (buf) { buf.write(data); } } catch (Exception ignore) {}
        sessionStreamBytes += data.length;
        sessionStreamFrames++;
    }

    private void stopStreamCapture() {
        try { AIRECBleManager.getInstance().setAudioStreamListener(null); } catch (Exception ignore) {}
    }

    /** 调试期：把一行状态写到外部目录 stream_ops/last_result.txt（vivo 限制 logcat，靠它看上传结果）。 */
    private void writeProbeStatus(String line) {
        try {
            File root = appCtx.getExternalFilesDir(null);
            if (root == null) return;
            File dir = new File(root, "stream_ops");
            if (!dir.exists()) dir.mkdirs();
            try (java.io.FileWriter w = new java.io.FileWriter(new File(dir, "last_result.txt"), true)) {
                w.write(android.os.SystemClock.elapsedRealtime() / 1000 % 100000 + "s  " + line + "\n");
            }
        } catch (Exception ignore) {}
    }

    /** 把拼好的实时流缓冲写成本地 .ops 文件，返回路径(失败返回 null)。 */
    private String writeStreamOps(java.io.ByteArrayOutputStream buf, String fileName, long startWallMs) {
        try {
            byte[] bytes; synchronized (buf) { bytes = buf.toByteArray(); }
            if (bytes.length < 320) return null;
            String base = !TextUtils.isEmpty(fileName) ? stripExt(fileName) : ("stream_" + startWallMs);
            // 调试期：写外部目录(可直接 adb pull、且不随成功删除影响排查)。正式版改回 getCacheDir()。
            File root = appCtx.getExternalFilesDir(null);
            if (root == null) root = appCtx.getCacheDir();
            File dir = new File(root, "stream_ops");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, base + ".ops");
            try (java.io.FileOutputStream fo = new java.io.FileOutputStream(f)) { fo.write(bytes); }
            Log.d(TAG, "实时流已落地 " + f.getAbsolutePath() + " " + bytes.length + "B 帧约" + (bytes.length / 80));
            return f.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "writeStreamOps 失败", e);
            return null;
        }
    }

    /** 直传本地拼好的 .ops(不占蓝牙、不解码、opus 端到端)。 */
    private void uploadLocalOps(final UploadTask task) {
        worker.submit(() -> {
            long recId = -1;
            try {
                File f = new File(task.localOpsPath);
                if (!f.exists() || f.length() < 320) { workerTaskFailed(task, "流文件无效", true); return; }
                String name = baseName(task.localOpsPath);   // xxx.ops
                Uploader.Result r = Uploader.uploadOps(f, task.durSec, task.cookie, task.uploadUrl,
                        name, task.sn, task.placeholderId, task.startWallMs);
                // 调试期：把上传结果写状态文件(logcat被vivo限制，靠这个看)，且【不删.ops】留作核对
                writeProbeStatus("file=" + name + " bytes=" + f.length()
                        + " ok=" + r.ok + " recId=" + r.recordingId + " err=" + r.error);
                if (r.ok) {
                    recId = r.recordingId;
                    // try { f.delete(); } catch (Exception ignore) {}  // 调试期不删
                } else {
                    Log.w(TAG, "实时流opus上传失败：" + r.error);
                }
            } catch (Exception e) {
                Log.e(TAG, "uploadLocalOps 失败", e);
            } finally {
                if (recId > 0) workerTaskDone(task, recId);
                else workerTaskFailed(task, "stream upload failed", true);
            }
        });
    }

    // ============ SDK 回调 ============

    private final AIRECBleCallback callback = new AIRECBleCallback() {
        @Override
        public void onRecordStateChanged(boolean recording, String fileName) {
            markPenResponded();
            if (recording) {
                // ★镜像：笔开始录音（App 发起 或 笔上操作/声控自动）→ 都显示「陪伴进行中」。
                boolean wasAppStart = appStartPending;   // 捕获：这段是不是你在App点开始触发的
                penRecording = true;
                appStartPending = false;
                main.removeCallbacks(confirmTimeout);
                if (!sessionActive) {
                    sessionActive = true;
                    sessionAppInitiated = wasAppStart;   // 你点的→失败大声提示；笔自发→空录静默丢
                    sessionGen++;    // ★新一段：作废上一段挂起的结束兜底，别误伤这段
                    failedCount = 0; // 新一批录音，失败计数清零
                    sessionFileName = !TextUtils.isEmpty(fileName) ? fileName : null;
                    if (sessionStartWallMs == 0) sessionStartWallMs = System.currentTimeMillis();
                    startElapsedMs = SystemClock.elapsedRealtime();
                    pauseWorker();   // 笔要录了，暂停后台下载（0xFD 冲突），录完再续
                    startStreamCapture();   // ★开始捕获蓝牙实时流(拼 .ops)
                    Log.d(TAG, "镜像：笔开始录音 file=" + sessionFileName + " gen=" + sessionGen);
                    post(PhoneMicService.STATE_RECORDING, "录音中…（录音笔）", 0, -1);
                } else if (!TextUtils.isEmpty(fileName)) {
                    sessionFileName = fileName;
                }
            } else {
                // ★镜像：笔停止 → 收尾入队后台上传。
                penRecording = false;
                if (!sessionActive) {
                    kickWorker();   // 没在跟踪：可能是停后的重复帧，顺便推进后台队列
                    return;
                }
                finishSessionEnqueue(
                        !TextUtils.isEmpty(fileName) ? fileName : sessionFileName,
                        elapsedSec(), sessionStartWallMs);
            }
        }

        @Override
        public void onFileListUpdated(List<AIRECBleFile> files) {
            markPenResponded();
            final UploadTask task = currentTask;
            if (!waitingForFile || task == null || files == null || files.isEmpty()) return;
            AIRECBleFile target = pickForTask(files, task);
            if (target == null) {
                if (task.firstAttemptMs == 0) task.firstAttemptMs = System.currentTimeMillis();
                if (++fileListAttempts < MAX_FILELIST_ATTEMPTS) {
                    // 诊断：把笔上报的文件列表实际名字打出来，便于核对"开始帧名 vs 存盘名"
                    StringBuilder sb = new StringBuilder();
                    for (AIRECBleFile f : files) if (f != null) sb.append(f.getFileName()).append(' ');
                    Log.w(TAG, "后台：未定位文件(第" + fileListAttempts + "次) 找=" + task.fileName
                            + " startWall=" + task.startWallMs + " 笔列表[" + files.size() + "]=" + sb);
                    main.postDelayed(() -> {
                        if (waitingForFile) {
                            try { AIRECBleManager.getInstance().fetchFileList(); } catch (Exception ignored) {}
                        }
                    }, 2000);
                } else if (System.currentTimeMillis() - task.firstAttemptMs < DEFER_MAX_MS) {
                    // 文件暂未在笔列表里(连录时笔忙、刚停还没落盘)。挪到队尾、先处理别的，稍后再试。
                    long waited = (System.currentTimeMillis() - task.firstAttemptMs) / 1000;
                    Log.w(TAG, "暂未在笔列表找到 " + task.fileName + " dur=" + task.durSec + "s，挪队尾稍后重试(已等" + waited + "s)");
                    uploadQueue.remove(task);
                    uploadQueue.add(task);     // 挪到队尾，先处理别的；本任务稍后再轮到
                    workerBusy = false; currentTask = null; waitingForFile = false;
                    notifyPending();
                    main.postDelayed(PenController.this::kickWorker, 8000);
                } else {
                    workerTaskFailed(task, "not found", true);
                }
                return;
            }
            waitingForFile = false;
            Log.d(TAG, "后台：定位到文件 " + target.getFileName() + "（任务名=" + task.fileName + "）");
            try { AIRECBleManager.getInstance().downloadFile(target); }
            catch (Exception e) { workerTaskFailed(task, "download err: " + e.getMessage(), true); }
        }

        @Override
        public void onFileDownloadProgress(AIRECBleFile file, int progress) {
            markPenResponded();
            // 后台进度：推给 UI 显示"保存中 N%"(大文件几分钟)。不抢占当前录音 UI。
            if (listener != null) main.post(() -> listener.onPenProgress(progress));
            Log.d(TAG, "后台下载 " + progress + "%");
        }

        @Override
        public void onFileDownloadComplete(AIRECBleFile file, String localPath) {
            markPenResponded();
            downloadRetries = 0;
            final UploadTask task = currentTask;
            if (task == null) return;
            inflightTask = task;   // 进入解码+上传阶段：去重 + 不再被 pauseWorker 打断
            processAndUpload(task, file, localPath);
        }

        @Override
        public void onFileDownloadFailed(AIRECBleFile file, String reason) {
            final UploadTask task = currentTask;
            if (task == null) return;
            if (penRecording) {
                // 笔在录(0xFD 无法下载)：留任务，等录完 kickWorker 再续。
                workerTaskFailed(task, "pen recording: " + reason, false);
                return;
            }
            if (downloadRetries < MAX_DOWNLOAD_RETRIES) {
                downloadRetries++;
                Log.w(TAG, "后台下载失败(" + reason + ")，第 " + downloadRetries + " 次重试");
                waitingForFile = true;
                main.postDelayed(() -> {
                    if (waitingForFile) {
                        try { AIRECBleManager.getInstance().fetchFileList(); } catch (Exception ignored) {}
                    }
                }, 2500);
            } else {
                workerTaskFailed(task, reason, true);
            }
        }

        @Override
        public void onDeviceFound(AIRECBleDevice device) {
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
            if (device != null && device.getAddress() != null) lastConnectedMac = device.getAddress();
            penSettingsWritten = false;
            // ★SDK 的 onConnected 只当"链路就绪"，不当"真连上"(它有 3 秒无响应也假触发的兜底)。
            //   清空 verifiedConnected，发命令等真回包；收到才 markPenResponded→点亮已连接。
            verifiedConnected = false;
            lastRxMs = 0;
            try { AIRECBleManager.getInstance().fetchDeviceInfo(); }   // 握手：会回 0x0E/0x0F
            catch (Exception e) { Log.e(TAG, "handshake fetchDeviceInfo failed", e); }
            main.removeCallbacks(handshakeTimeout);
            main.postDelayed(handshakeTimeout, HANDSHAKE_TIMEOUT_MS);
            main.postDelayed(() -> {
                try { AIRECBleManager.getInstance().fetchAllDeviceInfo(); }
                catch (Exception e) { Log.e(TAG, "fetchAllDeviceInfo failed", e); }
            }, 600);
            main.postDelayed(() -> ensurePenConfigured(), 3500);
            // 连上后若有积压的后台上传，趁笔空闲推进。
            main.postDelayed(this::tryResumeWorker, 4000);
        }

        private void tryResumeWorker() { kickWorker(); }

        @Override
        public void onInitParamUpdated() { markPenResponded(); ensurePenConfigured(); }

        @Override
        public void onDisconnected(AIRECBleDevice device, String reason) {
            // 复位真连接/心跳状态
            verifiedConnected = false; lastRxMs = 0;
            stopHeartbeat();
            main.removeCallbacks(handshakeTimeout);
            if (listener != null) main.post(() -> listener.onPenConnected(false));
            // 断开时若后台正在下载，标记失败留队，重连后再续。
            if (workerBusy && currentTask != null) {
                workerTaskFailed(currentTask, "disconnected", false);
            }
            // ★断开必复位会话：否则 sessionActive/penRecording/appStartPending 残留→重连点开始被守卫静默吞→永久卡"正在唤醒"。
            main.removeCallbacks(confirmTimeout);   // 防断开后确认超时误判
            if (sessionActive || penRecording || appStartPending) {
                sessionStreamComplete = false;   // ★录音中断开→实时流有缺口，本段回落到"下载补全"路径
                Log.w(TAG, "断开时复位残留会话 sessionActive=" + sessionActive + " penRecording=" + penRecording + " appStartPending=" + appStartPending);
                // ★录音中途断开(如笔被按键关机)：这段其实已录在笔存储里。存它的信息，等重连+笔空闲自动补下载上传，不丢这段。
                if (sessionActive && !TextUtils.isEmpty(sessionFileName) && cookie != null && uploadUrl != null) {
                    pendingRecovery = new UploadTask(sessionFileName, cookie, uploadUrl, penSn(),
                            elapsedSec(), sessionStartWallMs, sessionAppInitiated);
                    Log.w(TAG, "录音中断开，保存待补传 file=" + sessionFileName);
                }
                boolean willRecover = (pendingRecovery != null);
                sessionGen++;   // 作废挂起的结束兜底
                penRecording = false; sessionActive = false;
                appStartPending = false; sessionAppInitiated = false;
                startElapsedMs = 0; sessionFileName = null; sessionStartWallMs = 0;
                post(PhoneMicService.STATE_ERROR,
                        willRecover ? "录音笔断开了，这段会在重新连上后自动补传" : "录音笔已断开，请重连后重试",
                        0, -1);
            }
        }

        @Override
        public void onRecordStatusQueried(boolean recording, boolean paused, String fileName) {
            markPenResponded();   // 0x0F 回包 = 笔活着（握手最常用这条点亮真连接）
            penPaused = recording && paused;
            // 连上时查到笔已经在录 → 镜像里也显示出来（笔上更早就开始录的场景）。
            if (recording && !sessionActive) {
                penRecording = true;
                sessionActive = true;
                sessionGen++;    // ★新一段
                sessionAppInitiated = false;  // 连上时笔已在录=笔自发，空录静默丢
                sessionFileName = !TextUtils.isEmpty(fileName) ? fileName : null;
                if (sessionStartWallMs == 0) sessionStartWallMs = 0; // 不设墙上时间→不做旧文件拒收（更早开始的）
                if (startElapsedMs == 0) startElapsedMs = SystemClock.elapsedRealtime();
                pauseWorker();
                post(PhoneMicService.STATE_RECORDING, "录音中…（录音笔）", 0, -1);
            }
            if (listener != null) main.post(() -> {
                listener.onPenRecordStatus(recording);
                if (recording) listener.onPenPaused(paused);
            });
        }

        @Override
        public void onRecordPaused() {
            if (appInitiatedPauseResume) { appInitiatedPauseResume = false; return; }
            penPaused = !penPaused;
            final boolean p = penPaused;
            if (listener != null) main.post(() -> listener.onPenPaused(p));
        }

        @Override
        public void onRecordDurationUpdated(long durationSec) {
            markPenResponded();
            // 只在镜像会话内同步时长。注意：墙上钟已在网页自走，这里只在差距较大时前跳（不回退）。
            if (!sessionActive) return;
            if (listener != null) main.post(() -> listener.onPenRecordDuration((int) durationSec));
        }
    };

    // ============ 后台上传 worker ============

    /** 尝试推进后台队列：仅在笔没在录、且当前无任务在跑时，取队首处理。 */
    private void kickWorker() {
        main.post(() -> {
            if (workerBusy) return;
            UploadTask task = uploadQueue.peek();
            if (task == null) { notifyPending(); return; }
            if (task == inflightTask) { notifyPending(); return; }   // 该段已在上传阶段，别重复处理
            boolean isLocal = (task.localOpsPath != null);
            // 下载任务需等笔空闲(0xFD冲突)；本地直传不占蓝牙，录音中也能传
            if (!isLocal && (penRecording || sessionActive || appStartPending)) return;
            workerBusy = true; currentTask = task;
            notifyPending();
            if (isLocal) {
                inflightTask = task;   // 直传阶段：去重 + pauseWorker 不打断
                Log.d(TAG, "后台开始【直传实时流opus】 file=" + task.fileName + " 剩余=" + uploadQueue.size());
                uploadLocalOps(task);
                return;
            }
            waitingForFile = true; pendingFileName = task.fileName;
            fileListAttempts = 0; downloadRetries = 0;
            Log.d(TAG, "后台开始处理 file=" + task.fileName + " 剩余=" + uploadQueue.size());
            try { AIRECBleManager.getInstance().fetchFileList(); } catch (Exception ignored) {}
        });
    }

    /** 笔要开始录音时暂停后台下载流程（任务留在队列头，录完再续）。 */
    private void pauseWorker() {
        if (inflightTask != null) return;   // 已进入解码+上传阶段(不占蓝牙)，别打断，避免该段被丢回队列重复处理
        if (workerBusy || waitingForFile) {
            Log.d(TAG, "暂停后台上传（笔要录音）");
            workerBusy = false; waitingForFile = false; currentTask = null;
            notifyPending();
        }
    }

    private void workerTaskDone(final UploadTask task, final long recId) {
        main.post(() -> {
            uploadQueue.remove(task);
            workerBusy = false; currentTask = null; inflightTask = null; waitingForFile = false;
            Log.d(TAG, "后台完成 file=" + task.fileName + " recId=" + recId + " 剩余=" + uploadQueue.size());
            if (recId > 0 && listener != null) listener.onPenUploaded(recId);
            notifyPending();
            kickWorker();
        });
    }

    private void workerTaskFailed(final UploadTask task, final String reason, final boolean drop) {
        main.post(() -> {
            if (drop) {
                uploadQueue.remove(task);
                // ★占位清理：这段没传成功就别让"处理中"占位永远留在未归档——调后端删掉它(失败/404则忽略)。
                if (task.placeholderId > 0) {
                    final long pid = task.placeholderId;
                    final String phCancelUrl = placeholderUrlFrom(task.uploadUrl) + "/cancel";
                    final String ck = task.cookie;
                    worker.submit(() -> {
                        Uploader.cancelPlaceholder(ck, phCancelUrl, pid);
                        if (listener != null) main.post(listener::onPenPlaceholderCreated);  // 刷新未归档(占位已移除)
                    });
                }
                // ★只有"你主动点开始"的那段失败了才计入、提示你；笔自发的空录静默丢，不打扰。
                if (task.appInitiated) {
                    failedCount++;
                    Log.w(TAG, "后台放弃(你发起的) file=" + task.fileName + " 原因=" + reason + " 失败累计=" + failedCount + " 剩余=" + uploadQueue.size());
                } else {
                    Log.d(TAG, "后台静默丢弃(笔自发空录) file=" + task.fileName + " 原因=" + reason + " 剩余=" + uploadQueue.size());
                }
            } else {
                Log.w(TAG, "后台暂挂 file=" + task.fileName + " 原因=" + reason + "（留队稍后续）");
            }
            workerBusy = false; currentTask = null; inflightTask = null; waitingForFile = false;
            notifyPending();
            main.postDelayed(PenController.this::kickWorker, 2500);
        });
    }

    private void notifyPending() {
        final int n = uploadQueue.size();
        final int f = failedCount;
        if (listener != null) main.post(() -> listener.onPenPendingChanged(n, f));
    }

    // ============ 下载完成后：解码 → 上传（后台，不抢占当前录音 UI） ============

    private void processAndUpload(final UploadTask task, final AIRECBleFile file, final String localPath) {
        final int durSec = task.durSec > 0 ? task.durSec
                : ((file != null && file.getDurationSec() > 0) ? (int) file.getDurationSec() : 0);
        worker.submit(() -> {
            long recId = -1;
            try {
                if (TextUtils.isEmpty(localPath) || !new File(localPath).exists()) {
                    Log.w(TAG, "后台：下载文件不存在");
                    workerTaskFailed(task, "下载文件不存在", true);
                    return;
                }
                String uploadPath, name, mime;
                // 私有分帧(5B50/4B41 + SILK-WB opus) → libopus 解码成 16kHz 单声道 wav 再传。
                String wav = OpusBridge.decodeToWav(localPath, localPath + ".wav");
                if (wav != null && new File(wav).exists() && new File(wav).length() > 44) {
                    uploadPath = wav;
                    name = stripExt(baseName(localPath)) + ".wav";
                    mime = "audio/wav";
                } else {
                    String ext = extOf(localPath);
                    if (isAccepted(ext)) {
                        uploadPath = localPath; name = baseName(localPath); mime = mimeFor(ext);
                    } else {
                        Log.w(TAG, "后台：未知格式，放弃 " + localPath);
                        workerTaskFailed(task, "未知格式", true);
                        return;
                    }
                }
                Uploader.Result r = Uploader.upload(new File(uploadPath), durSec,
                        task.cookie, task.uploadUrl, name, mime, task.sn, task.placeholderId);
                writeProbeStatus("[补下载兜底] file=" + name + " ok=" + r.ok + " recId=" + r.recordingId + " err=" + r.error);
                if (r.ok) {
                    recId = r.recordingId;
                } else {
                    Log.w(TAG, "后台上传失败：" + r.error);
                }
            } catch (Exception e) {
                Log.e(TAG, "processAndUpload failed", e);
            } finally {
                if (recId > 0) workerTaskDone(task, recId);
                else workerTaskFailed(task, "upload failed", true);  // 上传失败就丢弃，不无限重试
            }
        });
    }

    /** 从上传地址推导占位接口地址：.../api/consultant/upload → .../api/consultant/placeholder。 */
    private static String placeholderUrlFrom(String uploadUrl) {
        if (uploadUrl == null) return null;
        if (uploadUrl.endsWith("/upload")) return uploadUrl.substring(0, uploadUrl.length() - 7) + "/placeholder";
        return uploadUrl.replace("/upload", "/placeholder");
    }

    /** 当前连接笔的 SN（SDK 的 getMacAddress 实为 SN）。读不到返回空串。 */
    private String penSn() {
        try {
            String sn = AIRECBleManager.getInstance().getMacAddress();
            return sn == null ? "" : sn;
        } catch (Exception e) { return ""; }
    }

    // ============ 工具 ============

    /**
     * 给一个上传任务定位它对应的笔文件：
     *  1) 精确文件名匹配且不早于会话开始 → 用它（最可靠）。
     *  2) 文件名对不上（笔存盘名与"开始帧名"有出入）→ 取"时间戳最接近本任务开始、且不早于它(留容差)"的那条。
     *     既能拿到这次真录的文件、排除笔休眠时的旧文件，多段连录也能各自按开始时间对上(不会都取最新那条)。
     *  startWallMs<=0(采纳已在录的会话)时只能退化为"取最新"。
     */
    private AIRECBleFile pickForTask(List<AIRECBleFile> files, UploadTask task) {
        final long start = task.startWallMs;
        final long marginBefore = 20000L;   // 文件名时间戳可能比会话开始早几秒(笔先开录、帧后到)
        // 1) 精确文件名匹配
        if (!TextUtils.isEmpty(task.fileName)) {
            for (AIRECBleFile f : files) {
                if (f != null && task.fileName.equals(f.getFileName())) {
                    long ts = parseFileTimestamp(f.getFileName());
                    if (start <= 0 || ts <= 0 || ts >= start - marginBefore) return f;
                }
            }
        }
        if (start <= 0) {
            // 没有会话开始时间(采纳场景)：取文件名最新一条
            AIRECBleFile newest = null;
            for (AIRECBleFile f : files) {
                if (f == null || f.getFileName() == null) continue;
                if (newest == null || f.getFileName().compareTo(newest.getFileName()) > 0) newest = f;
            }
            return newest;
        }
        // 2) 时间就近匹配：时间戳不早于会话开始(留容差)、且最接近会话开始
        AIRECBleFile best = null; long bestDiff = Long.MAX_VALUE;
        for (AIRECBleFile f : files) {
            if (f == null || f.getFileName() == null) continue;
            long ts = parseFileTimestamp(f.getFileName());
            if (ts <= 0) continue;
            if (ts < start - marginBefore) continue;   // 排除旧文件(早于本次会话)
            long diff = Math.abs(ts - start);
            if (diff < bestDiff) { bestDiff = diff; best = f; }
        }
        return best;
    }

    /** 从文件名前 14 位数字解析 yyyyMMddHHmmss → epoch 毫秒（设备本地时区）。解析不出返回 0。 */
    private static long parseFileTimestamp(String name) {
        if (name == null) return 0;
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < name.length() && digits.length() < 14; i++) {
            char c = name.charAt(i);
            if (c >= '0' && c <= '9') digits.append(c);
        }
        if (digits.length() < 14) return 0;
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US);
            java.util.Date d = f.parse(digits.toString());
            return d != null ? d.getTime() : 0;
        } catch (Exception e) { return 0; }
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
