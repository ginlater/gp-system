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

    // 录音/蓝牙/通知运行时权限一次性申请；结果忽略——拒绝时引擎各自降级，用户真点「开启陪伴」时再据缺失提示。
    // registerForActivityResult 必须在 onCreate 之前完成（字段初始化即注册，符合 ComponentActivity 约束）。
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* no-op */ }

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
        // 登录门：本地有未过期会话 Cookie 才进 Gate（再按角色分流到顾问端主壳 / 管理台），否则先进登录页。
        // 粗判（不打网络），无网也可用；Gate 会打 /api/me 实判会话与角色，失败再退回登录页。
        val startDestination = if (AuthManager().isLoggedIn()) Routes.Gate else Routes.Login
        // F1：冷启动就来自点通知（提醒通知塞了 open=reminders）→ 记下深链，壳就绪后跳提醒页
        deepLink.value = intent?.getStringExtra("open")
        setContent {
            MeiliApp(
                startDestination = startDestination,
                deepLink = deepLink.value,
                onDeepLinkConsumed = { deepLink.value = null },
            )
        }
        requestRecordingPermissions()
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

    /** 申请录音/蓝牙/通知运行时权限（只申请尚未授予的，按 SDK 版本裁剪）。 */
    private fun requestRecordingPermissions() {
        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }
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
                AppScaffold(
                    startDestination = startDestination,
                    deepLink = deepLink,
                    onDeepLinkConsumed = onDeepLinkConsumed,
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.systemBars),
                )
                forceUpdate.value?.let { ForceUpdateGate(it) }
            }
        }
        // 可关的「有新版」提示仍在设置页（AboutCard 检查更新）；不可关的强更由上面的全局门负责。
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
