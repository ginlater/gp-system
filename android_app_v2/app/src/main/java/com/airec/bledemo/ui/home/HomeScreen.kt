package com.airec.bledemo.ui.home

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.airec.bledemo.designsystem.Dimens
import com.airec.bledemo.designsystem.MeiliIcons
import com.airec.bledemo.designsystem.MeiliPalette
import com.airec.bledemo.designsystem.MeiliShapes
import com.airec.bledemo.designsystem.MeiliTheme
import com.airec.bledemo.designsystem.components.MeiliCard
import com.airec.bledemo.designsystem.components.SectionLabel
import com.airec.bledemo.recording.CompanionSource
import com.airec.bledemo.recording.RecordingState

/**
 * 陪伴首页（SPEC §4.2 / redesign_2026-06-13 方案A #home）。
 *
 * 顶部问候 + 「美丽陪伴」+ 陪伴师 chip（右侧仅一个设置齿轮）；陪伴卡改为「紧凑横排」：
 * 左侧小圆钮（空闲=陪伴渐变+并蒂花蕊，进行中=呼吸玫瑰渐变+白色停止方块），右侧计时 + 状态文案；
 * 细分隔下保留 陪伴笔 / 手机麦克风 来源切换 + 待传/失败 badge。卡片下方两个 tile：
 * 「提醒」（铃 + 未读红点）与「今天的接诊与待整理」。
 *
 * 数据：[HomeViewModel] —— repo 兜底（笔绑定 / 待传 / 提醒计数）+ RecordingController 实时态。
 *
 * @param onOpenReception 跳「接诊（接诊+待整理合并）」入口；默认空实现
 * @param onBindCustomer 陪伴成功结束后→绑定该段(recordingId)到顾客（引擎回传 lastRecordingId 时触发）
 * @param onOpenReminders 跳「提醒」（提醒 tile）；默认空实现
 * @param onOpenSettings 右上角设置齿轮；默认空实现
 * @param onSwitchSystem 多系统切换（切换器弹窗选中其它系统时回调 key；单系统账号图标不显示）
 * @param modifier 由 AppScaffold 传入（含底栏避让 padding）
 */
