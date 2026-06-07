package com.airec.bledemo;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.Toast;

import android.app.AlertDialog;

import com.airec.bledemo.recording.PhoneMicService;
import com.airec.bledemo.recording.RecordingBus;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 接诊 WebView 壳（启动页）。
 *
 * 设计：录音的「可见 UI」全部在网页里（登录后的「美丽陪伴」页的录音按钮 + 右上角来源切换）。
 * 本 Activity 只是全屏 WebView + 看不见的录音引擎：网页通过 window.AndroidBridge 驱动
 * 手机麦克风（PhoneMicService）或蓝牙录音笔（PenController），录音状态再回推给网页。
 * ——所以这里没有任何原生录音条，不会在登录页等每个页面都显示一套录音按钮。
 */
public class ConsultantActivity extends Activity
        implements WebAppBridge.Host, RecordingBus.Listener, PenController.Listener {

    // ★ 唯一需要确认的配置：接诊页地址。https 打不开就改成 http://<服务器IP>/gp/consultant 之类。
    private static final String START_URL = "https://gp.aibeautyfulwomen.com/consultant";

    private static final int REQ_PERMS = 1001;
    private static final int REQ_FILE_CHOOSER = 1002;
    private static final int REQ_SCAN = 1003;

    private WebView webView;
    private ProgressBar progress;

    private ValueCallback<Uri[]> filePathCallback;
    private PermissionRequest pendingWebPermission;

    private String state = "idle";           // idle / recording / uploading
    private String activeSource = null;      // 正在录音的来源：phone / pen
    private long recordingStartMs = 0;
    private boolean batteryAsked = false;
    private long lastVersionCheckMs = 0;                  // 强制更新检查节流
    private AlertDialog forceUpdateDialog;                // 强制更新弹窗(不可关)
    private boolean pendingSelectPenAfterConnect = false;
    private boolean pendingRecordAfterConnect = false;   // 录音笔连上后是否自动开始录音
    private long penPausedAtMs = 0;                       // 暂停起始时刻，用于继续时扣掉暂停时长
    private PenController penController;

    private final Handler ui = new Handler(Looper.getMainLooper());

    // 连上录音笔后、若录音状态查询迟迟不回来的兜底：按空闲开新录音。
    private final Runnable penRecordFallback = new Runnable() {
        @Override public void run() {
            if (pendingRecordAfterConnect && penController != null && penController.isConnected()) {
                pendingRecordAfterConnect = false;
                startRecordingInternal("pen");
            } else if (pendingRecordAfterConnect) {
                // 连上后又断开/查询不回：清掉残留"要录音"意图(防泄漏到下次连接幽灵开录)，并把网页解锁到可重试。
                pendingRecordAfterConnect = false;
                applyState("error", "录音笔连接不稳定，请确认它已开机靠近后重试");
            }
        }
    };

    private static String uploadUrlFor(String startUrl) {
        // 去掉结尾 /consultant，拼 /api/consultant/upload；兼容根路径或带 /gp 前缀的部署
        String base = startUrl.replaceAll("/consultant/?$", "");
        return base + "/api/consultant/upload";
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_consultant);

        webView = findViewById(R.id.webview);
        progress = findViewById(R.id.progress);

        penController = new PenController(getApplicationContext(), this);

        configureWebView();
        requestRuntimePermissions();

        if (savedInstanceState == null) {
            webView.loadUrl(START_URL);
        } else {
            webView.restoreState(savedInstanceState);
        }
    }

    // ============ WebView ============

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void configureWebView() {
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setCacheMode(isOnline() ? WebSettings.LOAD_DEFAULT : WebSettings.LOAD_CACHE_ELSE_NETWORK);  // 离线冷启时尽量用缓存
        registerNetworkMonitor();

        webView.addJavascriptInterface(new WebAppBridge(this), "AndroidBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest req) {
                // ★离线时拦截整页跳转/刷新 → 保住当前录音界面不白屏；
                //   数据请求(XHR/fetch)不是整页跳转，照常发、各自报网络错(这正是你要的)。
                if (req != null && req.isForMainFrame() && !isOnline()) {
                    android.widget.Toast.makeText(ConsultantActivity.this, "网络断开，该操作暂不可用", android.widget.Toast.LENGTH_SHORT).show();
                    return true;
                }
                return handleExternalUrl(req.getUrl());
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                pageLoadFailed = false;
            }
            @Override public void onReceivedError(WebView view, android.webkit.WebResourceRequest req,
                                                  android.webkit.WebResourceError err) {
                // 仅记录主页面失败(如冷启动时就离线)，供网络恢复后自动重载；不替换页面、不弹遮罩。
                if (req != null && req.isForMainFrame()) pageLoadFailed = true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int p) {
                progress.setProgress(p);
                progress.setVisibility(p >= 100 ? android.view.View.GONE : android.view.View.VISIBLE);
            }
            @Override public void onPermissionRequest(PermissionRequest request) {
                pendingWebPermission = request;
                if (hasAudioPermission()) {
                    request.grant(request.getResources());
                    pendingWebPermission = null;
                } else {
                    requestRuntimePermissions();
                }
            }
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = cb;
                try {
                    startActivityForResult(params.createIntent().addCategory(Intent.CATEGORY_OPENABLE), REQ_FILE_CHOOSER);
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });
    }

    // ============ 离线优雅处理（断网保住录音界面、不白屏、恢复自动重载） ============
    private volatile boolean pageLoadFailed = false;
    private android.net.ConnectivityManager.NetworkCallback netCallback;

    private boolean isOnline() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            android.net.Network n = cm.getActiveNetwork();
            if (n == null) return false;
            android.net.NetworkCapabilities c = cm.getNetworkCapabilities(n);
            return c != null && c.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) { return true; }
    }

    private void reloadPage() {
        pageLoadFailed = false;
        try {
            webView.getSettings().setCacheMode(isOnline() ? WebSettings.LOAD_DEFAULT : WebSettings.LOAD_CACHE_ELSE_NETWORK);
        } catch (Exception ignored) {}
        webView.loadUrl(START_URL);
    }

    /** 网络恢复 → 若之前加载失败，自动重载页面。 */
    private void registerNetworkMonitor() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            netCallback = new android.net.ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(android.net.Network n) {
                    runOnUiThread(() -> {
                        if (penController != null) penController.onNetworkAvailable();   // 待传录音立刻补传
                        if (pageLoadFailed) reloadPage();
                    });
                }
            };
            cm.registerDefaultNetworkCallback(netCallback);
        } catch (Exception e) { android.util.Log.e("Consultant", "registerNetworkMonitor failed", e); }
    }

    private boolean handleExternalUrl(Uri uri) {
        String scheme = uri.getScheme();
        if ("http".equals(scheme) || "https".equals(scheme)) return false;
        try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Exception ignored) {}
        return true;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE_CHOOSER && filePathCallback != null) {
            filePathCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            filePathCallback = null;
        } else if (requestCode == REQ_SCAN) {
            boolean connected = penController != null && penController.isConnected();
            pendingSelectPenAfterConnect = false;
            // 通知网页刷新右上角来源切换的连接状态
            evalJs("if(window.__onPenConnChanged){window.__onPenConnChanged(" + connected + ");}");
            if (connected) {
                penController.activate();
                Toast.makeText(this, "录音笔已连接", Toast.LENGTH_SHORT).show();
                // 不立即开录：等 onPenRecordStatus 查询回来再决定——
                // 笔已在录(用户退出App期间)则同步为录音中，否则才开新录音。兜底 4s。
                if (pendingRecordAfterConnect) {
                    ui.removeCallbacks(penRecordFallback);
                    ui.postDelayed(penRecordFallback, 4000);
                }
            } else {
                // 没连上/取消：复位意图标志，并把"正在连接"锁住的网页解锁回可重试态（A配B，缺一会变永久卡disabled）。
                boolean wantedRecord = pendingRecordAfterConnect;
                pendingRecordAfterConnect = false;
                ui.removeCallbacks(penRecordFallback);
                activeSource = null;
                if (wantedRecord && "starting".equals(state)) {
                    applyState("error", "没连上录音笔，请确认它已开机后重试");
                } else if ("starting".equals(state)) {
                    applyState("idle", null);
                }
            }
        }
    }

    // ============ 录音引擎控制（由网页通过桥驱动）============

    private void startRecordingInternal(String source) {
        if (source == null) source = "phone";
        if ("pen".equals(source)) {
            if (penController == null || !penController.isConnected()) {
                pendingSelectPenAfterConnect = true;
                pendingRecordAfterConnect = true;   // 用户意图是录音：连上后自动开录
                activeSource = "pen";               // 让后续 stop 走笔分支
                applyState("starting", "正在连接录音笔…");  // 锁住按钮(uploading=true)，避免连接窗口里反复点
                openScan();
                return;
            }
            CookieManager.getInstance().flush();
            activeSource = "pen";
            recordingStartMs = 0;   // 计时起点等"笔确认开录"时再设（见 onStatus STATE_RECORDING）
            penPausedAtMs = 0;
            // ★先显示"启动中"，不乐观显示"录音中"：等笔回确认才转录音（防笔休眠空录、防传旧录音）。
            applyState("starting", "正在唤醒录音笔…");
            penController.startRecording(
                    CookieManager.getInstance().getCookie(START_URL), uploadUrlFor(START_URL));
            return;
        }

        // 手机麦克风
        if (!hasAudioPermission()) {
            requestRuntimePermissions();
            Toast.makeText(this, "请先允许麦克风权限", Toast.LENGTH_SHORT).show();
            evalJs("if(window.__onNativeRecState){window.__onNativeRecState('idle','请允许麦克风权限',0);}");
            return;
        }
        maybeAskBatteryExemption();
        CookieManager.getInstance().flush();
        activeSource = "phone";
        Intent i = new Intent(this, PhoneMicService.class);
        i.setAction(PhoneMicService.ACTION_START);
        i.putExtra(PhoneMicService.EXTRA_UPLOAD_URL, uploadUrlFor(START_URL));
        startService(i);
        recordingStartMs = SystemClock.elapsedRealtime();
        applyState("recording", "录音中…");
    }

    private void stopRecordingInternal() {
        // 默认归录音笔结束：笔自发录音时 activeSource 可能因 BLE 抖动还没设成"pen"，也必须走笔的 endRecord，
        // 否则误走手机麦支会导致笔从未收到 endRecord、该段丢失。只有显式手机麦(activeSource=="phone")才走下面。
        if (!"phone".equals(activeSource)) {
            if (penController != null) penController.stopRecording();
            else applyState("idle", "已结束");
            return;
        }
        CookieManager.getInstance().flush();
        Intent i = new Intent(this, PhoneMicService.class);
        i.setAction(PhoneMicService.ACTION_STOP);
        i.putExtra(PhoneMicService.EXTRA_COOKIE, CookieManager.getInstance().getCookie(START_URL));
        i.putExtra(PhoneMicService.EXTRA_UPLOAD_URL, uploadUrlFor(START_URL));
        startService(i);
        applyState("uploading", "上传中…");
    }

    private void openScan() {
        if (penController != null) penController.activate(); // 连上后回调切回 PenController
        try {
            startActivityForResult(new Intent(this, ScanActivity.class), REQ_SCAN);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开录音笔连接页：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** PenController.Listener：录音笔未连接，去连接。 */
    @Override
    public void onPenNeedConnect() {
        ui.post(() -> {
            pendingSelectPenAfterConnect = true;
            openScan();
        });
    }

    /**
     * 连上录音笔后查到的录音状态：
     * - 笔已在录（用户退出 App 期间笔仍在物理录音）→ 同步为「录音中」，补齐上传上下文，不新开录音；
     * - 笔空闲且用户刚点了开启陪伴 → 现在开始新录音。
     */
    @Override
    public void onPenRecordStatus(boolean recording) {
        ui.post(() -> {
            ui.removeCallbacks(penRecordFallback);
            // 确保上传上下文可用（笔自发录音也要用同样的 cookie/上传地址）
            if (penController != null) penController.setUploadContext(
                    CookieManager.getInstance().getCookie(START_URL), uploadUrlFor(START_URL));
            if (!pendingRecordAfterConnect) return;
            pendingRecordAfterConnect = false;
            if (recording) {
                // 笔已经在录（镜像已由 PenController 据状态查询显示）→ 只标记来源，不再发开始命令
                activeSource = "pen";
                // 用户确实点了开始 → 把这段升级为 app-initiated，失败时大声提示而非当空录静默丢
                if (penController != null) penController.markCurrentSessionAppInitiated();
            } else {
                // 笔空闲、用户刚点了开始 → 发开始命令
                startRecordingInternal("pen");
            }
        });
    }

    /** 录音笔上报的当前已录时长 → 校准计时并推给网页，同步进度。 */
    @Override
    public void onPenRecordDuration(int durationSec) {
        ui.post(() -> {
            if (durationSec > 0 && "recording".equals(state) && "pen".equals(activeSource)) {
                // ⚠️ 只向前对齐，绝不回退。声控模式下笔每段时长会归零(1,2→新段1,2…)，
                //    若每次都按笔报的段时长回退同步，会把计时卡在 0~2 秒走不动。
                //    计时主要靠网页墙上钟自走；这里仅当笔报的时长明显比当前显示更长时才前跳
                //    （用于重连到一支已经在录的笔时补上已录进度）。
                int cur = elapsedSec();
                if (durationSec > cur + 2) {
                    recordingStartMs = SystemClock.elapsedRealtime() - durationSec * 1000L;
                    pushStateToWeb("recording", null);
                }
            }
        });
    }

    /** 录音笔连接状态变化 → 通知网页刷新连接指示。 */
    @Override
    public void onPenConnected(boolean connected) {
        ui.post(() -> {
            evalJs("if(window.__onPenConnChanged){window.__onPenConnChanged(" + connected + ");}");
            if (connected) {
                // 连上就把上传上下文给 PenController（笔自发录音/后台上传都要用）
                if (penController != null) penController.setUploadContext(
                        CookieManager.getInstance().getCookie(START_URL), uploadUrlFor(START_URL));
                Toast.makeText(this, "录音笔已连接", Toast.LENGTH_SHORT).show();
            } else if (pendingRecordAfterConnect) {
                // 还没把"要录音"落地就断了 → 清标志(防泄漏)、撤兜底、网页回可用态
                pendingRecordAfterConnect = false;
                ui.removeCallbacks(penRecordFallback);
                if ("starting".equals(state)) applyState("idle", null);
            }
        });
    }

    private int lastFailedCount = 0;
    /** 后台待传/在传段数 + 失败段数变化 → 推给网页指示；新增失败时弹一次提示，确保用户知道没保存成功。 */
    @Override
    public void onPenPendingChanged(int pending, int failed) {
        ui.post(() -> {
            evalJs("if(window.__onPenPending){window.__onPenPending(" + pending + "," + failed + ");}");
            if (failed > lastFailedCount) {
                Toast.makeText(this, "有 " + failed + " 段录音没能保存（录音笔可能没存上），请检查录音笔后重录",
                        Toast.LENGTH_LONG).show();
            }
            lastFailedCount = failed;
        });
    }

    /** 一段后台上传成功 → 只刷新未归档列表（不自动弹绑定，避免打断正在录的下一段）。 */
    @Override
    public void onPenUploaded(long recordingId) {
        ui.post(() -> evalJs("if(window.__onPenUploaded){window.__onPenUploaded(" + recordingId + ");}"));
    }

    /** 已建占位片段 → 刷新未归档列表，让它带服务日期立刻显示("处理中")。 */
    @Override
    public void onPenPlaceholderCreated() {
        ui.post(() -> evalJs("if(window.__onPenUploaded){window.__onPenUploaded(0);}"));
    }

    /** 后台下载进度 → 推给网页 badge 显示"保存中 N%"。 */
    @Override
    public void onPenProgress(int percent) {
        ui.post(() -> evalJs("if(window.__onPenProgress){window.__onPenProgress(" + percent + ");}"));
    }

    /** 暂停/继续状态变化 → 推给网页（paused / recording）。 */
    @Override
    public void onPenPaused(boolean paused) {
        ui.post(() -> {
            if (!"recording".equals(state) && !"paused".equals(state)) return;
            if (paused) {
                penPausedAtMs = SystemClock.elapsedRealtime();
                applyState("paused", null);
            } else {
                // 继续：把暂停期间的墙上时间从计时起点里扣掉，避免计时跳变
                // （笔的实际录音已自动扣掉暂停，这里让显示与之一致）。
                if (penPausedAtMs > 0 && recordingStartMs > 0) {
                    recordingStartMs += (SystemClock.elapsedRealtime() - penPausedAtMs);
                }
                penPausedAtMs = 0;
                applyState("recording", null);
            }
        });
    }

    /** 只维护状态并回推给网页（无原生 UI）。 */
    private void applyState(String newState, String message) {
        state = newState;
        pushStateToWeb(newState, message);
    }

    private void pushStateToWeb(String st, String message) {
        String safe = message == null ? "" : message.replace("\\", "\\\\").replace("'", "\\'");
        evalJs("if(window.__onNativeRecState){window.__onNativeRecState('" + st + "','" + safe + "'," + elapsedSec() + ");}");
    }

    private void evalJs(String js) {
        if (webView == null) return;
        webView.evaluateJavascript(js, null);
    }

    private int elapsedSec() {
        if (recordingStartMs == 0) return 0;
        return (int) Math.max(0, (SystemClock.elapsedRealtime() - recordingStartMs) / 1000);
    }

    // ============ RecordingBus 回调（来自录音引擎的真实状态）============

    @Override
    public void onStatus(String st, String message, int durSec, long recId) {
        switch (st) {
            case PhoneMicService.STATE_STARTING:
                // 已发开始命令、等笔确认（不起计时）。
                applyState("starting", message);
                break;
            case PhoneMicService.STATE_RECORDING:
                // 笔自发录音(镜像)时 activeSource 可能还没设 → 这里补上"pen"，让结束/计时走录音笔路径。
                if (activeSource == null && penController != null && penController.isConnected()) activeSource = "pen";
                if (recordingStartMs == 0) recordingStartMs = SystemClock.elapsedRealtime() - durSec * 1000L;
                applyState("recording", message);
                break;
            case PhoneMicService.STATE_UPLOADING:
                applyState("uploading", message);
                break;
            case PhoneMicService.STATE_IDLE:
                recordingStartMs = 0;
                activeSource = null;
                penPausedAtMs = 0;
                applyState("idle", message);
                if (recId >= 0) {
                    evalJs("if(window.__onNativeUploaded){window.__onNativeUploaded(" + recId + ");}");
                }
                break;
            case PhoneMicService.STATE_ERROR:
                recordingStartMs = 0;
                activeSource = null;
                penPausedAtMs = 0;
                applyState("error", message);
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                break;
            default:
                break;
        }
    }

    // ============ WebAppBridge.Host（网页调用）============

    @Override public void bridgeStartRecording(final String source) {
        ui.post(() -> startRecordingInternal(source == null ? "phone" : source));
    }
    @Override public void bridgeStopRecording() {
        ui.post(this::stopRecordingInternal);
    }
    @Override public void bridgePauseRecording() {
        ui.post(() -> {
            if ("recording".equals(state) && "pen".equals(activeSource) && penController != null) penController.pause();
        });
    }
    @Override public void bridgeResumeRecording() {
        ui.post(() -> {
            if ("paused".equals(state) && "pen".equals(activeSource) && penController != null) penController.resume();
        });
    }
    @Override public String bridgeGetState() { return state; }
    @Override public String bridgeGetSources() {
        return (penController != null && penController.isPenAlive()) ? "phone,pen" : "phone";
    }
    @Override public String bridgePendingInfo() {
        if (penController == null) return "0,0";
        return penController.pendingCount() + "," + penController.pendingFailedCount();
    }
    @Override public boolean bridgeIsPenConnected() {
        // ★用"真在线"(近期有回包)，不用裸 isConnected()(只看GATT指针，会把假连接也当已连)
        return penController != null && penController.isPenAlive();
    }
    @Override public void bridgeConnectPen() {
        ui.post(() -> { pendingSelectPenAfterConnect = true; openScan(); });
    }
    @Override public void bridgeOpenPenManager() {
        ui.post(() -> startActivity(new Intent(this, MainActivity.class)));
    }

    // ============ 权限 ============

    private boolean hasAudioPermission() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestRuntimePermissions() {
        java.util.List<String> need = new java.util.ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        // 蓝牙：用于开机自动连接录音笔
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.BLUETOOTH_SCAN);
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_PERMS);
    }

    private boolean hasBlePermission() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /** App 打开/回到前台：录音笔没连就静默自动连上次那支笔（不弹扫描页、不用手点）。 */
    private void maybeAutoConnectPen() {
        if (penController == null || penController.isConnected()) return;
        if (!hasBlePermission()) return;
        String mac = getSharedPreferences("pen_prefs", MODE_PRIVATE).getString("last_mac", null);
        if (mac != null) penController.autoConnect(mac);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMS && pendingWebPermission != null && hasAudioPermission()) {
            pendingWebPermission.grant(pendingWebPermission.getResources());
            pendingWebPermission = null;
        }
    }

    /** 引导加入电池优化白名单，降低被国产 ROM 杀进程概率（每次启动最多问一次）。 */
    private void maybeAskBatteryExemption() {
        if (batteryAsked) return;
        batteryAsked = true;
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent it = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName()));
                startActivity(it);
            }
        } catch (Exception ignored) {}
    }

    // ============ 强制更新 ============

    /** 取本机已安装的 versionCode；取不到返回 -1（→ fail-open，不挡用户）。 */
    private int installedVersionCode() {
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return (int) pi.getLongVersionCode();
            return pi.versionCode;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 启动/回前台查后端 /api/app/version：装的 versionCode < 服务器 minVersionCode → 弹不可关的强制更新框。
     * 关键设计：网络/解析任何失败一律【放过】(fail-open) —— 后端抖一下不能把所有顾问挡在门外。
     */
    private void checkForceUpdate() {
        if (forceUpdateDialog != null && forceUpdateDialog.isShowing()) return;   // 已在挡，不重复查
        long now = SystemClock.elapsedRealtime();
        if (now - lastVersionCheckMs < 20000) return;   // 20s 内不重复查
        lastVersionCheckMs = now;
        final int installed = installedVersionCode();
        if (installed <= 0) return;   // 取不到本机版本 → 不挡
        final String base = START_URL.replaceAll("/consultant/?$", "");
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(base + "/api/app/version");
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("Accept", "application/json");
                if (conn.getResponseCode() != 200) return;   // fail-open
                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String ln;
                    while ((ln = br.readLine()) != null) sb.append(ln);
                }
                JSONObject o = new JSONObject(sb.toString());
                int minCode = o.optInt("minVersionCode", 0);
                final String latestName = o.optString("latestVersionName", "");
                final String note = o.optString("updateNote", "");
                String apkUrl = o.optString("apkUrl", "/download/app.apk");
                if (!apkUrl.startsWith("http")) apkUrl = base + apkUrl;
                final String fApkUrl = apkUrl;
                if (installed < minCode) {
                    ui.post(() -> showForceUpdateDialog(latestName, note, fApkUrl));
                }
            } catch (Exception e) {
                android.util.Log.w("ConsultantActivity", "版本检查失败(放过): " + e.getMessage());   // fail-open
            } finally {
                if (conn != null) conn.disconnect();
            }
        }, "ver-check").start();
    }

    /** 不可关的强制更新框：点「立即更新」打开浏览器下载新包，但【不关框】，逼用户升级后才能继续用。 */
    private void showForceUpdateDialog(String latestName, String note, String apkUrl) {
        if (isFinishing() || (Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;
        if (forceUpdateDialog != null && forceUpdateDialog.isShowing()) return;
        String msg = (note != null && !note.isEmpty())
                ? note
                : ("发现新版本" + (latestName.isEmpty() ? "" : " " + latestName) + "，必须更新后才能继续使用。");
        forceUpdateDialog = new AlertDialog.Builder(this)
                .setTitle("需要更新")
                .setMessage(msg)
                .setCancelable(false)                  // 返回键关不掉
                .setPositiveButton("立即更新", null)    // 监听器下面覆写，避免点完自动关框
                .create();
        forceUpdateDialog.setCanceledOnTouchOutside(false);
        forceUpdateDialog.show();
        forceUpdateDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl)));
            } catch (Exception e) {
                Toast.makeText(this, "打开下载失败，请用浏览器访问 " + apkUrl, Toast.LENGTH_LONG).show();
            }
            // 故意不 dismiss：用户装完新版重开 App 即是新版本，本框不再出现；没装则下次回前台仍挡。
        });
    }

    // ============ 生命周期 ============

    @Override
    protected void onResume() {
        super.onResume();
        RecordingBus.setListener(this);
        // 回到接诊页：选了/连了录音笔则夺回 SDK 回调
        if (penController != null && penController.isConnected()) {
            penController.activate();
        } else {
            // 没连：静默自动连接上次那支笔（开机自动连，无需手点）
            maybeAutoConnectPen();
        }
        // 把当前录音状态与录音笔连接状态同步回网页
        evalJs("if(window.__onNativeRecState){window.__onNativeRecState('"
                + RecordingBus.lastState + "','',"
                + (recordingStartMs == 0 ? 0 : elapsedSec()) + ");}"
                + "if(window.__onPenConnChanged){window.__onPenConnChanged("
                + (penController != null && penController.isPenAlive()) + ");}");
        checkForceUpdate();   // ★每次回前台查一次：版本过低 → 弹不可关的强制更新框
    }

    @Override
    protected void onPause() {
        super.onPause();
        RecordingBus.clear(this);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) { webView.goBack(); return; }
        super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) webView.saveState(outState);
    }

    @Override
    protected void onDestroy() {
        if (netCallback != null) {
            try {
                android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                cm.unregisterNetworkCallback(netCallback);
            } catch (Exception ignored) {}
            netCallback = null;
        }
        super.onDestroy();
    }
}
