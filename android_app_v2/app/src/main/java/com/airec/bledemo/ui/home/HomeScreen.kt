package com.airec.bledemo.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.airec.bledemo.designsystem.Dimens
import com.airec.bledemo.designsystem.MeiliIcons
import com.airec.bledemo.designsystem.MeiliPalette
import com.airec.bledemo.designsystem.MeiliShapes
import com.airec.bledemo.designsystem.MeiliTheme
import com.airec.bledemo.designsystem.components.CompanionStage
import com.airec.bledemo.designsystem.components.MeiliCard
import com.airec.bledemo.designsystem.components.SectionLabel
import com.airec.bledemo.recording.CompanionSource
import com.airec.bledemo.recording.RecordingState

/**
 * 陪伴首页（SPEC §4.2 / warm_2 #home）。
 *
 * 顶部问候 + 「美丽陪伴」+ 陪伴师 chip；中部大「点击开启陪伴」圆钮（[CompanionStage]：
 * 进行中=呼吸态「陪伴进行中」+计时，结束=「结束陪伴」）；陪伴来源 手机/陪伴笔分段切换；
 * 陪伴笔状态卡（已连接·电量 / 未连接 + 「从陪伴笔同步」入口）；待传/失败 badge。
 *
 * 数据：[HomeViewModel] —— repo.penBinding()(绑定) / repo.pending()(待传计数) + RecordingController 实时态。
 *
 * @param onOpenPending 跳「待整理」（「未选择顾客的陪伴」入口 + 待传 badge 点击）
 * @param onBindCustomer 陪伴成功结束后→绑定该段(recordingId)到顾客（引擎回传 lastRecordingId 时触发）
 * @param onOpenReception 跳「今日接诊」（「看看今天的陪伴」入口）；默认空实现
 * @param onOpenReminders 跳「提醒」（顶部铃铛）；默认空实现
 * @param onOpenPenSync 「从陪伴笔同步」点击 → 上层唤起陪伴笔机身记录 sheet；默认空实现
 * @param modifier 由 AppScaffold 传入（含底栏避让 padding）
 */
@Composable
fun HomeScreen(
    onOpenPending: () -> Unit = {},
    onBindCustomer: (recordingId: Long) -> Unit = {},
    onOpenReception: () -> Unit = {},
    onOpenReminders: () -> Unit = {},
    onOpenPenSync: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = viewModel(),
) {
    val header by viewModel.header.collectAsStateWithLifecycle()
    val companion by viewModel.companion.collectAsStateWithLifecycle()
    val source by viewModel.source.collectAsStateWithLifecycle()
    val pendingOnServer by viewModel.pendingOnServer.collectAsStateWithLifecycle()
    val reminderCount by viewModel.reminderCount.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()

    // 标记是否已经历过首个 onResume（首拉已覆盖，故首个 resume 不再重复联网）。
    val resumedOnce = remember { androidx.compose.runtime.mutableStateOf(false) }

    // 进首页拉取问候 / 笔绑定 / 待整理计数 + 提醒未读计数（仅首拉一次，切回 tab 不重复联网）。
    LaunchedEffect(Unit) { viewModel.refreshOnEnter() }

    // 提醒红点轮询（对齐 web setInterval(loadReminders, 120000)）：每 120s 刷新一次未读计数。
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(120_000)
            viewModel.refreshReminders()
        }
    }

    // 回前台刷新（对齐 web visibilitychange/pageshow）：每次本页 onResume 重拉提醒计数，体现「即时-1」。
    // 首拉已由 refreshOnEnter 覆盖，这里跳过首个 ON_RESUME 以免与首拉重复联网。
    LifecycleResumeEffect(Unit) {
        if (resumedOnce.value) viewModel.refreshReminders() else resumedOnce.value = true
        onPauseOrDispose { }
    }

    // 接入共享录音引擎：首页是登录后入口，此处会话 Cookie 已就绪 → 注入上传上下文 + 接控制器（幂等）。
    LaunchedEffect(Unit) {
        com.airec.bledemo.recording.RecordingModule.refreshUploadContext()
        viewModel.attachController(com.airec.bledemo.recording.RecordingModule.controller)
    }
    // 陪伴结束不再强制跳绑定：片段已入「待整理」，由 ViewModel 一次性 toast 提示，可随时去绑（不串行）。

    // 引擎/交互产生的提示（失败段、已保存可去绑定、笔没开机/连接不稳/「开机自动录制」被关等）→ 原生 Toast 弹出。
    // 之前这里只 consume 不展示，导致所有提示被静默吞掉；现对齐旧宿主的 Toast.makeText 真正告诉用户。
    val toastContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(toast) {
        toast?.let {
            android.widget.Toast.makeText(toastContext, it, android.widget.Toast.LENGTH_LONG).show()
            viewModel.consumeToast()
        }
    }

    HomeContent(
        header = header,
        companion = companion,
        source = source,
        pendingOnServer = pendingOnServer,
        reminderCount = reminderCount,
        onToggleCompanion = viewModel::toggleCompanion,
        onPickSource = viewModel::pickSource,
        onRetryUploads = viewModel::retryUploads,
        onOpenPending = onOpenPending,
        onOpenReception = onOpenReception,
        onOpenReminders = onOpenReminders,
        onOpenPenSync = {
            if (viewModel.onPenSyncClicked()) onOpenPenSync()
        },
        onOpenSettings = onOpenSettings,
        modifier = modifier,
    )
}

