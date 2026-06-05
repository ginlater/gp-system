# 实时流 opus（.ops / KA-opus）后端改动说明

给服务器端的 Claude Code：本文件按"现状 → 要改成什么 → 逐处改动清单 + 可直接粘贴的代码"来写。
**只动 `webapp.py` 一个文件**（外加一次数据库自动迁移，见下）。改完不需要我这边动安卓。

---

## 0. 背景与目标（先读这段）

安卓 App 的录音方案变了：

- 录音笔通过蓝牙**边录边实时流**给手机，每个流块就是 **80 字节的杰理 KA-opus 块**。
- 手机把这些 80 字节块**按顺序拼接**成一个完整的 `.ops` 文件（= 杰理 KA 私有 opus 封装，和录音笔 U 盘里 `/RECORD/*.ops` 完全同一种格式，已真机验证可解码）。
- 录音结束时，手机把这个完整 `.ops` 文件**整包上传**到后端现有的 `/api/consultant/upload`。

后端现状（已确认）：

- 顾问端上传走 `POST /api/consultant/upload`，把文件原样 `oss_bucket.put_object` 存进 OSS，然后 `ingest_recording(...)` 入库。
- ASR 前在 `_ensure_clean_audio()` 里用 **ffmpeg** 把 webm/ogg 等转成干净 16k 单声道 wav，再交给 DashScope `fun-asr`。
- 播放/下载统一靠 `oss_signed_url(rec["oss_key"], ...)` 直接对 OSS 上那个对象签名给浏览器播。
- `recordings` 表已有列：`oss_key / recorded_at / duration_label / size_bytes / source / upload_status / asr_status ...`（建表在 webapp.py ~246 行；运行时迁移在 ~459 行）。

`.ops` 带来的两个新问题：

1. **上传会被拒**：`/api/consultant/upload` 的扩展名白名单里没有 `ops`（也没有 `opus`），现在 `ext` 会被强制改成 `webm`，文件名/类型全乱。
2. **ASR 认不了、浏览器播不了**：`.ops` 是 KA 私有封装，ffmpeg / DashScope / `<audio>` 都解不了，必须先用我们自己的 `decode_ka()`（纯 Python + libopus）解成 PCM/wav。

目标：

- 让 `/api/consultant/upload` **接受 `.ops` / `.opus`**，并新增可选表单字段 `recorded_at`（录音真实开始时间）回填到 `recordings.recorded_at`。
- `.ops` **原样存 OSS**（KA 比 wav 小约 8 倍，省空间），**只在需要时解码**：
  - ASR 前在 `_ensure_clean_audio()` 里识别 KA → 解码成 wav 再走 ASR；
  - 播放时另存一份可播放版（wav/mp3）到 OSS，`audio_url` 指向可播放版，原始 `.ops` 保留。

---

## 1. 关键事实（解码逻辑的"唯一真相")

KA 解码法已在 `tools/导出录音笔.py` 的 `decode_ka()` 里验证过，规则如下（**逐字照抄，不要自己改**）：

- 文件 = 连续的 **80 字节块**，从头开始每 80 字节一块。
- 对每一块 `block`（`raw[pos:pos+80]`）：
  - 若 `block[0]==0x4B and block[1]==0x41`（即 ASCII `"KA"`，short-frame 块）：
    - `pad = block[2]`，`plen = max(0, min(77, 77 - pad))`
    - opus 包 = `bytes([0x48]) + block[3:3+plen]`（**前面补回被剥离的 TOC 字节 `0x48`**）
  - 否则（long-frame 块）：整块 80 字节就是一个完整 opus 包，`fr = block`
  - 用 libopus `opus_decode` 解（**16000 Hz、单声道**），把 PCM int16 累加。
- 解出来是 **16k 单声道 PCM**，写成标准 WAV 即可。

**识别一个文件是不是 KA-opus**：扩展名是 `.ops`，或文件首 2 字节是 `0x4B 0x41`（`"KA"`）。（首块一般是 short-frame，会以 KA 开头；保险起见两条都查。）

