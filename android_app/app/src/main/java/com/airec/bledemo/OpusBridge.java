package com.airec.bledemo;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;

/**
 * Opus 解码桥接：使用 JNA 直接调用 droidkit libopus.so 解码 BLE 实时 Opus 帧。
 *
 * 与 airecdev Flutter OpusHelper.decodeJieli() / iOS libopus decode 完全一致：
 * - 设备 BLE 实时流以固定步长分隔帧，每帧跳过 2 字节帧头后送入解码器
 * - 输出 16kHz 16bit 单声道 PCM
 *
 * 无需 CMake/Native 编译，纯 Java 实现。droidkit:opus 提供的预编译 libopus.so
 * 打包进 APK 后，JNA 5.14.0 可以通过 System.load(绝对路径) 自动找到并加载。
 */
public class OpusBridge {

    private static final String TAG = "AIREC_OPUS";

    // ─── JNA Opus 接口 ───────────────────────────────────────────────────

    /**
     * Opus 标准 C API (RFC 6716)。
     * droidkit:opus AAR 包含预编译 libopus.so (armeabi-v7a, arm64-v8a 等)。
     * JNA Native.load("opus") → dlopen("libopus.so")，由 Android 系统处理路径。
     */
    public interface LibOpus extends Library {
        /**
         * opus_decoder_create(int Fs, int channels, int[] error) → native pointer (long)
         */
        long opus_decoder_create(int Fs, int channels, int[] error);

        /**
         * opus_decode(long st, byte[] data, int len,
         *             short[] pcm, int frame_size, int decode_fec) → samples
         */
        int opus_decode(long st, byte[] data, int len,
                        short[] pcm, int frame_size, int decode_fec);

        /**
         * opus_decoder_destroy(long st)
         */
        void opus_decoder_destroy(long st);
    }

    // JNA 单例（延迟初始化，线程安全）
    private static volatile LibOpus s_libOpus = null;
    private static volatile boolean s_libInitAttempted = false;

    private static synchronized LibOpus getLibOpus() {
        if (s_libOpus != null) return s_libOpus;
        if (s_libInitAttempted) return null;
        s_libInitAttempted = true;

        // 策略1: 尝试 System.load(绝对路径) — 最可靠
        String libPath = findNativeLibPath();
        if (libPath != null) {
            try {
                System.load(libPath);
                Log.d(TAG, "libopus.so loaded via System.load: " + libPath);
            } catch (Throwable e) {
                Log.d(TAG, "System.load(\"" + libPath + "\") failed: " + e.getMessage()
                        + ", trying JNA Native.load");
                libPath = null;
            }
        }

        if (libPath == null) {
            // 策略2: JNA Native.load（让系统 linker 搜索标准路径）
            try {
                @SuppressWarnings("unchecked")
                LibOpus lib = (LibOpus) Native.load("opus", LibOpus.class);
                s_libOpus = lib;
                Log.d(TAG, "libopus.so loaded via JNA Native.load");
                return s_libOpus;
            } catch (Throwable e) {
                Log.e(TAG, "JNA Native.load failed: " + e.getMessage());
            }
        }

        // 策略3: JNA NativeLibrary.getInstance (明确指定库名)
        try {
            NativeLibrary libInstance =
                    NativeLibrary.getInstance("opus");
            @SuppressWarnings("unchecked")
            LibOpus lib = (LibOpus) Native.loadLibrary(
                    "opus", LibOpus.class);
            s_libOpus = lib;
            Log.d(TAG, "libopus.so loaded via JNA loadLibrary");
        } catch (Throwable e) {
            Log.e(TAG, "All JNA strategies failed: " + e.getMessage());
            logLoadedLibraries();
            s_libOpus = null;
        }
        return s_libOpus;
    }