/** 纯展示层（无 VM 依赖，便于 @Preview 各态）。 */
@Composable
private fun HomeContent(
    header: HomeHeader,
    companion: CompanionUiState,
    source: CompanionSource,
    pendingOnServer: Int,
    reminderCount: Int = 0,
    onToggleCompanion: () -> Unit,
    onPickSource: (CompanionSource) -> Unit,
    onRetryUploads: () -> Unit,
    onOpenPending: () -> Unit,
    onOpenReception: () -> Unit,
    onOpenReminders: () -> Unit,
    onOpenPenSync: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Dimens.ScreenH)
            .padding(bottom = Dimens.S6),
    ) {
        AppHeader(
            header = header,
            reminderCount = reminderCount,
            onOpenReminders = onOpenReminders,
            onOpenSettings = onOpenSettings,
        )

        CompanionCard(
            companion = companion,
            source = source,
            onToggleCompanion = onToggleCompanion,
            onPickSource = onPickSource,
            onRetryUploads = onRetryUploads,
            onOpenPenSync = onOpenPenSync,
        )

        EntryTile(
            icon = MeiliIcons.Reception,
            background = MeiliPalette.ClayTint,
            border = MeiliPalette.ClaySoft,
            title = "看看今天的陪伴",
            subtitle = "所有接诊记录、分析报告、点评",
            onClick = onOpenReception,
        )
        EntryTile(
            icon = MeiliIcons.Tidy,
            background = MeiliPalette.SageTint,
            border = MeiliPalette.SageSoft,
            title = "未选择顾客的陪伴",
            subtitle = pendingSubtitle(pendingOnServer),
            onClick = onOpenPending,
        )
    }
}

// ─────────────────────────── 顶部 appbar ───────────────────────────

/** .appbar：问候 + 「美丽陪伴」衬线标题 + 陪伴师 chip / 名·店；右侧主题 + 提醒铃（带 badge）。 */
@Composable
private fun AppHeader(
    header: HomeHeader,
    reminderCount: Int,
    onOpenReminders: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Dimens.S2, bottom = Dimens.S4),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f, fill = false)) {
            Text(
                text = greeting() + "，",
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = MeiliPalette.Ink2,
            )
            Text(
                text = "美丽陪伴",
                style = MaterialTheme.typography.displaySmall,
                color = MeiliPalette.ClayDeep,
                modifier = Modifier.padding(top = 3.dp),
            )
            Row(
                modifier = Modifier.padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                RoleChip()
                val sub = header.subtitleText()
                if (sub.isNotBlank()) {
                    Text(
                        text = sub,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = MeiliPalette.Ink3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButtonBox(icon = MeiliIcons.Palette, onClick = onOpenSettings)
            // 铃铛 + 未读红点 badge（对齐 web reminderDot）：0 不显示。
            ReminderBell(count = reminderCount, onClick = onOpenReminders)
        }
    }
}

/** 提醒铃铛：复用 [IconButtonBox]，右上角叠加未读计数红点（对齐 web 的 #reminderDot）。 */
@Composable
private fun ReminderBell(
    count: Int,
    onClick: () -> Unit,
) {
    Box {
        IconButtonBox(icon = MeiliIcons.Reminder, onClick = onClick)
        if (count > 0) {
            ReminderBadge(
                count = count,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 5.dp, y = (-5).dp),
            )
        }
    }
}

