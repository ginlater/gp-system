# 美丽陪伴 · android_app_v2 — 构建唯一事实源 (SPEC)

> 这份文档是 v2 原生重写的**唯一事实源**。所有子 agent 必须先读它再动手。
> 视觉/交互的像素级事实源是同目录 `mockups/warm_2.html`（已定稿的"暖玉柔光"版，可点原型）。

## 0. 一句话目标
把现有"WebView 套 Flask 网页"的顾问端，重写成**纯原生 Android（Jetpack Compose + Material 3）**的高端 app「美丽陪伴」，
**保留全部功能**、**复用现成的录音/BLE 引擎**、**对接现成后端 JSON API**。日后本目录将单独抽成一个 GitHub 仓库，因此必须**自包含、不依赖父仓库文件**。

## 1. 架构
- **UI 层**：Jetpack Compose + Material 3，单 Activity（MainActivity）+ Navigation-Compose。底部 5 槽导航。
- **引擎层（复用，不重写）**：从 `../android_app` 拷过来的录音/BLE Java 代码（见 §5）。这是踩了无数坑才稳定的部分（看门狗/断线宽限/防截断/v7a-only opus/补传重试），**严禁推倒重来**。
- **数据层**：OkHttp + Retrofit + Moshi(或 kotlinx-serialization) 调现成后端 `https://gp.aibeautyfulwomen.com` 的 `/api/consultant/...`；Cookie 会话鉴权（原生 PersistentCookieJar，登录 POST `/login`）。
- **包名**：module namespace 沿用 `com.airec.bledemo`（让引擎文件零改动复用）；新 Compose 代码放 `com.airec.bledemo.ui.*` / `.data.*` / `.nav` / `.designsystem`。`applicationId = com.aibeautyfulwomen.gongpai`，debug 加 `.v2` 后缀以便与线上老 app 在测试机共存。

## 2. 产品红线（顾客可见处，违者砸品牌）
- **绝不出现"录音/录制/recording"**。统一"陪伴"系词汇：美丽陪伴 / 点击开启陪伴 / 陪伴进行中 / 结束陪伴 / 陪伴师（顾问）/ 看看今天的陪伴 / 回顾每一次陪伴。
- 蓝牙设备叫 **「陪伴笔」**（不是"录音笔"）。同步动作叫「从陪伴笔同步」。
- 报告里**不出现"调用 1/2/3"**这类内部实现词。
- **高级、不廉价**：定制统一线性图标体系（24 栅格、1.6–1.8 描边、圆端点），禁 emoji/老拟物/粗劣 clipart。精致排版、留白、克制配色。
- 详见长期记忆 `companion-wording-no-recording`。

## 3. 设计系统「暖玉柔光」(Warm Jade Glow)
> 精确 token / 组件 / 图标以 `mockups/warm_2.html` 的 `:root` 与各组件 class 为准，原样翻译成 Compose。
- 关键色：底 暖灰米 `#F8F3ED`、surface `#FFFCF8`、主色 陶土 `#BE7459`(deep 更深)、辅色 雾感鼠尾草 `#93A38E`、文字 深摩卡 `#3D3833`、蜜色点缀。圆角 18–22px、柔光卡片、克制阴影。标题可用系统衬线提升高级感。
- 已定的整体亮度（比初版调亮约 10–15%）、不要再调暗。
- **底部导航 = 4 tab + 中间大圆 FAB**：`陪伴(home) / 接诊(reception) / [开启陪伴 FAB] / 待整理(pending) / 档案(profile-search)`。左右滑可在 tab 间切换（已是定稿交互）。
- 图标体系：warm_2 里已有一套自建 `<symbol id="ic-...">` 雪碧图（陪伴=并蒂双瓣花蕊、接诊=日历+心、待整理=层叠收纳盒、档案=人像、陪伴笔=ic-pen、同步=ic-sync 等）。Compose 里转成 `ImageVector`/矢量资源，保持同一视觉规范。

