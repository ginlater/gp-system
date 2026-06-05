# 录音笔「连接即自动录音」问题 — 交接给接手会话

> 写于 2026-06-04。真机：vivo V2445A（`adb` 已通）+ AIREC 蓝牙录音笔。
> 这份是给**全新上下文**接手的，自包含。前序背景见同目录 `合作开发.md`、`PEN_DECODE_BUG.md`、`HANDOFF_NEXT.md`。

## 0. 目标用途
医美「接诊/陪伴」：顾问在 App（接诊页 WebView，加载线上 `gp.aibeautyfulwomen.com/consultant`）里**点开始录音→说话→点结束**，App 通过蓝牙录音笔录音→下载→解码成 wav→上传到后端 OSS→绑定客人分析。要的是**用户按命令控制起停的单段录音**。

## 1. 核心问题（当前最大卡点）
**录音笔一旦被蓝牙连上（不管我们 App 还是厂商官方「灵犀」App `com.hsd.record.airec`），就持续自动声控录音、每 ~3 秒自动起停一小段，停不下来。**
- 笔**本机（不连蓝牙）按物理键录音是正常的**（不自动录）。
- 一连蓝牙就自动录：实测官方 App 连上也一样自动录。
- 后果：用户点开始/结束没用，笔在自己录连续小段；状态栏曾疯狂闪「陪伴进行中↔保存中」(已被 App 门控压住，见下)。

## 2. ⚠️ 用户最新反馈（很重要，可能是我闯的祸）
用户说：**「最开始官方 App 是好的；是我（前一会话）执行了 `ensurePenConfigured` 写设置之后，笔恢复不了了，导致官方 App 现在也不行了。」**
即：**我对笔写设置（见第 4 节）可能把笔弄进了一个坏状态，连官方 App 都救不回来。** 头号任务是**把笔恢复出厂、让官方 App 重新可用**。

## 3. 笔的设置现状（用 SDK get* 读到，但回读不稳定/前后不一致）
最近几次读到：`noise=true(声控开) segDur=0 powerOnRec=false~true(漂移) idle=0~30(漂移)`。
**疑似出厂值**：`noiseSwitch=true、segmentDuration=0、powerOnRecord=true、idleShutdown=30`（这支 63:E6 笔最早读到的、且我代码只读没写时是这组）。

## 4. 我对笔下发过的写操作（可能的肇事者，需排查/回滚）
当前 `PenController.ensurePenConfigured()`（每次连接调一次，已加 `penSettingsWritten` 防循环）会写：
- `setPowerOnRecord(false)` → TX `55 AA 02 2E 00`（已生效，powerOnRec 读回 false）
- `setNoiseSwitch(false)`  → TX `55 AA 02 19 00`（**发了但 `getNoiseSwitch()` 回读仍 true，没生效/改不动**）
- **历史上还写过**（后来已从代码删除，但笔里可能已被写过）：`setSegmentDuration(0)`(TX `03 22 00 00`)、`setIdleShutdown(0)`(TX `05 39 00 00 00 00`)。
> 注意：`powerOnRecord=false` 需**关机重开**才生效；实测关机重开后笔**仍自动录** → 自动录不是 powerOnRecord，而是**声控(noiseSwitch)**，但 `setNoiseSwitch(false)` 改不动它。

