package com.airec.bledemo;

import com.airec.blesdk.AIRECBleCallback;
import com.airec.blesdk.AIRECBleDevice;
import com.airec.blesdk.AIRECBleFile;
import com.airec.blesdk.AIRECBleManager;

import java.util.List;

/**
 * 文件下载管理器（桥接层）
 *
 * 调用新版 AIRECBleManager.downloadFile()，
 * 通过 AIRECBleCallback 的下载回调驱动 UI 更新。
 */
public class FileDownloadManager {

    public interface DownloadCallback {
        void onProgress(AIRECBleFile file, int progress);
        void onComplete(AIRECBleFile file, String localPath);
        void onError(AIRECBleFile file, String reason);
    }

    private static FileDownloadManager instance;
    public static FileDownloadManager getInstance() {
        if (instance == null) instance = new FileDownloadManager();
        return instance;
    }

    private DownloadCallback pendingCallback;
    private AIRECBleFile     pendingFile;

    /** 是否已下载到本地 */
    public boolean isDownloaded(String fileName) {
        return AIRECBleManager.getInstance().isDownloaded(fileName);
    }

    /** 获取本地路径 */
    public String getLocalPath(String fileName) {
        return AIRECBleManager.getInstance().getLocalPath(fileName);
    }

    /**
     * 开始下载文件
     * 内部通过新版 SDK 的 downloadFile() 触发真实 BLE 传输
     */
    public void startDownload(AIRECBleFile file, DownloadCallback callback) {
        this.pendingFile     = file;
        this.pendingCallback = callback;
        AIRECBleManager.getInstance().downloadFile(file);
    }

    /** 取消下载 */
    public void cancelDownload() {
        AIRECBleManager.getInstance().cancelDownload();
        pendingFile = null;
        pendingCallback = null;
    }

    /**
     * 供 MainActivity 在 AIRECBleCallback 里转发下载回调
     */
    public void onProgress(AIRECBleFile file, int progress) {
        if (pendingCallback != null) pendingCallback.onProgress(file, progress);
    }

    public void onComplete(AIRECBleFile file, String localPath) {
        DownloadCallback cb = pendingCallback;
        pendingFile = null;
        pendingCallback = null;
        if (cb != null) cb.onComplete(file, localPath);
    }

    public void onError(AIRECBleFile file, String reason) {
        DownloadCallback cb = pendingCallback;
        pendingFile = null;
        pendingCallback = null;
        if (cb != null) cb.onError(file, reason);
    }
}
