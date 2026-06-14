# 录音笔系统 5 大故障模式 —— v1(android_app)已修,android_app_v2 自查清单

> **给新的 Claude Code (Opus 4.8) 的任务**:
> 2026-06-13 对线上顾问端 `android_app`(WebView 套壳 + 杰理录音笔)做了三方面审计(上传卡住 / 录音乱码 / 录不上去),
> 发现并修复了 5 个最严重的问题(都进了 v25)。
> `android_app_v2` 是 Compose 原生重写,**复用了 v1 的整套录音引擎 Java 代码**(同包 `com.airec.bledemo`,
> 里面有 `PenController.java / PhoneMicService.java / Uploader.java / ATWOpusConverter.java` 等同名文件),
> 但它是 **v1 某个中间时间点的拷贝**,缺了后期几个修复。
> **请逐项核对 v2:缺的补上、有的确认逻辑完整。** 别只信下面的"标记速扫",一定读代码确认。

---

## v2 现状速扫(2026-06-14,按"修复标记在不在"粗判,务必深入验证)

| # | 问题 | v1 修复标记 | v2 粗扫 | 行动 |
|---|------|-------------|---------|------|
| #1 | 登录过期(401/403)丢音频 | `needsReauth` / `cookieNow` | ❌ 缺 | **重点补** |
| #2 | 服务器无兜底卡死 | (后端 webapp.py) | — | 共用后端,不用查 v2 |
| #3 | 录音后段乱码(KA 分帧错) | `extractFrames` 80字节块 | ✅ 有标记 | 确认逻辑对 |
| #4 | 没传上的录音无人知/无人捞 | `detectUnsynced` / sync-preview | ❌ 缺 | **重点补** |
| #5a | 手机麦被来电/抢麦静默录废 | `setOnErrorListener` | ✅ 有 | 确认含"保留文件" |
| #5b | 手机麦上传失败无持久化重传 | `retryPendingUploads` | ❌ 缺 | **重点补** |
| 附 | 录满 90 分钟自动结束 | `MAX_REC_SEC` | ❌ 缺 | 看是否要补 |
| 附 | 笔信号/电量诊断采集 | `onDeviceInfoUpdated` | ✅ 有 | — |

**结论:v2 大概率缺 #1、#4、#5b、90分钟自动停。下面是逐项详情。**

---

## 重要前提(决定每项是否适用 v2)

1. **笔的 SDK 可能不同**:v1 用杰理录音笔(AIRECBle SDK,私有 KA/ATW 格式);v2 文档记载**正在把蓝牙笔换成声云(PNote SDK)**(声云是 opus 裸帧 → 端上包 Ogg 直传 .ogg)。
   - **先确认 v2 实际跑的是哪种笔**(读 `PenController.java` / `RecordingControllerImpl.kt`,看连的是 AIRECBle 还是 PNote)。
   - 笔不同会影响 **#3(KA 是杰理特有)** 和 **#4(补传/文件列表机制)** 的适用性。
2. **UI 层不同**:v1 的提示在网页 `consultant.html`(如 `__onPenUnsynced`);v2 是 Compose,**所有面向顾问的提示要在 Compose 重做**(`ui/home/HomeScreen.kt`、`HomeViewModel.kt` 等),不能照搬网页。
3. **后端共用**:v1/v2 连同一个 `webapp.py`。**#2(服务器兜底)已在后端修好,v2 不用单独查**。
4. **检查方法**(二选一):
   - A. 把 v2 的 `.java` 和 v1 当前(修复后)版本 `diff`,看缺了哪些 commit 的改动;
   - B. 按下面每项的"v2 检查点"读 v2 代码判断。
   - ⚠️ 标记粗扫会漏判(v2 可能有等价但不同名实现),**务必读代码确认**。

---

## #1 登录过期(cookie 401/403)时丢音频 【通用,v2 缺,务必补】

- **现象**:顾问登录失效后,后台正在上传的录音被直接删掉(队列任务 + 服务器占位 + 本地音频文件),其实重登一下就能救,却永久丢了。
- **根因(v1)**:`Uploader` 把 HTTP 401/403 当"永久失败";上层 `workerTaskFailed(drop=true)` 删任务 + 删占位;本地直传路径(`uploadLocalOps`)连本地 `.ops` 一起删。
- **v1 修法**:
  1. `Uploader.Result` 加 `needsReauth` 字段,401/403 → `needsReauth=true`(不当永久失败 drop);
  2. 上传结果处理:遇 `needsReauth` → **留在队列、退避、不删任何文件/占位**,等用户重登;
  3. 上传时用"最新 cookie"——`cookieNow()`:实例最新 `this.cookie` 优先、任务固化的 cookie 回退。重登后队列里旧任务**自动用新 cookie 重传,不必重启 App**。