## 5. SDK 命令映射（反编译 `app/libs/blesdk-release.aar` 的 `AIRECBleManager` 得到，已核实）
TX（手机发，帧 `55 AA <len> <cmd> <data>`）：
`0x02`syncTime / `0x03`startRecord / `0x04`endRecord / `0x05`fetchFileList / `0x08`downloadFile / `0x0A`deleteFile / `0x0E`fetchDeviceInfo / `0x10`pause==resume(同一命令!) / `0x12`firmware / `0x18`setLedSwitch(1B) / `0x19`setNoiseSwitch(1B,0/1) / `0x22`setSegmentDuration(2B大端) / `0x24`setUsbSwitch / `0x26`fetchInitParam / `0x2A`setMicGain(1..7) / `0x2E`setPowerOnRecord(1B) / `0x39`setIdleShutdown(4B大端) / `0x3C`formatDisk。
RX（笔回）：`0x03`onRecordStateChanged(true) / `0x04`onRecordStateChanged(false) / `0x0F`RecordStatus(data[0]==1=录音中) / `0x3B`onRecordDurationUpdated / `0x05/0x06/0xFE`文件列表 / `0x26`InitParam(解析出 noise/led/powerOn/segDur/idle/micGain) / set 类回执走"Setting confirmed"只打印。
- 出厂默认（SDK 构造里）：noise=true、led=true、powerOnRecord=false、segDur=0、idle=0、micGain=3（**这是 Java 对象初值，非设备真值；设备真值要 fetchInitParam 后读**）。
- demo `MainActivity` 的设置 picker：分段 `{0,5,10,15,30,60,120,180,240,480}`，`segmentLabel(0)="不分段"`；空闲关机 `{0,3,5,...}`，`0="不关机"`。**但本机固件疑似把 segDur=0 当"最短段"而非"不分段"**（待厂商确认）。
- SDK **无**"恢复出厂"方法；只能逐项 set* 写回。`formatDisk()` 只格式化存储、不重置设置，且对一支已坏的笔无响应(无 onFormatResult)。

## 6. App 侧已做好的（这部分是对的，别推翻）
- `PenController` 加了 `userRecording/stopping/stopHandled` 门控：**idle 时彻底忽略笔自发的 onRecordStateChanged 帧**（日志"忽略笔自发录音帧"），不 post、不 fetchFileList、不上传 → **闪烁已消除**、fetchFileList 刷屏已消除。只有用户点开始才跟踪、点结束才走一次 下载→解码→上传。
- `ConsultantActivity.onPenRecordStatus` 只在 `pendingRecordAfterConnect`(用户刚点开始) 时才动作，不把笔自发录音误显示成"录音中"。
- 网页 `consultant.html`：已删暂停功能；连接指示定时轮询真实 BLE 状态。
- 录音笔私有 opus 解码已修好（`OpusBridge.decodeToWav`，真机验证过能解出干净人声）。
- start(0x03)/end(0x04) 命令真机确认能正常发出。

## 7. ✅ 待办清单（按优先级）
1. **【最急】把用户这两支笔恢复出厂、让官方 App 重新可用**：写回出厂值 `setPowerOnRecord(true)`、`setNoiseSwitch(true)`、`setIdleShutdown(30)`，segmentDuration 试写回**非 0**值（如 30 或 480，逐个试，配合官方 App 验证是否恢复）。每写一项后关机重开 + 用官方 App 验。**这是用户当下最在意的**（他只有两支笔）。
2. **路 A：找到关闭"连接态自动声控录音"的正确办法**：① 在官方「灵犀」App 设置里找「声控录音/录音模式/连续录音」开关，关掉看笔是否停止自动录；② 若官方 App 关得掉而我们 `setNoiseSwitch(false)` 关不掉 → 抓官方 App 的 BLE 命令(或问厂商)拿到正确命令，让我们 App 连上也发。
3. **路 B（若 A 走不通）：改 App 适配"连续录音"**：笔反正一直录连续小段(时间戳连续可无缝拼)，把"结束"改成：把用户「开始→结束」时间窗内笔录的所有小段 download+拼接+解码+上传成一整段。较大改造，但能彻底适配。
4. 保持第 6 节 App 侧门控逻辑不要推翻。
5. 厂商问题清单（转 AIREC）：连接后如何进"仅命令控制录音"模式不自动录？`setNoiseSwitch` 为何回读不变？segmentDuration=0 的真实语义？15 秒长按硬复位会不会损坏笔、正确恢复步骤？