@Composable
fun HomeScreen(
    onOpenReception: () -> Unit = {},
    onBindCustomer: (recordingId: Long) -> Unit = {},
    onOpenReminders: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onSwitchSystem: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = viewModel(),
) {
    // 多系统账号才显示顶栏「切换工作台」宫格图标（lastMe 登录时已缓存，读一次即可）
    val multiSystem = remember { com.airec.bledemo.data.auth.AuthManager.lastMe?.multiSystem == true }
    val switcherOpen = remember { androidx.compose.runtime.mutableStateOf(false) }
    val header by viewModel.header.collectAsStateWithLifecycle()
    val companion by viewModel.companion.collectAsStateWithLifecycle()
    val source by viewModel.source.collectAsStateWithLifecycle()
    val recPerm by viewModel.recPerm.collectAsStateWithLifecycle()
    val reminderCount by viewModel.reminderCount.collectAsStateWithLifecycle()
    val receptionStats by viewModel.receptionStats.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()
    val pendingBindRecId by viewModel.pendingBindRecId.collectAsStateWithLifecycle()

    // 录完一段 → 直接跳转到绑定页（不必等上传完成；强制绑定，不给「稍后」逃避，避免顾问多录几段忘绑）。
    // 一次性事件：先消费置空（避免回首页重复跳转），再跳转。
    LaunchedEffect(pendingBindRecId) {
        pendingBindRecId?.let { rid ->
            viewModel.consumeBindPrompt()
            onBindCustomer(rid)
        }
    }

    // 标记是否已经历过首个 onResume（首拉已覆盖，故首个 resume 不再重复联网）。
    val resumedOnce = remember { androidx.compose.runtime.mutableStateOf(false) }

    // 进首页拉取问候 / 笔绑定 / 待整理计数 + 提醒未读计数（仅首拉一次，切回 tab 不重复联网）。
    LaunchedEffect(Unit) { viewModel.refreshOnEnter() }

    // 提醒红点轮询（对齐 web setInterval(loadReminders, 120000)）：每 120s 刷新一次未读计数。
    // F2：随生命周期暂停——锁屏/切后台不再空转联网。
    val pollLifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(Unit) {
        pollLifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            while (true) {
                kotlinx.coroutines.delay(120_000)
                viewModel.refreshReminders()
            }
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

    // ★2026-07-16 隐私合规:权限改为「谁用谁申请、申请前先说明」(原来 App 一启动就一次性申请,
    //   被应用商店隐私检测驳回:①过度申请 ②未告知目的)。这里在真正用到功能的那一刻才要权限。
    val permGate = com.airec.bledemo.permission.rememberPermissionGate()

    HomeContent(
        header = header,
        companion = companion,
        source = source,
        recPerm = recPerm,
        reminderCount = reminderCount,
        receptionStats = receptionStats,
        onToggleCompanion = {
            // 手机麦录音才要麦克风权限;用陪伴笔录音由笔采集,不需要手机麦克风。
            // 停止录音时也不该要权限(已在录=权限早就有了)。
            val needMic = source == com.airec.bledemo.recording.CompanionSource.Phone &&
                companion.state is com.airec.bledemo.recording.RecordingState.Idle
            if (needMic) {
                permGate.require(
                    com.airec.bledemo.permission.PermissionPurpose.Record,
                    onDenied = { viewModel.onPermissionDenied("麦克风") },
                ) { viewModel.toggleCompanion() }
            } else {
                viewModel.toggleCompanion()
            }
        },
        onPickSource = { src ->
            // 选「陪伴笔」会立刻去连蓝牙 → 先要蓝牙权限(安卓11-附带定位,说明框里已写清用途)
            if (src == com.airec.bledemo.recording.CompanionSource.Pen) {
                permGate.require(
                    com.airec.bledemo.permission.PermissionPurpose.Pen,
                    onDenied = { viewModel.onPermissionDenied("蓝牙") },
                ) { viewModel.pickSource(src) }
            } else {
                viewModel.pickSource(src)
            }
        },
        onRetryUploads = viewModel::retryUploads,
        onOpenReception = onOpenReception,
        onOpenReminders = onOpenReminders,
        onOpenSettings = onOpenSettings,
        showSwitcher = multiSystem,
        onOpenSwitcher = { switcherOpen.value = true },
        modifier = modifier,
    )

    // 多系统切换弹窗（从工牌切去网课等，不用退出重登）
    com.airec.bledemo.ui.workspace.SystemSwitcherSheet(
        visible = switcherOpen.value,
        currentKey = "gongpai",
        onDismiss = { switcherOpen.value = false },
        onSwitch = onSwitchSystem,
    )
}

/** 纯展示层（无 VM 依赖，便于 @Preview 各态）。 */
@Composable
private fun HomeContent(
    header: HomeHeader,
    companion: CompanionUiState,
    source: CompanionSource,
    recPerm: RecPerm = RecPerm(),
    reminderCount: Int = 0,
    receptionStats: ReceptionStats = ReceptionStats(),
    onToggleCompanion: () -> Unit,
    onPickSource: (CompanionSource) -> Unit,
    onRetryUploads: () -> Unit,
    onOpenReception: () -> Unit,
    onOpenReminders: () -> Unit,
    onOpenSettings: () -> Unit,
    showSwitcher: Boolean = false,
    onOpenSwitcher: () -> Unit = {},
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
            onOpenSettings = onOpenSettings,
            showSwitcher = showSwitcher,
            onOpenSwitcher = onOpenSwitcher,
        )

        CompanionCard(
            companion = companion,
            source = source,
            recPerm = recPerm,
            onToggleCompanion = onToggleCompanion,
            onPickSource = onPickSource,
            onRetryUploads = onRetryUploads,
        )

        // ── 提醒 tile（铃 + 未读红点 badge；0 不显示 badge）──
        ReminderTile(
            count = reminderCount,
            onClick = onOpenReminders,
        )
        // ── 今日接诊 + 待整理（合并入口）──
        EntryTile(
            icon = MeiliIcons.Reception,
            background = MeiliPalette.SageTint,
            border = MeiliPalette.SageSoft,
            title = "今天的接诊与待整理",
            subtitle = receptionSubtitle(receptionStats),
            onClick = onOpenReception,
        )
    }
}

// ─────────────────────────── 顶部 appbar ───────────────────────────

/** .appbar：问候 + 「美丽陪伴」衬线标题 + 陪伴师 chip / 名·店；右侧（多系统时）工作台宫格 + 设置齿轮。 */
@Composable
private fun AppHeader(
    header: HomeHeader,
    onOpenSettings: () -> Unit,
    showSwitcher: Boolean = false,
    onOpenSwitcher: () -> Unit = {},
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
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            if (showSwitcher) {
                IconButtonBox(icon = MeiliIcons.Workspace, onClick = onOpenSwitcher)
            }
            IconButtonBox(icon = MeiliIcons.Settings, onClick = onOpenSettings)
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

// ─────────────────────────── 陪伴卡（紧凑横排：小圆钮 + 计时/状态 + 来源 + badge） ───────────────────────────

@Composable
private fun CompanionCard(
    companion: CompanionUiState,
    source: CompanionSource,
    recPerm: RecPerm,
    onToggleCompanion: () -> Unit,
    onPickSource: (CompanionSource) -> Unit,
    onRetryUploads: () -> Unit,
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
            // ── .rec-mini：左小圆钮 + 右计时/状态 ──
            CompactCompanionRow(
                companion = companion,
                source = source,
                onToggle = onToggleCompanion,
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
                penBattery = companion.penBattery,
                recPerm = recPerm,
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
        }
    }
}

