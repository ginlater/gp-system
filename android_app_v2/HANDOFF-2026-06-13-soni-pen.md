# android_app_v2 会话交接 — 蓝牙陪伴笔换成「声云录音卡片」(PNote SDK)

> 日期：2026-06-13｜写给下一个 Claude Code 会话接手。
> 上一份交接是 [`HANDOFF-2026-06-12.md`](./HANDOFF-2026-06-12.md)（录音笔逻辑同步 + 网页对齐审计），那份的「陪伴笔」指**杰理笔**。
> **本会话把 v2 的蓝牙陪伴笔从杰理笔整体换成了声云录音卡片**，并真机全链路验证通过。
> 唯一事实源仍是 [`SPEC.md`](./SPEC.md)；长期记忆见 `soni-pen-v2-integration`（已自动加载）。

---

## 0. TL;DR

- **v2（顾问端原生 Compose 重写）的蓝牙陪伴笔，从此用「声云录音卡片」**（SDK 类 `com.wind.pnote.ui.PNote`），不再用杰理笔。杰理笔只留给老 WebView app（`../android_app`）。
- 新引擎 `soni/SoniPenController.java`（~2000 行）= 杰理 `PenController` 的全量行为移植到声云协议；UI 经 `RecordingControllerImpl` 接入，**上层 ViewModel / Compose 屏零改动**。
- **真机全链路已验证通过**（vivo PD2445 + 声云卡片 CB08）：扫描/连接/App 开停录/笔按键开停录/实时流直传/断线补传/中途断点续传/被杀恢复/主线程冻死自愈/手动同步/连接级保活，全绿。生产 recId 1082/1084/1085/1087/1088/1089/1095 均落库正确。
- **编译状态：`./gradlew :app:assembleDebug` 绿。已装在 vivo 真机上跑过。**
- **改动全部未 commit**（`android_app_v2/` 整个目录是 git untracked，分支 `online-0527`）。
- App 改名「**刁姐私教**」+ 换了人像图标（与生产「刁姐陪伴.apk」是不同包，debug 签名共存）。

---

## 1. 为什么换 + 两种笔的关系

- 用户的硬件供应商换了。**v2 = 声云笔；老 WebView app（android_app）= 杰理笔**，两者独立、互不影响。
- 声云 SDK 资料在 `/Users/ginlater/Downloads/`：
  - `蓝牙_声云录音卡片sdk518/`（**官方 SDK**：`pnote-android-sdk/pnote_20260313081243.aar` + 两份开发文档 md）
  - `soni-sdkdemo-main/`（Flutter 联调 demo，**不是纯安卓 SDK**，但内嵌一份更新的 AAR——**别用它那份**，见 §4 坑①）
  - `声云录音卡片 SDK 产品说明与集成手册.pdf`（27 页完整手册，pypdf 可抽文本）

---

## 2. 声云 SDK 协议速记（接入这层全吸收了，上层无感）

「指令下发 + JSON 事件回调」异步模型，所有命令立即返回，结果在 `DeviceDataListener.onDeviceDataEvent(String json)` 里按 `cmd` 字段分发。

**关键 cmd：** 1=搜索结果 / 2=连接状态 / 3=录音状态(record_state 1录2暂停0停) / 4=文件列表(分批,finish=1结束) / 5=传输进度(record_file_state 0完成4传输中1不存在2offset过大3停止) / 6=电量 / 7=SN / 8=**笔实体按键**(event 1开始3停止5暂停7继续) / 9=录音状态查询 / 10=录音时长 / 11=录音文件名。

**音频：** 标准 opus 裸帧，16kHz 单声道，40 字节/帧(20ms)；实时流 `onDeviceRecordData` 160B/包(=4帧)，约 2000 B/s；文件传输 `onDeviceFileData`。两者都是**无容器裸帧**。

**和杰理笔的关键差异（SoniPenController 已全部处理）：**
- 命令是异步 JSON，不是杰理那种回调对象。
- 文件下载用 `startGetFile(fileName, int offset)` 按字节断点续传，**没有「文件对象」概念**，自己攒字节到 `.part` 文件。
- 笔实体按键走「请求→应答」握手：收到 cmd=8 必须回 `start/stop/pause/continueBtnBackRecord()`，笔才真执行。
- **连上必发 `syncTime`** 同步手机时间（根治杰理时代「笔时钟回 2019 补传取错文件」那坑）。
- SN = `getSn()` 回的 `sn` 字段（实测 `sD1A1CD00005A`，是 MAC 加前缀 s）。

