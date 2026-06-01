# 交接文档 · Android 接诊 App（给下一个 Claude Code 会话）

> 这份文档是跨环境交接用的。上一段工作在一台**没有 Java/Android SDK 的服务器**上完成（只能写代码、不能编译）。
> 你现在大概率在一台**装了 Android Studio** 的机器上接手，目标是：编译通过 → 装到安卓手机 → 真机验证 → 修 bug。

---

## 一、背景：两个需求 + 先后顺序（已定）

线上系统 `gp-system`（Flask，`webapp.py` + `web_v2/templates/consultant.html`）是医美「工牌接诊 / 美丽陪伴」录音分析系统。顾问在网页上录音 → 上传 → ASR → 分析。
**痛点**：网页用浏览器 `getUserMedia`/`MediaRecorder` 录音，锁屏/切后台/来电会**掐断录音**——这是浏览器对麦克风/JS 生命周期的回收，纯网页堵不死。

两个需求：①给网页加"录音笔蓝牙录音"；②做 App 版。
**结论（已定）**：录音笔 SDK 是原生 Android `.aar`（`com.airec.blesdk.AIRECBleManager`），网页根本调不动，必须 App 当宿主 → **App 先行、录音笔叠在 App 上**。平台**先 Android**。
- 阶段一：手机麦克风前台 Service 录音（止血，切后台/锁屏不中断）。
- 阶段二：蓝牙录音笔（根治，笔本地录音，App/进程被杀都不丢）。

---

## 二、当前状态：阶段一+二 **代码完成，未编译、未真机验证**

这台机器没有 Android SDK，所以代码是**人肉静态自查**过的（括号配平、SDK 方法名在 `.aar` 字节码里逐个核对、R 资源齐全），但**没编译过**。你接手第一件事就是在 Android Studio 里 Sync + 构建，**很可能有少量编译错误要修**。

### 工程怎么来的
以你提供的 AIREC 蓝牙录音笔 Demo（`AIRECBleDemo2.zip`）为底座重建。保留了 Demo 的整套蓝牙能力（`MainActivity` 录音笔管理页、`ScanActivity` 扫描连接、`AudioConverter`/`OpusToWavConverter`/`ATWOpusConverter` 转码、`App` 里的 SDK 初始化），在上面加了接诊壳和录音管线。
- 源码包名 `com.airec.bledemo`（沿用 Demo，避免大规模重命名）；
- applicationId `com.aibeautyfulwomen.gongpai`（与线上一致）；
- SDK：`app/libs/blesdk-release.aar`（24K，已在仓库里）。

---

## 三、架构（一句话）

**手机麦克风 和 蓝牙录音笔 走同一条管线：**

```
录音(手机麦 / 录音笔)
  → RecordingBus（进程内状态总线，STATE_RECORDING/UPLOADING/IDLE/ERROR）
  → ConsultantActivity.onStatus（更新底部录音条 UI）
  → Uploader 上传到 /api/consultant/upload（带 WebView 登录 cookie）
  → 成功后 evalJs window.__onNativeUploaded(id)
  → 网页复用现有「刷新未归档 + 打开绑定客人弹窗」流程
```

UI：`ConsultantActivity` 是启动页，全屏 WebView 加载接诊网页；底部一条原生录音条 = `[🎤手机/🖊录音笔 切换按钮] + [● 开始/■ 结束] + 状态 + 计时`。

### 新增/改写文件清单（`app/src/main/java/com/airec/bledemo/`）
| 文件 | 作用 |
|---|---|
| `ConsultantActivity.java` | **启动页**。WebView + JS桥 + 底部录音条 + 来源切换 + 权限 + 生命周期。核心活动。 |
| `WebAppBridge.java` | 注入网页的 `window.AndroidBridge`：isApp/getSources/startRecording/stopRecording/openPenManager。 |
| `recording/PhoneMicService.java` | 手机麦克风**前台 Service** 录音（MediaRecorder → m4a），切后台/锁屏不中断；停录后带 cookie 上传。 |
| `recording/RecordingBus.java` | 极简进程内状态总线（不用 LocalBroadcastManager）。 |
| `net/Uploader.java` | multipart 上传，带 Cookie；有 m4a 与通用(可传 wav)两个重载。 |
| `PenController.java` | **录音笔 B 模式**：startRecord→endRecord→fetchFileList→定位新文件→downloadFile→onFileDownloadComplete→必要时 `AudioConverter.toWav` 转 wav→上传。状态走 RecordingBus，复用同一管线。 |

