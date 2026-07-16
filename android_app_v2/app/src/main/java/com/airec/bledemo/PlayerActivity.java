package com.airec.bledemo;

import android.content.ContentValues;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;

import com.airec.bledemo.databinding.ActivityPlayerBinding;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 自定义播放器：支持 WAV / OGG / 标准格式。
 * ExoPlayer 优先，ExoPlayer 失败时自动切换 AudioTrack 播放 WAV。
 */
public class PlayerActivity extends AppCompatActivity {

    public static final String EXTRA_FILE_PATH = "file_path";
    public static final String EXTRA_FILE_NAME = "file_name";
    public static final String EXTRA_FILE_SIZE = "file_size";
    private static final String TAG = "AIREC_PLAYER";

    private ActivityPlayerBinding binding;
    private ExoPlayer exoPlayer;
    private AudioTrack audioTrack;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private boolean isPlaying = false;
    private boolean useAudioTrack = false; // true = AudioTrack 模式
    private String originalFilePath = null;
    private String originalFileName = null;
    private String playingWavPath = null;

    // AudioTrack 播放状态
    private byte[] wavPcmData = null;
    private int wavSampleRate = 16000;
    private int wavChannels = 1;
    private int wavBitsPerSample = 16;
    private int pcmDataOffset = 44; // WAV data 起始偏移
    private int pcmDataSize = 0;
    private volatile int playPosition = 0; // 当前播放位置（字节）
    private volatile boolean atPlaying = false;
    private Thread atPlayThread = null;

