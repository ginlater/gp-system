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
import android.text.TextUtils;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.airec.bledemo.recording.PhoneMicService;
import com.airec.bledemo.recording.RecordingBus;

/**
 * 接诊 WebView 壳（启动页）。
 *
 * 复用线上 consultant 网页的全部业务/UI，只把「录音采集」从浏览器 getUserMedia 换成原生：
 *  - 阶段一：手机麦克风前台 Service（切后台/锁屏不中断）
 *  - 阶段二：蓝牙录音笔（彻底不依赖手机进程）
 * 底部自带一个原生录音条，即便网页未接入 Bridge 也能录音上传（便于先期验证）。
 */
public class ConsultantActivity extends Activity
        implements WebAppBridge.Host, RecordingBus.Listener, PenController.Listener {

    // ★ 唯一需要你确认的配置：接诊页地址。https 域名打不开就改成 http://<服务器IP>/gp/consultant 之类。
    private static final String START_URL = "https://gp.aibeautyfulwomen.com/consultant";

    private static final int REQ_PERMS = 1001;
    private static final int REQ_FILE_CHOOSER = 1002;
    private static final int REQ_SCAN = 1003;

    private WebView webView;
    private ProgressBar progress;
    private Button btnRecord;
    private Button btnSource;
    private TextView recStatus;
    private TextView recTimer;

    private ValueCallback<Uri[]> filePathCallback;
    private PermissionRequest pendingWebPermission;

    private String state = "idle";           // idle / recording / uploading
    private long recordingStartMs = 0;
    private boolean batteryAsked = false;

    // 录音来源：phone（手机麦克风）/ pen（蓝牙录音笔）
    private String recSource = "phone";      // 用户当前选择，用于下一次录音
    private String activeSource = null;      // 正在录音的来源
    private boolean pendingSelectPenAfterConnect = false;
    private PenController penController;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if ("recording".equals(state)) {
                updateTimerText(elapsedSec());
                ui.postDelayed(this, 500);
            }
        }
    };

    private static String uploadUrlFor(String startUrl) {
        // 去掉结尾的 /consultant，拼成 /api/consultant/upload；兼容根路径或带 /gp 前缀的部署
        String base = startUrl.replaceAll("/consultant/?$", "");
        return base + "/api/consultant/upload";
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_consultant);

        webView = findViewById(R.id.webview);
        progress = findViewById(R.id.progress);
        btnRecord = findViewById(R.id.btn_record);
        btnSource = findViewById(R.id.btn_source);
        recStatus = findViewById(R.id.rec_status);
        recTimer = findViewById(R.id.rec_timer);

        penController = new PenController(getApplicationContext(), this);

        configureWebView();
        btnRecord.setOnClickListener(v -> onRecordButton());
        btnSource.setOnClickListener(v -> toggleSource());
        updateSourceLabel();
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
                // 网页若直接申请麦克风（getUserMedia 回退路径），在已有系统权限时放行
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
            if (penController != null) penController.activate();
            if (penController != null && penController.isConnected()) {
                if (pendingSelectPenAfterConnect) { recSource = "pen"; pendingSelectPenAfterConnect = false; }
                Toast.makeText(this, "录音笔已连接", Toast.LENGTH_SHORT).show();
            }
            updateSourceLabel();
        }
    }

    // ============ 原生录音条 / 按钮 ============

    private void onRecordButton() {
        if ("uploading".equals(state)) return;
        if ("recording".equals(state)) {
            stopRecordingInternal();
        } else {
            startRecordingInternal(recSource);
        }
    }

    private void startRecordingInternal(String source) {
        if (source == null) source = "phone";
        if ("pen".equals(source)) {
            // 蓝牙录音笔：未连接则去连接，连上后用户再点录音
            if (penController == null || !penController.isConnected()) {
                pendingSelectPenAfterConnect = true;
                openScan();
                return;
            }
            CookieManager.getInstance().flush();
            activeSource = "pen";
            recordingStartMs = SystemClock.elapsedRealtime();
            applyState("recording", "录音中…（录音笔）");
            ui.removeCallbacks(ticker);
            ui.post(ticker);
            penController.startRecording(
                    CookieManager.getInstance().getCookie(START_URL), uploadUrlFor(START_URL));
            return;
        }

        // 手机麦克风
        if (!hasAudioPermission()) {
            requestRuntimePermissions();
            Toast.makeText(this, "请先允许麦克风权限", Toast.LENGTH_SHORT).show();
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
        ui.removeCallbacks(ticker);
        ui.post(ticker);
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

    // ============ 录音来源切换 / 录音笔连接 ============

    private void toggleSource() {
        if ("recording".equals(state) || "uploading".equals(state)) {
            Toast.makeText(this, "录音/上传中，无法切换来源", Toast.LENGTH_SHORT).show();
            return;
        }
        if ("phone".equals(recSource)) {
            // 切到录音笔：已连接直接切，否则去连接
            if (penController != null && penController.isConnected()) {
                recSource = "pen";
            } else {
                pendingSelectPenAfterConnect = true;
                openScan();
            }
        } else {
            recSource = "phone";
        }
        updateSourceLabel();
    }

    private void updateSourceLabel() {
        if (btnSource == null) return;
        boolean pen = "pen".equals(recSource);
        btnSource.setText(pen ? "🖊 录音笔" : "🎤 手机");
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
            Toast.makeText(this, "请先连接录音笔", Toast.LENGTH_SHORT).show();
            pendingSelectPenAfterConnect = true;
            openScan();
        });
    }

    /** 更新本地状态 + 底部录音条 UI，并推给网页。 */
    private void applyState(String newState, String message) {
        state = newState;
        if ("recording".equals(newState)) {
            btnRecord.setText(R.string.rec_stop);
            btnRecord.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFE0466B));
            recStatus.setText(message != null ? message : getString(R.string.rec_recording));
        } else if ("uploading".equals(newState)) {
            btnRecord.setText(R.string.rec_start);
            btnRecord.setEnabled(false);
            recStatus.setText(message != null ? message : getString(R.string.rec_uploading));
        } else { // idle
            btnRecord.setText(R.string.rec_start);
            btnRecord.setEnabled(true);
            btnRecord.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF3B6EF6));
            recStatus.setText(message != null ? message : getString(R.string.rec_idle));
            updateTimerText(0);
        }
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

    private void updateTimerText(int sec) {
        int m = sec / 60, r = sec % 60;
        recTimer.setText(String.format(java.util.Locale.US, "%02d:%02d", m, r));
    }

    // ============ RecordingBus 回调（来自 PhoneMicService 的真实状态）============

    @Override
    public void onStatus(String st, String message, int durSec, long recId) {
        switch (st) {
            case PhoneMicService.STATE_RECORDING:
                if (recordingStartMs == 0) recordingStartMs = SystemClock.elapsedRealtime() - durSec * 1000L;
                applyState("recording", message);
                ui.removeCallbacks(ticker);
                ui.post(ticker);
                break;
            case PhoneMicService.STATE_UPLOADING:
                applyState("uploading", message);
                break;
            case PhoneMicService.STATE_IDLE:
                recordingStartMs = 0;
                activeSource = null;
                applyState("idle", message);
                if (recId >= 0) {
                    // 通知网页：刷新未归档列表 + 打开绑定弹窗（复用网页现有流程）
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
        // 若选了录音笔，回到接诊页时夺回 SDK 回调（可能被录音笔管理页抢走过）
        if (penController != null && "pen".equals(recSource) && penController.isConnected()) {
            penController.activate();
        }
        // 重新同步底部录音条（切后台期间 Service 状态可能已变）
        if (!TextUtils.equals(state, RecordingBus.lastState)) {
            onStatus(RecordingBus.lastState, RecordingBus.lastMessage, RecordingBus.lastDurSec, -1);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        RecordingBus.clear(this);
        ui.removeCallbacks(ticker);
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
