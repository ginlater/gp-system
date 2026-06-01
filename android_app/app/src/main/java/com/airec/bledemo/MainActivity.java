package com.airec.bledemo;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.airec.blesdk.AIRECBleCallback;
import com.airec.blesdk.AIRECBleDevice;
import com.airec.blesdk.AIRECBleFile;
import com.airec.blesdk.AIRECBleManager;
import com.airec.bledemo.adapter.FileAdapter;
import com.airec.bledemo.databinding.ActivityMainBinding;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private FileAdapter fileAdapter;
    private final List<AIRECBleFile> rawFileList = new ArrayList<>();   // 原始全量
    private final List<AIRECBleFile> allFileList = new ArrayList<>();  // 过滤后全量
    private int currentFilterTab = 0; // 0=全部 1=设备 2=本地
    private final List<AIRECBleFile> fileList = new ArrayList<>();     // 当前页
    private static final int PAGE_SIZE = 10;
    private int currentPage = 0;
    private ActivityResultLauncher<Intent> scanLauncher;

    private static final Set<String> AUDIO_EXTS = new HashSet<>(
            Arrays.asList("wav", "mp3", "aac", "m4a", "ogg", "flac", "pcm", "amr"));

    private boolean isRecording = false;
    private boolean isPaused    = false;
    private boolean isResumingFromPause = false; // 从暂停恢复时不重置计时
    private boolean appInitiatedPauseResume = false; // App 主动发的暂停/恢复
    private boolean initialInfoFetched = false; // 防止重复查询循环
    private boolean decibelEnabled = false; // 分贝检测开关
    private boolean autoDownloadEnabled = false; // 自动下载开关
    private final List<AIRECBleFile> autoDownloadQueue = new ArrayList<>(); // 自动下载队列

    // ─── 实时转写相关 ──────────────────────────────────────────────────────
    private boolean transcribeEnabled = false; // 实时转写开关
    private String selectedLangId = "190";    // 语种ID，默认中文(190)
    // 阿里云 appKey（每个语种对应不同的 appKey，从 airecdev LanguageCfgBak 获取）
    // TODO: 替换为你的阿里云 appKey
    private static final java.util.Map<String, String> ALIYUN_APPKEYS = new java.util.HashMap<String, String>() {{
        put("190", "YOUR_APPKEY_ZH"); // 中文
        put("1",   "YOUR_APPKEY_EN"); // 英文
        put("3",   "YOUR_APPKEY_JA"); // 日文
        put("2",   "YOUR_APPKEY_KO"); // 韩文
    }};
    // airecdev 服务器地址（用于获取阿里云 token）
    private static final String AIREC_SERVER_BASE = "https://YOUR_AIREC_SERVER_HOST";
    private static final String AIREC_TOKEN_URL = AIREC_SERVER_BASE + "/api/aliyun/getAliyunToken";
    private static final String ASR_WS_URL = "wss://nls-gateway-cn-shenzhen.aliyuncs.com/ws/v1";
    private String currentToken = ""; // 缓存 token，避免每次录音都重新获取
    // WebSocket 客户端
    private java.util.concurrent.atomic.AtomicBoolean isWsConnected = new java.util.concurrent.atomic.AtomicBoolean(false);
    private java.util.concurrent.atomic.AtomicBoolean isWsConnecting = new java.util.concurrent.atomic.AtomicBoolean(false);
    private Object wsLock = new Object();
    private java.net.Socket wsSocket;
    private java.io.InputStream wsIn;
    private java.io.OutputStream wsOut;
    private java.io.DataInputStream wsDataIn;
    private ExecutorService wsExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ws-transcribe");
        t.setDaemon(true);
        return t;
    });
    private ExecutorService sendExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ws-send");
        t.setDaemon(true);
        return t;
    });
    // PCM 音频缓冲（累积到 8000 字节约 500ms 再发送）
    private byte[] pcmBuffer = new byte[8000];
    private AtomicInteger pcmBufferLen = new AtomicInteger(0);
    private final Object pcmLock = new Object();
    // ATW 帧解码
    private OpusBridge opusBridge = null;
    // PCM 发送计数器（用于调试）
    private java.util.concurrent.atomic.AtomicInteger pcmSentCount = new java.util.concurrent.atomic.AtomicInteger(0);
    private long pcmLastLogTime = 0;

    // 实时转写结果
    private final android.os.Handler transcribeHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final StringBuilder transcribeResult = new StringBuilder();
    private static final String TAG_TRANSCRIBE = "TRANSCRIBE";

    // 语种选项
    private static final String[][] LANG_OPTIONS = {
        {"中文",      "190"},
        {"英文",      "1"},
        {"日文",      "3"},
        {"韩文",      "2"},
    };

    // 声波动画
    private View[] waveBars;
    private final java.util.Random waveRandom = new java.util.Random();
    private final Runnable waveRunnable = new Runnable() {
        @Override public void run() {
            if (!decibelEnabled || !isRecording || isPaused || waveBars == null) return;
            for (View bar : waveBars) {
                int h = 8 + waveRandom.nextInt(32);
                android.view.ViewGroup.LayoutParams lp = bar.getLayoutParams();
                lp.height = (int) (h * getResources().getDisplayMetrics().density);
                bar.setLayoutParams(lp);
            }
            mainHandler.postDelayed(this, 150);
        }
    };

    private void startWaveAnimation() {
        if (!decibelEnabled) return;
        binding.layoutWave.setVisibility(View.VISIBLE);
        mainHandler.removeCallbacks(waveRunnable);
        mainHandler.post(waveRunnable);
    }

    private void stopWaveAnimation() {
        mainHandler.removeCallbacks(waveRunnable);
        binding.layoutWave.setVisibility(View.GONE);
    }

    // ─── 实时转写 UI ───────────────────────────────────────────────────────

    private void showTranscribeUI(boolean show) {
        binding.scrollTranscribe.setVisibility(show ? View.VISIBLE : View.GONE);
        binding.tvTranscribeStatus.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    /** 更新转写状态提示文字 */
    private void setTranscribeStatus(String status) {
        runOnUiThread(() -> {
            if (status == null || status.isEmpty()) {
                binding.tvTranscribeStatus.setVisibility(View.GONE);
            } else {
                binding.tvTranscribeStatus.setVisibility(View.VISIBLE);
                binding.tvTranscribeStatus.setText(status);
            }
        });
    }

    private void updateTranscribeResult(String text) {
        runOnUiThread(() -> {
            if (text != null && !text.isEmpty()) {
                if (transcribeResult.length() > 0 && !transcribeResult.toString().endsWith(text)) {
                    // 追加新片段
                    transcribeResult.append(text);
                } else {
                    transcribeResult.append(text);
                }
                binding.tvTranscribeResult.setText(transcribeResult.toString());
                // 自动滚动到底部
                binding.scrollTranscribe.post(() -> binding.scrollTranscribe.fullScroll(View.FOCUS_DOWN));
            }
        });
    }

    private void clearTranscribeResult() {
        runOnUiThread(() -> {
            transcribeResult.setLength(0);
            binding.tvTranscribeResult.setText("");
        });
    }

    private void showLangPicker() {
        String[] labels = new String[LANG_OPTIONS.length];
        for (int i = 0; i < LANG_OPTIONS.length; i++) labels[i] = LANG_OPTIONS[i][0];
        new AlertDialog.Builder(this).setTitle("选择转写语种")
            .setItems(labels, (d, w) -> {
                selectedLangId = LANG_OPTIONS[w][1];
                binding.tvTranscribeLang.setText(LANG_OPTIONS[w][0]);
                Toast.makeText(this, "已切换为" + labels[w] + "转写", Toast.LENGTH_SHORT).show();
            }).show();
    }

    // ─── 实时转写 WebSocket ───────────────────────────────────────────────

    /**
     * 启动实时转写 WebSocket 连接
     * token 从 airecdev 服务器动态获取，appKey 从语种映射获取
     */
    private void startTranscribeWs() {
        if (isWsConnecting.get() || isWsConnected.get()) return;
        isWsConnecting.set(true);
        android.util.Log.i(TAG_TRANSCRIBE, "开始启动 ASR WebSocket...");

        wsExecutor.execute(() -> {
            try {
                // 1. 获取 token（从 airecdev 服务器，优先使用缓存 token）
                String appKey = ALIYUN_APPKEYS.get(selectedLangId);
                if (appKey == null || appKey.isEmpty()) appKey = "YOUR_APPKEY_ZH"; // 默认中文
                android.util.Log.i(TAG_TRANSCRIBE, "使用 appKey: " + appKey + ", 语种: " + selectedLangId);
                setTranscribeStatus("获取认证中...");

                String token = currentToken;
                if (token == null || token.isEmpty()) {
                    android.util.Log.i(TAG_TRANSCRIBE, "正在从服务器获取 token...");
                    token = getAliyunTokenSync(appKey);
                    if (token == null || token.isEmpty()) {
                        android.util.Log.e(TAG_TRANSCRIBE, "获取 token 失败，无法启动转写");
                        setTranscribeStatus("❌ 获取 token 失败");
                        isWsConnecting.set(false);
                        closeWsQuietly();
                        return;
                    }
                    currentToken = token; // 缓存
                }
                android.util.Log.d(TAG_TRANSCRIBE, "Token: " + token.substring(0, Math.min(16, token.length())) + "...");

                // 2. 构建带 token 的 WebSocket URL
                String wsUrl = ASR_WS_URL + "?token=" + token;
                URI uri = new URI(wsUrl);

                android.util.Log.i(TAG_TRANSCRIBE, "正在连接: " + uri.getHost() + ":" + (uri.getPort() > 0 ? uri.getPort() : 443));
                setTranscribeStatus("连接服务器...");

                // 3. 创建 SSL Socket 并连接
                android.util.Log.i(TAG_TRANSCRIBE, "创建 SSL Socket...");
                javax.net.ssl.SSLSocketFactory factory = (javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault();
                int port = uri.getPort() > 0 ? uri.getPort() : 443;
                android.util.Log.i(TAG_TRANSCRIBE, "连接到 " + uri.getHost() + ":" + port + "...");
                wsSocket = factory.createSocket(uri.getHost(), port);
                wsSocket.setSoTimeout(15000);
                android.util.Log.i(TAG_TRANSCRIBE, "SSL Socket 创建成功，开始获取流...");
                wsIn = wsSocket.getInputStream();
                wsOut = wsSocket.getOutputStream();
                android.util.Log.i(TAG_TRANSCRIBE, "输入输出流获取成功");

                // 4. 发送 WebSocket 握手
                android.util.Log.i(TAG_TRANSCRIBE, "发送 WebSocket 握手...");
                sendWsHandshake(uri);

                // 5. 读取握手响应
                if (!readWsHandshakeResponse()) {
                    android.util.Log.e(TAG_TRANSCRIBE, "WebSocket handshake failed");
                    setTranscribeStatus("❌ 握手失败");
                    isWsConnecting.set(false);
                    closeWsQuietly();
                    return;
                }

                isWsConnecting.set(false);
                isWsConnected.set(true);
                android.util.Log.i(TAG_TRANSCRIBE, "✅ WebSocket 连接成功！");
                setTranscribeStatus("连接成功，等待开始...");

                // 6. 发送开始识别请求（携带 appKey）
                sendAsrStartRequest(appKey);

                // 7. 启动接收线程
                wsExecutor.execute(this::readWsLoop);

            } catch (Exception e) {
                android.util.Log.e(TAG_TRANSCRIBE, "WS connect error: " + e.getClass().getName() + ": " + e.getMessage());
                java.io.StringWriter sw = new java.io.StringWriter();
                e.printStackTrace(new java.io.PrintWriter(sw));
                android.util.Log.e(TAG_TRANSCRIBE, "StackTrace: " + sw.toString());
                isWsConnecting.set(false);
                isWsConnected.set(false);
                setTranscribeStatus("❌ 连接失败");
                closeWsQuietly();
            }
        });
    }

    /**
     * 从 airecdev 服务器同步获取阿里云 Token
     * 参照 airecdev: AIREC_RecordHttpHelper.getAliyunToken()
     */
    private String getAliyunTokenSync(String appKey) {
        // 从 airecdev 服务器获取有效的阿里云 ASR Token
        // TODO: 实现 HTTP 请求获取 token，或在此填入你的 token
        String token = "YOUR_ALIYUN_ASR_TOKEN";
        android.util.Log.i(TAG_TRANSCRIBE, "使用 token: " + token.substring(0, 16) + "...");
        return token;
    }

    /**
     * WebSocket 握手
     */
    private void sendWsHandshake(URI uri) throws Exception {
        String host = uri.getHost();
        String path = uri.getPath() + (uri.getQuery() != null ? "?" + uri.getQuery() : "");
        String key = java.util.Base64.getEncoder().encodeToString(
            new java.security.SecureRandom().generateSeed(16));

        String request = "GET " + path + " HTTP/1.1\r\n" +
            "Host: " + host + "\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Key: " + key + "\r\n" +
            "Sec-WebSocket-Version: 13\r\n" +
            "\r\n";

        wsOut.write(request.getBytes("UTF-8"));
        wsOut.flush();
    }

    /**
     * 读取并验证 WebSocket 握手响应
     */
    private boolean readWsHandshakeResponse() throws Exception {
        byte[] buf = new byte[1024];
        int n = wsIn.read(buf);
        if (n <= 0) return false;
        String response = new String(buf, 0, n, "UTF-8");
        android.util.Log.d(TAG_TRANSCRIBE, "WS handshake response: " + response.substring(0, Math.min(200, response.length())));
        return response.contains("101") || response.contains("200");
    }

    /**
     * 发送阿里云实时转写开始请求
     * 参照 airecdev aliyun_asr.dart: SpeechTranscriber.StartTranscription
     */
    private void sendAsrStartRequest(String appKey) {
        try {
            String taskId = java.util.UUID.randomUUID().toString().replaceAll("-", "");

            // SpeechTranscriber 协议（阿里云实时转写 WebSocket）
            String startRequest = "{" +
                "\"header\":{" +
                    "\"message_id\":\"" + taskId + "\"," +
                    "\"task_id\":\"" + taskId + "\"," +
                    "\"namespace\":\"SpeechTranscriber\"," +
                    "\"name\":\"StartTranscription\"," +
                    "\"appkey\":\"" + appKey + "\"" +
                "}," +
                "\"payload\":{" +
                    "\"format\":\"wav\"," +
                    "\"sample_rate\":16000," +
                    "\"enable_intermediate_result\":true," +
                    "\"enable_punctuation_prediction\":true," +
                    "\"enable_inverse_text_normalization\":true," +
                    "\"enable_semantic_sentence_detection\":true" +
                "}" +
            "}";

            sendWsTextFrame(startRequest);
            android.util.Log.d(TAG_TRANSCRIBE, "ASR start request sent: " + startRequest);

        } catch (Exception e) {
            android.util.Log.e(TAG_TRANSCRIBE, "sendAsrStartRequest error: " + e.getMessage());
        }
    }

    /**
     * 发送文本帧 (WebSocket Text Frame, opcode=0x01)
     */
    private void sendWsTextFrame(String text) throws Exception {
        if (!isWsConnected.get()) return;
        byte[] data = text.getBytes("UTF-8");
        sendWsFrame((byte) 0x81, data);
    }

    /**
     * 发送二进制帧 (WebSocket Binary Frame, opcode=0x02)
     */
    private void sendWsBinaryFrame(byte[] data) throws Exception {
        if (!isWsConnected.get()) return;
        sendWsFrame((byte) 0x82, data);
    }

    /**
     * 发送 WebSocket Frame
     */
    private void sendWsFrame(byte opcode, byte[] data) throws Exception {
        synchronized (wsLock) {
            if (wsOut == null) return;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            // FIN=1, opcode
            baos.write((byte) (0x80 | opcode));
            // Mask=1 (客户端必须掩码)，payload length
            if (data.length < 126) {
                baos.write((byte) (0x80 | data.length));
            } else if (data.length < 65536) {
                baos.write(0xFE);
                baos.write((byte) ((data.length >> 8) & 0xFF));
                baos.write((byte) (data.length & 0xFF));
            } else {
                baos.write(0xFF);
                // 8-byte length
                baos.write(0);
                baos.write(0);
                baos.write(0);
                baos.write(0);
                baos.write((byte) ((data.length >> 24) & 0xFF));
                baos.write((byte) ((data.length >> 16) & 0xFF));
                baos.write((byte) ((data.length >> 8) & 0xFF));
                baos.write((byte) (data.length & 0xFF));
            }
            // 掩码 key (4 bytes)
            byte[] mask = new byte[4];
            new java.security.SecureRandom().nextBytes(mask);
            baos.write(mask[0]); baos.write(mask[1]);
            baos.write(mask[2]); baos.write(mask[3]);
            // 掩码数据
            for (int i = 0; i < data.length; i++) {
                baos.write(data[i] ^ mask[i % 4]);
            }
            wsOut.write(baos.toByteArray());
            wsOut.flush();
        }
    }

    /**
     * WebSocket 接收循环
     */
    private void readWsLoop() {
        byte[] lenBuf = new byte[8];
        try {
            while (isWsConnected.get() && wsIn != null) {
                // 读帧头 (2 bytes)
                int b1 = wsIn.read();
                int b2 = wsIn.read();
                if (b1 < 0 || b2 < 0) break;

                boolean fin = (b1 & 0x80) != 0;
                int opcode = b1 & 0x0F;
                int len = b2 & 0x7F;

                if (len == 126) {
                    wsIn.read(lenBuf, 0, 2);
                    len = ((lenBuf[0] & 0xFF) << 8) | (lenBuf[1] & 0xFF);
                } else if (len == 127) {
                    wsIn.read(lenBuf, 0, 8);
                    len = (int) (((long)(lenBuf[0] & 0xFF) << 56)
                            | ((long)(lenBuf[1] & 0xFF) << 48)
                            | ((long)(lenBuf[2] & 0xFF) << 40)
                            | ((long)(lenBuf[3] & 0xFF) << 32)
                            | ((long)(lenBuf[4] & 0xFF) << 24)
                            | ((long)(lenBuf[5] & 0xFF) << 16)
                            | ((long)(lenBuf[6] & 0xFF) << 8)
                            | (lenBuf[7] & 0xFF));
                }

                byte[] payload = new byte[len];
                int read = 0;
                while (read < len) {
                    int r = wsIn.read(payload, read, len - read);
                    if (r < 0) break;
                    read += r;
                }

                if (opcode == 0x01) { // Text
                    String msg = new String(payload, "UTF-8");
                    handleAsrResponse(msg);
                } else if (opcode == 0x02) { // Binary
                    String msg = new String(payload, "UTF-8");
                    handleAsrResponse(msg);
                } else if (opcode == 0x08) { // Close
                    android.util.Log.d(TAG_TRANSCRIBE, "WS server close");
                    break;
                } else if (opcode == 0x09) { // Ping
                    // 自动 Pong
                    sendWsFrame((byte) 0x8A, new byte[0]);
                }
            }
        } catch (Exception e) {
            android.util.Log.e(TAG_TRANSCRIBE, "WS read loop error: " + e.getMessage());
        }
        isWsConnected.set(false);
    }

    /**
     * 处理阿里云实时转写响应
     * 参照 airecdev aliyun_asr.dart 事件：
     * TranscriptionStarted, SentenceEnd, TranscriptionCompleted, TaskFailed
     */
    private void handleAsrResponse(String msg) {
        try {
            // 解析 JSON
            org.json.JSONObject root = new org.json.JSONObject(msg);
            org.json.JSONObject header = root.optJSONObject("header");
            org.json.JSONObject payload = root.optJSONObject("payload");

            if (header == null) return;
            String name = header.optString("name", "");     // TranscriptionStarted, SentenceEnd, etc.
            String event = header.optString("event", "");   // 有时用 event 字段
            String displayName = !name.isEmpty() ? name : event;

            android.util.Log.d(TAG_TRANSCRIBE, "ASR response: " + displayName + " -> " + msg.substring(0, Math.min(300, msg.length())));

            if ("TranscriptionStarted".equals(displayName)) {
                // 识别开始，发送 PCM 音频数据
                android.util.Log.i(TAG_TRANSCRIBE, "✅ ASR 识别已开始，开始发送音频");
                isWsConnected.set(true);
                setTranscribeStatus("🎙️ 转写中...");
            } else if ("SentenceEnd".equals(displayName)) {
                // 一句话识别完成，提取转写文字
                if (payload != null) {
                    String text = payload.optString("result", "");
                    if (text != null && !text.isEmpty()) {
                        android.util.Log.d(TAG_TRANSCRIBE, "转写结果: " + text);
                        updateTranscribeResult(text);
                    }
                }
            } else if ("TranscriptionResultChanged".equals(displayName)) {
                // 中间结果（实时文字）
                if (payload != null) {
                    String text = payload.optString("result", "");
                    if (text != null && !text.isEmpty()) {
                        android.util.Log.d(TAG_TRANSCRIBE, "实时中间结果: " + text);
                        updateTranscribeResult(text);
                    }
                }
            } else if ("TranscriptionCompleted".equals(displayName)) {
                android.util.Log.i(TAG_TRANSCRIBE, "ASR 识别完成");
            } else if ("TaskFailed".equals(displayName) || "task-failed".equals(displayName)) {
                android.util.Log.e(TAG_TRANSCRIBE, "❌ ASR 识别失败: " + msg);
                setTranscribeStatus("❌ 识别失败，自动重连中...");
                // 自动重试
                isWsConnected.set(false);
                wsExecutor.execute(() -> {
                    try { Thread.sleep(2000); } catch (Exception ignored) {}
                    if (transcribeEnabled) startTranscribeWs();
                });
            }
        } catch (Exception e) {
            android.util.Log.e(TAG_TRANSCRIBE, "handleAsrResponse parse error: " + e.getMessage());
        }
    }

    /**
     * 发送 PCM 音频数据到 ASR
     */
    private void sendPcmToAsr(byte[] pcmData) {
        if (!isWsConnected.get()) return;
        // 阿里云一句话识别：直接发送 PCM 二进制帧
        try {
            sendWsBinaryFrame(pcmData);
            // 每秒打印一次发送计数
            int count = pcmSentCount.incrementAndGet();
            long now = System.currentTimeMillis();
            if (now - pcmLastLogTime > 5000) {
                android.util.Log.i(TAG_TRANSCRIBE, "📤 已发送 " + count + " 帧 PCM (最近 5 秒)");
                pcmLastLogTime = now;
            }
        } catch (Exception e) {
            android.util.Log.e(TAG_TRANSCRIBE, "sendPcmToAsr error: " + e.getMessage());
        }
    }

    /**
     * 关闭 WebSocket，发送 StopTranscription 请求
     */
    private void stopTranscribeWs() {
        isWsConnected.set(false);
        isWsConnecting.set(false);
        currentToken = ""; // 清除 token 缓存，下次重新获取
        try {
            // 发送停止转写请求
            String appKey = ALIYUN_APPKEYS.get(selectedLangId);
            if (appKey == null) appKey = "YOUR_APPKEY_ZH";
            String taskId = java.util.UUID.randomUUID().toString().replaceAll("-", "");
            String stopRequest = "{" +
                "\"header\":{" +
                    "\"message_id\":\"" + taskId + "\"," +
                    "\"task_id\":\"" + taskId + "\"," +
                    "\"namespace\":\"SpeechTranscriber\"," +
                    "\"name\":\"StopTranscription\"," +
                    "\"appkey\":\"" + appKey + "\"" +
                "}" +
            "}";
            try { sendWsTextFrame(stopRequest); } catch (Exception ignored) {}
            // 发送 WebSocket 关闭帧
            try { sendWsFrame((byte) 0x88, new byte[]{}); } catch (Exception ignored) {}
        } catch (Exception e) {
            android.util.Log.e(TAG_TRANSCRIBE, "stopTranscribeWs error: " + e.getMessage());
        }
        closeWsQuietly();
    }

    private void closeWsQuietly() {
        try { if (wsIn != null) wsIn.close(); } catch (Exception ignored) {}
        try { if (wsOut != null) wsOut.close(); } catch (Exception ignored) {}
        try { if (wsSocket != null) wsSocket.close(); } catch (Exception ignored) {}
        wsIn = null; wsOut = null; wsSocket = null;
    }

    /**
     * 语种ID转阿里云语言代码
     */
    private String getLangCode(String langId) {
        switch (langId) {
            case "190": return "zh-CN";
            case "1":   return "en-US";
            case "3":   return "ja-JP";
            case "2":   return "ko-KR";
            default:    return "zh-CN";
        }
    }

    // 上次连接的设备，用于自动重连
    private AIRECBleDevice lastDevice;

    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    // 录音中 battery 轮询
    private final Runnable batteryPollRunnable = new Runnable() {
        @Override public void run() {
            if (isRecording && AIRECBleManager.getInstance().isConnected()) {
                AIRECBleManager.getInstance().fetchDeviceInfo();
                mainHandler.postDelayed(this, 30_000);
            }
        }
    };

    private void startBatteryPoll() {
        mainHandler.removeCallbacks(batteryPollRunnable);
        mainHandler.postDelayed(batteryPollRunnable, 5000); // 5秒后第一次查
    }

    private void stopBatteryPoll() {
        mainHandler.removeCallbacks(batteryPollRunnable);
    }

    // 本地录音计时（设备不推 0x3B 时的备用计时）
    private long recordStartMs = 0;
    private long pausedElapsedMs = 0; // 暂停时已经过的毫秒数
    private final android.os.Handler timerHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable timerRunnable = new Runnable() {
        @Override public void run() {
            if (!isRecording || isPaused) return;
            long elapsed = pausedElapsedMs + (System.currentTimeMillis() - recordStartMs);
            long totalSec = elapsed / 1000;
            long h = totalSec / 3600, m = (totalSec % 3600) / 60, s = totalSec % 60;
            binding.tvRecordTime.setText(String.format("%02d:%02d:%02d", h, m, s));
            timerHandler.postDelayed(this, 1000);
        }
    };

    private void startLocalTimer() {
        recordStartMs = System.currentTimeMillis();
        timerHandler.removeCallbacks(timerRunnable);
        binding.tvRecordTime.setTextColor(0xFFE53935);
        timerHandler.post(timerRunnable);
    }

    private void resumeLocalTimer() {
        // 从暂停点继续，不重置 pausedElapsedMs
        recordStartMs = System.currentTimeMillis();
        timerHandler.removeCallbacks(timerRunnable);
        binding.tvRecordTime.setTextColor(0xFFE53935);
        timerHandler.post(timerRunnable);
    }

    private void pauseLocalTimer() {
        pausedElapsedMs += (System.currentTimeMillis() - recordStartMs);
        timerHandler.removeCallbacks(timerRunnable);
    }

    private void stopLocalTimer() {
        timerHandler.removeCallbacks(timerRunnable);
        pausedElapsedMs = 0;
        binding.tvRecordTime.setTextColor(ContextCompat.getColor(this, R.color.colorPrimary));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbar);
        setTitle("灵犀");

        // 注册 mainCallback 到 App，供 ScanActivity 连接成功后切换
        App.setMainCallback(mainCallback);

        // 扫描页返回 — callback 已在 ScanActivity.onConnected 切换，数据已在拉取中
        scanLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() == RESULT_OK) {
                        // 只切换 UI，数据由 onConnected/onDeviceInfoUpdated 等回调驱动
                        showConnectedUI();
                    }
                });

        // 文件列表
        fileAdapter = new FileAdapter(fileList);
        fileAdapter.setOnDownloadListener((file, pos) -> startDownload(file));
        fileAdapter.setOnPlayListener((file, localPath) -> {
            Intent intent = new Intent(this, PlayerActivity.class);
            intent.putExtra(PlayerActivity.EXTRA_FILE_PATH, localPath);
            intent.putExtra(PlayerActivity.EXTRA_FILE_NAME, file.getFileName());
            intent.putExtra(PlayerActivity.EXTRA_FILE_SIZE, file.getFileSizeStr());
            startActivity(intent);
        });

        binding.recyclerFiles.setLayoutManager(new LinearLayoutManager(this));
        binding.recyclerFiles.setAdapter(fileAdapter);
        // 分类筛选 Tab
        binding.tabFilter.addTab(binding.tabFilter.newTab().setText("全部"));
        binding.tabFilter.addTab(binding.tabFilter.newTab().setText("设备音频"));
        binding.tabFilter.addTab(binding.tabFilter.newTab().setText("本地音频"));
        binding.tabFilter.addOnTabSelectedListener(new com.google.android.material.tabs.TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(com.google.android.material.tabs.TabLayout.Tab tab) {
                currentFilterTab = tab.getPosition();
                applyFilterAndSort();
            }
            @Override public void onTabUnselected(com.google.android.material.tabs.TabLayout.Tab tab) {}
            @Override public void onTabReselected(com.google.android.material.tabs.TabLayout.Tab tab) {}
        });

        // 滚动到底部时加载下一页
        binding.recyclerFiles.addOnScrollListener(new androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            @Override public void onScrolled(androidx.recyclerview.widget.RecyclerView rv, int dx, int dy) {
                if (dy <= 0) return;
                androidx.recyclerview.widget.LinearLayoutManager lm =
                    (androidx.recyclerview.widget.LinearLayoutManager) rv.getLayoutManager();
                if (lm != null && lm.findLastVisibleItemPosition() >= fileList.size() - 1) {
                    loadNextPage();
                }
            }
        });

        // 左滑删除
        new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            @Override public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh,
                                            @NonNull RecyclerView.ViewHolder t) { return false; }
            @Override public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int dir) {
                int pos = vh.getBindingAdapterPosition();
                if (pos < 0 || pos >= fileList.size()) return;
                confirmDelete(pos);
            }
        }).attachToRecyclerView(binding.recyclerFiles);

        binding.btnConnect.setOnClickListener(v ->
                scanLauncher.launch(new Intent(this, ScanActivity.class)));

        binding.btnDisconnect.setOnClickListener(v ->
                new AlertDialog.Builder(this)
                        .setTitle("断开连接").setMessage("确认断开蓝牙连接？")
                        .setPositiveButton("断开", (d, w) -> {
                            lastDevice = null; // 手动断开不自动重连
                            AIRECBleManager.getInstance().disconnect();
                            showDisconnectedUI();
                        })
                        .setNegativeButton("取消", null).show());

        binding.btnRefreshFiles.setOnClickListener(v -> {
            if (!AIRECBleManager.getInstance().isConnected()) {
                Toast.makeText(this, "请先连接设备", Toast.LENGTH_SHORT).show();
                return;
            }
            AIRECBleManager.getInstance().fetchFileList();
        });

        binding.btnOta.setOnClickListener(v -> showOtaDialog());

        // 初始化声波动画条
        waveBars = new View[]{
            binding.waveBar1, binding.waveBar2, binding.waveBar3,
            binding.waveBar4, binding.waveBar5, binding.waveBar6,
            binding.waveBar7, binding.waveBar8, binding.waveBar9
        };

        // ─── 设备设置 UI ──────────────────────────────────────────────────────
        binding.swLed.setOnCheckedChangeListener((b, on) -> {
            if (b.isPressed()) AIRECBleManager.getInstance().setLedSwitch(on);
        });
        binding.swUsb.setOnCheckedChangeListener((b, on) -> {
            if (b.isPressed()) AIRECBleManager.getInstance().setUsbSwitch(on);
        });
        binding.swPowerOnRec.setOnCheckedChangeListener((b, on) -> {
            if (b.isPressed()) AIRECBleManager.getInstance().setPowerOnRecord(on);
        });
        binding.swDecibel.setOnCheckedChangeListener((b, on) -> {
            decibelEnabled = on;
            if (on && isRecording && !isPaused) {
                startWaveAnimation();
            } else {
                stopWaveAnimation();
            }
        });

        // 实时转写开关
        binding.swTranscribe.setOnCheckedChangeListener((b, on) -> {
            try {
                transcribeEnabled = on;
                if (!on) {
                    stopTranscribeWs();
                    showTranscribeUI(false);
                    setTranscribeStatus("");
                } else {
                    // 打开开关时，如果正在录音，立即启动 ASR
                    if (isRecording && !isPaused) {
                        clearTranscribeResult();
                        showTranscribeUI(true);
                        if (opusBridge == null) {
                            opusBridge = new OpusBridge();
                            opusBridge.init();
                        }
                        android.util.Log.i(TAG_TRANSCRIBE, "swTranscribe ON while recording → start ASR");
                        setTranscribeStatus("连接中...");
                        startTranscribeWs();
                    }
                }
            } catch (Exception e) {
                android.util.Log.e(TAG_TRANSCRIBE, "swTranscribe onCheckedChanged error: " + e.getMessage());
            }
        });

        // 点击语种标签切换语种
        binding.tvTranscribeLang.setOnClickListener(v -> showLangPicker());
        binding.btnSegment.setOnClickListener(v -> showSegmentPicker());
        binding.btnIdle.setOnClickListener(v -> showIdlePicker());
        binding.btnMicGain.setOnClickListener(v -> showMicGainPicker());
        binding.btnSafeCode.setOnClickListener(v -> showInputDialog("安全码", "请输入安全码"));
        binding.btnActivateCode.setOnClickListener(v -> showInputDialog("激活码", "请输入激活码"));
        binding.btnFormat.setOnClickListener(v -> showFormatConfirm());

        // 自动下载开关
        binding.swAutoTransfer.setOnCheckedChangeListener((b, on) -> {
            autoDownloadEnabled = on;
            if (on) triggerAutoDownload();
            else autoDownloadQueue.clear();
        });

        binding.btnStartRecord.setOnClickListener(v -> {
            try {
                android.util.Log.i(TAG_TRANSCRIBE, "=== 开始录音，点击了开始按钮 ===");

                // 开启实时音频流监听
                AIRECBleManager.getInstance().setAudioStreamListener(data -> {
                    try {
                        if (data == null || data.length == 0) return;

                        // 1. Log 分析
                        if (data.length >= 2) {
                            boolean isAtw = (data[0] & 0xFF) == 0x5B && (data[1] & 0xFF) == 0x50;
                            boolean isKa  = (data[0] & 0xFF) == 0x4B && (data[1] & 0xFF) == 0x41;
                            String format = isAtw ? "ATW" : (isKa ? "KA" : "未知");
                            android.util.Log.d("AIREC_STREAM",
                                String.format("实时: %d字节 [%s] 格式", data.length, format));
                        }

                        // 2. 实时转写：解码 ATW/Opus 帧 → PCM → 发给 ASR
                        if (transcribeEnabled) {
                            if (isWsConnected.get()) {
                                byte[] pcm = decodeAudioFrame(data);
                                if (pcm != null && pcm.length > 0) {
                                    sendPcmToAsr(pcm);
                                }
                            } else {
                                // WS 未连接时，显示提示（每 3 秒一次）
                                android.util.Log.w(TAG_TRANSCRIBE, "WS 未连接，跳过发送音频");
                            }
                        }
                    } catch (Exception e) {
                        android.util.Log.e("AIREC_STREAM", "AudioStreamListener error: " + e.getMessage());
                    }
                });
                AIRECBleManager.getInstance().startRecord();
                isRecording = true; isPaused = false;
                startLocalTimer();
                updateRecordButtons();
                startWaveAnimation();
                android.util.Log.i(TAG_TRANSCRIBE, "录音已启动，isRecording=true");

                // 启动实时转写
                if (transcribeEnabled) {
                    android.util.Log.i(TAG_TRANSCRIBE, "实时转写已开启，开始初始化...");
                    clearTranscribeResult();
                    showTranscribeUI(true);
                    try {
                        if (opusBridge == null) {
                            android.util.Log.i(TAG_TRANSCRIBE, "初始化 OpusBridge...");
                            opusBridge = new OpusBridge();
                            opusBridge.init();
                            android.util.Log.i(TAG_TRANSCRIBE, "OpusBridge 初始化成功");
                        }
                    } catch (Exception e) {
                        android.util.Log.e(TAG_TRANSCRIBE, "OpusBridge.init 失败: " + e.getClass().getName() + " - " + e.getMessage());
                        Toast.makeText(MainActivity.this, "OpusBridge 初始化失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    android.util.Log.i(TAG_TRANSCRIBE, "正在连接 ASR WebSocket...");
                    setTranscribeStatus("连接中...");
                    startTranscribeWs();
                }
            } catch (Exception e) {
                android.util.Log.e(TAG_TRANSCRIBE, "btnStartRecord error: " + e.getMessage());
                Toast.makeText(MainActivity.this, "启动录音失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        binding.btnPauseRecord.setOnClickListener(v -> {
            appInitiatedPauseResume = true; // 标记为 App 主动发起
            if (isPaused) {
                AIRECBleManager.getInstance().resumeRecord();
                isPaused = false;
                binding.btnPauseRecord.setText("暂停");
                resumeLocalTimer();
                startWaveAnimation();
                // 暂停后恢复 → 重连 ASR
                if (transcribeEnabled && !isWsConnected.get() && !isWsConnecting.get()) {
                    startTranscribeWs();
                }
            } else {
                AIRECBleManager.getInstance().pauseRecord();
                isPaused = true;
                binding.btnPauseRecord.setText("继续");
                pauseLocalTimer();
                stopWaveAnimation();
            }
            updateRecordButtons();
        });

        binding.btnSaveRecord.setOnClickListener(v -> {
            android.util.Log.i(TAG_TRANSCRIBE, "=== 保存录音，点击了保存按钮 ===");
            // 停止实时音频流监听
            AIRECBleManager.getInstance().setAudioStreamListener(null);
            AIRECBleManager.getInstance().endRecord();
            isRecording = false; isPaused = false;
            stopLocalTimer();
            stopWaveAnimation();
            binding.tvRecordTime.setText("00:00:00");
            updateRecordButtons();
            // 停止实时转写
            stopTranscribeWs();
            showTranscribeUI(false);
            setTranscribeStatus("");
            android.util.Log.i(TAG_TRANSCRIBE, "录音已停止，转写已关闭");
        });

        showDisconnectedUI();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 确保 callback 始终指向 mainCallback
        AIRECBleManager.getInstance().setCallback(mainCallback);
        App.setMainCallback(mainCallback);

        if (AIRECBleManager.getInstance().isConnected()) {
            showConnectedUI();
        }
    }

    // ─── UI 状态 ──────────────────────────────────────────────────────────────

    /** 显示已连接界面（不触发数据拉取，数据由回调驱动） */
    private void showConnectedUI() {
        binding.layoutConnect.setVisibility(View.GONE);
        binding.layoutConnected.setVisibility(View.VISIBLE);
        AIRECBleDevice dev = AIRECBleManager.getInstance().getConnectedDevice();
        if (dev != null) {
            lastDevice = dev;
            updateDeviceInfo(dev);
        }
        updateRecordButtons();
    }

    private void showDisconnectedUI() {
        binding.layoutConnect.setVisibility(View.VISIBLE);
        binding.layoutConnected.setVisibility(View.GONE);
        isRecording = false; isPaused = false;
        binding.tvRecordTime.setText("00:00:00");
        rawFileList.clear(); allFileList.clear(); fileList.clear(); currentPage = 0;
        fileAdapter.notifyDataSetChanged();
        binding.tvFileCount.setText("音频文件（共 0 个）");
        binding.tvAudioHeader.setText("🎵 音频文件 (0)");    
    }

    private void updateDeviceInfo(AIRECBleDevice dev) {
        if (dev == null) return;
        binding.tvDeviceName.setText(dev.getName());
        binding.tvBattery.setText("电量：" + dev.getBattery() + "%");
        binding.tvStorage.setText("存储：" + dev.getStorageStr());
        String fw = dev.getFirmwareVersion();
        binding.tvFirmware.setText("固件：" + (fw == null || fw.isEmpty() ? "读取中..." : fw));
        String mac = AIRECBleManager.getInstance().getMacAddress();
        binding.tvMac.setText("SN：" + (mac == null || mac.isEmpty() ? "--" : mac));
    }

    private void updateSettingsUI() {
        AIRECBleManager mgr = AIRECBleManager.getInstance();
        binding.swLed.setChecked(mgr.getLedSwitch());
        binding.swUsb.setChecked(mgr.getUsbSwitch());
        binding.swPowerOnRec.setChecked(mgr.getPowerOnRecord());
        setButtonBlueText(binding.btnSegment, segmentLabel(mgr.getSegmentDuration()));
        setButtonBlueText(binding.btnIdle, idleLabel(mgr.getIdleShutdown()));
        setButtonBlueText(binding.btnMicGain, String.valueOf(mgr.getMicGain()));
    }

    private String segmentLabel(int min) {
        if (min <= 0) return "不分段";
        if (min < 60) return min + "分钟";
        return min / 60 + "小时" + (min % 60 > 0 ? min % 60 + "分" : "");
    }

    private String idleLabel(int min) {
        if (min <= 0) return "不关机";
        if (min < 60) return min + "分钟";
        return min / 60 + "小时" + (min % 60 > 0 ? min % 60 + "分" : "");
    }

    private void setButtonBlueText(android.widget.Button btn, String text) {
        btn.setText(text);
        btn.setTextColor(0xFF1976D2);
    }

    private void showSegmentPicker() {
        int[] opts = {0, 5, 10, 15, 30, 60, 120, 180, 240, 480};
        String[] labels = new String[opts.length];
        for (int i = 0; i < opts.length; i++) labels[i] = segmentLabel(opts[i]);
        new AlertDialog.Builder(this).setTitle("分段录音时间")
            .setItems(labels, (d, w) -> {
                AIRECBleManager.getInstance().setSegmentDuration(opts[w]);
                setButtonBlueText(binding.btnSegment, labels[w]);
            }).show();
    }

    private void showIdlePicker() {
        int[] opts = {0, 3, 5, 10, 15, 30, 60, 120, 240};
        String[] labels = new String[opts.length];
        for (int i = 0; i < opts.length; i++) labels[i] = idleLabel(opts[i]);
        new AlertDialog.Builder(this).setTitle("空闲关机时间")
            .setItems(labels, (d, w) -> {
                AIRECBleManager.getInstance().setIdleShutdown(opts[w]);
                setButtonBlueText(binding.btnIdle, labels[w]);
            }).show();
    }

    private void showMicGainPicker() {
        String[] labels = {"1", "2", "3", "4", "5", "6", "7"};
        new AlertDialog.Builder(this).setTitle("麦克风增益")
            .setItems(labels, (d, w) -> {
                AIRECBleManager.getInstance().setMicGain(w + 1);
                setButtonBlueText(binding.btnMicGain, labels[w]);
            }).show();
    }

    private void showInputDialog(String title, String hint) {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setHint(hint);
        new AlertDialog.Builder(this).setTitle(title).setView(input)
            .setPositiveButton("确定", (d, w) -> {
                String val = input.getText().toString().trim();
                if (!val.isEmpty()) Toast.makeText(this, title + "已设置: " + val, Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("取消", null).show();
    }

    private void showFormatConfirm() {
        new AlertDialog.Builder(this)
            .setTitle("⚠️ 格式化存储")
            .setMessage("此操作将清除设备上所有录音文件，且不可恢复。\n\n确定要继续吗？")
            .setPositiveButton("确定格式化", (d, w) -> {
                AIRECBleManager.getInstance().formatDisk();
                Toast.makeText(this, "正在格式化...", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("取消", null).show();
    }

    private void updateRecordButtons() {
        binding.btnStartRecord.setEnabled(!isRecording);
        binding.btnPauseRecord.setEnabled(isRecording);
        binding.btnSaveRecord.setEnabled(isRecording);
        if (!isRecording) binding.btnPauseRecord.setText("暂停");
    }

    private void updateFileLists(List<AIRECBleFile> all) {
        rawFileList.clear();
        for (AIRECBleFile f : all) {
            String name = f.getFileName().toLowerCase();
            int dot = name.lastIndexOf('.');
            if (dot < 0) { rawFileList.add(f); }
            else { String ext = name.substring(dot + 1); if (AUDIO_EXTS.contains(ext)) rawFileList.add(f); }
        }
        applyFilterAndSort();
    }

    private void applyFilterAndSort() {
        // 从原始列表排序（不修改 rawFileList）
        List<AIRECBleFile> sorted = new ArrayList<>(rawFileList);
        sorted.sort((a, b) -> b.getFileName().compareTo(a.getFileName()));

        // 根据 tab 过滤（0=全部 1=设备 2=本地）
        List<AIRECBleFile> filtered = new ArrayList<>();
        for (AIRECBleFile f : sorted) {
            boolean isLocal = AIRECBleManager.getInstance().isDownloaded(f.getFileName());
            if (currentFilterTab == 0) filtered.add(f);
            else if (currentFilterTab == 1 && !isLocal) filtered.add(f);
            else if (currentFilterTab == 2 && isLocal) filtered.add(f);
        }

        // 更新 allFileList（过滤后全量，用于分页）
        allFileList.clear();
        allFileList.addAll(filtered);

        // 重置分页，显示第一页
        currentPage = 0;
        fileList.clear();
        int end = Math.min(PAGE_SIZE, allFileList.size());
        fileList.addAll(allFileList.subList(0, end));
        fileAdapter.notifyDataSetChanged();
        updateFileHeader();
    }

    private void loadNextPage() {
        int start = (currentPage + 1) * PAGE_SIZE;
        if (start >= allFileList.size()) return;
        currentPage++;
        int end = Math.min(start + PAGE_SIZE, allFileList.size());
        int insertPos = fileList.size();
        fileList.addAll(allFileList.subList(start, end));
        fileAdapter.notifyItemRangeInserted(insertPos, end - start);
        updateFileHeader();
    }

    private void updateFileHeader() {
        int total = allFileList.size();
        int showing = fileList.size();
        String more = showing < total ? "（显示 " + showing + "/" + total + "，下拉加载更多）" : "";
        binding.tvFileCount.setText("音频文件（共 " + total + " 个）" + more);
        binding.tvAudioHeader.setText("🎵 音频文件 (" + total + ")" + more);
    }

    private void confirmDelete(int pos) {
        AIRECBleFile file = fileList.get(pos);
        new AlertDialog.Builder(this)
                .setTitle("删除文件").setMessage("确认删除 " + file.getFileName() + "？")
                .setPositiveButton("删除", (d, w) -> {
                    AIRECBleManager.getInstance().deleteFile(file.getFileName());
                    fileList.remove(pos);
                    fileAdapter.notifyItemRemoved(pos);
                    binding.tvFileCount.setText("音频文件（共 " + fileList.size() + " 个）");
                    binding.tvAudioHeader.setText("🎵 音频文件 (" + fileList.size() + ")");
                })
                .setNegativeButton("取消", (d, w) -> fileAdapter.notifyItemChanged(pos))
                .show();
    }

    private void showOtaDialog() {
        AIRECBleDevice dev = AIRECBleManager.getInstance().getConnectedDevice();
        String ver = (dev != null && dev.getFirmwareVersion() != null
                && !dev.getFirmwareVersion().isEmpty()) ? dev.getFirmwareVersion() : "未知";
        new AlertDialog.Builder(this)
                .setTitle("OTA 固件升级")
                .setMessage("当前固件版本：" + ver
                        + "\n\n升级步骤：\n1. 将固件文件(.bin)放入设备存储根目录\n"
                        + "2. 重命名为 update.bin\n3. 重启设备，设备将自动完成升级")
                .setPositiveButton("确定", null).show();
    }

    // ─── 文件下载 ─────────────────────────────────────────────────────────────

    private void startDownload(AIRECBleFile file) {
        fileAdapter.updateProgress(file.getFileName(), 0);
        AIRECBleManager.getInstance().downloadFile(file);
    }

    /** 触发自动下载：正在下载时入队，闲时取队首 */
    private void triggerAutoDownload() {
        if (!autoDownloadEnabled || autoDownloadQueue.isEmpty()) return;
        AIRECBleManager mgr = AIRECBleManager.getInstance();
        if (mgr.isConnected()) {
            AIRECBleFile next = autoDownloadQueue.remove(0);
            startDownload(next);
        }
    }

    /** 自动下载队列追加（去重、跳过已下载） */
    private void enqueueAutoDownload(AIRECBleFile file) {
        if (!autoDownloadEnabled) return;
        if (AIRECBleManager.getInstance().isDownloaded(file.getFileName())) return;
        for (AIRECBleFile f : autoDownloadQueue) {
            if (f.getFileName().equals(file.getFileName())) return;
        }
        autoDownloadQueue.add(file);
        triggerAutoDownload();
    }

    // ─── 蓝牙回调 ─────────────────────────────────────────────────────────────

    private final AIRECBleCallback mainCallback = new AIRECBleCallback() {

        @Override
        public void onConnected(AIRECBleDevice device) {
            runOnUiThread(() -> {
                lastDevice = device;
                initialInfoFetched = false;
                showConnectedUI();
                // syncTime 已在 SDK 连接成功时立即发送
                // 等设备处理完时间同步后再查询（3秒），AIREC(BLE) 需要更长稳定时间
                mainHandler.postDelayed(() -> {
                    if (AIRECBleManager.getInstance().isConnected()) {
                        AIRECBleManager.getInstance().fetchAllDeviceInfo();
                        // 单独查询录音状态，同步设备当前录音状态到 UI
                        mainHandler.postDelayed(() -> {
                            if (AIRECBleManager.getInstance().isConnected()) {
                                AIRECBleManager.getInstance().fetchDeviceInfo();
                            }
                        }, 500);
                    }
                }, 3000);
                // 连接成功必须加载文件列表
                mainHandler.postDelayed(() -> {
                    if (AIRECBleManager.getInstance().isConnected()) {
                        AIRECBleManager.getInstance().fetchFileList();
                    }
                }, 3500);
            });
        }

        @Override
        public void onDisconnected(AIRECBleDevice device, String reason) {
            runOnUiThread(() -> {
                stopBatteryPoll();
                stopLocalTimer();
                initialInfoFetched = false;
                showDisconnectedUI();
                // 自动重连（手动断开时 lastDevice 已清空）
                if (lastDevice != null) {
                    Toast.makeText(MainActivity.this, "设备断开，3秒后自动重连...", Toast.LENGTH_SHORT).show();
                    binding.getRoot().postDelayed(() -> {
                        if (!AIRECBleManager.getInstance().isConnected() && lastDevice != null) {
                            AIRECBleManager.getInstance().connect(lastDevice);
                        }
                    }, 3000);
                } else {
                    Toast.makeText(MainActivity.this, "设备已断开", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @Override
        public void onDeviceInfoUpdated(AIRECBleDevice device) {
            runOnUiThread(() -> {
                if (binding == null) return;
                updateDeviceInfo(device);
            });
        }

        @Override
        public void onFileListUpdated(List<AIRECBleFile> files) {
            runOnUiThread(() -> {
                if (binding == null) return;
                // 确保 UI 已切换到已连接状态
                if (AIRECBleManager.getInstance().isConnected()
                        && binding.layoutConnected.getVisibility() != View.VISIBLE) {
                    showConnectedUI();
                }
                updateFileLists(files);
                // 自动下载：只对未下载的新文件入队（避免刷新时重复入队）
                if (autoDownloadEnabled) {
                    for (AIRECBleFile f : files) {
                        if (!AIRECBleManager.getInstance().isDownloaded(f.getFileName())
                                && !autoDownloadQueue.contains(f)) {
                            autoDownloadQueue.add(f);
                        }
                    }
                    triggerAutoDownload();
                }
            });
        }

        @Override
        public void onFileDeleted(String fileName, boolean success) {
            runOnUiThread(() -> {
                Toast.makeText(MainActivity.this,
                        success ? "删除成功" : "删除失败", Toast.LENGTH_SHORT).show();
                if (success) AIRECBleManager.getInstance().fetchFileList();
            });
        }

        @Override
        public void onRecordStateChanged(boolean recording, String fileName) {
            runOnUiThread(() -> {
                isRecording = recording;
                // 录音真正停止时才重置暂停状态，暂停时不覆盖
                if (!recording) {
                    isPaused = false;
                    binding.btnPauseRecord.setText("暂停");
                }
                if (!recording) {
                    binding.tvRecordTime.setText("00:00:00");
                    stopLocalTimer();
                    stopBatteryPoll();
                    // 录音结束后重新查询设备信息
                    mainHandler.postDelayed(() -> {
                        if (AIRECBleManager.getInstance().isConnected()) {
                            AIRECBleManager.getInstance().fetchDeviceInfo();
                            AIRECBleManager.getInstance().fetchFirmwareVersion();
                            AIRECBleManager.getInstance().fetchFileList();
                        }
                    }, 1500);
                } else {
                    if (isResumingFromPause) {
                        isResumingFromPause = false;
                        // 从暂停恢复，不重置计时，继续已有的计时
                    } else {
                        startLocalTimer();
                    }
                    startBatteryPoll();
                }
                updateRecordButtons();
                Toast.makeText(MainActivity.this,
                        recording ? "录音开始" : "录音已保存", Toast.LENGTH_SHORT).show();
            });
        }

        @Override
        public void onRecordPaused() {
            runOnUiThread(() -> {
                if (appInitiatedPauseResume) {
                    // App 主动发的，UI 已更新，只清标志
                    appInitiatedPauseResume = false;
                } else {
                    // 设备端按键触发，根据当前状态 toggle
                    if (isPaused) {
                        isPaused = false;
                        binding.btnPauseRecord.setText("暂停");
                        resumeLocalTimer();
                    } else {
                        isPaused = true;
                        binding.btnPauseRecord.setText("继续");
                        pauseLocalTimer();
                    }
                    startBatteryPoll();
                    updateRecordButtons();
                }
            });
        }

        @Override
        public void onRecordStatusQueried(boolean recording, boolean paused, String fileName) {
            runOnUiThread(() -> {
                if (binding == null) return;
                boolean wasRecording = isRecording;
                isRecording = recording;
                isPaused = paused;
                binding.btnPauseRecord.setText(paused ? "继续" : "暂停");
                if (recording && !paused) {
                    if (!wasRecording) startLocalTimer();
                    startBatteryPoll();
                } else if (paused) {
                    if (wasRecording) pauseLocalTimer();
                    startBatteryPoll();
                } else {
                    if (wasRecording) { stopLocalTimer(); binding.tvRecordTime.setText("00:00:00"); }
                    stopBatteryPoll();
                }
                updateRecordButtons();
                if (!initialInfoFetched) {
                    initialInfoFetched = true;
                    if (!recording) {
                        mainHandler.postDelayed(() -> {
                            if (AIRECBleManager.getInstance().isConnected()) {
                                AIRECBleManager.getInstance().fetchFileList();
                            }
                        }, 300);
                    }
                }
            });
        }
        @Override
        public void onRecordDurationUpdated(long durationSec) {
            runOnUiThread(() -> {
                if (isPaused) {
                    // 暂停期间收到设备时长，更新 pausedElapsedMs 以匹配设备端时长
                    // 这样恢复计时时能从正确的暂停点继续
                    pausedElapsedMs = durationSec * 1000;
                    recordStartMs = System.currentTimeMillis();
                } else {
                    // 录音进行中，以设备为准并重置本地计时起点
                    recordStartMs = System.currentTimeMillis() - durationSec * 1000;
                    pausedElapsedMs = 0;
                }
                long h = durationSec / 3600;
                long m = (durationSec % 3600) / 60;
                long s = durationSec % 60;
                binding.tvRecordTime.setText(String.format("%02d:%02d:%02d", h, m, s));
            });
        }

        @Override
        public void onFirmwareVersionReceived(String version) {
            runOnUiThread(() -> {
                if (version != null && !version.isEmpty()) {
                    binding.tvFirmware.setText("固件：" + version);
                }
            });
        }

        @Override
        public void onBluetoothStateChanged(boolean enabled) {
            runOnUiThread(() -> {
                if (!enabled) {
                    lastDevice = null;
                    Toast.makeText(MainActivity.this, "蓝牙已关闭", Toast.LENGTH_SHORT).show();
                    showDisconnectedUI();
                }
            });
        }

        @Override
        public void onInitParamUpdated() {
            runOnUiThread(() -> updateSettingsUI());
        }

        @Override
        public void onFormatResult(boolean success, String message) {
            runOnUiThread(() -> {
                Toast.makeText(MainActivity.this,
                    success ? "✅ " + message : "❌ " + message, Toast.LENGTH_SHORT).show();
                if (success) {
                    // 清空本地文件列表
                    rawFileList.clear();
                    allFileList.clear();
                    fileList.clear();
                    currentPage = 0;
                    fileAdapter.notifyDataSetChanged();
                    binding.tvFileCount.setText("音频文件（共 0 个）");
                    // 延迟刷新设备信息和文件列表
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        AIRECBleManager.getInstance().fetchDeviceInfo();
                    }, 1000);
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        AIRECBleManager.getInstance().fetchFileList();
                    }, 2000);
                }
            });
        }

        // ─── 文件下载回调 ─────────────────────────────────────────────────────

        @Override
        public void onFileDownloadProgress(AIRECBleFile file, int progress) {
            runOnUiThread(() -> fileAdapter.updateProgress(file.getFileName(), progress));
        }

        @Override
        public void onFileDownloadComplete(AIRECBleFile file, String localPath) {
            runOnUiThread(() -> {
                fileAdapter.markDone(file.getFileName());
                Toast.makeText(MainActivity.this, "下载完成：" + file.getFileName(), Toast.LENGTH_SHORT).show();
                // 自动下载队列继续
                triggerAutoDownload();
            });
        }

        @Override
        public void onFileDownloadFailed(AIRECBleFile file, String reason) {
            runOnUiThread(() -> {
                if (file != null) fileAdapter.markError(file.getFileName());
                // 保底：重置所有 downloading 状态（防止进度条卡住）
                fileAdapter.resetAllDownloading();
                Toast.makeText(MainActivity.this, "下载失败：" + reason, Toast.LENGTH_SHORT).show();
                // 自动下载队列继续（跳过失败的）
                triggerAutoDownload();
            });
        }
    };

    // ─── 音频帧解码 ─────────────────────────────────────────────────────────

    /**
     * 解码 BLE 推送的音频帧为 PCM
     * ATW 格式: [5B 50 ...] (ATW 格式设备)
     * KA 格式:  [4B 41 ...] (KA 格式设备)
     * 返回 PCM 数据，失败返回 null
     */
    private byte[] decodeAudioFrame(byte[] data) {
        if (data == null || data.length < 4) return null;

        // ATW 格式帧：跳过 2 字节帧头
        if ((data[0] & 0xFF) == 0x5B && (data[1] & 0xFF) == 0x50) {
            return extractAtwPayload(data);
        }
        // KA 格式帧：跳过 2 字节帧头
        else if ((data[0] & 0xFF) == 0x4B && (data[1] & 0xFF) == 0x41) {
            return extractKaPayload(data);
        }
        // 已是 PCM（无帧头）
        else if (data.length >= 160 && isLikelyPcm(data)) {
            return data;
        }
        return null;
    }

    private byte[] extractAtwPayload(byte[] data) {
        if (data.length <= 2) return null;
        byte[] payload = new byte[data.length - 2];
        System.arraycopy(data, 2, payload, 0, payload.length);

        // ATW 格式内层可能是 Opus 或 PCM
        if (opusBridge != null) {
            try {
                byte[] pcm = opusBridge.decode(payload);
                if (pcm != null && pcm.length > 0) return pcm;
            } catch (Exception e) {
                android.util.Log.e("AIREC_STREAM", "ATW Opus decode failed, try raw: " + e.getMessage());
            }
        }
        // 解码失败时直接返回 payload（可能是 PCM 格式）
        return payload;
    }

    private byte[] extractKaPayload(byte[] data) {
        if (data.length <= 2) return null;
        byte[] payload = new byte[data.length - 2];
        System.arraycopy(data, 2, payload, 0, payload.length);

        // KA 格式：内部可能是 Opus 或 PCM
        if (opusBridge != null) {
            try {
                byte[] pcm = opusBridge.decode(payload);
                if (pcm != null && pcm.length > 0) return pcm;
            } catch (Exception e) {
                android.util.Log.e("AIREC_STREAM", "KA Opus decode failed, try raw: " + e.getMessage());
            }
        }
        return payload;
    }

    /** 简单判断是否像 PCM 数据（16bit 16000Hz 单声道，每帧 320 字节） */
    private boolean isLikelyPcm(byte[] data) {
        // 检查数据范围是否像音频 PCM（-32768 ~ 32767）
        int count = Math.min(320, data.length);
        int nonZero = 0;
        for (int i = 0; i < count; i++) {
            if (data[i] != 0) nonZero++;
        }
        return nonZero > count * 0.1;
    }

    // ─── 十六进制数据分析工具 ───────────────────────────────────────────────────

    /**
     * 分析音频流数据并生成可读的报告
     */
    private String analyzeAudioStreamData(byte[] data) {
        if (data == null || data.length == 0) {
            return "空数据包";
        }

        StringBuilder report = new StringBuilder();
        
        // 1. 基本信息
        report.append("📊 数据包分析报告\n");
        report.append("────────────────\n");
        report.append("包大小: ").append(data.length).append(" 字节\n");
        
        // 2. 十六进制预览（前16字节）
        report.append("HEX预览: ");
        for (int i = 0; i < Math.min(16, data.length); i++) {
            report.append(String.format("%02X ", data[i] & 0xFF));
        }
        if (data.length > 16) report.append("...");
        report.append("\n");
        
        // 3. ASCII文本预览
        report.append("ASCII预览: ");
        for (int i = 0; i < Math.min(32, data.length); i++) {
            byte b = data[i];
            if (b >= 32 && b <= 126) { // 可打印字符
                report.append((char) b);
            } else {
                report.append(".");
            }
        }
        if (data.length > 32) report.append("...");
        report.append("\n");
        
        // 4. 格式检测
        report.append("格式检测: ");
        if (data.length >= 2) {
            boolean isAtw = (data[0] & 0xFF) == 0x5B && (data[1] & 0xFF) == 0x50; // [P
            boolean isKa = (data[0] & 0xFF) == 0x4B && (data[1] & 0xFF) == 0x41; // KA
            
            if (isAtw) {
                report.append("ATW格式音频帧");
                // 尝试估计帧长度
                if (data.length >= 4) {
                    int potentialFrameLen = data.length;
                    report.append(" (估计长度: ").append(potentialFrameLen).append("字节)");
                }
            } else if (isKa) {
                report.append("KA格式音频帧");
            } else {
                // 检查其他常见音频格式
                if (data.length >= 4) {
                    // 检查WAV头: RIFF
                    if (data[0] == 0x52 && data[1] == 0x49 && data[2] == 0x46 && data[3] == 0x46) {
                        report.append("WAV音频文件头");
                    }
                    // 检查MP3头: ID3或FF FB
                    else if ((data[0] == 0x49 && data[1] == 0x44 && data[2] == 0x33) ||
                             (data[0] == (byte)0xFF && (data[1] & 0xE0) == 0xE0)) {
                        report.append("MP3音频数据");
                    }
                    // 检查Opus头: OggS
                    else if (data[0] == 0x4F && data[1] == 0x67 && data[2] == 0x67 && data[3] == 0x53) {
                        report.append("OGG Opus音频");
                    }
                    else {
                        report.append("未知格式");
                    }
                } else {
                    report.append("数据过短，无法识别格式");
                }
            }
        } else {
            report.append("数据过短");
        }
        report.append("\n");
        
        // 5. 数据统计
        int printable = 0, control = 0, binary = 0;
        for (byte b : data) {
            int val = b & 0xFF;
            if (val >= 32 && val <= 126) printable++;
            else if (val < 32) control++;
            else binary++;
        }
        
        report.append("数据统计: ");
        report.append("可打印字符: ").append(printable).append(" (")
              .append(String.format("%.1f", (printable * 100.0 / data.length))).append("%)");
        report.append(" | 控制字符: ").append(control).append(" (")
              .append(String.format("%.1f", (control * 100.0 / data.length))).append("%)");
        report.append(" | 二进制数据: ").append(binary).append(" (")
              .append(String.format("%.1f", (binary * 100.0 / data.length))).append("%)\n");
        
        // 6. 可能的含义推测
        report.append("推测: ");
        if (printable > data.length * 0.8) {
            report.append("可能包含文本数据");
        } else if (binary > data.length * 0.7) {
            report.append("主要为二进制音频数据");
        } else {
            report.append("混合数据流");
        }
        
        return report.toString();
    }
}