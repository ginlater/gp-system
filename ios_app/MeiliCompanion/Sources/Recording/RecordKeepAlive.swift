import AVFoundation

/// 进程保活(审计 B1 + 复查 B2/B3/B5 重构):静音 AVAudioEngine 占住 audio 后台模式。
/// 持有者计数模型——录音("record")、机身下载/补取/同步("penwork")各自 acquire/release,
/// 谁都不在乎时才真正停;解决旧版"state didSet 单点顺带启停"导致的补取期误停/同步后泄漏。
/// 自愈:监听音频中断结束与引擎配置变更(来电/Siri/插拔耳机),持有者还在就自动重启引擎。
enum RecordKeepAlive {
    private static var holders = Set<String>()
    private static var engine: AVAudioEngine?
    private static var observersInstalled = false

    static func acquire(_ who: String) {
        holders.insert(who)
        startEngineIfNeeded()
    }

    static func release(_ who: String) {
        holders.remove(who)
        if holders.isEmpty { stopEngine() }
    }

    private static func startEngineIfNeeded() {
        installObserversIfNeeded()
        if let e = engine, e.isRunning { return }
        let session = AVAudioSession.sharedInstance()
        // 复查 B1:手机麦在录(playAndRecord)时绝不改类目——切成 .playback 会拔掉录音输入,
        // 手机麦从此静默丢内容;录音会话本身就是活的,引擎直接搭上去即可
        if session.category != .playAndRecord {
            // mixWithOthers:不顶掉微信语音/音乐
            try? session.setCategory(.playback, options: [.mixWithOthers])
        }
        do { try session.setActive(true) } catch {
            PenLog.d("⚠️ 保活会话激活失败: \(error.localizedDescription)")
        }
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
            PenLog.d("🎧 保活音频会话已启动(持有者: \(holders.sorted().joined(separator: ",")))")
        } catch {
            engine = nil
            // B1 同款守卫:引擎起不来的兜底也不能把手机麦的会话整个灭掉
            if session.category != .playAndRecord {
                try? session.setActive(false, options: .notifyOthersOnDeactivation)
            }
            PenLog.d("⚠️ 保活引擎启动失败: \(error.localizedDescription)")
        }
    }

    private static func stopEngine() {
        guard engine != nil else { return }
        engine?.stop()
        engine = nil
        // 复查 B1:手机麦还在录时不能把共享会话整个灭掉(那会连麦一起杀)
        if AVAudioSession.sharedInstance().category != .playAndRecord {
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        }
        PenLog.d("🎧 保活音频会话已停止")
    }

    /// 中断(来电/Siri)结束或引擎配置变更后,持有者还在就把引擎拉起来——否则保活会静默死亡。
    private static func installObserversIfNeeded() {
        guard !observersInstalled else { return }
        observersInstalled = true
        NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { note in
            guard let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                  AVAudioSession.InterruptionType(rawValue: raw) == .ended, !holders.isEmpty else { return }
            PenLog.d("🎧 音频中断结束 → 重启保活")
            engine?.stop(); engine = nil
            startEngineIfNeeded()
        }
        NotificationCenter.default.addObserver(
            forName: .AVAudioEngineConfigurationChange, object: nil, queue: .main) { _ in
            guard !holders.isEmpty else { return }
            PenLog.d("🎧 音频配置变更 → 重启保活")
            engine?.stop(); engine = nil
            startEngineIfNeeded()
        }
    }
}