---

## 2. 服务器依赖（部署侧要确认）

`decode_ka()` 需要 **libopus** 动态库（`opus_decoder_create / opus_decode / opus_decoder_destroy`）。

- 导出脚本里写死的是 macOS 路径 `/opt/homebrew/lib/libopus.dylib`，**服务器上不能用这个写死路径**。
- 服务器（多半是 Linux）请用 `ctypes.util.find_library("opus")` 自动定位；装库：
  - Debian/Ubuntu：`apt-get install -y libopus0`（运行库；如需头文件再装 `libopus-dev`）
  - CentOS/RHEL：`yum install -y opus`
- 下面给的 `_load_libopus()` 已做成"先 find_library，再依次试常见路径"，并把 handle 缓存，不用每次 dlopen。
- ffmpeg / ffprobe 后端已经在用（`_run_ffmpeg` / `_ffprobe_duration`），可继续复用：我们把 KA 解成 wav 后，可选再用 ffmpeg 转 mp3 做播放版。

---

## 3. 逐处改动清单（文件 : 函数 : 大概位置 → 改成什么）

> 行号是当前版本的参考位置，可能随改动漂移，**以函数名 + 代码上下文为准**。

| # | 文件 : 函数 : 位置 | 改什么 |
|---|---|---|
| A | `webapp.py` : 模块级（建议放在 `AUDIO_EXTS` ~1103 行附近，或文件靠后的工具函数区） | **新增**：`_load_libopus()`、`decode_ka()`、`_write_wav_bytes()`、`_looks_like_ka()`、`_decode_ka_oss_to_wav()` 五个函数（§4 给出完整可粘贴代码）。 |
| B | `webapp.py` : `api_consultant_upload()` : ~8813–8815 行 | 扩展名白名单**加上 `ops`、`opus`**；默认兜底扩展名保持 `webm` 不变。（§5.1） |
| C | `webapp.py` : `api_consultant_upload()` : ~8817–8818 行 | **新增**读取可选表单字段 `recorded_at` 并解析；解析失败/未传则回退 `now`。回填给占位分支和 `ingest_recording` 两条路径。（§5.2） |
| D | `webapp.py` : `api_consultant_upload()` : 占位回填分支 ~8839–8843 行 | 占位 `UPDATE` 时**同时写入 `recorded_at`**（连录场景保真实时间）。`upload_status='done'`、`source` 等照旧不变。（§5.3） |
| E | `webapp.py` : `_ensure_clean_audio()` : ~1417–1456 行 | **新增 KA 分支**：识别 `.ops` / KA → 用 `decode_ka()` 解成 wav，**另存一个 `_clean.wav` 到 OSS 供 ASR 用**，但**不删原始 `.ops`、不改 `oss_key`**（原始要留存）。返回的是给 ASR 用的临时 wav key。（§5.4） |
| F | `webapp.py` : `recordings` 表 + 运行时迁移 : 建表 ~246 行 / 迁移 ~459–471 行 | **新增列 `playable_oss_key TEXT`**（播放用 wav/mp3 的 OSS key）。建表语句加一行 + 迁移块加一段 `ALTER TABLE`。（§5.5） |
| G | `webapp.py` : `api_consultant_upload()` 上传成功后 | **生成播放版**：上传完 `.ops` 后，解码成 wav（或再转 mp3）另存 OSS，写入 `recordings.playable_oss_key`。建议异步（不阻塞上传响应）。（§5.6） |
| H | `webapp.py` : 所有产出 `audio_url` 的地方（签名 `oss_key` 的端点） | 改成**优先用 `playable_oss_key`**：有就签它，没有再签 `oss_key`。统一封装成 `_playback_key(rec)` 一处改、多处调。涉及：`/api/recording/<rid>/url`（~5535）、`/api/consultant/recordings/pending`（~8951）、`api/sessions` 录音列表（~5346）、待办/复核列表（~5769 / ~10640 / ~10676 / ~10848）等所有 `oss_signed_url(... oss_key ...)`。（§5.7） |

