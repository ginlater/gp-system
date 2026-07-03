import Foundation

/// 陪伴笔链路的轻量文件日志(Documents/penlog.txt),对齐 android penlog.txt 的排障习惯。
/// devicectl 控制台挂管道在部分系统上收不到 stdout,文件日志兜底;拉取:
/// `xcrun devicectl device copy from --domain-type appDataContainer
///   --domain-identifier com.aibeautyfulwomen.gongpai.ios --source Documents/penlog.txt ...`
/// 只记事件级日志(连接/命令/收尾/补传),不记逐帧音频,量极小;超 2MB 自动截半保尾。
enum PenLog {
    private static let q = DispatchQueue(label: "com.meili.penlog")
    private static let maxBytes = 2 * 1024 * 1024
    private static let url: URL = FileManager.default
        .urls(for: .documentDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("penlog.txt")
    private static let df: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "MM-dd HH:mm:ss.SSS"
        f.locale = Locale(identifier: "en_US_POSIX")
        return f
    }()

    static func d(_ msg: String) {
        let line = "\(df.string(from: Date())) \(msg)\n"
        #if DEBUG
        print("[pen] \(msg)")
        #endif
        q.async {
            guard let data = line.data(using: .utf8) else { return }
            if let h = try? FileHandle(forWritingTo: url) {
                defer { try? h.close() }
                if let size = try? h.seekToEnd(), size > maxBytes {
                    try? h.close()
                    trim()
                    if let h2 = try? FileHandle(forWritingTo: url) {
                        defer { try? h2.close() }
                        _ = try? h2.seekToEnd()
                        try? h2.write(contentsOf: data)
                    }
                    return
                }
                try? h.write(contentsOf: data)
            } else {
                try? data.write(to: url)
            }
        }
    }

    /// 超限截半:保留后一半,防日志无限膨胀。
    private static func trim() {
        guard let all = try? Data(contentsOf: url) else { return }
        let tail = all.suffix(all.count / 2)
        try? tail.write(to: url)
    }
}
