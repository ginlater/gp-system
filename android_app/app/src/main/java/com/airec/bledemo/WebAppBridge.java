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
        void bridgePauseRecording();
        void bridgeResumeRecording();
        String bridgeGetState();
        String bridgeGetSources();
        boolean bridgeIsPenConnected();
        void bridgeConnectPen();
        void bridgeOpenPenManager();
        String bridgePendingInfo();   // "count,failed"：后台待传/失败段数，供页面重载后恢复 badge
        void bridgeSyncPenFiles();    // 手动"从录音笔同步"：拉机身文件列表(回 window.__onPenFiles)
        void bridgeUploadPenFiles(String namesJson);  // 上传网页勾选的机身文件(JSON 文件名数组)
        void bridgeRetryPenUploads(); // ★A1:网页"重试"按钮：立刻重推待补传队列
        void bridgeCancelPenUpload(String id);  // 网页"取消上传"：按 placeholderId 从补传队列移除该段任务
        void bridgeUploadDiag();      // 一键诊断：把 penlog/last_result + 设备信息上传后端，供远程排查
        int bridgeGetRecElapsedSec(); // 录音笔当前已录秒数(切回陪伴页时把计时对到笔真实进度，不从0重数)
        String bridgeAppVersionName(); // 本机已安装的 versionName(如 "2.1.13")，给陪伴页显示版本号
    }

    private final Host host;

    public WebAppBridge(Host host) { this.host = host; }

    @JavascriptInterface
    public boolean isApp() { return true; }

    @JavascriptInterface
    public String appVersion() { return host.bridgeAppVersionName(); }

    /** 当前可用录音来源，逗号分隔。阶段一只有手机麦克风；阶段二加 "pen"。 */
    @JavascriptInterface
    public String getSources() { return host.bridgeGetSources(); }

    /** source: "phone"（手机麦克风）/ "pen"（蓝牙录音笔，阶段二）。 */
    @JavascriptInterface
    public void startRecording(String source) { host.bridgeStartRecording(source); }

    @JavascriptInterface
    public void stopRecording() { host.bridgeStopRecording(); }

    /** 暂停录音（仅录音笔）。 */
    @JavascriptInterface
    public void pauseRecording() { host.bridgePauseRecording(); }

    /** 继续录音（仅录音笔）。 */
    @JavascriptInterface
    public void resumeRecording() { host.bridgeResumeRecording(); }

    /** idle / recording / paused / uploading */
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

    /** "count,failed"：后台待传/失败段数。页面重载后调它恢复"💾保存中"badge。 */
    @JavascriptInterface
    public String pendingInfo() { return host.bridgePendingInfo(); }

    /** 手动"从录音笔同步"：拉机身文件列表，native 拉到后回调 window.__onPenFiles(json)。 */
    @JavascriptInterface
    public void syncPenFiles() { host.bridgeSyncPenFiles(); }

    /** 上传网页勾选的机身文件：namesJson = JSON 文件名数组。 */
    @JavascriptInterface
    public void uploadPenFiles(String namesJson) { host.bridgeUploadPenFiles(namesJson); }

    /** ★A1:网页"重试"按钮：立刻重推待补传(下载补全)队列。 */
    @JavascriptInterface
    public void retryPenUploads() { host.bridgeRetryPenUploads(); }

    @JavascriptInterface
    public void cancelPenUpload(String id) { host.bridgeCancelPenUpload(id); }

    /** 一键诊断上传：收集 penlog/last_result + 设备信息发后端，供远程排查上传失败等问题。
     *  结果异步回调 window.__onDiagUploaded(ok, msg)。 */
    @JavascriptInterface
    public void uploadDiag() { host.bridgeUploadDiag(); }

    /** 录音笔当前已录秒数（没在录返回 0）。切回陪伴页时据此把计时对到笔真实进度。 */
    @JavascriptInterface
    public int getRecElapsedSec() { return host.bridgeGetRecElapsedSec(); }
}