> 说明：**为什么要 `playable_oss_key` 而不是直接把 `oss_key` 换成 wav** —— `.ops` 要"原样留存 + 省空间"，所以 `oss_key` 必须一直指向原始 `.ops`；播放/ASR 是它的"派生物"。ASR 那份 wav 是临时的、用完即弃（不入 OSS 长期留存也行，见 §5.4 备选）；播放那份 wav/mp3 要长期留存，所以单独存一列。

---

## 4. 可直接粘贴的工具函数（模块级，放 §3-A 位置）

```python
# ============ KA-opus(.ops) 解码（实时流录音笔格式） ============
# .ops = 杰理 KA 私有 opus 封装：每 80 字节一块；short-frame 块以 "KA"(0x4B 0x41) 开头、
# TOC 字节(0x48) 被剥离需补回；long-frame 块整块即完整 opus 包。16k 单声道。
# 解码法逐字来自 tools/导出录音笔.py 的 decode_ka()，已真机验证。
import ctypes
import ctypes.util as _ctypes_util
import struct as _ka_struct

_LIBOPUS = None  # 缓存已加载的 libopus handle

def _load_libopus():
    """加载 libopus（跨平台）。失败抛 RuntimeError。结果缓存复用。"""
    global _LIBOPUS
    if _LIBOPUS is not None:
        return _LIBOPUS
    candidates = []
    found = _ctypes_util.find_library("opus")
    if found:
        candidates.append(found)
    candidates += [
        "libopus.so.0", "libopus.so",                 # Linux
        "/usr/lib/x86_64-linux-gnu/libopus.so.0",     # Debian/Ubuntu
        "/usr/lib64/libopus.so.0",                     # CentOS/RHEL
        "/opt/homebrew/lib/libopus.dylib",            # macOS(arm)
        "/usr/local/lib/libopus.dylib",              # macOS(intel)
    ]
    last_err = None
    for name in candidates:
        try:
            o = ctypes.CDLL(name)
            o.opus_decoder_create.restype = ctypes.c_void_p
            o.opus_decoder_create.argtypes = [ctypes.c_int32, ctypes.c_int, ctypes.POINTER(ctypes.c_int)]
            o.opus_decode.restype = ctypes.c_int
            o.opus_decode.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_int32,
                                      ctypes.POINTER(ctypes.c_int16), ctypes.c_int, ctypes.c_int]
            o.opus_decoder_destroy.argtypes = [ctypes.c_void_p]
            _LIBOPUS = o
            return o
        except OSError as e:
            last_err = e
    raise RuntimeError(f"无法加载 libopus，请在服务器安装(apt install libopus0): {last_err}")


def decode_ka(raw):
    """KA 封装 bytes → 16k 单声道 PCM(int16, bytes)。返回 (pcm_bytes, ok_frames, bad_frames)。
    逐字照抄 tools/导出录音笔.py 的 decode_ka()，仅把 opus 参数改为内部 _load_libopus()。"""
    opus = _load_libopus()
    N = len(raw); FRAME = 5760; TOC = 0x48
    err = ctypes.c_int(0)
    dec = opus.opus_decoder_create(16000, 1, ctypes.byref(err))
    pcm = (ctypes.c_int16 * FRAME)()
    out = bytearray(); ok = bad = 0; pos = 0
    while pos + 80 <= N:
        b0, b1 = raw[pos], raw[pos + 1]
        if b0 == 0x4B and b1 == 0x41:                      # 短帧块：补回 TOC 0x48
            pad = raw[pos + 2]; plen = max(0, min(77, 77 - pad))
            fr = bytes([TOC]) + raw[pos + 3:pos + 3 + plen]
        else:                                              # 长帧块：整块即完整 opus 包
            fr = bytes(raw[pos:pos + 80])
        n = opus.opus_decode(dec, fr, len(fr), pcm, FRAME, 0)
        if n > 0:
            ok += 1
            for i in range(n):
                out += _ka_struct.pack('<h', pcm[i])
        else:
            bad += 1
        pos += 80
    try:
        opus.opus_decoder_destroy(dec)
    except Exception:
        pass
    return bytes(out), ok, bad


def _write_wav_bytes(path, pcm, sr=16000, ch=1):
    """16k 单声道 int16 PCM bytes → 标准 WAV 文件。"""
    data = len(pcm)
    with open(path, 'wb') as w:
        w.write(b'RIFF'); w.write(_ka_struct.pack('<I', 36 + data)); w.write(b'WAVE')
        w.write(b'fmt '); w.write(_ka_struct.pack('<IHHIIHH', 16, 1, ch, sr, sr * ch * 2, ch * 2, 16))
        w.write(b'data'); w.write(_ka_struct.pack('<I', data)); w.write(pcm)


def _looks_like_ka(oss_key, head_bytes=None):
    """判断是否 KA-opus(.ops)。oss_key 后缀 .ops/.opus 即认；或首 2 字节是 'KA'(0x4B 0x41)。"""
    if oss_key:
        ext = oss_key.rsplit(".", 1)[-1].lower() if "." in oss_key else ""
        if ext in ("ops", "opus"):
            return True
    if head_bytes and len(head_bytes) >= 2 and head_bytes[0] == 0x4B and head_bytes[1] == 0x41:
        return True
    return False


def _decode_ka_oss_to_wav(oss_key, out_wav_path):
    """从 OSS 取 .ops、KA 解码、写成 16k 单声道 wav 到 out_wav_path。
    返回时长秒数(float)；解码不出有效帧返回 0。"""
    raw = oss_bucket.get_object(oss_key).read()
    pcm, ok, bad = decode_ka(raw)
    if not pcm:
        return 0.0
    _write_wav_bytes(out_wav_path, pcm)
    return (len(pcm) // 2) / 16000.0  # int16 → 样本数 / 采样率
```

