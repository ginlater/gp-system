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
        var sn: String? = nil          // 重拼 body 需要(复查 B3)
        var promptBind: Bool = true    // 录音段=传完弹绑定;同步导入=不弹(一次多段会轰炸)
        var attempts: Int = 0
        var nextAttemptAt: Date? = nil // 失败退避(复查 B7):到点前不自动重试
        var truncated: Bool? = nil     // D1:诚实部分件标记(可选类型:老盘面数据无此键照常解码)
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

    /// 后台任务对账完成前禁止 kick(复查 B1:被杀期间系统还在代传,不对账就重发=双入库)
    private var ready = false

    private override init() {
        super.init()
        load()
        // 冷启动重建会话并对账:系统里还挂着的旧任务按 taskDescription(=item.id)收编进 inflight,
        // 之后才允许 kick——否则同一段两个任务并发上传
        session.getAllTasks { [weak self] tasks in
            Task { @MainActor in
                guard let self else { return }
                for t in tasks where t.state == .running || t.state == .suspended {
                    if let d = t.taskDescription, !d.isEmpty {
                        self.inflight[t.taskIdentifier] = d
                    }
                }
                if !self.inflight.isEmpty {
                    PenLog.d("⬆️ 对账:接管上次会话在传任务 \(self.inflight.count) 个")
                }
                self.ready = true
                self.sweepOrphanFiles()
                self.kick()
            }
        }
        // 网络恢复自动补传
        monitor.pathUpdateHandler = { [weak self] path in
            guard path.status == .satisfied else { return }
            Task { @MainActor in self?.kick() }
        }
        monitor.start(queue: DispatchQueue.global(qos: .utility))
    }

    /// 启动清扫:pending_uploads 里不被 index 引用的孤儿文件(拼body失败/中途崩溃遗留)删除,
    /// 防慢性磁盘泄漏(复查 B3)。
    private func sweepOrphanFiles() {
        let referenced = Set(items.flatMap { [$0.audioFile, $0.bodyFile] } + ["index.json"])
        let files = (try? FileManager.default.contentsOfDirectory(atPath: Self.dir.path)) ?? []
        for f in files where !referenced.contains(f) {
            try? FileManager.default.removeItem(at: Self.dir.appendingPathComponent(f))
            PenLog.d("🧹 清理孤儿上传文件 \(f)")
        }
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
                 promptBind: Bool = true, truncated: Bool = false) {
        let id = UUID().uuidString
        let audioName = "\(id)_\(fileURL.lastPathComponent)"
        let audioDst = Self.dir.appendingPathComponent(audioName)
        do {
            try FileManager.default.moveItem(at: fileURL, to: audioDst)
        } catch {
            // 复查 B3:静默丢=用户以为传了。给明白话 + 清占位,音频留在原地(tmp)尽人事
            PenLog.d("⚠️ 上传入队失败(移动文件): \(error.localizedDescription)")
            RecordingManager.shared.toast = "保存录音失败（存储空间不足？），请截图联系工程师"
            if let pid = placeholderId {
                Task { try? await ConsultantRepo.cancelPlaceholder(pid) }
            }
            return
        }
        let bodyName = "\(id).body"
        let item = Item(id: id, audioFile: audioName, bodyFile: bodyName,
                        durationSec: durationSec, recordedAt: recordedAt,
                        penFile: penFile, contentType: contentType, placeholderId: placeholderId,
                        sn: sn, promptBind: promptBind, truncated: truncated ? true : nil)
        // 拼 body 失败不丢段:音频已在常驻目录,item 照记,start() 会按元数据重拼(复查 B3)
        try? Self.buildMultipartBodyFile(
            to: Self.dir.appendingPathComponent(bodyName),
            boundary: Self.boundary(for: id),
            fields: Self.bodyFields(for: item), fileURL: audioDst,
            fileField: "file", contentType: contentType)
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

    private static func bodyFields(for item: Item) -> [String: String?] {
        var f: [String: String?] = ["duration_sec": String(item.durationSec)]
        f["recorded_at"] = item.recordedAt
        f["pen_file"] = item.penFile
        f["sn"] = item.sn
        if let pid = item.placeholderId { f["placeholder_id"] = String(pid) }
        if item.truncated == true { f["truncated"] = "1" }   // D1:服务端据此记 truncate_note
        return f
    }

    /// 踢一轮:所有不在传且到了重试时间的项发起。force=手动重试(首页badge点击),无视退避。
    func kick(force: Bool = false) {
        guard ready else { return }
        let now = Date()
        for item in items where !inflight.values.contains(item.id) {
            if !force, let next = item.nextAttemptAt, next > now { continue }
            if !force, item.attempts >= 8 { continue }   // 连败8次转手动(复查 B7,防永生重试)
            start(item)
        }
    }

    private func start(_ item: Item) {
        let bodyURL = Self.dir.appendingPathComponent(item.bodyFile)
        if !FileManager.default.fileExists(atPath: bodyURL.path) {
            // body 缺失(拼失败/被清):按元数据从音频重拼,拼不出才放弃(复查 B3/S7)
            let audioURL = Self.dir.appendingPathComponent(item.audioFile)
            guard FileManager.default.fileExists(atPath: audioURL.path),
                  (try? Self.buildMultipartBodyFile(
                      to: bodyURL, boundary: Self.boundary(for: item.id),
                      fields: Self.bodyFields(for: item), fileURL: audioURL,
                      fileField: "file", contentType: item.contentType)) != nil else {
                PenLog.d("⚠️ \(item.audioFile) body 无法重建 → 放弃该项")
                remove(item, deleteFiles: true)
                return
            }
        }
        var req = URLRequest(url: URL(string: "https://gp.aibeautyfulwomen.com/api/consultant/upload")!)
        req.httpMethod = "POST"
        req.setValue("multipart/form-data; boundary=\(Self.boundary(for: item.id))", forHTTPHeaderField: "Content-Type")
        let task = session.uploadTask(with: req, fromFile: bodyURL)
        task.taskDescription = item.id   // 被杀后对账/认领的钥匙(复查 B1)
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

    private func finish(taskId: Int, desc: String?, response: HTTPURLResponse?, error: Error?) {
        // inflight 查不到(被杀期间系统代传完成的旧任务)→ 用 taskDescription 兜底认领(复查 B1)
        let itemId = inflight.removeValue(forKey: taskId) ?? desc
        guard let itemId, let item = items.first(where: { $0.id == itemId }) else {
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
            // 会话过期:静默重登一次;只有重登成功才立刻重试——失败就等退避/外部事件,
            // 否则凭据失效时整文件无退避热循环烧流量(复查 B2)
            PenLog.d("⚠️ 上传401 → 重登后重试 \(item.audioFile)")
            markFailed(item)
            if !reauthing {
                reauthing = true
                Task { @MainActor in
                    let ok = (try? await ConsultantRepo.reauthProbe()) != nil
                    self.reauthing = false
                    if ok { self.kick(force: true) }
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
            // 指数退避 30s→60→…→1h 封顶(复查 B7):5xx/断网时不再每个网络事件全量重发
            let backoff = min(3600.0, 30.0 * pow(2.0, Double(items[i].attempts - 1)))
            items[i].nextAttemptAt = Date().addingTimeInterval(backoff)
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
        let desc = task.taskDescription
        let resp = task.response as? HTTPURLResponse
        Task { @MainActor in
            UploadQueue.shared.finish(taskId: tid, desc: desc, response: resp, error: error)
        }
    }

    nonisolated func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        Task { @MainActor in
            UploadQueue.backgroundCompletionHandler?()
            UploadQueue.backgroundCompletionHandler = nil
        }
    }
}
