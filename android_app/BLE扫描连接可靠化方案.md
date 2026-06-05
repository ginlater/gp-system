# BLE 真扫描+真连接+在线判定+录音前确认 方案（多agent调查，2026-06-04）

I have enough to see how `isConnected()` is consumed across `ConsultantActivity` (multiple call sites: lines 68, 173, 206, 411, 459, 462, 508, 544, 555). This confirms that adding an App-layer `isPenAlive()` and keeping `isConnected()` semantics matters. I have everything needed to write the design.

---

# 录音笔「真扫描 + 真连接 + 在线判定 + 录音前确认」可靠化改造方案

## 0. 一句话结论（根因 → 原则）

四维调查已锁定根因：**SDK 的扫描是干净的实时广播**，但 (a) `ScanActivity` 列表只在手动点扫描时清空、永不过期，所以"上次那支笔"会一直挂着；(b) `AIRECBleManager.isConnected()` 只看 GATT 句柄指针（`g!=null && f!=null && !h`），不看笔是否回过包；(c) SDK 在 `syncTime` 无响应时有一个 **3 秒 fallback-onConnected** 兜底，会把"链路连上但笔深睡不应答"也上报成 `onConnected`。

因此本方案的核心原则：

> **"已连接"和"在场"永远以「最近收到过笔的真实业务回包」为唯一硬证据，绝不相信 `isConnected()` 这个指针判断，也不无条件相信 SDK 的 `onConnected`。**

落地手段（全部在 App 层，不改 SDK aar）：
1. 扫描前硬校验蓝牙开关；列表项加新鲜度过期，关机/走开的笔自动消失。
2. `PenController` 引入 App 层 `verifiedConnected` + `lastRxMs`，`onConnected` 只当"链路就绪"，发一条命令限时等回包，收到才算真连上。
3. 连上后跑周期心跳，连续静默即判离线、主动断开、刷新 UI。
4. 录音前先做一次"必须收到回包"的活性确认，再走现有 `startRecord` + `confirmTimeout`。
5. `autoConnect` 同样以"真回包"为准，并加新鲜度约束。

---

## 1. 【扫描】只显示此刻真在广播、真能连的笔

### 1.1 扫描前硬校验蓝牙（关着就不进"扫描中"，直接提示）

SDK 的 `startScan()` 在蓝牙关闭时是**静默 return**（`if (adapter != null && adapter.isEnabled())` 否则直接 return，无回调），UI 会永远停在转圈。必须在 App 侧先判 `isEnabled()`。

`ScanActivity.requestPermissionsAndScan()` 改成先判蓝牙：

```java
// ScanActivity.java
private boolean ensureBluetoothOn() {
    android.bluetooth.BluetoothManager bm =
            (android.bluetooth.BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
    android.bluetooth.BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
    if (adapter == null) {
        binding.tvStatus.setText("此设备不支持蓝牙");
        binding.progressBar.setVisibility(View.GONE);
        return false;
    }
    if (!adapter.isEnabled()) {
        binding.progressBar.setVisibility(View.GONE);
        binding.tvStatus.setText("蓝牙未开启，请先打开蓝牙");
        // 拉起系统开蓝牙弹窗（需要 BLUETOOTH_CONNECT 权限，Android 12+）
        try {
            startActivity(new Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE));
        } catch (Exception ignored) {
            Toast.makeText(this, "请在系统设置里打开蓝牙后重试", Toast.LENGTH_LONG).show();
        }
        return false;
    }
    return true;
}

private void requestPermissionsAndScan() {
    // ...原有权限判断保持不变，权限齐了之后：
    if (!ensureBluetoothOn()) return;     // ★关键：蓝牙没开就别进入扫描态
    startFreshScan();
}
```

并把 `onCreate` 末尾、`btnScan` 点击里直接调 `startScan()` 的地方都改成走 `requestPermissionsAndScan()`（已是这样），确保都经过 `ensureBluetoothOn()`。

### 1.2 列表项加新鲜度过期（关机/走开的笔自动消失）—— 这是"上次那支笔总在"的根治