---

## 5. 各处具体改法（带代码片段）

### 5.1 (B) `/api/consultant/upload` 接受 `.ops` / `.opus`

`webapp.py` ~8814 行，把白名单加上两个扩展名：

```python
    # 改前：
    # if ext not in ("webm", "mp3", "wav", "m4a", "mp4", "ogg", "aac", "amr"):
    #     ext = "webm"
    # 改后：
    if ext not in ("webm", "mp3", "wav", "m4a", "mp4", "ogg", "aac", "amr", "ops", "opus"):
        ext = "webm"
```

> 同时建议把模块级 `AUDIO_EXTS`（~1103 行）补上 `.ops`，让 `scan_oss_bucket()` 也能识别历史 `.ops`：
> ```python
> AUDIO_EXTS = {".mp3", ".wav", ".m4a", ".flac", ".aac", ".ogg", ".opus", ".ops"}
> ```

### 5.2 (C) 新增可选表单字段 `recorded_at`

`webapp.py` ~8817 行，原来是无条件 `recorded_at = now.strftime(...)`。改成"优先用表单传来的真实时间，解析失败再回退 now"。**复用现有 `/api/upload` 里那套多格式解析**（~5419 行的写法），保持一致：

```python
    now = datetime.now()
    # ★录音真实开始时间：安卓传 'YYYY-MM-DD HH:MM:SS'；连录/补传场景必须用真实时间，别用服务器当前时间
    recorded_at_raw = (request.form.get("recorded_at") or "").strip()
    recorded_at = None
    if recorded_at_raw:
        for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%dT%H:%M:%S", "%Y-%m-%d %H:%M", "%Y-%m-%dT%H:%M"):
            try:
                recorded_at = datetime.strptime(recorded_at_raw, fmt).strftime("%Y-%m-%d %H:%M:%S")
                break
            except ValueError:
                continue
        if recorded_at is None:
            recorded_at = recorded_at_raw  # 兜底：原样存，别丢
    if not recorded_at:
        recorded_at = now.strftime("%Y-%m-%d %H:%M:%S")
    ts14 = now.strftime("%Y%m%d%H%M%S")  # 这行保持不变（仅用于 oss_key 文件名，不是录音时间）
```

