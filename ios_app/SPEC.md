# 美丽陪伴 · iOS 版 — 构建唯一事实源 (SPEC)

> 这份文档是 iOS 原生版的**唯一事实源**。所有开发(含子 agent)必须先读它再动手。
> 它的兄弟文档是 `../android_app_v2/SPEC.md`(android 版事实源)。
> 视觉/交互的像素级事实源沿用 `../android_app_v2/mockups/warm_2.html`(暖玉柔光定稿可点原型)。

## 0. 一句话目标
把已经稳定的 **android_app_v2「美丽陪伴」顾问端**，1:1 复刻成一个**纯原生 iOS app(SwiftUI)**。
**功能、页面、信息架构全部一致**;**后端一行都不改**(对接现成 Flask JSON API);
在 iOS 上把"暖玉柔光"设计做得更精致(原生流畅度、SF/宋体、触感反馈、安全区适配)。

## 1. 当前状态 / 硬前提
- **本机暂只装了 Command Line Tools,没有完整 Xcode** → 编译/运行/真机部署都做不了。
  **第一步:用户从 Mac App Store 安装完整 Xcode(免费,~10GB+)。** 装好前可写全部源码,但无法编译验证。
- 开发者:Claude 直接在本仓库 `ios_app/` 下写。用户用 Mac + Xcode + 自己的 iPhone 真机调试。
- **录音来源做成可插拔**:iOS v1 先做**手机麦**;蓝牙「陪伴笔」(杰理/声云)留干净接口,
  等厂商 iOS SDK 情况明确(见 `厂商SDK清单_要问杰理和声云.md`)再接,不返工。

## 2. 产品红线(顾客可见处,违者砸品牌 —— 与 android 版完全一致)
- **绝不出现"录音/录制/recording"**。统一"陪伴"系词汇:美丽陪伴 / 点击开启陪伴 / 陪伴进行中 /
  结束陪伴 / 陪伴师(顾问) / 看看今天的陪伴 / 回顾每一次陪伴。
- 蓝牙设备叫 **「陪伴笔」**(不是"录音笔");同步动作叫「从陪伴笔同步」。
- 报告里**不出现"调用 1/2/3"**这类内部实现词。
- **高级、不廉价**:统一线性图标体系、精致排版留白、克制配色。禁 emoji/老拟物/粗劣 clipart。
- 详见长期记忆 `companion-wording-no-recording`。

## 3. 架构(iOS)
- **语言/框架**:Swift 6 + SwiftUI(+ 必要处用 UIViewRepresentable 包 UIKit,如音频波形/系统分享)。
  最低支持 **iOS 16**(SwiftUI NavigationStack、async/await 成熟)。
- **UI 层**:单 App,`TabView` 自定义底栏(4 tab + 中间大圆 FAB),`NavigationStack` 管次级页 push。
  与 android 的"4 tab 横向 Pager + 中间 FAB"对齐(iOS 上 tab 左右滑可选,优先稳妥用 TabView)。
- **数据层**:`URLSession`(async/await) + `Codable`。Cookie 会话鉴权用 `HTTPCookieStorage`
  (登录 POST `/login` 后系统自动持有 Cookie,与 android 的 PersistentCookieJar 等价)。
- **状态管理**:每屏一个 `@Observable`(或 ObservableObject)ViewModel,与 android 的 ViewModel 一一对应。
- **录音引擎(iOS 自研 UI,陪伴笔用声云官方 iOS SDK)**:
  - 手机麦:`AVAudioEngine` / `AVAudioRecorder` 录 → 编码。需开启 **Background Modes: Audio** 后台录音权限。
  - 输出格式与后端兼容:后端 `/api/consultant/upload` 已接受多种音频;iOS 优先录 **m4a/AAC** 或
    用 opus(若要与 android 完全对齐)。**上传前确认后端对 iOS 产物的解析**(开发时读 webapp.py upload 处)。
  - **陪伴笔(声云,已确认有 iOS SDK)**:`libPNote.a`(Objective-C,内部 CoreBluetooth)+ `PNode.h`。
    - 用 **zip2(soni-sdkdemo)里更新的那份**:`/tmp/soni_inspect/zip2/soni-sdkdemo-main/ios/Runner/sonilib/`(多了 OTA/WiFi快传/低功耗 API)。
    - 集成:`.a` 拖进工程 + Link Binary + `LIBRARY_SEARCH_PATHS` + Swift **桥接头** `#import "PNode.h"`。非 Pod/SPM,纯手动链接。
    - 调用样例:照抄 `/tmp/soni_inspect/zip2/soni-sdkdemo-main/ios/Runner/AppDelegate.swift`(它把每个 `PNode` 方法 + `WindBleDelegate` 回调都演示了)。
    - 协议事实源(JSON over BLE,cmd 整数键):`/tmp/soni_inspect/zip1/iOS SDK开发文档(最新版本)-2026-1-5.md`。
    - 单例 `PNode.shared()` + `delegate`;关键方法:`startSearch/connectDevice/syncTime`(连后必调)、
      `getRecordFileList`、`startGetFileByFromToEnd:::`(**按字节 offset 续传=补传白送**)、`getCBC`(电量)、`getSn`、
      `start/pause/continue/stopRecord` + `*BtnBackRecord`(响应笔实体键,不回笔不动作)、`sendAppShowState:`(前/后台)。
    - **录音格式 = opus 16k 单声道 40字节/帧**(与 android 完全相同)→ **复用现成 opus→Ogg→服务器链路,后端零改动**。
    - ⚠ **`libPNote.a` 仅 arm64 真机,无模拟器切片** → 含笔功能的构建**只能真机跑**,模拟器链接不过(见 §10)。
  - `RecordingController` 协议(对应 android `recording/RecordingController.kt`):
    `start/stop/pause?/resume?/state/isPenConnected/syncPenFiles/pendingInfo`。
    手机麦实现先落地;陪伴笔实现用 `PNode` 接(参照 android `RecordingControllerImpl`)。