/**
 * .rec-mini：紧凑横排——左侧小圆钮（[CompactCompanionButton]），右侧计时 + 状态文案列。
 * 取代旧的居中大舞台；点击圆钮仍走同一个 [onToggle]（toggleCompanion）。
 */
@Composable
private fun CompactCompanionRow(
    companion: CompanionUiState,
    source: CompanionSource,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CompactCompanionButton(
            live = companion.live,
            starting = companion.starting,
            // Z1改B（用户拍板）：保存中也能点——直接开录新段，上一段后台继续传（录音优先级最高）
            enabled = true,
            onClick = onToggle,
        )
        Column(modifier = Modifier.weight(1f)) {
            if (companion.syncingTime) {
                // 重连到已在录的笔、真实时长同步中：计时位置先显示提示，别让用户看到从 0 往上跳的假时间。
                Text(
                    text = "正在同步小伙伴时间…",
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold, lineHeight = 36.sp),
                    color = MeiliPalette.Ink2,
                    maxLines = 1,
                )
            } else {
                Text(
                    text = formatTimer(companion.elapsedSec),
                    style = MeiliTheme.timerStyle.copy(fontSize = 32.sp, lineHeight = 36.sp, letterSpacing = 1.sp),
                    color = MeiliPalette.Ink,
                    maxLines = 1,
                )
            }
            Row(
                modifier = Modifier.padding(top = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                if (companion.live) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(MeiliPalette.Rose, CircleShape),
                    )
                }
                Text(
                    text = companion.statusText(source),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MeiliPalette.Ink2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 紧凑版陪伴小圆钮（~80dp）。复用 [MeiliPalette.CompanionGradient]（空闲）/
 * [MeiliPalette.CompanionLiveGradient]（进行中），进行中/唤醒中加轻微呼吸缩放：
 *  - 空闲：陪伴渐变 + 并蒂花蕊（[MeiliIcons.Companion]）。
 *  - 进行中：玫瑰渐变 + 白色停止方块（comp-square 缩比例 ~22dp）。
 *  - 唤醒/连接中：玫瑰渐变 + 白色转圈，点击=取消。
 *  - 保存中：置灰半透明不可点。
 *
 * 红线：绝不出现「录音/录制」字样或图形（停止用纯方块，不带「REC」）。
 */
@Composable
private fun CompactCompanionButton(
    live: Boolean,
    starting: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val active = live || starting

    // 呼吸态：轻微缩放脉动（对齐大圆钮 breathe，幅度收小适配 80dp）。
    val transition = rememberInfiniteTransition(label = "breatheMini")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (active) 1.05f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1300),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    Box(
        modifier = Modifier
            .size(80.dp)
            .scale((if (pressed) 0.96f else 1f) * pulse)
            .alpha(if (enabled) 1f else 0.5f)
            .background(
                brush = if (active) MeiliPalette.CompanionLiveGradient else MeiliPalette.CompanionGradient,
                shape = CircleShape,
            )
            .border(1.5.dp, Color.White.copy(alpha = 0.45f), CircleShape)
            .then(
                if (enabled) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        when {
            starting -> CircularProgressIndicator(
                modifier = Modifier.size(26.dp),
                color = Color.White,
                strokeWidth = 3.dp,
            )
            live -> Box(
                modifier = Modifier
                    .size(22.dp)
                    .background(Color.White, RoundedCornerShape(7.dp)),
            )
            else -> Icon(
                MeiliIcons.Companion,
                contentDescription = "开启陪伴",
                tint = Color.White,
                modifier = Modifier.size(30.dp),
            )
        }
    }
}

/** .src-row：两个等分来源卡（陪伴笔 / 手机麦克风），选中态描边陶土。 */
@Composable
private fun SourceRow(
    source: CompanionSource,
    penConnected: Boolean,
    penBattery: com.airec.bledemo.recording.PenBattery?,
    recPerm: RecPerm,
    onPickSource: (CompanionSource) -> Unit,
) {
    // 已连接时副标题带上电量：「已连接 · 电量78%」/ 充电时「已连接 · 充电中」；电量未知则只显示已连接。
    val penSub = if (penConnected) {
        when {
            penBattery == null -> "已连接"
            penBattery.charging -> "已连接 · 充电中"
            else -> "已连接 · 电量${penBattery.percent}%"
        }
    } else "未连接"
    // 仅展示已开通的来源卡：未开通手机录音→不显示「手机麦克风」；未开通陪伴笔→不显示「陪伴笔」。
    // 两者都未开通时不显示来源选择（后端 upload 也会 403 兜底）。
    if (!recPerm.phone && !recPerm.pen) return
    Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        if (recPerm.pen) {
            SourceCell(
                icon = MeiliIcons.Pen,
                title = "陪伴笔",
                sub = penSub,
                subDot = penConnected,
                selected = source == CompanionSource.Pen,
                onClick = { onPickSource(CompanionSource.Pen) },
                modifier = Modifier.weight(1f),
            )
        }
        if (recPerm.phone) {
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

// ─────────────────────────── 提醒 tile + 大入口 tile ───────────────────────────

/** .entry + badge：提醒入口（铃图标右上叠未读红点；0 不显示 badge）。 */
@Composable
private fun ReminderTile(
    count: Int,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Dimens.S3)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        shape = MeiliShapes.Lg,
        color = MeiliPalette.ClayTint,
        border = androidx.compose.foundation.BorderStroke(Dimens.BorderThin, MeiliPalette.ClaySoft),
        shadowElevation = Dimens.Elev2,
    ) {
        Row(
            modifier = Modifier.padding(Dimens.S4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(15.dp),
        ) {
            Box {
                Surface(
                    shape = MeiliShapes.Md,
                    color = MeiliPalette.Surface,
                    contentColor = MeiliPalette.ClayDeep,
                    shadowElevation = Dimens.Elev1,
                    modifier = Modifier.size(50.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(MeiliIcons.Reminder, contentDescription = null, modifier = Modifier.size(Dimens.IconLg))
                    }
                }
                if (count > 0) {
                    ReminderBadge(
                        count = count,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 6.dp, y = (-6).dp),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "提醒",
                    style = MaterialTheme.typography.titleMedium,
                    color = MeiliPalette.Ink,
                )
                Text(
                    text = "未绑定 / 报告待看 / 需跟进",
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

/**
 * 「今天的接诊与待整理」副标题：待绑定 / 待分析 / 报告 / 客人数 四个实时数字。
 * 首次加载前用静态文案，避免闪「0 段待绑定…」。
 */
private fun receptionSubtitle(s: ReceptionStats): String {
    if (!s.loaded) return "接诊记录、分析报告，以及待绑定的陪伴"
    return "${s.pendingBind} 段待绑定 · ${s.waitingAnalysis} 位待分析 · " +
        "${s.reportsDone} 份报告 · 共 ${s.customers} 位顾客"
}

/** .entry：渐变底大入口（今天的接诊与待整理）。 */
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
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
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

/** 圆钮旁状态文案：唤醒中/进行中/暂停/上传中/空闲（守红线，绝不出现「录音」）。 */
private fun CompanionUiState.statusText(source: CompanionSource): String = when (val st = state) {
    // 唤醒/连接中：用引擎附带文案（「正在连接陪伴笔…」/「正在唤醒陪伴笔…」），无则给个默认。
    is RecordingState.Recording -> if (st.starting) (st.message ?: "正在唤醒陪伴笔…") else "陪伴进行中"
    is RecordingState.Paused -> "陪伴已暂停"
    is RecordingState.Uploading -> "正在保存这次陪伴…"
    // 空闲：选了陪伴笔但还没连上 → 先提示连接（对齐 web 进页/掉线时的「请先连接录音笔」），否则给开启引导。
    is RecordingState.Idle -> st.message ?: st.errorMessage
        ?: if (source == CompanionSource.Pen && !penConnected) "请先连接陪伴笔" else "点一下 · 开启今天的陪伴"
}

// ─────────────────────────── Preview ───────────────────────────

@Preview(showBackground = true, backgroundColor = 0xFFF8F3ED, widthDp = 360, heightDp = 760)
@Composable
private fun HomeIdlePreview() {
    MeiliTheme {
        HomeContent(
            header = HomeHeader(advisorName = "张敏", storeName = "朝阳旗舰店", loaded = true),
            companion = CompanionUiState(penConnected = true),
            source = CompanionSource.Pen,
            reminderCount = 3,
            onToggleCompanion = {},
            onPickSource = {},
            onRetryUploads = {},
            onOpenReception = {},
            onOpenReminders = {},
            onOpenSettings = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF8F3ED, widthDp = 360, heightDp = 760)
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
            reminderCount = 0,
            onToggleCompanion = {},
            onPickSource = {},
            onRetryUploads = {},
            onOpenReception = {},
            onOpenReminders = {},
            onOpenSettings = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF8F3ED, widthDp = 360, heightDp = 760)
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
            reminderCount = 0,
            onToggleCompanion = {},
            onPickSource = {},
            onRetryUploads = {},
            onOpenReception = {},
            onOpenReminders = {},
            onOpenSettings = {},
        )
    }
}
