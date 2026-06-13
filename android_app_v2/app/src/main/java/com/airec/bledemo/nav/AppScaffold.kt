package com.airec.bledemo.nav

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import com.airec.bledemo.designsystem.MeiliTheme
import com.airec.bledemo.ui.admin.AdminHomeScreen
import com.airec.bledemo.ui.admin.ops.AdminOpsScreen
import com.airec.bledemo.ui.archive.ArchiveScreen
import com.airec.bledemo.ui.bind.BindCustomerScreen
import com.airec.bledemo.ui.gate.GateScreen
import com.airec.bledemo.ui.home.HomeScreen
import com.airec.bledemo.ui.login.LoginScreen
import com.airec.bledemo.ui.pending.PendingScreen
import com.airec.bledemo.ui.reception.ReceptionScreen
import com.airec.bledemo.ui.reminders.RemindersScreen
import com.airec.bledemo.ui.report.ReportScreen
import com.airec.bledemo.ui.session.SessionPreviewScreen
import com.airec.bledemo.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

/**
 * 底部 tab 定义（与 warm_2.html 的 `NAV` 数组一致）：陪伴 / 接诊 / [FAB] / 待整理 / 档案。
 * FAB 不是一个 Pager 页，单列出来插在视觉中点；4 个 tab 即 [HorizontalPager] 的 4 页，页序 = 声明序。
 *
 * `enum.ordinal` 直接当 Pager 页索引用：Home=0 / Reception=1 / Pending=2 / Archive=3。
 */
private enum class BottomTab(
    val label: String,
    val icon: ImageVector,
) {
    Home("陪伴", MeiliIcons.Companion),
    Reception("接诊", MeiliIcons.Reception),
    Pending("待整理", MeiliIcons.Tidy),
    Archive("档案", MeiliIcons.Profile),
}

/** Pager 总页数（4 个 tab，FAB 不占页）。与 [BottomTab] 条目数保持一致。 */
private const val TAB_COUNT = 4

/**
 * App 主壳：[NavHost] 承载「主壳页（[Routes.Main]）+ 各次级页」。
 *
 * 改版要点（iOS 顺滑切 tab）：
 *  - 4 个底部 tab 不再各占一个 NavHost 目的地，而是同居在 [Routes.Main] 里的 [HorizontalPager] 4 页。
 *    左右滑由 Pager 原生跟手 + 弹簧落位（不再手写 swipeTabsGesture）；点底栏走 animateScrollToPage 平滑动画。
 *  - 4 个 tab 屏的 `viewModel()` 因同住一个 NavBackStackEntry 的组合，切 tab 不再重建/重新拉数据（性能红利）。
 *  - 次级页（绑定/预览/报告/提醒/设置）仍是独立 NavHost 目的地，盖满全屏 → 底栏自动隐藏。
 *
 * @param navController 可注入（测试/外部深链用）；默认内部 [rememberNavController]。
 * @param startDestination 起点路由。未登录传 [Routes.Login]，已登录传 [Routes.Gate]（再按角色分流）。
 * @param onStartCompanion 中间 FAB 点击：滚到陪伴页(0)后触发「开启陪伴」（壳阶段默认仅导航到首页）。
 */
@Composable
fun AppScaffold(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    startDestination: String = Routes.Main,
    onStartCompanion: () -> Unit = {},
) {
    AppNavHost(
        navController = navController,
        startDestination = startDestination,
        onStartCompanion = onStartCompanion,
        modifier = modifier
            .fillMaxSize()
            .background(MeiliPalette.Bg),
    )
}

