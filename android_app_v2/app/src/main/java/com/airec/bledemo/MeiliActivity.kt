package com.airec.bledemo

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.airec.bledemo.privacy.PrivacyConsent
import com.airec.bledemo.privacy.PrivacyConsentDialog
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.airec.bledemo.data.auth.AuthManager
import com.airec.bledemo.data.net.NetworkModule
import com.airec.bledemo.designsystem.MeiliPalette
import com.airec.bledemo.designsystem.MeiliTheme
import com.airec.bledemo.nav.AppScaffold
import com.airec.bledemo.nav.Routes
import com.airec.bledemo.recording.RecordingModule

/**
 * 「美丽陪伴」Compose 入口 Activity（取代旧 ConsultantActivity 的 WebView 壳）。
 *
 * 只做一件事：`MeiliTheme { AppScaffold() }`。导航 / 底栏 / 各屏全在 [AppScaffold] 里。
 * 旧 Activity（ConsultantActivity / MainActivity / ScanActivity / PlayerActivity）保留备用，
 * 但已不再是 LAUNCHER 入口（见 AndroidManifest）。
 *
 * 系统栏：启用 edge-to-edge，由 [systemBars] insets 把内容压在状态栏/导航栏之内
 * （暖玉柔光底色铺满，状态栏区域同色不突兀）。
 */
class MeiliActivity : ComponentActivity() {

    /** 电池优化白名单只问一次（每进程）。 */
    private var batteryAsked = false

    /** F1：通知深链（open=reminders）。冷启动 onCreate / 热启动 onNewIntent 都写这里，AppScaffold 消费。 */
    private val deepLink = androidx.compose.runtime.mutableStateOf<String?>(null)

    /** 网络恢复监听（onDestroy 注销，防泄漏）。 */
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 初始化网络层（OkHttp+Cookie+Retrofit），各屏 ViewModel 调 API 前必须先 init。
        NetworkModule.init(applicationContext)
        // 共享录音引擎单例（陪伴首页 / 待整理同步共用同一引擎实例，避免各拿各的 PenController 打架）。
        RecordingModule.init(applicationContext)
        // 主题皮肤：载入用户上次选定的配色（默认暖玉柔光），全 app 据此着色。
        com.airec.bledemo.designsystem.ThemeManager.init(applicationContext)
        com.airec.bledemo.designsystem.FontScaleManager.init(applicationContext)
        // 蓝牙开关状态（全局横幅）：广播+定时双保险，见 BtStateManager 病案注释。
        com.airec.bledemo.device.BtStateManager.init(applicationContext)
        // 登录门：本地有未过期会话 Cookie 才进 Gate（再按角色分流到顾问端主壳 / 管理台），否则先进登录页。
        // 粗判（不打网络），无网也可用；Gate 会打 /api/me 实判会话与角色，失败再退回登录页。
        val startDestination = if (AuthManager().isLoggedIn()) Routes.Gate else Routes.Login
        // F1：冷启动就来自点通知（提醒通知塞了 open=reminders）→ 记下深链，壳就绪后跳提醒页
        deepLink.value = intent?.getStringExtra("open")
        setContent {
            // ★2026-07-17 隐私合规 —— 首次启动必须先弹隐私政策,同意前不进任何页面。
            // OPPO 驳回原文:「首次运行时未通过弹窗等明显方式提醒用户阅读隐私政策」;
            // 小米同样要求,还要把这个弹窗录进演示视频。登录页那个勾选框不够(vivo 过了,
            // 但 OPPO/小米不认)——它们要的是「不同意就用不了」,而不是「不勾就登不上」。
            var agreed by remember { mutableStateOf(PrivacyConsent.isAgreed(this)) }
            if (!agreed) {
                PrivacyConsentDialog(
                    onAgree = { PrivacyConsent.setAgreed(this); agreed = true },
                    // 「不同意」必须真的能走人,不能把用户困在这
                    onDisagree = { finish() },
                )
            } else {
                MeiliApp(
                    startDestination = startDestination,
                    deepLink = deepLink.value,
                    onDeepLinkConsumed = { deepLink.value = null },
                )
            }
        }
        registerNetworkMonitor()
    }

    /** F1：App 活着时点通知（SINGLE_TOP 复用本 Activity）→ 从新 intent 取深链。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLink.value = intent.getStringExtra("open")
    }

    override fun onResume() {
        super.onResume()
        // 回前台：自动日夜模式下按当前时间刷新皮肤（晚上打开自动变黑金等）。
        com.airec.bledemo.designsystem.ThemeManager.tick()
        // 回前台：会话 Cookie 可能登录后才拿到/已变 → 刷新上传上下文，保证开启陪伴/后台补传带的是最新会话。
        RecordingModule.refreshUploadContext()
        // 回前台：重查蓝牙开关（蓝牙栈假死时广播可能不来，这里主动补一刀）。
        com.airec.bledemo.device.BtStateManager.refresh()
        // 引导加入电池白名单（每进程一次），降低国产 ROM 杀后台、保证陪伴/补传不被打断。
        maybeAskBatteryExemption()
        // 回前台：已连陪伴笔→夺回引擎回调(activate)；未连→静默自动重连上次那支笔，与旧端 onResume 一致。
        val rc = RecordingModule.controller
        if (rc.isPenConnected()) {
            rc.activate()
        } else {
            val mac = getSharedPreferences("pen_prefs", Context.MODE_PRIVATE).getString("last_mac", null)
            if (!mac.isNullOrEmpty()) rc.autoConnectPen(mac)
        }
    }

    override fun onDestroy() {
        netCallback?.let { cb ->
            try {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
                    ?.unregisterNetworkCallback(cb)
            } catch (_: Exception) {}
        }
        netCallback = null
        super.onDestroy()
    }

    /** 引导加入电池优化白名单（每次启动最多问一次）。失败静默（部分 ROM 无此 intent）。 */
    private fun maybeAskBatteryExemption() {
        if (batteryAsked) return
        batteryAsked = true
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName"),
                    ),
                )
            }
        } catch (_: Exception) {}
    }

    /** 监听网络恢复 → 通知引擎清退避、立刻重推待补传队列（与旧端 netCallback 一致）。 */
    private fun registerNetworkMonitor() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    RecordingModule.onNetworkAvailable()
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
        } catch (_: Exception) {}
    }

    // ★2026-07-16 隐私合规整改:原 requestRecordingPermissions() 已删除。
    //
    // 病根:它挂在 onCreate 末尾,App 一启动就一次性申请 麦克风+定位/蓝牙+通知,
    //   而那时用户什么都还没点。应用商店隐私检测两条驳回全踩:
    //   ①「过度申请权限」——未见使用权限对应的功能就提前申请(检测日志实锤:启动 5 秒后即申请位置权限组);
    //   ②「未告知申请权限目的」——直接弹系统框,没有任何说明。
    //
    // 现在改为:谁用谁申请、申请前先弹说明框。见 permission/PermissionGate.kt。
    //   · 麦克风 → 用户点「开启陪伴」时(HomeScreen)
    //   · 蓝牙   → 用户点连接陪伴笔/扫描时(HomeScreen 扫描入口)
    //   · 通知   → 提醒页首次进入时
    // ⚠️ 不要再在这里(或任何启动路径上)加权限申请。
}

