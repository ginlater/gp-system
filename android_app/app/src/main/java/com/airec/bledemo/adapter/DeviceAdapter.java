package com.airec.bledemo.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.airec.blesdk.AIRECBleDevice;
import com.airec.bledemo.R;

import java.util.List;

public class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.VH> {

    public interface OnConnectClick { void onClick(AIRECBleDevice device); }

    private final List<AIRECBleDevice> list;
    private final OnConnectClick listener;

    public DeviceAdapter(List<AIRECBleDevice> list, OnConnectClick listener) {
        this.list = list;
        this.listener = listener;
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_device, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        AIRECBleDevice d = list.get(pos);
        h.tvName.setText(d.getName());
        h.tvAddress.setText(d.getAddress());
        h.btnConnect.setOnClickListener(v -> listener.onClick(d));
    }

    @Override public int getItemCount() { return list.size(); }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvName, tvAddress;
        Button btnConnect;
        VH(View v) {
            super(v);
            tvName = v.findViewById(R.id.tv_device_name);
            tvAddress = v.findViewById(R.id.tv_device_address);
            btnConnect = v.findViewById(R.id.btn_connect);
        }
    }
}