> 注意：`ts14`（OSS 文件名里的时间戳）**继续用 `now`**，别改成 `recorded_at`，否则连录补传时文件名会和别的录音撞。真实时间只回填 `recordings.recorded_at` 这一列。

### 5.3 (D) 占位回填分支：写入 `recorded_at`，其余照旧

`webapp.py` ~8839 行的占位 `UPDATE`，加上 `recorded_at=?`。`upload_status='done'`、`source='consultant-pen'` **不变**：

```python
            db_write(
                """UPDATE recordings SET oss_key=?, size_bytes=?, duration_label=?,
                   recorded_at=?, source='consultant-pen', upload_status='done' WHERE id=?""",
                (oss_key, len(data), dur_label, recorded_at, prow["id"]),
            )
```

> 占位行最初由 `/api/consultant/placeholder` 用 App 传的 `recorded_at` 建好（~8871 行已支持），所以连录场景两端都有真实时间。这里再写一次是为了"上传时拿到更准的 recorded_at（如 App 在 upload 时才带）"，**幂等、无害**。若你想完全不动占位时间，可省略这一改，但建议保留以两端对齐。

非占位分支（~8850 `ingest_recording(...)`）已经把 `recorded_at=recorded_at` 传进去了，§5.2 改完后它自然就用上真实时间，无需再动。

### 5.4 (E) `_ensure_clean_audio()` 增加 KA 分支（ASR 前解码）

`webapp.py` ~1424 行函数开头。现状：扩展名是 `wav/mp3/m4a` 直接返回；否则 ffmpeg 转码并**替换 `oss_key`、删原文件**。

对 `.ops` 必须特殊处理：**解成 wav 给 ASR 用，但绝不删原始 `.ops`、绝不把 `oss_key` 改成 wav**（原始要留存且省空间）。

在函数最前面（`if not oss_key ...` 之后、取 `ext` 之前/之后均可）插入 KA 分支：

```python
def _ensure_clean_audio(recording_id, oss_key):
    """...（原 docstring 保留）...
    新增：KA-opus(.ops) 文件 → 用 decode_ka 解成临时 wav 上传 OSS 供 ASR，
    但不删原始 .ops、不改 recordings.oss_key（原始 KA 省空间且要留存）。"""
    if not oss_key or "." not in oss_key:
        return oss_key
    ext = oss_key.rsplit(".", 1)[-1].lower()

    # ★ KA-opus 分支：ffmpeg/DashScope 解不了 KA，先自解成 wav 再走 ASR
    if ext in ("ops", "opus") or _looks_like_ka(oss_key):
        import tempfile, os as _os
        tmpdir = tempfile.mkdtemp(prefix="ka_asr_")
        out = _os.path.join(tmpdir, "ka.wav")
        try:
            dur = _decode_ka_oss_to_wav(oss_key, out)
            if not dur or dur < 0.2:
                app.logger.warning("[clean audio] rec %s KA 解码无有效音频，回退原文件", recording_id)
                return oss_key
            # ASR 用的 wav：存一个临时 OSS key（带 _asr 后缀），ASR 取完即可（保留也行，体积小可接受）
            asr_key = (oss_key.rsplit(".", 1)[0]) + "_asr.wav"
            oss_bucket.put_object_from_file(asr_key, out)
            # 只回填时长（原 .ops 没法被 ffprobe 读时长）；不改 oss_key
            if not db_fetchone("SELECT duration_label FROM recordings WHERE id=?", (recording_id,))["duration_label"]:
                db_write("UPDATE recordings SET duration_label=? WHERE id=?",
                         (_format_duration_label(dur), recording_id))
            app.logger.info("[clean audio] rec %s KA 解码 → %s (%.1fs) 供 ASR", recording_id, asr_key, dur)
            return asr_key   # ★ 返回 ASR 用的 wav key，但 recordings.oss_key 仍指向原 .ops
        except Exception as e:
            app.logger.warning("[clean audio] rec %s KA 解码失败，回退原文件: %s", recording_id, e)
            return oss_key
        finally:
            import shutil
            shutil.rmtree(tmpdir, ignore_errors=True)

    if ext in ("wav", "mp3", "m4a"):
        return oss_key  # 已是带正确头的格式
    # ...（以下 ffmpeg webm→wav 原逻辑保持不变）...
```

