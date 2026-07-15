package com.airec.bledemo.ui.teach

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.airec.bledemo.data.model.TeachQuestion
import com.airec.bledemo.data.model.TeachQuizDetail
import com.airec.bledemo.designsystem.Dimens
import com.airec.bledemo.designsystem.MeiliIcons
import com.airec.bledemo.designsystem.MeiliPalette
import com.airec.bledemo.designsystem.MeiliShapes
import com.airec.bledemo.designsystem.components.GhostButton
import com.airec.bledemo.designsystem.components.MeiliCard
import com.airec.bledemo.designsystem.components.MeiliTopBar
import com.airec.bledemo.designsystem.components.PillKind
import com.airec.bledemo.designsystem.components.PrimaryButton
import com.airec.bledemo.designsystem.components.StatusPill

/**
 * teach 章节测验（[com.airec.bledemo.nav.Routes.TeachQuiz]）。
 *
 * 作答态：题目卡列表（单选 A-D 选项行 / 情境题多行输入）+ 底部提交按钮（全答完才可点）。
 * 结果态：大分数 + 过/未过 + 本章完成横幅 + 逐题判分明细（对错/正确答案/解析）+ 再考一次。
 */
@Composable
fun TeachQuizScreen(
    chapterKey: String,
    quizIndex: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    vm: TeachQuizViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(chapterKey, quizIndex) { vm.init(chapterKey, quizIndex) }
    LaunchedEffect(state.toast) {
        state.toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.toastShown()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MeiliPalette.Bg)
            .statusBarsPadding()
            .padding(horizontal = Dimens.ScreenH)
            .imePadding(),
    ) {
        MeiliTopBar(
            title = state.quiz?.title ?: "章节测验",
            subtitle = "测验 $quizIndex · 满分 100 · 60 分通过",
            onBack = onBack,
        )

        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MeiliPalette.Clay, strokeWidth = 2.5.dp, modifier = Modifier.size(26.dp))
            }
            state.error != null -> Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 70.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(MeiliIcons.Warn, contentDescription = null, tint = MeiliPalette.Rose, modifier = Modifier.size(34.dp))
                Spacer(Modifier.height(Dimens.S3))
                Text(state.error!!, style = MaterialTheme.typography.bodyMedium, color = MeiliPalette.Ink2)
                Spacer(Modifier.height(Dimens.S4))
                PrimaryButton("重试", onClick = { vm.load() })
            }
            state.result != null -> ResultContent(
                state = state,
                onRetry = { vm.retry() },
                onBack = onBack,
            )
            else -> AnswerContent(state = state, vm = vm)
        }
    }
}

// ============================================================================
// 作答态
// ============================================================================