---

## 3. 本会话落到哪些文件

### 新建
- **`app/src/main/java/com/airec/bledemo/soni/SoniPenController.java`**（~2000 行，核心引擎）
  杰理 `PenController` 的全量行为移植：镜像状态机、真连接判定+心跳探活、实时流捕获+卡流看门狗+截断闸门、开录健康看门狗(9s无证据报错)、断线25s宽限、自动重连(含蓝牙开关广播)、补传队列 **v24 语义**(持久化+退避+跳过冷却队头挑ready+僵尸2h清理+cancelUpload)、SN归属校验fail-open、30天旧文件清理、penlog/last_result 诊断。**外加杰理版没有的**：主线程冻结看门狗、btReady 适配器守卫、接管会话 sessionAdopted 标记、401 不丢段重试+刷Cookie。
- **`soni/OggOpusWriter.java`**（154 行）纯 Java 把 opus 裸帧打包成标准 Ogg Opus（RFC7845/3533，Ogg CRC 0x04C11DB7）。上传 `.ogg` → 服务器 `/api/consultant/upload` 本来就收 ogg、ffprobe/ASR 直接可用，**彻底绕开杰理时代的 ATWOpusConverter 乱码链，服务器零改动**。
- **`soni/SoniScanActivity.java`**（276 行）声云扫描/连接页，对齐旧 ScanActivity UX（新鲜度过期、自动连上次那支、真在线才算连上收页），带引擎自举（冷启直进也能用）。

### 改动
- **`recording/RecordingControllerImpl.kt`** — `penController` 从 `PenController` 换成 `SoniPenController`；`penListener` 改实现 `SoniPenController.Listener`（删了声云没有的 `onPenPowerOnRecordDisabled`）；`connectPen()` 跳 `SoniScanActivity`；`onAuthExpired` 回调接 `RecordingModule.refreshUploadContext`；`attach/detach` 接 `setAppForeground`；`onPenRecordStatus(true)` 强制 `currentSource=Pen`；**`stopPhoneMic` 改回普通 `startService`**（修 ForegroundServiceDidNotStartInTimeException 崩溃，见 §4 坑④）。
- **`recording/PenKeepAliveService.java`** — 从 android_app 移植 **v23 两段式连接级保活**（CONN=连着就挂FGS / REC=录音再加唤醒锁，断开去抖90s，START_STICKY，通知点 MeiliActivity），保留 `start()` 兼容别名给备份壳。
- **`App.java`** — `onCreate` 加引擎自举 + 自动回连（STICKY 拉活后无 Activity 也能续传）。
- **`AndroidManifest.xml`** — 加 `soni.SoniScanActivity`（`exported=true`，**调试用，发布前回收**）。
- **`app/build.gradle`** — 加声云 SDK 实际引用的 8 个依赖（rxandroidble2/rxjava2/rxjava3/eventbus/androidasync/xutils/gson）。
- **`app/libs/pnote_20260313081243.aar`** — 声云官方 518 AAR。
- **`res/values/strings.xml`** — `app_name` → 「刁姐私教」。
- **`res/mipmap-*/ic_launcher{,_round}.png`** — 换成用户人像图标（脚本生成，源图 `/Users/ginlater/Downloads/20260612-233336.jpg`）。

---

## 4. 真机踩过的坑（**最重要，下个会话别重蹈**）

**① SDK 必须用 518 官方 AAR，不是 demo 那份。** demo（soni-sdkdemo）里 2026-05 的 AAR 扫描时按设备名含 `pnote` 过滤，而真卡片广播名是 **CB08** → **永远扫不到**。换 `蓝牙_声云录音卡片sdk518/pnote-android-sdk/pnote_20260313081243.aar` 后秒出 cmd=1。当时排查靠开 `PNoteLogger.isShowLogText=true` 看到「搜索到设备 name=CB08」但没进回调，才定位到过滤。

