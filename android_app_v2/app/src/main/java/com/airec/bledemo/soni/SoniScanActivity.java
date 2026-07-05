package com.airec.bledemo.soni;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.airec.bledemo.R;
import com.airec.bledemo.databinding.ActivityScanBinding;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 声云陪伴笔扫描/连接页 —— 行为对齐旧 ScanActivity（新鲜度过期、自动连上次那支、连上即收）。
 * 设备发现走 {@link SoniPenController.ScanListener}（cmd=1），连接结果靠轮询 isPenAlive（真在线判定后才算连上）。
 */
public class SoniScanActivity extends AppCompatActivity {

    private static final long STALE_MS = 8000;
    private static final int REQ_PERMISSION = 100;
    static final String PEN_PREFS = "pen_prefs";
    static final String KEY_LAST_MAC = "last_mac";

    private static final class Device { String name; String address; }

    private ActivityScanBinding binding;
    private final List<Device> deviceList = new ArrayList<>();
    private final Map<String, Long> lastSeen = new HashMap<>();
    private final Handler scanUi = new Handler(Looper.getMainLooper());
    private RecyclerView.Adapter<VH> adapter;
    private String lastMac;
    private boolean autoConnectTried = false;
    private boolean connecting = false;

    private final Runnable pruneTask = new Runnable() {
        @Override public void run() {
            long now = SystemClock.elapsedRealtime();
            boolean changed = false;
            for (int i = deviceList.size() - 1; i >= 0; i--) {
                Long t = lastSeen.get(deviceList.get(i).address);
                if (t == null || now - t > STALE_MS) {
                    lastSeen.remove(deviceList.get(i).address);
                    deviceList.remove(i);
                    changed = true;
                }
            }
            if (changed && !connecting) {
                adapter.notifyDataSetChanged();
                binding.tvStatus.setText(deviceList.isEmpty()
                        ? "未发现在广播的陪伴笔，请确认它已开机靠近"
                        : "发现 " + deviceList.size() + " 台设备，点击连接");
            }
            scanUi.postDelayed(this, 1500);
        }
    };

    /** 连接结果轮询：真在线(isPenAlive) → 记住并收页；超时报失败。 */
    private long connectStartMs = 0;
    private String connectTargetAddr = null;   // B4:本次点选要连的笔MAC,防"另一支自动连上被当成功"
    private final Runnable connectWatch = new Runnable() {
        @Override public void run() {
            SoniPenController pc = SoniPenController.instance();
            // B4:必须是"点选的那支"真连上才算成功——否则双笔场景自动重连抢连了另一支,
            //   isPenAlive 也为真会误判成功并把错的笔存成 last_mac(以后自动连错笔/录进别人的笔)
            if (pc != null && pc.isPenAlive()
                    && connectTargetAddr != null
                    && connectTargetAddr.equalsIgnoreCase(pc.currentConnectedMac())) {
                setResult(RESULT_OK, new Intent());
                finish();
                return;
            }
            if (SystemClock.elapsedRealtime() - connectStartMs > 15000) {
                connecting = false;
                binding.progressBar.setVisibility(View.GONE);
                binding.tvStatus.setText("连接失败，请靠近设备重试");
                Toast.makeText(SoniScanActivity.this, "连接失败，请重试", Toast.LENGTH_SHORT).show();
                return;
            }
            scanUi.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityScanBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        setTitle("连接陪伴笔");

        lastMac = getSharedPreferences(PEN_PREFS, MODE_PRIVATE).getString(KEY_LAST_MAC, null);

        // 引擎自举：冷启直进本页（adb 调试 / 进程死后恢复）时 MeiliActivity 没跑过，单例还没建。
        if (SoniPenController.instance() == null) {
            try {
                com.airec.bledemo.recording.RecordingModule.INSTANCE.init(getApplicationContext());
                com.airec.bledemo.recording.RecordingModule.INSTANCE.getController().isPenConnected();   // 触发 lazy 建引擎
                com.airec.bledemo.recording.RecordingModule.INSTANCE.refreshUploadContext();
            } catch (Throwable t) {
                android.util.Log.w("SoniScan", "引擎自举失败: " + t.getMessage());
            }
        }

        adapter = new RecyclerView.Adapter<VH>() {
            @NonNull @Override
            public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                return new VH(LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_device, parent, false));
            }
            @Override public void onBindViewHolder(@NonNull VH h, int pos) {
                Device d = deviceList.get(pos);
                h.tvName.setText(d.name == null || d.name.isEmpty() ? "陪伴笔" : d.name);
                h.tvAddress.setText(d.address);
                h.btnConnect.setOnClickListener(v -> connectTo(d));
            }
            @Override public int getItemCount() { return deviceList.size(); }
        };
        binding.recyclerView.setLayoutManager(new LinearLayoutManager(this));
        binding.recyclerView.setAdapter(adapter);
        binding.btnScan.setOnClickListener(v -> requestPermissionsAndScan());

