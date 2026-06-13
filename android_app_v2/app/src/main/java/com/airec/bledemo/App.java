package com.airec.bledemo;

import android.app.Application;
import com.airec.blesdk.AIRECBleCallback;
import com.airec.blesdk.AIRECBleManager;

public class App extends Application {

    // 全局持有 mainCallback，ScanActivity 连接成功后直接切换到这里
    private static AIRECBleCallback mainCallback;

    public static void setMainCallback(AIRECBleCallback cb) { mainCallback = cb; }
    public static AIRECBleCallback getMainCallback()        { return mainCallback; }

    @Override
    public void onCreate() {
        super.onCreate();
        AIRECBleManager.getInstance().init(this);
        // 初始化 JNA libopus 加载路径（必须在其他代码之前设置）
        OpusBridge.setAppContext(this);
        // ★引擎自举：进程被杀后由 STICKY 前台服务拉活时没有 Activity，在这里把声云引擎建起来并
        //   自动回连上次的陪伴笔——配合持久化补传队列，自愈重启后无需用户打开界面就能续传。
        try {
            com.airec.bledemo.recording.RecordingModule.INSTANCE.init(this);
            com.airec.bledemo.recording.RecordingModule.INSTANCE.refreshUploadContext();
            String mac = getSharedPreferences("pen_prefs", MODE_PRIVATE).getString("last_mac", null);
            if (mac != null && !mac.isEmpty()) {
                com.airec.bledemo.recording.RecordingModule.INSTANCE.getController().autoConnectPen(mac);
            }
        } catch (Throwable t) {
            android.util.Log.w("App", "引擎自举失败(不影响正常入口): " + t.getMessage());
        }
    }
}