## 8. 关键文件 / 操作
- 原生：`android_app/app/src/main/java/com/airec/bledemo/PenController.java`（录音笔控制核心+`ensurePenConfigured`）、`ConsultantActivity.java`（WebView壳+桥+状态机）、`ScanActivity.java`（扫描连接）、`MainActivity.java`（demo 设置页，有分段/声控/格式化 UI，可临时设为 launcher 用来手动改设置/恢复）、`OpusBridge.java`（解码）。
- 桥：`WebAppBridge.java`。网页：`web_v2/templates/consultant.html`（线上，改后 scp 到服务器 `/opt/gp-system/web_v2/templates/` + `sudo systemctl restart gongpai`，服务器 `ubuntu@110.40.170.227`）。
- 构建装机：`cd android_app && export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew assembleDebug && ~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk`。当前手机装的是 **debug 包**（release 用 `RELEASE_SIGNING.md` 的 keystore）。
- 看日志：`adb logcat`，关键 tag `PenController`、`AIREC_BLE`(TX/RX 命令)、`AIREC_OPUS`(解码)。vivo 装机需手机点「继续安装」。
- 临时改设置/恢复笔最直接：把 `MainActivity` 设为 launcher（manifest 里把 LAUNCHER intent-filter 从 ConsultantActivity 挪到 MainActivity，exported=true），装上后用它的「设备设置」UI 手动改分段/声控/格式化；用完改回。
- ⚠️ 改笔设置必须**真机 + 实笔**验证（子 agent 能用 adb，但需要用户配合连笔、关机重开、用官方 App 验）。

## 9. git
分支 `online-0527`，远端 github.com/ginlater/gp-system（私有）。本地有 1 个 commit 未推（github 当时网络不通），网络好了 `git push origin online-0527`。

---

## 10. SDK 深挖结论（子 agent）

> 工具：`unzip` 拆 `blesdk-release.aar` → `classes.jar`（仅 22KB，**8 个类**）→ CFR 0.152 反编译 + `javap -c -p -v` 看字节码。产物在 `/tmp/blesdk_decompiled/`（src/ 是 CFR 输出，AIRECBleManager.javap.txt 是字节码）。SDK 经 R8 full 混淆，但全部方法名/字符串都保留可读。**整个 SDK 的全部类**：`com.airec.blesdk.{AIRECBleManager, AIRECBleCallback, AIRECBleDevice, AIRECBleFile, AIRECBleManager$AudioStreamListener}` + 混淆内部类 `a.a`(ScanCallback) / `a.b`(GattCallback+RX解析) / `a.c`(蓝牙开关广播)。**没有任何其它隐藏类或 native 库**。

### 10.0 ★最关键结论（先看这条）
**反编译穷尽了整个 SDK 的常量池和全部命令分支：SDK 里【根本没有】任何"录音模式 / record mode / VOX / 声控开关之外的连续录音 / continuous / auto-record"的 set 方法或命令码。** 能影响"连上是否自动录"的设置项，SDK 暴露的就只有那 7 个 setter（noise/led/usb/powerOnRecord/segmentDuration/idleShutdown/micGain），**我们一个都没漏**。常量池里搜 `mode/vox/voice/continuous/auto/trigger/sens/detect` 全部无命中（只有 `Voice`/`Recorder` 是设备名白名单字符串）。

→ **结论：靠这个 SDK 现有 API，没有"一条命令让笔进入仅命令控制、连上不自动录"的开关。** 连接态自动声控录音要么靠 `setNoiseSwitch(false)`（但本机固件不接受，见 10.3），要么得抓官方 App 的 BLE 报文 / 问厂商拿私有命令。**路 B（适配连续录音）是当前 SDK 能力内唯一可落地的方向。**

### 10.1 完整 TX 命令码表（手机→笔）
帧格式 `55 AA <len> <cmd> <data...>`。`len` = `data.length + 1`（即"cmd+data"的字节数；构造函数 `a(int cmd, byte[] data, int lenOverride)`：lenOverride<0 时自动算）。`55=0x55、AA=0xAA(-86)`。