**② ★SDK 把 BLE 命令写死在主线程（最毒）。** 反编译坐实 `WindBluetoothService.onMessageEvent` 是 `@Subscribe(threadMode=MAIN)`——**所有 PNote 调用都在我们的主线程执行**。蓝牙关闭/切换瞬间 SDK 内部 closeConnect/startSearch 同步阻塞 → **主线程整个冻死**（真机实测冻了 201 秒，penlog 全停、UI 无响应）。三层防御已上：
  - `btReady()`（适配器 STATE_ON 守卫）包住所有 BLE 命令（`bleCmd()`）；
  - **主线程冻结看门狗**（后台守护线程探活，主线程 >20s 不应答 → `Process.killProcess` 自杀重启）；
  - `App.onCreate` 引擎自举 + 自动回连（STICKY 前台服务拉活后无界面也能续传）。
  - 实战中看门狗触发过多次，自愈链全部跑通、一段录音没丢。**这是必需的，别删。**

**③ SDK 命令要等服务就绪。** `PNote.init()` 绑 `WindBluetoothService` 是异步的，服务没起来前发命令被静默丢（logcat `No subscribers registered for BluetoothEvent`）。`whenSdkReady()` 用 `EventBus.hasSubscriberForEvent` 探测后再发，封顶 10s。

**④ v2 宿主层老 bug：`stopPhoneMic` 误用 `startForegroundService` 发 STOP。** service 没在跑时（UI 残留态点停止）被系统按 `ForegroundServiceDidNotStartInTimeException` 杀进程。已改回普通 `startService`（对齐旧宿主 ConsultantActivity）。这跟声云无关，是顺手发现修的。

**⑤ 接管会话(连上时笔已在录)尾段不能直传。** 实时流只覆盖接管之后，当完整段直传会抢先占坑，后面下载的完整版被服务器按 pen_file 去重丢弃（只剩尾段=丢音频）。`sessionAdopted` 标记强制走「补下载全文件」。

**⑥ 401(登录失效)绝不能丢段。** 旧逻辑 401 当永久失败直接丢任务（失败模式审计毒点#1）。已改：401 留 raw+占位、退避重试、回调刷 Cookie。实测**任务里冻结的过期 Cookie 是 401 主因**——上传改用引擎里的实时 cookie（`liveCk`）后直接传上。

**⑦ 笔报「传输完成」常少 ~480 字节。** 字节校验（`got < expected-40` 即重试）逮住后按 offset 续传秒补尾。**必须有这个校验**，否则段尾缺帧。

---

## 5. 验证过的链路（真机 vivo + CB08）

| 场景 | 结果 | 证据 |
|---|---|---|
| 扫描→连接→syncTime/SN/电量 | ✅ | CB08, sn=sD1A1CD00005A, 电量90 |
| App 开录→实时流→停录→直传 .ogg | ✅ | recId 1082, 57s 零丢帧 |
| **笔按键开录(event=1)** | ✅ | cmd8→startBtnBackRecord→cmd3→镜像 |
| **笔按键停录(event=3)** | ✅ | recId 1095, 6s, done 无乱码 |
| 断线(关蓝牙)→宽限→自动重连→接管→补下载 | ✅ | recId 1085/1089 |
| **下载中途断链→offset 断点续传** | ✅ | recId 1089(12分54秒), penlog 显示 offset=288000 续传 |
| App 被杀→持久化队列恢复→自动回连续传 | ✅ | — |
| **主线程冻死→看门狗自杀重启→自举自愈** | ✅ | penlog「★主线程冻结201s→自杀重启自愈」 |
| 「从陪伴笔同步」列表/勾选导入 | ✅ | recId 1087/1088 |
| 连接级 FGS 后台 2 分钟进程/心跳存活 | ✅ | — |
| 图标+改名「刁姐私教」 | ✅ | 桌面截图确认 |

**未验证：** 黑屏超长录(>30min)、暂停/继续(网页本也不暴露暂停)。

---

## 6. 关键架构 / 复用关系（写需求/维护必须知道）