给每个设备记最后一次 `onDeviceFound` 的 `elapsedRealtime`，起一个 1.5s 定时器移除超过 **8s** 没再刷新的设备。BLE 广播间隔通常 <1s，真开机的笔会持续刷新，关机的笔 8s 后从列表消失。

```java
// ScanActivity.java —— 用一个并行的时间戳表，不动 SDK 的 AIRECBleDevice
private final java.util.Map<String, Long> lastSeen = new java.util.HashMap<>(); // mac -> elapsedRealtime
private static final long STALE_MS = 8000;
private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());

private final Runnable pruneTask = new Runnable() {
    @Override public void run() {
        long now = android.os.SystemClock.elapsedRealtime();
        boolean changed = false;
        for (int i = deviceList.size() - 1; i >= 0; i--) {
            String mac = deviceList.get(i).getAddress();
            Long t = lastSeen.get(mac);
            if (t == null || now - t > STALE_MS) {
                deviceList.remove(i);
                lastSeen.remove(mac);
                changed = true;
            }
        }
        if (changed) {
            adapter.notifyDataSetChanged();
            binding.tvStatus.setText(deviceList.isEmpty()
                    ? "未发现在广播的录音笔，请确认笔已开机靠近"
                    : "发现 " + deviceList.size() + " 台设备，点击连接");
        }
        ui.postDelayed(this, 1500);
    }
};

private void startFreshScan() {
    deviceList.clear();
    lastSeen.clear();
    adapter.notifyDataSetChanged();
    binding.tvStatus.setText("扫描中...");
    binding.progressBar.setVisibility(View.VISIBLE);
    AIRECBleManager.getInstance().startScan();
    ui.removeCallbacks(pruneTask);
    ui.postDelayed(pruneTask, 1500);
}
```

`onDeviceFound` 改为：刷新时间戳 + 可选 RSSI 门槛，且**每次进扫描都重新攒列表**（不再让 onCreate 那次的设备永久留存）：

```java
@Override
public void onDeviceFound(AIRECBleDevice device) {
    if (device == null) return;
    // ★信号门槛：太弱（远/半睡）直接不收，避免列出根本连不上的笔。-90 可按真机调。
    if (device.getRssi() != 0 && device.getRssi() < -90) return;

    String mac = device.getAddress();
    lastSeen.put(mac, android.os.SystemClock.elapsedRealtime());   // ★刷新新鲜度
    int idx = -1;
    for (int i = 0; i < deviceList.size(); i++) {
        if (deviceList.get(i).getAddress().equals(mac)) { idx = i; break; }
    }
    if (idx < 0) {
        deviceList.add(device);
        adapter.notifyItemInserted(deviceList.size() - 1);
    }
    binding.tvStatus.setText("发现 " + deviceList.size() + " 台设备，点击连接");

    // 自动连接（保留，仍依赖真实广播；过期逻辑保证关机笔不会进这里）
    if (!autoConnectTried && lastMac != null && lastMac.equals(mac)) {
        autoConnectTried = true;
        binding.tvStatus.setText("自动连接上次的录音笔：" + device.getName() + "…");
        AIRECBleManager.getInstance().stopScan();
        AIRECBleManager.getInstance().connect(device);
    }
}
```

记得 `btnScan` 点击和 `onCreate` 自动扫描都改调 `startFreshScan()`，并在 `onDestroy` / `onPause` 里 `ui.removeCallbacks(pruneTask)`。

> 注意：RSSI 门槛会让"放在很远的笔"列不出来，这正是想要的（远到根本连不上就别假装能连）。阈值要在真机上量一次正常使用距离的 RSSI 再定，先用 -90 保守值。

### 1.3 `onScanFailed` 不要无声重试（可选，体验项）

SDK 内部 `onScanFailed` 会 1s 后自动重启扫描，UI 永远停"扫描中"。这块在 SDK 里改不到，但 App 可以：扫描启动后起一个 **6s 兜底**，若期间 `onDeviceFound` 一次都没来，提示"未发现设备，请确认笔已开机/靠近，或检查定位服务"。（用上面的 `pruneTask` 顺带就能做到——列表空时就显示这句。）

---