- **v1 位置**:`net/Uploader.java`(`Result.needsReauth`、`upload()` 的 `if (code==401||code==403)` 分支)、`PenController.java`(`cookieNow()`、`uploadLocalOps`/`processAndUpload` 的结果处理里 `r.needsReauth` 分支)。
- **v2 检查点**:
  - v2 上传录音到 `/api/consultant/upload` 的代码在哪?(Uploader.java 复用 or RecordingControllerImpl.kt 新写?)
  - 上传遇 401/403 时:**是不是当失败把录音删了**?有没有"留队等重登 + 用最新 cookie 重试"?
  - 本地录音文件在上传失败时会不会被删?
  - → 若 v2 上传失败就删文件 / 不重传,就有这个丢音频问题,按 v1 修法补。

---

## #2 服务器无兜底,卡死要重启 【后端,v1/v2 共用,不用查 v2】

- 现象:转写卡 `running`、或"上传中"占位永久残留,只能重启服务器。
- 修法在后端 `webapp.py`(`run_asr` 的 urlopen 加 timeout=60;`task_health_check_loop` 加"asr running 超 30min 标 failed"+"processing 占位超 3h 删")。
- **v2 连同一个后端,已修,跳过**。除非 v2 有自己的本地状态机/占位,才检查有没有永久卡死的兜底。

---

## #3 录音后段乱码(转换器分帧算法错) 【杰理特有,v2 已有标记需确认】

- **现象**:录音后段变乱码噪声(不是真实录音)。
- **根因(v1)**:上传用的 `ATWOpusConverter` 对杰理 KA 格式用"固定步长 stride"切帧(**错**);正确应按 **80 字节块逐块解析**(短帧块头 `4B 41`、`byte[2]=padlen`,剥 padding 补回 TOC `0x48`;长帧块整 80 字节即完整 opus 包)。`OpusBridge.java` 里有正确实现,但上传路径之前没用它。
- **v1 修法**:`ATWOpusConverter.extractFrames` 的 KA 分支改成 80 字节块逐块解析(对齐 `OpusBridge.extractFrames`)。
- **v2 检查点**:
  - v2 粗扫**有** `KA extracted` 标记 → 说明拷贝了修复后的 `ATWOpusConverter`。**确认 KA 分支确实是 80 字节块逐块(不是固定 stride)**。
  - ⚠️ **但要先确认 v2 用哪种笔**:若 v2 已换声云 PNote(opus 裸帧 → Ogg),KA 格式根本不出现,#3 的 KA 算法不适用 → 重点改为检查 **v2 把声云音频打包成 Ogg 的逻辑对不对**(分帧/打包错位同样会乱码)。
  - 通用教训:**别只信"下载字节数 = 笔报大小"**(v1 这个自检偏松,对"取错文件 / 错位但总长对"无感);要确认帧边界/内容正确。
  - **另一类乱码**:BLE 传输丢字节 / 源数据本身损坏(转换器救不了)——这是笔/链路硬件问题(见文末"头号根因")。

---

## #4 没传上来的录音"无人知、无人捞" 【通用,v2 缺,务必补】

- **现象**:① 补传放弃后只剩个数字角标,顾问不知道"录音还在笔里、要手动取回";② 录音中 App 被杀的那段,因自动扫描补传被关(`SWEEP_ENABLED=false`,旧版有"复活已删录音 / 时长 00:00 / 去重不准"的 bug),没人去笔里捞 → 录音静默消失。
- **v1 修法**(不重开有 bug 的自动扫描,改"检测 + 提示手动取回"):连上空闲拉笔文件列表 → 调后端 `sync-preview` 算准确"未传"数(已传 / 已删墓碑都过滤掉)→ UI 提示"小伙伴里有 N 段没导入,点取回"→ 打开"取回小伙伴"手动选。
- **v1 位置**:`PenController.java`(`detectUnsynced()`、`syncPreviewUrlFrom()`、`onFileListUpdated` 里 `detectUnsynced(files)`)、`net/Uploader.java`(`syncPreviewNewCount()`)、`ConsultantActivity.java`(`onPenUnsynced` → `evalJs`)、`consultant.html`(`window.__onPenUnsynced` 渲染提示)。**已有的"取回小伙伴"手动同步功能是前提。**
- **v2 检查点**:
  - v2 有没有"手动从笔取回录音"的功能(对应"取回小伙伴")?
  - 有没有"连上空闲时主动检测笔里有没传的段、并提示顾问"?(v2 粗扫**缺** `detectUnsynced`)
  - 录音没传上来时,顾问能不能发现(有提示 / 入口)?补传放弃 / 进程被杀的段,有没有兜底让顾问手动取回?
  - → v2 大概率缺这套,**别让"没传上的录音"静默消失**。提示 UI 要用 Compose 做(`HomeScreen`/`HomeViewModel`),不是网页那套。