| 方法 | cmd(hex) | data | 帧示例 |
|---|---|---|---|
| `fetchAllDeviceInfo` 第1发 | `0x01` | 无 | `55 AA 01 01` |
| `syncTime` | `0x02` | "yyyyMMddHHmmss" ASCII | `55 AA 0F 02 <14B>` |
| `startRecord` 第1发 | `0x03` | 无 | `55 AA 01 03` |
| `startRecord` 第2发(+100ms) | `0x21` | `"R"`(0x52) | `55 AA 02 21 52` |
| `endRecord` | `0x04` | 无 | `55 AA 01 04` |
| `fetchFileList` | `0x05` | 无 | `55 AA 01 05` |
| `sendSyncFileCmd`(下载前选文件) | `0x07` | 文件名 18B(右补0) | `55 AA 13 07 <18B>` |
| `downloadFile`/`cancelDownload` | `0x08` | 无 | `55 AA 01 08` |
| `deleteFile` | `0x0A` | 文件名 ASCII | `55 AA <len> 0A <name>` |
| `fetchDeviceInfo` 含 | `0x0B` | 无 | 电池/电量 |
| `fetchDeviceInfo` 含 | `0x0E` | 无 | 存储信息 |
| `fetchFirmwareVersion` | `0x12` | 无 | `55 AA 01 12` |
| `pauseRecord`==`resumeRecord` | `0x10` | 无 | `55 AA 01 10`（**同一命令，toggle**） |
| `setLedSwitch(b)` | `0x18` | 1B 0/1 | `55 AA 02 18 0X` |
| `setNoiseSwitch(b)` | `0x19` | 1B 0/1 | `55 AA 02 19 0X` |
| `setSegmentDuration(n)` | `0x22` | **2B 大端** | `55 AA 03 22 HH LL` |
| `setUsbSwitch(b)` | `0x24` | 1B 0/1 | `55 AA 02 24 0X` |
| `fetchInitParam` | `0x26` | 无 | `55 AA 01 26` |
| `fetchAllDeviceInfo` 末发 | `0x26` | 无 | 同上(读全参数) |
| `setMicGain(n)` | `0x2A` | 1B(夹 1..7) | `55 AA 02 2A 0X` |
| `setPowerOnRecord(b)` | `0x2E` | 1B 0/1 | `55 AA 02 2E 0X` |
| `setIdleShutdown(n)` | `0x39` | **4B 大端** | `55 AA 05 39 BB BB BB BB` |
| `formatDisk` | `0x3C` | 无 | `55 AA 01 3C` |
| 服务发现后握手 | — | — | 直接发 `55 AA 01 01`（硬编码字节，"hello"） |

> 上面 cmd 是 SDK 源码里的十进制实参换算来的：`a(24..)`=0x18、`a(25..)`=0x19、`a(34..)`=0x22、`a(36..)`=0x24、`a(38..)`=0x26、`a(42..)`=0x2A、`a(46..)`=0x2E、`a(57..)`=0x39、`a(60..)`=0x3C、`a(33,"R")`=0x21、`a(7..)`=0x07、`a(16..)`=0x10。**与第 5 节既有映射完全一致，无新命令。**

### 10.2 RX 命令码表（笔→手机，`c(byte[])` 解析，帧头要求 `AA 55`）
注意 RX 帧头是 **`AA 55`**（与 TX 的 `55 AA` 相反！见 `a.b.onCharacteristicChanged`：`data[0]==0xAA && data[1]==0x55` 才当控制帧走 `c()`，否则当音频流走 `b()`）。data 同样从第 4 字节起，`len=data[2]-1`。

| cmd(hex) | 含义 | 备注 |
|---|---|---|
| `0x01` | MAC/SN | 去控制字符后存 `J`(getMacAddress)，触发 onDeviceInfoUpdated |
| `0x02` | syncTime 回执 | 触发 onConnected（连接握手锚点） |
| `0x03` | 录音中(true) | `device.h=true` + onRecordStateChanged(true) |
| `0x04` | 录音停止(false) | `device.h=false` + onRecordStateChanged(false) |
| `0x05`/`0x06`/`0xFE` | 文件列表项/列表结束 | 0x06/0xFE 提交列表 onFileListUpdated；0x05 单条 name+size(4B大端) |
| `0x07` | SyncFile 确认 | 读 actualSize(idx14,4B大端) 校正文件大小，准备收音频 |
| `0x0C` | 存储已用 KB | device.e |
| `0x0D` | 存储总量 KB | device.f |
| `0x0E` | 电池电量% | device.d |
| `0x0F` | RecordStatus 查询回执 | `data[0]==1`=录音中 → onRecordStatusQueried |
| `0x10` | 暂停状态回执 | onRecordPaused |
| `0x12` | 固件版本字符串 | onFirmwareVersionReceived |
| `0x26` | **InitParam 全参数**（详见 10.5） | onInitParamUpdated |
| `0x36` | 充电状态 | `data[0]==1`=充电中，device.i |
| `0x3B` | 录音时长(2B/可变大端) | onRecordDurationUpdated |
| `0x3C` | 格式化成功 | onFormatResult(true) |
| `0xF0/0xF1/0xF2` | 格式化失败(找不到卡/无法格式化/卡损坏) | onFormatResult(false,…) |
| `0xFC` | 文件传输失败 | onFileDownloadFailed |
| `0xFD` | 设备传输失败/录音中无法下载 | 触发下载失败（我们的 retry 就是吃这个） |
| 0x18/0x19/0x22/0x24/0x2A/0x2E/0x39 | **set 类回执** | **只打印 `Setting confirmed CMD=0xXX` 然后 500ms 后自动 `fetchInitParam` 回读**，不更新任何字段 |