/** App 根 Composable：主题 + 系统栏避让 + 主壳。抽出来便于 @Preview / 测试。 */
@Composable
private fun MeiliApp(
    startDestination: String,
    deepLink: String? = null,
    onDeepLinkConsumed: () -> Unit = {},
) {
    MeiliTheme {
        // 自动日夜：App 开着时每分钟检查一次是否跨过 6:00/18:00 → 切换日/夜皮肤。
        androidx.compose.runtime.LaunchedEffect(Unit) {
            while (true) {
                com.airec.bledemo.designsystem.ThemeManager.tick()
                // ★上传 cookie 自愈：前台时每分钟把本地最新会话 Cookie 重注给上传引擎。
                // 会话失效后 App 会自动重登拿到新 Cookie(已落 CookieJar)，但引擎里可能还攥着旧 Cookie 死循环 401
                // ("卡在上传")。原来只在回前台/进首页刷新，顾问干等不会自动好、要手动重开 App。这里定时兜底，
                // 最多 1 分钟内上传器就换上新 Cookie 自动冲上去，不依赖自动重登的返回值判定。
                runCatching { com.airec.bledemo.recording.RecordingModule.refreshUploadContext() }
                // 蓝牙开关兜底刷新：MIUI 等蓝牙栈假死时 STATE_CHANGED 广播可能不发，定时查真实状态。
                runCatching { com.airec.bledemo.device.BtStateManager.refresh() }
                kotlinx.coroutines.delay(60_000)
            }
        }
        // 状态栏/导航栏图标随主题明暗：暗色皮肤(黑金)=浅色图标，浅色皮肤=深色图标。
        val view = androidx.compose.ui.platform.LocalView.current
        val dark = com.airec.bledemo.designsystem.ThemeManager.current.dark
        if (!view.isInEditMode) {
            androidx.compose.runtime.SideEffect {
                val window = (view.context as android.app.Activity).window
                val ctl = androidx.core.view.WindowCompat.getInsetsController(window, view)
                ctl.isAppearanceLightStatusBars = !dark
                ctl.isAppearanceLightNavigationBars = !dark
            }
        }
        // 全局强更门（2026-07-06）：原来强更卡只在设置页——顾问不点设置等于没强更。
        // 每次启动/回前台查 /api/app/v2/version（无需登录），installed < min → 全屏遮罩挡住整个 App。
        val forceUpdate = remember { mutableStateOf<com.airec.bledemo.data.model.AppVersion?>(null) }
        val fuCtx = androidx.compose.ui.platform.LocalContext.current
        val fuOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
        LaunchedEffect(Unit) {
            fuOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                runCatching {
                    val r = com.airec.bledemo.data.repo.ConsultantRepository().appVersionV2()
                    if (r is com.airec.bledemo.data.repo.ApiResult.Success) {
                        val installed = runCatching {
                            fuCtx.packageManager.getPackageInfo(fuCtx.packageName, 0).run {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode
                                else @Suppress("DEPRECATION") versionCode.toLong()
                            }
                        }.getOrDefault(0L)
                        val min = (r.data.minVersionCode ?: 0).toLong()
                        forceUpdate.value = if (installed in 1 until min) r.data else null
                    }
                }
            }
        }
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MeiliPalette.Bg,
        ) {
            Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.systemBars),
                ) {
                    // 全局蓝牙横幅：蓝牙没开时所有页面（首页/待整理/…）顶部都能看见并一键打开，
                    // 不再依赖只在扫描页的提示 + 会被 MIUI 吞掉的系统弹框（2026-07-09 西财店病案）。
                    BtOffBanner()
                    AppScaffold(
                        startDestination = startDestination,
                        deepLink = deepLink,
                        onDeepLinkConsumed = onDeepLinkConsumed,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )
                }
                forceUpdate.value?.let { ForceUpdateGate(it) }
            }
        }
        // 可关的「有新版」提示仍在设置页（AboutCard 检查更新）；不可关的强更由上面的全局门负责。
    }
}

