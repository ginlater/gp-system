import SwiftUI

/// teach 测验页 ViewModel:取题 → 作答 → 提交 → 判分结果。
/// android 端对应 `ui/teach/TeachQuizViewModel.kt`。
///
/// 判分规则(服务端 quiz_helpers.score_quiz):单选每题 15 分;情境题按关键词命中 ×5(≤25)。
/// ≥60 过;三套全过本章完成并解锁下一章。分数只保留最高,重考不吃亏——结果页放「再考一次」。
@MainActor
final class TeachQuizViewModel: ObservableObject {
    @Published var loading = false
    @Published var error: String?
    @Published var quiz: TeachQuizResp?
    @Published var answers: [Int: String] = [:]
    @Published var submitting = false
    @Published var result: TeachSubmitResp?
    @Published var toast: String?

    private let repo = TeachRepository()
    private var chapter = ""
    private var quizIndex = 1

    /// 全部题都有作答(情境题非空文本)才允许提交。
    var allAnswered: Bool {
        guard let total = quiz?.questions?.count, total > 0 else { return false }
        return (0..<total).allSatisfy { !(answers[$0] ?? "").isEmpty }
    }

    var unansweredCount: Int {
        guard let total = quiz?.questions?.count else { return 0 }
        return (0..<total).filter { (answers[$0] ?? "").isEmpty }.count
    }

    /// 首次进入取题(重复调忽略)。
    func initLoad(chapterKey: String, index: Int) {
        if !chapter.isEmpty { return }
        chapter = chapterKey
        quizIndex = index
        load()
    }

    func load() {
        if loading { return }
        loading = true
        error = nil
        result = nil
        answers = [:]
        Task {
            do {
                quiz = try await repo.quiz(chapter: chapter, quizIndex: quizIndex)
                loading = false
            } catch {
                loading = false
                self.error = (error as? SubsystemError)?.message ?? "网络异常"
            }
        }
    }

    /// 记录第 qIndex 题作答(单选 = "A"~"D";情境题 = 文本)。
    func answer(_ qIndex: Int, _ value: String) {
        answers[qIndex] = value
    }

    /// 提交整套答案。未答题在 UI 侧已拦(按钮禁用),这里按题序补空串兜底。
    func submit() {
        guard let total = quiz?.questions?.count else { return }
        if submitting || result != nil { return }
        submitting = true
        let list = (0..<total).map { answers[$0] ?? "" }
        Task {
            do {
                result = try await repo.submitQuiz(chapter: chapter, quizIndex: quizIndex, answers: list)
            } catch {
                toast = (error as? SubsystemError)?.message ?? "提交失败,请重试"
            }
            submitting = false
        }
    }

    /// 结果页「再考一次」:清作答与结果(题目固定,但心态归零)。
    func retry() {
        result = nil
        answers = [:]
    }
}
