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

import java.util.ArrayList;
import java.util.List;

public class ScanActivity extends AppCompatActivity {

    private ActivityScanBinding binding;
    private DeviceAdapter adapter;
    private final List<AIRECBleDevice> deviceList = new ArrayList<>();
    private static final int REQ_PERMISSION = 100;
    static final String PEN_PREFS = "pen_prefs";
    static final String KEY_LAST_MAC = "last_mac";
    static final String KEY_LAST_NAME = "last_name";
    private String lastMac;                 // 上次连过的笔 MAC，用于自动连接
    private boolean autoConnectTried = false;

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

        binding.btnScan.setOnClickListener(v -> {
            deviceList.clear();
            adapter.notifyDataSetChanged();
            binding.tvStatus.setText("扫描中...");
            binding.progressBar.setVisibility(View.VISIBLE);
            requestPermissionsAndScan();
        });

        AIRECBleManager.getInstance().setCallback(new AIRECBleCallback() {

            @Override
            public void onDeviceFound(AIRECBleDevice device) {
                for (AIRECBleDevice d : deviceList) {
                    if (d.getAddress().equals(device.getAddress())) return;
                }
                deviceList.add(device);
                adapter.notifyItemInserted(deviceList.size() - 1);
                binding.tvStatus.setText("发现 " + deviceList.size() + " 台设备，点击连接");
                // 自动连接上次用过的录音笔（一发现就连，无需手动点）
                if (!autoConnectTried && lastMac != null && lastMac.equals(device.getAddress())) {
                    autoConnectTried = true;
                    binding.progressBar.setVisibility(View.VISIBLE);
                    binding.tvStatus.setText("自动连接上次的录音笔：" + device.getName() + "…");
                    AIRECBleManager.getInstance().stopScan();
                    AIRECBleManager.getInstance().connect(device);
                }
            }

            @Override
            public void onConnected(AIRECBleDevice device) {
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
            }

            @Override
            public void onDisconnected(AIRECBleDevice device, String reason) {
                binding.progressBar.setVisibility(View.GONE);
                String msg = (reason != null && reason.contains("超时"))
                        ? "连接超时，请靠近设备重试" : "连接失败，请重试";
                binding.tvStatus.setText(msg);
                Toast.makeText(ScanActivity.this, msg, Toast.LENGTH_SHORT).show();
            }

            @Override public void onDeviceInfoUpdated(AIRECBleDevice device) {}
            @Override public void onFileListUpdated(List<AIRECBleFile> files) {}
            @Override public void onFileDeleted(String fileName, boolean success) {}
            @Override public void onRecordStateChanged(boolean recording, String fileName) {}
            @Override public void onRecordDurationUpdated(long durationSec) {}
            @Override public void onFirmwareVersionReceived(String version) {}

            @Override
            public void onBluetoothStateChanged(boolean enabled) {
                if (!enabled) {
                    binding.progressBar.setVisibility(View.GONE);
                    binding.tvStatus.setText("蓝牙已关闭，请开启蓝牙");
                }
            }
        });

        binding.tvStatus.setText("扫描中...");
        binding.progressBar.setVisibility(View.VISIBLE);
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
        AIRECBleManager.getInstance().startScan();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                AIRECBleManager.getInstance().startScan();
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
        AIRECBleManager.getInstance().stopScan();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
