package com.airec.bledemo.soni;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import com.airec.bledemo.R;
import com.airec.bledemo.net.Uploader;
import com.airec.bledemo.recording.PenKeepAliveService;
import com.airec.bledemo.recording.PhoneMicService;
import com.airec.bledemo.recording.RecordingBus;
import com.wind.pnote.ui.PNote;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 声云录音卡片（PNote SDK）陪伴笔控制器 —— v2 专用引擎。
 *
 * 行为上是 {@link com.airec.bledemo.PenController}（杰理版）的全量移植：镜像状态机、真连接判定+心跳、
 * 实时流捕获+卡流看门狗+截断闸门、开录健康看门狗、断线宽限、自动重连、补传队列（v24：持久化+退避+
 * 跳过冷却队头挑 ready+僵尸清理+取消）、SN 归属校验(fail-open)、保活前台服务、penlog 诊断——一个不少。
 *
 * 与杰理版的协议差异（这层全部吸收，上层无感知）：
 *  - SDK 是「指令下发 + JSON 事件回调」模型：所有命令异步，结果在 onDeviceDataEvent 按 cmd 分发。
 *  - 音频是【标准 opus 裸帧】16kHz 单声道 40B/帧(20ms)，实时流 160B/包=4帧（速率 2000B/s）。
 *    上传前用 {@link OggOpusWriter} 打包成标准 Ogg Opus 直传 .ogg —— 后端零改动、不再碰 ATW 转换器。
 *  - 文件下载按文件名 + 字节 offset 断点续传（startGetFile），不需要先"定位文件对象"。
 *  - 笔上实体按键走 cmd=8 请求/应答：App 必须回 *BtnBackRecord，笔才真正执行。
 *  - 连上必发 syncTime 同步手机时间 → 根治"笔时钟回 2019 补传取错文件"那坑。
 *
 * 红线（继承）：下载/补传失败【绝不】提示用户重录——录音在笔机身存储里没丢，只是暂时取不回。
 */
public class SoniPenController implements com.wind.pnote.ui.DeviceDataListener {

    private static final String TAG = "SoniPenController";

    public interface Listener {
        /** 录音笔未连接，需要 UI 去打开扫描/连接页。 */
        void onPenNeedConnect();
        /** 连上后查到的录音笔当前录音状态。 */
        void onPenRecordStatus(boolean recording);
        /** 录音笔上报的当前录音时长（秒）。 */
        void onPenRecordDuration(int durationSec);
        /** 录音笔连接状态变化（真在线判定后的连上/断开）。 */
        void onPenConnected(boolean connected);
        /** 录音暂停状态变化。 */
        void onPenPaused(boolean paused);
        /** 后台待传/在传段数 + 累计保存失败段数变化。 */
        void onPenPendingChanged(int pending, int failed);
        /** 一段后台上传成功（recordingId>0）。 */
        void onPenUploaded(long recordingId);
        /** 已建占位片段(后端记录)。 */
        void onPenPlaceholderCreated();
        /** 当前后台下载进度(0-100)。 */
        void onPenProgress(int percent);
        /** 手动"从陪伴笔同步"：机身文件列表(JSON 数组)。 */
        void onPenFileList(String filesJson);
        /** 陪伴笔电量(cmd=6)：percent=0–100；charging=是否充电中(充电时 percent 仅供参考)。 */
        default void onPenBattery(int percent, boolean charging) {}
        /** 录满 90 分钟自动结束这一段 → UI 提示"已自动保存，要继续请点开始陪伴"。 */
        default void onPenAutoStopped() {}
    }

    /** 扫描页注册：cmd=1 设备发现转发。 */
    public interface ScanListener {
        void onDeviceFound(String name, String address, String productType);
    }

    // ============ 单例引用（扫描页经它走同一个控制器/同一个 PNote 监听） ============
    private static volatile SoniPenController sInstance;
    public static SoniPenController instance() { return sInstance; }

    private final Context appCtx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private volatile ScanListener scanListener;
    public void setScanListener(ScanListener l) { this.scanListener = l; }

    // 上传上下文
    private volatile String cookie;
    private volatile String uploadUrl;

    // 连接/自动连接
    private volatile boolean linkUp = false;            // cmd=2 报的链路状态（类比 GATT 就绪）
    private volatile String autoConnectMac = null;
    private volatile String lastConnectedMac = null;
    private volatile String lastConnectedName = null;
    private volatile boolean autoReconnectOn = false;
    private volatile int reconnectAttempts = 0;
    private android.content.BroadcastReceiver btStateReceiver = null;
    private volatile boolean scanningForAuto = false;   // 当前 startSearch 是否是自动重连发起的（连上/超时要 stopSearch）

    // ★真连接判定 + 在线活性（同杰理版：只认"最近收到过笔的真实业务回包"）
    private volatile boolean verifiedConnected = false;
    private volatile long    lastRxMs = 0;
    private volatile int     hbMissed = 0;
    private int              hbBatteryTick = 0;   // 心跳计数：每 ~8 拍(≈64s)查一次电量
    private static final long HANDSHAKE_TIMEOUT_MS = 7000;  // 连上后 2s 才发首批命令（手册要求），整体放宽到 7s
    private static final long STALE_RX_MS = 12000;
    private static final long HB_INTERVAL_MS = 8000;
    private static final long HB_INTERVAL_BUSY_MS = 15000;

    // ★SN绑定校验（fail-open）
    private volatile boolean penAllowed = true;
    private volatile String  penDeniedMac = null;
    private volatile String  penDenyMsg = null;
    private volatile String  currentMac = null;
    private volatile int     snVerifyGen = 0;
    private volatile String  penSn = "";          // cmd=7 缓存
    private volatile String  batteryPct = "";     // cmd=6 缓存（"110"=充电中）

    // 暂停
    private volatile boolean penPaused = false;
    private volatile boolean appInitiatedPauseResume = false;

    // ★镜像状态
    private volatile boolean penRecording = false;
    private volatile boolean sessionActive = false;
    private volatile int     sessionGen = 0;
    private volatile boolean sessionAppInitiated = false;
    private volatile boolean appStartPending = false;
    private volatile String  sessionFileName = null;
    private volatile long    sessionStartWallMs = 0;
    private long startElapsedMs = 0;

    // ★实时流捕获：opus 裸帧(40B/帧) 拼接；速率 2000B/s
    private volatile java.io.ByteArrayOutputStream sessionStreamBuf = null;
    private volatile boolean sessionStreamComplete = false;
    private volatile long sessionStreamBytes = 0;
    private volatile int  sessionStreamFrames = 0;
    private volatile long lastStreamFrameMs = 0;
    private static final long STREAM_STALL_MS = 6000;
    private static final int  STREAM_BYTES_PER_SEC = 2000;   // 160B/80ms

    // ★保活：REC(录音级,FGS+唤醒锁) + CONN(连接级,仅FGS——笔连着就托住进程,治后台被杀断蓝牙, v23方案)
    private volatile boolean keepAliveOn = false;
    private volatile boolean connKeepAliveOn = false;
    private static final long CONN_KEEPALIVE_LINGER_MS = 90 * 1000L;   // 断开去抖：短暂抖动/重连期间不撤 FGS

    // ★上传遇 401(登录失效)时回调上层刷新 Cookie（RecordingControllerImpl 接到 RecordingModule.refreshUploadContext）
    public volatile Runnable onAuthExpired;

    // ★接管会话标记：连上时笔已在录(cmd=9采纳)——实时流只覆盖接管之后，收尾必须走"补下载全文件"，
    //   绝不能把尾段当完整段直传(会抢先占坑,让完整版被服务器去重丢弃)。
    private volatile boolean sessionAdopted = false;

    private static final long CONFIRM_TIMEOUT_MS = 5000;
    private static final long REC_HEALTH_GRACE_MS = 9000;
    private volatile int lastPenDurationSec = 0;
    // ★单段最长 90 分钟：到点自动结束保存（声云笔无暂停，连续录会越来越大）。
    //   计时以【笔上报的真实录音时长 lastPenDurationSec】为准（最权威、跨黑屏/重连都准），
    //   笔没报时退回 elapsedSec()（startElapsedMs 为 0 时它返回 0，不会像旧 V1 那样用墙钟算出天文数字误触发）。
    private static final int MAX_REC_SEC = 90 * 60;
    private volatile boolean autoStoppedAt90 = false;   // 本段是否已触发90分钟自动结束(防重复)
    private volatile boolean healthWatchdogArmed = false;
    private static final long RECONNECT_RECORDING_GRACE_MS = 25000;

    // ============ 后台上传队列（v24 语义） ============
    private static final class UploadTask {
        final String fileName, cookie, uploadUrl, sn;
        int durSec;
        final long startWallMs;
        final boolean appInitiated;
        volatile long placeholderId = -1;
        volatile String localRawPath = null;   // 非空=本地已有完整裸帧文件，打包直传（不占蓝牙）
        volatile int uploadAttempts = 0;
        volatile int downloadRequeues = 0;
        volatile long firstSeenMs = 0;
        volatile long nextAttemptWallMs = 0;   // ★v24:退避到期墙钟（持久化，重启后仍尊重冷却）
        UploadTask(String fileName, String cookie, String uploadUrl, String sn, int durSec, long startWallMs, boolean appInitiated) {
            this.fileName = fileName; this.cookie = cookie; this.uploadUrl = uploadUrl;
            this.sn = sn; this.durSec = durSec; this.startWallMs = startWallMs; this.appInitiated = appInitiated;
        }
    }
    private final ConcurrentLinkedQueue<UploadTask> uploadQueue = new ConcurrentLinkedQueue<>();
    private final java.util.Set<String> uploadedFileNames = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private volatile boolean workerBusy = false;
    private volatile UploadTask currentTask = null;
    private volatile UploadTask inflightTask = null;
    private volatile UploadTask pendingRecovery = null;
    private volatile int failedCount = 0;
    private volatile boolean waitingForFile = false;      // 在等文件列表（定位大小/确认存在）
    private volatile int fileListAttempts = 0;
    private static final int MAX_FILELIST_ATTEMPTS = 4;
    // 连着这么多轮都收到完整(非空)笔列表却找不到这个文件名 → 判定文件根本不在笔上(多半手机/笔时钟偏移命名对不上)，
    // 不再傻等 2 小时，直接放弃 + 取消占位(badge 清)。真音频在笔上(名字不同)可走「从陪伴笔同步」手动重导。
    private static final int ABSENT_FROM_LIST_GIVEUP = 3;
    private static final int MAX_DOWNLOAD_REQUEUES = 200;
    private static final long FAIL_GIVEUP_MS = 2 * 60 * 60 * 1000L;
    private static final long STALE_GIVEUP_MS = 2 * 60 * 60 * 1000L;   // ★v24:僵尸任务按入队龄直接清
    private static final long FILE_KEEP_MS = 30L * 24 * 3600 * 1000;
    private volatile boolean pendingCleanup = false;
    private volatile boolean pendingSyncList = false;
    private volatile boolean autoRetryScan = false;   // 点"重试"触发的重扫:回包时自动把未传段入队补传

    // ============ 下载（断点续传） ============
    private static final class DownloadState {
        final UploadTask task;
        final File partFile;
        final long baseOffset;          // 本次从这个字节开始要
        final long expectedSize;        // 文件列表报的总大小（0=未知）
        volatile long written = 0;      // 本次已收字节
        java.io.OutputStream out;
        DownloadState(UploadTask t, File f, long off, long total) { task = t; partFile = f; baseOffset = off; expectedSize = total; }
    }
    private volatile DownloadState dlState = null;
    private volatile long lastDlProgressMs = 0;
    private static final long DL_STALL_MS = 20000;

    // 文件列表分批攒收（cmd=4）
    private static final class PenFileEntry { String name; long size; int timeSec; }
    private final List<PenFileEntry> fileListBuf = new ArrayList<>();
    private volatile boolean fileListCollecting = false;

    // 持久化恢复
    private static final class PersistedDl {
        final String fileName; final int durSec; final long startWallMs;
        final long placeholderId; final boolean appInitiated; final long firstSeenMs;
        final int downloadRequeues; final long nextAttemptMs;
        PersistedDl(String fn, int d, long sw, long pid, boolean ai, long fs, int rq, long na) {
            fileName = fn; durSec = d; startWallMs = sw; placeholderId = pid; appInitiated = ai; firstSeenMs = fs;
            downloadRequeues = rq; nextAttemptMs = na;
        }
    }
    private final List<PersistedDl> pendingRestore = java.util.Collections.synchronizedList(new ArrayList<>());