> 关键差异：原 webm 分支会 `UPDATE recordings SET oss_key=new_key` 并 `_oss_delete_quiet(原文件)`；**KA 分支两件都不做**——`oss_key` 仍是 `.ops`，原文件不删。`run_asr()`（~1470）拿到 `_ensure_clean_audio` 的返回值去签名提交 ASR，它本就用返回值而非 `rec["oss_key"]`，所以无需改 `run_asr`。
>
> **备选（更省 OSS）**：ASR 用的 `_asr.wav` 也可以"不入 OSS"——DashScope 需要一个可访问 URL，所以必须先上传到 OSS 才能签 URL；ASR 完成后可在 `run_asr` 末尾把 `_asr.wav` 删掉（`_oss_delete_quiet(asr_key)`）。是否删取决于你是否还想缓存它做调试；不删也只是多占一份 wav，不影响正确性。**注意别误删 `oss_key`(.ops) 或 `playable_oss_key`**。

### 5.5 (F) `recordings` 表新增列 `playable_oss_key`

两处都要改（建表 + 运行时迁移），否则老库不会有这列。

**建表**（webapp.py ~246–266 行，`CREATE TABLE ... recordings`），在 `oss_key` 附近加一行：

```sql
    oss_key TEXT UNIQUE NOT NULL,
    playable_oss_key TEXT,          -- 浏览器可播放版(wav/mp3)的 OSS key；.ops 等需转码格式用，原始 oss_key 不变
```

**运行时迁移**（webapp.py ~459–471 行，那段 `rec_cols = {...}; if "xxx" not in rec_cols: ALTER TABLE`），追加一段：

```python
    if "playable_oss_key" not in rec_cols:
        conn.execute("ALTER TABLE recordings ADD COLUMN playable_oss_key TEXT")
```

### 5.6 (G) 上传后生成播放版并写入 `playable_oss_key`

`.ops` 浏览器播不了，需要一份可播放的 wav/mp3。建议**异步生成**（不阻塞上传响应），用现有 `trigger_pipeline_for_recording` 同样的"起线程"模式。

新增一个模块级函数：

```python
def _build_playable_for_recording(recording_id):
    """若该录音是 KA-opus(.ops)，解码生成可播放版(mp3)存 OSS，写入 playable_oss_key。
    幂等：已有 playable_oss_key 则跳过。失败仅记日志（不影响 ASR/上传）。"""
    rec = db_fetchone(
        "SELECT oss_key, playable_oss_key FROM recordings WHERE id=?", (recording_id,))
    if not rec or not rec["oss_key"]:
        return
    if rec["playable_oss_key"]:
        return
    oss_key = rec["oss_key"]
    if not (_looks_like_ka(oss_key) or oss_key.lower().endswith((".ops", ".opus"))):
        return  # 普通格式浏览器能直接播，不需要播放版
    import tempfile, os as _os, shutil
    tmpdir = tempfile.mkdtemp(prefix="ka_play_")
    wav = _os.path.join(tmpdir, "p.wav")
    mp3 = _os.path.join(tmpdir, "p.mp3")
    try:
        dur = _decode_ka_oss_to_wav(oss_key, wav)
        if not dur or dur < 0.2:
            return
        # 转 mp3 体积更小、所有浏览器可播（也可直接用 wav，省掉这步 ffmpeg）
        _run_ffmpeg(["-i", wav, "-vn", "-ac", "1", "-ar", "16000", "-b:a", "48k", mp3])
        play_key = (oss_key.rsplit(".", 1)[0]) + "_play.mp3"
        oss_bucket.put_object_from_file(play_key, mp3)
        db_write("UPDATE recordings SET playable_oss_key=? WHERE id=?", (play_key, recording_id))
        app.logger.info("[playable] rec %s 生成播放版 %s (%.1fs)", recording_id, play_key, dur)
    except Exception as e:
        app.logger.warning("[playable] rec %s 生成播放版失败: %s", recording_id, e)
    finally:
        shutil.rmtree(tmpdir, ignore_errors=True)
```

