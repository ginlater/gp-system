import AVFoundation

/// 陪伴笔录音期间的进程保活(审计 B1):
/// 蓝牙笔录音时 App 没有任何活跃音频会话,`UIBackgroundModes: audio` 完全没用上——
/// 90 分钟锁屏录音全靠"BLE 包持续到达把 App 一次次唤醒"这个巧合;流一断 App 立即挂起,
/// 心跳/重连/看门狗全部冻结,还抬高被 jetsam 杀掉的概率。
/// 方案:笔录音期间跑一个静音 AVAudioEngine 占住 audio 后台模式(.mixWithOthers 不打扰
/// 微信通话/其他App音频),把"活着"从巧合变成系统承诺。手机麦录音自带会话,不需要这个。
enum RecordKeepAlive {
    private static var engine: AVAudioEngine?

    static func start() {
        guard engine == nil else { return }
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, options: [.mixWithOthers])
        try? session.setActive(true)
        let e = AVAudioEngine()
        let silence = AVAudioSourceNode { _, _, _, audioBufferList -> OSStatus in
            let abl = UnsafeMutableAudioBufferListPointer(audioBufferList)
            for buf in abl { memset(buf.mData, 0, Int(buf.mDataByteSize)) }
            return noErr
        }
        e.attach(silence)
        e.connect(silence, to: e.mainMixerNode, format: nil)
        e.mainMixerNode.outputVolume = 0
        do {
            try e.start()
            engine = e
            PenLog.d("🎧 保活音频会话已启动(笔录音期间进程不挂起)")
        } catch {
            PenLog.d("⚠️ 保活音频会话启动失败: \(error.localizedDescription)")
        }
    }

    static func stop() {
        guard engine != nil else { return }
        engine?.stop()
        engine = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        PenLog.d("🎧 保活音频会话已停止")
    }
}
