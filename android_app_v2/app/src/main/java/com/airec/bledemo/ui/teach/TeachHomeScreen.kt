package com.airec.bledemo.ui.teach

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.airec.bledemo.data.model.TeachChapter
import com.airec.bledemo.designsystem.Dimens
import com.airec.bledemo.designsystem.MeiliIcons
import com.airec.bledemo.designsystem.MeiliPalette
import com.airec.bledemo.designsystem.components.MeiliCard
import com.airec.bledemo.designsystem.components.MeiliTopBar
import com.airec.bledemo.designsystem.components.PillKind
import com.airec.bledemo.designsystem.components.PrimaryButton
import com.airec.bledemo.designsystem.components.SoftButton
import com.airec.bledemo.designsystem.components.StatusPill
import com.airec.bledemo.designsystem.components.TopBarIconButton

/**
 * teach 网课首页（[com.airec.bledemo.nav.Routes.TeachHome]，工作台「美业网课」进）。
 *
 * 顶部打卡/学习时长卡 + 9 章课程列表（锁/勾/分数状态）。
 *  - 章卡点击 → 课件正文（WebView，[TeachReaderScreen]）；
 *  - 章卡内三个测验 pill 点击 → 测验页（[TeachQuizScreen]）；
 *  - unlocked=false 锁着不让进（toast 提示先完成上一章）；allowed=false 置灰「未开通」。
 * 从课件/测验返回（ON_RESUME）静默刷新，分数与解锁状态即时可见。
 */
@Composable
fun TeachHomeScreen(
    onBack: () -> Unit,
    onOpenReader: (chapterKey: String) -> Unit,
    onOpenQuiz: (chapterKey: String, quizIndex: Int) -> Unit,
    onSwitchSystem: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    vm: TeachHomeViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // 多系统切换弹窗（从网课切回工牌等；能进到这里的账号必然 ≥2 系统，图标恒显）
    val switcherOpen = remember { androidx.compose.runtime.mutableStateOf(false) }

    // 一次性 toast（打卡结果 / 锁章提示统一走系统 toast，与全 app 一致）
    LaunchedEffect(state.toast) {
        state.toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.toastShown()
        }
    }

    // 从课件/测验页返回时静默刷新（测验刚提交，分数/解锁要立刻反映在列表上）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var first = true
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (first) first = false else vm.load(silent = true)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MeiliPalette.Bg)
            .statusBarsPadding()
            .padding(horizontal = Dimens.ScreenH),
    ) {
        MeiliTopBar(
            title = "美业网课",
            subtitle = "课程学习 · 打卡测验",
            onBack = onBack,
            actions = {
                TopBarIconButton(MeiliIcons.Workspace, { switcherOpen.value = true })
                TopBarIconButton(MeiliIcons.Refresh, { vm.load() })
            },
        )

        com.airec.bledemo.ui.workspace.SystemSwitcherSheet(
            visible = switcherOpen.value,
            currentKey = "teach",
            onDismiss = { switcherOpen.value = false },
            onSwitch = onSwitchSystem,
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
            else -> {
                val stats = state.stats
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(Dimens.CardGap),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    // ---- 打卡 / 学习时长卡 ----
                    item(key = "checkin") {
                        CheckinCard(
                            todaySigned = stats?.checkin?.todaySigned == true,
                            streak = stats?.checkin?.streak ?: 0,
                            todayMinutes = stats?.study?.todayMinutes ?: 0.0,
                            completedCount = stats?.progress?.completedCount ?: 0,
                            totalCount = stats?.progress?.totalCount ?: 0,
                            busy = state.checkinBusy,
                            onCheckin = { vm.checkin() },
                        )
                    }
                    // ---- 课程列表 ----
                    val chapters = stats?.progress?.chapters.orEmpty()
                    items(chapters.size, key = { chapters[it].key ?: it.toString() }) { i ->
                        ChapterCard(
                            index = i + 1,
                            chapter = chapters[i],
                            onOpenReader = onOpenReader,
                            onOpenQuiz = onOpenQuiz,
                        )
                    }
                    item(key = "bottom-inset") { Spacer(Modifier.navigationBarsPadding()) }
                }
            }
        }
    }
}

