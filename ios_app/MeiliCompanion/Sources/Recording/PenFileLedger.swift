import Foundation

/// 「已完整上传」机身文件名单(对齐安卓 SoniPenController.fullyUploadedNames,2.2.1)。
///
/// 写入条件在 UploadQueue.finish() 收口:服务器【真存了这份音频】——
/// 2xx 且 id>0 且非 discarded(墓碑丢弃)且非 dedupFuzzy(按时刻认亲的去重),且非截断件。
/// 这名单是将来笔上文件清理(规则①"已传删")删机身原件的唯一依据,认错一次=原件永久丢失,
/// 所以宁缺毋滥:任何"收下了请求但没存"的响应都不配进来。
enum PenFileLedger {
    private static let key = "fully_uploaded_pen_files"

    /// 声云文件名形如 note20251203-123344.opus / call…:能解析出时间戳即认为是真实笔文件。
    static func looksLikePenFile(_ name: String) -> Bool {
        fileTimestamp(name) != nil
    }

    /// 从机身文件名解析录音开始时刻(同 PenController.parseRecordedAt 的 8-6 位数字约定)。
    static func fileTimestamp(_ name: String) -> Date? {
        guard let m = name.range(of: #"\d{8}-\d{6}"#, options: .regularExpression) else { return nil }
        let s = name[m]                            // 20260703-174600
        let f = DateFormatter()
        f.dateFormat = "yyyyMMdd-HHmmss"
        f.locale = Locale(identifier: "en_US_POSIX")
        return f.date(from: String(s))
    }

    static func markFullyUploaded(_ name: String) {
        guard looksLikePenFile(name) else { return }
        var cur = Set(UserDefaults.standard.stringArray(forKey: key) ?? [])
        guard !cur.contains(name) else { return }
        cur.insert(name)
        // 防无界增长:只留最近 500 个(按文件名时间戳倒序;笔上文件也就几十个,足够)
        var list = Array(cur)
        if list.count > 500 {
            list.sort { (fileTimestamp($0) ?? .distantPast) > (fileTimestamp($1) ?? .distantPast) }
            list = Array(list.prefix(500))
        }
        UserDefaults.standard.set(list, forKey: key)
        PenLog.d("📗 记入已完整上传名单 \(name) (共\(list.count)个)")
    }

    static func isFullyUploaded(_ name: String) -> Bool {
        (UserDefaults.standard.stringArray(forKey: key) ?? []).contains(name)
    }
}