- **包标识**:`bundleId` 用 `com.aibeautyfulwomen.gongpai.ios`(与 android `applicationId` 区分)。
  显示名「美丽陪伴」。

## 4. 设计系统「暖玉柔光」(Warm Jade Glow) —— 色值照搬 warm_2.html `:root`
> 1:1 翻译自 `../android_app_v2/mockups/warm_2.html`。SwiftUI 里建 `Color` 扩展 + `Theme` 环境对象,
> 对应 android 的 `designsystem/Color.kt` + `Theme.kt`。

- **ground**: bg `#F8F3ED` / bg2 `#F2EBE2` / surface `#FFFCF8` / surfaceSoft `#FAF4ED` / surfaceFrost `rgba(255,252,248,.74)`
- **陶土 clay(主色)**: clay `#BE7459` / clayDeep `#A35E45` / claySoft `#EFDED4` / clayTint `#F8ECE4`
- **鼠尾草 sage(辅色)**: sage `#93A38E` / sageDeep `#74866F` / sageSoft `#E3E9DE` / sageTint `#F1F4ED`
- **ink 文字**: ink `#3D3833` / ink2 `#736A60` / ink3 `#A89F94` / ink4 `#C8BFB4`
- **lines**: line `#EBE2D7` / lineSoft `#F2EBE1`
- **status**: honey `#C99A5B`(+soft `#F0E2C8`) / rose `#C57D6B`(+soft `#F2DED7`) / leaf `#7FA083`(+soft `#DFEADD`)
- **圆角**: xs 12 / sm 16 / md 20 / lg 24 / xl 30 / pill 999
- **间距**: 6 / 10 / 14 / 18 / 24 / 32
- **阴影**(克制): sh1 `0 2 10 rgba(120,90,68,.06)` / sh2 `0 8 26 rgba(120,90,68,.09)` /
  glow `0 14 38 rgba(190,116,89,.30)` / glow-sage `0 14 38 rgba(120,140,110,.26)`
- **字体**: 标题衬线 `Songti SC`(iOS 自带,提升高级感);正文 `PingFang SC`(系统默认)。
- **渐变**(还原 CSS): 主按钮 `linear 135° clayLight→clayDeep`;sage 按钮;honey 按钮;
  进度条 `linear 90° clay→honey`;陪伴圆钮空闲 `radial 高光→clay→clayDeep`;
  进行中(呼吸态)`radial clayBright→clay→clayDeep` + 缩放呼吸动画 + 白色停止方块。
- **底栏**: 4 tab + 中间大圆 FAB —— `陪伴(home) / 接诊(reception) / [开启陪伴 FAB] / 待整理(pending) / 档案(profile/report)`。
  > 注:android「方案A」底栏为 陪伴/接诊/●FAB/报告/客户 五槽。iOS 以 **android_app_v2 当前真机版**为准,
  > 开发时核对 `android_app_v2/nav/AppScaffold.kt` + `Routes.kt` 的实际 tab 顺序。

