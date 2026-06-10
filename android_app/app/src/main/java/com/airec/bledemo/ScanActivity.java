package com.airec.bledemo;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.airec.blesdk.AIRECBleCallback;
import com.airec.blesdk.AIRECBleDevice;
import com.airec.blesdk.AIRECBleFile;
import com.airec.blesdk.AIRECBleManager;
import com.airec.bledemo.adapter.DeviceAdapter;
import com.airec.bledemo.databinding.ActivityScanBinding;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ScanActivity extends AppCompatActivity {

    private ActivityScanBinding binding;
    private DeviceAdapter adapter;
    private final List<AIRECBleDevice> deviceList = new ArrayList<>();
    private final Map<String, Long> lastSeen = new HashMap<>();   // mac → 最后一次扫到的时刻(elapsedRealtime)
    private static final long STALE_MS = 8000;                    // 超过这么久没再广播 → 从列表移除(关机/走开的笔自动消失)
    private static final int RSSI_MIN = -90;                      // 信号弱于此丢弃(太远根本连不上)
    private final Handler scanUi = new Handler(Looper.getMainLooper());
    private static final int REQ_PERMISSION = 100;
    static final String PEN_PREFS = "pen_prefs";
    static final String KEY_LAST_MAC = "last_mac";
    static final String KEY_LAST_NAME = "last_name";
    private String lastMac;                 // 上次连过的笔 MAC，用于自动连接
    private boolean autoConnectTried = false;

    // 新鲜度过期：每 1.5s 移除超过 STALE_MS 没再收到广播的设备，关机/走开的笔自动从列表消失。
    private final Runnable pruneTask = new Runnable() {
        @Override public void run() {
            long now = SystemClock.elapsedRealtime();
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
            scanUi.postDelayed(this, 1500);
        }
    };

    /** 扫描前硬校验蓝牙是否开启（SDK 的 startScan 在蓝牙关时是静默 return，会让 UI 永久转圈假扫描）。 */
    private boolean ensureBluetoothOn() {
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter adp = bm != null ? bm.getAdapter() : null;
        if (adp == null) {
            binding.progressBar.setVisibility(View.GONE);
            binding.tvStatus.setText("此设备不支持蓝牙");
            return false;
        }
        if (!adp.isEnabled()) {
            binding.progressBar.setVisibility(View.GONE);
            binding.tvStatus.setText("蓝牙未开启，请先打开蓝牙");
            try { startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); }
            catch (Exception ignored) { Toast.makeText(this, "请在系统设置里打开蓝牙后重试", Toast.LENGTH_LONG).show(); }
            return false;
        }
        return true;
    }

    /** 每次扫描都重新攒列表 + 启动过期定时器（不再让上次的设备永久留存）。 */
    private void startFreshScan() {
        deviceList.clear();
        lastSeen.clear();
        adapter.notifyDataSetChanged();
        binding.tvStatus.setText("扫描中...");
        binding.progressBar.setVisibility(View.VISIBLE);
        AIRECBleManager.getInstance().startScan();
        scanUi.removeCallbacks(pruneTask);
        scanUi.postDelayed(pruneTask, 1500);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityScanBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        setTitle("扫描设备");

        lastMac = getSharedPreferences(PEN_PREFS, MODE_PRIVATE).getString(KEY_LAST_MAC, null);

        adapter = new DeviceAdapter(deviceList, device -> {
            binding.progressBar.setVisibility(View.VISIBLE);
            binding.tvStatus.setText("连接中：" + device.getName() + "...");
            AIRECBleManager.getInstance().stopScan();
            AIRECBleManager.getInstance().connect(device);
        });

        binding.recyclerView.setLayoutManager(new LinearLayoutManager(this));
        binding.recyclerView.setAdapter(adapter);

        binding.btnScan.setOnClickListener(v -> requestPermissionsAndScan());

        AIRECBleManager.getInstance().setCallback(new AIRECBleCallback() {

            @Override
            public void onDeviceFound(AIRECBleDevice device) {
                // ★SDK 回调现在在后台线程(BLE 已挪后台治下载卡死)，这里碰 UI 必须切回主线程，否则 CalledFromWrongThreadException 闪退。
                runOnUiThread(() -> {
                    if (device == null) return;
                    // 信号太弱(太远/半睡)直接不收，避免列出根本连不上的笔
                    if (device.getRssi() != 0 && device.getRssi() < RSSI_MIN) return;
                    String mac = device.getAddress();
                    lastSeen.put(mac, SystemClock.elapsedRealtime());   // ★刷新新鲜度，过期定时器据此移除
                    boolean exists = false;
                    for (AIRECBleDevice d : deviceList) {
                        if (d.getAddress().equals(mac)) { exists = true; break; }
                    }
                    if (!exists) {
                        deviceList.add(device);
                        adapter.notifyItemInserted(deviceList.size() - 1);
                    }
                    binding.tvStatus.setText("发现 " + deviceList.size() + " 台设备，点击连接");
                    // 自动连接上次用过的录音笔（一发现就连，无需手动点；过期逻辑保证关机笔不会进这里）
                    if (!autoConnectTried && lastMac != null && lastMac.equals(mac)) {
                        autoConnectTried = true;
                        binding.progressBar.setVisibility(View.VISIBLE);
                        binding.tvStatus.setText("自动连接上次的录音笔：" + device.getName() + "…");
                        AIRECBleManager.getInstance().stopScan();
                        AIRECBleManager.getInstance().connect(device);
                    }
                });
            }

            @Override
            public void onConnected(AIRECBleDevice device) {
                runOnUiThread(() -> {
                    // 记住这支笔，下次开始录音时自动连
                    getSharedPreferences(PEN_PREFS, MODE_PRIVATE).edit()
                            .putString(KEY_LAST_MAC, device.getAddress())
                            .putString(KEY_LAST_NAME, device.getName())
                            .apply();
                    // 切换到 mainCallback，并手动触发 onConnected 确保 MainActivity 收到
                    AIRECBleCallback main = App.getMainCallback();
                    if (main != null) {
                        AIRECBleManager.getInstance().setCallback(main);
                        main.onConnected(device);
                    }
                    setResult(RESULT_OK, new Intent());
                    finish();
                });
            }

            @Override
            public void onDisconnected(AIRECBleDevice device, String reason) {
                runOnUiThread(() -> {
                    binding.progressBar.setVisibility(View.GONE);
                    String msg = (reason != null && reason.contains("超时"))
                            ? "连接超时，请靠近设备重试" : "连接失败，请重试";
                    binding.tvStatus.setText(msg);
                    Toast.makeText(ScanActivity.this, msg, Toast.LENGTH_SHORT).show();
                });
            }

            @Override public void onDeviceInfoUpdated(AIRECBleDevice device) {}
            @Override public void onFileListUpdated(List<AIRECBleFile> files) {}
            @Override public void onFileDeleted(String fileName, boolean success) {}
            @Override public void onRecordStateChanged(boolean recording, String fileName) {}
            @Override public void onRecordDurationUpdated(long durationSec) {}
            @Override public void onFirmwareVersionReceived(String version) {}

            @Override
            public void onBluetoothStateChanged(boolean enabled) {
                runOnUiThread(() -> {
                    if (!enabled) {
                        binding.progressBar.setVisibility(View.GONE);
                        binding.tvStatus.setText("蓝牙已关闭，请开启蓝牙");
                    }
                });
            }
        });

        requestPermissionsAndScan();
    }

    private void requestPermissionsAndScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.BLUETOOTH_SCAN,
                                Manifest.permission.BLUETOOTH_CONNECT},
                        REQ_PERMISSION);
                return;
            }
        } else {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_PERMISSION);
                return;
            }
        }
        if (ensureBluetoothOn()) startFreshScan();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (ensureBluetoothOn()) startFreshScan();
            } else {
                binding.tvStatus.setText("缺少权限，无法扫描");
                binding.progressBar.setVisibility(View.GONE);
                Toast.makeText(this, "需要蓝牙权限才能扫描设备", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        scanUi.removeCallbacks(pruneTask);
        AIRECBleManager.getInstance().stopScan();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