        requestPermissionsAndScan();
    }

    private void connectTo(Device d) {
        SoniPenController pc = SoniPenController.instance();
        if (pc == null) { Toast.makeText(this, "引擎未就绪，请稍后重试", Toast.LENGTH_SHORT).show(); return; }
        connecting = true;
        binding.progressBar.setVisibility(View.VISIBLE);
        binding.tvStatus.setText("连接中：" + (d.name == null || d.name.isEmpty() ? d.address : d.name) + "…");
        pc.stopSearch();
        connectTargetAddr = d.address;   // B4:记住点选的笔,connectWatch 只认它真连上
        pc.connectTo(d.name, d.address);
        connectStartMs = SystemClock.elapsedRealtime();
        scanUi.removeCallbacks(connectWatch);
        scanUi.postDelayed(connectWatch, 800);
    }

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

    private void startFreshScan() {
        SoniPenController pc = SoniPenController.instance();
        if (pc == null) { binding.tvStatus.setText("引擎未就绪"); return; }
        deviceList.clear();
        lastSeen.clear();
        adapter.notifyDataSetChanged();
        connecting = false;
        binding.tvStatus.setText("扫描中…");
        binding.progressBar.setVisibility(View.VISIBLE);
        pc.setScanListener((name, address, productType) -> {
            if (address == null || address.isEmpty()) return;
            lastSeen.put(address, SystemClock.elapsedRealtime());
            Device found = null;
            for (Device d : deviceList) {
                if (address.equals(d.address)) { found = d; break; }
            }
            if (found == null) {
                Device d = new Device();
                d.name = name; d.address = address;
                deviceList.add(d);
                adapter.notifyItemInserted(deviceList.size() - 1);
            } else if (name != null && !name.isEmpty() && !name.equals(found.name)) {
                found.name = name;
                adapter.notifyDataSetChanged();
            }
            if (!connecting) binding.tvStatus.setText("发现 " + deviceList.size() + " 台设备，点击连接");
            // 自动连接上次用过的那支（新鲜度过期保证关机笔不会进这里）
            if (!autoConnectTried && lastMac != null && lastMac.equalsIgnoreCase(address)) {
                autoConnectTried = true;
                Device d = found != null ? found : deviceList.get(deviceList.size() - 1);
                binding.tvStatus.setText("自动连接上次的陪伴笔：" + (name == null ? "" : name) + "…");
                connectTo(d);
            }
        });
        pc.startSearch();
        scanUi.removeCallbacks(pruneTask);
        scanUi.postDelayed(pruneTask, 1500);
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
        scanUi.removeCallbacks(connectWatch);
        SoniPenController pc = SoniPenController.instance();
        if (pc != null) {
            pc.setScanListener(null);
            pc.stopSearch();
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView tvName, tvAddress;
        final Button btnConnect;
        VH(View v) {
            super(v);
            tvName = v.findViewById(R.id.tv_device_name);
            tvAddress = v.findViewById(R.id.tv_device_address);
            btnConnect = v.findViewById(R.id.btn_connect);
        }
    }
}