/**
 * 全局蓝牙横幅：手机蓝牙没开 && 这台手机用过陪伴笔 → 所有页面顶部醒目红条 + 一键打开。
 * 安卓 12 及以下点击直接帮用户把蓝牙打开（enable()），不依赖系统弹框；13+ 走系统弹框/设置页。
 */
@Composable
private fun BtOffBanner() {
    val btOn by com.airec.bledemo.device.BtStateManager.btOn
    val penUser by com.airec.bledemo.device.BtStateManager.penUser
    if (btOn || !penUser) return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Surface(color = MeiliPalette.RoseDeep, modifier = Modifier.fillMaxWidth()) {
        androidx.compose.foundation.layout.Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text = "手机蓝牙未开启，无法连接录音笔",
                style = MaterialTheme.typography.bodyMedium,
                color = MeiliPalette.White,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "点此打开",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                ),
                color = MeiliPalette.White,
                modifier = Modifier
                    .padding(start = 12.dp)
                    .clickable { com.airec.bledemo.device.BtStateManager.requestEnable(ctx) },
            )
        }
    }
}

/** 全屏不可关的强制升级门：盖住整个 App，吞返回键，唯一出口是「立即更新」（浏览器开下载页）。 */
@Composable
private fun ForceUpdateGate(v: com.airec.bledemo.data.model.AppVersion) {
    androidx.activity.compose.BackHandler(enabled = true) { /* 吞掉返回键：强更不可绕过 */ }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Surface(modifier = Modifier.fillMaxSize(), color = MeiliPalette.Bg) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "请升级到新版本",
                style = MaterialTheme.typography.headlineSmall,
                color = MeiliPalette.Ink,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = v.updateNote?.takeIf { it.isNotBlank() }
                    ?: "当前版本已停用，请更新到 ${v.latestVersionName ?: "最新版"} 后继续使用。",
                style = MaterialTheme.typography.bodyMedium,
                color = MeiliPalette.Ink2,
            )
            Spacer(Modifier.height(24.dp))
            com.airec.bledemo.designsystem.components.PrimaryButton(
                text = "立即更新",
                onClick = {
                    val base = com.airec.bledemo.data.net.NetworkModule.BASE_URL.trimEnd('/')
                    val page = v.apkUrl ?: v.pageUrl ?: "/download/v2"
                    val url = if (page.startsWith("http")) page else base + page
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
