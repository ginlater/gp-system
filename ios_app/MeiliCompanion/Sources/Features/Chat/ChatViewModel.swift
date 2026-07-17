import SwiftUI

/// 销售话术 ViewModel:表单/自由描述两模式 + SSE 流式生成 + 历史。
/// 网页版(beauty_consultant.html)已原生化,行为逐项对齐。
@MainActor
final class ChatViewModel: ObservableObject {
    // ---- 模式与表单 ----
    @Published var freeMode = false
    @Published var form = ChatFormSpec.FormData()
    @Published var freeDesc = ""

    // ---- 生成态 ----
    @Published var generating = false
    /// 流式累计的原始文本(含【思考】【话术】标记)。
    @Published var rawOutput = ""
    /// done 帧给的最终切分(优先);流中用 parseThinkingScript 实时切。
    @Published var finalThinking: String?
    @Published var finalScript: String?
    @Published var warnWords: [String] = []
    @Published var genError: String?
    @Published var thinkingExpanded = false

    // ---- 历史 ----
    @Published var historyOpen = false
    @Published var historyLoading = false
    @Published var historyItems: [ChatHistoryItem] = []
    @Published var historyPage = 1
    @Published var historyTotalPages = 1

    // ---- 杂项 ----
    @Published var quotaText = ""
    @Published var loginError: String?
    @Published var toast: String?

    private let repo = ChatRepository()
    private var genTask: Task<Void, Never>?
    private var inited = false

    /// 实时切分的 思考/话术(流中);done 后用服务端切好的。
    var thinking: String {
        finalThinking ?? ChatFormSpec.parseThinkingScript(rawOutput).thinking
    }
    var script: String? {
        finalScript ?? ChatFormSpec.parseThinkingScript(rawOutput).script
    }

    func initLoad() {
        if inited { return }
        inited = true
        loadQuota()
    }

    func loadQuota() {
        Task {
            do {
                let q = try await repo.quota()
                let remain = max(0, (q.total ?? 0) - (q.used ?? 0))
                quotaText = "本月剩余 \(remain)/\(q.total ?? 0) 次"
                loginError = nil
            } catch {
                loginError = (error as? SubsystemError)?.message ?? "网络异常"
            }
        }
    }

    // ---- 生成 ----

    func generate() {
        if generating { return }
        let prompt: String
        if freeMode {
            let desc = freeDesc.trimmingCharacters(in: .whitespacesAndNewlines)
            if desc.isEmpty {
                toast = "请描述顾客情况"
                return
            }
            prompt = desc
        } else {
            let missing = form.missing
            if !missing.isEmpty {
                toast = "请填写：\(missing.joined(separator: "、"))"
                return
            }
            prompt = form.buildPrompt()
        }

        generating = true
        rawOutput = ""
        finalThinking = nil
        finalScript = nil
        warnWords = []
        genError = nil
        thinkingExpanded = false

        genTask = Task { [weak self] in
            guard let self else { return }
            for await ev in self.repo.generateStream(prompt: prompt) {
                switch ev {
                case .delta(let d):
                    self.rawOutput += d
                case .restart:
                    self.rawOutput = ""
                    self.toast = "内容触发合规重写，正在重新生成…"
                case .warning(let words):
                    self.warnWords = words
                case .done(let reply, let thinking, let script):
                    if let reply, !reply.isEmpty { self.rawOutput = reply }
                    self.finalThinking = thinking
                    self.finalScript = script
                    self.generating = false
                    self.loadQuota()
                case .failed(let msg):
                    self.generating = false
                    self.genError = msg
                }
            }
            self.generating = false
        }
    }

    func stopGenerate() {
        genTask?.cancel()
        genTask = nil
        generating = false
    }

    // ---- 历史 ----

    func openHistory() {
        historyOpen = true
        loadHistory(page: 1)
    }

    func loadHistory(page: Int) {
        historyLoading = true
        Task {
            do {
                let (items, totalPages) = try await repo.history(page: page)
                historyItems = items
                historyPage = page
                historyTotalPages = totalPages
            } catch {
                toast = (error as? SubsystemError)?.message ?? "加载失败"
            }
            historyLoading = false
        }
    }

    func toggleFavorite(_ item: ChatHistoryItem) {
        let target = !item.favorite
        historyItems = historyItems.map { i in
            var v = i
            if v.id == item.id { v.favorite = target }
            return v
        }
        Task {
            do { try await repo.setFavorite(id: item.id, favorite: target) }
            catch { loadHistory(page: historyPage) }   // 失败回读
        }
    }
}