    private final Runnable progressUpdater = new Runnable() {
        @Override public void run() {
            if (useAudioTrack && atPlaying) {
                int totalMs = pcmDataSize > 0 ? (int)((long) pcmDataSize * 1000 / (wavSampleRate * wavChannels * wavBitsPerSample / 8)) : 0;
                int curMs = pcmDataSize > 0 ? (int)((long) playPosition * 1000 / (wavSampleRate * wavChannels * wavBitsPerSample / 8)) : 0;
                binding.seekbar.setProgress(curMs);
                binding.tvCurrent.setText(fmtTime(curMs));
                handler.postDelayed(this, 200);
            } else if (exoPlayer != null && isPlaying) {
                int cur = (int) exoPlayer.getCurrentPosition();
                binding.seekbar.setProgress(cur);
                binding.tvCurrent.setText(fmtTime(cur));
                handler.postDelayed(this, 500);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityPlayerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        String filePath = getIntent().getStringExtra(EXTRA_FILE_PATH);
        String fileName = getIntent().getStringExtra(EXTRA_FILE_NAME);
        String fileSize = getIntent().getStringExtra(EXTRA_FILE_SIZE);

        setTitle("播放器");
        binding.tvFileName.setText(fileName != null ? fileName : "未知文件");
        binding.tvFileSize.setText(fileSize != null ? "大小：" + fileSize : "");
        originalFileName = fileName != null ? fileName :
                (filePath != null ? new File(filePath).getName() : "unknown");

        if (filePath == null) { Toast.makeText(this, "文件路径无效", Toast.LENGTH_SHORT).show(); finish(); return; }
        if (!new File(filePath).exists() || new File(filePath).length() == 0) {
            Toast.makeText(this, "文件不存在或为空", Toast.LENGTH_LONG).show(); finish(); return;
        }

        originalFilePath = filePath;
        setupControls();
        startConvertAndPlay(filePath);
    }

    // ─── 转码 + 播放 ────────────────────────────────────────────────────────

    private void startConvertAndPlay(String filePath) {
        binding.btnPlayPause.setEnabled(false);
        binding.tvDuration.setText("转换中...");

        executor.execute(() -> {
            String result = AudioConverter.toWav(filePath);
            handler.post(() -> {
                if (result != null && new File(result).exists()) {
                    playingWavPath = result;
                    // WAV 文件用 AudioTrack 直接播放（避免 MediaCodec bug）
                    if (AudioConverter.isWav(result)) {
                        Log.d(TAG, "Playing WAV with AudioTrack: " + result);
                        startAudioTrackPlay(result);
                    } else {
                        // OGG 等格式尝试 ExoPlayer
                        Log.d(TAG, "Playing with ExoPlayer: " + result);
                        playWithExoPlayer(result);
                    }
                } else {
                    binding.tvDuration.setText("--:--");
                    binding.btnPlayPause.setEnabled(false);
                    Toast.makeText(this, "格式转换失败", Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    // ─── AudioTrack 播放 WAV（不依赖 MediaCodec）─────────────────────────────

    private void startAudioTrackPlay(String wavPath) {
        useAudioTrack = true;
        try {
            // 解析 WAV 头
            RandomAccessFile raf = new RandomAccessFile(wavPath, "r");
            byte[] header = new byte[44];
            raf.readFully(header);

            // 验证 RIFF/WAVE
            if (header[0]!='R'||header[1]!='I'||header[2]!='F'||header[3]!='F'
                    ||header[8]!='W'||header[9]!='A'||header[10]!='V'||header[11]!='E') {
                raf.close();
                Toast.makeText(this, "无效的 WAV 文件", Toast.LENGTH_SHORT).show();
                return;
            }

            wavChannels = (header[22] & 0xFF) | ((header[23] & 0xFF) << 8);
            wavSampleRate = (header[24] & 0xFF) | ((header[25] & 0xFF) << 8)
                    | ((header[26] & 0xFF) << 16) | ((header[27] & 0xFF) << 24);
            wavBitsPerSample = (header[34] & 0xFF) | ((header[35] & 0xFF) << 8);
            pcmDataSize = (header[40] & 0xFF) | ((header[41] & 0xFF) << 8)
                    | ((header[42] & 0xFF) << 16) | ((header[43] & 0xFF) << 24);

            // 如果 data size 不合理，用文件大小减去头
            if (pcmDataSize <= 0 || pcmDataSize > raf.length()) {
                pcmDataSize = (int)(raf.length() - 44);
            }

            Log.d(TAG, "WAV: " + wavSampleRate + "Hz " + wavChannels + "ch "
                    + wavBitsPerSample + "bit data=" + pcmDataSize + "B");

            // 读取 PCM 数据
            wavPcmData = new byte[pcmDataSize];
            raf.seek(44);
            raf.readFully(wavPcmData);
            raf.close();

            int durationMs = (int)((long) pcmDataSize * 1000 / (wavSampleRate * wavChannels * wavBitsPerSample / 8));
            binding.seekbar.setMax(durationMs);
            binding.tvDuration.setText(fmtTime(durationMs));
            binding.tvCurrent.setText("00:00");
            binding.btnPlayPause.setEnabled(true);

            // 开始播放
            atPlay();

        } catch (Exception e) {
            Log.e(TAG, "AudioTrack init failed", e);
            Toast.makeText(this, "播放失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void atPlay() {
        if (wavPcmData == null) return;
        atPlaying = true;
        isPlaying = true;
        binding.btnPlayPause.setImageResource(R.drawable.ic_pause);
        handler.post(progressUpdater);

        int channelConfig = wavChannels == 2 ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
        int encoding = wavBitsPerSample == 8 ? AudioFormat.ENCODING_PCM_8BIT : AudioFormat.ENCODING_PCM_16BIT;
        int bufSize = AudioTrack.getMinBufferSize(wavSampleRate, channelConfig, encoding);
        if (bufSize < 4096) bufSize = 4096;

        audioTrack = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(wavSampleRate)
                        .setChannelMask(channelConfig)
                        .setEncoding(encoding)
                        .build())
                .setBufferSizeInBytes(bufSize * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();

        audioTrack.play();
        final int bs = bufSize;

        atPlayThread = new Thread(() -> {
            int pos = playPosition;
            while (atPlaying && pos < pcmDataSize) {
                int toWrite = Math.min(bs, pcmDataSize - pos);
                int written = audioTrack.write(wavPcmData, pos, toWrite);
                if (written > 0) {
                    pos += written;
                    playPosition = pos;
                } else {
                    break;
                }
            }
            // 播放结束
            if (atPlaying) {
                handler.post(() -> {
                    atPlaying = false;
                    isPlaying = false;
                    handler.removeCallbacks(progressUpdater);
                    binding.btnPlayPause.setImageResource(R.drawable.ic_play);
                    binding.seekbar.setProgress(0);
                    binding.tvCurrent.setText("00:00");
                    playPosition = 0;
                });
            }
        }, "AudioTrackPlay");
        atPlayThread.start();
    }

    private void atPause() {
        atPlaying = false;
        isPlaying = false;
        if (audioTrack != null) {
            try { audioTrack.pause(); } catch (Exception ignored) {}
        }
        handler.removeCallbacks(progressUpdater);
        binding.btnPlayPause.setImageResource(R.drawable.ic_play);
    }

    private void atResume() {
        if (wavPcmData == null) return;
        if (playPosition >= pcmDataSize) playPosition = 0;
        atPlaying = true;
        isPlaying = true;
        binding.btnPlayPause.setImageResource(R.drawable.ic_pause);
        handler.post(progressUpdater);

        if (audioTrack != null && audioTrack.getState() == AudioTrack.STATE_INITIALIZED) {
            audioTrack.play();
        }

        final int bufSize = AudioTrack.getMinBufferSize(wavSampleRate,
                wavChannels == 2 ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO,
                wavBitsPerSample == 8 ? AudioFormat.ENCODING_PCM_8BIT : AudioFormat.ENCODING_PCM_16BIT);

        atPlayThread = new Thread(() -> {
            int pos = playPosition;
            while (atPlaying && pos < pcmDataSize) {
                int toWrite = Math.min(Math.max(bufSize, 4096), pcmDataSize - pos);
                int written = audioTrack.write(wavPcmData, pos, toWrite);
                if (written > 0) { pos += written; playPosition = pos; } else break;
            }
            if (atPlaying) {
                handler.post(() -> {
                    atPlaying = false; isPlaying = false;
                    handler.removeCallbacks(progressUpdater);
                    binding.btnPlayPause.setImageResource(R.drawable.ic_play);
                    binding.seekbar.setProgress(0); binding.tvCurrent.setText("00:00");
                    playPosition = 0;
                });
            }
        }, "AudioTrackPlay");
        atPlayThread.start();
    }

    private void atSeek(int posMs) {
        int byteRate = wavSampleRate * wavChannels * wavBitsPerSample / 8;
        int bytePos = (int)((long) posMs * byteRate / 1000);
        // 对齐到 block
        int blockAlign = wavChannels * wavBitsPerSample / 8;
        bytePos = (bytePos / blockAlign) * blockAlign;
        playPosition = Math.max(0, Math.min(bytePos, pcmDataSize));
    }

    // ─── ExoPlayer（标准格式 fallback）────────────────────────────────────────

    @OptIn(markerClass = UnstableApi.class)
    private void playWithExoPlayer(String path) {
        useAudioTrack = false;
        if (exoPlayer != null) { exoPlayer.release(); exoPlayer = null; }

        exoPlayer = new ExoPlayer.Builder(this).build();
        exoPlayer.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    long dur = exoPlayer.getDuration();
                    binding.seekbar.setMax(dur > 0 ? (int) dur : 0);
                    binding.tvDuration.setText(fmtTime((int) dur));
                    binding.tvCurrent.setText("00:00");
                    binding.btnPlayPause.setEnabled(true);
                    exoPlayer.play();
                } else if (state == Player.STATE_ENDED) {
                    isPlaying = false;
                    handler.removeCallbacks(progressUpdater);
                    binding.btnPlayPause.setImageResource(R.drawable.ic_play);
                    binding.seekbar.setProgress(0);
                    binding.tvCurrent.setText("00:00");
                }
            }
            @Override public void onIsPlayingChanged(boolean playing) {
                isPlaying = playing;
                binding.btnPlayPause.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
                if (playing) handler.post(progressUpdater);
                else handler.removeCallbacks(progressUpdater);
            }
            @Override public void onPlayerError(PlaybackException error) {
                isPlaying = false;
                handler.removeCallbacks(progressUpdater);
                Log.e(TAG, "ExoPlayer error: " + error.getMessage(), error);
                Toast.makeText(PlayerActivity.this, "播放失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(new File(path))));
        exoPlayer.prepare();
    }

    // ─── 控件 ────────────────────────────────────────────────────────────────

    private void setupControls() {
        binding.btnPlayPause.setEnabled(false);
        binding.btnPlayPause.setOnClickListener(v -> {
            if (useAudioTrack) {
                if (atPlaying) atPause(); else atResume();
            } else {
                if (exoPlayer == null) return;
                if (exoPlayer.isPlaying()) exoPlayer.pause(); else exoPlayer.play();
            }
        });
        binding.btnRewind.setOnClickListener(v -> seek(-10_000));
        binding.btnForward.setOnClickListener(v -> seek(10_000));
        binding.seekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (fromUser) {
                    if (useAudioTrack) {
                        atSeek(p);
                        binding.tvCurrent.setText(fmtTime(p));
                    } else if (exoPlayer != null) {
                        exoPlayer.seekTo(p);
                        binding.tvCurrent.setText(fmtTime(p));
                    }
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        binding.btnSaveLocal.setOnClickListener(v -> saveToDownloads());
    }

    private void seek(int deltaMs) {
        if (useAudioTrack) {
            int byteRate = wavSampleRate * wavChannels * wavBitsPerSample / 8;
            int curMs = byteRate > 0 ? (int)((long) playPosition * 1000 / byteRate) : 0;
            atSeek(curMs + deltaMs);
        } else if (exoPlayer != null) {
            long t = Math.max(0, Math.min(exoPlayer.getCurrentPosition() + deltaMs, exoPlayer.getDuration()));
            exoPlayer.seekTo(t);
            binding.seekbar.setProgress((int) t);
            binding.tvCurrent.setText(fmtTime((int) t));
        }
    }

    // ─── 保存 ────────────────────────────────────────────────────────────────

    private void saveToDownloads() {
        binding.btnSaveLocal.setEnabled(false);
        executor.execute(() -> {
            try {
                String wav = playingWavPath;
                if (wav == null || !new File(wav).exists()) wav = AudioConverter.toWav(originalFilePath);
                if (wav == null || !new File(wav).exists()) {
                    handler.post(() -> { binding.btnSaveLocal.setEnabled(true);
                        Toast.makeText(this, "文件不存在", Toast.LENGTH_SHORT).show(); });
                    return;
                }
                String name = originalFileName;
                if (name == null) name = new File(wav).getName();
                if (!name.toLowerCase().endsWith(".wav")) {
                    int dot = name.lastIndexOf('.');
                    name = (dot >= 0 ? name.substring(0, dot) : name) + ".wav";
                }
                final String destName = name;
                final File src = new File(wav);
                String saved = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                        ? saveMediaStore(src, destName) : saveLegacy(src, destName);
                handler.post(() -> { binding.btnSaveLocal.setEnabled(true);
                    Toast.makeText(this, saved != null ? "已保存：" + destName : "保存失败", Toast.LENGTH_LONG).show(); });
            } catch (Exception e) {
                handler.post(() -> { binding.btnSaveLocal.setEnabled(true);
                    Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show(); });
            }
        });
    }

    private String saveMediaStore(File src, String name) throws IOException {
        ContentValues v = new ContentValues();
        v.put(MediaStore.Downloads.DISPLAY_NAME, name);
        v.put(MediaStore.Downloads.MIME_TYPE, "audio/wav");
        v.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = getContentResolver().insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
        if (uri == null) return null;
        try (OutputStream os = getContentResolver().openOutputStream(uri); InputStream is = new FileInputStream(src)) {
            byte[] buf = new byte[8192]; int n; while ((n = is.read(buf)) >= 0) os.write(buf, 0, n);
        }
        v.clear(); v.put(MediaStore.Downloads.IS_PENDING, 0);
        getContentResolver().update(uri, v, null, null);
        return uri.toString();
    }

    private String saveLegacy(File src, String name) throws IOException {
        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!dir.exists()) dir.mkdirs();
        File dest = new File(dir, name);
        int i = 1;
        while (dest.exists()) { int d = name.lastIndexOf('.');
            dest = new File(dir, (d>=0?name.substring(0,d):name)+"_"+i+(d>=0?name.substring(d):"")); i++; }
        try (InputStream is = new FileInputStream(src); OutputStream os = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192]; int n; while ((n = is.read(buf)) >= 0) os.write(buf, 0, n);
        }
        sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(dest)));
        return dest.getAbsolutePath();
    }

    private String fmtTime(int ms) {
        if (ms < 0) ms = 0;
        int s = ms / 1000;
        return String.format("%02d:%02d", s / 60, s % 60);
    }

    @Override public boolean onSupportNavigateUp() { finish(); return true; }

    @Override protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(progressUpdater);
        atPlaying = false; isPlaying = false;
        if (audioTrack != null) { try { audioTrack.stop(); } catch (Exception ignored) {} audioTrack.release(); audioTrack = null; }
        if (exoPlayer != null) { exoPlayer.release(); exoPlayer = null; }
        wavPcmData = null;
        executor.shutdown();
    }
}
