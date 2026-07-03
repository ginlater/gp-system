# 接力文档：录完一段显示「这1段保存中」卡住 — 调试现场 + 待决事项

> 写给新会话的 Claude Code。日期 2026-06-12。上一会话在真机(vivo V2445A, adb 已连, 设备号 10AG5S2KNM00BCK)调试中断。
> 背景知识先读 memory：`MEMORY.md` → `android-pen-recording`、`diag-upload-triage`、`app-release-and-force-update`。

## 一、用户刚问的问题（没答完）

用户在测试 vivo 上录了 6 分钟（笔录音），录完界面一直显示「这1段保存中」。
他问：**是不是不能一边录音、一边上传上一段？**

### 已从 penlog 查明的事实（答案的核心）

penlog 路径：`/sdcard/Android/data/com.aibeautyfulwomen.gongpai/files/stream_ops/penlog.txt`（adb shell cat 可读）

```
36901s  开始录音 file=20260612162522  (16:25:22, 用户点的)
37118s  ⚠️ 录音中出现「SDK BLE Handler 切后台线程 ok」= PenController 重新初始化
        （= ConsultantActivity 重建/进程重启，录音中实时流断了缺口）
37310s  录音结束 (≈6分钟段)
37314s  4秒后用户又开了新一段 file=20260612163215，此时 q=1（6分钟段进了补传队列）
```

### 答案逻辑（给用户讲清楚）

1. **实时流直传**（流完整时）可以和新录音并行——不冲突，几秒就传完。
2. 但这段 6 分钟**流断过缺口**（37118s 那次重启），按设计回落到「**从笔下载补传**」。
3. **下载和录音不能同时**：笔固件在录音时拒绝文件传输（回 0xFD，反编译坐实），`kickWorker` 也主动等笔空闲（`PenController` 里 `if (!isLocal && (penRecording || sessionActive || appStartPending)) return;`）。
4. 所以「保存中」会一直等到**当前录音停止、笔空闲**才开始下载上传。用户停止录音后笔放手机旁等 1-2 分钟即可。
5. 附带好处：固件怪癖「残缺流文件要等下一段录音开始才提交进笔的文件列表」——他开新段正好让 6 分钟段的文件被提交，停录后能找到。

### 未决的疑点（值得新会话追查）

**为什么 37118s 录音中 PenController 会重新初始化？**
- `PenController` 是在 `ConsultantActivity.onCreate` 里 new 的（`ConsultantActivity.java:112` 附近）——Activity 重建 = 新 PenController = 流会话丢。
- penlog 显示 36484s/36792s/37118s 多次「SDK BLE Handler 切后台线程 ok」= 短时间多次实例化。
- 可能原因：用户反复进出 App / vivo 杀 Activity（进程没死但 Activity 被回收）/ 配置变化。
- **架构改进方向**：PenController/录音会话提升到 Application 级单例，Activity 重建不丢流。这能让"录音中切出去再回来"不再把好流变残缺流 → 大幅减少掉入脆弱的下载补传路。改动有体量，需评估。

## 二、手头未发布的工作：v23/2.1.12（已装测试机，未发版）

**生产线上 = v22/2.1.11**（webapp.py `APP_LATEST/MIN=22`）。本地 `android_app/` 已是 v23 并装在测试 vivo 上，**git 未提交、未发版**。改动内容：

1. **连接级前台服务保活**（核心，治"后台被杀→蓝牙断"）：
   - `recording/PenKeepAliveService.java` 重写成两段式持有：
     - `CONN`（笔连着就挂 FGS，不持唤醒锁，省电）→ `connOn/connOff`
     - `REC`（录音/补传时 FGS+PARTIAL_WAKE_LOCK）→ `recOn/recOff`
   - `PenController.java`：`connKeepAlive(bool)` 连上(markPenResponded)挂、断开(心跳失联/onDisconnected)去抖90s撤（`CONN_KEEPALIVE_LINGER_MS=90000`）；原录音级保活改调 recOn/recOff。
   - 通知文案遵守产品红线（无"录音"二字）："美丽陪伴 · 保持连接中/陪伴进行中"。
2. **电池白名单弹框全员化**：`ConsultantActivity.onCreate` 末尾 `ui.postDelayed(this::maybeAskBatteryExemption, 8000)`（原来只有手机麦路径才弹）。
3. 真机已验证：锁屏后进程活着 + `isForeground=true`（`PEN_KA_CONN_ON`）+ penlog 出现「保活·连接级↑」。测试机已在电池白名单所以不弹框（正常）。

**待用户决策**：是否发版 v23 强更全网。发版流程见 memory `app-release-and-force-update`：
bump webapp.py 版本常量 22→23 + 更新说明 → `tools/deploy_app.sh --with-backend`。
（v23 还应顺手把 git 未提交的 android_app 改动 commit：PenKeepAliveService.java + PenController.java + ConsultantActivity.java + build.gradle。）

## 三、近期已上线的相关修复（背景，勿重做）

- v14~v16：保活去抖12s、补传僵尸2h自愈(按入队时刻)、下载退避跨重启持久化、开机自录强提醒、64位通用包(自编arm64 libopus, jniLibs)、手机麦"2秒不见了"修复(bridgeGetState 按 activeSource 区分)。
- v17~v22（协作者发的）：下载完整性校验(v22修了误判残缺的判据)、手机录音时间段后移修复等。
- 服务端：同步列表只显示未传+分页、审批删除补墓碑、/consultant no-cache、下载文件名/URL带版本号(防旧包顶包强更死循环)。
- SDK 反编译结论（重要）：下载协议**无断点续传**（命令7只带文件名+4字节恒0的疑似offset字段；接收端 FileOutputStream 覆盖模式从0写）；"设备传输失败"=笔回0xFD且SDK不知道笔在录；10s无数据超时。厂家口头说"支持续传"，已建议向厂家要带续传的SDK或协议文档。厂家新发的 demo（~/Downloads/AIRECBleDemo_new）里 blesdk-release.aar 与我们在用的 **md5 相同**＝没有新SDK。
- "笔硬件坏"结论已被推翻：刘爽同一支笔曾直传成功35分钟/67MB。真实图景=旧手机/真实环境流易断→回落下载→下载更脆。

## 四、常用命令速查

```bash
ADB=~/Library/Android/sdk/platform-tools/adb
# penlog / 直传结果
$ADB shell tail -50 /sdcard/Android/data/com.aibeautyfulwomen.gongpai/files/stream_ops/penlog.txt
$ADB shell tail -5  /sdcard/Android/data/com.aibeautyfulwomen.gongpai/files/stream_ops/last_result.txt
# 前台服务/进程
$ADB shell pidof com.aibeautyfulwomen.gongpai
$ADB shell dumpsys activity services com.aibeautyfulwomen.gongpai | grep -E "isForeground|PEN_KA"
# 生产 DB（顾问卡住的占位等）
ssh ubuntu@110.40.170.227 'sqlite3 /opt/gp-system/recordings.db "SELECT id,uploader_user_id,recorded_at,upload_status FROM recordings WHERE upload_status!=\"done\";"'
# 编译/装包
cd android_app && ./gradlew :app:assembleRelease -q && $ADB install -r app/build/outputs/apk/release/app-release.apk
```
