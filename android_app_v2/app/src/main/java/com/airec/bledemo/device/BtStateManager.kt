package com.airec.bledemo.device

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.mutableStateOf

/**
 * 手机蓝牙开关状态快照（全局横幅用）。
 *
 * 病案（2026-07-09 西财店）：手机蓝牙没开，顾问守在待整理页等自动重连等了两个多小时——
 * 全 App 没有任何地方告诉她"蓝牙没开"（提示只在扫描页，且 MIUI 会吞掉系统的"允许开启蓝牙"
 * 弹框：拒绝过一次之后就静默拒绝）。这里维护一份可组合订阅的蓝牙开关状态：
 * 广播 + 回前台/60s tick 主动刷新双保险——蓝牙栈假死时广播可能不来，主动刷新每次都取
 * 新 adapter 查实时 state，不信缓存。
 */
object BtStateManager {

    /** 蓝牙是否开启（Compose 可订阅）。默认 true：查不到时不误报横幅。 */
    val btOn = mutableStateOf(true)

    /** 是否用过陪伴笔（pen_prefs 有 last_mac）——从没连过笔的账号（纯手机麦/管理员）不弹蓝牙横幅。 */
    val penUser = mutableStateOf(false)

    @Volatile
    private var appCtx: Context? = null

    @Volatile
    private var registered = false

    fun init(context: Context) {
        val ctx = context.applicationContext
        appCtx = ctx
        if (!registered) {
            registered = true
            try {
                ctx.registerReceiver(
                    object : BroadcastReceiver() {
                        override fun onReceive(c: Context?, i: Intent?) = refresh()
                    },
                    IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                )
            } catch (_: Exception) {}
        }
        refresh()
    }

    /** 主动刷新（回前台 / 60s tick）：每次取新 adapter 查真实 state。 */
    fun refresh() {
        val ctx = appCtx ?: return
        btOn.value = try {
            val bm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val ad = bm?.adapter
            ad != null && ad.state == BluetoothAdapter.STATE_ON
        } catch (_: Exception) {
            true
        }
        penUser.value = try {
            !ctx.getSharedPreferences("pen_prefs", Context.MODE_PRIVATE)
                .getString("last_mac", null).isNullOrEmpty()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 一键打开蓝牙：安卓 12 及以下直接 enable()（不依赖会被 MIUI 吞掉的系统弹框；
     * 需要的 BLUETOOTH_ADMIN/CONNECT 权限已申请，被拒时 runCatching 兜住）；
     * 不行再走系统"允许开启蓝牙"弹框；最后兜底跳系统蓝牙设置页。
     */
    fun requestEnable(context: Context) {
        try {
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val ad = bm?.adapter
            if (ad != null && android.os.Build.VERSION.SDK_INT <= 32) {
                @Suppress("DEPRECATION")
                if (runCatching { ad.enable() }.getOrDefault(false)) return
            }
        } catch (_: Exception) {}
        try {
            context.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        } catch (_: Exception) {}
        try {
            context.startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
        } catch (_: Exception) {}
    }
}