> 重点：**set 命令的回执只是"收到了"，笔不在回执里回报新值**；SDK 是发完 set 后延时 500ms 自己再发 `0x26` 去重新拉全参数。所以 get* 读到的值完全取决于笔在 `0x26` 里报什么。

### 10.3 `setNoiseSwitch` 失效原因（回读不变的真相）
- `setNoiseSwitch(false)` 发的帧是对的：`55 AA 02 19 00`（字节码核实）。
- `getNoiseSwitch()` 返回的是 **本地字段 `C`**（`public boolean getNoiseSwitch(){ return this.C; }`），`C` **只在解析 `0x26` InitParam 时被赋值**（`C = data[0] != 0`），别处不写。
- 链路是：set(0x19) → 笔回 set 回执 → SDK 500ms 后自动 `fetchInitParam(0x26)` → 笔回 `0x26` → 用 `data[0]` 重写 `C`。
- **所以"回读仍 true"= 笔在 `0x26` 里仍然报 `data[0]=1`**。即 **不是本地缓存问题，也不是 SDK 没发，而是笔的固件【拒绝了】这次声控关闭写入（或这支笔/这版固件不支持蓝牙改声控开关，又或 `0x19` 在该固件里语义不同）**。SDK 侧逻辑完全正确，问题在设备固件。
- 佐证：`setPowerOnRecord(false)`(0x2E) 能被接受（powerOnRec 回读变 false），但 `setNoiseSwitch`(0x19) 不被接受 → 同一套 set→回读机制，唯独声控这项笔不收。**这强烈指向"该命令需抓官方 App 报文比对"或"固件 bug/需厂商专用命令"。**

### 10.4 `setSegmentDuration` 编码与 `=0` 语义
- 编码：**2 字节大端**。`setSegmentDuration(n)`：`data[0]=(n>>8)&0xFF; data[1]=n&0xFF`，cmd `0x22`。如 `setSegmentDuration(30)`→`55 AA 03 22 00 1E`；`(480)`→`55 AA 03 22 01 E0`；`(0)`→`55 AA 03 22 00 00`。
- 回读：`0x26` 里 `G = bigEndian16(data[3..4])`（**注意取的是 data[3]、data[4]，跳过了 data[2]**）。单位是**秒**。
- `=0` 语义：**协议层只是数值 0**，SDK 不赋予特殊含义（demo UI 把 0 标成"不分段"纯属 UI label）。**固件如何解释 0 由设备决定**——第 5 节已怀疑本机固件把 `segDur=0` 当"最短段/连续小段"而非"不分段"，这与"每~3 秒起停一小段"的现象吻合。**建议把它写成一个明确的大值（如 480）测试是否改变自动分段行为**（见 10.6 恢复代码已含此项）。

### 10.5 `0x26` InitParam 回包【全字段】（字节码逐字节解析）
要求 `data.length >= 9`。**布局随 data 长度有两种变体**（SDK 用 `length==9||==10` vs `>10` 分流）：

固定头部（两变体相同）：
- `data[0]` → **noiseSwitch**(C)  ← 声控开关
- `data[1]` → **ledSwitch**(B)
- `data[3..4]` 2B大端 → **segmentDuration**(G) 秒 （**注意跳过 data[2]，data[2] 是什么 SDK 没解析，可能是保留/校验位**）