## 2. 【真连接判定】用 App 层 `verifiedConnected` 取代 `isConnected()`

### 2.1 在 `PenController` 维护真连接状态

新增字段 + 一个"真响应"判据。**关键点：`onConnected` 不再立刻 `onPenConnected(true)`，而是先标"校验中"，发一条 `fetchDeviceInfo()`（含 0x0E/0x0F/0x0B，最轻），限时 5s 等任意真回包；收到才点亮。**

```java
// PenController.java —— 新增字段
private volatile boolean verifiedConnected = false;   // App 层"已验证在线"
private volatile long    lastRxMs = 0;                // 最近一次收到笔真实回包的时刻(elapsedRealtime)
private static final long HANDSHAKE_TIMEOUT_MS = 5000;
private static final long STALE_MS = 12000;           // 超过这么久没回包 → 视为不在线

/** 任意"只有真设备才会回"的回调都调它：刷新在线证据。 */
private void markPenResponded() {
    lastRxMs = android.os.SystemClock.elapsedRealtime();
    if (!verifiedConnected) {
        verifiedConnected = true;
        main.removeCallbacks(handshakeTimeout);
        if (listener != null) main.post(() -> listener.onPenConnected(true));  // ★真连上才点亮
        startHeartbeat();                                                      // 见第 3 节
    }
}

/** 对外的"真在线"判据：链路在 且 最近 STALE_MS 内收到过回包。UI/网页都用它，不用裸 isConnected()。 */
public boolean isPenAlive() {
    try {
        return verifiedConnected
                && AIRECBleManager.getInstance().isConnected()
                && (android.os.SystemClock.elapsedRealtime() - lastRxMs) < STALE_MS;
    } catch (Exception e) { return false; }
}
```

### 2.2 `onConnected` 改成"链路就绪 → 发命令等回包"

```java
@Override
public void onConnected(AIRECBleDevice device) {
    autoConnectMac = null;
    penSettingsWritten = false;
    verifiedConnected = false;          // ★清空：SDK 的 onConnected 只当"链路就绪"，不当"真连上"
    lastRxMs = 0;
    // 立刻发一条会回包的命令做握手确认（比原来的 600ms 延迟更早、更明确）
    try { AIRECBleManager.getInstance().fetchDeviceInfo(); }   // 发 0x0E/0x0F/0x0B
    catch (Exception e) { Log.e(TAG, "handshake fetchDeviceInfo failed", e); }
    main.removeCallbacks(handshakeTimeout);
    main.postDelayed(handshakeTimeout, HANDSHAKE_TIMEOUT_MS);
    // 其余设备信息/配置读取仍可延迟做
    main.postDelayed(() -> ensurePenConfigured(), 3500);
}

/** 握手超时：发了命令但 5s 内笔一个回包都没回 → 判定假连接（SDK 的 3s fallback-onConnected 就被这条堵住）。 */
private final Runnable handshakeTimeout = new Runnable() {
    @Override public void run() {
        if (!verifiedConnected) {
            Log.w(TAG, "连上但笔无回包 → 判定假连接，断开");
            try { AIRECBleManager.getInstance().disconnect(); } catch (Exception ignored) {}
            if (listener != null) main.post(() -> listener.onPenConnected(false));
        }
    }
};
```

### 2.3 在所有"真回包"回调里调 `markPenResponded()`

`PenController` 已有的这些回调都是"只有真设备才会回"的硬证据，逐个加一行：

```java
@Override public void onRecordStatusQueried(boolean recording, boolean paused, String fileName) {
    markPenResponded();        // ★ 0x0F 回包 = 笔活着（握手最爱用这条）
    ...原有逻辑...
}
@Override public void onInitParamUpdated() { markPenResponded(); ensurePenConfigured(); }
@Override public void onRecordDurationUpdated(long durationSec) { markPenResponded(); ...原有... }
@Override public void onRecordStateChanged(boolean recording, String fileName) { markPenResponded(); ...原有... }
@Override public void onFileListUpdated(List<AIRECBleFile> files) { markPenResponded(); ...原有... }
@Override public void onFileDownloadProgress(AIRECBleFile file, int progress) { markPenResponded(); ...原有... }
```

