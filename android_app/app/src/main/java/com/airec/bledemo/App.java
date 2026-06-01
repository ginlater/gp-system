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
    }
}