然后分支：
- **若 len==9 或 10**：`data[5]`(1B) → **idleShutdown**(H) 分钟；游标 idx=6
- **若 len>10**：`data[5..8]`(4B大端) → **idleShutdown**(H) 分钟，上限夹 525600；游标 idx=9

合流后（idx 记为 p）：
- `data[p]` → **usbSwitch**(D)
- `data[p+1]` → **micGain**(I)（夹 1..7）
- `data[p+2]` → **powerOnRecord**(E)
- `data[p+3]`（若存在，`p+3 < length`）→ **diskFormatSupport**(F)

→ **笔回报的全部可读字段就是这 8 个：noise / led / segDur / idle / usb / micGain / powerOnRecord / diskFormatSupport。** `data[2]` 这一字节 SDK 不解析（未知）。**没有任何"录音模式/声控灵敏度/连续录音标志"字段被回报**——也就是说，即便笔固件内部有这种状态，这版 SDK 也读不到、写不了。这进一步印证 10.0 的结论。

### 10.6 `restorePenToFactory()` 恢复出厂代码（待审，未改 ensurePenConfigured，未 build）
用 SDK 现有 setter 把疑似出厂值写回。**注意**：① SDK 无原子"恢复出厂"命令，只能逐项写；② `powerOnRecord` 改值要**关机重开**才生效；③ 每条 set 后 SDK 会自动 500ms 拉一次 `0x26`，连发多条会互相打断回读，所以这里**用 Handler 间隔 ~700ms 串行下发**，给每条留出回执+回读窗口；④ `setNoiseSwitch(true)` 笔很可能仍不接受（见 10.3），但写回出厂方向（true）至少不会更糟。

把下面方法**追加**进 `PenController`（不改动 `ensurePenConfigured`）：

```java
/**
 * 【恢复出厂尝试】把笔的可写设置逐项写回疑似出厂值，用于"我们之前写坏了、官方 App 也救不回"的急救。
 * 出厂方向：noiseSwitch=true、ledSwitch=true、usbSwitch=false、micGain=3、
 *           segmentDuration=segDur(默认传 480 当“尽量长/不频繁分段”；传 0 = 写回固件原始 0 值)、
 *           idleShutdown=30(分钟)、powerOnRecord=true。
 * 说明：
 *  - SDK 无原子恢复出厂命令，这里逐条 set，间隔 ~700ms 串行下发，避免互相打断（每条 set 后 SDK 会自动 0x26 回读）。
 *  - powerOnRecord 改值需【关机重开】才生效；setNoiseSwitch 这支固件可能仍不接受（见交接文档 10.3）。
 *  - 调用前需已连接。下发完成后建议 fetchInitParam 看日志 "InitParam:" 确认，再关机重开 + 用官方 App 验。
 *
 * @param segDur 分段秒数。想"尽量不频繁分段"传 480；想写回固件原始默认传 0。
 */
public void restorePenToFactory(final int segDur) {
    try {
        final AIRECBleManager mgr = AIRECBleManager.getInstance();
        if (!mgr.isConnected()) {
            Log.w(TAG, "restorePenToFactory: 未连接，忽略");
            if (listener != null) listener.onPenNeedConnect();
            return;
        }
        Log.d(TAG, "restorePenToFactory(改前): noise=" + mgr.getNoiseSwitch()
                + " led=" + mgr.getLedSwitch()
                + " usb=" + mgr.getUsbSwitch()
                + " mic=" + mgr.getMicGain()
                + " segDur=" + mgr.getSegmentDuration()
                + " idle=" + mgr.getIdleShutdown()
                + " powerOnRec=" + mgr.getPowerOnRecord());

        // 串行下发，每步 ~700ms，给 set 回执 + SDK 自动 0x26 回读留窗口
        int step = 0; final int GAP = 700;
        main.postDelayed(() -> { mgr.setLedSwitch(true);        Log.d(TAG, "restore: ledSwitch=true"); },        GAP * (step) );
        main.postDelayed(() -> { mgr.setUsbSwitch(false);       Log.d(TAG, "restore: usbSwitch=false"); },       GAP * (1) );
        main.postDelayed(() -> { mgr.setMicGain(3);             Log.d(TAG, "restore: micGain=3"); },             GAP * (2) );
        main.postDelayed(() -> { mgr.setSegmentDuration(segDur);Log.d(TAG, "restore: segmentDuration=" + segDur); }, GAP * (3) );
        main.postDelayed(() -> { mgr.setIdleShutdown(30);       Log.d(TAG, "restore: idleShutdown=30"); },       GAP * (4) );
        main.postDelayed(() -> { mgr.setPowerOnRecord(true);    Log.d(TAG, "restore: powerOnRecord=true(需关机重开生效)"); }, GAP * (5) );
        main.postDelayed(() -> { mgr.setNoiseSwitch(true);      Log.d(TAG, "restore: noiseSwitch=true(固件可能不接受)"); }, GAP * (6) );
        // 末尾回读确认（看 logcat 的 "InitParam:" 行）
        main.postDelayed(() -> {
            try { mgr.fetchInitParam(); } catch (Exception ignored) {}
            Log.d(TAG, "restorePenToFactory: 已全部下发，请看 InitParam 日志，并关机重开后用官方 App 验证");
        }, GAP * 7);
    } catch (Exception e) {
        Log.e(TAG, "restorePenToFactory failed", e);
    }
}

/** 无参重载：segDur 默认 480（尽量减少自动分段）。想写回固件原始 0 用 restorePenToFactory(0)。 */
public void restorePenToFactory() {
    restorePenToFactory(480);
}
```