还要给 SDK 回调里 App 当前没覆写的 `onDeviceInfoUpdated` / `onFirmwareVersionReceived` 也加一个空实现并 `markPenResponded()`（它们也是真回包）。

### 2.4 `onDisconnected` 复位真连接状态

```java
@Override public void onDisconnected(AIRECBleDevice device, String reason) {
    verifiedConnected = false;
    lastRxMs = 0;
    stopHeartbeat();
    main.removeCallbacks(handshakeTimeout);
    if (listener != null) main.post(() -> listener.onPenConnected(false));
    if (workerBusy && currentTask != null) workerTaskFailed(currentTask, "disconnected", false);
}
```

### 2.5 `PenController.isConnected()` 的处置 + 调用方迁移

`ConsultantActivity` 有约 9 处用 `penController.isConnected()`（行 68、173、206、411、459、462、508、544、555）。建议：

- 保留 `isConnected()`（转发 SDK，仍代表"链路在"），**新增 `isPenAlive()`**（真在线）。
- 把**给网页/UI 的"已连接"指示、以及"能不能开始录音"的判断**改用 `isPenAlive()`：行 173（连接指示）、206 和 411（开始录音前的判断）、459/462（`getRecordingSources`/网页连接态）、555（注入网页的连接状态 JS）。
- 自动连接的"已连就别再连"判断（行 68、508、544）可继续用 `isConnected()`（避免重复发起 connect 即可），但行 68 那个"连上后自动开始录音"应改成等 `isPenAlive()`（详见第 4 节衔接）。

---

## 3. 【主动活性检测 / 心跳】连续静默即判离线并断开

SDK 没有任何 keepalive，`onDisconnected` 只在 Android supervision-timeout（约 5~20s、本 SDK 未配置不可控）后才触发。笔**直接关机时 `onConnectionStateChange` 不保证及时甚至不触发**，所以必须 App 主动探活。

参数（贴合现有节奏，避开下载/录音抢占）：

| 项 | 值 | 说明 |
|---|---|---|
| 心跳间隔 | 8s | 录音中/下载中放宽到 15s（此时有数据帧流动本身就是活性证据） |
| 心跳命令 | `fetchDeviceInfo()` | 含 0x0F 录音状态查询，回 `onRecordStatusQueried`；SDK 未暴露单发 0x0F |
| 单次回包超时 | 4s | 发出后 4s 内任意真回包 → 刷新 `lastRxMs` |
| 判离线 | 连续 2 次心跳都没回（≈12~16s 全静默） | 抗偶发丢包 |
| 判离线动作 | `disconnect()` + `verifiedConnected=false` + `onPenConnected(false)` | UI 立刻如实改"未连接" |

```java
// PenController.java —— 心跳骨架
private static final long HB_INTERVAL_MS = 8000;
private static final long HB_INTERVAL_BUSY_MS = 15000;   // 录音/下载时放宽
private volatile int hbMissed = 0;

private final Runnable heartbeat = new Runnable() {
    @Override public void run() {
        try {
            if (!AIRECBleManager.getInstance().isConnected()) { stopHeartbeat(); return; }
        } catch (Exception e) { stopHeartbeat(); return; }

        long now = android.os.SystemClock.elapsedRealtime();
        // 录音中/下载中：有业务帧在流动就当心跳，不主动发命令抢通道
        boolean busy = penRecording || sessionActive || workerBusy || waitingForFile;
        if (busy && (now - lastRxMs) < HB_INTERVAL_BUSY_MS) {
            hbMissed = 0;
            main.postDelayed(this, HB_INTERVAL_BUSY_MS);
            return;
        }
        // 上一轮发的心跳有没有回包？
        if ((now - lastRxMs) > (HB_INTERVAL_MS + 4000)) {
            hbMissed++;
            Log.w(TAG, "心跳未回包，连续 " + hbMissed + " 次");
            if (hbMissed >= 2) {                 // ★连续 2 次静默 → 判离线
                Log.w(TAG, "笔失联 → 断开，连接指示如实");
                verifiedConnected = false;
                try { AIRECBleManager.getInstance().disconnect(); } catch (Exception ignored) {}
                if (listener != null) main.post(() -> listener.onPenConnected(false));
                stopHeartbeat();
                return;
            }
        } else {
            hbMissed = 0;
        }
        // 发本轮心跳（不在录音/下载时才发，避免 0xFD 冲突；0x0F 是只读查询其实也安全）
        if (!busy) {
            try { AIRECBleManager.getInstance().fetchDeviceInfo(); } catch (Exception ignored) {}
        }
        main.postDelayed(this, busy ? HB_INTERVAL_BUSY_MS : HB_INTERVAL_MS);
    }
};

private void startHeartbeat() {
    hbMissed = 0;
    main.removeCallbacks(heartbeat);
    main.postDelayed(heartbeat, HB_INTERVAL_MS);
}
private void stopHeartbeat() { main.removeCallbacks(heartbeat); hbMissed = 0; }
```

