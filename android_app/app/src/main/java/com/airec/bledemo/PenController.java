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
import com.airec.bledemo.recording.PenKeepAliveService;
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
        /** 手动"从录音笔同步"：拉到机身文件列表(JSON 数组)，交给网页渲染预览勾选。 */
        void onPenFileList(String filesJson);
        /** #4 连上空闲检测到笔上有 N 段"未传"录音(已传/已删不算) → 提示顾问去「取回小伙伴」手动导入。0=隐藏提示。 */
        void onPenUnsynced(int count);
        /** 检测到笔"开机自动录制"开着并已自动关闭 → 弹框提示顾问把笔关机重开(让设置生效、停掉本次开机自录)。 */
        void onPenPowerOnRecordDisabled();
        /** 之前提示过重启、本次连接读到"开机自动录制=关" → 用户已把笔关机重开生效 → 给个正反馈、消除焦虑。 */
        void onPenPowerOnRecordConfirmedOff();
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
    // ★自动重连：连上后开启；断开 / 蓝牙恢复 → 自动扫同一支笔(按MAC)连回来，带退避
    private volatile boolean autoReconnectOn = false;
    private volatile int reconnectAttempts = 0;
    private android.content.BroadcastReceiver btStateReceiver = null;
    private volatile long lastReflushMs = 0;           // 上次自动重连刷新的时刻(限频)
    private volatile boolean penSettingsWritten = false;

    // ★真连接判定 + 在线活性：不信 isConnected()(只看GATT指针) 也不无条件信 SDK 的 onConnected(有3秒假兜底)。
    //   只认"最近收到过笔的真实业务回包"才算真在线。
    private volatile boolean verifiedConnected = false;   // App 层"已验证在线"
    private volatile long    lastRxMs = 0;                // 最近一次收到笔真实回包(elapsedRealtime)
    private volatile int     hbMissed = 0;                // 心跳连续未回次数
    // ★诊断用:笔电量% 和 蓝牙信号RSSI(dBm)。空闲心跳每8s fetchDeviceInfo→onDeviceInfoUpdated 刷新。
    //   排查"蓝牙老断"时直接看信号弱不弱、电量低不低,不用靠猜(刘爽案例)。
    private volatile int     lastBattery = -1;            // 笔电量%(-1=未知)
    private volatile int     lastRssi = 0;                // 蓝牙RSSI dBm(0=未知,正常为负值如-60)
    private volatile int     lastLoggedBattery = -999;    // penlog 节流:上次记录的电量
    private volatile int     lastLoggedRssi = 0;          // penlog 节流:上次记录的RSSI
    private volatile long    lastDevInfoLogMs = 0;        // penlog 节流:上次记录设备信息的时刻
    private static final long HANDSHAKE_TIMEOUT_MS = 5000; // 连上后等"真回包"的握手超时
    private static final long STALE_RX_MS = 12000;        // 超过这么久没回包 → 视为不在线
    private static final long HB_INTERVAL_MS = 8000;      // 心跳间隔(空闲)
    private static final long HB_INTERVAL_BUSY_MS = 15000; // 录音/下载时放宽

    // ★录音笔SN绑定校验(Phase2)：连上验证后读SN问后端准不准用。默认 true=fail-open(网络/读不到不挡录音)。
    private volatile boolean penAllowed = true;       // 当前连接是否准许用(录音前提)
    private volatile String  penDeniedMac = null;     // 被拒的MAC：不自动重连它(防 deny→重连→deny 死循环)
    private volatile String  penDenyMsg = null;       // 拒绝文案(录音被挡时提示)
    private volatile String  currentMac = null;       // 当前连接的MAC
    private volatile int     snVerifyGen = 0;         // 校验代号：换连接即作废挂起的校验

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
    private volatile boolean sessionStreamComplete = false;  // 本段流是否完整(蓝牙全程没断 + 没中途卡死)
    private volatile long sessionStreamBytes = 0;
    private volatile int  sessionStreamFrames = 0;
    // ★录音中卡流看门狗：实时流可能"连着但没数据"(GATT通知静默停)——onDisconnected 不触发、流就这么断了。
    //   记最后一帧时刻，>STREAM_STALL_MS 没新帧 → 判本段流不完整 → 收尾回落"从笔机身补下载"，绝不直传截断件。
    private volatile long lastStreamFrameMs = 0;
    private static final long STREAM_STALL_MS = 6000;
    // ★保活：录音笔录音整段起前台 Service + 唤醒锁，防 App 被切后台/锁屏挂起→BLE 回调停→流卡死。
    private volatile boolean keepAliveOn = false;

    private static final long CONFIRM_TIMEOUT_MS = 5000; // 发开始命令后等笔确认的超时（超时判定休眠/关机）

    // ★开录健康看门狗（A）：你点开始、笔回了"已开录"后，限时内必须出现"真音频证据"
    //   （实时流来过帧 或 笔上报已录时长>0）。两者都没有 = 笔应了开录但没真录（设备时间停在0、
    //   保存时笔上找不到文件 → "未成功保存"）。这时立刻停+明确报错，不再墙钟空跑到结束才报。
    //   一旦出现任一证据即取消，绝不打扰正常录音；只作用于"你主动点开始"的段（笔自发声控录音不干预）。
    private static final long REC_HEALTH_GRACE_MS = 9000;
    private volatile int lastPenDurationSec = 0;        // 笔最近上报的已录时长（>0 即视为真在录）
    private volatile boolean healthWatchdogArmed = false; // 看门狗待命中（避免每帧都扫消息队列）

    // ★断线宽限（B）：录音中蓝牙断开后，保住"重连中·录音继续"显示这么久；超时仍没接回一段真在录的
    //   会话 → 老实结束本段（只到断开前），停掉网页墙钟空跑，避免"显示9分钟、实际只录35秒"。
    //   断开后的音频本就丢了，老实报错不会多丢；断开前那段由 pendingRecovery 重连后补传，不丢。
    private static final long RECONNECT_RECORDING_GRACE_MS = 25000;

    // ★后台上传队列
    private static final class UploadTask {
        final String fileName, cookie, uploadUrl, sn;
        final int durSec;
        final long startWallMs;
        final boolean appInitiated;   // 你主动点开始的？失败时只对这种大声提示
        volatile long placeholderId = -1;  // 已建的"占位片段"后端记录 id；上传时回填它(不新建)。-1=未建/降级
        volatile long firstAttemptMs = 0;  // 首次尝试定位文件的时刻；笔提交延迟时据此判断挂多久才放弃
        volatile String localOpsPath = null;  // ★非空=直传这个本地拼好的 .ops(实时流完整，不下载不解码)
        volatile int uploadAttempts = 0;      // 上传临时失败(网络抖)的重试次数，退避用
        volatile int downloadRequeues = 0;    // ★A1:下载补传失败/卡死被挪队尾重试的次数(退避用，不再硬放弃)
        volatile long firstSeenMs = 0;        // ★A1:任务入队(初次出现)的墙钟时刻(构造即打戳、持久化)→按"已挂多久"封顶放弃；手动重新同步=新任务从这里重新计时
        volatile long nextAttemptWallMs = 0;  // ★A1:下次允许发起下载的墙钟时刻(退避到期，持久化)→App重启也尊重冷却，不一上来就秒重试敲爆BLE
        UploadTask(String fileName, String cookie, String uploadUrl, String sn, int durSec, long startWallMs, boolean appInitiated) {
            this.fileName = fileName; this.cookie = cookie; this.uploadUrl = uploadUrl;
            this.sn = sn; this.durSec = durSec; this.startWallMs = startWallMs; this.appInitiated = appInitiated;
            this.firstSeenMs = System.currentTimeMillis();   // ★A1:入队即打戳→封顶放弃从"这次入队"算起(手动重新同步旧文件=重新计时2h，不被旧录音时间误删)
        }
    }
    private final ConcurrentLinkedQueue<UploadTask> uploadQueue = new ConcurrentLinkedQueue<>();
    // ★已成功上传的笔文件名：防同一段重复上传(如断线补传 + 停止收尾 为同一文件各入队一次)
    private final java.util.Set<String> uploadedFileNames = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
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
    private static final int MAX_DOWNLOAD_REQUEUES = 200;       // ★A1:次数兜底上限(墙钟封顶为主)
    private static final long FAIL_GIVEUP_MS = 2 * 60 * 60 * 1000L;     // ★A1:补传墙钟封顶——2小时还没成才真放弃+删占位(音频在笔上、可日后重导)
    private static final long DEFER_MAX_MS = 3 * 60 * 1000L; // 文件未出现在笔列表时的重试上限(跨过连录/笔忙/落盘延迟)，超时才放弃
    // ★笔存储清理：删除 >30天 的旧文件(已远超每日上云，确定已上云，安全)
    private static final long FILE_KEEP_MS = 30L * 24 * 3600 * 1000;
    private volatile boolean pendingCleanup = false;
    // ★扫描补传：连上空闲时扫笔机身文件，把 App 没传过的(最近 SWEEP_WINDOW_MS 内)自动入队补传，
    //   覆盖"全程断开状态下录的、App 从没感知到"的录音。已传集合持久化(uploadedFileNames)，后端再按 pen_file 去重。
    private volatile boolean pendingSweep = false;
    private static final long SWEEP_WINDOW_MS = 2L * 24 * 3600 * 1000; // 只扫最近2天，避免首次把整盘旧文件全扫
    private volatile boolean pendingSyncList = false;  // 手动"从录音笔同步"：拉文件列表给网页预览
    // ★扫描补传暂时关闭：v6 的实现有 bug(机身列表 durationSec=0→时长00:00；会复活已删录音；v5无pen_file去重不准)。
    //   关掉止血，待"删除墓碑 + 上传时 ffprobe 补时长 + 后端去重过滤"做对了再开。
    private static final boolean SWEEP_ENABLED = false;

    public PenController(Context ctx, Listener l) {
        this.appCtx = ctx.getApplicationContext();
        this.listener = l;
        moveSdkBleOffMainThread();   // ★把 SDK 的 BLE 工作线程从主线程挪到后台(治"下载时整个App卡死"+"卡5~7%")
        loadUploadedNames();    // ★恢复"已传文件名"持久化集合(防重启后扫描补传重复下载)
        loadPendingQueue();     // ★A1:恢复"待补传下载任务"(App被杀也不丢,等上下文就绪续传)
        registerBtReceiver();   // ★监听蓝牙开关，恢复时自动重连
    }

    /**
     * ★把杰理 SDK 的工作 Handler 从【主线程】换到【后台 HandlerThread】。
     * 根因：SDK 内部唯一的工作 Handler 用 Looper.getMainLooper() 建（已反编译确认），整个 BLE 状态机——
     * 包括下载大文件时每个 GATT 块的读写——全 post 到主线程。大文件下载几分钟，主线程被 BLE 操作灌满
     * → 整个 App 卡死动不了；且下载的 GATT 操作被 WebView/UI 抢占而饿死 → 进度卡在 5~7% 不动。
     * 把这个 Handler 换到专用后台线程后，BLE 不再占主线程，UI 不卡、下载也不被抢占。
     * 实现：反射改 SDK 的 public final 字段 a（同进程、本应用自带类，反射可行）。
     * fail-open：任何异常都吞掉、维持原主线程 Handler（顶多还卡，绝不崩）。App 内的 SDK 回调本就全部
     * main.post 回主线程做 UI，不依赖回调线程，故切换对上层透明。仅做一次（静态标志）。
     */
    private static volatile boolean sBleHandlerMoved = false;
    private void moveSdkBleOffMainThread() {
        if (sBleHandlerMoved) return;
        try {
            AIRECBleManager mgr = AIRECBleManager.getInstance();   // 触发单例初始化(此时它建的是主线程 Handler)
            java.lang.reflect.Field f = AIRECBleManager.class.getDeclaredField("a");
            f.setAccessible(true);
            Object cur = f.get(mgr);
            if (cur instanceof android.os.Handler
                    && ((android.os.Handler) cur).getLooper() != android.os.Looper.getMainLooper()) {
                sBleHandlerMoved = true; return;   // 已经不在主线程，无需处理
            }
            android.os.HandlerThread ht = new android.os.HandlerThread(
                    "airec-ble", android.os.Process.THREAD_PRIORITY_FOREGROUND);
            ht.start();
            f.set(mgr, new android.os.Handler(ht.getLooper()));
            sBleHandlerMoved = true;
            Log.w(TAG, "★SDK BLE Handler 已切到后台线程 airec-ble");
            penLog("★SDK BLE Handler 切后台线程 ok");
        } catch (Throwable t) {
            Log.w(TAG, "切 SDK BLE Handler 失败(维持主线程，下载可能仍卡): " + t.getMessage());
            try { penLog("★SDK BLE Handler 切后台失败:" + t.getMessage()); } catch (Throwable ignore) {}
        }
    }

    public boolean isConnected() {
        try { return AIRECBleManager.getInstance().isConnected(); }
        catch (Exception e) { return false; }
    }

    /** 录音笔当前是否真在录(会话进行中)。作为 UI"是否陪伴中"的唯一事实源：
     *  防黑屏时笔结束、idle 状态没传到 Activity(onPause 已解绑)→ state 残留"录音中"。 */
    public boolean isRecording() { return sessionActive || penRecording; }

    /** 录音笔当前已录秒数(UI 计时同步用)：优先笔上报的时长(最权威、跨黑屏准)，否则用起点连续计时；没在录返回 0。
     *  切出陪伴页再回来时据此把计时对到笔的真实进度，绝不从 0 重数。 */
    public int getRecordingElapsedSec() {
        if (!isRecording()) return 0;
        return lastPenDurationSec > 0 ? lastPenDurationSec : elapsedSec();
    }

    public AIRECBleCallback getCallback() { return callback; }

    /** 让 SDK 的回调指向本控制器（进入接诊页 / 开始用笔录音前调用）。 */
    public void activate() {
        AIRECBleManager.getInstance().setCallback(callback);
        App.setMainCallback(callback);
    }

    /** 设置/刷新上传上下文：接诊页在进入/连接/开始时调用，确保笔自发录音也有 cookie/上传地址可用。 */
    public void setUploadContext(String cookie, String uploadUrl) {
        boolean wasNull = (this.cookie == null || this.uploadUrl == null);
        if (cookie != null && !cookie.isEmpty()) this.cookie = cookie;
        if (uploadUrl != null && !uploadUrl.isEmpty()) this.uploadUrl = uploadUrl;
        // 刚拿到上传上下文(如刚进接诊页)且笔已连 → 扫一遍机身存储补传(覆盖App未开时录的)
        if (wasNull && this.cookie != null && this.uploadUrl != null) {
            main.postDelayed(this::triggerSweep, 3000);
        }
        // ★A1:上下文就绪 → 把上次没传完、被App杀掉时存盘的待补传任务用新cookie重建续传
        if (this.cookie != null && this.uploadUrl != null) materializeRestored();
    }

    // ============ 扫描补传：把 App 没传过的机身录音自动捞回来 ============

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

    // ============ A1：下载补传队列持久化 + 卡死看门狗（App被杀不丢任务、烂链路无限退避重试） ============
    private static final class PersistedDl {
        final String fileName; final int durSec; final long startWallMs;
        final long placeholderId; final boolean appInitiated; final long firstSeenMs;
        final int downloadRequeues; final long nextAttemptMs;
        PersistedDl(String fn, int d, long sw, long pid, boolean ai, long fs, int rq, long na) {
            fileName = fn; durSec = d; startWallMs = sw; placeholderId = pid; appInitiated = ai; firstSeenMs = fs;
            downloadRequeues = rq; nextAttemptMs = na;
        }
    }
    // App 启动时从盘里读出的待传任务，等拿到上传上下文(登录态)再用【新 cookie】重建入队
    private final java.util.List<PersistedDl> pendingRestore =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private volatile long lastDlProgressMs = 0;     // 最近一次下载进度时刻，0=没在下载
    private static final long DL_STALL_MS = 20000;   // 下载 >20s 无进度 → 判卡死、取消重试(卡5~7%更快恢复；有进度就一直喂、不误杀慢链路)
    private static final long STALE_GIVEUP_MS = 2 * 60 * 60 * 1000L;   // ★A1:补传任务从"入队时刻"起算超此时长仍没传成→放弃+清占位(不依赖是否发起过下载，治"进程被反复杀/链路烂、下载一个周期都跑不完"的僵尸)。★放弃只删未归档里那条"处理中"占位，音频仍在笔上→顾问可重新「取回小伙伴」，那是一条新任务、重新计时2h，不会被秒删

    private boolean isDownloadQueued(String fn) {
        if (fn == null) return false;
        for (UploadTask t : uploadQueue) if (t != null && fn.equals(t.fileName)) return true;
        return false;
    }

    /** 把当前队列里的"下载补传"任务(localOpsPath==null)存盘。队列每次变动调一次。 */
    private void persistPendingQueue() {
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            for (UploadTask t : uploadQueue) {
                if (t == null || t.localOpsPath != null || t.fileName == null) continue;
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("fn", t.fileName); o.put("dur", t.durSec); o.put("sw", t.startWallMs);
                o.put("pid", t.placeholderId); o.put("ai", t.appInitiated); o.put("fs", t.firstSeenMs);
                o.put("rq", t.downloadRequeues); o.put("na", t.nextAttemptWallMs);
                arr.put(o);
            }
            appCtx.getSharedPreferences("pen_prefs", Context.MODE_PRIVATE)
                    .edit().putString("pending_dl", arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    /** App 启动读盘 → 暂存 pendingRestore（此刻还没 cookie，等 setUploadContext 再 materialize）。 */
    private void loadPendingQueue() {
        try {
            String s = appCtx.getSharedPreferences("pen_prefs", Context.MODE_PRIVATE)
                    .getString("pending_dl", null);
            if (s == null || s.isEmpty()) return;
            org.json.JSONArray arr = new org.json.JSONArray(s);
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String fn = o.optString("fn", "");
                if (fn.isEmpty() || uploadedFileNames.contains(fn)) continue;
                pendingRestore.add(new PersistedDl(fn, o.optInt("dur", 0),
                        o.optLong("sw", 0), o.optLong("pid", -1), o.optBoolean("ai", false), o.optLong("fs", 0),
                        o.optInt("rq", 0), o.optLong("na", 0)));
            }
            if (!pendingRestore.isEmpty()) {
                Log.d(TAG, "A1:读到 " + pendingRestore.size() + " 条持久化待补传任务，待上下文就绪续传");
                penLog("★A1 读到 " + pendingRestore.size() + " 条持久化待补传(等登录态续传)");
            }
        } catch (Exception ignored) {}
    }

    /** 上传上下文就绪(登录)后：用【当前新 cookie/url】重建读盘的待传任务、入队续传。 */
    private void materializeRestored() {
        if (cookie == null || uploadUrl == null || pendingRestore.isEmpty()) return;
        java.util.List<PersistedDl> snap;
        synchronized (pendingRestore) { snap = new java.util.ArrayList<>(pendingRestore); pendingRestore.clear(); }
        int n = 0;
        for (PersistedDl p : snap) {
            if (p == null || p.fileName == null) continue;
            if (uploadedFileNames.contains(p.fileName) || isDownloadQueued(p.fileName)) continue;
            UploadTask t = new UploadTask(p.fileName, cookie, uploadUrl, penSn(),
                    p.durSec, p.startWallMs, p.appInitiated);
            t.placeholderId = p.placeholderId;
            t.firstSeenMs = p.firstSeenMs;   // ★A1:沿用首次失败时刻→封顶放弃跨重启有效
            t.downloadRequeues = p.downloadRequeues;   // ★A1:沿用重试次数→退避继续escalate，不重启就回到秒级
            t.nextAttemptWallMs = p.nextAttemptMs;     // ★A1:沿用退避到期→重启后尊重冷却(kickWorker据此延后)
            if (giveUpIfStale(t)) continue;  // ★A1:僵尸任务(从未传成且太老)直接清掉，不再重建入队 → 打破"重启→重建→又被杀"的无限循环
            uploadQueue.add(t);
            n++;
        }
        if (n > 0) {
            Log.d(TAG, "A1:续传 重建 " + n + " 条待补传任务入队");
            penLog("★A1 续传:重建 " + n + " 条待补传入队(App被杀后恢复)");
            persistPendingQueue();
            notifyPending();
            main.postDelayed(this::kickWorker, 1500);
        }
    }

    /** 任务"入队(初次出现)"以来的墙钟时长(ms)。
     *  ★优先用入队时刻 firstSeenMs：顾问手动重新同步一个旧录音 = 一条新任务、从现在重新计时，绝不被"旧录音时间"一上来就判超时误删
     *    → 保证"今天还想重传"始终能传。firstSeenMs 缺失(老数据=0)才退回录音墙钟 startWallMs，让历史僵尸也能按真实年龄尽快清掉。 */
    private long taskAgeMs(UploadTask t) {
        if (t == null) return -1;
        long ref = (t.firstSeenMs > 0) ? t.firstSeenMs : t.startWallMs;
        return ref > 0 ? (System.currentTimeMillis() - ref) : -1;
    }

    /** ★A1 僵尸自愈：下载补传任务从"录音时刻"起算已超 STALE_GIVEUP_MS 仍没传成 → 放弃 + 清占位，返回 true=已处理掉。
     *  治本点：进程被反复杀/蓝牙烂导致"下载一个完整周期都没跑完"的任务，requeueDownloadTask 那套(必须先发起过下载并失败)
     *  根本轮不到，firstSeenMs 永远是 0、2小时封顶永不触发 → 任务被无限重建、UI 永远"上传中"(宋艳那台华为就是这样)。
     *  这里不依赖任何下载尝试，按真实年龄直接收尾，打破"重启→重建入队→又被杀→重启"的死循环。 */
    private boolean giveUpIfStale(UploadTask t) {
        if (t == null || t.localOpsPath != null) return false;   // 只管"从笔下载补传"，本地直传(实时流)不在此列
        long age = taskAgeMs(t);
        if (age <= STALE_GIVEUP_MS) return false;
        Log.w(TAG, "A1:补传放弃(僵尸·已挂" + (age / 60000) + "min·从未传成) file=" + t.fileName);
        penLog("★A1 补传放弃(僵尸·挂了" + (age / 60000) + "分钟·从未传成)删占位,音频留笔上可日后重导 " + t.fileName);
        workerTaskFailed(t, "stale-giveup", true);   // 出队(若在队)+清占位+持久化，不再重建
        return true;
    }

    /** A1:下载补传【暂未成】(找不到文件/下载失败/卡死)：不放弃，挪队尾、退避后跨重连重试。 */
    private void requeueDownloadTask(final UploadTask task, final String reason) {
        main.post(() -> {
            if (task == null) return;
            task.downloadRequeues++;
            if (task.firstSeenMs == 0) task.firstSeenMs = System.currentTimeMillis();
            lastDlProgressMs = 0; main.removeCallbacks(downloadStallWatch);
            long elapsed = System.currentTimeMillis() - task.firstSeenMs;
            // ★A1 墙钟封顶(持久化、跨重启有效)：统一2小时。"未在笔列表找到"多半是笔还没把文件提交进列表
            //   (要等下一段录音才提交，可能拖到下次接诊)，给足时间，避免误删"其实在笔上、只是没提交"的录音；
            //   真·笔上没有的(如太短没落盘)2小时后才清理。
            if (elapsed > FAIL_GIVEUP_MS || task.downloadRequeues > MAX_DOWNLOAD_REQUEUES) {
                Log.w(TAG, "A1:补传放弃(已试" + (elapsed / 60000) + "min/" + task.downloadRequeues + "次," + reason + ") file=" + task.fileName);
                penLog("★A1 补传放弃(试了" + (elapsed / 60000) + "分钟·" + reason + ")删占位,音频留笔上可日后重导 " + task.fileName);
                workerTaskFailed(task, "放弃:" + reason, true);   // 出队+删占位，notifyPending 同步持久化
                return;
            }
            if (uploadQueue.remove(task)) uploadQueue.add(task);   // 挪队尾，别堵后面的
            workerBusy = false; currentTask = null; inflightTask = null; waitingForFile = false;
            long delay = Math.min(10 * 60 * 1000L, 8000L * task.downloadRequeues);  // 退避，封顶10分钟
            task.nextAttemptWallMs = System.currentTimeMillis() + delay;   // ★A1:退避到期持久化→App被杀重启后 kickWorker 仍尊重冷却，不秒重试敲爆BLE
            Log.w(TAG, "A1:下载补传暂未成(" + reason + ")，第" + task.downloadRequeues + "次，" + (delay / 1000) + "s后重试 file=" + task.fileName);
            penLog("★A1 补传暂未成(" + reason + ") 第" + task.downloadRequeues + "次 " + (delay / 1000) + "s后重试 " + task.fileName);
            persistPendingQueue();
            notifyPending();
            main.postDelayed(PenController.this::kickWorker, delay);
        });
    }

    /** A1:下载卡死看门狗——下载中 >DL_STALL_MS 没新进度 → cancelDownload + 挪队尾重来。 */
    private final Runnable downloadStallWatch = new Runnable() {
        @Override public void run() {
            UploadTask t = currentTask;
            boolean downloading = (t != null && t.localOpsPath == null && !waitingForFile && inflightTask == null);
            if (downloading && lastDlProgressMs > 0
                    && System.currentTimeMillis() - lastDlProgressMs > DL_STALL_MS) {
                Log.w(TAG, "A1:下载卡死>" + (DL_STALL_MS / 1000) + "s 无进度，取消重试 file=" + t.fileName);
                penLog("★A1 下载卡死>" + (DL_STALL_MS / 1000) + "s无进度 取消重试 " + t.fileName);
                try { AIRECBleManager.getInstance().cancelDownload(); } catch (Exception ignored) {}
                requeueDownloadTask(t, "下载卡死");   // 内部已清 lastDlProgressMs、移除本看门狗
                return;
            }
            if (lastDlProgressMs > 0) main.postDelayed(this, 5000);  // 还在下载，继续盯(5s 巡检)
        }
    };

    /** ★A1:网页「重试」按钮 → 立刻重推待补传(读盘任务materialize + 重置退避 + kick)。 */
    public void retryPenUploads() {
        main.post(() -> {
            materializeRestored();
            for (UploadTask t : uploadQueue) if (t != null) { t.downloadRequeues = 0; t.nextAttemptWallMs = 0; }  // 重置退避+清冷却，立刻试一轮
            Log.d(TAG, "A1:手动重试待补传 队列=" + uploadQueue.size());
            penLog("★A1 手动重试待补传 队列=" + uploadQueue.size());
            kickWorker();
        });
    }

    /** 连上且空闲 → 拉文件列表(onFileListUpdated 里 sweepPenStorage 处理)。 */
    private void triggerSweep() {
        if (!isConnected() || penRecording || sessionActive || appStartPending || workerBusy) return;
        if (cookie == null || uploadUrl == null) return;
        pendingSweep = true;
        try { AIRECBleManager.getInstance().fetchFileList(); } catch (Exception ignored) {}
    }

    // ============ 手动"从录音笔同步"：拉列表给网页预览 + 上传所选 ============

    /** 网页点"从录音笔同步" → 拉机身文件列表，回调 onPenFileList。 */
    public void requestPenFileList() {
        if (!isConnected()) { if (listener != null) listener.onPenFileList("[]"); return; }
        pendingSyncList = true;
        try { AIRECBleManager.getInstance().fetchFileList(); } catch (Exception e) {
            if (listener != null) listener.onPenFileList("[]");
        }
    }

    private static String jsEsc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
    private void deliverPenFileList(List<AIRECBleFile> files) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        if (files != null) {
            for (AIRECBleFile f : files) {
                if (f == null) continue;
                String name = f.getFileName();
                if (name == null || name.isEmpty()) continue;
                long startMs = parsePenFileStartMs(name, f);
                String ra = startMs > 0 ? fmtWall(startMs) : "";
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"name\":\"").append(jsEsc(name))
                  .append("\",\"ra\":\"").append(jsEsc(ra))
                  .append("\",\"size\":").append(f.getFileSize())
                  .append(",\"dur\":").append(f.getDurationSec())
                  .append(",\"uploaded\":").append(uploadedFileNames.contains(name)).append('}');
            }
        }
        sb.append("]");
        final String json = sb.toString();
        if (listener != null) main.post(() -> listener.onPenFileList(json));
    }

    /** 网页选好后 → 把选中的机身文件名(JSON 数组)入队下载补传(带 pen_file，后端去重/ffprobe补时长)。 */
    public void uploadPenFiles(String namesJson) {
        if (cookie == null || uploadUrl == null) return;
        java.util.List<String> names = new java.util.ArrayList<>();
        try {
            org.json.JSONArray arr = new org.json.JSONArray(namesJson);
            for (int i = 0; i < arr.length(); i++) {
                String n = arr.optString(i, null);
                if (n != null && !n.isEmpty()) names.add(n);
            }
        } catch (Exception e) { Log.w(TAG, "uploadPenFiles parse: " + e.getMessage()); return; }
        java.util.List<UploadTask> added = new java.util.ArrayList<>();
        for (String name : names) {
            if (isQueuedByName(name)) continue;
            long startMs = parsePenFileStartMs(name, null);
            UploadTask t = new UploadTask(name, cookie, uploadUrl, penSn(), 0, startMs, false);
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

    /** 文件名/createTime → 录音开始墙上毫秒；解析不到返回 0。 */
    private long parsePenFileStartMs(String name, AIRECBleFile f) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{14})").matcher(name == null ? "" : name);
            if (m.find()) {
                java.util.Date d = new java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US).parse(m.group(1));
                if (d != null) return d.getTime();
            }
        } catch (Exception ignored) {}
        try {
            String ct = (f != null) ? f.getCreateTime() : null;
            if (ct != null && !ct.isEmpty()) {
                for (String fmt : new String[]{"yyyy-MM-dd HH:mm:ss", "yyyy/MM/dd HH:mm:ss", "yyyyMMddHHmmss"}) {
                    try { java.util.Date d = new java.text.SimpleDateFormat(fmt, java.util.Locale.US).parse(ct); if (d != null) return d.getTime(); }
                    catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
        return 0;
    }
    private boolean isQueuedByName(String name) {
        if (name == null) return false;
        UploadTask c = currentTask, inf = inflightTask;
        if (c != null && name.equals(c.fileName)) return true;
        if (inf != null && name.equals(inf.fileName)) return true;
        for (UploadTask t : uploadQueue) if (t != null && name.equals(t.fileName)) return true;
        return false;
    }

    /** 扫描机身文件：没传过的(最近2天、非正在录的)入队补传。后端按 pen_file 去重，不会重复入库。 */
    private void sweepPenStorage(List<AIRECBleFile> files) {
        if (!SWEEP_ENABLED) return;   // ★暂时关闭(见 SWEEP_ENABLED 注释)
        if (files == null || files.isEmpty()) return;
        if (penRecording || sessionActive || appStartPending) return;
        if (cookie == null || uploadUrl == null) return;
        final long now = System.currentTimeMillis();
        java.util.List<UploadTask> added = new java.util.ArrayList<>();
        for (AIRECBleFile f : files) {
            if (f == null) continue;
            String name = f.getFileName();
            if (name == null || name.isEmpty()) continue;
            if (uploadedFileNames.contains(name)) continue;       // 已传过
            if (name.equals(sessionFileName)) continue;            // 正在录的那个
            if (isQueuedByName(name)) continue;                    // 已在队列
            long startMs = parsePenFileStartMs(name, f);
            if (startMs <= 0) continue;                            // 解析不到时间，保守跳过
            if ((now - startMs) > SWEEP_WINDOW_MS) continue;       // 太老
            if ((now - startMs) < 60_000) continue;                // 太新(可能还在写)，留给正常流程
            UploadTask t = new UploadTask(name, cookie, uploadUrl, penSn(), f.getDurationSec(), startMs, false);
            uploadQueue.add(t);
            added.add(t);
            penLog("★扫描补传入队 " + name + " dur=" + f.getDurationSec() + "s start=" + fmtWall(startMs));
        }
        if (!added.isEmpty()) {
            Log.d(TAG, "扫描录音笔存储：补传入队 " + added.size() + " 条");
            final String phUrl = placeholderUrlFrom(uploadUrl);
            for (final UploadTask t : added) {
                worker.submit(() -> {
                    long pid = Uploader.createPlaceholder(t.cookie, phUrl, t.startWallMs);
                    if (pid > 0) { t.placeholderId = pid; if (listener != null) main.post(listener::onPenPlaceholderCreated); }
                });
            }
            notifyPending();
            main.postDelayed(this::kickWorker, 1500);
        }
    }

    /** 当前后台待传/在传段数。 */
    public int pendingCount() { return uploadQueue.size(); }

    /** 累计保存失败段数（本批）。 */
    public int pendingFailedCount() { return failedCount; }

    /** 网络恢复：清掉排队任务的重试退避 + 立刻推进(别等退避计时，让卡着的录音马上补传)。 */
    public void onNetworkAvailable() {
        main.post(() -> {
            for (UploadTask t : uploadQueue) if (t != null) t.uploadAttempts = 0;
            if (!uploadQueue.isEmpty()) Log.d(TAG, "网络恢复 → 立即重试待传 队列=" + uploadQueue.size());
            kickWorker();
        });
    }

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
            penLog("★verified=true 收到真回包→确认已连接");
            connKeepAlive(true);   // ★笔真连上 → 挂连接级前台服务，App 在后台/锁屏也不被杀(蓝牙不断)
            if (listener != null) main.post(() -> listener.onPenConnected(true));
            startHeartbeat();
            verifyPenAllowed();   // ★真连上(SN此刻可读) → 校验这台笔归属，不是本人绑定的就断开+拦截录音
            // ★重连后：把上次录音中途断开(笔关机)那段补传(它在笔存储里)。
            if (pendingRecovery != null) {
                UploadTask r = pendingRecovery; pendingRecovery = null;
                if (shouldEnqueue(r.fileName)) {
                    uploadQueue.add(r);
                    Log.d(TAG, "重连后补传中断录音 file=" + r.fileName);
                    notifyPending();
                } else {
                    Log.d(TAG, "重连后跳过重复补传 file=" + r.fileName);
                }
            }
            materializeRestored();   // ★A1:连上且有上下文 → 把App被杀时存盘的待补传任务重建入队
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
                penLog("★握手超时(5s无真回包)→判假连接、主动断开");
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
                    penLog("★心跳连续2次未回→判失联、主动断开");
                    verifiedConnected = false;
                    connKeepAlive(false);   // ★笔失联 → 去抖90s后撤连接级保活(其间重连成功会取消)
                    try { AIRECBleManager.getInstance().disconnect(); } catch (Exception ignored) {}
                    if (listener != null) main.post(() -> listener.onPenConnected(false));
                    // ★关键修复：失联时主动复位录音会话 + 更新 UI，绝不干等 onDisconnected 回调。
                    //   笔关机/走远时该回调常不来→否则 sessionActive 残留、UI 永久卡"录制中"(瞎录制)、还能点结束。
                    resetSessionOnLinkDown();
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

    /** 诊断用:最新笔电量%(-1=未知) 和 蓝牙信号RSSI dBm(0=未知,正常负值如-60,越接近0越强)。 */
    public int penBattery() { return lastBattery; }
    public int penRssi() { return lastRssi; }

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

    // ============ 自动重连：断开/蓝牙恢复 → 自动连回同一支笔 ============

    private final Runnable reconnectRunnable = this::tryReconnect;

    /** 连上后调用：开启"维持连接"，并停掉重连循环。 */
    private void enableAutoReconnect() {
        autoReconnectOn = true;
        reconnectAttempts = 0;
        main.removeCallbacks(reconnectRunnable);
    }

    /** 用户主动断开时调用：不再自动重连。 */
    public void stopAutoReconnect() {
        autoReconnectOn = false;
        main.removeCallbacks(reconnectRunnable);
    }

    private void scheduleReconnect(long delayMs) {
        if (!autoReconnectOn) return;
        main.removeCallbacks(reconnectRunnable);
        main.postDelayed(reconnectRunnable, delayMs);
    }

    /** 一次重连尝试：蓝牙开着且记得上次的笔 → 扫同一 MAC 连回来；连不上则退避后再试。 */
    private void tryReconnect() {
        if (!autoReconnectOn || isConnected()) return;
        boolean btOn = false;
        try {
            android.bluetooth.BluetoothAdapter ad = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            btOn = ad != null && ad.isEnabled();
        } catch (Exception ignored) {}
        if (btOn && lastConnectedMac != null && lastConnectedMac.equals(penDeniedMac)) {
            // ★被拒的那台：不自动重连它，防 deny→重连→deny 死循环。需用户主动在列表里换台。
            penLog("自动重连跳过被拒的笔 " + lastConnectedMac);
        } else if (btOn && lastConnectedMac != null) {
            reconnectAttempts++;
            Log.d(TAG, "自动重连尝试#" + reconnectAttempts + " → " + lastConnectedMac);
            penLog("自动重连尝试#" + reconnectAttempts + " 扫描连 " + lastConnectedMac);
            autoConnect(lastConnectedMac);   // 扫到该 MAC 就连(其内含 12s 超时)
        } else {
            Log.d(TAG, "自动重连等待中(蓝牙关/无上次设备)，靠广播或下次轮询");
        }
        // 退避：前5次每15s，之后每60s。连上后 onConnected 会停掉本循环。
        scheduleReconnect(reconnectAttempts <= 5 ? 15000 : 60000);
    }

    /** 注册"蓝牙开关变化"广播：蓝牙重新打开 → 立刻重连。 */
    private void registerBtReceiver() {
        if (btStateReceiver != null) return;
        btStateReceiver = new android.content.BroadcastReceiver() {
            @Override public void onReceive(android.content.Context c, android.content.Intent i) {
                int st = i.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, -1);
                penLog("蓝牙状态广播 state=" + st + " (12=on,10=off) autoReconnectOn=" + autoReconnectOn + " connected=" + isConnected());
                if (st == android.bluetooth.BluetoothAdapter.STATE_ON && autoReconnectOn && !isConnected()) {
                    Log.d(TAG, "蓝牙恢复 → 立即重连");
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

    // ============ 笔存储清理：删除 >30天 的旧文件 ============

    /** 连上空闲时触发一次清理：拉文件列表 → 在 onFileListUpdated 里删 >30天 的。 */
    private void triggerCleanup() {
        if (!isConnected() || penRecording || sessionActive || appStartPending || workerBusy) return;
        pendingCleanup = true;
        pendingSweep = true;   // ★连上清理时顺带扫描补传(一次文件列表两用)
        try { AIRECBleManager.getInstance().fetchFileList(); } catch (Exception ignored) {}
    }

    /** 删除笔上时间戳 >30天 的文件。只删能解析出时间、且确实超期的(无效时间戳不动)。 */
    private void cleanupOldFiles(List<AIRECBleFile> files) {
        if (files == null) return;
        long now = System.currentTimeMillis();
        int deleted = 0;
        for (AIRECBleFile f : files) {
            if (f == null) continue;
            String fn = f.getFileName();
            long ts = parseFileTimestamp(fn);
            if (ts > 0 && now - ts > FILE_KEEP_MS) {
                try {
                    AIRECBleManager.getInstance().deleteFile(fn);
                    deleted++;
                    Log.d(TAG, "清理笔上 >30天 旧文件 " + fn);
                } catch (Exception e) { Log.e(TAG, "deleteFile failed " + fn, e); }
            }
        }
        if (deleted > 0) Log.d(TAG, "本次共清理 " + deleted + " 个超期文件");
    }

    /** 连上后读取设备设置打日志；并【关掉"开机自动录制"】——它会让笔一开机就自录、App 完全不知情(瞎录制)，
     *  与"App 必须和录音设备严格同步"直接冲突。故连接时检测：开着就 setPowerOnRecord(false) 关掉。
     *  写后笔会回 onInitParamUpdated 再次走到这里，读 powerOnRec 验证是否真关上了——还开着就再写，
     *  确认关闭后 penSettingsWritten 置 true 收敛(本次连接不再反复写)；断开时(onDisconnected)重置以便下次重连再查。
     *  其余设置(降噪/分段/USB/空闲关机)只读不写(交接文档：乱写有风险)。 */
    private void ensurePenConfigured() {
        try {
            AIRECBleManager mgr = AIRECBleManager.getInstance();
            if (!mgr.isConnected()) return;
            boolean powerOnRec = mgr.getPowerOnRecord();
            Log.d(TAG, "笔设置(读): noise=" + mgr.getNoiseSwitch()
                    + " segDur=" + mgr.getSegmentDuration()
                    + " powerOnRec=" + powerOnRec
                    + " idle=" + mgr.getIdleShutdown());
            if (penSettingsWritten) return;   // 本次连接已检查处理过 → 不重复(onInitParamUpdated 会多次回调)
            penSettingsWritten = true;
            if (powerOnRec) {
                // ★开机自动录制开着：笔一开机就自动录、App 完全不知情=瞎录制，这些文件只能走脆弱的"下载补传"路、极易传不上来。
                //   关掉它，持久化"等用户重启"状态(跨 App 被杀仍记得)，并弹框强提示顾问把笔关机重开。
                //   ——改设置要等下次开机才生效，且"本次开机已在进行的自动录制"也得靠重启才停；未重启前每次重连都重检测、持续提醒。
                mgr.setPowerOnRecord(false);
                setPoweronRestartPending(true);
                Log.w(TAG, "★检测到开机自动录制=开 → setPowerOnRecord(false) 关闭 + 强提示重启(未重启不生效)");
                penLog("★开机自动录制=开 → 已关闭，强提示重启笔(未重启前每次连接持续提醒)");
                if (listener != null) main.post(() -> listener.onPenPowerOnRecordDisabled());
            } else {
                penLog("开机自动录制=关 (无需处理)");
                if (isPoweronRestartPending()) {   // ★之前提示过、现在读到=关 → 用户已把笔关机重开生效 → 收尾 + 正反馈
                    setPoweronRestartPending(false);
                    Log.i(TAG, "★开机自动录制已确认关闭(用户已重启笔)");
                    penLog("★开机自动录制已确认关闭(用户已重启笔)");
                    if (listener != null) main.post(() -> listener.onPenPowerOnRecordConfirmedOff());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "ensurePenConfigured failed", e);
        }
    }

    /** ★开机自动录制"已关、等用户把笔关机重开生效"的持久化标记——跨 App 被杀仍记得，
     *  以便重连读到"已关"时确认用户已重启、给正反馈，并避免遗漏提醒。 */
    private void setPoweronRestartPending(boolean v) {
        try { appCtx.getSharedPreferences("pen_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("poweron_restart_pending", v).apply(); } catch (Exception ignored) {}
    }
    private boolean isPoweronRestartPending() {
        try { return appCtx.getSharedPreferences("pen_prefs", Context.MODE_PRIVATE)
                .getBoolean("poweron_restart_pending", false); } catch (Exception e) { return false; }
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
        if (!penAllowed) {   // ★SN校验没过/还没确认归属 → 不让录(连到别人的笔已被断开，这里挡住"识别中"窗口)
            String msg = (penDenyMsg != null && !penDenyMsg.isEmpty()) ? penDenyMsg
                    : "正在确认录音笔归属，请稍候；或在列表里换用你自己的录音笔";
            post(PhoneMicService.STATE_ERROR, msg, 0, -1);
            return;
        }
        appStartPending = true;
        sessionStartWallMs = System.currentTimeMillis();
        enterRecordingKeepAlive();   // ★你点开始时 App 必在前台，此刻起保活最稳，能扛住随后锁屏/切后台
        activate();
        try {
            AIRECBleManager.getInstance().startRecord();
            post(PhoneMicService.STATE_STARTING, "正在唤醒录音笔…", 0, -1);
            main.removeCallbacks(confirmTimeout);
            main.postDelayed(confirmTimeout, CONFIRM_TIMEOUT_MS);
        } catch (Exception e) {
            Log.e(TAG, "startRecord failed", e);
            appStartPending = false;
            exitRecordingKeepAlive();   // ★开录命令就没发出去 → 停保活，别泄漏唤醒锁
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
                exitRecordingKeepAlive();   // ★笔没确认开录、这段没真录 → 停保活，别泄漏唤醒锁
                post(PhoneMicService.STATE_ERROR, "启动失败：录音笔没有响应，请确认它已开机/唤醒后重试", 0, -1);
                try { AIRECBleManager.getInstance().disconnect(); } catch (Exception ignored) {}
            }
        }
    };

    /** （A）开录健康看门狗：笔回了"已开录"、限时内却既无实时流帧、笔也没报时长 → 判"应了但没真录"。 */
    private final Runnable recordingHealthWatchdog = new Runnable() {
        @Override public void run() {
            healthWatchdogArmed = false;
            // 只管"你点开始"的这段；笔自发(声控)录音不干预；暂停时不判。
            if (!sessionActive || !sessionAppInitiated || penPaused) return;
            boolean haveAudioEvidence = (sessionStreamFrames > 0) || (lastPenDurationSec > 0);
            if (haveAudioEvidence) return;   // 有真音频证据 → 正常录音，放过
            Log.w(TAG, "开录健康检查失败：" + REC_HEALTH_GRACE_MS + "ms 内无实时流帧也无时长 → 判定笔未真正开始录音");
            penLog("★开录看门狗触发(无流无时长)→判未真录、停并报错 frames=" + sessionStreamFrames + " penDur=" + lastPenDurationSec);
            abortGhostRecording();
        }
    };

    /** "笔应了开录但没真录"：干净复位会话、不入队、给出即时明确错误，让用户当场重连重录（不再墙钟空跑）。 */
    private void abortGhostRecording() {
        disarmHealthWatchdog();
        stopStreamCapture();
        exitRecordingKeepAlive();   // ★没真录 → 停保活
        sessionGen++;    // 作废任何挂起的结束兜底
        penRecording = false; sessionActive = false; appStartPending = false; sessionAppInitiated = false;
        startElapsedMs = 0; sessionFileName = null; sessionStartWallMs = 0;
        sessionStreamBuf = null; sessionStreamComplete = false; sessionStreamBytes = 0; sessionStreamFrames = 0;
        lastPenDurationSec = 0;
        // 尝试发停止把笔可能的"半开"状态收掉（失败忽略，保留连接好让用户快速重试）。
        try { AIRECBleManager.getInstance().endRecord(); } catch (Exception ignored) {}
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

    /** （B）断线宽限到：仍没接回一段真在录的会话 → 老实结束本段，停掉网页墙钟空跑。 */
    private final Runnable reconnectGiveUp = new Runnable() {
        @Override public void run() {
            if (sessionActive || penRecording) return;   // 已重新接上并在录 → 不打扰
            Log.w(TAG, "断线重连宽限到，仍未恢复录音 → 老实结束本段(只到断开前)");
            penLog("★重连宽限到仍未恢复→老实结束、停墙钟空跑");
            exitRecordingKeepAlive();   // ★本段已放弃 → 停保活(断开前那段由 pendingRecovery 重连后补传)
            // pendingRecovery 仍在：重连后会把断开前那段补传，不丢；这里只是不再假装还在录。
            post(PhoneMicService.STATE_ERROR, "录音笔信号中断，本段只保存到断开前，请重连后继续", 0, -1);
        }
    };

    /** 链路掉了（onDisconnected 或【心跳判失联】）统一的录音会话收尾：复位录音状态、存断开前那段待补传，
     *  按是否自动重连决定"录音中·重连中(25s宽限)"还是"已断开"，并把状态【如实推给 UI】(不再空走计时/留结束按钮)。
     *  幂等：没在录就只确保不残留。★关键：心跳判失联必须主动调它——不能干等 onDisconnected 回调
     *  (笔关机/走远时该回调常不来，注释见 heartbeat)，否则 sessionActive 残留→UI 永久卡"录制中"=瞎录制。 */
    private void resetSessionOnLinkDown() {
        main.removeCallbacks(confirmTimeout);   // 防断开后确认超时误判
        disarmHealthWatchdog();                 // 断开后开录看门狗无意义，撤掉防误触发
        if (!(sessionActive || penRecording || appStartPending)) return;
        sessionStreamComplete = false;   // ★录音中断→实时流有缺口，本段回落到"下载补全"路径
        Log.w(TAG, "链路掉→复位残留会话 sessionActive=" + sessionActive + " penRecording=" + penRecording + " appStartPending=" + appStartPending);
        // ★录音中途断链(笔关机/走远/信号丢)：这段其实已录在笔存储里，存它的信息，等重连+笔空闲自动补下载，不丢。
        if (sessionActive && !TextUtils.isEmpty(sessionFileName) && cookie != null && uploadUrl != null) {
            pendingRecovery = new UploadTask(sessionFileName, cookie, uploadUrl, penSn(),
                    elapsedSec(), sessionStartWallMs, sessionAppInitiated);
            Log.w(TAG, "录音中断链路，保存待补传 file=" + sessionFileName);
        }
        boolean willRecover = (pendingRecovery != null);
        boolean wasRecording = (sessionActive || penRecording);
        int wasElapsed = elapsedSec();   // 复位前捕获，重连显示时计时不归零
        sessionGen++;   // 作废挂起的结束兜底
        penRecording = false; sessionActive = false;
        appStartPending = false; sessionAppInitiated = false;
        startElapsedMs = 0; sessionFileName = null; sessionStartWallMs = 0;
        if (wasRecording && autoReconnectOn) {
            // 给 25s 重连宽限：接回真在录的会话则续；到点没接回由 reconnectGiveUp 老实结束、停墙钟空跑。
            post(PhoneMicService.STATE_RECORDING, "🔄 信号断开，正在自动重连，录音继续中…", wasElapsed, -1);
            main.removeCallbacks(reconnectGiveUp);
            main.postDelayed(reconnectGiveUp, RECONNECT_RECORDING_GRACE_MS);
        } else {
            exitRecordingKeepAlive();   // ★没在录 / 不会重连 → 停保活
            post(PhoneMicService.STATE_ERROR,
                    willRecover ? "录音笔断开了，正在自动重连…" : "录音笔已断开，正在自动重连…", 0, -1);
        }
    }

    /** App 点「结束陪伴」：发停止命令。笔停 → onRecordStateChanged(false) → 入队后台上传。 */
    public void stopRecording() {
        main.removeCallbacks(confirmTimeout);
        main.removeCallbacks(preStartTimeout);   // 启动确认期间点"取消"也能撤销
        disarmHealthWatchdog();                  // 用户主动结束 → 撤开录看门狗
        main.removeCallbacks(reconnectGiveUp);   // 用户在重连宽限期内点结束 → 撤断线宽限
        if (appStartPending && !penRecording) {
            // 还没等到笔确认开录就点了结束 → 没真录，直接回 idle
            appStartPending = false;
            sessionStartWallMs = 0;
            exitRecordingKeepAlive();   // ★没真录 → 停保活
            post(PhoneMicService.STATE_IDLE, "已结束", 0, -1);
            return;
        }
        if (!penRecording && !sessionActive) {
            exitRecordingKeepAlive();   // ★本就没在录 → 停保活
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
        disarmHealthWatchdog();                  // 本段已收尾 → 撤开录看门狗
        main.removeCallbacks(reconnectGiveUp);   // 正常收尾 → 撤断线宽限
        lastPenDurationSec = 0;
        final boolean appInit = sessionAppInitiated;   // 捕获本段是否你主动发起
        // ★先收口实时流：停止捕获、在复位前快照缓冲
        stopStreamCapture();
        final java.io.ByteArrayOutputStream streamBuf = sessionStreamBuf;
        boolean streamComplete = sessionStreamComplete;
        final long streamBytes = sessionStreamBytes;
        sessionStreamBuf = null; sessionStreamComplete = false;
        sessionStreamBytes = 0; sessionStreamFrames = 0;
        // ★防截断件直传(最后一道闸)：实时流估算秒数(KA SILK-WB ≈80B/20ms ≈ 4000B/s) 远小于墙钟 durSec
        //   → 八成中途静默卡流没被看门狗逮到，别信这段 buffer，回落"从笔机身补下载"拿全段，绝不上传截断件。
        if (streamComplete && durSec >= 30 && streamBuf != null) {
            int estStreamSec = (int) (streamBytes / 4000);
            if (estStreamSec < durSec * 0.6) {
                Log.w(TAG, "实时流估算" + estStreamSec + "s ≪ 墙钟" + durSec + "s → 疑似截断，回落补下载");
                penLog("★疑似截断 est=" + estStreamSec + "s wall=" + durSec + "s → 回落补下载");
                streamComplete = false;
            }
        }

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
        } else if (fn != null && haveCtx && shouldEnqueue(fn)) {
            // 回落：流不完整(蓝牙断过)/没捕获到 → 走原"从笔存储下载补全"路径
            final UploadTask task = new UploadTask(fn, cookie, uploadUrl, penSn(), durSec, startWallMs, appInit);
            uploadQueue.add(task);
            Log.d(TAG, "入队后台下载补全 file=" + fn + "(流不完整) 队列=" + uploadQueue.size());
            enqueuePlaceholderAndKick(task, startWallMs, 1200);
        } else if (fn != null && haveCtx) {
            // 同一文件已传过/已在队列(如断线补传已处理) → 去重，不重复上传；
            // ★但仍要推进队列里那条已存在的任务(否则它会卡住，直到下次录音才被带出来)。
            Log.d(TAG, "跳过重复入队 file=" + fn + "(已传/已在队)，仅推进队列");
            post(PhoneMicService.STATE_IDLE, "已结束", 0, -1);
            main.postDelayed(this::kickWorker, 800);
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
        exitRecordingKeepAlive();   // ★本段录音已收尾入队 → 停保活(上传/补下载由后台队列自带重试，不再占前台)
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
        sessionStreamComplete = true;   // 先假定完整，蓝牙断过/中途卡死则置 false
        sessionStreamBytes = 0; sessionStreamFrames = 0;
        lastStreamFrameMs = 0;
        try {
            AIRECBleManager.getInstance().setAudioStreamListener(this::onStreamFrame);
        } catch (Exception e) {
            Log.e(TAG, "setAudioStreamListener 失败，本段回落下载", e);
            sessionStreamComplete = false;
        }
        main.removeCallbacks(streamStallWatch);
        main.postDelayed(streamStallWatch, 2000);   // ★起卡流看门狗
    }

    /** 实时流回调：把 80 字节 KA 块原样拼接(不解码)。 */
    private void onStreamFrame(byte[] data) {
        if (data == null || data.length == 0) return;
        java.io.ByteArrayOutputStream buf = sessionStreamBuf;
        if (buf == null) return;
        try { synchronized (buf) { buf.write(data); } } catch (Exception ignore) {}
        sessionStreamBytes += data.length;
        sessionStreamFrames++;
        lastStreamFrameMs = SystemClock.elapsedRealtime();   // ★记最后一帧时刻，供卡流看门狗判定
        // ★有真音频流进来 = 笔确实在录 → 撤开录看门狗（armed 守卫：避免每帧都扫消息队列）
        if (healthWatchdogArmed) { healthWatchdogArmed = false; main.removeCallbacks(recordingHealthWatchdog); }
    }

    /** ★录音中卡流看门狗：流来过、但 >STREAM_STALL_MS 没新帧(连着但没数据) → 标本段流不完整，收尾走补下载。 */
    private final Runnable streamStallWatch = new Runnable() {
        @Override public void run() {
            if (sessionStreamBuf == null) return;   // 捕获已停 → 不再续期
            if (sessionActive && !penPaused && sessionStreamComplete
                    && lastStreamFrameMs > 0
                    && (SystemClock.elapsedRealtime() - lastStreamFrameMs) > STREAM_STALL_MS) {
                sessionStreamComplete = false;   // 误判也只是多走一次"从笔补下载"(拿到全段)，不丢音频——对商业软件是安全方向
                Log.w(TAG, "实时流卡死 >" + STREAM_STALL_MS + "ms 无新帧 → 标记流不完整，收尾走补下载 bytes=" + sessionStreamBytes);
                penLog("★实时流卡死无新帧→标不完整(回落补下载) bytes=" + sessionStreamBytes);
            }
            main.postDelayed(this, 2000);
        }
    };

    private void stopStreamCapture() {
        main.removeCallbacks(streamStallWatch);
        try { AIRECBleManager.getInstance().setAudioStreamListener(null); } catch (Exception ignore) {}
    }

    // ============ 录音笔录音保活（前台 Service + 唤醒锁，幂等） ============

    /** 录音段开始：起前台保活，托住进程整段不被挂起。幂等。 */
    private void enterRecordingKeepAlive() {
        main.removeCallbacks(keepAliveStopRun);   // ★去抖：刚排了延迟停止又要录 → 撤掉，别让唤醒锁被刷成秒级起停
        if (keepAliveOn) return;
        keepAliveOn = true;
        PenKeepAliveService.recOn(appCtx);   // 录音级：FGS + 唤醒锁
        penLog("保活↑(录音中, FGS+唤醒锁)");
    }

    /** 录音段结束/放弃：不立刻撤，linger 去抖后再撤"录音级"持有——治声控/暂停把录音状态秒级 toggle 导致churn。
     *  linger 期间又要录、或还有待补传任务(下载也需唤醒锁) → 继续持有。幂等。
     *  ★注意：撤的只是录音级(放唤醒锁)；只要笔还连着，连接级 FGS 仍托住进程，后台依旧不被杀。 */
    private void exitRecordingKeepAlive() {
        if (!keepAliveOn) return;
        main.removeCallbacks(keepAliveStopRun);
        main.postDelayed(keepAliveStopRun, KEEPALIVE_LINGER_MS);
    }

    private static final long KEEPALIVE_LINGER_MS = 12000;   // ★录音级去抖窗口：停前先等这么久，期间重新开录则不真停
    private final Runnable keepAliveStopRun = new Runnable() {
        @Override public void run() {
            if (!keepAliveOn) return;
            // ★还在录 / 还有待补传任务 → 继续持唤醒锁，过会儿再判；都没了才放锁(FGS 由连接级决定去留)
            if (penRecording || sessionActive || !uploadQueue.isEmpty()) {
                main.postDelayed(keepAliveStopRun, KEEPALIVE_LINGER_MS);
                return;
            }
            keepAliveOn = false;
            PenKeepAliveService.recOff(appCtx);   // 放唤醒锁；若仍连着 FGS 继续(连接级)
            penLog("保活↓(录音结束放唤醒锁, 连接级FGS仍保持)");
        }
    };

    // ====== 连接级保活：只要笔连着就挂前台服务(后台不被杀)，断开 90s 还没回来才撤(扛短暂抖动/重连，不翻转) ======
    private static final long CONN_KEEPALIVE_LINGER_MS = 90000;
    private volatile boolean connKeepAliveOn = false;
    private final Runnable connKeepAliveStopRun = new Runnable() {
        @Override public void run() {
            connKeepAliveOn = false;
            PenKeepAliveService.connOff(appCtx);   // 撤连接级；FGS 若无录音级持有则停
            penLog("保活·连接级↓(笔断开超" + (CONN_KEEPALIVE_LINGER_MS / 1000) + "s)");
        }
    };
    /** 笔真连上 → 挂连接级 FGS(进程后台不被杀)；笔断开 → 去抖 90s 再撤(短暂抖动/重连期间不撤)。 */
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

    /** 调试期：连接/录音/重连 状态机事件写文件(vivo 封了 logcat，靠它看)。append。 */
    private void penLog(String ev) {
        try {
            File root = appCtx.getExternalFilesDir(null);
            if (root == null) return;
            File dir = new File(root, "stream_ops");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, "penlog.txt");
            try (java.io.FileWriter w = new java.io.FileWriter(f, f.length() < 262144)) {   // >256KB 重写，防无限增长
                w.write((android.os.SystemClock.elapsedRealtime() / 1000 % 100000) + "s  " + ev
                        + "  [conn=" + verifiedConnected + " rec=" + penRecording + " sess=" + sessionActive + " q=" + uploadQueue.size() + "]\n");
            }
        } catch (Exception ignore) {}
    }

    /** 开启/结束陪伴震动反馈：用「通知震动」实现——震动由系统执行，不受 Android 10+ 对【后台应用】
     *  Vibrator 的限制(直接调 Vibrator 在后台/黑屏会被静默丢弃)。笔自发开录、手机黑屏/切后台都能震。
     *  isEnd=true 双短震「嗒-嗒」(结束陪伴)、false 单短震「嗒」(开启陪伴)。通知 1.5s 自动消失。失败静默。 */
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
                    ch.setSound(null, null);     // 只震不响
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

    /** 调试期：把一行状态写到外部目录 stream_ops/last_result.txt（vivo 限制 logcat，靠它看上传结果）。 */
    private void writeProbeStatus(String line) {
        try {
            File root = appCtx.getExternalFilesDir(null);
            if (root == null) return;
            File dir = new File(root, "stream_ops");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, "last_result.txt");
            try (java.io.FileWriter w = new java.io.FileWriter(f, f.length() < 131072)) {   // >128KB 重写
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
            File dir = new File(appCtx.getCacheDir(), "stream_ops");   // 内部缓存，可被系统清理
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

    /** 上传用最新 cookie：实例 this.cookie(连接/录音状态变化、重登时 setUploadContext 刷新)优先，
     *  回退任务固化的旧 cookie。→ cookie 过期后用户重登，队列里旧任务的重试自动用新 cookie，不必重启 App。 */
    private String cookieNow(UploadTask t) {
        return (cookie != null && !cookie.isEmpty()) ? cookie : t.cookie;
    }

    /** 直传本地拼好的 .ops(不占蓝牙、不解码、opus 端到端)。 */
    private void uploadLocalOps(final UploadTask task) {
        worker.submit(() -> {
            try {
                File ops = new File(task.localOpsPath);
                if (!ops.exists() || ops.length() < 320) { workerTaskFailed(task, "流文件无效", true); return; }
                // ★官方转换：KA 私有 opus → 标准 OGG Opus(浏览器直接播、ffmpeg直接转写、后端不用解码)
                String oggPath = ATWOpusConverter.convert(task.localOpsPath);
                if (oggPath == null || !new File(oggPath).exists() || new File(oggPath).length() < 64) {
                    workerTaskFailed(task, "ogg转换失败", true); return;
                }
                File ogg = new File(oggPath);
                String name = stripExt(baseName(task.localOpsPath)) + ".ogg";
                long t0 = SystemClock.elapsedRealtime();
                Uploader.Result r = Uploader.upload(ogg, task.durSec, cookieNow(task), task.uploadUrl,
                        name, "audio/ogg", task.sn, task.placeholderId, fmtWall(task.startWallMs), task.fileName);
                long ms = SystemClock.elapsedRealtime() - t0;
                writeProbeStatus("[直传ogg] " + name + " ogg=" + ogg.length() + "B 上传" + ms + "ms ok=" + r.ok + " recId=" + r.recordingId + " err=" + r.error);
                if (r.ok) {
                    try { ops.delete(); ogg.delete(); } catch (Exception ignore) {}   // 成功即删
                    workerTaskDone(task, r.recordingId);
                } else if (r.needsReauth) {
                    // ★登录失效：绝不丢音频(重登就能救)。删 ogg 临时产物，保留 ops 与占位，留队退避，等重登后用新 cookie 自动重传。
                    try { ogg.delete(); } catch (Exception ignore) {}
                    Log.w(TAG, "ogg上传需重登(留队等重登，不丢)：" + r.error);
                    requeueTransient(task);
                } else if (r.transientFail) {
                    try { ogg.delete(); } catch (Exception ignore) {}   // 删ogg临时产物，保留ops待重试
                    Log.w(TAG, "ogg上传临时失败(重试)：" + r.error);
                    requeueTransient(task);
                } else {
                    try { ops.delete(); ogg.delete(); } catch (Exception ignore) {}
                    Log.w(TAG, "ogg上传永久失败(放弃)：" + r.error);
                    workerTaskFailed(task, "upload rejected", true);
                }
            } catch (Exception e) {
                Log.e(TAG, "uploadLocalOps 失败", e);
                requeueTransient(task);   // 本地异常当临时，重试(别丢)
            }
        });
    }

    /** 墙上时间(ms) → "yyyy-MM-dd HH:mm:ss"，<=0 返回 null。 */
    private static String fmtWall(long ms) {
        if (ms <= 0) return null;
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(new java.util.Date(ms));
    }

    // ============ SDK 回调 ============

    private final AIRECBleCallback callback = new AIRECBleCallback() {
        @Override
        public void onRecordStateChanged(boolean recording, String fileName) {
            markPenResponded();
            penLog("onRecordStateChanged rec=" + recording + " file=" + fileName + " appStartPending=" + appStartPending);
            if (recording) {
                // ★镜像：笔开始录音（App 发起 或 笔上操作/声控自动）→ 都显示「陪伴进行中」。
                boolean wasAppStart = appStartPending;   // 捕获：这段是不是你在App点开始触发的
                penRecording = true;
                appStartPending = false;
                main.removeCallbacks(confirmTimeout);
                if (!sessionActive && !wasAppStart && isStaleRecordingFile(fileName)) {
                    // ★幽灵：非你发起、且文件很旧 = 笔的残留状态，别当新录音
                    penLog("忽略幽灵旧录音开始 file=" + fileName);
                    penRecording = false;
                    return;
                }
                if (!sessionActive) {
                    sessionActive = true;
                    enterRecordingKeepAlive();   // ★笔自发(声控/按钮)开录也要保活；后台启动受限时 start() 已吞异常
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
                    buzz(false);   // 开启陪伴：单短震（通知震动，黑屏/后台/笔自发开录也生效）
                    main.removeCallbacks(reconnectGiveUp);   // 新会话已建立 → 撤断线宽限
                    if (wasAppStart) armHealthWatchdog();    // ★你点的开始：限时内必须出现真音频证据
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
                buzz(true);   // 结束陪伴：双短震（引擎层触发，黑屏/后台也生效）
                finishSessionEnqueue(
                        !TextUtils.isEmpty(fileName) ? fileName : sessionFileName,
                        elapsedSec(), sessionStartWallMs);
            }
        }

        @Override
        public void onFileListUpdated(List<AIRECBleFile> files) {
            markPenResponded();
            if (pendingCleanup) { pendingCleanup = false; cleanupOldFiles(files); }
            if (pendingSweep) { pendingSweep = false; sweepPenStorage(files); detectUnsynced(files); }
            if (pendingSyncList) { pendingSyncList = false; deliverPenFileList(files); }
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
                } else {
                    // ★A1:4次还没在笔列表里找到 → 不再3分钟就放弃，挪队尾、退避后跨重连继续(笔忙/落盘延迟/断连都熬得住)
                    requeueDownloadTask(task, "未在笔列表找到");
                }
                return;
            }
            waitingForFile = false;
            Log.d(TAG, "后台：定位到文件 " + target.getFileName() + "（任务名=" + task.fileName + "）");
            try {
                AIRECBleManager.getInstance().downloadFile(target);
                lastDlProgressMs = System.currentTimeMillis();        // ★A1:启动卡死看门狗
                main.removeCallbacks(downloadStallWatch);
                main.postDelayed(downloadStallWatch, 10000);
            } catch (Exception e) { requeueDownloadTask(task, "download err: " + e.getMessage()); }
        }

        @Override
        public void onFileDownloadProgress(AIRECBleFile file, int progress) {
            markPenResponded();
            lastDlProgressMs = System.currentTimeMillis();   // ★A1:喂看门狗(有进度就不算卡死)
            // 后台进度：推给 UI 显示"保存中 N%"(大文件几分钟)。不抢占当前录音 UI。
            if (listener != null) main.post(() -> listener.onPenProgress(progress));
            Log.d(TAG, "后台下载 " + progress + "%");
        }

        @Override
        public void onFileDownloadComplete(AIRECBleFile file, String localPath) {
            markPenResponded();
            lastDlProgressMs = 0; main.removeCallbacks(downloadStallWatch);   // ★A1:下完了，停看门狗
            downloadRetries = 0;
            final UploadTask task = currentTask;
            if (task == null) return;
            inflightTask = task;   // 进入解码+上传阶段：去重 + 不再被 pauseWorker 打断
            processAndUpload(task, file, localPath);
        }

        @Override
        public void onFileDownloadFailed(AIRECBleFile file, String reason) {
            lastDlProgressMs = 0; main.removeCallbacks(downloadStallWatch);   // ★A1:停看门狗
            final UploadTask task = currentTask;
            if (task == null) return;
            if (penRecording) {
                // 笔在录(0xFD 无法下载)：留任务，等录完 kickWorker 再续。
                workerTaskFailed(task, "pen recording: " + reason, false);
                return;
            }
            // ★泄漏锁自愈：SDK 回"已有文件正在下载"=上一次下载断开时锁没释放。主动 cancelDownload 清锁，
            //   下一轮 fetchFileList→downloadFile 就能正常发起，不必等 App 重启。
            if (reason != null && reason.contains("正在下载")) {
                try { AIRECBleManager.getInstance().cancelDownload(); } catch (Exception ignored) {}
            }
            if (downloadRetries < MAX_DOWNLOAD_RETRIES) {
                downloadRetries++;
                Log.w(TAG, "后台下载失败(" + reason + ")，第 " + downloadRetries + " 次快速重试");
                waitingForFile = true;
                main.postDelayed(() -> {
                    if (waitingForFile) {
                        try { AIRECBleManager.getInstance().fetchFileList(); } catch (Exception ignored) {}
                    }
                }, 2500);
            } else {
                // ★A1:快速重试也失败 → 不放弃，挪队尾、退避后跨重连重来(烂链路逮到好窗口就能凑成)
                requeueDownloadTask(task, "下载失败:" + reason);
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
            currentMac = (device != null ? device.getAddress() : null);
            penAllowed = true;   // ★fail-open：默认放行(连上即可录、<2s)。只有后端【明确判拒】才 false+断开；
            penDenyMsg = null;   //   SN读不到/网络失败/没配上下文 一律不挡录音(商业化：绝不因这些让人录不了)。
            penLog("onConnected GATT就绪 " + (device != null ? device.getAddress() : "?"));
            enableAutoReconnect();   // ★连上了：开启"维持连接"、停掉重连循环
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
            // ★读"初始化参数"(含开机自动录制 powerOnRecord)：必须主动 fetchInitParam，否则 getPowerOnRecord
            //   读的是上次旧缓存——笔关了重连仍误判=开、重复弹框。读回后由 onInitParamUpdated→ensurePenConfigured 判断；
            //   不再用固定延迟直接读(那样必读旧值)。fetchInitParam 没回则不判(宁可漏检也不误弹)。
            main.postDelayed(() -> {
                try { AIRECBleManager.getInstance().fetchInitParam(); }
                catch (Exception e) { Log.e(TAG, "fetchInitParam failed", e); }
            }, 1200);
            // 连上后若有积压的后台上传，趁笔空闲推进。
            main.postDelayed(this::tryResumeWorker, 4000);
            // 连上空闲后清理笔上 >30天 旧文件。
            main.postDelayed(PenController.this::triggerCleanup, 9000);
        }

        private void tryResumeWorker() { kickWorker(); }

        @Override
        public void onInitParamUpdated() { markPenResponded(); ensurePenConfigured(); }

        @Override
        public void onDeviceInfoUpdated(AIRECBleDevice device) {
            markPenResponded();   // 收到设备信息 = 笔有真实回包,顺带当心跳
            if (device == null) return;
            int bat = device.getBattery();
            int rssi = device.getRssi();
            lastBattery = bat;
            lastRssi = rssi;
            // penlog 节流写入(空闲每8s会回调,别刷屏):电量变化 / RSSI跨档(≥8dBm) / 距上次>60s 才记一行,
            //   留下信号&电量随时间的趋势,便于看"断连前信号是不是在掉"。
            long now = SystemClock.elapsedRealtime();
            if (bat != lastLoggedBattery
                    || Math.abs(rssi - lastLoggedRssi) >= 8
                    || (now - lastDevInfoLogMs) > 60000) {
                penLog("设备信息 电量=" + bat + "% 信号=" + rssi + "dBm");
                lastLoggedBattery = bat;
                lastLoggedRssi = rssi;
                lastDevInfoLogMs = now;
            }
        }

        @Override
        public void onDisconnected(AIRECBleDevice device, String reason) {
            penLog("onDisconnected reason=" + reason);
            // 复位真连接/心跳状态
            verifiedConnected = false; lastRxMs = 0;
            connKeepAlive(false);   // ★断开 → 去抖90s后撤连接级保活(其间自动重连成功会取消)
            stopHeartbeat();
            main.removeCallbacks(handshakeTimeout);
            if (listener != null) main.post(() -> listener.onPenConnected(false));
            // 断开时若后台正在下载，标记失败留队，重连后再续。
            if (workerBusy && currentTask != null) {
                // ★断开中若有 BLE 下载在飞，必须显式 cancelDownload 释放 SDK 那把"正在下载"锁，
                //   否则锁泄漏→重连后每次 downloadFile 都被回"已有文件正在下载"，队列永久卡死(35%定格)。
                try { AIRECBleManager.getInstance().cancelDownload(); } catch (Exception ignored) {}
                lastDlProgressMs = 0; main.removeCallbacks(downloadStallWatch);
                workerTaskFailed(currentTask, "disconnected", false);
            }
            // ★断开必复位会话：否则 sessionActive/penRecording/appStartPending 残留→UI 卡"录制中"、重连点开始被守卫静默吞。
            resetSessionOnLinkDown();
            // ★断开后：若仍要维持连接，自动重连回同一支笔(扫到该MAC就连)
            if (autoReconnectOn) {
                Log.d(TAG, "断开 → 启动自动重连循环");
                reconnectAttempts = 0;
                scheduleReconnect(3000);
            }
        }

        @Override
        public void onRecordStatusQueried(boolean recording, boolean paused, String fileName) {
            markPenResponded();   // 0x0F 回包 = 笔活着（握手最常用这条点亮真连接）
            penPaused = recording && paused;
            penLog("onRecordStatusQueried rec=" + recording + " paused=" + paused + " file=" + fileName);
            // 连上时查到笔已经在录 → 镜像里也显示出来（笔上更早就开始录的场景）。
            if (recording && !sessionActive && isStaleRecordingFile(fileName)) {
                // ★幽灵：连上时笔报了个很旧的残留"录音"状态(非真在录) → 忽略，不镜像不上传。
                penLog("忽略幽灵旧录音(连上时笔残留状态) file=" + fileName);
            } else if (recording && !sessionActive) {
                penRecording = true;
                sessionActive = true;
                enterRecordingKeepAlive();   // ★连上即在录(笔更早自发开录) → 也保活
                sessionGen++;    // ★新一段
                sessionAppInitiated = false;  // 连上时笔已在录=笔自发，空录静默丢
                sessionFileName = !TextUtils.isEmpty(fileName) ? fileName : null;
                if (sessionStartWallMs == 0) sessionStartWallMs = 0; // 不设墙上时间→不做旧文件拒收（更早开始的）
                if (startElapsedMs == 0) startElapsedMs = SystemClock.elapsedRealtime();
                pauseWorker();
                post(PhoneMicService.STATE_RECORDING, "录音中…（录音笔）", 0, -1);
                main.removeCallbacks(reconnectGiveUp);   // 重连后接回一段真在录的会话 → 撤断线宽限
            }
            if (listener != null) main.post(() -> {
                listener.onPenRecordStatus(recording);
                if (recording) listener.onPenPaused(paused);
            });
        }

        @Override
        public void onRecordPaused() {
            penLog("onRecordPaused(笔上报暂停切换) appInitiated=" + appInitiatedPauseResume);
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
            // ★笔报了已录时长>0 = 笔确实在录 → 撤开录看门狗（即使实时流没捕获到也算真录证据）
            if (durationSec > 0) {
                lastPenDurationSec = (int) durationSec;
                if (healthWatchdogArmed) { healthWatchdogArmed = false; main.removeCallbacks(recordingHealthWatchdog); }
            }
            if (listener != null) main.post(() -> listener.onPenRecordDuration((int) durationSec));
        }
    };

    // ============ 后台上传 worker ============

    /** 该笔文件是否还需入队：已传过 或 已在队列/处理中 → 不再入队(去重)。空名/合成名一律放行。 */
    private boolean shouldEnqueue(String fn) {
        if (fn == null || fn.isEmpty()) return true;
        if (uploadedFileNames.contains(fn)) return false;
        for (UploadTask t : uploadQueue) if (t != null && fn.equals(t.fileName)) return false;
        UploadTask c = currentTask, i = inflightTask;
        if (c != null && fn.equals(c.fileName)) return false;
        if (i != null && fn.equals(i.fileName)) return false;
        return true;
    }

    /** 尝试推进后台队列：仅在笔没在录、且当前无任务在跑时，取队首处理。 */
    private void kickWorker() {
        main.post(() -> {
            if (workerBusy) return;
            if (uploadQueue.isEmpty()) { notifyPending(); return; }
            final long now = System.currentTimeMillis();
            final boolean penBusy = (penRecording || sessionActive || appStartPending);
            // ★A1(v24)：不再只盯队头——队头若是"退避冷却中"的下载任务(常见：笔上没有的僵尸文件，退避最长10min)，
            //   原来整个 worker 就睡到它冷却结束，把后面【已就绪】的任务全饿死(手动重传/新段白等几分钟)。
            //   现改为遍历队列挑第一个能立刻干的：本地直传(不占蓝牙)最优先 → 再挑退避到期且笔空闲的下载任务。
            UploadTask chosen = null;
            for (UploadTask t : uploadQueue) {
                if (t != null && t != inflightTask && t.localOpsPath != null) { chosen = t; break; }
            }
            long earliestRetry = Long.MAX_VALUE;   // 没有就绪任务时，最近一个下载任务的重试时刻(用于排下次kick)
            if (chosen == null && !penBusy) {
                for (UploadTask t : uploadQueue) {
                    if (t == null || t == inflightTask || t.localOpsPath != null) continue;
                    long wait = t.nextAttemptWallMs - now;
                    if (wait <= 0) { chosen = t; break; }   // ★跳过仍在冷却的，挑第一个到期的——别被队头僵尸堵死
                    if (t.nextAttemptWallMs < earliestRetry) earliestRetry = t.nextAttemptWallMs;
                }
            }
            if (chosen == null) {
                // 没有可立刻处理的：有下载任务在冷却→按最近到期时刻排一次kick；笔忙则等录音结束的回调来kick
                if (!penBusy && earliestRetry != Long.MAX_VALUE) {
                    main.postDelayed(this::kickWorker, Math.min(Math.max(earliestRetry - now, 500L), 10 * 60 * 1000L));
                }
                notifyPending();
                return;
            }
            // 选中的是下载任务且太老(僵尸)→放弃+清占位，giveUpIfStale 内部已重新 kick，这里直接返回
            if (chosen.localOpsPath == null && giveUpIfStale(chosen)) return;
            workerBusy = true; currentTask = chosen;
            notifyPending();
            if (chosen.localOpsPath != null) {
                inflightTask = chosen;   // 直传阶段：去重 + pauseWorker 不打断
                Log.d(TAG, "后台开始【直传实时流opus】 file=" + chosen.fileName + " 剩余=" + uploadQueue.size());
                uploadLocalOps(chosen);
                return;
            }
            waitingForFile = true; pendingFileName = chosen.fileName;
            fileListAttempts = 0; downloadRetries = 0;
            Log.d(TAG, "后台开始处理 file=" + chosen.fileName + " 剩余=" + uploadQueue.size());
            try { AIRECBleManager.getInstance().fetchFileList(); } catch (Exception ignored) {}
        });
    }

    /** 网页「取消上传」→ 按 placeholderId 把这条任务从补传队列移除(录音仍在笔里，可日后重新取回)。修"上传中点了取消、App 后台还在空转重试"。 */
    public void cancelUpload(String placeholderIdStr) {
        main.post(() -> {
            long pid;
            try { pid = Long.parseLong(placeholderIdStr == null ? "" : placeholderIdStr.trim()); }
            catch (Exception e) { return; }
            if (pid <= 0) return;
            UploadTask target = null;
            for (UploadTask t : uploadQueue) if (t != null && t.placeholderId == pid) { target = t; break; }
            if (target == null) return;   // 不在队列(已传完/已被清)，网页那边删占位即可
            // 正在处理这条 → 复位 worker、停看门狗、取消在飞的下载(释放 SDK 下载锁)
            if (target == currentTask || target == inflightTask) {
                workerBusy = false; currentTask = null; inflightTask = null; waitingForFile = false;
                main.removeCallbacks(downloadStallWatch);
                try { AIRECBleManager.getInstance().cancelDownload(); } catch (Exception ignored) {}
            }
            uploadQueue.remove(target);
            persistPendingQueue();
            Log.d(TAG, "用户取消上传 placeholderId=" + pid + " file=" + target.fileName);
            penLog("★取消上传(用户) placeholderId=" + pid + " " + target.fileName);
            notifyPending();
            kickWorker();   // 推进队列里其他任务
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
            if (task.fileName != null) markUploaded(task.fileName);   // ★记下已传(持久化)，防重复扫描下载
            workerBusy = false; currentTask = null; inflightTask = null; waitingForFile = false;
            Log.d(TAG, "后台完成 file=" + task.fileName + " recId=" + recId + " 剩余=" + uploadQueue.size());
            if (recId > 0 && listener != null) listener.onPenUploaded(recId);
            notifyPending();
            kickWorker();
        });
    }

    /** 上传【临时】失败(网络抖/5xx)：不丢、不清占位，挪队尾、退避后重试。录音本体在笔/缓存里，迟早传上。 */
    private void requeueTransient(final UploadTask task) {
        main.post(() -> {
            task.uploadAttempts++;
            workerBusy = false; currentTask = null; inflightTask = null; waitingForFile = false;
            // 挪到队尾，别堵住后面的任务
            if (uploadQueue.remove(task)) uploadQueue.add(task);
            long delay = Math.min(120000, 5000L * task.uploadAttempts);   // 退避，封顶2分钟
            Log.w(TAG, "上传临时失败，第" + task.uploadAttempts + "次，" + (delay / 1000) + "s后重试 file=" + task.fileName);
            notifyPending();
            main.postDelayed(PenController.this::kickWorker, delay);
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
        persistPendingQueue();   // ★A1:队列一变就存盘，App被杀也能续传
        final int n = uploadQueue.size();
        final int f = failedCount;
        if (listener != null) main.post(() -> listener.onPenPendingChanged(n, f));
    }

    // ============ 下载完成后：解码 → 上传（后台，不抢占当前录音 UI） ============

    private void processAndUpload(final UploadTask task, final AIRECBleFile file, final String localPath) {
        final int durSec = task.durSec > 0 ? task.durSec
                : ((file != null && file.getDurationSec() > 0) ? (int) file.getDurationSec() : 0);
        worker.submit(() -> {
            try {
                if (TextUtils.isEmpty(localPath) || !new File(localPath).exists()) {
                    Log.w(TAG, "后台：下载文件不存在");
                    workerTaskFailed(task, "下载文件不存在", true);
                    return;
                }
                // ★v17 下载完整性自检：BLE 残缺/错位下载(SDK 自称成功却悄悄丢了「非整块字节」)会让 .ops 不是
                //   「整数个块」→ 转换器固定步长从断点起把后段全转成乱码噪声(顾问看似录好实则后段全乱)。
                //   提前逮住：删本地残件、挪队尾重新下载，绝不转码上传垃圾(2h 内换好窗口凑成；音频在笔上不丢)。
                //   integrityWarning 是 fail-open(任何不确定都返回 null 放行)，最坏只多重下一次，不误伤好文件。
                long _actual = new File(localPath).length();
                long _reported = (file != null) ? file.getFileSize() : 0;
                // ★完整性判据 = 下载字节数 vs 笔机身文件大小：相等就是完整。
                //   绝不能用"长度非80整数倍"判残缺——KA .ops 长度本就不一定是80整数倍(有头/尾字节)，
                //   那会把【完整下载】无限误判成残缺、反复重下，录音永远存不上(v17~v21 的严重 bug，本次修正)。
                //   只在【本地明显少于笔报大小】=确实没传完时才判残缺重下；笔没报大小(=0)则放行(fail-open)。
                boolean _truncated = (_reported > 4096 && _actual > 0 && _actual < _reported - 64);
                penLog("★下载校验 " + task.fileName + " 本地=" + _actual + "B 笔报=" + _reported + "B "
                        + (_truncated ? ("残缺(少" + (_reported - _actual) + "B)") : "ok"));
                if (_truncated) {
                    try { new File(localPath).delete(); } catch (Exception ignore) {}
                    Log.w(TAG, "下载残缺(本地" + _actual + "<笔报" + _reported + ") → 挪队尾重下 " + task.fileName);
                    requeueDownloadTask(task, "下载残缺(" + _actual + "/" + _reported + ")");
                    return;
                }
                String uploadPath, name, mime;
                // ★官方转换：下载到的 KA/ATW 私有 opus → 标准 OGG Opus(不解码、不放大、后端不用解)
                String ogg = ATWOpusConverter.convert(localPath);
                if (ogg != null && new File(ogg).exists() && new File(ogg).length() > 64) {
                    uploadPath = ogg;
                    name = stripExt(baseName(localPath)) + ".ogg";
                    mime = "audio/ogg";
                } else {
                    // 兜底：转换失败就按原扩展名直传(后端能认就行)
                    String ext = extOf(localPath);
                    if (isAccepted(ext)) {
                        uploadPath = localPath; name = baseName(localPath); mime = mimeFor(ext);
                    } else {
                        Log.w(TAG, "后台：转换失败且未知格式，放弃 " + localPath);
                        workerTaskFailed(task, "转换失败/未知格式", true);
                        return;
                    }
                }
                Uploader.Result r = Uploader.upload(new File(uploadPath), durSec,
                        cookieNow(task), task.uploadUrl, name, mime, task.sn, task.placeholderId, fmtWall(task.startWallMs), task.fileName);
                writeProbeStatus("[补下载兜底ogg] file=" + name + " ok=" + r.ok + " recId=" + r.recordingId + " err=" + r.error);
                if (r.ok) {
                    try { if (!uploadPath.equals(localPath)) new File(uploadPath).delete(); new File(localPath).delete(); } catch (Exception ignore) {}
                    workerTaskDone(task, r.recordingId);
                } else if (r.needsReauth) {
                    // ★登录失效：绝不丢音频。保留下载的原文件+占位，留队等重登后用新 cookie 自动重传。
                    try { if (!uploadPath.equals(localPath)) new File(uploadPath).delete(); } catch (Exception ignore) {}  // 只删ogg临时产物
                    task.localOpsPath = localPath;   // 重试走本地直传，不再重新下载
                    Log.w(TAG, "兜底上传需重登(留队等重登，不丢)：" + r.error);
                    requeueTransient(task);
                } else if (r.transientFail) {
                    try { if (!uploadPath.equals(localPath)) new File(uploadPath).delete(); } catch (Exception ignore) {}  // 删ogg，留下载的原文件
                    task.localOpsPath = localPath;   // 重试走本地直传，不再重新下载
                    Log.w(TAG, "兜底上传临时失败(重试)：" + r.error);
                    requeueTransient(task);
                } else {
                    try { if (!uploadPath.equals(localPath)) new File(uploadPath).delete(); new File(localPath).delete(); } catch (Exception ignore) {}
                    Log.w(TAG, "兜底上传永久失败(放弃)：" + r.error);
                    workerTaskFailed(task, "upload rejected", true);
                }
            } catch (Exception e) {
                Log.e(TAG, "processAndUpload failed", e);
                requeueTransient(task);   // 异常当临时，重试(别丢)
            }
        });
    }

    /** 从上传地址推导占位接口地址：.../api/consultant/upload → .../api/consultant/placeholder。 */
    private static String placeholderUrlFrom(String uploadUrl) {
        if (uploadUrl == null) return null;
        if (uploadUrl.endsWith("/upload")) return uploadUrl.substring(0, uploadUrl.length() - 7) + "/placeholder";
        return uploadUrl.replace("/upload", "/placeholder");
    }

    /** 从上传地址推导同步预览接口：.../api/consultant/upload → .../api/consultant/pen/sync-preview。 */
    private static String syncPreviewUrlFrom(String uploadUrl) {
        if (uploadUrl == null) return null;
        if (uploadUrl.endsWith("/upload")) return uploadUrl.substring(0, uploadUrl.length() - 7) + "/pen/sync-preview";
        return uploadUrl.replace("/upload", "/pen/sync-preview");
    }

    /** #4 检测笔上"未传"段并提示(不自动传，避免复活已删/时长bug=v6关扫描补传的坑)：连上空闲拉到文件列表后，
     *  收集候选(非已传/非正在录/非队列/2天内/非太新)，问后端 sync-preview 得准确未传数，推网页提示去手动取回。 */
    private void detectUnsynced(List<AIRECBleFile> files) {
        if (files == null || files.isEmpty()) return;
        if (penRecording || sessionActive || appStartPending) return;
        if (cookie == null || uploadUrl == null) return;
        final long now = System.currentTimeMillis();
        final org.json.JSONArray items = new org.json.JSONArray();
        for (AIRECBleFile f : files) {
            if (f == null) continue;
            String name = f.getFileName();
            if (name == null || name.isEmpty()) continue;
            if (uploadedFileNames.contains(name)) continue;       // 已传
            if (name.equals(sessionFileName)) continue;            // 正在录
            if (isQueuedByName(name)) continue;                    // 已在补传队列(会自动传,不算"待手动")
            long startMs = parsePenFileStartMs(name, f);
            if (startMs <= 0) continue;
            if ((now - startMs) > SWEEP_WINDOW_MS) continue;       // 太老(只看最近2天)
            if ((now - startMs) < 60_000) continue;                // 太新(可能还在写)
            try {
                org.json.JSONObject it = new org.json.JSONObject();
                it.put("name", name);
                it.put("ra", fmtWall(startMs));
                items.put(it);
            } catch (Exception ignore) {}
        }
        if (items.length() == 0) { if (listener != null) main.post(() -> listener.onPenUnsynced(0)); return; }
        final String spUrl = syncPreviewUrlFrom(uploadUrl);
        final String ck = cookie;
        worker.submit(() -> {
            int n = Uploader.syncPreviewNewCount(ck, spUrl, items);   // 出错返回 -1
            if (n >= 0 && listener != null) main.post(() -> listener.onPenUnsynced(n));
        });
    }

    // ============ 录音笔SN绑定校验 ============

    private static String reportSnUrlFrom(String uploadUrl) {
        if (uploadUrl == null) return null;
        if (uploadUrl.endsWith("/upload")) return uploadUrl.substring(0, uploadUrl.length() - 7) + "/pen/report-sn";
        return uploadUrl.replace("/upload", "/pen/report-sn");
    }
    /** 连上验证(verified)后：读SN→问后端准不准用这台。主线程调用。 */
    private void verifyPenAllowed() {
        final int gen = ++snVerifyGen;
        final String url = reportSnUrlFrom(uploadUrl);
        final String ck = cookie;
        final String mac = currentMac;
        if (url == null || ck == null) return;   // 没上传上下文 → 没法校验，维持现状(fail-open)
        verifyPenSnStep(gen, mac, url, ck, 0);
    }
    /** SN 在连上后可能略滞后于握手，读不到就退避重试几次(最多 ~2s)。 */
    private void verifyPenSnStep(final int gen, final String mac, final String url, final String ck, final int attempt) {
        if (gen != snVerifyGen) return;   // 已换连接，作废
        String sn = penSn();
        if (sn == null || sn.isEmpty()) {
            if (attempt < 8) main.postDelayed(() -> verifyPenSnStep(gen, mac, url, ck, attempt + 1), 250);
            return;   // 读不到SN → 维持现状(fail-open)
        }
        final String fsn = sn;
        worker.submit(() -> {
            final Uploader.SnVerdict v = Uploader.reportSn(ck, url, fsn);
            main.post(() -> {
                if (gen != snVerifyGen) return;   // 期间换了连接，作废
                if (v == null) { penAllowed = true; return; }   // 网络/服务端错 → fail-open，不挡录音
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
                    try { AIRECBleManager.getInstance().disconnect(); } catch (Exception ignored) {}
                }
            });
        });
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
    /** 笔报"正在录"的文件名时间戳是否很旧(>6h前=连上时笔的残留/幽灵状态，非真在录)。 */
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
