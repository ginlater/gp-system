package com.airec.bledemo.net;

import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 把本地音频文件以 multipart/form-data 上传到接诊后端 /api/consultant/upload。
 * 复刻网页端 fetch 的请求：字段 file + duration_sec，并带上 WebView 的登录 Cookie。
 * 后端按 @login_required 用 session cookie 鉴权，返回 {"id": <rid>} 或 {"error": "..."}。
 */
public final class Uploader {

    private static final String TAG = "Uploader";
    private static final String CRLF = "\r\n";

    public static final class Result {
        public final boolean ok;
        public final long recordingId;   // 成功时为后端返回的录音 id，否则 -1
        public final String error;
        Result(boolean ok, long recordingId, String error) {
            this.ok = ok; this.recordingId = recordingId; this.error = error;
        }
    }

    private Uploader() {}

    /** 手机麦克风路径：m4a。沿用旧签名，委托给通用方法。 */
    public static Result upload(File file, int durationSec, String cookie, String uploadUrl) {
        String name = "rec-" + System.currentTimeMillis() + ".m4a";
        return upload(file, durationSec, cookie, uploadUrl, name, "audio/mp4");
    }

    /**
     * 通用上传：可指定上传文件名与 MIME（录音笔下载转出的 wav 走这里）。
     * 后端按扩展名识别格式，所以 fileName 的后缀要与真实内容一致（如 .wav / .m4a / .mp3）。
     */
    public static Result upload(File file, int durationSec, String cookie, String uploadUrl,
                                String fileName, String mime) {
        if (file == null || !file.exists() || file.length() == 0) {
            return new Result(false, -1, "录音文件为空");
        }
        if (uploadUrl == null || uploadUrl.isEmpty()) {
            return new Result(false, -1, "上传地址未配置");
        }

        String boundary = "----gongpai" + System.currentTimeMillis();
        HttpURLConnection conn = null;
        try {
            URL url = new URL(uploadUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setUseCaches(false);
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(120000);
            conn.setRequestProperty("Connection", "keep-alive");
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            if (cookie != null && !cookie.isEmpty()) {
                conn.setRequestProperty("Cookie", cookie);
            }
            // 流式传输，避免大文件 OOM
            conn.setChunkedStreamingMode(0);

            String safeName = (fileName == null || fileName.isEmpty())
                    ? ("rec-" + System.currentTimeMillis() + ".m4a") : fileName;
            String safeMime = (mime == null || mime.isEmpty()) ? "application/octet-stream" : mime;
            try (DataOutputStream out = new DataOutputStream(conn.getOutputStream())) {
                // duration_sec 字段
                out.writeBytes("--" + boundary + CRLF);
                out.writeBytes("Content-Disposition: form-data; name=\"duration_sec\"" + CRLF + CRLF);
                out.write(String.valueOf(durationSec).getBytes(StandardCharsets.UTF_8));
                out.writeBytes(CRLF);

                // file 字段
                out.writeBytes("--" + boundary + CRLF);
                out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"" + safeName + "\"" + CRLF);
                out.writeBytes("Content-Type: " + safeMime + CRLF + CRLF);
                try (FileInputStream fis = new FileInputStream(file)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = fis.read(buf)) != -1) out.write(buf, 0, n);
                }
                out.writeBytes(CRLF);
                out.writeBytes("--" + boundary + "--" + CRLF);
                out.flush();
            }

            int code = conn.getResponseCode();
            String body = readBody(code < 400 ? conn.getInputStream() : conn.getErrorStream());

            if (code >= 200 && code < 300) {
                try {
                    JSONObject obj = new JSONObject(body);
                    if (obj.has("error") && !obj.isNull("error")) {
                        return new Result(false, -1, obj.optString("error"));
                    }
                    long id = obj.optLong("id", -1);
                    return new Result(true, id, null);
                } catch (Exception parseErr) {
                    // 2xx 但响应不是预期 JSON，仍按成功处理（避免重复录音），但无 id
                    Log.w(TAG, "响应解析失败但 HTTP 成功: " + body);
                    return new Result(true, -1, null);
                }
            }
            if (code == 401 || code == 403) {
                return new Result(false, -1, "登录已失效，请在 App 里重新登录");
            }
            return new Result(false, -1, "服务器返回 " + code);
        } catch (Exception e) {
            Log.e(TAG, "upload failed", e);
            return new Result(false, -1, e.getMessage() == null ? "网络异常" : e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readBody(InputStream in) {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        } catch (Exception ignored) {}
        return sb.toString();
    }
}