心跳在 `markPenResponded()` 首次点亮时 `startHeartbeat()`，`onDisconnected` 里 `stopHeartbeat()`。因为每个真回包都刷新 `lastRxMs`，正常使用时心跳几乎不会误判；只有笔真没了才连续 2 次静默触发断开。

> 进阶（可选）：若希望心跳更省，可在 SDK 加 `public void queryRecordStatus(){ e(a(15,null,-1)); }` 只发 0x0F。但本方案不依赖改 SDK，`fetchDeviceInfo()` 已够用。

---

## 4. 【录音前确认】点"开启陪伴"先验活，收不到回包绝不假录

现有 `startRecording` 已有 `appStartPending` + `confirmTimeout(5s)`：发 `startRecord` 后 5s 没等到 `onRecordStateChanged(true)` 就判休眠/关机并断开——这是对的，但它**到录音阶段才暴露假连接**。改进：在发 `startRecord` 之前，先用 `isPenAlive()` 快速判，再串一个"握手必达回包"窗口。

最小改动：把入口的 `isConnected()` 换成 `isPenAlive()`，并在不 alive 时先验活一次再决定：

```java
// PenController.startRecording —— 入口判断改用真在线
public void startRecording(String cookie, String uploadUrl) {
    setUploadContext(cookie, uploadUrl);
    if (penRecording || sessionActive) { /* 笔已在录，镜像已显示，忽略 */ return; }

    if (!isPenAlive()) {
        // 链路可能还在但近期没回包：先发一次确认命令，限时等回包，回来了再真开始
        if (isConnected()) {
            preStartVerifyThenRecord();          // 见下
        } else {
            if (listener != null) listener.onPenNeedConnect();
        }
        return;
    }
    doStartRecord();   // 真在线，直接发开始（原 startRecording 主体抽成 doStartRecord）
}

/** 录音前活性确认：发 fetchDeviceInfo，5s 内必须收到回包(markPenResponded→verifiedConnected) 才真开录。 */
private void preStartVerifyThenRecord() {
    post(PhoneMicService.STATE_STARTING, "正在确认录音笔…", 0, -1);
    try { AIRECBleManager.getInstance().fetchDeviceInfo(); } catch (Exception ignored) {}
    main.removeCallbacks(preStartTimeout);
    main.postDelayed(preStartTimeout, HANDSHAKE_TIMEOUT_MS);   // 5s
}
private final Runnable preStartTimeout = new Runnable() {
    @Override public void run() {
        if (isPenAlive()) {                       // 期间回包了 → 真活着，开录
            doStartRecord();
        } else {
            post(PhoneMicService.STATE_ERROR, "录音笔没开机/没响应，请确认它已开机并靠近后重试", 0, -1);
            try { AIRECBleManager.getInstance().disconnect(); } catch (Exception ignored) {}
            if (listener != null) main.post(() -> listener.onPenConnected(false));
        }
    }
};
```

`doStartRecord()` 就是把原 `startRecording` 里"发 `startRecord()` + 起 `confirmTimeout`"那段搬过来。这样形成**双闸门**：

