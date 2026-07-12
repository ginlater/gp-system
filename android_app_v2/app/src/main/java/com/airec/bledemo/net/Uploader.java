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
        // ★2026-07-13 复查修正:下面俩=服务端"收下了请求但没存这份音频"。上传任务算完成,
        //   但绝不能把机身文件记入"已完整上传"可删名单——那名单是删机身原件的唯一依据。
        public final boolean discarded;      // 命中墓碑被丢弃(顾问删过该段),未入库
        public final boolean dedupFuzzy;     // 去重命中但非机身文件名精确匹配(按录音时刻认的),可能认错文件
        Result(boolean ok, long recordingId, String error) { this(ok, recordingId, error, false); }
        Result(boolean ok, long recordingId, String error, boolean transientFail) {
            this(ok, recordingId, error, transientFail, false, false);
        }
        Result(boolean ok, long recordingId, String error, boolean transientFail,
               boolean discarded, boolean dedupFuzzy) {
            this.ok = ok; this.recordingId = recordingId; this.error = error; this.transientFail = transientFail;
            this.discarded = discarded; this.dedupFuzzy = dedupFuzzy;
        }
    }

    private Uploader() {}

    // ★双Cookie头401病根防御(2026-07-09)：部分机型进程运行数小时后会被装上全局 CookieHandler
    //   (注入者待查,ROM/运行时组件)，它给 HttpURLConnection 自动追加第二个 Cookie 头——旧 session 快照。
    //   服务端(WSGI)把两个头折叠成 "session=新,session=旧"，werkzeug 验签失败 → 上传永远 401、
    //   重登也救不回(新Cookie在第一个头里，坏的是折叠后的整串)，只有重开App(Handler消失)才好。
    //   每次发请求前清掉它；首次发现暂存类名，由 SoniPenController 落 penlog(诊断上传可见,vivo等logcat不可用)。
    private static final java.util.concurrent.atomic.AtomicReference<String> CK_HANDLER_SEEN =
            new java.util.concurrent.atomic.AtomicReference<>();

    /** 取一次"发现全局CookieHandler"通告（取后清空；无则 null）。 */
    public static String consumeCookieHandlerNotice() {
        return CK_HANDLER_SEEN.getAndSet(null);
    }

    private static void disarmGlobalCookieHandler() {
        try {
            java.net.CookieHandler h = java.net.CookieHandler.getDefault();
            if (h != null) {
                java.net.CookieHandler.setDefault(null);
                CK_HANDLER_SEEN.set(h.getClass().getName());
                Log.w(TAG, "发现并清除全局CookieHandler(双Cookie头401病根): " + h.getClass().getName());
            }
        } catch (Throwable ignore) {}
    }

    /** 手机麦克风路径：m4a。沿用旧签名，委托给通用方法。 */
    public static Result upload(File file, int durationSec, String cookie, String uploadUrl) {
        String name = "rec-" + System.currentTimeMillis() + ".aac";   // A1:ADTS 流式容器
        return upload(file, durationSec, cookie, uploadUrl, name, "audio/aac");
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
        return upload(file, durationSec, cookie, uploadUrl, fileName, mime, sn, placeholderId, null, null);
    }

    /** 直传录音笔实时流拼好的 opus(.ops)：opus 端到端、后端解码；recordedAtWallMs>0 时保留真实录音时间。 */
    public static Result uploadOps(File file, int durationSec, String cookie, String uploadUrl,
                                   String fileName, String sn, long placeholderId, long recordedAtWallMs) {
        String ra = null;
        if (recordedAtWallMs > 0) {
            ra = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(new java.util.Date(recordedAtWallMs));
        }
        return upload(file, durationSec, cookie, uploadUrl, fileName, "audio/opus", sn, placeholderId, ra, null, false);
    }

    /** 完整签名：recordedAt(可空) + penFile(录音笔机身文件名，后端去重用，可空)。 */
    public static Result upload(File file, int durationSec, String cookie, String uploadUrl,
                                String fileName, String mime, String sn, long placeholderId, String recordedAt,
                                String penFile) {
        return upload(file, durationSec, cookie, uploadUrl, fileName, mime, sn, placeholderId, recordedAt, penFile, false);
    }

    /** A2:truncated=true → 表单带 truncated=1,服务端记 truncate_note、之后机身完整版可自动替换。 */
    public static Result upload(File file, int durationSec, String cookie, String uploadUrl,
                                String fileName, String mime, String sn, long placeholderId, String recordedAt,
                                String penFile, boolean truncated) {
        return upload(file, durationSec, cookie, uploadUrl, fileName, mime, sn, placeholderId, recordedAt, penFile, truncated, null);
    }

    /** B1(复审):source="phone" → 服务端按手机录音权限校验(否则默认按录音笔权限,只开手机权限的顾问恒403)。 */
    public static Result upload(File file, int durationSec, String cookie, String uploadUrl,
                                String fileName, String mime, String sn, long placeholderId, String recordedAt,
                                String penFile, boolean truncated, String source) {
        if (file == null || !file.exists() || file.length() == 0) {
            return new Result(false, -1, "录音文件为空");
        }
        if (uploadUrl == null || uploadUrl.isEmpty()) {
            return new Result(false, -1, "上传地址未配置");
        }

        String boundary = "----gongpai" + System.currentTimeMillis();
        HttpURLConnection conn = null;
        try {
            disarmGlobalCookieHandler();
            URL url = new URL(uploadUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setUseCaches(false);
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(30000);
            // 问题 #4：弱网下大文件(长录音/录音笔几十 MB)上传慢，120s 偏短易超时丢传，放宽到 5 分钟。
            conn.setReadTimeout(300000);
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
                // pen_file 字段（录音笔机身文件名，后端按它去重，防扫描补传重复；可空）
                if (penFile != null && !penFile.isEmpty()) {
                    out.writeBytes("--" + boundary + CRLF);
                    out.writeBytes("Content-Disposition: form-data; name=\"pen_file\"" + CRLF + CRLF);
                    out.write(penFile.getBytes(StandardCharsets.UTF_8));
                    out.writeBytes(CRLF);
                }
                // B1 source 字段（"phone"=手机麦，服务端按 allow_phone_rec 校验；空则按笔）
                if (source != null && !source.isEmpty()) {
                    out.writeBytes("--" + boundary + CRLF);
                    out.writeBytes("Content-Disposition: form-data; name=\"source\"" + CRLF + CRLF);
                    out.write(source.getBytes(StandardCharsets.UTF_8));
                    out.writeBytes(CRLF);
                }
                // A2 truncated 字段（诚实"可能不完整"标记，服务端记 note+允许完整版替换）
                if (truncated) {
                    out.writeBytes("--" + boundary + CRLF);
                    out.writeBytes("Content-Disposition: form-data; name=\"truncated\"" + CRLF + CRLF);
                    out.write("1".getBytes(StandardCharsets.UTF_8));
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
                    boolean discarded = obj.optBoolean("discarded", false);
                    // 老服务端的 deduped 响应没有 pen_exact 字段 → 按"非精确"处理(宁可不删机身文件)
                    boolean dedupFuzzy = obj.optBoolean("deduped", false) && !obj.optBoolean("pen_exact", false);
                    return new Result(true, id, null, false, discarded, dedupFuzzy);
                } catch (Exception parseErr) {
                    // A4(P0):2xx 但响应不是 JSON——多半是酒店/医院 captive-portal WiFi 返回 200 HTML。
                    //   绝不能当成功(那样音频已删、服务端没收到、占位永挂"同步中")→ 按临时故障重试。
                    Log.w(TAG, "2xx 但响应非 JSON(疑似 captive portal)，按临时失败重试: " + body);
                    return new Result(false, -1, "上传响应异常，稍后自动重试", true);
                }
            }
            if (code == 401) {
                return new Result(false, -1, "登录已失效，请在 App 里重新登录");  // 永久(重试也没用，需重登)
            }
            if (code == 403) {
                // B7(批次六):403≠登录失效——权限被关等场景,透传服务端原话按永久失败处理,
                // 不再触发无效重登+每2分钟整文件白传的无限重试
                String msg = "无权限，已停止重试";
                try { msg = new JSONObject(body).optString("error", msg); } catch (Exception ignore) {}
                return new Result(false, -1, msg);
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
        return createPlaceholder(cookie, placeholderUrl, recordedAtWallMs, null, null);
    }

    /**
     * E1:占位带机身文件名 pen_file → 同步弹层预检可精确匹配"正在传的段"(不再赌 ±90s 时刻吻合)；
     * D12:source="phone" → 占位按手机麦来源入库(服务端权限/回填口径正确)。两者皆可空。
     */
    public static long createPlaceholder(String cookie, String placeholderUrl, long recordedAtWallMs,
                                         String penFile, String source) {
        return createPlaceholder(cookie, placeholderUrl, recordedAtWallMs, penFile, source, 0);
    }

    /**
     * ★2.2.0：占位再带上【时长】——顾问在"同步中"那行就能看到"15:40 – 15:45 · 4分23秒"，
     * 靠时段认出是哪位顾客直接绑定（音频还没传完时本来就没法试听，这是唯一的识别依据）。
     * 时长从笔的机身文件列表就能拿到（导入时已知），以前白白丢掉了。durSec<=0 时不带该字段。
     */
    public static long createPlaceholder(String cookie, String placeholderUrl, long recordedAtWallMs,
                                         String penFile, String source, int durSec) {
        if (placeholderUrl == null || placeholderUrl.isEmpty()) return -1;
        HttpURLConnection conn = null;
        String boundary = "----gongpaiPH" + System.currentTimeMillis();
        try {
            disarmGlobalCookieHandler();
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
                // ★2.2.0 duration_sec：占位就带上时长 → "同步中"那行能显示"15:40 – 15:45 · 4分23秒"
                if (durSec > 0) {
                    out.writeBytes("--" + boundary + CRLF);
                    out.writeBytes("Content-Disposition: form-data; name=\"duration_sec\"" + CRLF + CRLF);
                    out.write(String.valueOf(durSec).getBytes(StandardCharsets.UTF_8));
                    out.writeBytes(CRLF);
                }
                if (penFile != null && !penFile.isEmpty()) {
                    out.writeBytes("--" + boundary + CRLF);
                    out.writeBytes("Content-Disposition: form-data; name=\"pen_file\"" + CRLF + CRLF);
                    out.write(penFile.getBytes(StandardCharsets.UTF_8));
                    out.writeBytes(CRLF);
                }
                if (source != null && !source.isEmpty()) {
                    out.writeBytes("--" + boundary + CRLF);
                    out.writeBytes("Content-Disposition: form-data; name=\"source\"" + CRLF + CRLF);
                    out.write(source.getBytes(StandardCharsets.UTF_8));
                    out.writeBytes(CRLF);
                }
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
            disarmGlobalCookieHandler();
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

    /** SN 校验结果：allow=准不准用这台笔，message=拒绝时给用户的话。 */
    public static final class SnVerdict {
        public final boolean allow;
        public final String message;
        public SnVerdict(boolean allow, String message) { this.allow = allow; this.message = message; }
    }

    /**
     * 连上录音笔后上报它的 SN，问后端这台准不准这个顾问用。
     * 返回 SnVerdict；网络/服务端出错返回 null（调用方按 fail-open 处理，别因网络抖动挡录音）。
     */
    public static SnVerdict reportSn(String cookie, String url, String sn) {
        if (url == null || url.isEmpty() || sn == null || sn.isEmpty()) return null;
        HttpURLConnection conn = null;
        String boundary = "----gongpaiSN" + System.currentTimeMillis();
        try {
            disarmGlobalCookieHandler();
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setUseCaches(false);
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            if (cookie != null && !cookie.isEmpty()) conn.setRequestProperty("Cookie", cookie);
            try (DataOutputStream out = new DataOutputStream(conn.getOutputStream())) {
                out.writeBytes("--" + boundary + CRLF);
                out.writeBytes("Content-Disposition: form-data; name=\"sn\"" + CRLF + CRLF);
                out.write(sn.getBytes(StandardCharsets.UTF_8));
                out.writeBytes(CRLF);
                out.writeBytes("--" + boundary + "--" + CRLF);
                out.flush();
            }
            int code = conn.getResponseCode();
            String body = readBody(code < 400 ? conn.getInputStream() : conn.getErrorStream());
            if (code >= 200 && code < 300) {
                JSONObject o = new JSONObject(body);
                boolean allow = !"deny".equals(o.optString("decision", "allow"));
                return new SnVerdict(allow, o.optString("message", ""));
            }
            Log.w(TAG, "reportSn http " + code + " " + body);
            return null;   // 服务端错 → fail-open
        } catch (Exception e) {
            Log.w(TAG, "reportSn failed: " + e.getMessage());
            return null;   // 网络失败 → fail-open
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