/**
 * 未读计数小红点（暖玉柔光：玫瑰陶土底 + 白字，不用刺眼正红）。
 * 0 由调用方拦掉；>99 显示「99+」。单数字时呈正圆，多位时呈胶囊（min 宽兜底）。
 */
@Composable
private fun ReminderBadge(
    count: Int,
    modifier: Modifier = Modifier,
) {
    val label = if (count > 99) "99+" else count.toString()
    Box(
        modifier = modifier
            .heightIn(min = 16.dp)
            .widthIn(min = 16.dp)
            .clip(MeiliShapes.Pill)
            .background(MeiliPalette.Rose)
            .padding(horizontal = if (label.length > 1) 4.dp else 0.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 10.sp,
                letterSpacing = 0.sp,
            ),
            color = MeiliPalette.OnInk,
            maxLines = 1,
        )
    }
}

/** .rolechip：鼠尾草 tint 胶囊 + 人像图标 + 「陪伴师」。 */
@Composable
private fun RoleChip() {
    Surface(
        shape = MeiliShapes.Pill,
        color = MeiliPalette.SageTint,
        contentColor = MeiliPalette.SageDeep,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(MeiliIcons.Profile, contentDescription = null, modifier = Modifier.size(13.dp))
            Text(
                text = "陪伴师",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.sp,
                ),
            )
        }
    }
}

/** .iconbtn：44 方圆角、surface 底、细描边、轻浮起。 */
@Composable
private fun IconButtonBox(
    icon: ImageVector,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        shape = MeiliShapes.Sm,
        color = MeiliPalette.Surface,
        contentColor = MeiliPalette.Ink2,
        border = androidx.compose.foundation.BorderStroke(Dimens.BorderThin, MeiliPalette.Line),
        shadowElevation = Dimens.Elev1,
        modifier = Modifier
            .size(Dimens.IconButton)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(Dimens.Icon))
        }
    }
}

// ─────────────────────────── 陪伴卡（圆钮 + 来源 + badge + 笔状态） ───────────────────────────