    /**
     * 查找 droidkit:opus AAR 打包的 libopus.so 完整路径。
     * Android 系统在安装/启动 app 时会解压 AAR 的 .so 到:
     *   /data/data/<package>/lib/<abi>/libopus.so
     */
    private static String findNativeLibPath() {
        // ABI 优先级（从高到低）
        String[] abis = {"arm64-v8a", "armeabi-v7a", "armeabi", "x86_64", "x86"};

        // 先尝试通过 Context 获取 nativeLibraryDir
        try {
            Context ctx = getApplicationContext();
            if (ctx != null) {
                File libDir = ctx.getFilesDir(); // 备用: filesDir
                // 实际路径应该是 nativeLibraryDir，但需要 Context
                // Android 21+ Activity 有此方法
                try {
                    java.lang.reflect.Method m =
                            Context.class.getMethod("getNoBackupFilesDir");
                    File dir = (File) m.invoke(ctx);
                    if (dir != null) {
                        File nativeDir = new File(dir.getParent(), "lib");
                        for (String abi : abis) {
                            File f = new File(nativeDir, abi + "/libopus.so");
                            if (f.exists()) return f.getAbsolutePath();
                        }
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}

        // 备用：直接搜索 /data/data/ 下的目录
        try {
            String pkg = getPackageName();
            String dataDir = "/data/data/" + pkg + "/lib";
            File libDir = new File(dataDir);
            if (libDir.exists() && libDir.isDirectory()) {
                File[] subDirs = libDir.listFiles();
                if (subDirs != null) {
                    for (File sub : subDirs) {
                        if (sub.isDirectory()) {
                            File f = new File(sub, "libopus.so");
                            if (f.exists()) {
                                Log.d(TAG, "Found libopus.so at: " + f.getAbsolutePath());
                                return f.getAbsolutePath();
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        return null; // 找不到，返回 null 走 JNA Native.load 路径
    }

    private static Context s_appContext = null;

    /**
     * 设置 Application Context（需在 App.onCreate 中调用一次）。
     * 不设置则使用备用搜索策略。
     */
    public static void setAppContext(Context ctx) {
        s_appContext = ctx.getApplicationContext();
    }

    private static Context getApplicationContext() {
        if (s_appContext != null) return s_appContext;
        try {
            return (Context) Thread.currentThread()
                    .getContextClassLoader()
                    .loadClass("android.app.AppGlobals")
                    .getMethod("getInitialApplication")
                    .invoke(null);
        } catch (Throwable ignored) {}
        try {
            return (Context) Thread.currentThread()
                    .getContextClassLoader()
                    .loadClass("android.app.ActivityThread")
                    .getMethod("currentApplication")
                    .invoke(null);
        } catch (Throwable ignored) {}
        return null;
    }

    private static String getPackageName() {
        try {
            return getApplicationContext().getPackageName();
        } catch (Throwable e) {
            return "com.airec.bledemo"; // fallback hardcoded
        }
    }

    /** 诊断：打印已加载的 .so 列表 */
    private static void logLoadedLibraries() {
        try {
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.FileReader("/proc/self/maps"));
            String line;
            int count = 0;
            StringBuilder sb = new StringBuilder("Loaded .so:\n");
            while ((line = reader.readLine()) != null && count < 15) {
                if (line.contains(".so")) {
                    sb.append(line).append("\n");
                    count++;
                }
            }
            if (count >= 15) sb.append("... (truncated)\n");
            reader.close();
            Log.d(TAG, sb.toString());
        } catch (Exception e) {
            Log.d(TAG, "Cannot read /proc/self/maps: " + e.getMessage());
        }
    }

    // ─── 实例状态 ────────────────────────────────────────────────────────

    private long decoderPtr = 0;
    private int sampleRate = 16000;
    private int channels = 1;

    // 解码输出缓冲（每包最大样本数）。这款笔用 SILK-WB 20ms 帧(=320样本)，
    // 旧值 160 会让 opus_decode 报 BUFFER_TOO_SMALL → 全部解码失败（录音笔噪音 bug 根因之一）。
    // 取 5760（48kHz/120ms 上限）足够覆盖任何 opus 帧。
    private static final int FRAME_SIZE = 5760;

    /**
     * 初始化 Opus 解码器（默认 16kHz 单声道）
     */
    public void init() {
        init(16000, 1);
    }

    /**
     * 初始化 Opus 解码器
     */
    public synchronized void init(int sr, int ch) {
        destroy();
        this.sampleRate = sr;
        this.channels = ch;

        LibOpus lib = getLibOpus();
        if (lib == null) {
            Log.e(TAG, "libopus not available — raw PCM data will be used as fallback");
            return;
        }

        int[] error = new int[1];
        try {
            decoderPtr = lib.opus_decoder_create(sr, ch, error);
        } catch (Throwable e) {
            Log.e(TAG, "opus_decoder_create exception: " + e.getMessage());
            decoderPtr = 0;
            return;
        }

        if (error[0] != 0 || decoderPtr == 0) {
            Log.e(TAG, "opus_decoder_create failed: error=" + error[0] + " ptr=" + decoderPtr);
            decoderPtr = 0;
        } else {
            Log.d(TAG, "OpusBridge decoder started: " + sr + "Hz " + ch + "ch");
        }
    }

    /**
     * 销毁解码器
     */
    public synchronized void destroy() {
        if (decoderPtr != 0) {
            LibOpus lib = getLibOpus();
            if (lib != null) {
                try {
                    lib.opus_decoder_destroy(decoderPtr);
                } catch (Throwable e) {
                    Log.e(TAG, "opus_decoder_destroy error: " + e.getMessage());
                }
            }
            decoderPtr = 0;
        }
    }

    /**
     * 解码单个 Opus 帧（已去除 ATW/KA 帧头的裸 Opus 数据）。
     *
     * @param opusFrame 裸 Opus 帧数据（不含帧头）
     * @return PCM 字节数组（16bit LE），失败返回 null
     */
    public byte[] decode(byte[] opusFrame) {
        if (decoderPtr == 0 || opusFrame == null || opusFrame.length == 0) {
            return null;
        }

        LibOpus lib = getLibOpus();
        if (lib == null) return null;

        short[] pcm = new short[FRAME_SIZE];
        int samples;
        try {
            samples = lib.opus_decode(decoderPtr, opusFrame, opusFrame.length,
                    pcm, FRAME_SIZE, 0);
        } catch (Throwable e) {
            Log.e(TAG, "opus_decode exception: " + e.getMessage());
            return null;
        }
        if (samples <= 0) {
            return null;
        }

        // short[] → byte[] (little-endian)
        byte[] pcmBytes = new byte[samples * 2];
        ByteBuffer buf = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < samples; i++) {
            buf.putShort(pcm[i]);
        }
        return pcmBytes;
    }

    // ─── 文件批量解码（静态方法，供 decodeToWav 使用） ─────────────────────

    /**
     * 解码 ATW/KA 文件 → WAV
     * 参照 airecdev OpusHelper.decodeJieli() 的帧提取逻辑
     *
     * @param srcPath ATW/KA 文件路径
     * @param wavPath 输出 WAV 文件路径
     * @return WAV 文件路径，失败返回 null
     */
    public static String decodeToWav(String srcPath, String wavPath) {
        // 按内容首字节判格式（不能按扩展名：笔下载的文件名不一定带 .atw/.ka）
        String format = detectFormatByContent(srcPath);
        if (format == null) {
            Log.e(TAG, "decodeToWav: 不是已知私有格式(首字节非 5B50/4B41): " + srcPath);
            return null;
        }
        Log.d(TAG, "decodeToWav: " + srcPath + " → " + wavPath + " (" + format + ")");

        // 第一步：提取 Opus 帧
        List<byte[]> frames = extractFrames(srcPath, format);
        if (frames == null || frames.isEmpty()) {
            Log.e(TAG, "extractFrames failed or empty");
            return null;
        }
        Log.d(TAG, "Extracted " + frames.size() + " frames");

        // 第二步：逐帧解码
        OpusBridge bridge = new OpusBridge();
        bridge.init(16000, 1);

        if (bridge.decoderPtr == 0) {
            Log.e(TAG, "Opus decoder init failed");
            bridge.destroy();
            return null;
        }

        LibOpus lib = getLibOpus();
        if (lib == null) {
            Log.e(TAG, "libopus unavailable for decodeToWav");
            bridge.destroy();
            return null;
        }

        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(wavPath);
            // 先写占位 WAV 头（44 字节）
            fos.write(new byte[44]);

            short[] pcm = new short[FRAME_SIZE];
            int totalSamples = 0;

            for (byte[] frame : frames) {
                int samples;
                try {
                    samples = lib.opus_decode(bridge.decoderPtr, frame, frame.length,
                            pcm, FRAME_SIZE, 0);
                } catch (Throwable e) {
                    Log.e(TAG, "decode frame error: " + e.getMessage());
                    continue;
                }
                if (samples > 0) {
                    byte[] pcmBytes = new byte[samples * 2];
                    ByteBuffer buf = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN);
                    for (int i = 0; i < samples; i++) {
                        buf.putShort(pcm[i]);
                    }
                    fos.write(pcmBytes);
                    totalSamples += samples;
                }
            }

            int dataSize = totalSamples * 2; // bytes
            Log.d(TAG, "Decoded " + totalSamples + " samples, " + dataSize + " bytes PCM");

            // 第三步：重写 WAV 头
            fos.close();
            writeWavHeader(wavPath, dataSize, 16000, 1);

            return wavPath;
        } catch (IOException e) {
            Log.e(TAG, "decodeToWav IO error: " + e.getMessage());
            return null;
        } finally {
            try { if (fos != null) fos.close(); } catch (IOException ignored) {}
            bridge.destroy();
        }
    }

    /** 按内容首 2 字节判私有格式：5B 50→ATW，4B 41→KA，否则 null。 */
    private static String detectFormatByContent(String path) {
        try (FileInputStream fis = new FileInputStream(path)) {
            byte[] h = new byte[2];
            if (fis.read(h) < 2) return null;
            int b0 = h[0] & 0xFF, b1 = h[1] & 0xFF;
            if (b0 == 0x5B && b1 == 0x50) return "ATW";
            if (b0 == 0x4B && b1 == 0x41) return "KA";
        } catch (IOException ignored) {}
        return null;
    }

    // ─── 帧提取（与 airecdev reDecodeOpusToWav 完全一致） ────────────────

    private static List<byte[]> extractFrames(String srcPath, String format) {
        File f = new File(srcPath);
        byte[] raw;
        try (FileInputStream fis = new FileInputStream(f)) {
            raw = new byte[(int) f.length()];
            int off = 0;
            int n;
            while (off < raw.length && (n = fis.read(raw, off, raw.length - off)) >= 0) {
                off += n;
            }
        } catch (IOException e) {
            Log.e(TAG, "read file failed: " + e.getMessage());
            return null;
        }

        int sep0 = format.equals("ATW") ? 0x5B : 0x4B;
        int sep1 = format.equals("ATW") ? 0x50 : 0x41;

        // 找所有分隔符位置
        List<Integer> sepPos = new ArrayList<>();
        for (int i = 0; i < raw.length - 1; i++) {
            if ((raw[i] & 0xFF) == sep0 && (raw[i + 1] & 0xFF) == sep1) {
                sepPos.add(i);
            }
        }
        if (sepPos.size() < 2) {
            Log.e(TAG, "Not enough frame separators found");
            return null;
        }

        // 统计步长分布，找最常见的步长
        Map<Integer, Integer> gaps = new HashMap<>();
        for (int i = 1; i < sepPos.size(); i++) {
            int gap = sepPos.get(i) - sepPos.get(i - 1);
            gaps.merge(gap, 1, Integer::sum);
        }
        int stride = 0, maxCount = 0;
        for (Map.Entry<Integer, Integer> e : gaps.entrySet()) {
            if (e.getValue() > maxCount) {
                maxCount = e.getValue();
                stride = e.getKey();
            }
        }

        int frameSize = stride - 2; // 去掉 2 字节帧头
        if (frameSize < 10 || stride > 500) {
            Log.e(TAG, "Invalid stride=" + stride + " frameSize=" + frameSize);
            return null;
        }

        Log.d(TAG, "stride=" + stride + " frameSize=" + frameSize + " frames=" + sepPos.size());

        // 按固定步长提取帧
        List<byte[]> frames = new ArrayList<>();
        int pos = sepPos.get(0);
        while (pos + stride <= raw.length) {
            byte[] frame = new byte[frameSize];
            System.arraycopy(raw, pos + 2, frame, 0, frameSize);
            frames.add(frame);
            pos += stride;
        }
        return frames;
    }

    // ─── WAV 头写入 ─────────────────────────────────────────────────────

    private static void writeWavHeader(String path, int dataSize, int sr, int ch) {
        try (RandomAccessFile raf = new RandomAccessFile(path, "rw")) {
            ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
            h.put("RIFF".getBytes());
            h.putInt(dataSize + 36);
            h.put("WAVE".getBytes());
            h.put("fmt ".getBytes());
            h.putInt(16);                          // Subchunk1Size
            h.putShort((short) 1);                  // AudioFormat = PCM
            h.putShort((short) ch);                 // NumChannels
            h.putInt(sr);                           // SampleRate
            h.putInt(sr * ch * 2);                  // ByteRate
            h.putShort((short) (ch * 2));           // BlockAlign
            h.putShort((short) 16);                 // BitsPerSample
            h.put("data".getBytes());
            h.putInt(dataSize);                     // Subchunk2Size
            raf.seek(0);
            raf.write(h.array());
        } catch (IOException e) {
            Log.e(TAG, "writeWavHeader failed: " + e.getMessage());
        }
    }
}