在 `api_consultant_upload()` 里，**两条成功路径**（占位回填分支 return 前、非占位 `ingest_recording` 之后）都起一个后台线程生成播放版：

```python
    # 占位回填分支，db_write 之后、return 之前：
    threading.Thread(target=_build_playable_for_recording,
                     args=(prow["id"],), daemon=True).start()
    return jsonify({"id": prow["id"], "oss_key": oss_key})
```

```python
    # 非占位分支，拿到 rid 之后、return 之前：
    threading.Thread(target=_build_playable_for_recording,
                     args=(rid,), daemon=True).start()
    return jsonify({"id": rid, "oss_key": oss_key})
```

> 也可改成"首次播放时懒生成"：但首播会卡几秒、且要在 `/api/recording/<rid>/url` 里同步解码，体验差。**推荐上传后异步预生成**。
>
> 想更省事可直接存 wav（去掉 ffmpeg 转 mp3 那步，`play_key` 用 `_play.wav`、`put_object_from_file(play_key, wav)`）。wav 浏览器也能播，只是体积比 mp3 大；但仍远小于把原始 .ops 换掉的方案，且原始 .ops 照样留存。

### 5.7 (H) 播放/下载统一优先用 `playable_oss_key`

现在每个返回 `audio_url` 的地方都是 `oss_signed_url(rec["oss_key"], ...)`。要统一改成"有播放版就签播放版"。加一个小工具，**所有签名播放 URL 的地方都改调它**：

```python
def _playback_key(rec):
    """返回用于浏览器播放/下载的 OSS key：优先 playable_oss_key(可播放转码版)，否则原 oss_key。
    rec 可为 sqlite3.Row 或 dict。"""
    try:
        pk = rec["playable_oss_key"] if "playable_oss_key" in rec.keys() else None
    except Exception:
        pk = rec.get("playable_oss_key") if isinstance(rec, dict) else None
    return pk or rec["oss_key"]
```

需要替换的端点（把 `oss_signed_url(rec["oss_key"], ...)` / `oss_signed_url(d["oss_key"], ...)` 换成 `oss_signed_url(_playback_key(rec), ...)`，并确保这些查询的 `SELECT` 带上了 `playable_oss_key`）：

- `/api/recording/<int:rid>/url` ~5518/5535（`SELECT` 要加 `playable_oss_key`）
- `/api/consultant/recordings/pending` ~8920/8951（`SELECT` 已列出多列，补 `playable_oss_key`）
- `api/sessions` 录音列表 ~5334/5346（`SELECT ... oss_key ...` 补 `playable_oss_key`）
- 待办/复核/拆分相关：~5749/5769、~10622/10640、~10663/10676、~10836/10848（凡 `SELECT r.oss_key` 处补 `r.playable_oss_key`，签名处换 `_playback_key`）

> **下载场景**（`?download=1`）：可以选择仍下载原始 `.ops`（体积小、可归档），或下载播放版。建议给老板/顾问下载**播放版 mp3**（能直接听）。`download_name` 的扩展名跟着 `_playback_key` 的实际后缀走即可。

> **批量回填历史 .ops（可选）**：若上线前已有 `.ops` 入库但没有 `playable_oss_key`，可写个一次性脚本对 `SELECT id FROM recordings WHERE (oss_key LIKE '%.ops' OR oss_key LIKE '%.opus') AND playable_oss_key IS NULL` 逐条调 `_build_playable_for_recording(id)`。

---

## 6. 占位回填流程确认（流程不变）

连录/边录边传的完整链路，**逻辑不变**，只是多带了 `recorded_at` 和生成了播放版：

