package com.airec.bledemo.nav

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.airec.bledemo.designsystem.Dimens
import com.airec.bledemo.designsystem.MeiliIcons
import com.airec.bledemo.designsystem.MeiliPalette
import com.airec.bledemo.designsystem.MeiliShapes
import com.airec.bledemo.recording.CompanionSource
import com.airec.bledemo.recording.RecordingModule
import com.airec.bledemo.recording.RecordingState
import com.airec.bledemo.ui.admin.AdminHomeScreen
import com.airec.bledemo.ui.admin.ops.AdminOpsScreen
import com.airec.bledemo.ui.archive.ArchiveScreen
import com.airec.bledemo.ui.bind.BindCustomerScreen
import com.airec.bledemo.ui.customer.CustomerDetailScreen
import com.airec.bledemo.ui.customer.CustomerScreen
import com.airec.bledemo.ui.gate.GateScreen
import com.airec.bledemo.ui.home.HomeScreen
import com.airec.bledemo.ui.login.LoginScreen
import com.airec.bledemo.ui.reception.ReceptionScreen
import com.airec.bledemo.ui.reminders.RemindersScreen
import com.airec.bledemo.ui.report.ReportScreen
import com.airec.bledemo.ui.session.SessionPreviewScreen
import com.airec.bledemo.ui.agentweb.AgentWebScreen
import com.airec.bledemo.ui.settings.SettingsScreen
import com.airec.bledemo.ui.teach.TeachHomeScreen
import com.airec.bledemo.ui.teach.TeachQuizScreen
import com.airec.bledemo.ui.teach.TeachReaderScreen
import com.airec.bledemo.ui.workspace.WorkspaceScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 底部 tab 定义（方案A 改版后）：陪伴 / 接诊 / [中间录音 FAB] / 报告 / 客户。
 *  - 接诊 = 原「接诊 + 待整理」合并成一页（[ReceptionScreen] 内含今日接诊 + 待整理 + 从陪伴笔同步）。
 *  - 报告 = 原「档案」（[ArchiveScreen]，行已精简）。
 *  - 客户 = 新增 tab（[CustomerScreen] 顾客搜索 → 美丽档案详情）。
 * 中间大圆 FAB 不是 Pager 页，单列插在视觉中点；4 个 tab 即 [HorizontalPager] 的 4 页，页序 = 声明序。
 *
 * `enum.ordinal` 直接当 Pager 页索引用：Home=0 / Reception=1 / Report=2 / Customer=3。
 */
private enum class BottomTab(
    val label: String,
    val icon: ImageVector,
) {
    Home("陪伴", MeiliIcons.Companion),
    Reception("接诊", MeiliIcons.Reception),
    Report("报告", MeiliIcons.Doc),
    Customer("客户", MeiliIcons.Profile),
}

/** Pager 总页数（4 个 tab，FAB 不占页）。与 [BottomTab] 条目数保持一致。 */
private const val TAB_COUNT = 4

/**
 * App 主壳：[NavHost] 承载「主壳页（[Routes.Main]）+ 各次级页」。
 *
 * 4 个底部 tab 同居在 [Routes.Main] 的 [HorizontalPager] 4 页里，左右滑跟手 + 弹簧落位；点底栏走
 * animateScrollToPage。中间录音 FAB 直接驱动录音引擎（[RecordingModule.controller]）开/停，并以引擎
 * 状态点亮「进行中」呼吸态——不依赖某个屏的 ViewModel，任何 tab 上点都能开/停。
 *
 * @param navController 可注入（测试/深链）；默认内部 [rememberNavController]。
 * @param startDestination 起点路由。未登录传 [Routes.Login]，已登录传 [Routes.Gate]（再按角色分流）。
 * @param deepLink F1：通知深链（"reminders"=打开提醒页）。冷启/热点通知都由 MeiliActivity 递进来。
 * @param onDeepLinkConsumed 深链导航执行（或放弃）后回执，避免重组重复导航。
 */
