import Foundation
import Network

/// 落盘重传队列 + 后台上传。一次性根治审计清单 U1-U5/U9/P1/P2/B2:
///  - 音频移入 `Application Support/pending_uploads/`(系统不清理、不备份),成功上传后才删;
///  - multipart body 预拼成文件,`uploadTask(fromFile:)` 流式上传:内存 KB 级、
///    无 timeoutIntervalForResource 硬顶、App 挂起/被杀由系统进程代传;
///  - 每项元数据 JSON 落盘;失败保留,网络恢复(NWPathMonitor)/回前台/冷启动自动重试;
///  - 401 自动重登一次后重试;结果回调 onUploaded(rid) 供弹绑定页。
@MainActor
final class UploadQueue: NSObject, ObservableObject {
    static let shared = UploadQueue()

    struct Item: Codable, Identifiable {
        let id: String
        let audioFile: String        // pending_uploads 内的文件名
        let bodyFile: String         // 预拼好的 multipart body 文件名
        let durationSec: Int
        let recordedAt: String?
        let penFile: String?
        let contentType: String
        let placeholderId: Int?
        var promptBind: Bool = true    // 录音段=传完弹绑定;同步导入=不弹(一次多段会轰炸)
        var attempts: Int = 0
    }

    /// 待上传总数(含在传);attempts>0 视为"失败过"。
    @Published private(set) var pendingCount = 0
    @Published private(set) var failedCount = 0

    /// 上传成功回调(recordingId, 是否弹绑定页)。
    var onUploaded: ((Int?, Bool) -> Void)?
    /// 首次失败回调(提示"已加入重传队列")。
    var onFirstFailure: (() -> Void)?

    /// 后台会话被系统重新拉起时的收尾回调(AppDelegate 存入)。
    static var backgroundCompletionHandler: (() -> Void)?

    private var items: [Item] = []
    private var inflight: [Int: String] = [:]        // taskIdentifier → item.id
    private var responseData: [Int: Data] = [:]
    private var reauthing = false
    private let monitor = NWPathMonitor()

    private lazy var session: URLSession = {
        let cfg = URLSessionConfiguration.background(withIdentifier: "com.aibeautyfulwomen.gongpai.ios.upload")
        cfg.httpCookieStorage = .shared
        cfg.httpShouldSetCookies = true
        cfg.waitsForConnectivity = true
        cfg.sessionSendsLaunchEvents = true
        return URLSession(configuration: cfg, delegate: self, delegateQueue: nil)
    }()

    private override init() {
        super.init()
        load()
        _ = session   // 冷启动即重建会话:接管上次被杀期间系统代传的任务回调
        // 网络恢复自动补传
        monitor.pathUpdateHandler = { [weak self] path in
            guard path.status == .satisfied else { return }
            Task { @MainActor in self?.kick() }
        }
        monitor.start(queue: DispatchQueue.global(qos: .utility))
        // 启动 3s 后首踢(等 cookie/登录就绪)
        Task { try? await Task.sleep(nanoseconds: 3_000_000_000); kick() }
    }

    // MARK: - 目录/持久化

