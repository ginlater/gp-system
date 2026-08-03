package com.aibeautyfulwomen.gongpai;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final String START_URL = "https://gp.aibeautyfulwomen.com/consultant";
    private static final String PERMISSION_READ_MEDIA_AUDIO = "android.permission.READ_MEDIA_AUDIO";
    private static final String PERMISSION_READ_MEDIA_IMAGES = "android.permission.READ_MEDIA_IMAGES";
    private static final String PERMISSION_READ_MEDIA_VIDEO = "android.permission.READ_MEDIA_VIDEO";
    private static final int REQ_FILE_CHOOSER = 1001;
    private static final int REQ_PERMISSIONS = 1002;

    private WebView webView;
    private ProgressBar progressBar;
    private Button btnRecord;
    private Button btnStop;
    private TextView recordStatus;
    private ValueCallback<Uri[]> filePathCallback;
    private PermissionRequest pendingPermissionRequest;
    private boolean nativeRecording;

    private final BroadcastReceiver recordingReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String state = intent.getStringExtra(RecordingService.EXTRA_STATE);
            String message = intent.getStringExtra(RecordingService.EXTRA_MESSAGE);
            if (message == null) {
                message = "";
            }
            recordStatus.setText(message);
            if (RecordingService.STATE_RECORDING.equals(state)) {
                nativeRecording = true;
                btnRecord.setEnabled(false);
                btnStop.setEnabled(true);
            } else if (RecordingService.STATE_UPLOADING.equals(state)) {
                nativeRecording = false;
                btnRecord.setEnabled(false);
                btnStop.setEnabled(false);
            } else {
                nativeRecording = false;
                btnRecord.setEnabled(true);
                btnStop.setEnabled(false);
                if (RecordingService.STATE_ERROR.equals(state)) {
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = (WebView) findViewById(R.id.webview);
        progressBar = (ProgressBar) findViewById(R.id.progress);
        btnRecord = (Button) findViewById(R.id.btn_record);
        btnStop = (Button) findViewById(R.id.btn_stop);
        recordStatus = (TextView) findViewById(R.id.record_status);
        configureWebView();
        configureRecorderButtons();
        requestRuntimePermissions();

        if (savedInstanceState == null) {
            webView.loadUrl(START_URL);
        } else {
            webView.restoreState(savedInstanceState);
        }
    }

    private void configureRecorderButtons() {
        btnRecord.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!hasAudioPermission()) {
                    requestRuntimePermissions();
                    Toast.makeText(MainActivity.this, "请先允许麦克风权限", Toast.LENGTH_SHORT).show();
                    return;
                }
                CookieManager.getInstance().flush();
                Intent intent = new Intent(MainActivity.this, RecordingService.class);
                intent.setAction(RecordingService.ACTION_START);
                intent.putExtra(RecordingService.EXTRA_UPLOAD_URL, "https://gp.aibeautyfulwomen.com/api/consultant/upload");
                intent.putExtra(RecordingService.EXTRA_COOKIE, CookieManager.getInstance().getCookie(START_URL));
                startService(intent);
            }
        });
        btnStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CookieManager.getInstance().flush();
                Intent intent = new Intent(MainActivity.this, RecordingService.class);
                intent.setAction(RecordingService.ACTION_STOP_UPLOAD);
                intent.putExtra(RecordingService.EXTRA_COOKIE, CookieManager.getInstance().getCookie(START_URL));
                startService(intent);
            }
        });
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.setAcceptThirdPartyCookies(webView, true);
        }

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                Uri uri = Uri.parse(url);
                return handleExternalUrl(uri);
            }

            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleExternalUrl(request.getUrl());
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                progressBar.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                Intent intent = params.createIntent();
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                try {
                    startActivityForResult(intent, REQ_FILE_CHOOSER);
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                pendingPermissionRequest = request;
                requestRuntimePermissions();
                if (hasRuntimePermissions()) {
                    request.grant(request.getResources());
                    pendingPermissionRequest = null;
                }
            }
        });
    }

    private boolean handleExternalUrl(Uri uri) {
        if ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) {
            return false;
        }
        startActivity(new Intent(Intent.ACTION_VIEW, uri));
        return true;
    }

    private void requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || hasRuntimePermissions()) {
            return;
        }
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33) {
            permissions.add(PERMISSION_READ_MEDIA_AUDIO);
            permissions.add(PERMISSION_READ_MEDIA_IMAGES);
            permissions.add(PERMISSION_READ_MEDIA_VIDEO);
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        requestPermissions(permissions.toArray(new String[0]), REQ_PERMISSIONS);
    }

    private boolean hasAudioPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasRuntimePermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= 33) {
            return checkSelfPermission(PERMISSION_READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS && pendingPermissionRequest != null && hasRuntimePermissions()) {
            pendingPermissionRequest.grant(pendingPermissionRequest.getResources());
            pendingPermissionRequest = null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILE_CHOOSER || filePathCallback == null) {
            return;
        }
        Uri[] results = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
        filePathCallback.onReceiveValue(results);
        filePathCallback = null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(recordingReceiver, new IntentFilter(RecordingService.BROADCAST_STATUS));
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(recordingReceiver);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }
}
