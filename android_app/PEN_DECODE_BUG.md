# 录音笔录音解码 Bug — 交接给后端（基于现有 SDK 修复）

> 整理时间：2026-06-02。真机：vivo V2445A + 已蓝牙连接的 AIREC 录音笔。
> 现象由真机录音 + Mac 离线 ffmpeg 验证，根因已确定。**手机麦克风录音链路正常，只有录音笔链路坏。**

---

## ✅ 已解决（2026-06-02 服务器侧，用真机样本离线验证）

**它确实是 opus（说明书说的"wav"指最终交付格式）**，不是 PCM。正确解码 = **每 82 字节记录跳过头 2 字节 `5B 50` 同步头，剩下就是一个标准 opus 包（SILK-WB 20ms / config 9 / 320样本），用 libopus 逐帧 `opus_decode`（缓冲≥320）→ 16kHz 单声道 → WAV**。

用真实 libopus 在样本 `debug_samples/pen_sample_*.bin` 上离线验证：**408/408 帧全部解码成功，8.16s，削顶 0%，过零 7.5%（噪音会是 ~49%），wavcheck 判定"真实语音"**。解码产物见 `debug_samples/pen_sample_DECODED.wav`（可直接试听）。

> 顺带澄清第 6 节"下载截断"疑点：**没有截断**。33456B 是压缩后的 opus，解出来正好 261120B PCM，与笔报告的 261676B 几乎一致——笔报告的是解码后大小。

**修复（已提交）**：之前各转码器错在两点——① `AudioConverter`/`decodeWithFixedStride` 把含 `5B` 同步头的整段当 opus 包（5B 是非法 TOC）；② `OpusBridge.decodeToWav` 分帧本来就对（跳 2 字节、步长 82），但 `FRAME_SIZE=160` 太小让 `opus_decode` 报 BUFFER_TOO_SMALL 全失败，且按文件扩展名误判 KA。
现在：`OpusBridge` 解码缓冲改 `5760`、改按内容首字节判 ATW/KA；`PenController.processAndUpload` 改调 `OpusBridge.decodeToWav`（不再走坏的 `AudioConverter.toWav`）。最终上传的就是 wav。**待 Mac 重新构建装机做真机端到端验证**（录音笔录→停→下载→解码 wav→上传→试听）。

离线复现：`python3` + 系统 `libopus.so.0`，跳 2 字节逐记录 `opus_decode`；或装 `ffmpeg/opus-tools` 后用 `debug_samples/wavcheck.py` 判产物。

---

## 1. TL;DR

录音笔录的音频上传后**试听一下就结束 / 是噪音**。根因：手机端把录音笔下载文件的**私有分帧头字节当成了 opus 数据**，导致重组出的 OGG 里**每个 opus 包都非法**，ffmpeg 和 droidkit 解码全部失败，解出来的是满幅噪音 / 近乎空白（0.06 秒）。

这个转码器是从 demo 直接搬来的，对这款笔的格式**从一开始就是错的**（demo 的 `PlayerActivity` 用的也是同一个 `AudioConverter.toWav`）。

---

## 2. 根因（已用 ffmpeg 离线证实）

录音笔下载下来的文件是**私有格式**：每 **82 字节**一条记录，记录头是 4 字节 `5B 50 4B 41`（ASCII `"[PKA"`）。

转码器 `AudioConverter.decodeWithFixedStride()` 提帧时**整段（含 `[PKA` 头）拷贝当成一个 opus 包**，写进 OGG：

- `AudioConverter.java:126-134`（提帧，`System.arraycopy(raw, pos, frame, 0, stride)`，`pos` 指向分隔符，没跳过头字节）
- `ATWOpusConverter.java:102-110`（**同样的 bug**，注释还写着"包含分隔符头字节"）
- `OpusToWavConverter.convert()` 只是调 `ATWOpusConverter` → 同坑

结果：每个 opus 包的首字节变成 `0x5B`（被当 opus TOC：config=11 SILK-WB-60ms、code=3），解码器读到第二字节 `0x50` 当帧数→非法→bail。所以 408 帧只"解"出 1 帧垃圾。

---

## 3. 证据

**真机日志（tag `AIREC_BLE` / `AIREC_CONV`）**
```
File: name=20260602020952 size=261676      # 笔报告该文件 261676 B
（实际下载到本地只有 33456 B = 408×82）      # ← 见第 6 节，疑似下载截断，待查
FixedStride: stride=82 data=80 gaps={82=407}
FixedStride: 408 frames
droidkit isOpusFile=1 ... 20260602020952.fixed.ogg
droidkit totalPcmDuration=0
droidkit decoded: 1920B                     # 只解出 1 帧 = 960 样本 = 0.06s
droidkit WAV: 1920B @ 16kHz
```

**Mac 离线 ffmpeg 解 `.fixed.ogg`**：每个包都报
`[opus] Error parsing the packet header` / `Invalid data found when processing input`（408 个包全错）。