## 5. 屏幕清单(行为/文案以 android_app_v2 + warm_2.html 为准,逐屏对照其 ViewModel)
对照 `android_app_v2/.../ui/<feature>/` 一一复刻:
1. **登录 Login** — POST `/login`,存 Cookie。(android `ui/login/`)
2. **角色门 Gate** — 拉 `/api/me`,按 role 分流:consultant/store_manager → 主壳;admin/super → 管理台。(`ui/gate/`)
3. **陪伴首页 Home** — 大「点击开启陪伴」圆钮(进行中=呼吸态);来源 手机/陪伴笔切换;
   陪伴笔状态卡(已连接·电量·从陪伴笔同步);待传/失败 badge。(`ui/home/`)
4. **今日接诊 Reception** — 今日接诊列表(定时刷新提示);新增/删除/改日期。(`ui/reception/`)
5. **待整理 Pending** — 未绑定陪伴片段列表(时间/时长/说话人数/试听全程无 60s 上限);刷新+从陪伴笔同步;进入绑定。(`ui/pending/`)
6. **绑定顾客 BindCustomer** — 搜索已有顾客 / 新增顾客 / 补登当天接诊。(`ui/bind/`)
7. **会话预览+开始分析 SessionPreview** — 勾选片段→预览→确认开始分析;取消分析/锁定。(`ui/session/`)
8. **分析报告 Report** — 见 §6。(`ui/report/`)
9. **提醒 Reminders** — 提醒列表 + 处理。(`ui/reminders/`)
10. **档案 Archive / 客户 Customer / 客户详情 CustomerDetail** — 顾客美丽档案搜索/详情/价值预测。(`ui/archive/`、`ui/customer/`)
11. **设置 Settings / 强制更新** — 版本检查 `/api/app/version`,低于 MIN 强制升级(iOS 走 App Store 跳转)。(`ui/settings/`)
12. **管理台 AdminHome / 运营看板 AdminOps** — admin/super 角色。(`ui/admin/`)
13. **陪伴笔扫描/连接/机身同步 sheet** — iOS v1 先留入口占位;接厂商 SDK 后实装。

## 6. 分析报告:11 任务 + 11 PART(与 android 完全一致,照抄真实名称)
- **原始音频**:整页仅一个入口,点开 = 全程播放器(无 60s) + 逐字转写(陪伴师/顾客分行带时间戳,可独立折叠)。
  脚注"AI 自动转写,仅供顾问复盘参考"。
- **任务执行状态**:不常驻正文,点「重跑」弹出 = `9/11 完成`汇总 + 平铺 11 任务(T1–T11) + 状态 + 勾选重跑 + 全部重跑 + 补齐缺失。
- **11 个 PART(可折叠卡 01→11)**:01 全维度评估总览 / 02 顾客真实画像重建 / 03 接诊失分根因定位 /
  04 可攻破痛点·完整作战方案 / 05 项目后价值收割·黄金窗口 / 06 关键Case复盘 / 07 顾问能力训练路径 /
  08 下一步动作·回店规划 / 09 竞品分析·售后学习清单 / 10 顾客标签·画像积累 / 11 成交诊断·维度判断+老板/专家点评。
- ⚠ **报告解析坑(android 真实事故,iOS 必须规避)**:`timestamp_seconds` 等字段后端可能返回小数,
  Swift 的 `Codable` 用 `Double?` 接,**不要用 Int**,否则整份 JSON 解析崩 → 报告白页。
  (长期记忆 `v2-report-parse-crash-float-int`)

## 7. 后端 API(现成,不改后端;base = https://gp.aibeautyfulwomen.com)
与 android_app_v2 SPEC §7 完全相同的 ~30 个 `/api/consultant/*` + 通用端点。
**字段以 android_app_v2 的 `data/model/*.kt` + `data/api/ConsultantApi.kt` 为准,直接翻成 Swift `Codable`;
不要臆造字段。** 关键组:
- 鉴权 `POST /login`、`GET /logout`、`GET /api/me`;版本 `GET /api/app/version`
- 上传/占位 `POST /api/consultant/upload`、`/placeholder`(+cancel)
- 陪伴笔绑定 `GET /pen/binding`、`POST /pen/report-sn`
- 待整理 `GET /recordings/pending`、`/pending_dates`、`/rebind_candidates`、`POST .../bind`、`/direct_rebind`、`/unbind`、`/add_day_customer`、`/rebind_request`、`/confirm_speakers`、`needs_confirm`
- 今日接诊 `GET /today_reception`、`POST .../add`、`DELETE .../<id>`、`PATCH .../<id>/date`
- 顾客 `GET /customer_lookup`、`/customer_recordings`、`GET /api/customers/search`
- 会话/分析 `GET /session/preview`、`POST .../preview/remove`、`/session/start_analysis`、`/session/cancel_analysis`、`POST /analyze`
- 报告/任务 `GET /api/session/<sid>`、`/tasks`、`POST /task/<tid>/rerun`、`/tasks/fill-missing`、`GET /api/recording/<rid>/url`、`/asr`
- 提醒 `GET /reminders`、`POST /api/manager/reminder/<rid>/handle`