接入方式（任选其一，未替你改）：
- 临时调试：在 `MainActivity`（demo 设置页）加一个按钮 `onClick → penController.restorePenToFactory(480)`（或 `(0)`）。注意 `MainActivity` 当前直接用 `AIRECBleManager`，也可直接在按钮里照抄上面逻辑、把 `main`/`TAG`/`listener` 换成 Activity 本地的 Handler/常量。
- 或在接诊页临时挂一个隐藏入口调用 `penController.restorePenToFactory()`。

**验证步骤**（每改一项的因果要靠 logcat + 官方 App）：装好后点恢复按钮 → 看 `adb logcat -s AIREC_BLE PenController` 里每条 `Setting confirmed CMD=0x..` 和最后的 `InitParam: ...` → **关机重开笔** → 用官方「灵犀」App 连，看是否恢复正常 / 是否仍自动录。若 `InitParam` 里 `noise=` 仍是 `true` 之外关不掉自动录，基本可断定是固件层问题，须抓官方 App 报文或找厂商。

### 10.7 给路 B（适配连续录音）的有用事实
若走"把用户时间窗内的小段全下载拼接"：`0x05` 文件列表每条含 name + size(4B大端)，文件名通常带时间戳（`pickRecorded` 已按名倒序取最新）。下载用 `0x07`(选文件,文件名18B) + `0x08`(拉流)，音频是私有 `5B50/4B41` 同步头 + SILK-WB opus（`OpusBridge.decodeToWav` 已能解）。所以"列时间窗内多文件→逐个 download+decode→按文件名时序拼 wav→上传"在现有 SDK 能力内完全可做，无需任何新命令。

---

## 11. 结论更新（2026-06-04 真机验证后）
1. **已彻底删除"初始化写设置"**：`PenController.ensurePenConfigured()` 现在**只读取打日志、不写任何设置**。App 连上笔后保持笔 100% 出厂状态，与官方 App 一致。原因：写声控关闭笔固件不收（白写）、写了还有风险。
2. **"恢复出厂"按钮已上线**：`MainActivity.doRestoreFactory()` + 设置页橙色按钮"🛠 恢复出厂设置（救笔）"。`MainActivity` 已临时 `exported=true`，可用 `adb shell am start -n com.aibeautyfulwomen.gongpai/com.airec.bledemo.MainActivity` 直接打开。
3. **`segmentDuration=0` 是出厂值且正确**：含义=不分段（整段不切）。关键证据：用户那支笔出厂即 0，且用户确认"最开始官方 App 是好的"——官方 App 就是在 segDur=0 下正常录的，**所以 0 不是"录1s就停"的原因**。（推翻第 5 节/10.4 里"0 被当最短段"的猜测。）
4. **"录1s就停又重录"= 声控(VOX)**，与 segmentDuration 无关。
5. **真机验证：用户执行"恢复出厂"（7 项全写回出厂值）后笔仍自动录** → 决定性证据：**自动录音是笔固件层的声控行为，不是我们写设置写坏的**（若是我们写坏的，恢复出厂就该好）。即笔从未被我们"软件锁死"，一直是固件声控行为。
6. **方向（用户已选路 A）**：给厂商的问题清单见 `给厂商的问题清单.md`，可直接转发 AIREC。路 B（App 拼接连续小段）作为兜底，未开工。