    public SoniPenController(Context ctx, Listener l) {
        this.appCtx = ctx.getApplicationContext();
        this.listener = l;
        sInstance = this;
        try {
            PNote.init(appCtx, this);
            PNote.setShowLog(true);   // 联调期开着（SDK 协议流水进 logcat）；广测前关
            // 联调期：让 SDK 把扫描到的每个 BLE 设备直接打进 logcat（tag=PNoteLogger），便于排查"扫不到"
            try { com.wind.pnote.util.PNoteLogger.isShowLogText = true; } catch (Throwable ignore) {}
        } catch (Throwable t) {
            Log.e(TAG, "PNote.init failed", t);
        }
        loadUploadedNames();
        loadPendingQueue();
        registerBtReceiver();
        startMainFreezeWatchdog();
    }

    /**
     * ★主线程冻结自愈：SDK 把 BLE 命令写死在主线程跑（@Subscribe(threadMode=MAIN)），万一哪个调用
     * 还是把主线程卡死（btReady 守卫漏网/SDK 内部状态机卡死），整个 App 假死且无法自救（真机实锤过一次）。
     * 后台守护线程探活：主线程 >20s 不应答 → 自杀重启。STICKY 前台服务 + 持久化补传队列 +
     * App.onCreate 引擎自举 + 自动回连 = 重启后全自愈；录音在笔机身上，一秒不丢。
     */
    private void startMainFreezeWatchdog() {
        Thread t = new Thread(() -> {
            final java.util.concurrent.atomic.AtomicLong lastAck =
                    new java.util.concurrent.atomic.AtomicLong(SystemClock.elapsedRealtime());
            while (true) {
                main.post(() -> lastAck.set(SystemClock.elapsedRealtime()));
                try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
                long stall = SystemClock.elapsedRealtime() - lastAck.get();
                if (stall > 20000) {
                    penLog("★主线程冻结" + (stall / 1000) + "s(疑SDK主线程BLE阻塞)→自杀重启自愈");
                    writeProbeStatus("[看门狗] 主线程冻结" + (stall / 1000) + "s → 进程自杀重启自愈");
                    android.os.Process.killProcess(android.os.Process.myPid());
                }
            }
        }, "soni-main-watchdog");
        t.setDaemon(true);
        t.start();
    }

    // ============ 对外状态 ============

    public boolean isConnected() { return linkUp; }

    public boolean isRecording() { return sessionActive || penRecording; }

    public int getRecordingElapsedSec() {
        if (!isRecording()) return 0;
        return lastPenDurationSec > 0 ? lastPenDurationSec : elapsedSec();
    }

    /** 对外"真在线"判据：链路在 且 已验证 且 最近 STALE_RX_MS 内收到过回包。 */
    public boolean isPenAlive() {
        return verifiedConnected && linkUp && (SystemClock.elapsedRealtime() - lastRxMs) < STALE_RX_MS;
    }

    /** 杰理版遗留语义：让回调指向本控制器。PNote 监听在 init 时已固定为本实例，这里只确保单例指向。 */
    public void activate() { sInstance = this; }

    public void setUploadContext(String cookie, String uploadUrl) {
        boolean wasNull = (this.cookie == null || this.uploadUrl == null);
        if (cookie != null && !cookie.isEmpty()) this.cookie = cookie;
        if (uploadUrl != null && !uploadUrl.isEmpty()) this.uploadUrl = uploadUrl;
        if (this.cookie != null && this.uploadUrl != null) materializeRestored();
        if (wasNull && this.cookie != null && this.uploadUrl != null) main.postDelayed(this::kickWorker, 1500);
    }

    /** App 前后台状态通知给笔（SDK 要求实现；失败静默）。 */
    public void setAppForeground(boolean fg) {
        if (!linkUp) return;
        try { PNote.sendAppShowState(fg ? 1 : 2); } catch (Throwable ignore) {}
    }

    public int pendingCount() { return uploadQueue.size(); }
    public int pendingFailedCount() { return failedCount; }

    public void onNetworkAvailable() {
        main.post(() -> {
            for (UploadTask t : uploadQueue) if (t != null) t.uploadAttempts = 0;
            if (!uploadQueue.isEmpty()) Log.d(TAG, "网络恢复 → 立即重试待传 队列=" + uploadQueue.size());
            kickWorker();
        });
    }

    public void markCurrentSessionAppInitiated() {
        if (sessionActive) sessionAppInitiated = true;
    }

    // ============ 扫描 / 连接 ============

    /**
     * ★SDK 就绪门：PNote 的所有指令都是 EventBus 投给它的 WindBluetoothService，而 init() 绑定服务是异步的。
     * 服务没起来前发命令会被静默丢（logcat: "No subscribers registered for BluetoothEvent"）——冷启动秒发
     * startSearch/connect 就栽在这。这里用 hasSubscriberForEvent 探测服务已注册，没就绪 200ms 重试（封顶 10s）。
     */
    /**
     * ★蓝牙适配器守卫：声云 SDK 的命令处理被写死在 @Subscribe(threadMode=MAIN)——所有 PNote 调用
     * 都在【我们的主线程】执行。蓝牙关闭/切换瞬间，SDK 内部的 closeConnect/startSearch 会同步阻塞，
     * 直接把主线程冻死(真机实锤：63116s 后 penlog 全停、UI 无响应)。适配器不在 ON 态就别发任何 BLE 命令。
     */
    private static boolean btReady() {
        try {
            android.bluetooth.BluetoothAdapter ad = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            return ad != null && ad.getState() == android.bluetooth.BluetoothAdapter.STATE_ON;
        } catch (Throwable t) { return false; }
    }

    private void whenSdkReady(final Runnable r) { whenSdkReady(r, 0); }
    private void whenSdkReady(final Runnable r, final int attempt) {
        boolean ready = false;
        try {
            ready = org.greenrobot.eventbus.EventBus.getDefault()
                    .hasSubscriberForEvent(com.wind.pnote.bluetooth.BluetoothEvent.class);
        } catch (Throwable ignore) {}
        if (ready) { r.run(); return; }
        if (attempt >= 50) { Log.w(TAG, "PNote 服务迟迟未就绪，放弃本次命令"); penLog("★PNote服务10s未就绪,命令被丢"); return; }
        if (attempt == 0) Log.d(TAG, "PNote 服务未就绪，等待后重发命令…");
        main.postDelayed(() -> whenSdkReady(r, attempt + 1), 200);
    }

    /** 所有 PNote BLE 命令统一走这：SDK 就绪 + 蓝牙适配器 ON 双守卫（防 SDK 主线程阻塞冻死，见 btReady 注释）。 */
    private void bleCmd(String what, Runnable r) {
        whenSdkReady(() -> {
            if (!btReady()) { penLog("跳过BLE命令(" + what + "):蓝牙未就绪"); return; }
            try { r.run(); } catch (Throwable t) { Log.e(TAG, what + " failed", t); }
        });
    }

    public void startSearch() {
        bleCmd("startSearch", PNote::startSearch);
    }

    public void stopSearch() {
        bleCmd("stopSearch", PNote::stopSearch);
    }

    /** 扫描页点连接 / 自动重连命中：连接指定设备并记住它。 */
    public void connectTo(String name, String address) {
        if (TextUtils.isEmpty(address)) return;
        currentMac = address;
        lastConnectedMac = address;
        lastConnectedName = name;
        try {
            appCtx.getSharedPreferences("pen_prefs", Context.MODE_PRIVATE).edit()
                    .putString("last_mac", address)
                    .putString("last_name", name == null ? "" : name)
                    .apply();
        } catch (Exception ignore) {}
        penLog("connectTo " + name + " " + address);
        final String n = name == null ? "" : name;
        bleCmd("connectDevice", () -> PNote.connectDevice(n, address));
    }

    /** App 打开/回到前台时，静默自动连接上次那支笔（不弹扫描页）：扫到该 MAC 就连。 */
    public void autoConnect(String savedMac) {
        if (savedMac == null || savedMac.isEmpty()) return;
        if (linkUp || autoConnectMac != null) return;
        autoConnectMac = savedMac;
        scanningForAuto = true;
        startSearch();
        Log.d(TAG, "autoConnect scanning for " + savedMac);
        main.postDelayed(() -> {
            if (autoConnectMac != null) {
                autoConnectMac = null;
                if (scanningForAuto) { scanningForAuto = false; stopSearch(); }
                Log.d(TAG, "autoConnect timeout");
            }
        }, 12000);
    }

    public void stopAutoReconnect() {
        autoReconnectOn = false;
        main.removeCallbacks(reconnectRunnable);
    }

    private final Runnable reconnectRunnable = this::tryReconnect;

    private void enableAutoReconnect() {
        autoReconnectOn = true;
        reconnectAttempts = 0;
        main.removeCallbacks(reconnectRunnable);
    }

    private void scheduleReconnect(long delayMs) {
        if (!autoReconnectOn) return;
        main.removeCallbacks(reconnectRunnable);
        main.postDelayed(reconnectRunnable, delayMs);
    }

    private void tryReconnect() {
        if (!autoReconnectOn || linkUp) return;
        boolean btOn = false;
        try {
            android.bluetooth.BluetoothAdapter ad = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            btOn = ad != null && ad.isEnabled();
        } catch (Exception ignored) {}
        if (btOn && lastConnectedMac != null && lastConnectedMac.equals(penDeniedMac)) {
            penLog("自动重连跳过被拒的笔 " + lastConnectedMac);
        } else if (btOn && lastConnectedMac != null) {
            reconnectAttempts++;
            Log.d(TAG, "自动重连尝试#" + reconnectAttempts + " → " + lastConnectedMac);
            penLog("自动重连尝试#" + reconnectAttempts + " 扫描连 " + lastConnectedMac);
            autoConnect(lastConnectedMac);
        }
        scheduleReconnect(reconnectAttempts <= 5 ? 15000 : 60000);
    }