**离线尝试不同剥头方式**（剥 4/5 字节、去/不去尾零）重组 OGG 再解：
解出的 wav **最大振幅 32768（削顶）、96–98% 非零 = 满幅噪音**，不是真实语音。
时长 2.5–4.9s 只是 granule 撑出来的，不是真音频。
→ 说明**"每条 82 字节记录 = 一个 opus 包"这个假设本身就是错的**，真实 opus 帧边界跟 82 字节不是一回事。

---

## 4. 私有文件格式观察（给修复用）

样本已入库：`android_app/debug_samples/pen_sample_20260602020952.bin`（33456 B，原始下载文件）和 `....broken.ogg`（当前错误重组的产物）。

每条记录 82 字节，结构大致：
```
偏移0   偏移4  偏移5...
5B 50 4B 41 | XX | <payload...>            XX=每帧一字节(值无规律: 46 47 47 00 04 01 01 02 87 80 ...)
```
前 3 条记录几乎全零（疑似录音开头静音/头信息），从第 4 条起每条 70–82 字节非零。

已验证 / 已排除：
- `byte[4]`（`[PKA` 后那字节）**不是 opus 包长度**（仅 1/408 帧吻合"长度"假设）。
- 把 `byte[4:]` 或 `byte[5:]` 整段（去或不去尾零）当 opus 包 → 全是噪音。
- 即"82 字节记录 ≠ 一个 opus 包"。82 很可能是**蓝牙传输块大小**，真正的 opus 码流应该是各记录 payload 的**拼接流**，其 opus 包边界另有规则（变长 / 自带长度前缀 / 拼成连续流）。这一层需要厂商格式说明或 iOS 端参考解码来确定。

---

## 5. SDK 能力（反编译 `app/libs/blesdk-release.aar` 得到）

`com.airec.blesdk.AIRECBleManager` 关键方法：
- `downloadFile(AIRECBleFile)` → 回调 `onFileDownloadComplete(file, localPath)`，给的是**原始私有格式文件**，SDK **不负责解码**。
- ❌ SDK **没有**任何"解码 / 导出 wav / 导出 PCM"方法。解码必须 App 自己做。
- ✅ `setAudioStreamListener(AudioStreamListener)` + `onAudioStreamData(byte[])` —— **录音时实时音频流**回调（"A 模式"）。若这个流是 PCM 或规整 opus，可**绕开文件格式解码**这个坑。
- `AIRECBleFile.getDurationSec()`（int）/ `getCreateTime()`（String）/ `getFileSize()`。

---

## 6. 次要疑点：下载是否截断

笔在文件列表里报 `20260602020952` 为 **261676 B**，本地下载到的文件只有 **33456 B**（= 408×82，正好整数条记录）。
两种可能：(a) 下载提前结束（截断）；(b) 笔报告的 size 含义不同（非纯 opus 字节）。
建议后端结合 SDK 下载实现 / `AIRECBleFile.getFileSize()` 语义核对一下；若确为截断，则除解码外还有下载完整性 bug。

---

## 7. 建议修复方向（择一，按可靠度排序）

1. **拿 AIREC 厂商的格式说明 / iOS 端解码参考**，搞清 `[PKA` 记录到 opus 码流的真实组帧规则，重写 `AudioConverter` 的私有格式分支。**最可靠**。
2. **改用 A 模式实时流** `onAudioStreamData(byte[])`：录音过程中收流、落地。若是 PCM 直接封 WAV；若是规整 opus 直接封 OGG。绕开文件格式解码。需改 `PenController` 录音逻辑 + 真机验证。
3. 若确认"每条 payload = 一个 opus 帧"，用 `OpusBridge`（libopus）逐帧 `opus_decode` 拼 PCM——但第 4 节实验显示当前按 82 切并非真实帧边界，**得先确定组帧规则**才能走这条。

> 后端可用仓库里的 `debug_samples/pen_sample_*.bin` 离线复现：用 ffmpeg / opus 工具反复试，不必每次真机录音。

---

## 8. 受影响代码位置

- `android_app/.../AudioConverter.java` — `convertPrivate` / `decodeWithFixedStride`（提帧 126-134、`writeOggOpus` 298-331、`decodeDroidkit` 174-294）
- `android_app/.../ATWOpusConverter.java` — `extractFrames` 102-110（同 bug）
- `android_app/.../OpusToWavConverter.java` — 调 `ATWOpusConverter`
- `android_app/.../PenController.java` — `processAndUpload` 165-206 调 `AudioConverter.toWav` 后上传
- 后端 `/api/consultant/upload`：接受 `webm/mp3/wav/m4a/mp4/ogg/aac/amr`，**不收 opus**（所以必须 App 端转成 wav 再传）

---

## 9. 临时可用方案

**手机麦克风录音链路（`PhoneMicService` → m4a → 上传）正常、能播放。** 录音笔修好前，用网页录音方式切到「🎤 手机」即可正常接诊录音。