1. 闸门一（连接级）：`preStartTimeout` 要求 5s 内有任意回包 → 证明笔活着；
2. 闸门二（录音级，已有）：`confirmTimeout` 要求发 `startRecord` 后 5s 内 `onRecordStateChanged(true)` → 证明真开录。

任一闸门没过都报"录音笔没开机/没响应"并断开，**绝不进入录音 UI、绝不假录**。

`ConsultantActivity` 衔接：行 68 那个 `pendingRecordAfterConnect` 在 `onPenConnected` 回调里触发开始录音——因为现在 `onPenConnected(true)` 只在 `markPenResponded` 真回包后才发，所以这里天然就是"真连上才自动开始"，无需额外改；只要把行 206/411 的 `isConnected()` 换 `isPenAlive()` 即可。

---

## 5. 【自动连接】不静默连上一支不在场的笔

`PenController.autoConnect` 与 `ScanActivity` 的自动连本身**依赖实时广播**（关机笔不会进 `onDeviceFound`），方向是对的。问题只在于"连上"被 SDK 的 fallback-onConnected 污染。修好第 2 节后，autoConnect 自动获益：即使链路连上，没真回包也不会 `onPenConnected(true)`。再补三点：

```java
// PenController.autoConnect —— 加蓝牙校验 + 新鲜度；连上仍走 verifiedConnected
public void autoConnect(String savedMac) {
    if (savedMac == null || savedMac.isEmpty()) return;
    if (isConnected() || autoConnectMac != null) return;
    // ★蓝牙没开就别静默扫描（SDK 会静默 return，徒劳转 12s）
    try {
        android.bluetooth.BluetoothManager bm =
            (android.bluetooth.BluetoothManager) appCtx.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm == null || bm.getAdapter() == null || !bm.getAdapter().isEnabled()) {
            Log.d(TAG, "autoConnect: 蓝牙未开，跳过");
            return;
        }
    } catch (Exception ignored) {}
    autoConnectMac = savedMac;
    activate();
    try { AIRECBleManager.getInstance().startScan(); }
    catch (Exception e) { autoConnectMac = null; return; }
    main.postDelayed(() -> {
        if (autoConnectMac != null) {
            try { AIRECBleManager.getInstance().stopScan(); } catch (Exception ignored) {}
            autoConnectMac = null;     // 12s 没扫到=笔不在场，安静放弃，不假连
        }
    }, 12000);
}
```

`onDeviceFound`（PenController 里）匹配 `autoConnectMac` 就连——保持不变，因为它只在真广播时触发；连上后走第 2 节的握手确认，没回包就断开。`autoConnect=false` 这个连接参数**绝不能改成 true**（true 会让关机设备无限挂等，制造更糟的"isConnected 长期 true 却永远连不上"）。

> 保存 lastMac 的时机（`ScanActivity.onConnected`）建议也后移：等真回包/真连上后才 `putString(KEY_LAST_MAC)`，避免把一支假连上、没应答的笔记成"上次的笔"形成闭环。`ScanActivity.onConnected` 现在由 SDK 触发，最稳的做法是把"保存 MAC"挪到 `PenController.markPenResponded()` 首次点亮时做（用 `device.getAddress()`），ScanActivity 仅负责跳转。

---

## 6. 按优先级的实施清单