@Composable
private fun CompanionCard(
    companion: CompanionUiState,
    source: CompanionSource,
    onToggleCompanion: () -> Unit,
    onPickSource: (CompanionSource) -> Unit,
    onRetryUploads: () -> Unit,
    onOpenPenSync: () -> Unit,
) {
    // .comp-card：顶部柔光高光的渐变底。
    val compBrush = Brush.radialGradient(
        colors = listOf(MeiliPalette.ClayTint, MeiliPalette.Surface),
        radius = 520f,
    )
    MeiliCard(
        modifier = Modifier.padding(bottom = Dimens.CardGap),
        padded = false,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(compBrush)
                .padding(Dimens.CardPad),
        ) {
            CompanionStage(
                timerText = formatTimer(companion.elapsedSec),
                statusText = companion.statusText(source),
                hint = companion.hintText(),
                live = companion.live,
                onToggle = onToggleCompanion,
                starting = companion.starting,
                enabled = !companion.uploading,
                modifier = Modifier.fillMaxWidth(),
            )

            HrLine(modifier = Modifier.padding(vertical = 15.dp))

            // ── 陪伴设备 ──
            SectionLabel(
                text = "陪伴设备",
                icon = MeiliIcons.Wifi,
                modifier = Modifier.padding(bottom = 9.dp),
            )
            SourceRow(
                source = source,
                penConnected = companion.penConnected,
                onPickSource = onPickSource,
            )

            // ── 待传 / 失败 badge（同步中 N%、失败可点重试）──
            if (companion.uploading || companion.pendingCount > 0) {
                SyncBadge(
                    count = if (companion.pendingCount > 0) companion.pendingCount else 1,
                    percent = companion.progressPercent,
                    modifier = Modifier.padding(top = 13.dp),
                )
            }
            if (companion.failedCount > 0) {
                FailBadge(
                    count = companion.failedCount,
                    onClick = onRetryUploads,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            // ── 陪伴笔状态 + 从陪伴笔同步入口 ──
            PenStatusLine(
                penConnected = companion.penConnected,
                modifier = Modifier.padding(top = 15.dp),
            )
            PenSyncRow(
                onClick = onOpenPenSync,
                modifier = Modifier.padding(top = 13.dp),
            )
        }
    }
}

/** .src-row：两个等分来源卡（陪伴笔 / 手机麦克风），选中态描边陶土。 */
@Composable
private fun SourceRow(
    source: CompanionSource,
    penConnected: Boolean,
    onPickSource: (CompanionSource) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        SourceCell(
            icon = MeiliIcons.Pen,
            title = "陪伴笔",
            sub = if (penConnected) "已连接" else "未连接",
            subDot = penConnected,
            selected = source == CompanionSource.Pen,
            onClick = { onPickSource(CompanionSource.Pen) },
            modifier = Modifier.weight(1f),
        )
        SourceCell(
            icon = MeiliIcons.Phone,
            title = "手机麦克风",
            sub = "手机采集",
            subDot = false,
            selected = source == CompanionSource.Phone,
            onClick = { onPickSource(CompanionSource.Phone) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SourceCell(
    icon: ImageVector,
    title: String,
    sub: String,
    subDot: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        modifier = modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick),
        shape = MeiliShapes.Sm,
        color = if (selected) MeiliPalette.Surface else MeiliPalette.SurfaceSoft,
        border = androidx.compose.foundation.BorderStroke(
            Dimens.BorderField,
            if (selected) MeiliPalette.Clay else MeiliPalette.Line,
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MeiliPalette.ClayDeep,
                modifier = Modifier.size(Dimens.Icon),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.sp),
                color = MeiliPalette.Ink,
                modifier = Modifier.padding(top = 5.dp),
            )
            Row(
                modifier = Modifier.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (subDot) GreenDot()
                Text(
                    text = sub,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = MeiliPalette.Ink3,
                )
            }
        }
    }
}

/** .pill.clay：后台同步中 N 段… 60%。 */
@Composable
private fun SyncBadge(
    count: Int,
    percent: Int,
    modifier: Modifier = Modifier,
) {
    InlinePill(
        modifier = modifier,
        bg = MeiliPalette.ClayTint,
        fg = MeiliPalette.ClayDeep,
        icon = MeiliIcons.Sync,
    ) {
        Text(
            text = "$count 段后台同步中…",
            style = pillTextStyle(),
        )
        if (percent in 1..99) {
            Text(
                text = " $percent%",
                style = pillTextStyle(),
                color = MeiliPalette.ClayDeep.copy(alpha = 0.75f),
            )
        }
    }
}

/** .pill.fail：N 段没保存成功，点击重新尝试同步。 */
@Composable
private fun FailBadge(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    InlinePill(
        modifier = modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick),
        bg = MeiliPalette.RoseSoft,
        fg = MeiliPalette.RoseText,
        icon = MeiliIcons.Warn,
    ) {
        Text(
            text = "$count 段没保存成功，点击重试",
            style = pillTextStyle(),
        )
    }
}

@Composable
private fun InlinePill(
    modifier: Modifier = Modifier,
    bg: androidx.compose.ui.graphics.Color,
    fg: androidx.compose.ui.graphics.Color,
    icon: ImageVector,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = MeiliShapes.Pill,
        color = bg,
        contentColor = fg,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(13.dp))
            content()
        }
    }
}

/** 陪伴笔已连接·电量 / 未连接 文字行（warm_2 用 dotg + 文字；电量未知时省略）。 */
@Composable
private fun PenStatusLine(
    penConnected: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (penConnected) {
            GreenDot()
            Text(
                text = "陪伴笔 已连接",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.5f.sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = MeiliPalette.Ink2,
            )
        } else {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(MeiliPalette.Ink4, CircleShape),
            )
            Text(
                text = "陪伴笔 未连接",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.5f.sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = MeiliPalette.Ink2,
            )
        }
    }
}