## 8. 项目结构 / 构建 / 部署(iOS)
- **目录**:`ios_app/MeiliCompanion/`(Xcode 工程) + 本 `SPEC.md`。建议用 **XcodeGen**(`project.yml` → 生成 .xcodeproj,
  避免手改易冲突的 pbxproj),或用户在 Xcode 里 New App 后 Claude 接管 .swift 文件。开工时定。
- **源码分包**(对应 android):
  `DesignSystem/`(Color/Theme/Components/Icons) · `Data/`(Models/Api/Net/Repo/Auth) ·
  `Recording/`(RecordingController 协议 + 手机麦实现 + 陪伴笔桩) · `Features/<screen>/`(每屏 View + ViewModel) · `Nav/`。
- **权限**(Info.plist):`NSMicrophoneUsageDescription`(陪伴需要麦克风)、后续陪伴笔需
  `NSBluetoothAlwaysUsageDescription`;`UIBackgroundModes: audio`(后台陪伴) +(接笔后)`bluetooth-central`。
- **签名/发布**:开发期用免费个人 Apple ID 真机调试(7 天有效,够测);正式上架需 **Apple Developer 账号($99/年)** 走 App Store。
  > ⚠ iOS 不能像 android 那样 scp APK 强制更新 —— 分发必走 App Store(或 TestFlight 内测)。
  > 「强制更新」改为:版本低于 MIN 时弹窗引导跳 App Store,不能后台静默换包。
- **真机验证**:录音/陪伴/上传链路必须真机回归(模拟器无麦/无蓝牙)。

## 9. 开发约定
- **先读本 SPEC + warm_2.html + 对应 android ViewModel,再动手**。视觉/文案/交互一律对齐。
- **复刻而非重新设计**:逻辑、字段、状态机照 android_app_v2;只在 iOS 平台习惯处(导航手势、安全区、
  触感、分享 sheet、系统通知)做原生化。
- 守红线(§2)、报告 Double 解析坑(§6)、后端零改动(§7)。
- 质量门:地基(Theme/Components/Data/Net)先编译通过,再逐屏铺;每屏合并后整体编译 + 真机冒烟。

## 10. 已知 iOS 特有难点(预先标注)
1. **蓝牙陪伴笔**:~~杰理/声云 SDK 是 Android .aar~~ → **已解决**。杰理已下线;**声云有官方 iOS SDK**(`libPNote.a`,见 §3),
   照搬 demo 的 Swift 桥接即可,**不必逆向 BLE**。从"最大风险"降为"中等工作量"。
2. **`libPNote.a` 仅 arm64 真机、无模拟器切片**:① 含笔的构建只能在真 iPhone 上跑(用户有 iPhone,OK);
   ② 若想用模拟器调纯 UI,需把笔 SDK 用 `#if !targetEnvironment(simulator)` 条件排除 + 链接配置区分,
   或干脆全程真机。**手机麦那条链路不受影响**,可模拟器跑。
3. **后台录音被系统回收**:`UIBackgroundModes: audio`(手机麦)+ `bluetooth-central`(笔后台传) + 正确 AVAudioSession 类目可长录。
4. **音频格式与后端兼容**:笔=opus 16k/mono/40字节帧(同 android,链路复用);手机麦需确认 `/upload` 对 m4a/aac 解析,必要时端上转码。
5. **分发**:无侧载/无强制换包,依赖 App Store / TestFlight;上架需开发者账号 + 审核(陪伴=录音/蓝牙类需说明用途)。

## 11. 录音笔稳定性 · 真机验收清单(复测必读)
iOS 陪伴笔链路做完后,**必须逐条复测** Android 已验证的「录音笔稳定性」能力(保活降断连 / 卡流看门狗 / 防截断直传闸 / 时长诚实 / .ogg 占位 recorded_at / 断网 toast / 回归),确认在 iOS(声云 PNote + CoreBluetooth)上同样成立。
- **唯一总清单**:[`../录音笔稳定性_统一真机验收清单_iOS对照.md`](../录音笔稳定性_统一真机验收清单_iOS对照.md) —— 已合并四份零散清单,每项带「Android 改了什么 → iOS 对应点 → 真机操作 → 期望 → 勾选」,可直接照着勾。
- 平台映射要点:Android 的 `PenController`/`ATWOpusConverter`/`penlog.txt` 是杰理命名,iOS 在 PNote 链路找**功能等价点**;保活靠 Background Modes(audio + bluetooth-central),非前台常驻通知;后端契约两端共用、零改动。