---

## 12. ★根因确认（2026-06-04，真机修复成功）
**"录1s就停 / 连蓝牙断开也录不了" 的真正原因 = 之前写了 `setSegmentDuration(0)`。**
- 本机固件把 segDur=0 理解成"每0秒切一段"（而非"不分段"），导致笔录一下就立刻切断→停→再录→再切，表现为"录1s就停又重录"。
- segmentDuration 是存在笔里的**设备设置**，所以**不连蓝牙、物理键录音也一样坏**（这解释了"断蓝牙也不行"）。
- **修复**：MainActivity 的"恢复出厂"按钮把 `setSegmentDuration` 从 0 改写为 **480**（最长，约8小时一段），关机重开后物理键录音恢复正常。✅ 真机验证通过。
- **推翻第11节第3条的判断**：segDur=0 不是出厂值、不是"正确"的；是我用不可靠的回读误判成出厂值，实际是我写 0 把笔搞坏的。出厂真值非0。
- **教训**：① 绝不要给这支笔写 segmentDuration=0；要"整段不分段"的效果就写大值(480)。② 设备级设置写错会让笔脱机也坏，且 readback 不可信，改设置务必真机验证。
- **现状**：`PenController.ensurePenConfigured()` 已不写任何设置；正式录音用的分段值若需要，应走非0大值。两支笔都需用"恢复出厂"按钮(segDur=480)救。

---

## 13. ★KA 格式解码破解成功（2026-06-04）
**现象**：录音上传后浏览器播放是噪音/乱码。**根因**：笔这次录的是 KA 私有格式(同步头 `4B 41`)，而 `OpusBridge.decodeToWav` 的 `extractFrames` 只对 ATW(`5B 50`,定长82字节记录)用"等长stride"分帧；KA 是变长opus+补零，等长法解成噪音。**跟 wav/webm 格式无关**(wav 浏览器能正常播)，是 KA 没正确分帧。

**KA 帧结构**(离线用 libopus 逐字节逆向 + 真机文件 588帧验证)：
- 固定 **80 字节块，每块 = 1 个 Opus 帧**(config9 = SILK-WB 20ms，TOC 恒 0x48，每帧 320 样本)。
- **短帧块**(头 `4B 41`，占多数)：`byte[2]=padlen`(尾零个数，连续0~71)，opus payload = `block[3 : 80-padlen]`(长度 77-padlen)，**TOC 0x48 被剥离了，解码前要补回**：`[0x48]+payload`。恒等式 `padlen + payload非零长 == 77`。静音帧形如 `4B 41 47 07 C9 ..`(padlen=71,payload仅6字节)。
- **长帧块**(头非 `4B41`，约 50/588)：payload 占满 80 字节、TOC 未剥离，**整 80 字节即完整 opus 包**，原样送解码器。

**验证(客观)**：588/588 帧全 `opus_decode>0`、全 320 样本、时长 11.76s、247说话帧+239静音帧的语音起伏、Crest 7.17/动态范围 96dB(噪音会接近1~2)。decoded_clean.wav 已离线生成确认是人声。
**修复**：`OpusBridge.extractFrames` 加 KA 专用分支(见代码)，ATW 分支不动。已 build+install。
**离线分析存档**：`/tmp/ka_analysis/`(rec.ka 真机样本、SOLUTION.md、decoded_clean.wav、harness.py)。
**遗留**：之前已上传到服务器的那几段是 KA 错解的噪音 wav(作废)；笔里原始 `.ka` 还在(`/data/data/<pkg>/files/ble_audio/`)。**为什么这支笔录 KA 而非 ATW 未知**(可能型号/固件/某设置决定)——若以后想统一回 ATW 可作为备选方向；但现在 KA 已能正确解码，不影响使用。