/** .penrow：陶土 tint 行，「从陪伴笔同步」入口（拉机身保存、未导入片段）。 */
@Composable
private fun PenSyncRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        shape = MeiliShapes.Md,
        color = MeiliPalette.ClayTint,
        border = androidx.compose.foundation.BorderStroke(Dimens.BorderThin, MeiliPalette.ClaySoft),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = MeiliShapes.Xs,
                color = MeiliPalette.Surface,
                contentColor = MeiliPalette.ClayDeep,
                shadowElevation = Dimens.Elev1,
                modifier = Modifier.size(38.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(MeiliIcons.Sync, contentDescription = null, modifier = Modifier.size(Dimens.IconSm))
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "从陪伴笔同步",
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.5f.sp),
                    color = MeiliPalette.ClayDeep,
                )
                Text(
                    text = "拉取陪伴笔机身保存、还没导入的片段",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = MeiliPalette.Ink2,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Icon(
                MeiliIcons.ChevRight,
                contentDescription = null,
                tint = MeiliPalette.Clay,
                modifier = Modifier.size(Dimens.Icon),
            )
        }
    }
}

// ─────────────────────────── 大入口 tile ───────────────────────────

/** .entry：渐变底大入口（看看今天的陪伴 / 未选择顾客的陪伴）。 */
@Composable
private fun EntryTile(
    icon: ImageVector,
    background: androidx.compose.ui.graphics.Color,
    border: androidx.compose.ui.graphics.Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Dimens.S3)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        shape = MeiliShapes.Lg,
        color = background,
        border = androidx.compose.foundation.BorderStroke(Dimens.BorderThin, border),
        shadowElevation = Dimens.Elev2,
    ) {
        Row(
            modifier = Modifier.padding(Dimens.S4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(15.dp),
        ) {
            Surface(
                shape = MeiliShapes.Md,
                color = MeiliPalette.Surface,
                contentColor = MeiliPalette.ClayDeep,
                shadowElevation = Dimens.Elev1,
                modifier = Modifier.size(50.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(Dimens.IconLg))
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MeiliPalette.Ink,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5f.sp),
                    color = MeiliPalette.Ink2,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            Icon(
                MeiliIcons.ChevRight,
                contentDescription = null,
                tint = MeiliPalette.Clay,
                modifier = Modifier.size(Dimens.Icon),
            )
        }
    }
}

// ─────────────────────────── 小件 / 工具 ───────────────────────────

/** .dotg：叶绿圆点 + 浅晕。 */
@Composable
private fun GreenDot() {
    Box(
        modifier = Modifier
            .size(13.dp)
            .clip(CircleShape)
            .background(MeiliPalette.LeafSoft),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(MeiliPalette.Leaf, CircleShape),
        )
    }
}

/** .hr：line-soft 细分隔。 */
@Composable
private fun HrLine(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MeiliPalette.LineSoft),
    )
}

@Composable
private fun pillTextStyle() = MaterialTheme.typography.labelMedium.copy(
    fontSize = 11.5f.sp,
    fontWeight = FontWeight.Bold,
    letterSpacing = 0.sp,
)

/** 待整理入口副标题：有数则「… · N 段待整理」。 */
private fun pendingSubtitle(count: Int): String =
    if (count > 0) "试听后绑定到对应顾客 · $count 段待整理" else "试听后绑定到对应顾客"

/** 简单时段问候（不依赖系统语言；上午/下午/晚上）。 */
private fun greeting(): String {
    val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    return when (h) {
        in 5..10 -> "早上好"
        in 11..12 -> "中午好"
        in 13..17 -> "下午好"
        else -> "晚上好"
    }
}

/** 秒 → mm:ss / h:mm:ss。 */
private fun formatTimer(totalSec: Int): String {
    val s = totalSec.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) {
        "%d:%02d:%02d".format(h, m, sec)
    } else {
        "%02d:%02d".format(m, sec)
    }
}

private fun HomeHeader.subtitleText(): String {
    val name = advisorName?.takeIf { it.isNotBlank() }
    val store = storeName?.takeIf { it.isNotBlank() }
    return listOfNotNull(name, store).joinToString(" · ")
}