## 4. 信息架构 / 屏幕清单（行为以 warm_2.html 为准）
1. **登录** — POST `/login`，存 Cookie。
2. **陪伴首页 (home)** — 大「点击开启陪伴」按钮(陪伴进行中=呼吸态)；陪伴来源 手机/陪伴笔切换；**陪伴笔状态卡**(已连接·电量) 内含「从陪伴笔同步」入口；待传/失败 badge。
3. **待整理 (pending)** — 未绑定的陪伴片段列表(时间/时长/说话人数/试听**全程**无60s上限)；顶部「刷新」+「从陪伴笔同步」；进入**绑定顾客**流程。空态/进行/上传/失败态。
4. **绑定顾客** — 搜索已有顾客 / 新增顾客 / 补登当天接诊。
5. **今日接诊 (reception)** — 今日接诊列表(每 20s 刷新提示)。
6. **会话预览 + 开始分析** — 勾选片段→预览→确认并开始分析；取消分析/锁定。
7. **分析报告 (report)** — 见 §6。
8. **提醒 (reminders)**。
9. **档案 (profile-search)** — 顾客"美丽档案"搜索/详情。
10. **陪伴笔机身记录 sheet** — 「从陪伴笔同步」唤起：勾选机身片段→导入→落入待整理（首页卡 + 待整理顶部两个入口共用）。
11. **设置 / 强制更新** — 版本检查 `/api/app/version`，低于 MIN 强制升级。
12. **陪伴笔扫描/连接** — 暂复用现有 ScanActivity 逻辑（见 §5），UI 后续再 Compose 化。

## 5. 复用引擎清单（从 ../android_app/app/src/main/java/com/airec/bledemo/ 拷贝，包名不变）
**直接复用（核心逻辑，UI 无关）**：
- `App.java`、`PenController.java`(1510行 BLE B模式录制+下载+补传)、`OpusBridge.java`、`OpusToWavConverter.java`、`AudioConverter.java`、`ATWOpusConverter.java`、`FileDownloadManager.java`
- `net/Uploader.java`(上传，含 pen_file 去重)
- `recording/PhoneMicService.java`、`recording/PenKeepAliveService.java`、`recording/RecordingBus.java`(前台保活/状态总线)
- `adapter/*`、`ScanActivity.java`(+`activity_scan.xml`/`item_device.xml`)：扫描连接，暂以 View 形式复用，从 Compose 启动。

**不复用（被 Compose 取代）**：`MainActivity`、`ConsultantActivity`、`PlayerActivity`、`WebAppBridge`、`activity_main/consultant/player.xml`。

**复用必做的接线改造**（集成阶段处理，预期编译报错点）：
- 引擎里对 `ConsultantActivity`/`MainActivity` 的引用（如前台通知点击意图、Bridge 回调）→ 改指向新的 Compose MainActivity / ViewModel 回调。
- 上传/同步原来从 WebView CookieManager 取 Cookie → 改用原生 PersistentCookieJar 的会话。
- `WebAppBridge.Host` 那套回调语义（startRecording/stop/pause/resume/getState/isPenConnected/syncPenFiles/pendingInfo）→ 抽成一个 `RecordingController` 接口，由 ViewModel 实现/调用。

**libs/打包约束（照搬现有）**：`blesdk-release.aar`；`com.droidkit:opus:1.1.1`；`net.java.dev.jna:jna:5.14.0@aar`；`abiFilters "armeabi-v7a"`（只打 v7a，否则 arm64 找不到 libopus.so）；`useLegacyPackaging = true`（libopus.so 必须解压到磁盘）。minSdk 24 / targetSdk 34 / compileSdk 34。