- **唯一引擎实例**：`RecordingModule.controller`（单例）→ `RecordingControllerImpl` → `SoniPenController`。`SoniPenController.instance()` 是 static 单例，扫描页经它注册 `ScanListener`（同一个 PNote 监听）。
- **状态上报**：引擎单向 `RecordingBus.post(state,msg,dur,recId)` → `RecordingControllerImpl` 翻译成 `RecordingState`（StateFlow）→ UI collectAsState。状态串：idle/starting/recording/paused/uploading/error。
- **一次性提示**走 `penEvents: SharedFlow<String>`（连接成功/错误/归属拒绝）；**列表刷新**走 `penListChanged: SharedFlow<Unit>`。别塞 `Idle.errorMessage`（会被去重吞）。
- **「假录音中」巡检**（`RecordingControllerImpl.consistencyWatch`，3s）**必须带 `currentSource==Pen` 守卫**，否则误杀正在进行的手机麦录音（手机麦时 penController.isRecording()=false）。
- **上传上下文**（会话 Cookie + URL）：`RecordingModule.refreshUploadContext()` 从 NetworkModule 的 CookieJar 取，进首页/回前台时注入。上传地址 `https://gp.aibeautyfulwomen.com/api/consultant/upload`。
- **诊断**：penlog.txt / last_result.txt 落在 `getExternalFilesDir/stream_ops/`，路径与杰理版一致，「一键诊断上传」照常能捞（见记忆 `consultant-diag-upload`/`diag-upload-triage`）。

---

## 7. 待办 / 收尾（按优先级）

1. **【发布前必做】回收调试开关**：
   - `AndroidManifest.xml` 里 `soni.SoniScanActivity` 的 `android:exported="true"` → 改回 `false`（现在是为 adb 直接打开调试）。
   - `SoniPenController` 构造里 `PNote.setShowLog(true)` + `PNoteLogger.isShowLogText = true` → 关掉（联调期开的，刷屏 logcat）。
2. **commit**：本会话所有改动未提交（`android_app_v2/` 整个 untracked）。**注意 `android_app_v2/` 似乎被 git 忽略或从未 add**——下个会话要确认是单独成库还是纳入主仓（见记忆 `android-app-v2-build`「日后单独成 GitHub 仓库」）。APK 不在 git，发版另走 scp（记忆 `always-deploy-apk-after-android-change`）。
3. **分发**：v2 是 debug 包 `com.aibeautyfulwomen.gongpai.v2`，**绝不能覆盖生产 `/download` 的 `app-release.apk`**（那是杰理笔的生产包 `com.aibeautyfulwomen.gongpai`，包名不同+debug签名根本不会更新）。若要让 vivo 网页装 v2 需服务器另起路径，用户尚未拍板。
4. **补验**：黑屏超长录(>30min)、暂停/继续。
5. **承接 HANDOFF-2026-06-12 的旧待办**（与声云无关，仍有效）：管理员端 Wave 3–5、session 级删除申请端点、SN 绑定 Phase2。

---

## 8. 怎么继续（环境 / 命令）

- **编译**：`cd android_app_v2 && ./gradlew :app:assembleDebug`（APK 出 `app/build/outputs/apk/debug/app-debug.apk`）。
- **真机**：adb 可连（vivo PD2445，`adb` 在 `~/Library/Android/sdk/platform-tools/`）。装：`adb install -r <apk>`。
- **直开调试页**（adb）：`adb shell am start -n com.aibeautyfulwomen.gongpai.v2/com.airec.bledemo.soni.SoniScanActivity`；主入口 `MeiliActivity`。
- **看引擎日志**：`adb logcat | grep -E "SoniPen|PNoteLogger"`；看协议流水靠 PNoteLogger（联调开关开着）。
- **看 penlog**（vivo 封 logcat 时靠它）：`adb shell cat /storage/emulated/0/Android/data/com.aibeautyfulwomen.gongpai.v2/files/stream_ops/penlog.txt`。
- **生产 DB 核对**：`ssh ubuntu@110.40.170.227`，`cd /opt/gp-system && sqlite3 recordings.db "SELECT ... FROM recordings WHERE pen_file=..."`（记忆 `prod-deploy-topology`；**换库必重启 gongpai**）。
- **触发各场景**：断线=`adb shell svc bluetooth disable/enable`；笔按键/同步导入需手按硬件或点 UI。

**红线复诵**：顾客可见处零「录音/录制」，一律「陪伴」系；设备叫「陪伴笔」；高级不廉价；360–390dp 不横向溢出；**引擎是踩坑稳定件，btReady守卫/冻结看门狗/断点续传字节校验/sessionAdopted/401不丢段 都不许删**。