---

## #5 手机麦录音兜底弱 【通用,v2 部分缺,务必补】

- **现象**:① 录音被来电 / 其他 App 抢麦时静默录废,界面还显示"录音中";② 手机麦上传失败 / 进程被杀,本地文件没人再传(无持久化重传队列);③ `stop()` 出错直接丢文件。
- **v1 修法**:
  1. `MediaRecorder.setOnErrorListener`:出错(来电 / 抢麦)→ 停录 + **保留已录文件** + 明确提示(不静默);
  2. `stop()` 失败时也**保留文件待重传**(不直接丢);
  3. `retryPendingUploads`:App 启动扫本地残留 `.m4a` 后台重传(成功删、登录失效留下次、用 MediaMetadataRetriever 读时长)。
- **v1 位置**:`recording/PhoneMicService.java`(`setOnErrorListener` / `onRecorderError` / `retryPendingUploads` / `stop()` 异常分支保留文件)、`ConsultantActivity.java`(启动时 `postDelayed` 调 `retryPendingUploads`)。
- **v2 检查点**:
  - v2 有没有手机麦录音(备用录音方式)?(粗扫 v2 有 `PhoneMicService.java`)
  - `setOnErrorListener` 在不在?(粗扫**有**)→ 但确认出错时是否**保留文件 + 提示**(不只是记日志)。
  - `stop()` 失败会不会丢文件?(确认有"保留待重传"分支)
  - **有没有 `retryPendingUploads`**(粗扫**缺**)→ App 启动 / 网络恢复时扫本地残留录音重传?谁在调它?
  - → v2 大概率缺"残留重传",上传失败 / 进程被杀的手机麦录音会丢,要补。

---

## 附:这次还顺手做的(v2 可一并核对)

- **录满 90 分钟自动结束**(`MAX_REC_SEC=90*60`):录音到 90 分钟 → 自动 `stopRecording`(笔 + App 都停、这段照常保存)、不自动续录(免与声控打架),顾问要继续手动点开始。v2 粗扫**缺**。位置:`PenController.java`(`MAX_REC_SEC`、`maybeAutoStopAt90`、`onRecordDurationUpdated` 和心跳里的检查、新段重置 `autoStoppedAt90`)。
- **笔信号/电量诊断采集**(`onDeviceInfoUpdated` → penlog 记"设备信息 电量X% 信号YdBm"、诊断 meta 加 battery/rssi):v2 粗扫**有**。

---

## 完整审计参考(top5 之外还有更多)

本文是优先级 top 5。完整审计还发现:笔时钟重置→补传取错文件、残缺流先入库后完整版被去重丢弃、补传队列被"传不动的僵尸任务"退避拖慢、字节数自检偏松(64字节容差+size=0放行)、实时流截断判据已停用、多支笔时补传任务不认笔(在错的笔上反复找)等。若要全面排查 v2,这些也值得对照。

---

## ⚠️ 头号根因(硬件,不是软件能补的)

v1 实测发现:**这批杰理笔"空闲时蓝牙正常,一开始录音蓝牙就停发数据 / 断开"**(三支笔都这样,电量信号都正常也断)——笔录音时停止向手机发数据 → 流断缺口 → 回落补下载 → 慢 / 卡99% / 乱码。这是**笔固件 / 这批笔的硬伤**,软件(重连 / 补传 / 上面这些修复)只能缓解、救不了。

**v2 换了声云 PNote 笔,务必重点验证:声云笔录音时 BLE 稳不稳定**(用 RSSI / 断连日志看,录音中会不会失联)。这比上面任何软件补丁都关键——如果新笔录音时链路稳,很多问题从根上就不存在了。