## 6. 分析报告：11 个任务 + 11 个 PART（照抄真实名称，来自 webapp.py TASK_REGISTRY / report.html）
- **原始音频**：整页仅一个入口，点开 = 全程播放器(无60s) + 逐字转写(陪伴师/顾客分行带时间戳，可独立折叠)。脚注"AI 自动转写，仅供顾问复盘参考"。
- **任务执行状态**：不常驻正文，点「重跑」弹出 = `9/11 完成`汇总 + **平铺 11 任务**(T1–T11，不分调用组) + 状态(已完成/进行中/失败) + 勾选重跑 + 全部重跑 + 补齐缺失。
- **11 个 PART（可折叠卡，01→11）**：01 全维度评估总览 / 02 顾客真实画像重建 / 03 接诊失分根因定位 / 04 可攻破痛点·完整作战方案 / 05 项目后价值收割·黄金窗口 / 06 关键Case复盘 / 07 顾问能力训练路径 / 08 下一步动作·回店规划 / 09 竞品分析·售后学习清单 / 10 顾客标签·画像积累 / 11 成交诊断·维度判断+老板/专家点评。
- 11 个分析任务（后端，重跑按 task_id）：T1 顾客真实画像 / T2 可攻破痛点+作战方案 / T3 竞品提取+顾客标签 / T4 成交诊断 / T5 质检评分 / T6 Case复盘 / T7 失分根因 / T8 黄金窗口收割 / T9 PART1总览 / T10 能力训练路径 / T11 下一步动作。

## 7. 后端 API（现成，不改后端；base = https://gp.aibeautyfulwomen.com）
顾问端关键端点（webapp.py，共 ~30 个 `/api/consultant/*` + 通用）：
- 鉴权：`POST /login`、`GET /logout`、`GET /api/me`
- 版本：`GET /api/app/version`
- 陪伴上传/占位：`POST /api/consultant/upload`、`POST /api/consultant/placeholder`(+cancel)
- 陪伴笔绑定：`GET /api/consultant/pen/binding`、`POST /api/consultant/pen/report-sn`
- 待整理：`GET /api/consultant/recordings/pending`、`/pending_dates`、`/rebind_candidates`、`POST .../<rid>/bind`、`/direct_rebind`、`/unbind`、`/add_day_customer`、`/remove_day_customer`、`/rebind_request`、`/confirm_speakers`、`needs_confirm`
- 今日接诊：`GET /api/consultant/today_reception`、`POST .../add`、`DELETE .../<id>`、`PATCH .../<id>/date`
- 顾客：`GET /api/consultant/customer_lookup`、`/customer_recordings`、`GET /api/customers/search`
- 会话/分析：`GET /api/consultant/session/preview`、`POST .../preview/remove`、`POST .../session/start_analysis`、`POST .../session/cancel_analysis`、`POST /api/consultant/analyze`
- 报告/任务：`GET /api/session/<sid>`、`/api/session/<sid>/tasks`、`POST /api/session/<sid>/task/<tid>/rerun`、`/tasks/fill-missing`、录音 `GET /api/recording/<rid>/url`、`/asr`
- 提醒：`GET /api/consultant/reminders`、`POST /api/manager/reminder/<rid>/handle`
> 各端点的请求/响应字段，开发时按需读 webapp.py 对应行确认；不要臆造字段。

## 8. 构建 / 运行 / 部署
- JDK 17、Gradle 8.13(aliyun 镜像)、Android SDK android-34、`~/Library/Android/sdk/platform-tools/adb`。
- 测试机：vivo **V2445A，Android 15，逻辑宽约 360px**。**所有界面以 360–390px 为基线，不得横向溢出**（弹性等分、按钮行可换行）。
- 签名：复用 `gongpai-release.jks` + `keystore.properties`（已拷入本目录，固定 keystore 才能覆盖安装）。
- 强制更新：APK 不在 git，发版必 scp 到生产（见长期记忆 `app-release-and-force-update` / `always-deploy-apk-after-android-change`）。
- 真机验证：录音/陪伴笔链路必须真机回归（不可只靠编译）。

## 9. 并行开发约定（给子 agent）
- **先读本 SPEC + warm_2.html，再动手**。视觉/文案/交互一律对齐 warm_2。
- **文件所有权隔离**：每个屏幕 agent 只写自己包目录（`ui/<feature>/`）下的文件，避免互相覆盖。共享契约（Theme/Components/Icons/data models/Repository 接口/Nav routes）由"地基"阶段先定稿，screens 只依赖不修改。
- 守红线（§2）、守 360px 不溢出（§8）、复用引擎不重写（§5）。
- 质量门：地基阶段必须 `./gradlew assembleDebug` 编译通过后再 fan-out 屏幕；屏幕合并后再整体编译+真机冒烟。