@Composable
private fun AnswerContent(state: TeachQuizUiState, vm: TeachQuizViewModel) {
    val questions = state.quiz?.questions.orEmpty()
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(Dimens.CardGap),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(questions.size, key = { it }) { i ->
            QuestionCard(
                index = i + 1,
                question = questions[i],
                answer = state.answers[i],
                onAnswer = { vm.answer(i, it) },
            )
        }
        item(key = "submit") {
            Column {
                PrimaryButton(
                    text = when {
                        state.submitting -> "提交中…"
                        state.allAnswered -> "提交答卷"
                        else -> "还有 ${questions.indices.count { state.answers[it].isNullOrBlank() }} 题未作答"
                    },
                    onClick = { vm.submit() },
                    enabled = state.allAnswered && !state.submitting,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }
}

/** 单题卡：题干 + 单选选项行 / 情境题多行输入。 */
@Composable
private fun QuestionCard(
    index: Int,
    question: TeachQuestion,
    answer: String?,
    onAnswer: (String) -> Unit,
) {
    MeiliCard {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                "$index",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MeiliPalette.ClayDeep,
                modifier = Modifier
                    .background(MeiliPalette.ClayTint, CircleShape)
                    .padding(horizontal = 9.dp, vertical = 2.dp),
            )
            Spacer(Modifier.width(Dimens.S2))
            Text(
                question.question.orEmpty(),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MeiliPalette.Ink,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(Dimens.S3))

        if (question.isScenario) {
            // 情境题：自由作答（服务端按关键词命中打分）
            OutlinedTextField(
                value = answer.orEmpty(),
                onValueChange = onAnswer,
                placeholder = {
                    Text("请结合课件作答（建议 150-250 字）", color = MeiliPalette.Ink4)
                },
                minLines = 5,
                shape = MeiliShapes.Sm,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MeiliPalette.Clay,
                    unfocusedBorderColor = MeiliPalette.Line,
                    focusedContainerColor = MeiliPalette.Surface,
                    unfocusedContainerColor = MeiliPalette.SurfaceSoft,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            // 单选：A-D 选项行，选中 = 陶土描边 + tint 底
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.S2)) {
                question.options.orEmpty().toSortedMap().forEach { (letter, text) ->
                    val selected = answer == letter
                    val interaction = remember { MutableInteractionSource() }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (selected) MeiliPalette.ClayTint else MeiliPalette.SurfaceSoft,
                                MeiliShapes.Sm,
                            )
                            .border(
                                Dimens.BorderField,
                                if (selected) MeiliPalette.Clay else MeiliPalette.LineSoft,
                                MeiliShapes.Sm,
                            )
                            .clickable(interactionSource = interaction, indication = null) { onAnswer(letter) }
                            .padding(horizontal = Dimens.S3, vertical = 12.dp),
                    ) {
                        Text(
                            letter,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = if (selected) MeiliPalette.ClayDeep else MeiliPalette.Ink3,
                        )
                        Spacer(Modifier.width(Dimens.S2))
                        Text(
                            text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (selected) MeiliPalette.Ink else MeiliPalette.Ink2,
                            modifier = Modifier.weight(1f),
                        )
                        if (selected) {
                            Icon(MeiliIcons.Check, null, tint = MeiliPalette.ClayDeep, modifier = Modifier.size(Dimens.IconSm))
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// 结果态
// ============================================================================

@Composable
private fun ResultContent(
    state: TeachQuizUiState,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val result = state.result ?: return
    val questions = state.quiz?.questions.orEmpty()
    val passed = result.passed == true

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(Dimens.CardGap),
        modifier = Modifier.fillMaxSize(),
    ) {
        // ---- 分数卡 ----
        item(key = "score") {
            MeiliCard {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "${result.score ?: 0}",
                        style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                        color = if (passed) MeiliPalette.LeafText else MeiliPalette.RoseText,
                    )
                    Spacer(Modifier.height(6.dp))
                    StatusPill(
                        text = if (passed) "通过（≥60 分）" else "未通过，再接再厉",
                        kind = if (passed) PillKind.Ok else PillKind.Danger,
                        icon = if (passed) MeiliIcons.Check else MeiliIcons.Warn,
                    )
                    if (result.chapterCompleted == true) {
                        Spacer(Modifier.height(Dimens.S3))
                        Text(
                            if (result.nextUnlocked != null) "🎉 本章三套测验全部通过，已解锁下一章！"
                            else "🎉 本章三套测验全部通过！",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = MeiliPalette.LeafText,
                        )
                    }
                    Spacer(Modifier.height(Dimens.S4))
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.S2)) {
                        GhostButton("再考一次", onClick = onRetry)
                        PrimaryButton("返回课程", onClick = onBack)
                    }
                }
            }
        }
        // ---- 逐题明细 ----
        val details = result.details.orEmpty()
        items(details.size, key = { "d$it" }) { i ->
            DetailCard(index = i + 1, question = questions.getOrNull(i), detail = details[i])
        }
        item(key = "bottom-inset") { Spacer(Modifier.navigationBarsPadding()) }
    }
}

/** 逐题判分明细卡：对错 + 我的答案 + 正确答案/解析（情境题只有得分）。 */
@Composable
private fun DetailCard(
    index: Int,
    question: TeachQuestion?,
    detail: TeachQuizDetail,
) {
    val correct = detail.correct == true
    MeiliCard(tight = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (correct) MeiliIcons.Check else MeiliIcons.Close,
                contentDescription = null,
                tint = if (correct) MeiliPalette.LeafText else MeiliPalette.RoseText,
                modifier = Modifier.size(Dimens.IconSm),
            )
            Spacer(Modifier.width(Dimens.S2))
            Text(
                "第 $index 题 · ${detail.points ?: 0} 分",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MeiliPalette.Ink,
                modifier = Modifier.weight(1f),
            )
        }
        val q = question?.question
        if (!q.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(q, style = MaterialTheme.typography.bodySmall, color = MeiliPalette.Ink3)
        }
        Spacer(Modifier.height(6.dp))
        val my = (detail.user as? String).orEmpty().ifBlank { "（未作答）" }
        Text("我的答案：$my", style = MaterialTheme.typography.bodySmall, color = MeiliPalette.Ink2)
        if (!correct && !detail.answer.isNullOrBlank()) {
            Text("正确答案：${detail.answer}", style = MaterialTheme.typography.bodySmall, color = MeiliPalette.LeafText)
        }
        if (!detail.explanation.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text("解析：${detail.explanation}", style = MaterialTheme.typography.bodySmall, color = MeiliPalette.Ink3)
        }
    }
}