1. App 录音开始/即将结束 → `POST /api/consultant/placeholder`（带 `recorded_at`=真实开始时间）→ 建一条 `upload_status='processing'`、`source='consultant-pen-placeholder'`、`session_id=NULL` 的占位行，返回 `placeholder_id`。
2. App 拼完 `.ops` → `POST /api/consultant/upload`，form 带 `placeholder_id`（>0）、`recorded_at`、可选 `sn`/`duration_sec`、file=`xxx.ops`。
3. 后端命中占位分支（`prow.uploader_user_id==本人` 且 `upload_status=='processing'`）→ `put_object(.ops)` → `UPDATE` 占位行：`oss_key`、`size_bytes`、`duration_label`、`recorded_at`、`source='consultant-pen'`、`upload_status='done'`（§5.3）→ 起线程生成播放版（§5.6）。**不新建行、不改 session 绑定**。
4. 上传失败 → App 调 `/api/consultant/placeholder/cancel` 清占位（不变）。
5. `recorded_at` 让"未归档列表"的服务日期/时段显示成真实录音时间（`_rec_time_fields` ~9001 已按 `recorded_at` 算），连录多段各自保真实时间。
6. ASR：占位 `upload_status` 变 `done` 后走正常流水线；`_ensure_clean_audio` 命中 KA 分支解码（§5.4）。注意占位是直接 `INSERT`/`UPDATE`、**不走 `ingest_recording`**，所以 ASR 由谁触发要确认：当前占位回填后没有显式 `trigger_pipeline_for_recording`（orphan 录音是绑定客户后才跑 ASR）。**本次改动不改这套触发时机**，KA 解码只在"真正跑 ASR 那一刻"由 `_ensure_clean_audio` 负责，时机无关。

> `sn`（录音笔序列号）字段：现有 upload 路由没读它；若 App 会传，按需在 upload 里 `request.form.get("sn")` 取用（比如写日志/绑定设备），**与本次格式改动无关，保持你现有处理即可**，本文不强制改。

---

## 7. 自测清单（改完后验证）

1. **扩展名**：POST 一个 `.ops` 到 `/api/consultant/upload`，确认 OSS 里对象后缀是 `.ops`（不是被改成 `.webm`），DB `oss_key` 以 `.ops` 结尾。
2. **recorded_at**：带 `recorded_at=2026-06-04 14:30:00` 上传，确认 `recordings.recorded_at` 正是这个值（不是服务器当前时间）。
3. **KA 解码**：取一个已知 `.ops`（U 盘导出验证过的），跑 `decode_ka(open(f,'rb').read())`，`ok` 帧数 > 0、PCM 长度合理（`len(pcm)//2/16000 ≈ 录音秒数`）。在服务器 Python 里先 `_load_libopus()` 不报错。
4. **ASR**：让一条 `.ops` 录音跑 ASR，确认 `_ensure_clean_audio` 走 KA 分支、生成 `_asr.wav`、DashScope 出转写文本，且 `recordings.oss_key` 仍是 `.ops`、原始 `.ops` 仍在 OSS。
5. **播放**：`/api/recording/<rid>/url` 返回的 URL 指向 `_play.mp3`（或 `_play.wav`），浏览器 `<audio>` 能播；原始 `.ops` 未被删。
6. **占位回填**：placeholder → upload(带 placeholder_id) 全程，确认是 `UPDATE` 同一行（id 不变）、`upload_status` 从 `processing` 变 `done`、`recorded_at` 是真实时间。

---

## 8. 一句话给负责改的同学

`webapp.py` 单文件改动：①upload 白名单加 `ops/opus` + 读 `recorded_at`；②加 `decode_ka` 等工具函数；③`_ensure_clean_audio` 加 KA 分支（解 wav 给 ASR，**不删不换原 .ops**）；④`recordings` 加 `playable_oss_key` 列，上传后异步解出可播放版，所有 `audio_url` 改用 `_playback_key()` 优先播放版。原始 `.ops` 永远原样留存、省空间。