    private void registerBtReceiver() {
        if (btStateReceiver != null) return;
        btStateReceiver = new android.content.BroadcastReceiver() {
            @Override public void onReceive(android.content.Context c, android.content.Intent i) {
                int st = i.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, -1);
                penLog("蓝牙状态广播 state=" + st + " autoReconnectOn=" + autoReconnectOn + " linkUp=" + linkUp);
                if (st == android.bluetooth.BluetoothAdapter.STATE_ON && autoReconnectOn && !linkUp) {
                    reconnectAttempts = 0;
                    scheduleReconnect(1500);
                }
            }
        };
        try {
            androidx.core.content.ContextCompat.registerReceiver(appCtx, btStateReceiver,
                    new android.content.IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED),
                    androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
        } catch (Exception e) { Log.e(TAG, "registerBtReceiver failed", e); }
    }

    // ============ 真连接判定 / 心跳 ============

    private void markPenResponded() {
        lastRxMs = SystemClock.elapsedRealtime();
        if (!verifiedConnected) {
            verifiedConnected = true;
            main.removeCallbacks(handshakeTimeout);
            Log.d(TAG, "收到笔真实回包 → 确认真连上");
            penLog("★verified=true 收到真回包→确认已连接");
            if (listener != null) main.post(() -> listener.onPenConnected(true));
            connKeepAlive(true);   // ★笔真连上 → 挂连接级前台服务，App 在后台/锁屏也不被杀(蓝牙不断)
            startHeartbeat();
            verifyPenAllowed();
            if (pendingRecovery != null) {
                UploadTask r = pendingRecovery; pendingRecovery = null;
                if (shouldEnqueue(r.fileName)) {
                    uploadQueue.add(r);
                    Log.d(TAG, "重连后补传中断录音 file=" + r.fileName);
                    notifyPending();
                }
            }
            materializeRestored();
            kickWorker();
        }
    }

    private final Runnable handshakeTimeout = new Runnable() {
        @Override public void run() {
            if (!verifiedConnected) {
                Log.w(TAG, "连上但笔无回包 → 判定假连接，断开");
                penLog("★握手超时(无真回包)→判假连接、主动断开");
                if (btReady()) { try { PNote.closeConnect(); } catch (Throwable ignore) {} } else penLog("跳过closeConnect:蓝牙未就绪");
                if (listener != null) main.post(() -> listener.onPenConnected(false));
            }
        }
    };

    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (!linkUp) { stopHeartbeat(); return; }
            checkMaxRecordLimit();   // ★每拍查一次：录满90分钟就自动结束保存
            long now = SystemClock.elapsedRealtime();
            boolean busy = penRecording || sessionActive || workerBusy || waitingForFile || dlState != null;
            if (busy && (now - lastRxMs) < HB_INTERVAL_BUSY_MS) {
                hbMissed = 0; main.postDelayed(this, HB_INTERVAL_BUSY_MS); return;
            }
            if ((now - lastRxMs) > (HB_INTERVAL_MS + 4000)) {
                hbMissed++;
                Log.w(TAG, "心跳未回包，连续 " + hbMissed + " 次");
                if (hbMissed >= 2) {
                    Log.w(TAG, "笔失联 → 断开，连接指示如实改未连接");
                    penLog("★心跳连续2次未回→判失联、主动断开");
                    verifiedConnected = false;
                    if (btReady()) { try { PNote.closeConnect(); } catch (Throwable ignore) {} } else penLog("跳过closeConnect:蓝牙未就绪");
                    if (listener != null) main.post(() -> listener.onPenConnected(false));
                    connKeepAlive(false);   // ★失联 → 去抖90s后撤连接级保活(其间重连成功会取消)
                    resetSessionOnLinkDown();
                    stopHeartbeat();
                    return;
                }
            } else {
                hbMissed = 0;
            }
            if (!busy && btReady()) {
                try { PNote.getRecordState(); } catch (Throwable ignore) {}   // cmd=9 回包即心跳
                // ~每 64s 查一次电量(cmd=6)，低电时由 cmd=6 回包触发充电通知
                if ((++hbBatteryTick % 8) == 0) { try { PNote.getCBC(); } catch (Throwable ignore) {} }
            }
            main.postDelayed(this, busy ? HB_INTERVAL_BUSY_MS : HB_INTERVAL_MS);
        }
    };
    private void startHeartbeat() { hbMissed = 0; main.removeCallbacks(heartbeat); main.postDelayed(heartbeat, HB_INTERVAL_MS); }
    private void stopHeartbeat() { main.removeCallbacks(heartbeat); hbMissed = 0; }

    /**
     * 录满 90 分钟自动结束的判定。只在【真在录、未暂停、本段还没触发过】时查；
     * 时长优先用笔上报的真实录音时长，笔没报才退回 elapsedSec()（无基准时它返回 0，不会误触发）。
     * 心跳每 8~15s 跑一次，到点 15s 内必结束。
     */
    private void checkMaxRecordLimit() {
        if (autoStoppedAt90 || penPaused || !penRecording) return;
        int dur = lastPenDurationSec > 0 ? lastPenDurationSec : elapsedSec();
        if (dur >= MAX_REC_SEC) maybeAutoStopAt90(dur);
    }

    /** 到 90 分钟：笔 endRecord + App 收尾上传（这段照常保存），并通知 UI 提示"已自动结束，要继续请点开始陪伴"。 */
    private void maybeAutoStopAt90(int durSec) {
        if (durSec < MAX_REC_SEC) return;
        main.post(() -> {
            if (autoStoppedAt90 || !penRecording) return;
            autoStoppedAt90 = true;
            Log.w(TAG, "录满90分钟 → 自动结束 dur=" + durSec);
            penLog("★录满90分钟,自动结束保存(要继续请点开始陪伴)");
            stopRecording();   // 走正常结束流程：笔停 + 这段照常收尾上传
            final Listener l = listener;
            if (l != null) main.post(l::onPenAutoStopped);
        });
    }

    // ============ App 主动控制 ============

    public void startRecording(String cookie, String uploadUrl) {
        setUploadContext(cookie, uploadUrl);
        if (penRecording || sessionActive) {
            Log.d(TAG, "startRecording: 笔已在录，忽略重复开始，回推 recording 纠正UI");
            markCurrentSessionAppInitiated();
            post(PhoneMicService.STATE_RECORDING, "录音中…（录音笔）", elapsedSec(), -1);
            return;
        }
        if (!linkUp) {
            if (listener != null) listener.onPenNeedConnect();
            return;
        }
        if (isPenAlive()) {
            doStartRecord();
        } else {
            preStartVerifyThenRecord();
        }
    }

    private void doStartRecord() {
        if (!penAllowed) {
            String msg = (penDenyMsg != null && !penDenyMsg.isEmpty()) ? penDenyMsg
                    : "正在确认录音笔归属，请稍候；或在列表里换用你自己的录音笔";
            post(PhoneMicService.STATE_ERROR, msg, 0, -1);
            return;
        }
        appStartPending = true;
        sessionStartWallMs = System.currentTimeMillis();
        enterRecordingKeepAlive();
        try {
            PNote.startRecord();
            post(PhoneMicService.STATE_STARTING, "正在唤醒录音笔…", 0, -1);
            main.removeCallbacks(confirmTimeout);
            main.postDelayed(confirmTimeout, CONFIRM_TIMEOUT_MS);
        } catch (Throwable e) {
            Log.e(TAG, "startRecord failed", e);
            appStartPending = false;
            exitRecordingKeepAlive();
            post(PhoneMicService.STATE_ERROR, "录音笔启动失败：" + e.getMessage(), 0, -1);
        }
    }

    private void preStartVerifyThenRecord() {
        post(PhoneMicService.STATE_STARTING, "正在确认录音笔…", 0, -1);
        try { PNote.getRecordState(); } catch (Throwable ignore) {}
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
                if (btReady()) { try { PNote.closeConnect(); } catch (Throwable ignore) {} } else penLog("跳过closeConnect:蓝牙未就绪");
                if (listener != null) main.post(() -> listener.onPenConnected(false));
            }
        }
    };

    private final Runnable confirmTimeout = new Runnable() {
        @Override public void run() {
            if (appStartPending && !penRecording) {
                Log.w(TAG, "笔未在 " + CONFIRM_TIMEOUT_MS + "ms 内确认开录 → 判定休眠/关机");
                appStartPending = false;
                sessionStartWallMs = 0;
                exitRecordingKeepAlive();
                post(PhoneMicService.STATE_ERROR, "启动失败：录音笔没有响应，请确认它已开机/唤醒后重试", 0, -1);
                if (btReady()) { try { PNote.closeConnect(); } catch (Throwable ignore) {} } else penLog("跳过closeConnect:蓝牙未就绪");
            }
        }
    };

    private final Runnable recordingHealthWatchdog = new Runnable() {
        @Override public void run() {
            healthWatchdogArmed = false;
            if (!sessionActive || !sessionAppInitiated || penPaused) return;
            boolean haveAudioEvidence = (sessionStreamFrames > 0) || (lastPenDurationSec > 0);
            if (haveAudioEvidence) return;
            Log.w(TAG, "开录健康检查失败：" + REC_HEALTH_GRACE_MS + "ms 内无实时流帧也无时长 → 判定笔未真正开始录音");
            penLog("★开录看门狗触发(无流无时长)→判未真录、停并报错 frames=" + sessionStreamFrames + " penDur=" + lastPenDurationSec);
            abortGhostRecording();
        }
    };

    private void abortGhostRecording() {
        disarmHealthWatchdog();
        stopRecordDurationPoll();
        exitRecordingKeepAlive();
        sessionGen++;
        penRecording = false; sessionActive = false; appStartPending = false; sessionAppInitiated = false;
        sessionAdopted = false;
        startElapsedMs = 0; sessionFileName = null; sessionStartWallMs = 0;
        sessionStreamBuf = null; sessionStreamComplete = false; sessionStreamBytes = 0; sessionStreamFrames = 0;
        lastPenDurationSec = 0;
        main.removeCallbacks(streamStallWatch);
        try { PNote.stopRecord(); } catch (Throwable ignore) {}
        post(PhoneMicService.STATE_ERROR, "录音笔没有真正开始录音（信号弱/未唤醒），请靠近后重试", 0, -1);
    }

    private void armHealthWatchdog() {
        disarmHealthWatchdog();
        lastPenDurationSec = 0;
        healthWatchdogArmed = true;
        main.postDelayed(recordingHealthWatchdog, REC_HEALTH_GRACE_MS);
    }
    private void disarmHealthWatchdog() {
        healthWatchdogArmed = false;
        main.removeCallbacks(recordingHealthWatchdog);
    }

    private final Runnable reconnectGiveUp = new Runnable() {
        @Override public void run() {
            if (sessionActive || penRecording) return;
            Log.w(TAG, "断线重连宽限到，仍未恢复录音 → 老实结束本段(只到断开前)");
            penLog("★重连宽限到仍未恢复→老实结束、停墙钟空跑");
            exitRecordingKeepAlive();
            post(PhoneMicService.STATE_ERROR, "录音笔信号中断，本段只保存到断开前，请重连后继续", 0, -1);
        }
    };

    private void resetSessionOnLinkDown() {
        main.removeCallbacks(confirmTimeout);
        disarmHealthWatchdog();
        stopRecordDurationPoll();
        if (!(sessionActive || penRecording || appStartPending)) return;
        sessionStreamComplete = false;
        Log.w(TAG, "链路掉→复位残留会话 sessionActive=" + sessionActive + " penRecording=" + penRecording + " appStartPending=" + appStartPending);
        if (sessionActive && !TextUtils.isEmpty(sessionFileName) && cookie != null && uploadUrl != null) {
            pendingRecovery = new UploadTask(sessionFileName, cookie, uploadUrl, penSn(),
                    elapsedSec(), sessionStartWallMs, sessionAppInitiated);
            Log.w(TAG, "录音中断链路，保存待补传 file=" + sessionFileName);
        }
        boolean willRecover = (pendingRecovery != null);
        boolean wasRecording = (sessionActive || penRecording);
        int wasElapsed = elapsedSec();
        sessionGen++;
        penRecording = false; sessionActive = false;
        appStartPending = false; sessionAppInitiated = false; sessionAdopted = false;
        startElapsedMs = 0; sessionFileName = null; sessionStartWallMs = 0;
        if (wasRecording && autoReconnectOn) {
            post(PhoneMicService.STATE_RECORDING, "🔄 信号断开，正在自动重连，录音继续中…", wasElapsed, -1);
            main.removeCallbacks(reconnectGiveUp);
            main.postDelayed(reconnectGiveUp, RECONNECT_RECORDING_GRACE_MS);
        } else {
            exitRecordingKeepAlive();
            post(PhoneMicService.STATE_ERROR,
                    willRecover ? "录音笔断开了，正在自动重连…" : "录音笔已断开，正在自动重连…", 0, -1);
        }
    }

    public void stopRecording() {
        main.removeCallbacks(confirmTimeout);
        main.removeCallbacks(preStartTimeout);
        disarmHealthWatchdog();
        main.removeCallbacks(reconnectGiveUp);
        if (appStartPending && !penRecording) {
            appStartPending = false;
            sessionStartWallMs = 0;
            exitRecordingKeepAlive();
            post(PhoneMicService.STATE_IDLE, "已结束", 0, -1);
            return;
        }
        if (!penRecording && !sessionActive) {
            exitRecordingKeepAlive();
            post(PhoneMicService.STATE_IDLE, "已结束", 0, -1);
            return;
        }
        penPaused = false;
        final String fnAtStop = sessionFileName;
        final int durAtStop = elapsedSec();
        final long startWallAtStop = sessionStartWallMs;
        final int genAtStop = sessionGen;
        try {
            PNote.stopRecord();
        } catch (Throwable e) {
            Log.e(TAG, "stopRecord failed", e);
            post(PhoneMicService.STATE_ERROR, "录音笔停止失败：" + e.getMessage(), elapsedSec(), -1);
            return;
        }
        main.postDelayed(() -> {
            if (sessionActive && sessionGen == genAtStop) {
                Log.w(TAG, "stopRecord 后笔未回停止帧，兜底收尾入队 gen=" + genAtStop);
                finishSessionEnqueue(fnAtStop, durAtStop, startWallAtStop);
            }
        }, 4000);
    }

    public void pause() {
        if (penPaused) return;
        appInitiatedPauseResume = true;
        penPaused = true;
        try { PNote.pauseRecord(); } catch (Throwable e) { Log.e(TAG, "pauseRecord failed", e); }
        if (listener != null) main.post(() -> listener.onPenPaused(true));
    }

    public void resume() {
        if (!penPaused) return;
        appInitiatedPauseResume = true;
        penPaused = false;
        try { PNote.continueRecord(); } catch (Throwable e) { Log.e(TAG, "continueRecord failed", e); }
        if (listener != null) main.post(() -> listener.onPenPaused(false));
    }

    // ============ 会话收尾：入队后台上传，立刻回 idle ============

    private void finishSessionEnqueue(String fileName, int durSec, long startWallMs) {
        disarmHealthWatchdog();
        main.removeCallbacks(reconnectGiveUp);
        stopRecordDurationPoll();
        lastPenDurationSec = 0;
        final boolean appInit = sessionAppInitiated;
        main.removeCallbacks(streamStallWatch);
        final java.io.ByteArrayOutputStream streamBuf = sessionStreamBuf;
        boolean streamComplete = sessionStreamComplete;
        // ★接管会话(连上时笔已在录)：实时流只覆盖接管之后的尾段，绝不当完整段直传——
        //   否则尾段抢先占坑，后面下载的完整版会被服务器按 pen_file 去重丢弃(只剩尾段=丢音频)。
        if (sessionAdopted) {
            streamComplete = false;
            penLog("★接管会话收尾→强制走补下载全文件(尾段流不可信)");
        }
        sessionAdopted = false;
        final long streamBytes = sessionStreamBytes;
        sessionStreamBuf = null; sessionStreamComplete = false;
        sessionStreamBytes = 0; sessionStreamFrames = 0;
        // ★防截断件直传(最后一道闸)：实时流估算秒数(2000B/s) 远小于墙钟 → 疑似中途静默卡流，回落补下载
        if (streamComplete && durSec >= 30 && streamBuf != null) {
            int estStreamSec = (int) (streamBytes / STREAM_BYTES_PER_SEC);
            if (estStreamSec < durSec * 0.6) {
                Log.w(TAG, "实时流估算" + estStreamSec + "s ≪ 墙钟" + durSec + "s → 疑似截断，回落补下载");
                penLog("★疑似截断 est=" + estStreamSec + "s wall=" + durSec + "s → 回落补下载");
                streamComplete = false;
            }
        }

        sessionGen++;
        penRecording = false;
        sessionActive = false;
        appStartPending = false;
        sessionAppInitiated = false;
        startElapsedMs = 0;
        sessionFileName = null;
        sessionStartWallMs = 0;

        final boolean haveCtx = (cookie != null && uploadUrl != null);
        final String fn = !TextUtils.isEmpty(fileName) ? fileName : null;

        String localRaw = null;
        if (haveCtx && streamComplete && streamBuf != null && streamBytes > 800) {
            localRaw = writeStreamRaw(streamBuf, fn, startWallMs);
        }

        if (localRaw != null) {
            final UploadTask task = new UploadTask(fn != null ? fn : ("stream_" + startWallMs),
                    cookie, uploadUrl, penSn(), durSec, startWallMs, appInit);
            task.localRawPath = localRaw;
            uploadQueue.add(task);
            Log.d(TAG, "入队【直传实时流opus】 file=" + task.fileName + " bytes=" + streamBytes + " 队列=" + uploadQueue.size());
            enqueuePlaceholderAndKick(task, startWallMs, 300);
        } else if (fn != null && haveCtx && shouldEnqueue(fn)) {
            final UploadTask task = new UploadTask(fn, cookie, uploadUrl, penSn(), durSec, startWallMs, appInit);
            task.firstSeenMs = System.currentTimeMillis();
            uploadQueue.add(task);
            Log.d(TAG, "入队后台下载补全 file=" + fn + "(流不完整) 队列=" + uploadQueue.size());
            enqueuePlaceholderAndKick(task, startWallMs, 1200);
        } else if (fn != null && haveCtx) {
            Log.d(TAG, "跳过重复入队 file=" + fn + "(已传/已在队)，仅推进队列");
            post(PhoneMicService.STATE_IDLE, "已结束", 0, -1);
            main.postDelayed(this::kickWorker, 800);
        } else {
            if (appInit) {
                failedCount++;
                Log.w(TAG, "缺文件名/上传上下文，app-initiated 计入失败 fn=" + fn);
                notifyPending();
            } else {
                Log.d(TAG, "缺文件名/上传上下文(笔自发空录)，静默丢 fn=" + fn);
            }
            post(PhoneMicService.STATE_IDLE, "已结束", 0, -1);
        }
        exitRecordingKeepAlive();
    }

    private void enqueuePlaceholderAndKick(final UploadTask task, final long recStart, long kickDelayMs) {
        notifyPending();
        post(PhoneMicService.STATE_IDLE, "已结束", 0, -1);
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

    // ============ 实时流捕获 ============

    private void startStreamCapture() {
        sessionStreamBuf = new java.io.ByteArrayOutputStream();
        sessionStreamComplete = true;
        sessionStreamBytes = 0; sessionStreamFrames = 0;
        lastStreamFrameMs = 0;
        main.removeCallbacks(streamStallWatch);
        main.postDelayed(streamStallWatch, 2000);
    }

    /** 实时流回调（SDK 线程直入，不过主线程）：标准 opus 裸帧 160B/包(4×40B) 原样拼接。 */
    @Override
    public void onDeviceRecordData(byte[] data) {
        if (data == null || data.length == 0) return;
        lastRxMs = SystemClock.elapsedRealtime();   // 音频帧也是活性证据
        java.io.ByteArrayOutputStream buf = sessionStreamBuf;
        if (buf == null) return;
        try { synchronized (buf) { buf.write(data); } } catch (Exception ignore) {}
        sessionStreamBytes += data.length;
        sessionStreamFrames++;
        lastStreamFrameMs = SystemClock.elapsedRealtime();
        if (healthWatchdogArmed) { healthWatchdogArmed = false; main.removeCallbacks(recordingHealthWatchdog); }
    }

    private final Runnable streamStallWatch = new Runnable() {
        @Override public void run() {
            if (sessionStreamBuf == null) return;
            if (sessionActive && !penPaused && sessionStreamComplete
                    && lastStreamFrameMs > 0
                    && (SystemClock.elapsedRealtime() - lastStreamFrameMs) > STREAM_STALL_MS) {
                sessionStreamComplete = false;
                Log.w(TAG, "实时流卡死 >" + STREAM_STALL_MS + "ms 无新帧 → 标记流不完整，收尾走补下载 bytes=" + sessionStreamBytes);
                penLog("★实时流卡死无新帧→标不完整(回落补下载) bytes=" + sessionStreamBytes);
            }
            main.postDelayed(this, 2000);
        }
    };

    /** 录音中轮询笔上时长（cmd=10 回包兼作忙时心跳）。 */
    private final Runnable recordDurationPoll = new Runnable() {
        @Override public void run() {
            if (!sessionActive) return;
            if (btReady()) { try { PNote.getTimeOnlyRecording(); } catch (Throwable ignore) {} }
            main.postDelayed(this, 5000);
        }
    };
    private void startRecordDurationPoll() {
        main.removeCallbacks(recordDurationPoll);
        main.postDelayed(recordDurationPoll, 5000);
    }
    private void stopRecordDurationPoll() { main.removeCallbacks(recordDurationPoll); }

    // ============ 保活 ============

    private void enterRecordingKeepAlive() {
        if (keepAliveOn) return;
        keepAliveOn = true;
        PenKeepAliveService.recOn(appCtx);
        penLog("保活↑(录音级:FGS+唤醒锁)");
    }

    private void exitRecordingKeepAlive() {
        if (!keepAliveOn) return;
        keepAliveOn = false;
        PenKeepAliveService.recOff(appCtx);   // 只撤录音级(放唤醒锁)；笔还连着时连接级 FGS 仍托住后台
        penLog("保活↓(录音级)");
    }

    /** 笔真连上 → 挂连接级 FGS(进程后台不被杀、蓝牙不断)；断开 → 去抖 90s 再撤(抖动/重连期间不撤)。 */
    private final Runnable connKeepAliveStopRun = new Runnable() {
        @Override public void run() {
            connKeepAliveOn = false;
            PenKeepAliveService.connOff(appCtx);
            penLog("保活·连接级↓(笔断开超" + (CONN_KEEPALIVE_LINGER_MS / 1000) + "s)");
        }
    };
    private void connKeepAlive(boolean connected) {
        if (connected) {
            main.removeCallbacks(connKeepAliveStopRun);
            if (!connKeepAliveOn) { connKeepAliveOn = true; penLog("保活·连接级↑(笔已连, FGS托住后台)"); }
            PenKeepAliveService.connOn(appCtx);
        } else {
            if (!connKeepAliveOn) return;
            main.removeCallbacks(connKeepAliveStopRun);
            main.postDelayed(connKeepAliveStopRun, CONN_KEEPALIVE_LINGER_MS);
        }
    }

    // ============ 诊断（与杰理版同路径，一键诊断照常能捞） ============

    private void penLog(String ev) {
        try {
            File root = appCtx.getExternalFilesDir(null);
            if (root == null) return;
            File dir = new File(root, "stream_ops");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, "penlog.txt");
            try (java.io.FileWriter w = new java.io.FileWriter(f, f.length() < 262144)) {
                w.write((SystemClock.elapsedRealtime() / 1000 % 100000) + "s  " + ev
                        + "  [conn=" + verifiedConnected + " rec=" + penRecording + " sess=" + sessionActive + " q=" + uploadQueue.size() + "]\n");
            }
        } catch (Exception ignore) {}
    }

    private void writeProbeStatus(String line) {
        try {
            File root = appCtx.getExternalFilesDir(null);
            if (root == null) return;
            File dir = new File(root, "stream_ops");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, "last_result.txt");
            try (java.io.FileWriter w = new java.io.FileWriter(f, f.length() < 131072)) {
                w.write(SystemClock.elapsedRealtime() / 1000 % 100000 + "s  " + line + "\n");
            }
        } catch (Exception ignore) {}
    }

    private void buzz(final boolean isEnd) {
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) appCtx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            final String chId = isEnd ? "pen_buzz_end" : "pen_buzz_start";
            final long[] pattern = isEnd ? new long[]{0, 90, 120, 90} : new long[]{0, 130};
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                if (nm.getNotificationChannel(chId) == null) {
                    android.app.NotificationChannel ch = new android.app.NotificationChannel(
                            chId, isEnd ? "结束陪伴提示" : "开启陪伴提示",
                            android.app.NotificationManager.IMPORTANCE_DEFAULT);
                    ch.enableVibration(true);
                    ch.setVibrationPattern(pattern);
                    ch.setSound(null, null);
                    ch.setShowBadge(false);
                    nm.createNotificationChannel(ch);
                }
            }
            android.app.Notification.Builder b = (android.os.Build.VERSION.SDK_INT >= 26)
                    ? new android.app.Notification.Builder(appCtx, chId)
                    : new android.app.Notification.Builder(appCtx).setVibrate(pattern);
            android.app.Notification n = b
                    .setContentTitle(isEnd ? "结束陪伴" : "开启陪伴")
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setAutoCancel(true)
                    .build();
            final int id = isEnd ? 5301 : 5300;
            nm.notify(id, n);
            main.postDelayed(() -> { try { nm.cancel(id); } catch (Exception ignore) {} }, 1500);
        } catch (Exception ignore) {}
    }

    private String writeStreamRaw(java.io.ByteArrayOutputStream buf, String fileName, long startWallMs) {
        try {
            byte[] bytes; synchronized (buf) { bytes = buf.toByteArray(); }
            if (bytes.length < 800) return null;
            String base = !TextUtils.isEmpty(fileName) ? stripExt(fileName) : ("stream_" + startWallMs);
            File dir = new File(appCtx.getCacheDir(), "stream_ops");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, base + ".sopus");
            try (java.io.FileOutputStream fo = new java.io.FileOutputStream(f)) { fo.write(bytes); }
            Log.d(TAG, "实时流已落地 " + f.getAbsolutePath() + " " + bytes.length + "B 帧约" + (bytes.length / 40));
            return f.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "writeStreamRaw 失败", e);
            return null;
        }
    }

    // ============ SDK 事件入口 ============

    @Override
    public void onDeviceDataEvent(String data) {
        if (data == null || data.isEmpty()) return;
        final String json = data;
        main.post(() -> {
            try { handleEvent(new JSONObject(json)); }
            catch (Exception e) { Log.w(TAG, "事件解析失败: " + e.getMessage() + " raw=" + json); }
        });
    }

    private void handleEvent(JSONObject map) {
        String cmd = String.valueOf(map.opt("cmd"));
        JSONObject data = map.optJSONObject("data");
        switch (cmd) {
            case "1": handleDeviceFound(data); break;
            case "2": handleConnectState(data); break;
            case "3": markPenResponded(); handleRecordState(data); break;
            case "4": markPenResponded(); handleFileList(map); break;
            case "5": markPenResponded(); handleTransferState(data); break;
            case "6": markPenResponded();
                batteryPct = data != null ? String.valueOf(data.opt("cbc")) : "";
                notifyBatteryIfLow();   // 低电(<10%/<5%)发本地通知充电
                notifyBatteryToUi();    // 推给首页显示电量
                break;
            case "7": markPenResponded();
                if (data != null) {
                    String sn = String.valueOf(data.opt("sn"));
                    if (!TextUtils.isEmpty(sn) && !"null".equals(sn)) penSn = sn;
                }
                break;
            case "8": markPenResponded(); handleButtonEvent(data); break;
            case "9": markPenResponded(); handleRecordStateQueried(data); break;
            case "10": markPenResponded(); handleRecordTime(data); break;
            case "11": markPenResponded();
                if (data != null && sessionActive) {
                    String rn = String.valueOf(data.opt("recordName"));
                    if (!TextUtils.isEmpty(rn) && !"null".equals(rn)) sessionFileName = rn;
                }
                break;
            case "14": markPenResponded(); break;   // 删除结果，忽略
            default: break;   // 其余(WiFi/OTA/增益等)本控制器不用
        }
    }

    // cmd=1 设备发现：转给扫描页 + 自动重连匹配
    private void handleDeviceFound(JSONObject data) {
        if (data == null) return;
        String name = String.valueOf(data.opt("name"));
        String address = String.valueOf(data.opt("address"));
        String productType = String.valueOf(data.opt("productType"));
        if ("null".equals(name)) name = "";
        if ("null".equals(address)) address = "";
        ScanListener sl = scanListener;
        if (sl != null) {
            final String n = name, a = address, p = productType;
            main.post(() -> { ScanListener cur = scanListener; if (cur != null) cur.onDeviceFound(n, a, p); });
        }
        if (autoConnectMac != null && autoConnectMac.equalsIgnoreCase(address)) {
            String mac = autoConnectMac;
            autoConnectMac = null;
            if (scanningForAuto) { scanningForAuto = false; stopSearch(); }
            Log.d(TAG, "autoConnect matched, connecting " + mac);
            connectTo(name, address);
        }
    }

    // cmd=2 连接状态
    private void handleConnectState(JSONObject data) {
        if (data == null) return;
        String cs = String.valueOf(data.opt("connect_state"));
        boolean connected = "true".equals(cs) || "1".equals(cs);
        if (connected) {
            String name = String.valueOf(data.opt("name"));
            String address = String.valueOf(data.opt("address"));
            if (!"null".equals(address) && !TextUtils.isEmpty(address)) {
                lastConnectedMac = address;
                currentMac = address;
            }
            if (!"null".equals(name) && !TextUtils.isEmpty(name)) lastConnectedName = name;
            linkUp = true;
            penAllowed = true;    // fail-open：默认放行，后端明确判拒才挡
            penDenyMsg = null;
            penLog("cmd2 链路就绪 " + currentMac);
            enableAutoReconnect();
            verifiedConnected = false;
            lastRxMs = 0;
            // 手册：连接成功后延时约 2 秒等通道稳定再下发初始化指令
            main.postDelayed(() -> {
                if (!linkUp || !btReady()) return;
                try { PNote.syncTime(); } catch (Throwable ignore) {}      // ★同步手机时间，根治笔时钟漂移
                try { PNote.getSn(); } catch (Throwable ignore) {}
                try { PNote.getCBC(); } catch (Throwable ignore) {}
                try { PNote.getRecordState(); } catch (Throwable ignore) {}  // 连上时笔可能已在录
                try { PNote.sendAppShowState(1); } catch (Throwable ignore) {}
            }, 2000);
            main.removeCallbacks(handshakeTimeout);
            main.postDelayed(handshakeTimeout, HANDSHAKE_TIMEOUT_MS);
            // 连上空闲后清理笔上 >30天 旧文件（同杰理版节奏）
            main.postDelayed(this::triggerCleanup, 9000);
        } else {
            penLog("cmd2 断开 reason=" + cs);
            boolean wasUp = linkUp;
            linkUp = false;
            verifiedConnected = false; lastRxMs = 0;
            stopHeartbeat();
            main.removeCallbacks(handshakeTimeout);
            if (listener != null) main.post(() -> listener.onPenConnected(false));
            connKeepAlive(false);   // ★断开 → 去抖90s后撤连接级保活
            // 断开时若后台正在下载 → 收口部分文件，留队重连续传
            DownloadState dl = dlState;
            if (dl != null) {
                closeDownload(dl);
                dlState = null;
                workerTaskFailed(dl.task, "disconnected", false);
            } else if (workerBusy && currentTask != null && inflightTask == null) {
                workerTaskFailed(currentTask, "disconnected", false);
            }
            resetSessionOnLinkDown();
            if (wasUp && autoReconnectOn) {
                Log.d(TAG, "断开 → 启动自动重连循环");
                reconnectAttempts = 0;
                scheduleReconnect(3000);
            }
        }
    }

    // cmd=3 录音状态（开始/停止/暂停），App 发起与笔上按键殊途同归都走这
    private void handleRecordState(JSONObject data) {
        if (data == null) return;
        String rs = String.valueOf(data.opt("record_state"));
        String fileName = String.valueOf(data.opt("fileName"));
        if ("null".equals(fileName)) fileName = "";
        penLog("cmd3 record_state=" + rs + " file=" + fileName + " appStartPending=" + appStartPending);
        if ("1".equals(rs)) {
            boolean wasAppStart = appStartPending;
            boolean wasPaused = penPaused;
            penRecording = true;
            appStartPending = false;
            main.removeCallbacks(confirmTimeout);
            if (sessionActive && wasPaused) {
                // 暂停 → 继续
                penPaused = false;
                if (appInitiatedPauseResume) { appInitiatedPauseResume = false; }
                else if (listener != null) main.post(() -> listener.onPenPaused(false));
                if (!TextUtils.isEmpty(fileName)) sessionFileName = fileName;
                return;
            }
            if (!sessionActive && !wasAppStart && isStaleRecordingFile(fileName)) {
                penLog("忽略幽灵旧录音开始 file=" + fileName);
                penRecording = false;
                return;
            }
            if (!sessionActive) {
                sessionActive = true;
                sessionAdopted = false;   // 从头跟到的新段，实时流可信
                enterRecordingKeepAlive();
                sessionAppInitiated = wasAppStart;
                sessionGen++;
                failedCount = 0;
                sessionFileName = !TextUtils.isEmpty(fileName) ? fileName : null;
                if (sessionStartWallMs == 0) sessionStartWallMs = System.currentTimeMillis();
                startElapsedMs = SystemClock.elapsedRealtime();
                autoStoppedAt90 = false;   // 新一段开始 → 重置90分钟自动结束标记
                penPaused = false;
                pauseWorker();
                startStreamCapture();
                startRecordDurationPoll();
                if (sessionFileName == null) {
                    // 开始帧没带文件名 → 主动问一次（cmd=11 回填），收尾/补传都靠它
                    main.postDelayed(() -> { if (sessionActive && sessionFileName == null) {
                        try { PNote.getFileNameOnlyRecording(); } catch (Throwable ignore) {}
                    }}, 1500);
                }
                Log.d(TAG, "镜像：笔开始录音 file=" + sessionFileName + " gen=" + sessionGen);
                post(PhoneMicService.STATE_RECORDING, "录音中…（录音笔）", 0, -1);
                buzz(false);
                main.removeCallbacks(reconnectGiveUp);
                if (wasAppStart) armHealthWatchdog();
                if (listener != null) main.post(() -> listener.onPenRecordStatus(true));
            } else if (!TextUtils.isEmpty(fileName)) {
                sessionFileName = fileName;
            }
        } else if ("2".equals(rs)) {
            // 笔上报暂停
            penRecording = true;
            if (appInitiatedPauseResume) { appInitiatedPauseResume = false; penPaused = true; return; }
            penPaused = true;
            if (listener != null) main.post(() -> listener.onPenPaused(true));
        } else {
            // 停止
            penRecording = false;
            penPaused = false;
            if (!sessionActive) {
                kickWorker();
                return;
            }
            buzz(true);
            finishSessionEnqueue(
                    !TextUtils.isEmpty(fileName) ? fileName : sessionFileName,
                    elapsedSec(), sessionStartWallMs);
        }
    }

    // cmd=8 笔上实体按键请求：App 必须应答对应 *BtnBackRecord，笔才执行
    private void handleButtonEvent(JSONObject data) {
        if (data == null) return;
        String ev = String.valueOf(data.opt("event"));
        penLog("cmd8 按键 event=" + ev);
        try {
            switch (ev) {
                case "1": PNote.startBtnBackRecord(); break;     // 随后 cmd=3 state=1 → 镜像开始(笔自发)
                case "3": PNote.stopBtnBackRecord(); break;      // 随后 cmd=3 state=0 → 收尾入队
                case "5": PNote.pauseBtnBackRecord(); break;
                case "7": PNote.continueBtnBackRecord(); break;
                default: break;
            }
        } catch (Throwable e) { Log.e(TAG, "btnBack 应答失败", e); }
    }

    // cmd=9 录音状态查询结果（连上时笔可能已在录 → 采纳为镜像会话）
    private void handleRecordStateQueried(JSONObject data) {
        if (data == null) return;
        boolean recording = "1".equals(String.valueOf(data.opt("recordState")));
        penLog("cmd9 recordState=" + (recording ? 1 : 0));
        if (recording && !sessionActive) {
            penRecording = true;
            sessionActive = true;
            sessionAdopted = true;   // ★接管中途会话：实时流缺前半段，收尾必须走补下载
            enterRecordingKeepAlive();
            sessionGen++;
            sessionAppInitiated = false;
            sessionFileName = null;
            sessionStartWallMs = 0;   // 更早开始的，不做旧文件拒收
            if (startElapsedMs == 0) startElapsedMs = SystemClock.elapsedRealtime();
            pauseWorker();
            startStreamCapture();
            startRecordDurationPoll();
            try { PNote.getFileNameOnlyRecording(); } catch (Throwable ignore) {}
            post(PhoneMicService.STATE_RECORDING, "录音中…（录音笔）", 0, -1);
            main.removeCallbacks(reconnectGiveUp);
        } else if (!recording && sessionActive && !appStartPending && !penPaused) {
            // 巡检发现笔其实没在录（黑屏漏帧等）→ 老实收尾。
            // ★penPaused 守卫：暂停时笔可能回"不在录音"，不能把暂停中的会话误杀。
            Log.w(TAG, "cmd9 笔没在录但会话残留 → 收尾入队");
            finishSessionEnqueue(sessionFileName, elapsedSec(), sessionStartWallMs);
        }
        if (listener != null) {
            final boolean r = recording;
            main.post(() -> listener.onPenRecordStatus(r));
        }
    }

    // cmd=10 录音时长
    private void handleRecordTime(JSONObject data) {
        if (data == null || !sessionActive) return;
        int sec = 0;
        try { sec = (int) Double.parseDouble(String.valueOf(data.opt("recordTime"))); } catch (Exception ignore) {}
        if (sec > 0) {
            lastPenDurationSec = sec;
            if (healthWatchdogArmed) { healthWatchdogArmed = false; main.removeCallbacks(recordingHealthWatchdog); }
            final int s = sec;
            if (listener != null) main.post(() -> listener.onPenRecordDuration(s));
        }
    }

    // ============ 文件列表（cmd=4 分批攒收） ============

    private void requestFileListInternal() {
        fileListCollecting = true;
        synchronized (fileListBuf) { fileListBuf.clear(); }
        try { PNote.getRecordFileList(); }
        catch (Throwable e) { Log.e(TAG, "getRecordFileList failed", e); fileListCollecting = false; }
    }

    private void handleFileList(JSONObject map) {
        JSONArray arr = map.optJSONArray("data");
        if (arr != null) {
            synchronized (fileListBuf) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    PenFileEntry e = new PenFileEntry();
                    e.name = String.valueOf(o.opt("name"));
                    if (TextUtils.isEmpty(e.name) || "null".equals(e.name)) continue;
                    try { e.size = (long) Double.parseDouble(String.valueOf(o.opt("size"))); } catch (Exception ex) { e.size = 0; }
                    try { e.timeSec = (int) Double.parseDouble(String.valueOf(o.opt("time"))); } catch (Exception ex) { e.timeSec = 0; }
                    fileListBuf.add(e);
                }
            }
        }
        // finish=1 → 立即收口；没带 finish（有的固件不发/放别处）→ 2.5s 没新批就兜底收口，绝不死等
        String fin = String.valueOf(map.opt("finish"));
        main.removeCallbacks(fileListDebounce);
        if ("1".equals(fin)) {
            finalizeFileList();
        } else {
            main.postDelayed(fileListDebounce, 2500);
        }
    }

    private final Runnable fileListDebounce = this::finalizeFileList;

    private void finalizeFileList() {
        if (!fileListCollecting) return;
        fileListCollecting = false;
        main.removeCallbacks(fileListDebounce);
        List<PenFileEntry> snapshot;
        synchronized (fileListBuf) { snapshot = new ArrayList<>(fileListBuf); fileListBuf.clear(); }
        onFileListComplete(snapshot);
    }

    private void onFileListComplete(List<PenFileEntry> files) {
        if (pendingCleanup) { pendingCleanup = false; cleanupOldFiles(files); }
        if (pendingSyncList) { pendingSyncList = false; deliverPenFileList(files); }
        if (autoRetryScan) { autoRetryScan = false; enqueueUnuploadedForRetry(files); }
        final UploadTask task = currentTask;
        if (!waitingForFile || task == null) return;
        PenFileEntry target = null;
        for (PenFileEntry f : files) {
            if (f != null && task.fileName.equals(f.name)) { target = f; break; }
        }
        if (target == null) {
            if (task.firstSeenMs == 0) task.firstSeenMs = System.currentTimeMillis();
            if (++fileListAttempts < MAX_FILELIST_ATTEMPTS) {
                StringBuilder sb = new StringBuilder();
                for (PenFileEntry f : files) if (f != null) sb.append(f.name).append(' ');
                Log.w(TAG, "后台：未定位文件(第" + fileListAttempts + "次) 找=" + task.fileName + " 笔列表[" + files.size() + "]=" + sb);
                main.postDelayed(() -> { if (waitingForFile) requestFileListInternal(); }, 2000);
            } else {
                // 整份列表(非空)收齐仍没这个名字、且已连试好几轮 → 文件不在笔上(疑时钟偏移命名不符)，
                // 直接放弃别再死等 2h；workerTaskFailed(drop) 会取消占位 + persist 移除，重启不再复活。
                boolean penGaveRealList = files != null && !files.isEmpty();
                if (penGaveRealList && task.downloadRequeues >= ABSENT_FROM_LIST_GIVEUP) {
                    penLog("★文件名不在笔列表(疑时钟偏移),放弃补传并取消占位,可从陪伴笔同步重导 " + task.fileName);
                    workerTaskFailed(task, "文件不在笔列表(疑时钟偏移)", true);
                } else {
                    requeueDownloadTask(task, "未在笔列表找到");
                }
            }
            return;
        }
        waitingForFile = false;
        if (task.durSec <= 0 && target.timeSec > 0) task.durSec = target.timeSec;
        Log.d(TAG, "后台：定位到文件 " + target.name + " size=" + target.size);
        beginFileDownload(task, target.size);
    }

    // ============ 文件下载（断点续传） ============

    private File partFileFor(String penFileName) {
        File dir = new File(appCtx.getCacheDir(), "pen_dl");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, stripExt(penFileName) + ".part");
    }

    private void beginFileDownload(UploadTask task, long expectedSize) {
        File part = partFileFor(task.fileName);
        long offset = part.exists() ? part.length() : 0;
        // 对齐到 40 字节帧边界，防断点接半帧
        long aligned = (offset / 40) * 40;
        if (aligned != offset) {
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(part, "rw")) { raf.setLength(aligned); }
            catch (Exception e) { aligned = 0; try { part.delete(); } catch (Exception ignore) {} }
            offset = aligned;
        }
        if (expectedSize > 0 && offset >= expectedSize) {
            // 上次其实已收完只差收尾 → 直接进入上传
            Log.d(TAG, "部分文件已完整 offset=" + offset + " expected=" + expectedSize + " → 直接上传");
            inflightTask = task;
            processAndUploadDownloaded(task, part);
            return;
        }
        DownloadState dl = new DownloadState(task, part, offset, expectedSize);
        try {
            dl.out = new java.io.BufferedOutputStream(new java.io.FileOutputStream(part, true));
        } catch (Exception e) {
            requeueDownloadTask(task, "本地文件打开失败");
            return;
        }
        dlState = dl;
        lastDlProgressMs = System.currentTimeMillis();
        main.removeCallbacks(downloadStallWatch);
        main.postDelayed(downloadStallWatch, 10000);
        penLog("★开始下载 " + task.fileName + " offset=" + offset + "/" + expectedSize);
        try { PNote.startGetFile(task.fileName, (int) offset); }
        catch (Throwable e) {
            closeDownload(dl); dlState = null;
            requeueDownloadTask(task, "startGetFile失败:" + e.getMessage());
        }
    }

    /** 文件数据回调（SDK 线程直入）：顺序追加到部分文件。 */
    @Override
    public void onDeviceFileData(byte[] data) {
        if (data == null || data.length == 0) return;
        lastRxMs = SystemClock.elapsedRealtime();
        DownloadState dl = dlState;
        if (dl == null || dl.out == null) return;
        try {
            dl.out.write(data);
            dl.written += data.length;
            lastDlProgressMs = System.currentTimeMillis();
            if (dl.expectedSize > 0 && listener != null) {
                final int pct = (int) Math.min(99, (dl.baseOffset + dl.written) * 100 / dl.expectedSize);
                main.post(() -> listener.onPenProgress(pct));
            }
        } catch (Exception e) {
            Log.e(TAG, "写部分文件失败", e);
        }
    }

    // cmd=5 传输状态
    private void handleTransferState(JSONObject data) {
        if (data == null) return;
        String st = String.valueOf(data.opt("record_file_state"));
        DownloadState dl = dlState;
        if (dl == null) return;
        switch (st) {
            case "4":   // 传输中
                lastDlProgressMs = System.currentTimeMillis();
                break;
            case "0": { // 传输完成
                penLog("★下载完成 " + dl.task.fileName + " got=" + (dl.baseOffset + dl.written) + "/" + dl.expectedSize);
                closeDownload(dl);
                dlState = null;
                lastDlProgressMs = 0; main.removeCallbacks(downloadStallWatch);
                long got = dl.partFile.length();
                if (dl.expectedSize > 0 && got < dl.expectedSize - 40) {
                    // 笔说完成但字节不够 → 当失败重试（按 offset 续传）
                    requeueDownloadTask(dl.task, "字节不足 " + got + "/" + dl.expectedSize);
                    return;
                }
                if (listener != null) main.post(() -> listener.onPenProgress(100));
                inflightTask = dl.task;
                processAndUploadDownloaded(dl.task, dl.partFile);
                break;
            }
            case "1":   // 文件不存在
                closeDownload(dl); dlState = null;
                lastDlProgressMs = 0; main.removeCallbacks(downloadStallWatch);
                try { dl.partFile.delete(); } catch (Exception ignore) {}
                requeueDownloadTask(dl.task, "笔上无此文件");
                break;
            case "2":   // offset 过大 → 从头来
                closeDownload(dl); dlState = null;
                lastDlProgressMs = 0; main.removeCallbacks(downloadStallWatch);
                try { dl.partFile.delete(); } catch (Exception ignore) {}
                requeueDownloadTask(dl.task, "offset过大,重头下载");
                break;
            case "3":   // 其他停止
            default:
                closeDownload(dl); dlState = null;
                lastDlProgressMs = 0; main.removeCallbacks(downloadStallWatch);
                requeueDownloadTask(dl.task, "传输中止(" + st + ")");
                break;
        }
    }

    private void closeDownload(DownloadState dl) {
        if (dl == null) return;
        try { if (dl.out != null) { dl.out.flush(); dl.out.close(); } } catch (Exception ignore) {}
        dl.out = null;
    }

    private final Runnable downloadStallWatch = new Runnable() {
        @Override public void run() {
            DownloadState dl = dlState;
            if (dl != null && lastDlProgressMs > 0
                    && System.currentTimeMillis() - lastDlProgressMs > DL_STALL_MS) {
                Log.w(TAG, "下载卡死>" + (DL_STALL_MS / 1000) + "s 无进度，取消重试 file=" + dl.task.fileName);
                penLog("★下载卡死>" + (DL_STALL_MS / 1000) + "s无进度 取消重试 " + dl.task.fileName);
                try { PNote.stopGetFile(dl.task.fileName); } catch (Throwable ignore) {}
                closeDownload(dl); dlState = null;
                requeueDownloadTask(dl.task, "下载卡死");
                return;
            }
            if (lastDlProgressMs > 0) main.postDelayed(this, 5000);
        }
    };

    // ============ 上传 ============

    /** 下载完成的部分文件（裸帧）→ 打包 ogg → 上传。 */
    private void processAndUploadDownloaded(final UploadTask task, final File rawFile) {
        worker.submit(() -> uploadRawAsOgg(task, rawFile.getAbsolutePath(), "补下载"));
    }

    /** 直传本地拼好的实时流裸帧。 */
    private void uploadLocalRaw(final UploadTask task) {
        worker.submit(() -> uploadRawAsOgg(task, task.localRawPath, "直传实时流"));
    }

    /** 公共上传路径：裸帧文件 → OggOpusWriter 打包 → Uploader 直传 .ogg。 */
    private void uploadRawAsOgg(final UploadTask task, final String rawPath, final String tag) {
        try {
            File raw = rawPath == null ? null : new File(rawPath);
            if (raw == null || !raw.exists() || raw.length() < 800) {
                workerTaskFailed(task, tag + ":裸帧文件无效", true);
                return;
            }
            int durSec = task.durSec > 0 ? task.durSec : OggOpusWriter.rawBytesToSeconds(raw.length());
            String base = stripExt(baseName(rawPath));
            File oggDir = new File(appCtx.getCacheDir(), "stream_ops");
            if (!oggDir.exists()) oggDir.mkdirs();
            String oggPath = new File(oggDir, base + ".ogg").getAbsolutePath();
            String ogg = OggOpusWriter.wrapFile(rawPath, oggPath);
            if (ogg == null || !new File(ogg).exists() || new File(ogg).length() < 64) {
                workerTaskFailed(task, tag + ":ogg打包失败", true);
                return;
            }
            File oggFile = new File(ogg);
            String name = base + ".ogg";
            long t0 = SystemClock.elapsedRealtime();
            String penFile = looksLikePenFile(task.fileName) ? task.fileName : null;
            // ★用【当前最新】上传上下文：任务可能在队列里躺了很久(被杀恢复/退避)，task 里冻结的 Cookie 可能早过期
            String liveCk = (cookie != null && !cookie.isEmpty()) ? cookie : task.cookie;
            String liveUrl = (uploadUrl != null && !uploadUrl.isEmpty()) ? uploadUrl : task.uploadUrl;
            // recorded_at：会话开始墙钟丢了(崩溃恢复/手动同步)就用文件名时间戳——连上必 syncTime，文件名可信
            long startWall = task.startWallMs > 0 ? task.startWallMs : parseFileTimestamp(task.fileName);
            Uploader.Result r = Uploader.upload(oggFile, durSec, liveCk, liveUrl,
                    name, "audio/ogg", task.sn, task.placeholderId, fmtWall(startWall), penFile);
            long ms = SystemClock.elapsedRealtime() - t0;
            writeProbeStatus("[" + tag + "ogg] " + name + " ogg=" + oggFile.length() + "B 上传" + ms + "ms ok=" + r.ok + " recId=" + r.recordingId + " err=" + r.error);
            if (r.ok) {
                try { raw.delete(); oggFile.delete(); } catch (Exception ignore) {}
                workerTaskDone(task, r.recordingId);
            } else if (r.error != null && r.error.contains("登录已失效")) {
                // ★401/403 绝不丢段(失败模式审计毒点#1)：留 raw+占位，请上层刷新 Cookie 后退避重试。
                //   音频已经拿到手了，丢了才是事故；登录态恢复后迟早传上。
                try { oggFile.delete(); } catch (Exception ignore) {}
                task.localRawPath = rawPath;
                penLog("★上传401(登录失效)→不丢段,留队退避重试,已请求刷新Cookie " + task.fileName);
                Runnable hook = onAuthExpired;
                if (hook != null) main.post(hook);
                requeueTransient(task);
            } else if (r.transientFail) {
                try { oggFile.delete(); } catch (Exception ignore) {}
                task.localRawPath = rawPath;   // 重试直接走本地直传，不再占蓝牙重下
                Log.w(TAG, tag + "上传临时失败(重试)：" + r.error);
                requeueTransient(task);
            } else {
                try { raw.delete(); oggFile.delete(); } catch (Exception ignore) {}
                Log.w(TAG, tag + "上传永久失败(放弃)：" + r.error);
                workerTaskFailed(task, "upload rejected", true);
            }
        } catch (Exception e) {
            Log.e(TAG, "uploadRawAsOgg 失败", e);
            requeueTransient(task);
        }
    }

    // ============ 后台 worker / 队列（v24 语义） ============

    private boolean shouldEnqueue(String fn) {
        if (fn == null || fn.isEmpty()) return true;
        if (uploadedFileNames.contains(fn)) return false;
        for (UploadTask t : uploadQueue) if (t != null && fn.equals(t.fileName)) return false;
        UploadTask c = currentTask, i = inflightTask;
        if (c != null && fn.equals(c.fileName)) return false;
        if (i != null && fn.equals(i.fileName)) return false;
        return true;
    }

    private boolean isDownloadQueued(String fn) {
        if (fn == null) return false;
        for (UploadTask t : uploadQueue) if (t != null && fn.equals(t.fileName)) return true;
        return false;
    }

    private long taskAgeMs(UploadTask t) {
        if (t == null) return -1;
        long ref = (t.firstSeenMs > 0) ? t.firstSeenMs : t.startWallMs;
        return ref > 0 ? (System.currentTimeMillis() - ref) : -1;
    }

    private boolean giveUpIfStale(UploadTask t) {
        if (t == null || t.localRawPath != null) return false;
        long age = taskAgeMs(t);
        if (age <= STALE_GIVEUP_MS) return false;
        Log.w(TAG, "补传放弃(僵尸·已挂" + (age / 60000) + "min·从未传成) file=" + t.fileName);
        penLog("★补传放弃(僵尸·挂了" + (age / 60000) + "分钟·从未传成)删占位,音频留笔上可日后重导 " + t.fileName);
        workerTaskFailed(t, "stale-giveup", true);
        return true;
    }

    /** ★v24:不只盯队头——本地直传最优先，再挑退避到期且笔空闲的下载任务，别被冷却中的队头僵尸堵死。 */
    private void kickWorker() {
        main.post(() -> {
            if (workerBusy) return;
            if (uploadQueue.isEmpty()) { notifyPending(); return; }
            final long now = System.currentTimeMillis();
            final boolean penBusy = (penRecording || sessionActive || appStartPending);
            UploadTask chosen = null;
            for (UploadTask t : uploadQueue) {
                if (t != null && t != inflightTask && t.localRawPath != null) { chosen = t; break; }
            }
            long earliestRetry = Long.MAX_VALUE;
            if (chosen == null && !penBusy && linkUp) {
                for (UploadTask t : uploadQueue) {
                    if (t == null || t == inflightTask || t.localRawPath != null) continue;
                    long wait = t.nextAttemptWallMs - now;
                    if (wait <= 0) { chosen = t; break; }
                    if (t.nextAttemptWallMs < earliestRetry) earliestRetry = t.nextAttemptWallMs;
                }
            }
            if (chosen == null) {
                if (!penBusy && earliestRetry != Long.MAX_VALUE) {
                    main.postDelayed(this::kickWorker, Math.min(Math.max(earliestRetry - now, 500L), 10 * 60 * 1000L));
                }
                notifyPending();
                return;
            }
            if (chosen.localRawPath == null && giveUpIfStale(chosen)) return;
            workerBusy = true; currentTask = chosen;
            notifyPending();
            if (chosen.localRawPath != null) {
                inflightTask = chosen;
                Log.d(TAG, "后台开始【直传实时流opus】 file=" + chosen.fileName + " 剩余=" + uploadQueue.size());
                uploadLocalRaw(chosen);
                return;
            }
            waitingForFile = true;
            fileListAttempts = 0;
            Log.d(TAG, "后台开始处理 file=" + chosen.fileName + " 剩余=" + uploadQueue.size());
            requestFileListInternal();
        });
    }

    /** 笔要开始录音时暂停后台下载（任务留队，录完再续）。 */
    private void pauseWorker() {
        if (inflightTask != null) return;
        DownloadState dl = dlState;
        if (dl != null) {
            try { PNote.stopGetFile(dl.task.fileName); } catch (Throwable ignore) {}
            closeDownload(dl);
            dlState = null;
            lastDlProgressMs = 0; main.removeCallbacks(downloadStallWatch);
        }
        if (workerBusy || waitingForFile) {
            Log.d(TAG, "暂停后台上传（笔要录音）");
            workerBusy = false; waitingForFile = false; currentTask = null;
            notifyPending();
        }
    }

    private void workerTaskDone(final UploadTask task, final long recId) {
        main.post(() -> {
            uploadQueue.remove(task);
            if (task.fileName != null) markUploaded(task.fileName);
            try { partFileFor(task.fileName).delete(); } catch (Exception ignore) {}
            workerBusy = false; currentTask = null; inflightTask = null; waitingForFile = false;
            Log.d(TAG, "后台完成 file=" + task.fileName + " recId=" + recId + " 剩余=" + uploadQueue.size());
            if (recId > 0 && listener != null) listener.onPenUploaded(recId);
            notifyPending();
            kickWorker();
        });
    }

    private void requeueTransient(final UploadTask task) {
        main.post(() -> {
            task.uploadAttempts++;
            workerBusy = false; currentTask = null; inflightTask = null; waitingForFile = false;
            if (uploadQueue.remove(task)) uploadQueue.add(task);
            long delay = Math.min(120000, 5000L * task.uploadAttempts);
            Log.w(TAG, "上传临时失败，第" + task.uploadAttempts + "次，" + (delay / 1000) + "s后重试 file=" + task.fileName);
            notifyPending();
            main.postDelayed(SoniPenController.this::kickWorker, delay);
        });
    }

    private void requeueDownloadTask(final UploadTask task, final String reason) {
        main.post(() -> {
            if (task == null) return;
            task.downloadRequeues++;
            if (task.firstSeenMs == 0) task.firstSeenMs = System.currentTimeMillis();
            lastDlProgressMs = 0; main.removeCallbacks(downloadStallWatch);
            long elapsed = System.currentTimeMillis() - task.firstSeenMs;
            if (elapsed > FAIL_GIVEUP_MS || task.downloadRequeues > MAX_DOWNLOAD_REQUEUES) {
                Log.w(TAG, "补传放弃(已试" + (elapsed / 60000) + "min/" + task.downloadRequeues + "次," + reason + ") file=" + task.fileName);
                penLog("★补传放弃(试了" + (elapsed / 60000) + "分钟·" + reason + ")删占位,音频留笔上可日后重导 " + task.fileName);
                workerTaskFailed(task, "放弃:" + reason, true);
                return;
            }
            if (uploadQueue.remove(task)) uploadQueue.add(task);
            workerBusy = false; currentTask = null; inflightTask = null; waitingForFile = false;
            long delay = Math.min(10 * 60 * 1000L, 8000L * task.downloadRequeues);
            task.nextAttemptWallMs = System.currentTimeMillis() + delay;
            Log.w(TAG, "下载补传暂未成(" + reason + ")，第" + task.downloadRequeues + "次，" + (delay / 1000) + "s后重试 file=" + task.fileName);
            penLog("★补传暂未成(" + reason + ") 第" + task.downloadRequeues + "次 " + (delay / 1000) + "s后重试 " + task.fileName);
            persistPendingQueue();
            notifyPending();
            main.postDelayed(SoniPenController.this::kickWorker, delay);
        });
    }

    private void workerTaskFailed(final UploadTask task, final String reason, final boolean drop) {
        main.post(() -> {
            if (drop) {
                uploadQueue.remove(task);
                // ★放弃必须落盘:否则 pending_dl 里还留着这条,下次启动 loadPendingQueue 又把它捞回来重试,
                //   给不掉的"幽灵"任务会让首页"N段后台同步中"永远清不掉(跨重启复活)。
                persistPendingQueue();
                if (task.placeholderId > 0) {
                    final long pid = task.placeholderId;
                    final String phCancelUrl = placeholderUrlFrom(task.uploadUrl) + "/cancel";
                    final String ck = task.cookie;
                    worker.submit(() -> {
                        Uploader.cancelPlaceholder(ck, phCancelUrl, pid);
                        if (listener != null) main.post(listener::onPenPlaceholderCreated);
                    });
                }
                if (task.appInitiated) {
                    failedCount++;
                    Log.w(TAG, "后台放弃(你发起的) file=" + task.fileName + " 原因=" + reason + " 失败累计=" + failedCount);
                } else {
                    Log.d(TAG, "后台静默丢弃(笔自发空录) file=" + task.fileName + " 原因=" + reason);
                }
            } else {
                Log.w(TAG, "后台暂挂 file=" + task.fileName + " 原因=" + reason + "（留队稍后续）");
            }
            workerBusy = false; currentTask = null; inflightTask = null; waitingForFile = false;
            notifyPending();
            main.postDelayed(SoniPenController.this::kickWorker, 2500);
        });
    }

    /** ★v24:「取消上传」→ 按 placeholderId 把任务从补传队列移除（录音仍在笔里，可日后重新取回）。 */
    public void cancelUpload(String placeholderIdStr) {
        main.post(() -> {
            long pid;
            try { pid = Long.parseLong(placeholderIdStr == null ? "" : placeholderIdStr.trim()); }
            catch (Exception e) { return; }
            if (pid <= 0) return;
            UploadTask target = null;
            for (UploadTask t : uploadQueue) if (t != null && t.placeholderId == pid) { target = t; break; }
            if (target == null) return;
            if (target == currentTask || target == inflightTask) {
                workerBusy = false; currentTask = null; inflightTask = null; waitingForFile = false;
                main.removeCallbacks(downloadStallWatch);
                DownloadState dl = dlState;
                if (dl != null && dl.task == target) {
                    try { PNote.stopGetFile(target.fileName); } catch (Throwable ignore) {}
                    closeDownload(dl);
                    dlState = null;
                }
            }
            uploadQueue.remove(target);
            persistPendingQueue();
            Log.d(TAG, "用户取消上传 placeholderId=" + pid + " file=" + target.fileName);
            penLog("★取消上传(用户) placeholderId=" + pid + " " + target.fileName);
            notifyPending();
            kickWorker();
        });
    }

    public void retryPenUploads() {
        main.post(() -> {
            // ① 清"没保存成功"计数：这次重新尝试，badge 即时更新（成不成由后续上传决定）。
            failedCount = 0;
            // ② 队列里还在的任务：清退避，立刻重推。
            materializeRestored();
            for (UploadTask t : uploadQueue) if (t != null) { t.downloadRequeues = 0; t.nextAttemptWallMs = 0; }
            Log.d(TAG, "手动重试待补传 队列=" + uploadQueue.size());
            penLog("★手动重试待补传 队列=" + uploadQueue.size());
            // ③ 关键：失败的段多半【已从队列丢了】(给不掉的会drop)、且【音频还在笔上】→
            //    重扫笔机身列表，把"近6小时内、还没传上来"的段重新入队补传。这才是"点了重试真的开始同步"。
            if (linkUp && cookie != null && uploadUrl != null) {
                autoRetryScan = true;
                requestFileListInternal();
            }
            notifyPending();   // 立刻把 failedCount=0 推给 UI
            kickWorker();
        });
    }

    /** 重试时重扫到的笔列表：把近 6 小时内、还没传上来、也不在队里的段重新入队补传（音频在笔上）。 */
    private void enqueueUnuploadedForRetry(List<PenFileEntry> files) {
        if (files == null || cookie == null || uploadUrl == null) return;
        List<UploadTask> added = new ArrayList<>();
        for (PenFileEntry f : files) {
            if (f == null || TextUtils.isEmpty(f.name)) continue;
            if (uploadedFileNames.contains(f.name)) continue;   // 已传过
            if (isQueuedByName(f.name)) continue;               // 已在队
            if (isStaleRecordingFile(f.name)) continue;         // 超6小时的老段不自动抓(避免重导一堆历史)
            long startMs = parseFileTimestamp(f.name);
            UploadTask t = new UploadTask(f.name, cookie, uploadUrl, penSn(), f.timeSec, startMs, true);
            t.firstSeenMs = System.currentTimeMillis();
            uploadQueue.add(t);
            added.add(t);
            penLog("★重试:重扫到未传段,入队补传 " + f.name);
        }
        if (!added.isEmpty()) {
            final String phUrl = placeholderUrlFrom(uploadUrl);
            for (final UploadTask t : added) {
                worker.submit(() -> {
                    long pid = Uploader.createPlaceholder(t.cookie, phUrl, t.startWallMs);
                    if (pid > 0) { t.placeholderId = pid; if (listener != null) main.post(listener::onPenPlaceholderCreated); }
                });
            }
            notifyPending();
            main.postDelayed(this::kickWorker, 800);
        } else {
            penLog("★重试:笔上没有近6小时未传的段(可能已全传上或音频不在笔上)");
        }
    }

    private void notifyPending() {
        persistPendingQueue();
        final int n = uploadQueue.size();
        final int f = failedCount;
        if (listener != null) main.post(() -> listener.onPenPendingChanged(n, f));
    }

    // ============ 队列持久化 ============

    private void loadUploadedNames() {
        try {
            java.util.Set<String> s = appCtx.getSharedPreferences("pen_prefs", Context.MODE_PRIVATE)
                    .getStringSet("uploaded_files", null);
            if (s != null) uploadedFileNames.addAll(s);
        } catch (Exception ignored) {}
    }

    private void markUploaded(String fn) {
        if (fn == null || fn.isEmpty()) return;
        uploadedFileNames.add(fn);
        try {
            java.util.Set<String> snap;
            synchronized (uploadedFileNames) { snap = new java.util.HashSet<>(uploadedFileNames); }
            appCtx.getSharedPreferences("pen_prefs", Context.MODE_PRIVATE)
                    .edit().putStringSet("uploaded_files", snap).apply();
        } catch (Exception ignored) {}
    }

    /** 存盘：下载补传任务 + 真实笔文件名的直传任务(被杀后降级为下载补传，不丢段)。 */
    private void persistPendingQueue() {
        try {
            JSONArray arr = new JSONArray();
            for (UploadTask t : uploadQueue) {
                if (t == null || t.fileName == null) continue;
                if (t.localRawPath != null && !looksLikePenFile(t.fileName)) continue;   // 合成名(stream_*)没法补下载，不存
                JSONObject o = new JSONObject();
                o.put("fn", t.fileName); o.put("dur", t.durSec); o.put("sw", t.startWallMs);
                o.put("pid", t.placeholderId); o.put("ai", t.appInitiated); o.put("fs", t.firstSeenMs);
                o.put("rq", t.downloadRequeues); o.put("na", t.nextAttemptWallMs);
                arr.put(o);
            }
            appCtx.getSharedPreferences("pen_prefs", Context.MODE_PRIVATE)
                    .edit().putString("pending_dl", arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void loadPendingQueue() {
        try {
            String s = appCtx.getSharedPreferences("pen_prefs", Context.MODE_PRIVATE)
                    .getString("pending_dl", null);
            if (s == null || s.isEmpty()) return;
            JSONArray arr = new JSONArray(s);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String fn = o.optString("fn", "");
                if (fn.isEmpty() || uploadedFileNames.contains(fn)) continue;
                pendingRestore.add(new PersistedDl(fn, o.optInt("dur", 0),
                        o.optLong("sw", 0), o.optLong("pid", -1), o.optBoolean("ai", false), o.optLong("fs", 0),
                        o.optInt("rq", 0), o.optLong("na", 0)));
            }
            if (!pendingRestore.isEmpty()) {
                Log.d(TAG, "读到 " + pendingRestore.size() + " 条持久化待补传任务，待上下文就绪续传");
                penLog("★读到 " + pendingRestore.size() + " 条持久化待补传(等登录态续传)");
            }
        } catch (Exception ignored) {}
    }

    private void materializeRestored() {
        if (cookie == null || uploadUrl == null || pendingRestore.isEmpty()) return;
        List<PersistedDl> snap;
        synchronized (pendingRestore) { snap = new ArrayList<>(pendingRestore); pendingRestore.clear(); }
        int n = 0;
        for (PersistedDl p : snap) {
            if (p == null || p.fileName == null) continue;
            if (uploadedFileNames.contains(p.fileName) || isDownloadQueued(p.fileName)) continue;
            UploadTask t = new UploadTask(p.fileName, cookie, uploadUrl, penSn(),
                    p.durSec, p.startWallMs, p.appInitiated);
            t.placeholderId = p.placeholderId;
            t.firstSeenMs = p.firstSeenMs;
            t.downloadRequeues = p.downloadRequeues;
            t.nextAttemptWallMs = p.nextAttemptMs;
            if (giveUpIfStale(t)) { n++; continue; }
            uploadQueue.add(t);
            n++;
        }
        if (n > 0) {
            Log.d(TAG, "续传 重建 " + n + " 条待补传任务入队");
            penLog("★续传:重建待补传入队(App被杀后恢复) 队列=" + uploadQueue.size());
            persistPendingQueue();
            notifyPending();
            main.postDelayed(this::kickWorker, 1500);
        }
    }

    // ============ 手动"从陪伴笔同步" + 清理 ============

    public void requestPenFileList() {
        if (!linkUp) { if (listener != null) main.post(() -> listener.onPenFileList("[]")); return; }
        pendingSyncList = true;
        requestFileListInternal();
    }

    private void deliverPenFileList(List<PenFileEntry> files) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        if (files != null) {
            for (PenFileEntry f : files) {
                if (f == null || TextUtils.isEmpty(f.name)) continue;
                long startMs = parseFileTimestamp(f.name);
                String ra = startMs > 0 ? fmtWall(startMs) : "";
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"name\":\"").append(jsEsc(f.name))
                  .append("\",\"ra\":\"").append(jsEsc(ra == null ? "" : ra))
                  .append("\",\"size\":").append(f.size)
                  .append(",\"dur\":").append(f.timeSec)
                  .append(",\"uploaded\":").append(uploadedFileNames.contains(f.name)).append('}');
            }
        }
        sb.append("]");
        final String json = sb.toString();
        if (listener != null) main.post(() -> listener.onPenFileList(json));
    }

    public void uploadPenFiles(String namesJson) {
        if (cookie == null || uploadUrl == null) return;
        List<String> names = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(namesJson);
            for (int i = 0; i < arr.length(); i++) {
                String n = arr.optString(i, null);
                if (n != null && !n.isEmpty()) names.add(n);
            }
        } catch (Exception e) { Log.w(TAG, "uploadPenFiles parse: " + e.getMessage()); return; }
        List<UploadTask> added = new ArrayList<>();
        for (String name : names) {
            if (isQueuedByName(name)) continue;
            long startMs = parseFileTimestamp(name);
            UploadTask t = new UploadTask(name, cookie, uploadUrl, penSn(), 0, startMs, false);
            t.firstSeenMs = System.currentTimeMillis();   // 手动重导=新任务，从现在重新计 2h
            uploadQueue.add(t);
            added.add(t);
            penLog("★手动同步入队 " + name);
        }
        if (!added.isEmpty()) {
            final String phUrl = placeholderUrlFrom(uploadUrl);
            for (final UploadTask t : added) {
                worker.submit(() -> {
                    long pid = Uploader.createPlaceholder(t.cookie, phUrl, t.startWallMs);
                    if (pid > 0) { t.placeholderId = pid; if (listener != null) main.post(listener::onPenPlaceholderCreated); }
                });
            }
            notifyPending();
            main.postDelayed(this::kickWorker, 1200);
        }
    }

    private boolean isQueuedByName(String name) {
        if (name == null) return false;
        UploadTask c = currentTask, inf = inflightTask;
        if (c != null && name.equals(c.fileName)) return true;
        if (inf != null && name.equals(inf.fileName)) return true;
        for (UploadTask t : uploadQueue) if (t != null && name.equals(t.fileName)) return true;
        return false;
    }

    private void triggerCleanup() {
        if (!linkUp || penRecording || sessionActive || appStartPending || workerBusy) return;
        pendingCleanup = true;
        requestFileListInternal();
    }

    private void cleanupOldFiles(List<PenFileEntry> files) {
        if (files == null) return;
        long now = System.currentTimeMillis();
        int deleted = 0;
        for (PenFileEntry f : files) {
            if (f == null || TextUtils.isEmpty(f.name)) continue;
            long ts = parseFileTimestamp(f.name);
            if (ts > 0 && now - ts > FILE_KEEP_MS) {
                try {
                    PNote.deleteReordFile(f.name);
                    deleted++;
                    Log.d(TAG, "清理笔上 >30天 旧文件 " + f.name);
                } catch (Throwable e) { Log.e(TAG, "deleteReordFile failed " + f.name, e); }
            }
        }
        if (deleted > 0) Log.d(TAG, "本次共清理 " + deleted + " 个超期文件");
    }

    // ============ SN 归属校验（fail-open，同杰理版） ============

    private static String reportSnUrlFrom(String uploadUrl) {
        if (uploadUrl == null) return null;
        if (uploadUrl.endsWith("/upload")) return uploadUrl.substring(0, uploadUrl.length() - 7) + "/pen/report-sn";
        return uploadUrl.replace("/upload", "/pen/report-sn");
    }

    private void verifyPenAllowed() {
        final int gen = ++snVerifyGen;
        final String url = reportSnUrlFrom(uploadUrl);
        final String ck = cookie;
        final String mac = currentMac;
        if (url == null || ck == null) return;
        verifyPenSnStep(gen, mac, url, ck, 0);
    }

    private void verifyPenSnStep(final int gen, final String mac, final String url, final String ck, final int attempt) {
        if (gen != snVerifyGen) return;
        String sn = penSn();
        if (sn == null || sn.isEmpty()) {
            if (attempt < 12) main.postDelayed(() -> verifyPenSnStep(gen, mac, url, ck, attempt + 1), 400);
            return;
        }
        final String fsn = sn;
        worker.submit(() -> {
            final Uploader.SnVerdict v = Uploader.reportSn(ck, url, fsn);
            main.post(() -> {
                if (gen != snVerifyGen) return;
                if (v == null) { penAllowed = true; return; }
                if (v.allow) {
                    penAllowed = true; penDenyMsg = null;
                    if (mac != null && mac.equals(penDeniedMac)) penDeniedMac = null;
                    penLog("SN校验通过 sn=" + fsn);
                } else {
                    penAllowed = false; penDenyMsg = v.message; penDeniedMac = mac;
                    if (mac != null && mac.equals(lastConnectedMac)) lastConnectedMac = null;
                    penLog("★SN校验拒绝→断开 " + v.message);
                    String msg = (v.message == null || v.message.isEmpty()) ? "这台录音笔不是你的，请连你自己的录音笔" : v.message;
                    post(PhoneMicService.STATE_ERROR, msg, 0, -1);
                    if (btReady()) { try { PNote.closeConnect(); } catch (Throwable ignore) {} } else penLog("跳过closeConnect:蓝牙未就绪");
                }
            });
        });
    }

    /** 当前连接笔的 SN（cmd=7 缓存）。读不到返回空串。 */
    private String penSn() { return penSn == null ? "" : penSn; }

    /** 当前电量（cmd=6 缓存；"110"=充电中；空=未知）。 */
    public String batteryPercent() { return batteryPct; }

    /** 解析当前缓存电量 → 推给首页显示（percent 0–100；声云充电时 cbc 形如 1xx，百位=充电标记）。 */
    private void notifyBatteryToUi() {
        if (listener == null) return;
        try {
            String b = batteryPct;
            if (b == null) return;
            b = b.trim();
            if (b.isEmpty() || "null".equals(b)) return;
            int lvl = Integer.parseInt(b);
            boolean charging = lvl > 100;
            int pct = charging ? lvl - 100 : lvl;
            if (pct < 0) pct = 0;
            if (pct > 100) pct = 100;
            final int fp = pct; final boolean fc = charging;
            main.post(() -> { if (listener != null) listener.onPenBattery(fp, fc); });
        } catch (Exception ignore) {}
    }

    /** 解析当前缓存电量 → 交给 ReminderNotifier 判低电发通知（<10%/<5%，去重）。 */
    private void notifyBatteryIfLow() {
        try {
            String b = batteryPct;
            if (b == null) return;
            b = b.trim();
            if (b.isEmpty() || "null".equals(b)) return;
            int lvl = Integer.parseInt(b);
            boolean charging = lvl > 100;   // 声云 "110" = 充电中
            com.airec.bledemo.notify.ReminderNotifier.INSTANCE.onPenBattery(lvl, charging);
        } catch (Exception ignore) {}
    }

    // ============ 工具 ============

    private static String placeholderUrlFrom(String uploadUrl) {
        if (uploadUrl == null) return null;
        if (uploadUrl.endsWith("/upload")) return uploadUrl.substring(0, uploadUrl.length() - 7) + "/placeholder";
        return uploadUrl.replace("/upload", "/placeholder");
    }

    /** 声云文件名形如 note20251203-123344.opus / call...：能解析出 14 位时间戳即认为是真实笔文件。 */
    private static boolean looksLikePenFile(String name) {
        return parseFileTimestamp(name) > 0;
    }

    private boolean isStaleRecordingFile(String fileName) {
        long ts = parseFileTimestamp(fileName);
        return ts > 0 && (System.currentTimeMillis() - ts) > 6 * 3600 * 1000L;
    }

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

    private static String fmtWall(long ms) {
        if (ms <= 0) return null;
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(new java.util.Date(ms));
    }

    private int elapsedSec() {
        if (startElapsedMs == 0) return 0;
        return (int) Math.max(0, (SystemClock.elapsedRealtime() - startElapsedMs) / 1000);
    }

    private static String jsEsc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
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