| # | 优先级 | 文件 / 方法 | 关键改动 | 为什么 |
|---|---|---|---|---|
| 1 | P0 | `PenController` 新增 `verifiedConnected`/`lastRxMs`/`markPenResponded()`/`isPenAlive()`；改 `onConnected`（清状态+发 `fetchDeviceInfo`+`handshakeTimeout`）、`onDisconnected`（复位）；6 个真回包回调各加 `markPenResponded()` | 见 §2 | **根治假连接**：把"已连"从指针判断改为"真回包"，并堵住 SDK 的 3s fallback-onConnected |
| 2 | P0 | `ConsultantActivity` 行 173/206/411/459/462/555 的 `penController.isConnected()` → `isPenAlive()` | 同名调用替换 | 网页/UI 的"已连接"指示和"能否开始录音"以真在线为准，不再因"以前连过"显示已连 |
| 3 | P0 | `ScanActivity.requestPermissionsAndScan` 加 `ensureBluetoothOn()` | 见 §1.1 | **扫描必须先有蓝牙**：避免 SDK 静默 return 导致永久转圈假扫描 |
| 4 | P0 | `ScanActivity` 加 `lastSeen` 表 + `pruneTask`（8s 过期）+ `startFreshScan()`；`onDeviceFound` 刷新时间戳 | 见 §1.2 | **根治"上次那支笔总在"**：关机/走开的笔 8s 后自动消失 |
| 5 | P1 | `PenController` 加心跳 `heartbeat`/`startHeartbeat`/`stopHeartbeat`（8s 间隔、4s 超时、连续 2 次判离线→disconnect+刷新 UI） | 见 §3 | 笔关机时 Android 不保证及时回 `onDisconnected`，必须主动探活，否则"关机了还显示已连" |
| 6 | P1 | `PenController.startRecording` 入口 `isConnected()`→`isPenAlive()`，加 `preStartVerifyThenRecord`/`preStartTimeout`，原主体抽成 `doStartRecord()` | 见 §4 | **录音前必须收到回包才真开始**，与现有 `confirmTimeout` 串成双闸门，杜绝假录 |
| 7 | P1 | `PenController.autoConnect` 加蓝牙校验；连上仍走 verifiedConnected | 见 §5 | 蓝牙没开/笔不在场时安静放弃，不静默假连 |
| 8 | P2 | `ScanActivity.onDeviceFound` 加 `rssi < -90` 门槛；`DeviceAdapter` 可加"信号弱/标灰"展示 | 见 §1.2 + DeviceAdapter | 远到根本连不上的弱信号笔不列出/标不可连，避免误连 |
| 9 | P2 | 保存 `KEY_LAST_MAC` 时机后移到 `markPenResponded()` 首次点亮 | 见 §5 末 | 只把"真应答过"的笔记为上次的笔，避免假连闭环 |
| 10 | P3（可选改 SDK） | `AIRECBleManager` 加 `queryRecordStatus(){ e(a(15,null,-1)); }` 只发 0x0F | 单发 0x0F | 心跳更省、对录音/下载通道干扰最小（0x0F 只读不触发 0xFD） |

### 真机验收要点
- **关机笔不再出现**：笔开机扫到 → 关机 8s 内从列表消失（验 #4）。
- **蓝牙关时**：点连接立即提示"请打开蓝牙"，不转圈（验 #3）。
- **假连接消失**：笔休眠/半死时连接，5s 后报"没响应"并保持"未连接"，不显示已连（验 #1/#2/#6）。
- **掉线及时**：连上后把笔关机/拿远，约 12~16s 内 UI 自动变"未连接"（验 #5）。
- **不假录**：笔没开机点"开启陪伴"，报"录音笔没开机/没响应"，不进录音 UI（验 #6）。

### 关键约束（别踩）
- `connectGatt(autoConnect=false)` 保持不变，**不要改 true**。
- `onCharacteristicWrite`/写成功**不能**当活性证据，活性只认 `onXxxUpdated/onRecordStatusQueried` 等业务回包（已体现在 `markPenResponded` 只挂在这些回调上）。
- 心跳与下载/录音互斥：录音中/下载中放宽间隔或不主动发命令，靠业务帧刷新 `lastRxMs`（见 §3 `busy` 分支）。

相关文件（绝对路径）：
- `/Users/ginlater/code/persist创业/sm身美/android/gp-system/android_app/app/src/main/java/com/airec/bledemo/PenController.java`
- `/Users/ginlater/code/persist创业/sm身美/android/gp-system/android_app/app/src/main/java/com/airec/bledemo/ScanActivity.java`
- `/Users/ginlater/code/persist创业/sm身美/android/gp-system/android_app/app/src/main/java/com/airec/bledemo/ConsultantActivity.java`（约 9 处 `isConnected()` 调用点：行 68/173/206/411/459/462/508/544/555）
- `/Users/ginlater/code/persist创业/sm身美/android/gp-system/android_app/app/src/main/java/com/airec/bledemo/adapter/DeviceAdapter.java`（#8 标灰展示）