@Composable
fun AppScaffold(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    startDestination: String = Routes.Main,
    deepLink: String? = null,
    onDeepLinkConsumed: () -> Unit = {},
) {
    // F1：消费通知深链——等鉴权壳就绪（离开 Login/Gate）再跳提醒页；未登录 10s 内没就绪就放弃
    //（用户先去登录，红点/提醒页自己会引导）。发送侧 ReminderNotifier 塞的 open=reminders 至此接通。
    androidx.compose.runtime.LaunchedEffect(deepLink) {
        if (deepLink == "reminders") {
            val ready = kotlinx.coroutines.withTimeoutOrNull(10_000) {
                navController.currentBackStackEntryFlow.first {
                    it.destination.route != Routes.Login && it.destination.route != Routes.Gate
                }
            }
            if (ready != null && navController.currentDestination?.route != Routes.Reminders) {
                navController.navigate(Routes.Reminders) { launchSingleTop = true }
            }
            onDeepLinkConsumed()
        } else if (deepLink != null) {
            onDeepLinkConsumed()
        }
    }
    AppNavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier
            .fillMaxSize()
            .background(MeiliPalette.Bg),
    )
}

/** 全部目的地的 [NavHost]。主壳页含 Pager+底栏；带参数的报告/绑定/会话预览/客户详情从路由 arg 取值。 */
@Composable
private fun AppNavHost(
    navController: NavHostController,
    startDestination: String,
    modifier: Modifier = Modifier,
) {
    // 系统 key → 目的地（工作台格子直接用；切换器先 pop 回工作台再用）
    val navigateToSystem: (String) -> Unit = { key ->
        when (key) {
            "gongpai" -> navController.navigate(Routes.Main)
            "teach" -> navController.navigate(Routes.TeachHome)
            "followup", "higheq" -> navController.navigate(Routes.ScriptAgent.build(key))
            "chat" -> navController.navigate(Routes.ChatHome)
            "kpi" -> navController.navigate(Routes.KpiHome)
            "kpi_admin" -> navController.navigate(Routes.KpiAdminHome)
        }
    }

    // 多系统切换（P1.1）：先 pop 回工作台（多系统账号的 Gate 落点，必在返回栈），再进目标系统。
    // 极端情况（进程恢复等）栈里没有工作台就先补一个，保证返回层级始终是 工作台 → 系统。
    val switchSystem: (String) -> Unit = { key ->
        if (!navController.popBackStack(Routes.Workspace, false)) {
            navController.navigate(Routes.Workspace) { launchSingleTop = true }
        }
        navigateToSystem(key)
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
        // 次级页统一走 iOS 式「右侧滑入 + 淡入」(~300ms)，回退反向；tab 间切换交给 Pager。
        enterTransition = {
            slideInHorizontally(secondarySlideSpec) { full -> full } + fadeIn(secondaryFadeSpec)
        },
        exitTransition = { fadeOut(secondaryFadeSpec) },
        popEnterTransition = { fadeIn(secondaryFadeSpec) },
        popExitTransition = {
            slideOutHorizontally(secondarySlideSpec) { full -> full } + fadeOut(secondaryFadeSpec)
        },
    ) {
        // ---- 登录 ----
        composable(Routes.Login) {
            LoginScreen(
                onLoggedIn = {
                    navController.navigate(Routes.Gate) {
                        popUpTo(Routes.Login) { inclusive = true }
                    }
                },
            )
        }

        // ---- 角色分流门 ----
        composable(Routes.Gate) {
            GateScreen(
                onAdmin = {
                    navController.navigate(Routes.AdminHome) {
                        popUpTo(Routes.Gate) { inclusive = true }
                    }
                },
                onWorkspace = {
                    navController.navigate(Routes.Workspace) {
                        popUpTo(Routes.Gate) { inclusive = true }
                    }
                },
                onConsultant = {
                    navController.navigate(Routes.Main) {
                        popUpTo(Routes.Gate) { inclusive = true }
                    }
                },
                onLogin = {
                    navController.navigate(Routes.Login) {
                        popUpTo(Routes.Gate) { inclusive = true }
                    }
                },
            )
        }

        // ---- 多系统工作台（systems ≥2 的账号；back 键从各系统回到这里再退出） ----
        composable(Routes.Workspace) {
            WorkspaceScreen(
                onOpenSystem = navigateToSystem,
                onOpenSettings = { navController.navigate(Routes.Settings) },
            )
        }

        // ---- teach 网课壳（工作台入口进） ----
        composable(Routes.TeachHome) {
            TeachHomeScreen(
                onBack = { navController.popBackStack() },
                onOpenReader = { key -> navController.navigate(Routes.TeachReader.build(key)) },
                onOpenQuiz = { key, idx -> navController.navigate(Routes.TeachQuiz.build(key, idx)) },
                onSwitchSystem = switchSystem,
            )
        }
        composable(
            route = Routes.TeachReader.routePattern,
            arguments = listOf(
                navArgument(Routes.TeachReader.ARG_CHAPTER_KEY) { type = NavType.StringType },
            ),
        ) { entry ->
            val key = entry.arguments?.getString(Routes.TeachReader.ARG_CHAPTER_KEY).orEmpty()
            TeachReaderScreen(
                chapterKey = key,
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = Routes.TeachQuiz.routePattern,
            arguments = listOf(
                navArgument(Routes.TeachQuiz.ARG_CHAPTER_KEY) { type = NavType.StringType },
                navArgument(Routes.TeachQuiz.ARG_QUIZ_INDEX) { type = NavType.IntType },
            ),
        ) { entry ->
            val key = entry.arguments?.getString(Routes.TeachQuiz.ARG_CHAPTER_KEY).orEmpty()
            val idx = entry.arguments?.getInt(Routes.TeachQuiz.ARG_QUIZ_INDEX) ?: 1
            TeachQuizScreen(
                chapterKey = key,
                quizIndex = idx,
                onBack = { navController.popBackStack() },
            )
        }

        // ---- 回访 / 高情商话术:规格驱动的原生完整表单(P1.3,规格提取自网页版) ----
        composable(
            route = Routes.ScriptAgent.routePattern,
            arguments = listOf(
                navArgument(Routes.ScriptAgent.ARG_SYS_KEY) { type = NavType.StringType },
            ),
        ) { entry ->
            val sysKey = entry.arguments?.getString(Routes.ScriptAgent.ARG_SYS_KEY).orEmpty()
            com.airec.bledemo.ui.followup.FollowupScreen(
                systemKey = sysKey,
                onBack = { navController.popBackStack() },
            )
        }
        // ---- 扣子销售话术:仍为整页 WebView + 免登录注入(顶栏带 A±字号) ----
        composable(Routes.ChatHome) {
            AgentWebScreen(systemKey = "chat", onBack = { navController.popBackStack() })
        }
        // ---- KPI 积分:WebView + cookie 会话(账号体系独立,首次手动登录后常驻) ----
        composable(Routes.KpiHome) {
            AgentWebScreen(systemKey = "kpi", onBack = { navController.popBackStack() })
        }
        // ---- KPI 记分考核(管理后台,固定管理员账号自动登录) ----
        composable(Routes.KpiAdminHome) {
            AgentWebScreen(systemKey = "kpi_admin", onBack = { navController.popBackStack() })
        }

        // ---- 管理台 ----
        composable(Routes.AdminHome) {
            AdminHomeScreen(
                onOpenModule = { moduleKey ->
                    when (moduleKey) {
                        "ops_dashboard" -> navController.navigate(Routes.AdminOps)
                        else -> { /* 其余模块在 AdminHome 内冒 toast「即将上线」 */ }
                    }
                },
                onLoggedOut = {
                    navController.navigate(Routes.Login) {
                        popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.AdminOps) {
            AdminOpsScreen(onBack = { navController.popBackStack() })
        }

        // ---- 主壳页：4 tab 横向 Pager + 底栏 ----
        composable(Routes.Main) {
            MainTabsScaffold(
                onBindCustomer = { rid -> navController.navigate(Routes.BindCustomer.build(rid)) },
                onOpenReminders = { navController.navigate(Routes.Reminders) },
                onOpenReport = { sid -> navController.navigate(Routes.Report.build(sid)) },
                onOpenPreview = { cid, date -> navController.navigate(Routes.SessionPreviewByCustomer.build(cid, date)) },
                onOpenCustomerDetail = { cid -> navController.navigate(Routes.CustomerDetail.build(cid)) },
                onOpenSettings = { navController.navigate(Routes.Settings) },
                onSwitchSystem = switchSystem,
            )
        }

        // ---- 次级页 ----
        composable(Routes.Reminders) {
            RemindersScreen(
                onBack = { navController.popBackStack() },
                // 「查看报告」→ 报告页；「去绑定」→ 该录音的绑定页（修复：原来这俩没接，点了没反应）
                onOpenReport = { sid -> navController.navigate(Routes.Report.build(sid)) },
                onBindRecording = { rid -> navController.navigate(Routes.BindCustomer.build(rid)) },
                onGoToPending = { navController.popBackStack() },
            )
        }
        composable(Routes.Settings) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onLoggedOut = {
                    navController.navigate(Routes.Login) {
                        popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
                    }
                },
                // 「切换系统工作台」：pop 回工作台宫格（沿途 Settings/Main 等一并出栈）
                onSwitchWorkspace = {
                    if (!navController.popBackStack(Routes.Workspace, false)) {
                        navController.navigate(Routes.Workspace) { launchSingleTop = true }
                    }
                },
            )
        }

        // ---- 带参数次级页 ----
        composable(
            route = Routes.BindCustomer.routePattern,
            arguments = listOf(
                navArgument(Routes.BindCustomer.ARG_RECORDING_ID) { type = NavType.LongType },
            ),
        ) { entry ->
            val rid = entry.arguments?.getLong(Routes.BindCustomer.ARG_RECORDING_ID) ?: -1L
            BindCustomerScreen(
                recordingId = rid,
                onBack = { navController.popBackStack() },
                // 绑定成功 → 跳会话预览，并把「绑定页」从返回栈移除：
                // 否则预览页点返回会落回绑定页，而绑定页的 LaunchedEffect(boundSessionId) 仍持有
                // 旧 sessionId → 立刻又把你弹回预览，表现为「返回不了」。inclusive 移除后返回直达待整理。
                onBound = { sid ->
                    navController.navigate(Routes.SessionPreview.build(sid)) {
                        popUpTo(Routes.BindCustomer.routePattern) { inclusive = true }
                    }
                },
            )
        }
        composable(
            route = Routes.SessionPreview.routePattern,
            arguments = listOf(
                navArgument(Routes.SessionPreview.ARG_SESSION_ID) { type = NavType.LongType },
            ),
        ) { entry ->
            val sid = entry.arguments?.getLong(Routes.SessionPreview.ARG_SESSION_ID) ?: -1L
            SessionPreviewScreen(
                sessionId = sid,
                onBack = { navController.popBackStack() },
                onAnalysisStarted = { s -> navController.navigate(Routes.Report.build(s)) },
            )
        }
        // 接诊包预览（按顾客+日期）：今日接诊「绑定录音/开始分析/看进度」入口，0 录音的待绑定顾客也能开。
        composable(
            route = Routes.SessionPreviewByCustomer.routePattern,
            arguments = listOf(
                navArgument(Routes.SessionPreviewByCustomer.ARG_CUSTOMER_ID) { type = NavType.LongType },
                navArgument(Routes.SessionPreviewByCustomer.ARG_DATE) { type = NavType.StringType },
            ),
        ) { entry ->
            val cid = entry.arguments?.getLong(Routes.SessionPreviewByCustomer.ARG_CUSTOMER_ID) ?: -1L
            val date = entry.arguments?.getString(Routes.SessionPreviewByCustomer.ARG_DATE).orEmpty()
            SessionPreviewScreen(
                sessionId = -1L,
                customerId = cid,
                serviceDate = date,
                onBack = { navController.popBackStack() },
                onAnalysisStarted = { s -> navController.navigate(Routes.Report.build(s)) },
            )
        }
        composable(
            route = Routes.Report.routePattern,
            arguments = listOf(
                navArgument(Routes.Report.ARG_SESSION_ID) { type = NavType.LongType },
            ),
        ) { entry ->
            val sid = entry.arguments?.getLong(Routes.Report.ARG_SESSION_ID) ?: -1L
            ReportScreen(sessionId = sid, onBack = { navController.popBackStack() })
        }
        composable(
            route = Routes.CustomerDetail.routePattern,
            arguments = listOf(
                navArgument(Routes.CustomerDetail.ARG_CUSTOMER_ID) { type = NavType.LongType },
            ),
        ) { entry ->
            val cid = entry.arguments?.getLong(Routes.CustomerDetail.ARG_CUSTOMER_ID) ?: -1L
            CustomerDetailScreen(
                customerId = cid,
                onBack = { navController.popBackStack() },
                onOpenReport = { sid -> navController.navigate(Routes.Report.build(sid)) },
            )
        }
    }
}

/**
 * 主壳页内容：4 tab 横向 [HorizontalPager] + 底栏（4 tab + 中间录音 FAB）。
 *
 * - Pager 4 页（页序 = [BottomTab].ordinal）：陪伴 / 接诊 / 报告 / 客户。
 * - 中间 FAB：直接 toggle 录音引擎（开/停），并滚到陪伴页让用户看到进行中状态；录音中 FAB 呈呼吸态。
 * - 接诊页「去陪伴」回调滚到陪伴页；「开始分析/看进度」跳会话预览；「看报告」跳报告。
 */
@Composable
private fun MainTabsScaffold(
    onBindCustomer: (recordingId: Long) -> Unit,
    onOpenReminders: () -> Unit,
    onOpenReport: (sessionId: Long) -> Unit,
    onOpenPreview: (customerId: Long, date: String) -> Unit,
    onOpenCustomerDetail: (customerId: Long) -> Unit,
    onOpenSettings: () -> Unit,
    onSwitchSystem: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(pageCount = { TAB_COUNT })
    val scope = rememberCoroutineScope()

    val animateToPage: (Int) -> Unit = { target ->
        scope.launch { pagerState.animateScrollToPage(target, animationSpec = tabScrollSpec) }
    }

    // FAB 录音态：以引擎状态为准（笔上按键 / 声控自发开录也会点亮），任何 tab 上的 FAB 都同步。
    val recState by RecordingModule.controller.state.collectAsStateWithLifecycle()
    val fabRecording = recState is RecordingState.Recording || recState is RecordingState.Paused

    // ★2026-07-16 隐私合规:底栏 FAB 也要走权限门(它和首页圆钮是两条路径,真机实测过这里会漏)。
    val permGate = com.airec.bledemo.permission.rememberPermissionGate()
    val fabDenied = remember { mutableStateOf(false) }
    val fabCtx = androidx.compose.ui.platform.LocalContext.current
    if (fabDenied.value) {
        LaunchedEffect(Unit) {
            android.widget.Toast.makeText(
                fabCtx,
                "未获得麦克风权限，无法开始陪伴。如需使用，可在系统设置 → 应用 → 美业私教 → 权限中开启",
                android.widget.Toast.LENGTH_LONG,
            ).show()
            fabDenied.value = false
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // beyondViewportPageCount = TAB_COUNT-1：4 个 tab 页常驻已组合、不随滑动销毁/重建，左右滑 0 延迟。
        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = TAB_COUNT - 1,
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = Dimens.BottomNav),
        ) { page ->
            when (page) {
                BottomTab.Home.ordinal -> HomeScreen(
                    onOpenReception = { animateToPage(BottomTab.Reception.ordinal) },
                    onBindCustomer = onBindCustomer,
                    onOpenReminders = onOpenReminders,
                    onOpenSettings = onOpenSettings,
                    onSwitchSystem = onSwitchSystem,
                )
                BottomTab.Reception.ordinal -> ReceptionScreen(
                    onBindCustomer = onBindCustomer,
                    onOpenPreview = onOpenPreview,
                    onOpenReport = onOpenReport,
                )
                BottomTab.Report.ordinal -> ArchiveScreen(
                    onOpenReport = onOpenReport,
                    onOpenSettings = onOpenSettings,
                )
                BottomTab.Customer.ordinal -> CustomerScreen(
                    onOpenCustomerDetail = onOpenCustomerDetail,
                )
            }
        }

        BottomNavBar(
            selectedIndex = pagerState.currentPage,
            recording = fabRecording,
            onTabClick = { index -> animateToPage(index) },
            onFabClick = {
                // 中间键 = 直接开/停陪伴（录音），再滚到陪伴页看状态。源用上次选定的来源（持久化）。
                val c = RecordingModule.controller
                when (c.state.value) {
                    is RecordingState.Recording, is RecordingState.Paused -> {
                        c.stopCompanion()
                        animateToPage(BottomTab.Home.ordinal)
                    }
                    is RecordingState.Uploading -> Unit // 保存中：忽略
                    else -> {
                        // ★2026-07-16 隐私合规:这里原来直接 startCompanion(),绕过了权限检查——
                        //   真机实测:无麦克风权限时录音服务硬起 → SecurityException(FGS type microphone)
                        //   → 界面报「无法开始录音」。现在与首页圆钮走同一个权限门:
                        //   手机麦录音先弹说明框拿麦克风权限;用陪伴笔则不需要手机麦克风。
                        val src = c.lastSource() ?: CompanionSource.Phone
                        if (src == CompanionSource.Phone) {
                            permGate.require(
                                com.airec.bledemo.permission.PermissionPurpose.Record,
                                onDenied = { fabDenied.value = true },
                            ) {
                                c.startCompanion(src)
                                animateToPage(BottomTab.Home.ordinal)
                            }
                        } else {
                            c.startCompanion(src)
                            animateToPage(BottomTab.Home.ordinal)
                        }
                    }
                }
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

// ============================================================================
// 底栏 UI
// ============================================================================

/** .botnav：高 78、柔光磨砂底 + 上描边，5 槽等分（4 tab + 中间 FAB）。 */
@Composable
private fun BottomNavBar(
    selectedIndex: Int,
    recording: Boolean,
    onTabClick: (Int) -> Unit,
    onFabClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(Dimens.BottomNav)
            .background(MeiliPalette.SurfaceFrost)
            .border(width = 1.dp, color = MeiliPalette.Line)
            .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        NavItem(BottomTab.Home, selectedIndex, onTabClick, Modifier.weight(1f))
        NavItem(BottomTab.Reception, selectedIndex, onTabClick, Modifier.weight(1f))
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            CompanionFab(onClick = onFabClick, recording = recording)
        }
        NavItem(BottomTab.Report, selectedIndex, onTabClick, Modifier.weight(1f))
        NavItem(BottomTab.Customer, selectedIndex, onTabClick, Modifier.weight(1f))
    }
}

/** .nav-item：图标 chip + 文字；选中 = 当前 Pager 页索引等于本 tab 页序。 */
@Composable
private fun NavItem(
    tab: BottomTab,
    selectedIndex: Int,
    onClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = selectedIndex == tab.ordinal
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.18f else 1f,
        animationSpec = spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessMediumLow),
        label = "navIconScale",
    )
    val chipColor by animateColorAsState(
        targetValue = if (selected) MeiliPalette.ClayTint else Color.Transparent,
        animationSpec = tween(220),
        label = "navChip",
    )
    val tint by animateColorAsState(
        targetValue = if (selected) MeiliPalette.ClayDeep else MeiliPalette.Ink3,
        animationSpec = tween(220),
        label = "navTint",
    )
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .androidxClickable(interaction) { onClick(tab.ordinal) }
            .padding(vertical = 2.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(
            modifier = Modifier
                .background(color = chipColor, shape = MeiliShapes.Pill)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                tab.icon,
                contentDescription = tab.label,
                tint = tint,
                modifier = Modifier
                    .size(22.dp)
                    .scale(iconScale),
            )
        }
        Text(
            text = tab.label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 中间录音 FAB：56 径向陶土渐变圆 + 发光。
 *  - 空闲：陶土渐变 + 并蒂花蕊图标。
 *  - 录音中：玫瑰渐变 + 白色方块「停止」+ 呼吸缩放，明确告诉用户「正在陪伴」。
 */
@Composable
private fun CompanionFab(
    onClick: () -> Unit,
    recording: Boolean,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pulse = rememberInfiniteTransition(label = "fabPulse")
    val pulseScale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = if (recording) 1.07f else 1f,
        animationSpec = infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "fabScale",
    )
    // 外圈 ring：与底栏同色，盖住顶边线，让 FAB 像嵌进「凹槽」。
    Box(
        modifier = modifier
            .offset(y = (-10).dp)
            .size(66.dp)
            .background(MeiliPalette.SurfaceFrost, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .scale(if (recording) pulseScale else 1f)
                .shadow(elevation = Dimens.ElevGlow, shape = CircleShape, clip = false)
                .background(
                    brush = if (recording) MeiliPalette.CompanionLiveGradient else MeiliPalette.CompanionGradient,
                    shape = CircleShape,
                )
                .androidxClickable(interaction, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (recording) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .background(Color.White, RoundedCornerShape(6.dp)),
                )
            } else {
                Icon(
                    MeiliIcons.Companion,
                    contentDescription = "开启陪伴",
                    tint = Color.White,
                    modifier = Modifier.size(Dimens.IconLg),
                )
            }
        }
    }
}

/** 轻量 clickable：无水波纹（暖玉柔光走缩放/底色反馈）。 */
private fun Modifier.androidxClickable(
    interaction: MutableInteractionSource,
    onClick: () -> Unit,
): Modifier = this.clickable(
    interactionSource = interaction,
    indication = null,
    onClick = onClick,
)

// ============================================================================
// 动画 spec
// ============================================================================

// 点底栏/FAB 滚到目标页：FastOutSlowIn 240ms，克制不过冲。
private val tabScrollSpec = tween<Float>(durationMillis = 240, easing = FastOutSlowInEasing)

// 次级页进出：iOS 式右侧滑入 + 淡入（~300ms）。
private val secondarySlideSpec = tween<androidx.compose.ui.unit.IntOffset>(durationMillis = 300, easing = FastOutSlowInEasing)
private val secondaryFadeSpec = tween<Float>(durationMillis = 300, easing = FastOutSlowInEasing)
