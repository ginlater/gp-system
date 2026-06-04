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

import com.airec.bledemo.recording.PhoneMicService;
import com.airec.bledemo.recording.RecordingBus;

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

        webView.addJavascriptInterface(new WebAppBridge(this), "AndroidBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest req) {
                return handleExternalUrl(req.getUrl());
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
                pendingRecordAfterConnect = false;
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
                openScan();
                return;
            }
            CookieManager.getInstance().flush();
            activeSource = "pen";
            recordingStartMs = SystemClock.elapsedRealtime();
            penPausedAtMs = 0;
            applyState("recording", "录音中…（录音笔）");
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
        if ("pen".equals(activeSource)) {
            if (penController != null) penController.stopRecording();
            applyState("uploading", "正在保存录音笔文件…");
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
            // ★只在"用户刚点了开始"时才据此动作；否则笔自发的录音状态（声控等）一律不改 UI
            //   （连上会自动关声控，几秒后笔就不再自发录音）。
            if (!pendingRecordAfterConnect) return;
            pendingRecordAfterConnect = false;
            if (recording) {
                // 用户点了开始、连上发现笔已在录 → 采纳为本次会话
                activeSource = "pen";
                if (recordingStartMs == 0) recordingStartMs = SystemClock.elapsedRealtime();
                if (penController != null) {
                    penController.adoptRecording(
                            CookieManager.getInstance().getCookie(START_URL), uploadUrlFor(START_URL));
                }
                applyState("recording", "录音中…（录音笔）");
            } else {
                // 笔空闲 → 正常开始新录音
                startRecordingInternal("pen");
            }
        });
    }

    /** 录音笔上报的当前已录时长 → 校准计时并推给网页，同步进度。 */
    @Override
    public void onPenRecordDuration(int durationSec) {
        ui.post(() -> {
            if (durationSec > 0 && "recording".equals(state) && "pen".equals(activeSource)) {
                recordingStartMs = SystemClock.elapsedRealtime() - durationSec * 1000L;
                pushStateToWeb("recording", null);
            }
        });
    }

    /** 录音笔连接状态变化 → 通知网页刷新连接指示。 */
    @Override
    public void onPenConnected(boolean connected) {
        ui.post(() -> {
            evalJs("if(window.__onPenConnChanged){window.__onPenConnChanged(" + connected + ");}");
            if (connected) Toast.makeText(this, "录音笔已连接", Toast.LENGTH_SHORT).show();
        });
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
            case PhoneMicService.STATE_RECORDING:
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
                applyState("idle", message);
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
        return (penController != null && penController.isConnected()) ? "phone,pen" : "phone";
    }
    @Override public boolean bridgeIsPenConnected() {
        return penController != null && penController.isConnected();
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
                + (penController != null && penController.isConnected()) + ");}");
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
}
