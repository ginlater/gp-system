package com.airec.bledemo;

import android.webkit.JavascriptInterface;

/**
 * 注入到接诊网页的 JS 桥：window.AndroidBridge.*
 *
 * 网页通过 window.AndroidBridge.isApp() 判断自己跑在 App 里；若是，则把录音按钮改为调用
 * startRecording/stopRecording 走原生（前台 Service / 阶段二的蓝牙录音笔），而不是浏览器 getUserMedia。
 * 浏览器里没有这个对象，网页行为完全不变。
 *
 * 所有方法可能在 WebView 的 JS 线程被调用，具体实现里负责切回主线程。
 */
public class WebAppBridge {

    public interface Host {
        void bridgeStartRecording(String source);
        void bridgeStopRecording();
        String bridgeGetState();
        String bridgeGetSources();
        boolean bridgeIsPenConnected();
        void bridgeConnectPen();
        void bridgeOpenPenManager();
    }

    private final Host host;

    public WebAppBridge(Host host) { this.host = host; }

    @JavascriptInterface
    public boolean isApp() { return true; }

    @JavascriptInterface
    public String appVersion() { return "2.0.0"; }

    /** 当前可用录音来源，逗号分隔。阶段一只有手机麦克风；阶段二加 "pen"。 */
    @JavascriptInterface
    public String getSources() { return host.bridgeGetSources(); }

    /** source: "phone"（手机麦克风）/ "pen"（蓝牙录音笔，阶段二）。 */
    @JavascriptInterface
    public void startRecording(String source) { host.bridgeStartRecording(source); }

    @JavascriptInterface
    public void stopRecording() { host.bridgeStopRecording(); }

    /** idle / recording / uploading */
    @JavascriptInterface
    public String getState() { return host.bridgeGetState(); }

    /** 录音笔当前是否已蓝牙连接（网页据此决定默认来源/是否强制提示连接）。 */
    @JavascriptInterface
    public boolean isPenConnected() { return host.bridgeIsPenConnected(); }

    /** 打开录音笔扫描/连接页（网页"强制提示连接"时调用）。 */
    @JavascriptInterface
    public void connectPen() { host.bridgeConnectPen(); }

    /** 打开录音笔管理页（调试用）。 */
    @JavascriptInterface
    public void openPenManager() { host.bridgeOpenPenManager(); }
}
