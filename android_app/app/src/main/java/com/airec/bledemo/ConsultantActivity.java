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
    private PenController penController;

    private final Handler ui = new Handler(Looper.getMainLooper());

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
            if (connected) {
                penController.activate();
                if (pendingSelectPenAfterConnect) pendingSelectPenAfterConnect = false;
                Toast.makeText(this, "录音笔已连接", Toast.LENGTH_SHORT).show();
            }
            // 通知网页刷新右上角来源切换的连接状态
            evalJs("if(window.__onPenConnChanged){window.__onPenConnChanged(" + connected + ");}");
        }
    }

    // ============ 录音引擎控制（由网页通过桥驱动）============

    private void startRecordingInternal(String source) {
        if (source == null) source = "phone";
        if ("pen".equals(source)) {
            if (penController == null || !penController.isConnected()) {
                pendingSelectPenAfterConnect = true;
                openScan();
                return;
            }
            CookieManager.getInstance().flush();
            activeSource = "pen";
            recordingStartMs = SystemClock.elapsedRealtime();
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
                applyState("idle", message);
                if (recId >= 0) {
                    evalJs("if(window.__onNativeUploaded){window.__onNativeUploaded(" + recId + ");}");
                }
                break;
            case PhoneMicService.STATE_ERROR:
                recordingStartMs = 0;
                activeSource = null;
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
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_PERMS);
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
