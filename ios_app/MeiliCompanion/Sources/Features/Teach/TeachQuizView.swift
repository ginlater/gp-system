import SwiftUI

/// teach 章节测验。android 端对应 `ui/teach/TeachQuizScreen.kt`。
///
/// 作答态:题目卡列表(单选 A-D 选项行 / 情境题多行输入)+ 底部提交按钮(全答完才可点)。
/// 结果态:大分数 + 过/未过 + 本章完成横幅 + 逐题判分明细(对错/正确答案/解析)+ 再考一次。
struct TeachQuizView: View {
    let chapterKey: String
    let quizIndex: Int

    @Environment(\.dismiss) private var dismiss
    @StateObject private var vm = TeachQuizViewModel()

    var body: some View {
        ZStack(alignment: .bottom) {
            MeiliColor.bg.ignoresSafeArea()
            VStack(spacing: 0) {
                MeiliTopBar(title: vm.quiz?.title ?? "章节测验",
                            subtitle: "测验 \(quizIndex) · 满分 100 · 60 分通过",
                            onBack: { dismiss() })
                    .padding(.horizontal, MeiliMetric.screenH)

                if vm.loading {
                    Spacer()
                    ProgressView().tint(MeiliColor.clay)
                    Spacer()
                } else if let err = vm.error {
                    TeachErrorRetry(message: err) { vm.load() }
                    Spacer()
                } else if vm.result != nil {
                    resultContent
                } else {
                    answerContent
                }
            }

            if let t = vm.toast {
                Text(t).font(MeiliFont.bodySm).foregroundStyle(.white)
                    .padding(.horizontal, 16).padding(.vertical, 11)
                    .background(MeiliColor.inkSurface).clipShape(Capsule())
                    .padding(.bottom, 30)
                    .task(id: t) {
                        try? await Task.sleep(nanoseconds: 2_200_000_000)
                        if !Task.isCancelled { vm.toast = nil }
                    }
            }
        }
        .toolbar(.hidden, for: .navigationBar)
        .onAppear { vm.initLoad(chapterKey: chapterKey, index: quizIndex) }
    }

    // ---- 作答态 ----
    private var answerContent: some View {
        ScrollView {
            LazyVStack(spacing: MeiliMetric.cardGap) {
                let questions = vm.quiz?.questions ?? []
                ForEach(Array(questions.enumerated()), id: \.offset) { i, q in
                    QuestionCard(index: i + 1, question: q,
                                 answer: vm.answers[i] ?? "",
                                 onAnswer: { vm.answer(i, $0) })
                }
                MeiliButton(
                    vm.submitting ? "提交中…"
                        : (vm.allAnswered ? "提交答卷" : "还有 \(vm.unansweredCount) 题未作答"),
                    block: true,
                    enabled: vm.allAnswered && !vm.submitting) {
                    vm.submit()
                }
            }
            .padding(.horizontal, MeiliMetric.screenH)
            .padding(.bottom, 28)
        }
        .scrollDismissesKeyboard(.interactively)
    }

    // ---- 结果态 ----
    private var resultContent: some View {
        ScrollView {
            LazyVStack(spacing: MeiliMetric.cardGap) {
                if let result = vm.result {
                    scoreCard(result)
                    let details = result.details ?? []
                    let questions = vm.quiz?.questions ?? []
                    ForEach(Array(details.enumerated()), id: \.offset) { i, d in
                        DetailCard(index: i + 1,
                                   question: i < questions.count ? questions[i] : nil,
                                   detail: d)
                    }
                }
            }
            .padding(.horizontal, MeiliMetric.screenH)
            .padding(.bottom, 28)
        }
    }

    private func scoreCard(_ result: TeachSubmitResp) -> some View {
        let passed = result.passed == true
        return MeiliCard {
            VStack(spacing: 6) {
                Text("\(result.score ?? 0)")
                    .font(MeiliFont.scoreBig)
                    .foregroundStyle(passed ? MeiliColor.leafText : MeiliColor.roseText)
                StatusPill(text: passed ? "通过（≥60 分）" : "未通过，再接再厉",
                           kind: passed ? .ok : .danger,
                           icon: passed ? MeiliIcons.check : MeiliIcons.warn)
                if result.chapterCompleted == true {
                    Text(result.nextUnlocked != nil
                         ? "🎉 本章三套测验全部通过，已解锁下一章！"
                         : "🎉 本章三套测验全部通过！")
                        .font(.sz(13, weight: .bold)).foregroundStyle(MeiliColor.leafText)
                        .padding(.top, MeiliMetric.s2)
                }
                HStack(spacing: MeiliMetric.s2) {
                    MeiliButton("再考一次", kind: .ghost) { vm.retry() }
                    MeiliButton("返回课程") { dismiss() }
                }
                .padding(.top, MeiliMetric.s3)
            }
            .frame(maxWidth: .infinity)
        }
    }
}

