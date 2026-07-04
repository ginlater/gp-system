import Foundation

/// 陪伴笔链路的轻量文件日志(Documents/penlog.txt),对齐 android penlog.txt 的排障习惯。
/// devicectl 控制台挂管道在部分系统上收不到 stdout,文件日志兜底;拉取:
/// `xcrun devicectl device copy from --domain-type appDataContainer
///   --domain-identifier com.aibeautyfulwomen.gongpai.ios --source Documents/penlog.txt ...`
/// 审计 P6:FileHandle 常驻(原来每条日志 open/seek/write/close 一轮,高频时段 IO 放大);
/// 超 2MB 截半保尾。
enum PenLog {
    private static let q = DispatchQueue(label: "com.meili.penlog", qos: .utility)
    private static let maxBytes: UInt64 = 2 * 1024 * 1024
    private static let url: URL = FileManager.default
        .urls(for: .documentDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("penlog.txt")
    private static var handle: FileHandle?
    private static var size: UInt64 = 0
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
            ensureHandle()
            guard let h = handle else { return }
            try? h.write(contentsOf: data)
            size += UInt64(data.count)
            if size > maxBytes { trim() }
        }
    }

    private static func ensureHandle() {
        guard handle == nil else { return }
        if !FileManager.default.fileExists(atPath: url.path) {
            FileManager.default.createFile(atPath: url.path, contents: nil)
        }
        handle = try? FileHandle(forWritingTo: url)
        size = (try? handle?.seekToEnd()) ?? 0
    }

    /// 超限截半:流式复制后一半到临时文件再换回,避免整读进内存。
    private static func trim() {
        try? handle?.close()
        handle = nil
        guard let input = try? FileHandle(forReadingFrom: url) else { return }
        let keepFrom = size / 2
        try? input.seek(toOffset: keepFrom)
        let tmp = url.deletingLastPathComponent().appendingPathComponent("penlog.tmp")
        FileManager.default.createFile(atPath: tmp.path, contents: nil)
        if let out = try? FileHandle(forWritingTo: tmp) {
            while let chunk = try? input.read(upToCount: 128 * 1024), !chunk.isEmpty {
                try? out.write(contentsOf: chunk)
            }
            try? out.close()
        }
        try? input.close()
        try? FileManager.default.removeItem(at: url)
        try? FileManager.default.moveItem(at: tmp, to: url)
        ensureHandle()
    }
}
