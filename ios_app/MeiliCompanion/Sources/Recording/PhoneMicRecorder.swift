import Foundation
import AVFoundation

/// 手机麦录音(AVAudioRecorder → m4a/AAC)。android 端对应 `recording/PhoneMicService.java`。
/// 后台录音靠 Info.plist 的 `UIBackgroundModes: audio` + playAndRecord 类目。
/// ⚠ 模拟器无麦克风,只能真机录到声音。
final class PhoneMicRecorder: NSObject {
    private var recorder: AVAudioRecorder?
    private(set) var fileURL: URL?
    /// 录音被中断(来电/Siri/闹钟…)且无法自动恢复时回调 → 上层据此收尾保存已录部分,不让界面假装还在录。
    var onInterrupted: (() -> Void)?

    /// 开始录音。completion(成功, recordedAt 'YYYY-MM-DD HH:MM:SS')。
    func start(_ completion: @escaping (Bool, String?) -> Void) {
        let session = AVAudioSession.sharedInstance()
        session.requestRecordPermission { granted in
            DispatchQueue.main.async {
                guard granted else { completion(false, nil); return }
                do {
                    try session.setCategory(.playAndRecord, mode: .default,
                                            options: [.defaultToSpeaker, .allowBluetooth, .allowBluetoothA2DP])
                    try session.setActive(true)
                    let url = FileManager.default.temporaryDirectory
                        .appendingPathComponent("companion_\(Int(Date().timeIntervalSince1970)).m4a")
                    let settings: [String: Any] = [
                        AVFormatIDKey: kAudioFormatMPEG4AAC,
                        AVSampleRateKey: 44100,
                        AVNumberOfChannelsKey: 1,
                        AVEncoderAudioQualityKey: AVAudioQuality.medium.rawValue,
                    ]
                    let rec = try AVAudioRecorder(url: url, settings: settings)
                    guard rec.record() else { completion(false, nil); return }
                    self.recorder = rec
                    self.fileURL = url
                    self.registerInterruption()
                    completion(true, Self.wallClock())
                } catch {
                    completion(false, nil)
                }
            }
        }
    }

    func pause() { recorder?.pause() }
    func resume() { recorder?.record() }

    /// 停止并回传文件 URL。
    func stop(_ completion: @escaping (URL?) -> Void) {
        NotificationCenter.default.removeObserver(self, name: AVAudioSession.interruptionNotification, object: nil)
        recorder?.stop()
        let url = fileURL
        recorder = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        completion(url)
    }

    // MARK: - 中断处理(来电/Siri/闹钟/拔蓝牙耳机)

    private func registerInterruption() {
        NotificationCenter.default.removeObserver(self, name: AVAudioSession.interruptionNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(handleInterruption(_:)),
                                               name: AVAudioSession.interruptionNotification, object: nil)
    }

    @objc private func handleInterruption(_ note: Notification) {
        guard let info = note.userInfo,
              let raw = info[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: raw) else { return }
        switch type {
        case .began:
            recorder?.pause()   // 系统已停采,显式 pause 保险
        case .ended:
            let opts = (info[AVAudioSessionInterruptionOptionKey] as? UInt)
                .map { AVAudioSession.InterruptionOptions(rawValue: $0) } ?? []
            if opts.contains(.shouldResume), let rec = recorder {
                try? AVAudioSession.sharedInstance().setActive(true)
                if rec.record() { return }   // 续录成功,继续
            }
            // 无法恢复 → 通知上层收尾(保存已录部分,界面不再假装在录)
            DispatchQueue.main.async { self.onInterrupted?() }
        @unknown default:
            break
        }
    }

    static func wallClock() -> String {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd HH:mm:ss"
        f.locale = Locale(identifier: "en_US_POSIX")
        return f.string(from: Date())
    }
}