/** 全部目的地的 [NavHost]。主壳页含 Pager+底栏；带参数的报告/绑定/会话预览从路由 arg 取值传给 screen。 */
@Composable
private fun AppNavHost(
    navController: NavHostController,
    startDestination: String,
    onStartCompanion: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
        // 次级页统一走 iOS 式「右侧滑入 + 淡入」(~300ms FastOutSlowIn)，回退反向；tab 间切换已交给 Pager，无需 NavHost 过渡。
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
                // 登录成功统一回 Gate，由 Gate 按角色分流（顾问端主壳 vs 管理台）。
                onLoggedIn = {
                    navController.navigate(Routes.Gate) {
                        popUpTo(Routes.Login) { inclusive = true }
                    }
                },
            )
        }

        // ---- 角色分流门（startup / 登录后落点；分流后 pop 自身） ----
        composable(Routes.Gate) {
            GateScreen(
                onAdmin = {
                    navController.navigate(Routes.AdminHome) {
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

        // ---- 管理台首页（admin/super） ----
        composable(Routes.AdminHome) {
            AdminHomeScreen(
                onOpenModule = { moduleKey ->
                    // WAVE 1：仅运营看板有真实屏；后续模块在此 when 加分支即可。
                    when (moduleKey) {
                        "ops_dashboard" -> navController.navigate(Routes.AdminOps)
                        else -> { /* 其余模块在 AdminHome 内冒 toast「即将上线」，这里不导航 */ }
                    }
                },
                onLoggedOut = {
                    navController.navigate(Routes.Login) {
                        popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
                    }
                },
            )
        }

        // ---- 管理台 · 运营看板 ----
        composable(Routes.AdminOps) {
            AdminOpsScreen(onBack = { navController.popBackStack() })
        }

        // ---- 主壳页：4 tab 横向 Pager + 底栏（4 个 tab 同住此目的地，切页不重建） ----
        composable(Routes.Main) {
            MainTabsScaffold(
                onBindCustomer = { rid -> navController.navigate(Routes.BindCustomer.build(rid)) },
                onOpenReminders = { navController.navigate(Routes.Reminders) },
                onOpenReport = { sid -> navController.navigate(Routes.Report.build(sid)) },
                onOpenSettings = { navController.navigate(Routes.Settings) },
                onStartCompanion = onStartCompanion,
            )
        }

        // ---- 次级页 ----
        composable(Routes.Reminders) {
            RemindersScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.Settings) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onLoggedOut = {
                    navController.navigate(Routes.Login) {
                        popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
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
                onBound = { sid -> navController.navigate(Routes.SessionPreview.build(sid)) },
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
        composable(
            route = Routes.Report.routePattern,
            arguments = listOf(
                navArgument(Routes.Report.ARG_SESSION_ID) { type = NavType.LongType },
            ),
        ) { entry ->
            val sid = entry.arguments?.getLong(Routes.Report.ARG_SESSION_ID) ?: -1L
            ReportScreen(sessionId = sid, onBack = { navController.popBackStack() })
        }
    }
}

/**
 * 主壳页内容：4 tab 横向 [HorizontalPager] + 底栏（4 tab + 中间「开启陪伴」FAB）。
 *
 * - Pager 4 页（页序 = [BottomTab].ordinal）：陪伴 / 接诊 / 待整理 / 档案，左右滑原生跟手 + 弹簧落位。
 * - 底栏高亮跟 [androidx.compose.foundation.pager.PagerState.currentPage]；点底栏 tab → animateScrollToPage 平滑滚到对应页。
 * - 中间 FAB → 滚到陪伴页(0) + 触发 [onStartCompanion]（保留原行为）。
 * - 各 tab 屏的回调与改版前逐字一致：陪伴(onOpenPending=滚到待整理页 / onBindCustomer)、
 *   接诊(onOpenReminders)、待整理(onBindCustomer)、档案(onOpenReport / onOpenSettings)。
 *   其中陪伴页"去待整理"原本走 navigateToTab，现改为 Pager 内 animateScrollToPage(待整理页)，体验等价。
 */
@Composable
private fun MainTabsScaffold(
    onBindCustomer: (recordingId: Long) -> Unit,
    onOpenReminders: () -> Unit,
    onOpenReport: (sessionId: Long) -> Unit,
    onOpenSettings: () -> Unit,
    onStartCompanion: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(pageCount = { TAB_COUNT })
    val scope = rememberCoroutineScope()

    // 「从陪伴首页『从陪伴笔同步』跳到待整理后自动开 sheet」的一次性信号（待整理屏消费后回置 false）。
    var openPenSyncOnPending by remember { mutableStateOf(false) }

    // 点底栏/FAB 时平滑滚到目标页（FastOutSlowIn ~320ms，比默认弹簧更克制、不过冲，丝滑不突兀）。
    val animateToPage: (Int) -> Unit = { target ->
        scope.launch { pagerState.animateScrollToPage(target, animationSpec = tabScrollSpec) }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 内容给底栏预留 78dp 避让。
        // beyondViewportPageCount = TAB_COUNT-1：4 个 tab 页全部常驻已组合、不随滑动销毁/重建，
        // 所以左右滑只是平移已渲染好的页面 = 0 延迟丝滑；各页数据由其 ViewModel 协程异步加载、
        // 永不阻塞滑动（首次进主壳时一次性把 4 个 VM 拉起，之后切 tab 既不重建也不重新联网）。
        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = TAB_COUNT - 1,
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = Dimens.BottomNav),
        ) { page ->
            // 纯净横滑：用 Pager 原生跟手平移，不对页内容做任何缩放/位移变换——
            // 内容始终完整可读、零离屏合成，最顺（缩放/视差都试过反而碍可读性，弃用）。
            when (page) {
                BottomTab.Home.ordinal -> HomeScreen(
                    onOpenPending = { animateToPage(BottomTab.Pending.ordinal) },
                    onBindCustomer = onBindCustomer,
                    onOpenReception = { animateToPage(BottomTab.Reception.ordinal) },
                    onOpenReminders = onOpenReminders,
                    onOpenPenSync = {
                        openPenSyncOnPending = true
                        animateToPage(BottomTab.Pending.ordinal)
                    },
                    onOpenSettings = onOpenSettings,
                )
                BottomTab.Reception.ordinal -> ReceptionScreen(
                    onOpenReminders = onOpenReminders,
                )
                BottomTab.Pending.ordinal -> PendingScreen(
                    onBindCustomer = onBindCustomer,
                    openSyncSignal = openPenSyncOnPending,
                    onSyncSignalConsumed = { openPenSyncOnPending = false },
                )
                BottomTab.Archive.ordinal -> ArchiveScreen(
                    onOpenReport = onOpenReport,
                    onOpenSettings = onOpenSettings,
                )
            }
        }

        BottomNavBar(
            selectedIndex = pagerState.currentPage,
            onTabClick = { index -> animateToPage(index) },
            onFabClick = {
                animateToPage(BottomTab.Home.ordinal)
                onStartCompanion()
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

// ============================================================================
// 底栏 UI
// ============================================================================

/** .botnav：高 78、柔光磨砂底 + 上描边 + 顶部柔光阴影，5 槽等分（4 tab + 中间 FAB）。 */
@Composable
private fun BottomNavBar(
    selectedIndex: Int,
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
        // 中间 FAB 槽（等分 1f，圆钮上浮、外圈同色 ring 盖住顶边线形成"凹槽"感，不挤压两侧）。
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            CompanionFab(onClick = onFabClick)
        }
        NavItem(BottomTab.Pending, selectedIndex, onTabClick, Modifier.weight(1f))
        NavItem(BottomTab.Archive, selectedIndex, onTabClick, Modifier.weight(1f))
    }
}

/** .nav-item：图标 chip + 文字；选中态文字 clay-deep、chip 底 clay-tint。选中 = 当前 Pager 页索引等于本 tab 页序。 */
@Composable
private fun NavItem(
    tab: BottomTab,
    selectedIndex: Int,
    onClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = selectedIndex == tab.ordinal
    // 选中态动效（都是廉价属性动画，不触发离屏合成、不碰页面内容）：
    //  - 图标 spring 回弹「弹一下」(1→1.18)；- chip 底色 / 文字色平滑过渡。
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
        // navchip：选中时 clay-tint 胶囊底（颜色动画淡入），图标随 spring 弹一下
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

/** 中间「开启陪伴」FAB：56 径向陶土渐变圆 + 发光 + 并蒂花蕊图标（warm_2 .nav-fab 内的圆）。 */
@Composable
private fun CompanionFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    // 外圈 ring：与底栏同色，盖住其下方的顶边线，让 FAB 像嵌进"凹槽"而非生硬越线。
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
                .shadow(elevation = Dimens.ElevGlow, shape = CircleShape, clip = false)
                .background(brush = MeiliPalette.CompanionGradient, shape = CircleShape)
                .androidxClickable(interaction, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                MeiliIcons.Companion,
                contentDescription = "开启陪伴",
                tint = Color.White,
                modifier = Modifier.size(Dimens.IconLg),
            )
        }
    }
}

/** 轻量 clickable：无水波纹（暖玉柔光走缩放/底色反馈，不要 Material 默认 ripple 破坏调性）。 */
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

// 点底栏/FAB 滚到目标页：FastOutSlowIn 320ms，比 Pager 默认弹簧更克制不过冲，丝滑且方向清晰。
private val tabScrollSpec = tween<Float>(durationMillis = 240, easing = FastOutSlowInEasing)

// 次级页进出：iOS 式右侧滑入 + 淡入（~300ms FastOutSlowIn），回退反向滑出。位移与淡入共用同一时长。
private val secondarySlideSpec = tween<androidx.compose.ui.unit.IntOffset>(durationMillis = 300, easing = FastOutSlowInEasing)
private val secondaryFadeSpec = tween<Float>(durationMillis = 300, easing = FastOutSlowInEasing)
