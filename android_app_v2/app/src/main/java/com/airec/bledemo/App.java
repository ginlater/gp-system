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
        // 网络层提前初始化（供后台 WorkManager 轮询提醒用；幂等，与 MeiliActivity 不冲突）
        try {
            com.airec.bledemo.data.net.NetworkModule.INSTANCE.init(this);
        } catch (Throwable t) {
            android.util.Log.w("App", "NetworkModule init 失败: " + t.getMessage());
        }
        // 提醒通知渠道 + 后台周期轮询（录音待绑定 / 报告未查看 → 本地系统通知）
        try {
            com.airec.bledemo.notify.ReminderNotifier.INSTANCE.init(this);
            com.airec.bledemo.notify.ReminderPollWorker.Companion.schedule(this);
        } catch (Throwable t) {
            android.util.Log.w("App", "提醒轮询登记失败: " + t.getMessage());
        }
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