    static var dir: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let d = base.appendingPathComponent("pending_uploads", isDirectory: true)
        if !FileManager.default.fileExists(atPath: d.path) {
            try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
            var v = d
            var rv = URLResourceValues(); rv.isExcludedFromBackup = true
            try? v.setResourceValues(rv)
        }
        return d
    }

    private var indexURL: URL { Self.dir.appendingPathComponent("index.json") }

    private func load() {
        if let data = try? Data(contentsOf: indexURL),
           let arr = try? JSONDecoder().decode([Item].self, from: data) {
            items = arr
        }
        refreshCounts()
    }

    private func save() {
        if let data = try? JSONEncoder().encode(items) {
            try? data.write(to: indexURL)
        }
        refreshCounts()
    }

    private func refreshCounts() {
        pendingCount = items.count
        failedCount = items.filter { $0.attempts > 0 }.count
    }

    // MARK: - 入队

    /// 收尾产物入队:音频移入常驻目录 + 预拼 multipart body 文件 + 元数据落盘 + 立即尝试上传。
    /// 从此文件与任务都不怕 App 被杀/系统清 tmp。
    func enqueue(fileURL: URL, durationSec: Int, recordedAt: String?,
                 contentType: String, penFile: String?, sn: String?, placeholderId: Int?,
                 promptBind: Bool = true) {
        let id = UUID().uuidString
        let audioName = "\(id)_\(fileURL.lastPathComponent)"
        let audioDst = Self.dir.appendingPathComponent(audioName)
        do {
            try FileManager.default.moveItem(at: fileURL, to: audioDst)
        } catch {
            PenLog.d("⚠️ 上传入队失败(移动文件): \(error.localizedDescription)")
            return
        }
        let bodyName = "\(id).body"
        var fields: [String: String] = ["duration_sec": String(durationSec)]
        fields["recorded_at"] = recordedAt
        fields["pen_file"] = penFile
        fields["sn"] = sn
        if let pid = placeholderId { fields["placeholder_id"] = String(pid) }
        do {
            try Self.buildMultipartBodyFile(
                to: Self.dir.appendingPathComponent(bodyName),
                boundary: Self.boundary(for: id),
                fields: fields, fileURL: audioDst,
                fileField: "file", contentType: contentType)
        } catch {
            PenLog.d("⚠️ 上传入队失败(拼body): \(error.localizedDescription)")
            return
        }
        let item = Item(id: id, audioFile: audioName, bodyFile: bodyName,
                        durationSec: durationSec, recordedAt: recordedAt,
                        penFile: penFile, contentType: contentType, placeholderId: placeholderId,
                        promptBind: promptBind)
        items.append(item)
        save()
        PenLog.d("⬆️ 入队上传 \(audioName) (\(items.count) 项待传)")
        start(item)
    }

    private static func boundary(for id: String) -> String { "MeiliBoundary-\(id)" }

    /// 把 multipart 头 + 音频字节 + 尾流式拼到 body 文件(64KB 分块拷贝,内存 KB 级)。
    private static func buildMultipartBodyFile(to bodyURL: URL, boundary: String,
                                               fields: [String: String?], fileURL: URL,
                                               fileField: String, contentType: String) throws {
        FileManager.default.createFile(atPath: bodyURL.path, contents: nil)
        let out = try FileHandle(forWritingTo: bodyURL)
        defer { try? out.close() }
        func write(_ s: String) { try? out.write(contentsOf: Data(s.utf8)) }
        for (k, v) in fields {
            guard let v else { continue }
            write("--\(boundary)\r\nContent-Disposition: form-data; name=\"\(k)\"\r\n\r\n\(v)\r\n")
        }
        write("--\(boundary)\r\nContent-Disposition: form-data; name=\"\(fileField)\"; filename=\"\(fileURL.lastPathComponent)\"\r\nContent-Type: \(contentType)\r\n\r\n")
        let input = try FileHandle(forReadingFrom: fileURL)
        defer { try? input.close() }
        while let chunk = try input.read(upToCount: 64 * 1024), !chunk.isEmpty {
            try out.write(contentsOf: chunk)
        }
        write("\r\n--\(boundary)--\r\n")
    }

    // MARK: - 发送/重试

    /// 踢一轮:所有不在传的项都发起(冷启动/回前台/网络恢复/手动重试调用)。
    func kick() {
        for item in items where !inflight.values.contains(item.id) {
            start(item)
        }
    }

    private func start(_ item: Item) {
        let bodyURL = Self.dir.appendingPathComponent(item.bodyFile)
        guard FileManager.default.fileExists(atPath: bodyURL.path) else {
            remove(item, deleteFiles: true)   // body 丢了(不应发生):清项防卡队列
            return
        }
        var req = URLRequest(url: URL(string: "https://gp.aibeautyfulwomen.com/api/consultant/upload")!)
        req.httpMethod = "POST"
        req.setValue("multipart/form-data; boundary=\(Self.boundary(for: item.id))", forHTTPHeaderField: "Content-Type")
        let task = session.uploadTask(with: req, fromFile: bodyURL)
        inflight[task.taskIdentifier] = item.id
        task.resume()
    }

    private func remove(_ item: Item, deleteFiles: Bool) {
        items.removeAll { $0.id == item.id }
        if deleteFiles {
            try? FileManager.default.removeItem(at: Self.dir.appendingPathComponent(item.audioFile))
        }
        try? FileManager.default.removeItem(at: Self.dir.appendingPathComponent(item.bodyFile))
        save()
    }

    // MARK: - 完成处理(delegate 跳回主线程后调用)

    private func finish(taskId: Int, response: HTTPURLResponse?, error: Error?) {
        guard let itemId = inflight.removeValue(forKey: taskId),
              let item = items.first(where: { $0.id == itemId }) else {
            responseData.removeValue(forKey: taskId)
            return
        }
        let data = responseData.removeValue(forKey: taskId) ?? Data()
        let status = response?.statusCode ?? -1

        if error == nil, (200..<300).contains(status) {
            let decoder = JSONDecoder()
            decoder.keyDecodingStrategy = .convertFromSnakeCase
            let result = try? decoder.decode(UploadResult.self, from: data)
            PenLog.d("✅ 上传成功 \(item.audioFile) rid=\(result?.id ?? -1)")
            let prompt = item.promptBind
            remove(item, deleteFiles: true)
            onUploaded?(result?.id, prompt)
            return
        }

        if status == 401 {
            // 会话过期:静默重登一次再重试(单飞)
            PenLog.d("⚠️ 上传401 → 重登后重试 \(item.audioFile)")
            markFailed(item)
            if !reauthing {
                reauthing = true
                Task { @MainActor in
                    _ = try? await ConsultantRepo.reauthProbe()
                    self.reauthing = false
                    self.kick()
                }
            }
            return
        }

        // 网络失败/5xx:保留,等下次触发(网络恢复/回前台/启动)重试
        PenLog.d("⚠️ 上传失败 \(item.audioFile) status=\(status) err=\(error?.localizedDescription ?? "-") attempts=\(item.attempts + 1)")
        markFailed(item)
    }

    private func markFailed(_ item: Item) {
        if let i = items.firstIndex(where: { $0.id == item.id }) {
            items[i].attempts += 1
            save()
            if items[i].attempts == 1 { onFirstFailure?() }
        }
    }
}

// MARK: - URLSession delegate(后台会话回调在系统队列,统一跳主线程)

extension UploadQueue: URLSessionDataDelegate {
    nonisolated func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        let tid = dataTask.taskIdentifier
        Task { @MainActor in
            UploadQueue.shared.responseData[tid, default: Data()].append(data)
        }
    }

    nonisolated func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        let tid = task.taskIdentifier
        let resp = task.response as? HTTPURLResponse
        Task { @MainActor in
            UploadQueue.shared.finish(taskId: tid, response: resp, error: error)
        }
    }

    nonisolated func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        Task { @MainActor in
            UploadQueue.backgroundCompletionHandler?()
            UploadQueue.backgroundCompletionHandler = nil
        }
    }
}