网页端：`web_v2/templates/consultant.html` 加了 `IN_APP` 检测——App 内隐藏自带录音卡片(`id=recCard`)、定义 `window.__onNativeUploaded/__onNativeRecState`。**浏览器里行为 100% 不变**。

---

## 四、★ 头号配置：START_URL

`ConsultantActivity.java` 顶部常量（约第 45 行）：

```java
private static final String START_URL = "https://gp.aibeautyfulwomen.com/consultant";
```

**接手第一件事就是确认它能不能打开。** 打不开就改成可访问地址，例如 `http://<服务器IP>/gp/consultant`。
上传地址由代码自动从 START_URL 推导（去掉结尾 `/consultant` 拼 `/api/consultant/upload`），所以**只改这一处**。`network_security_config.xml` 已放行明文 http 方便测试。

---

## 五、怎么构建（详见 `README.md` 和 `build_manual.sh`）

1. Android Studio 打开 `android_app/` 目录，Sync。
2. 改 START_URL。
3. 构建 debug：`./gradlew assembleDebug` 或直接 Run。
4. 装机：`adb install -r app/build/outputs/apk/debug/app-debug.apk`。
5. 正式签名包见 `README.md`（目前 `app/build.gradle` **没有 signingConfig**，release 是未签名包；测试用 debug 即可）。
- 构建环境：AGP 8.13.1 / Gradle 8.13 / JDK 17 / compileSdk 34 / minSdk 24 / targetSdk 34。
- 注意：`gradle.properties` 里原 Demo 写死的 macOS NDK 路径**已删除**（不需要 NDK，Opus 走 JNA）。

---

## 六、⚠️ 必须真机 + 配对录音笔核对的假设（PenController 里标了 TODO）

这些是**凭 SDK 文档/Demo 猜的**，连真笔才能验证：

1. **录音笔停录后的文件定位**：`onRecordStateChanged(false, fileName)` 给的 `fileName` 是否就是 `fetchFileList` 列表里的文件名？停录后多久能 fetch 到？（现在是延时 1.5s fetch，按文件名精确匹配，退化为按文件名倒序取最新。）
2. **笔下载文件的真实格式/扩展名**：是 wav/mp3（后端直接收）还是 opus/atw（要 `AudioConverter.toWav` 转 wav 再传）？后端 `/api/consultant/upload` 接受 `webm/mp3/wav/m4a/mp4/ogg/aac/amr`，**不收 opus**。
3. `AIRECBleFile.getDurationSec()` 是否数字类型（代码按数字用）。`getCreateTime()` 已确认是 String（之前踩过坑，已改成不拿它比大小）。
4. **SDK 回调归属**：`ConsultantActivity`/`PenController` 与 Demo `MainActivity`（录音笔管理页）共用 `AIRECBleManager.setCallback` / `App.setMainCallback`，注意互斥。ConsultantActivity.onResume 在选了录音笔时会 `penController.activate()` 夺回回调。
5. `ScanActivity` 是否自行申请 BLE 运行时权限（BLUETOOTH_SCAN/CONNECT、定位）。如不够，ConsultantActivity 里可能要补请求。

---

## 七、建议的下一步（给接手的会话）

1. **先编译通过**：Android Studio Sync + 构建，修编译错误（重点看 SDK 方法签名是否与 `.aar` 一致、`AIRECBleCallback` 是否抽象类/接口）。
2. **先测手机麦克风链路**（不依赖笔，最容易先跑通）：改好 START_URL → 登录 → 底部录音条录音 → 结束 → 自动上传 → 出现在「未归档录音」→ 绑定客人；再测**中断**：锁屏 / 切到别的 App / 从最近任务划掉，观察录音是否继续/被杀（国产 ROM 记得把 App 加电池白名单/自启动/锁后台）。
3. **再测录音笔链路**：底部切到「🖊录音笔」→ 扫描连接 → 录音 → 结束 → 看下载/转码/上传是否走通，把第六节 5 个假设逐个验证。
4. iOS 阶段二的 SDK 在 `AIRECIOSBleDemo(3).zip`（本次未入库，需要时再加）。

整体决策与背景也记在了项目记忆 `recording-pen-and-app-plan`。
