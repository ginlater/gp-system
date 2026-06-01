package com.airec.bledemo.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.airec.blesdk.AIRECBleFile;
import com.airec.bledemo.FileDownloadManager;
import com.airec.bledemo.R;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FileAdapter extends RecyclerView.Adapter<FileAdapter.VH> {

    public interface OnPlayListener {
        void onPlay(AIRECBleFile file, String localPath);
    }

    public interface OnDownloadListener {
        void onDownload(AIRECBleFile file, int position);
    }

    // 下载状态
    static final int STATE_IDLE        = 0;
    static final int STATE_DOWNLOADING = 1;
    static final int STATE_DONE        = 2;

    private final List<AIRECBleFile> list;
    private final Map<String, Integer> stateMap    = new HashMap<>();
    private final Map<String, Integer> progressMap = new HashMap<>();

    private OnPlayListener     playListener;
    private OnDownloadListener downloadListener;

    public FileAdapter(List<AIRECBleFile> list) {
        this.list = list;
    }

    public void setOnPlayListener(OnPlayListener l)         { this.playListener = l; }
    public void setOnDownloadListener(OnDownloadListener l) { this.downloadListener = l; }

    /** 外部更新某文件的下载进度 */
    public void updateProgress(String fileName, int progress) {
        stateMap.put(fileName, STATE_DOWNLOADING);
        progressMap.put(fileName, progress);
        int pos = indexOf(fileName);
        if (pos >= 0) notifyItemChanged(pos);
    }

    /** 外部标记某文件下载完成 */
    public void markDone(String fileName) {
        stateMap.put(fileName, STATE_DONE);
        progressMap.put(fileName, 100);
        int pos = indexOf(fileName);
        if (pos >= 0) notifyItemChanged(pos);
    }

    /** 外部标记某文件下载失败，重置为 idle */
    public void markError(String fileName) {
        stateMap.remove(fileName);
        progressMap.remove(fileName);
        int pos = indexOf(fileName);
        if (pos >= 0) notifyItemChanged(pos);
    }

    /** 重置所有 downloading 状态（下载失败保底） */
    public void resetAllDownloading() {
        boolean changed = false;
        for (Map.Entry<String, Integer> e : new HashMap<>(stateMap).entrySet()) {
            if (e.getValue() == STATE_DOWNLOADING) {
                stateMap.remove(e.getKey());
                progressMap.remove(e.getKey());
                changed = true;
            }
        }
        if (changed) notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_file, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        AIRECBleFile f = list.get(pos);
        h.tvName.setText(f.getFileName());
        h.tvTime.setText(f.getCreateTime());
        h.tvDuration.setText(f.getDurationStr());
        h.tvSize.setText(f.getFileSizeStr());

        String key = f.getFileName();

        // 优先检查 FileDownloadManager 缓存（跨 adapter 刷新保持状态）
        int state;
        if (FileDownloadManager.getInstance().isDownloaded(key)) {
            state = STATE_DONE;
        } else {
            Integer s = stateMap.get(key);
            state = s == null ? STATE_IDLE : s;
        }

        int progress = progressMap.containsKey(key) ? progressMap.get(key) : 0;

        switch (state) {
            case STATE_DOWNLOADING:
                h.btnAction.setImageResource(R.drawable.ic_download);
                h.btnAction.setEnabled(false);
                h.btnAction.setAlpha(0.4f);
                h.progressBar.setVisibility(View.VISIBLE);
                h.progressBar.setProgress(progress);
                h.btnAction.setOnClickListener(null);
                break;

            case STATE_DONE:
                h.btnAction.setImageResource(R.drawable.ic_play_green);
                h.btnAction.setEnabled(true);
                h.btnAction.setAlpha(1.0f);
                h.progressBar.setVisibility(View.GONE);
                h.btnAction.setOnClickListener(v -> {
                    if (playListener != null) {
                        String localPath = FileDownloadManager.getInstance().getLocalPath(key);
                        playListener.onPlay(f, localPath);
                    }
                });
                break;

            default: // IDLE
                h.btnAction.setImageResource(R.drawable.ic_download);
                h.btnAction.setEnabled(true);
                h.btnAction.setAlpha(1.0f);
                h.progressBar.setVisibility(View.GONE);
                h.btnAction.setOnClickListener(v -> {
                    if (downloadListener != null) downloadListener.onDownload(f, pos);
                });
                break;
        }
    }

    @Override
    public int getItemCount() { return list.size(); }

    private int indexOf(String fileName) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getFileName().equals(fileName)) return i;
        }
        return -1;
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView    tvName, tvTime, tvDuration, tvSize;
        ImageButton btnAction;
        ProgressBar progressBar;

        VH(View v) {
            super(v);
            tvName      = v.findViewById(R.id.tv_file_name);
            tvTime      = v.findViewById(R.id.tv_file_time);
            tvDuration  = v.findViewById(R.id.tv_file_duration);
            tvSize      = v.findViewById(R.id.tv_file_size);
            btnAction   = v.findViewById(R.id.btn_action);
            progressBar = v.findViewById(R.id.progress_download);
        }
    }
}
