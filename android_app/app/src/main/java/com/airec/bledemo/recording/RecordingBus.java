package com.airec.bledemo.recording;

import android.os.Handler;
import android.os.Looper;

/**
 * 极简进程内事件总线：录音 Service → 接诊页 的状态回传。
 * 不用 LocalBroadcastManager（已废弃、要加依赖），同进程一个静态监听即可。
 * Activity 在 onResume 设监听、onPause 清监听，避免泄漏。
 */
public final class RecordingBus {

    public interface Listener {
        /** state 见 PhoneMicService.STATE_*；recId 仅上传成功时 >=0 */
        void onStatus(String state, String message, int durSec, long recId);
    }

    private static volatile Listener listener;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // 最近一次状态快照，供 Activity 切回前台时重新同步底部录音条
    public static volatile String lastState = "idle";
    public static volatile String lastMessage = "";
    public static volatile int lastDurSec = 0;

    private RecordingBus() {}

    public static void setListener(Listener l) { listener = l; }

    public static void clear(Listener l) { if (listener == l) listener = null; }

    public static void post(final String state, final String message, final int durSec, final long recId) {
        lastState = state;
        lastMessage = message;
        lastDurSec = durSec;
        MAIN.post(() -> {
            Listener l = listener;
            if (l != null) l.onStatus(state, message, durSec, recId);
        });
    }
}
