package com.airec.bledemo.ui.teach

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.airec.bledemo.data.model.TeachQuizResp
import com.airec.bledemo.data.model.TeachSubmitResp
import com.airec.bledemo.data.repo.ApiResult
import com.airec.bledemo.data.teach.TeachRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * teach 测验页 ViewModel：取题 → 作答 → 提交 → 判分结果。
 *
 * 判分规则（服务端 quiz_helpers.score_quiz）：单选每题 15 分；情境题按关键词命中 ×5（≤25）。
 * ≥60 过；三套全过本章完成并解锁下一章（提交响应里带 chapter_completed / next_unlocked）。
 * 分数只保留最高，重考不吃亏——结果页放「再考一次」。
 */
class TeachQuizViewModel(
    private val repo: TeachRepository = TeachRepository(),
) : ViewModel() {

    private val _state = MutableStateFlow(TeachQuizUiState())
    val state: StateFlow<TeachQuizUiState> = _state.asStateFlow()

    private var chapter: String = ""
    private var quizIndex: Int = 1

    /** 首次进入取题（Screen 在 LaunchedEffect 里调一次；重复调忽略）。 */
    fun init(chapterKey: String, index: Int) {
        if (chapter.isNotBlank()) return
        chapter = chapterKey
        quizIndex = index
        load()
    }

    fun load() {
        if (_state.value.loading) return
        _state.update { TeachQuizUiState(loading = true) }
        viewModelScope.launch {
            when (val r = repo.quiz(chapter, quizIndex)) {
                is ApiResult.Success -> _state.update { it.copy(loading = false, quiz = r.data) }
                is ApiResult.Failure -> _state.update { it.copy(loading = false, error = r.message) }
            }
        }
    }

    /** 记录第 [qIndex] 题作答（单选 = "A"~"D"；情境题 = 文本）。 */
    fun answer(qIndex: Int, value: String) {
        _state.update { it.copy(answers = it.answers + (qIndex to value)) }
    }

    /** 提交整套答案。未答题在 UI 侧已拦（按钮禁用），这里按题序补空串兜底。 */
    fun submit() {
        val st = _state.value
        val total = st.quiz?.questions?.size ?: return
        if (st.submitting || st.result != null) return
        _state.update { it.copy(submitting = true) }
        val answers = (0 until total).map { st.answers[it].orEmpty() }
        viewModelScope.launch {
            when (val r = repo.submitQuiz(chapter, quizIndex, answers)) {
                is ApiResult.Success -> _state.update { it.copy(submitting = false, result = r.data) }
                is ApiResult.Failure -> _state.update { it.copy(submitting = false, toast = r.message) }
            }
        }
    }

    /** 结果页「再考一次」：清作答与结果，重新取题（题目固定，但心态归零）。 */
    fun retry() {
        _state.update { it.copy(result = null, answers = emptyMap()) }
    }

    fun toastShown() {
        _state.update { it.copy(toast = null) }
    }
}

/**
 * 测验页 UI 状态。
 *
 * @param quiz 题目（服务端已剥答案）
 * @param answers 已作答（题序 → 答案）
 * @param result 提交后的判分结果（非空 = 展示结果页）
 */
data class TeachQuizUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val quiz: TeachQuizResp? = null,
    val answers: Map<Int, String> = emptyMap(),
    val submitting: Boolean = false,
    val result: TeachSubmitResp? = null,
    val toast: String? = null,
) {
    /** 全部题都有作答（情境题非空文本）才允许提交。 */
    val allAnswered: Boolean
        get() {
            val total = quiz?.questions?.size ?: return false
            return total > 0 && (0 until total).all { !answers[it].isNullOrBlank() }
        }
}
