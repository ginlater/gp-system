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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
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
        setContent {
            MeiliApp(startDestination = startDestination)
        }
        requestRecordingPermissions()
        registerNetworkMonitor()
    }

    override fun onResume() {
        super.onResume()
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
private fun MeiliApp(startDestination: String) {
    MeiliTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MeiliPalette.Bg,
        ) {
            AppScaffold(
                startDestination = startDestination,
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars),
            )
        }
    }
}