/** 顶部打卡卡：今日学习分钟 + 连续打卡天数 + 完成进度 + 打卡按钮。 */
@Composable
private fun CheckinCard(
    todaySigned: Boolean,
    streak: Int,
    todayMinutes: Double,
    completedCount: Int,
    totalCount: Int,
    busy: Boolean,
    onCheckin: () -> Unit,
) {
    MeiliCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "今日已学 ${"%.0f".format(todayMinutes)} 分钟",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MeiliPalette.Ink,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "连续打卡 $streak 天 · 已完成 $completedCount/$totalCount 章",
                    style = MaterialTheme.typography.bodySmall,
                    color = MeiliPalette.Ink3,
                )
            }
            if (todaySigned) {
                StatusPill("今日已打卡", PillKind.Ok, icon = MeiliIcons.Check)
            } else {
                SoftButton(if (busy) "打卡中…" else "打卡", onClick = onCheckin, enabled = !busy)
            }
        }
    }
}

/** 单章卡：序号圆 + 标题 + 状态 pill + （解锁时）三个测验分数 pill。 */
@Composable
private fun ChapterCard(
    index: Int,
    chapter: TeachChapter,
    onOpenReader: (String) -> Unit,
    onOpenQuiz: (String, Int) -> Unit,
) {
    val context = LocalContext.current
    val allowed = chapter.allowed != false     // 缺省当 true（服务端恒给该字段，防御写法）
    val unlocked = chapter.unlocked == true
    val key = chapter.key.orEmpty()
    val interaction = remember { MutableInteractionSource() }

    MeiliCard(
        modifier = Modifier.clickable(interactionSource = interaction, indication = null) {
            when {
                !allowed -> Toast.makeText(context, "本账号未开通该章节", Toast.LENGTH_SHORT).show()
                !unlocked -> Toast.makeText(context, "完成上一章全部测验后解锁", Toast.LENGTH_SHORT).show()
                key.isNotBlank() -> onOpenReader(key)
            }
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 序号圆：完成=叶绿勾 / 解锁=陶土序号 / 锁定=灰锁
            val (bg, fg) = when {
                chapter.completed == true -> MeiliPalette.LeafSoft to MeiliPalette.LeafText
                unlocked && allowed -> MeiliPalette.ClaySoft to MeiliPalette.ClayDeep
                else -> MeiliPalette.SurfaceSoft to MeiliPalette.Ink4
            }
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(bg, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    chapter.completed == true -> Icon(MeiliIcons.Check, null, tint = fg, modifier = Modifier.size(18.dp))
                    !unlocked || !allowed -> Icon(MeiliIcons.Lock, null, tint = fg, modifier = Modifier.size(16.dp))
                    else -> Text("$index", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = fg)
                }
            }
            Spacer(Modifier.width(Dimens.S3))
            Column(Modifier.weight(1f)) {
                Text(
                    chapter.title ?: key,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = if (unlocked && allowed) MeiliPalette.Ink else MeiliPalette.Ink3,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    when {
                        !allowed -> "未开通"
                        !unlocked -> "完成上一章后解锁"
                        chapter.completed == true -> "已完成 · 可随时复习"
                        else -> "点击阅读课件"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MeiliPalette.Ink4,
                )
            }
            if (unlocked && allowed) {
                Icon(MeiliIcons.ChevRight, null, tint = MeiliPalette.Ink4, modifier = Modifier.size(Dimens.IconSm))
            }
        }

        // 解锁章：三套测验 pill（分数/未考），点击进测验
        if (unlocked && allowed && key.isNotBlank()) {
            Spacer(Modifier.height(Dimens.S3))
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.S2)) {
                (1..3).forEach { qi ->
                    val score = chapter.quizScore(qi)
                    val qInteraction = remember { MutableInteractionSource() }
                    StatusPill(
                        text = if (score != null) "测验$qi · ${score}分" else "测验$qi · 未考",
                        kind = when {
                            score == null -> PillKind.Neutral
                            score >= 60 -> PillKind.Ok
                            else -> PillKind.Warn
                        },
                        modifier = Modifier.clickable(interactionSource = qInteraction, indication = null) {
                            onOpenQuiz(key, qi)
                        },
                    )
                }
            }
        }
    }
}