/// 单题卡:题干 + 单选选项行 / 情境题多行输入。
private struct QuestionCard: View {
    let index: Int
    let question: TeachQuestion
    let answer: String
    let onAnswer: (String) -> Void

    var body: some View {
        MeiliCard {
            HStack(alignment: .top, spacing: MeiliMetric.s2) {
                Text("\(index)")
                    .font(.sz(13, weight: .bold)).foregroundStyle(MeiliColor.clayDeep)
                    .padding(.horizontal, 9).padding(.vertical, 2)
                    .background(MeiliColor.clayTint).clipShape(Circle())
                Text(question.question ?? "")
                    .font(.sz(13.5, weight: .medium)).foregroundStyle(MeiliColor.ink)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            Spacer().frame(height: MeiliMetric.s3)

            if question.isScenario {
                // 情境题:自由作答(服务端按关键词命中打分)
                ScenarioField(answer: answer, onAnswer: onAnswer)
            } else {
                // 单选:A-D 选项行,选中 = 陶土描边 + tint 底
                VStack(spacing: MeiliMetric.s2) {
                    let opts = (question.options ?? [:]).sorted { $0.key < $1.key }
                    ForEach(opts, id: \.key) { letter, text in
                        optionRow(letter: letter, text: text, selected: answer == letter)
                    }
                }
            }
        }
    }

    private func optionRow(letter: String, text: String, selected: Bool) -> some View {
        Button { onAnswer(letter) } label: {
            HStack(spacing: MeiliMetric.s2) {
                Text(letter)
                    .font(.sz(13.5, weight: .bold))
                    .foregroundStyle(selected ? MeiliColor.clayDeep : MeiliColor.ink3)
                Text(text)
                    .font(.sz(13))
                    .foregroundStyle(selected ? MeiliColor.ink : MeiliColor.ink2)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .multilineTextAlignment(.leading)
                if selected {
                    MeiliIcon(MeiliIcons.check, size: MeiliMetric.iconSm)
                        .foregroundStyle(MeiliColor.clayDeep)
                }
            }
            .padding(.horizontal, MeiliMetric.s3).padding(.vertical, 12)
            .background(selected ? MeiliColor.clayTint : MeiliColor.surfaceSoft)
            .clipShape(RoundedRectangle(cornerRadius: MeiliRadius.sm, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: MeiliRadius.sm, style: .continuous)
                    .strokeBorder(selected ? MeiliColor.clay : MeiliColor.lineSoft,
                                  lineWidth: MeiliMetric.borderField)
            }
        }
        .buttonStyle(.plain)
    }
}

/// 情境题多行输入。
private struct ScenarioField: View {
    let answer: String
    let onAnswer: (String) -> Void
    @FocusState private var focused: Bool

    var body: some View {
        TextField("请结合课件作答（建议 150-250 字）",
                  text: Binding(get: { answer }, set: onAnswer),
                  axis: .vertical)
            .lineLimit(5...12)
            .font(.sz(13.5))
            .foregroundStyle(MeiliColor.ink)
            .tint(MeiliColor.clay)
            .focused($focused)
            .padding(12)
            .background(focused ? MeiliColor.white : MeiliColor.surfaceSoft)
            .clipShape(RoundedRectangle(cornerRadius: MeiliRadius.sm, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: MeiliRadius.sm, style: .continuous)
                    .strokeBorder(focused ? MeiliColor.clay : MeiliColor.line,
                                  lineWidth: MeiliMetric.borderField)
            }
    }
}

/// 逐题判分明细卡:对错 + 我的答案 + 正确答案/解析(情境题只有得分)。
private struct DetailCard: View {
    let index: Int
    let question: TeachQuestion?
    let detail: TeachQuizDetail

    var body: some View {
        let correct = detail.correct == true
        MeiliCard(tight: true) {
            HStack(spacing: MeiliMetric.s2) {
                MeiliIcon(correct ? MeiliIcons.check : MeiliIcons.close, size: MeiliMetric.iconSm)
                    .foregroundStyle(correct ? MeiliColor.leafText : MeiliColor.roseText)
                Text("第 \(index) 题 · \(detail.points ?? 0) 分")
                    .font(.sz(13.5, weight: .bold)).foregroundStyle(MeiliColor.ink)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            if let q = question?.question?.nilIfBlank {
                Text(q).font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3)
                    .padding(.top, 6)
            }
            Text("我的答案：\((detail.user ?? "").isEmpty ? "（未作答）" : detail.user!)")
                .font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink2)
                .padding(.top, 6)
            if !correct, let ans = detail.answer?.nilIfBlank {
                Text("正确答案：\(ans)").font(MeiliFont.bodySm).foregroundStyle(MeiliColor.leafText)
            }
            if let exp = detail.explanation?.nilIfBlank {
                Text("解析：\(exp)").font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3)
                    .padding(.top, 4)
            }
        }
    }
}