/** 圆钮上方状态文案：唤醒中/进行中/暂停/上传中/空闲（守红线，绝不出现「录音」）。 */
private fun CompanionUiState.statusText(source: CompanionSource): String = when (val st = state) {
    // 唤醒/连接中：用引擎附带文案（「正在连接陪伴笔…」/「正在唤醒陪伴笔…」），无则给个默认。
    is RecordingState.Recording -> if (st.starting) (st.message ?: "正在唤醒陪伴笔…") else "陪伴进行中"
    is RecordingState.Paused -> "陪伴已暂停"
    is RecordingState.Uploading -> "正在保存这次陪伴…"
    // 空闲：选了陪伴笔但还没连上 → 先提示连接（对齐 web 进页/掉线时的「请先连接录音笔」），否则给开启引导。
    is RecordingState.Idle -> st.message ?: st.errorMessage
        ?: if (source == CompanionSource.Pen && !penConnected) "请先连接陪伴笔" else "点击下方 · 开启今天的陪伴"
}

/** 圆钮下方小提示。 */
private fun CompanionUiState.hintText(): String = when (state) {
    // 唤醒中：还没真开录，提示「可取消」(对齐 starting 态点击=取消)，不说「正在记录」。
    is RecordingState.Recording -> if (starting) "正在准备这次陪伴 · 再次点击可取消。" else "正在温柔记录这次陪伴 · 结束后绑定顾客即可。"
    is RecordingState.Paused -> "正在温柔记录这次陪伴 · 结束后绑定顾客即可。"
    is RecordingState.Uploading -> "稍候片刻，保存完成后即可绑定顾客。"
    is RecordingState.Idle -> "陪伴结束后，请把这段陪伴绑定到今日接诊里的顾客。"
}

// ─────────────────────────── Preview ───────────────────────────

@Preview(showBackground = true, backgroundColor = 0xFFF8F3ED, widthDp = 360, heightDp = 860)
@Composable
private fun HomeIdlePreview() {
    MeiliTheme {
        HomeContent(
            header = HomeHeader(advisorName = "张敏", storeName = "朝阳旗舰店", loaded = true),
            companion = CompanionUiState(penConnected = true),
            source = CompanionSource.Pen,
            pendingOnServer = 2,
            reminderCount = 3,
            onToggleCompanion = {},
            onPickSource = {},
            onRetryUploads = {},
            onOpenPending = {},
            onOpenReception = {},
            onOpenReminders = {},
            onOpenPenSync = {},
            onOpenSettings = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF8F3ED, widthDp = 360, heightDp = 860)
@Composable
private fun HomeLivePreview() {
    MeiliTheme {
        HomeContent(
            header = HomeHeader(advisorName = "张敏", storeName = "朝阳旗舰店", loaded = true),
            companion = CompanionUiState(
                state = RecordingState.Recording(durationSec = 754, source = CompanionSource.Pen),
                penConnected = true,
                pendingCount = 2,
                failedCount = 1,
                progressPercent = 60,
            ),
            source = CompanionSource.Pen,
            pendingOnServer = 2,
            onToggleCompanion = {},
            onPickSource = {},
            onRetryUploads = {},
            onOpenPending = {},
            onOpenReception = {},
            onOpenReminders = {},
            onOpenPenSync = {},
            onOpenSettings = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF8F3ED, widthDp = 360, heightDp = 860)
@Composable
private fun HomePenOfflinePreview() {
    MeiliTheme {
        HomeContent(
            header = HomeHeader(advisorName = "张敏", storeName = "朝阳旗舰店", loaded = true),
            companion = CompanionUiState(
                state = RecordingState.Uploading(durationSec = 0, message = "正在保存…"),
                penConnected = false,
                pendingCount = 1,
                progressPercent = 30,
            ),
            source = CompanionSource.Phone,
            pendingOnServer = 0,
            onToggleCompanion = {},
            onPickSource = {},
            onRetryUploads = {},
            onOpenPending = {},
            onOpenReception = {},
            onOpenReminders = {},
            onOpenPenSync = {},
            onOpenSettings = {},
        )
    }
}
