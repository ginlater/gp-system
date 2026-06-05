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
        public final boolean transientFail;  // true=临时故障(网络/5xx)可重试；false=成功或永久失败(4xx/拒绝)
        Result(boolean ok, long recordingId, String error) { this(ok, recordingId, error, false); }
        Result(boolean ok, long recordingId, String error, boolean transientFail) {
            this.ok = ok; this.recordingId = recordingId; this.error = error; this.transientFail = transientFail;
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
        return upload(file, durationSec, cookie, uploadUrl, fileName, mime, null);
    }

    /** 带录音笔 SN 的上传：SN 作为表单字段 sn 一并提交，供后端做 SN→员工 绑定（sn 可空）。 */
    public static Result upload(File file, int durationSec, String cookie, String uploadUrl,
                                String fileName, String mime, String sn) {
        return upload(file, durationSec, cookie, uploadUrl, fileName, mime, sn, -1L);
    }

    /** 带 SN + 占位记录 id 的上传：placeholderId>0 时后端回填该占位行(不新建)，否则照旧新建。 */
    public static Result upload(File file, int durationSec, String cookie, String uploadUrl,
                                String fileName, String mime, String sn, long placeholderId) {
        return upload(file, durationSec, cookie, uploadUrl, fileName, mime, sn, placeholderId, null);
    }

    /** 直传录音笔实时流拼好的 opus(.ops)：opus 端到端、后端解码；recordedAtWallMs>0 时保留真实录音时间。 */
    public static Result uploadOps(File file, int durationSec, String cookie, String uploadUrl,
                                   String fileName, String sn, long placeholderId, long recordedAtWallMs) {
        String ra = null;
        if (recordedAtWallMs > 0) {
            ra = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(new java.util.Date(recordedAtWallMs));
        }
        return upload(file, durationSec, cookie, uploadUrl, fileName, "audio/opus", sn, placeholderId, ra);
    }

    /** 完整签名：多一个 recordedAt(yyyy-MM-dd HH:mm:ss，可空)，作表单字段 recorded_at 提交，后端保留真实录音时间。 */
    public static Result upload(File file, int durationSec, String cookie, String uploadUrl,
                                String fileName, String mime, String sn, long placeholderId, String recordedAt) {
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

                // sn 字段（录音笔 SN，供后端绑定员工；可空则不带）
                if (sn != null && !sn.isEmpty()) {
                    out.writeBytes("--" + boundary + CRLF);
                    out.writeBytes("Content-Disposition: form-data; name=\"sn\"" + CRLF + CRLF);
                    out.write(sn.getBytes(StandardCharsets.UTF_8));
                    out.writeBytes(CRLF);
                }
                // placeholder_id 字段（>0 时后端回填该占位片段，不新建行）
                if (placeholderId > 0) {
                    out.writeBytes("--" + boundary + CRLF);
                    out.writeBytes("Content-Disposition: form-data; name=\"placeholder_id\"" + CRLF + CRLF);
                    out.write(String.valueOf(placeholderId).getBytes(StandardCharsets.UTF_8));
                    out.writeBytes(CRLF);
                }
                // recorded_at 字段（真实录音开始时间，连录/补传时保留原时间；可空则不带）
                if (recordedAt != null && !recordedAt.isEmpty()) {
                    out.writeBytes("--" + boundary + CRLF);
                    out.writeBytes("Content-Disposition: form-data; name=\"recorded_at\"" + CRLF + CRLF);
                    out.write(recordedAt.getBytes(StandardCharsets.UTF_8));
                    out.writeBytes(CRLF);
                }

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
                return new Result(false, -1, "登录已失效，请在 App 里重新登录");  // 永久(重试也没用，需重登)
            }
            // 5xx=服务器临时故障→可重试；4xx=请求被拒(永久)
            return new Result(false, -1, "服务器返回 " + code, code >= 500);
        } catch (Exception e) {
            Log.e(TAG, "upload failed", e);
            return new Result(false, -1, e.getMessage() == null ? "网络异常" : e.getMessage(), true);  // 网络/IO异常=临时
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

    /**
     * 建"占位片段"：POST 到 placeholderUrl，表单 recorded_at(录音真实开始时间)。
     * 成功返回后端记录 id；失败/接口不存在返回 -1（调用方据此降级，跳过占位、照旧上传）。
     */
    public static long createPlaceholder(String cookie, String placeholderUrl, long recordedAtWallMs) {
        if (placeholderUrl == null || placeholderUrl.isEmpty()) return -1;
        HttpURLConnection conn = null;
        String boundary = "----gongpaiPH" + System.currentTimeMillis();
        try {
            conn = (HttpURLConnection) new URL(placeholderUrl).openConnection();
            conn.setUseCaches(false);
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            if (cookie != null && !cookie.isEmpty()) conn.setRequestProperty("Cookie", cookie);
            String recordedAt = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(new java.util.Date(recordedAtWallMs > 0 ? recordedAtWallMs : System.currentTimeMillis()));
            try (DataOutputStream out = new DataOutputStream(conn.getOutputStream())) {
                out.writeBytes("--" + boundary + CRLF);
                out.writeBytes("Content-Disposition: form-data; name=\"recorded_at\"" + CRLF + CRLF);
                out.write(recordedAt.getBytes(StandardCharsets.UTF_8));
                out.writeBytes(CRLF);
                out.writeBytes("--" + boundary + "--" + CRLF);
                out.flush();
            }
            int code = conn.getResponseCode();
            String body = readBody(code < 400 ? conn.getInputStream() : conn.getErrorStream());
            if (code >= 200 && code < 300) {
                try { return new JSONObject(body).optLong("id", -1); }
                catch (Exception e) { return -1; }
            }
            Log.w(TAG, "createPlaceholder http " + code + " " + body);
            return -1;
        } catch (Exception e) {
            Log.w(TAG, "createPlaceholder failed: " + e.getMessage());
            return -1;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 取消/删除一条占位片段（上传最终失败时清掉，别让"处理中"占位永远残留）。
     * POST cancelUrl，表单 placeholder_id。失败/接口不存在都安静忽略。
     */
    public static void cancelPlaceholder(String cookie, String cancelUrl, long placeholderId) {
        if (cancelUrl == null || cancelUrl.isEmpty() || placeholderId <= 0) return;
        HttpURLConnection conn = null;
        String boundary = "----gongpaiPC" + System.currentTimeMillis();
        try {
            conn = (HttpURLConnection) new URL(cancelUrl).openConnection();
            conn.setUseCaches(false);
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            if (cookie != null && !cookie.isEmpty()) conn.setRequestProperty("Cookie", cookie);
            try (DataOutputStream out = new DataOutputStream(conn.getOutputStream())) {
                out.writeBytes("--" + boundary + CRLF);
                out.writeBytes("Content-Disposition: form-data; name=\"placeholder_id\"" + CRLF + CRLF);
                out.write(String.valueOf(placeholderId).getBytes(StandardCharsets.UTF_8));
                out.writeBytes(CRLF);
                out.writeBytes("--" + boundary + "--" + CRLF);
                out.flush();
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) Log.w(TAG, "cancelPlaceholder http " + code);
        } catch (Exception e) {
            Log.w(TAG, "cancelPlaceholder failed: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
