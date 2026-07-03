# 蓝牙陪伴笔 · iOS 支持调研清单(发给杰理 / 声云)

> ✅ **2026-06-16 更新:声云已确认有官方 iOS SDK,本清单基本回答完毕。** 杰理已决定下线,只走声云。
> 声云给的两个 zip(`蓝牙_声云录音卡片sdk518.zip`、`soni-sdkdemo-main.zip`)里:
> - iOS SDK = 预编译 `libPNote.a`(Objective-C,内部 CoreBluetooth)+ `PNode.h`;手动链接 + Swift 桥接头,非 Pod/SPM。
> - 用 **zip2(soni-sdkdemo)里更新的那份**(多 OTA/WiFi快传/低功耗);demo `AppDelegate.swift` 是完整 Swift 调用样例。
> - 录音 = opus 16k/单声道/40字节帧(同 android);`startGetFileByFromToEnd` 原生按 offset 续传=补传。
> - ⚠ `.a` **仅 arm64 真机、无模拟器切片** → 含笔功能只能真机测。
> - 协议文档(JSON over BLE):zip1 内 `iOS SDK开发文档(最新版本)-2026-1-5.md`。
> **仍需问声云的少数点见文末「剩余待确认」。** 下面原始清单保留作存档。

---


> 背景:android 版用了两个**安卓专用 SDK**(`blesdk-release.aar` 杰理、`pnote_*.aar` 声云)。
> iOS 用不了 .aar。要在 iOS 上支持陪伴笔,**核心是问厂商有没有 iOS SDK**。这一个问题能让 iOS 工期相差一个数量级。
> iOS v1 先不依赖笔(手机麦),拿到下面答复后再决定何时、怎么接。

## 这支笔是什么(给厂商对齐型号)
- android 端集成了**两种笔**:① 杰理(JieLi)方案,SDK = `blesdk-release.aar`;② 声云 PNote(型号如 CB08),SDK = `pnote_20260313081243.aar`。
- 录音格式:opus(android 端有 OpusBridge / OggOpusWriter,声云走裸 opus 帧端上包 Ogg)。
- 现用能力:BLE 连接、机身录音文件列表、按片段下载、断线补传、电量/状态。

## 必问清单(逐条要明确答复)

### A. 有没有现成 iOS SDK?(决定性问题)
1. 贵司是否提供 **iOS 版 SDK**(CoreBluetooth 封装,Swift / Objective-C 均可)?
2. 若有:给 **SDK 文件 + iOS Demo 工程 + 接口文档**;支持 **Swift Package / CocoaPods / 手动 .framework/.xcframework** 哪种集成?
3. 最低支持 iOS 版本?是否支持 **arm64 真机 + 模拟器(arm64-sim)**?

### B. 若无 iOS SDK —— 能否拿到 BLE 协议,自己用 CoreBluetooth 实现?
4. 能否提供 **BLE 协议文档**:Service/Characteristic UUID、连接握手、开始/停止录音指令、
   机身文件列表查询、分片下载协议、断点续传、电量/状态上报?
5. 录音编码细节:opus 的帧结构 / 采样率 / 声道 / 是否带容器头?(android 端踩过"固定步长解码烂到 EOF"的坑,需精确帧格式)
6. 是否有**非 SDK 的参考实现**(C / 伪代码 / 抓包样例)可参考?

### C. 兼容与时间
7. 同一支笔,android 已连接时 iOS 能否连?是否需要重新配对?是否支持多端?
8. 若需贵司开发 iOS SDK,**排期与费用**?

## 内部决策树(拿到答复后)
- **A 有 iOS SDK** → 成本骤降:照 android 的 PenController 逻辑,用厂商 iOS SDK 重实现 `RecordingController` 的陪伴笔分支。中等工作量。
- **B 只有协议文档** → 用 CoreBluetooth 从零实现连接/下载/补传 + opus 解码。**高成本、高风险**(等于重踩 android 那套坑),需排专门里程碑。
- **都没有** → iOS v1/v2 **只支持手机麦**,陪伴笔入口在 iOS 上隐藏或标"仅安卓",等厂商补 iOS SDK 再上。

## 给两家分别要的东西(一句话版)
- **杰理**:"你们的 BLE 录音笔(我们用的 `blesdk-release.aar`)有没有 iOS SDK?有就给 SDK + Demo + 文档;没有就给 BLE 协议文档和 opus 帧格式。"
- **声云(PNote)**:"PNote(CB08 这类)有没有 iOS SDK?有就给 xcframework/Pod + Demo;没有就给 BLE 协议 + 裸 opus 帧规格,我们自己用 CoreBluetooth 接。"

## 剩余待确认(声云)
1. **模拟器切片**:能否提供带 arm64 模拟器架构的 `.a`/`.xcframework`?(否则团队只能真机调,接受亦可)
2. **后台传输**:连接/下载在 app 切后台时是否稳定?是否要求 `UIBackgroundModes: bluetooth-central`?有无保活建议?
3. **同一支笔多端**:android 连过的笔,iOS 直接连是否需重新配对?能否双端共存?
4. **最新 iOS 开发文档**:zip2 README 提到 `iOS SDK开发文档...2026.04.23.md` 但压缩包里没有 → 索取最新版文档。
5. **App Store 上架合规**:有无静态库的隐私清单(PrivacyInfo)/合规说明,供审核用?
