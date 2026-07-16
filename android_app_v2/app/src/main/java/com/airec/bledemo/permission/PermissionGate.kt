package com.airec.bledemo.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import com.airec.bledemo.designsystem.MeiliPalette

/**
 * ★2026-07-16 隐私合规整改 —— 运行时权限的统一入口。
 *
 * 【为什么有这个文件】应用商店隐私检测驳回两条：
 *  1. **未告知申请权限目的**：原来直接弹系统授权框，没有任何说明。
 *     监管要求「通过弹窗、文字、蒙层等形式，在申请前同步告知目的和用途」。
 *  2. **过度申请权限**：原来 MeiliActivity.onCreate 里一次性申请麦克风/定位/蓝牙，
 *     App 一启动就弹（检测日志实锤：启动 5 秒后即申请位置权限组），
 *     那时用户根本没使用对应功能。监管要求「按业务功能实际需要逐步申请」。
 *
 * 【本文件的规矩】
 *  - **谁用谁申请**：录音功能触发才申请麦克风；连笔才申请蓝牙；不提前、不搭车。
 *  - **申请前必先说明**：先弹我们自己的说明框讲清用途，用户点「去授权」才拉起系统框。
 *  - **拒绝不纠缠**：拒绝后走 onDenied 降级提示，不循环弹（监管也整治「反复弹窗」）。
 *
 * ⚠️ 新增任何权限申请，一律走这里，不要再直接调 launcher。
 */

/** 一类功能所需的权限组 + 给用户看的说明。 */
enum class PermissionPurpose(
    val title: String,
    val reason: String,
) {
    /** 手机麦克风录音。 */
    Record(
        title = "需要麦克风权限",
        reason = "用于录制您与顾客的接待过程，生成您本人的接待记录与复盘报告。\n\n" +
            "录音由您主动点击开始，不会自动开启；您可随时在系统设置中关闭该权限。",
    ),

    /** 连接蓝牙陪伴笔（安卓 11 及以下会附带定位，说明里必须写清楚）。 */
    Pen(
        title = "需要蓝牙权限",
        reason = "用于连接您的蓝牙陪伴笔，读取笔中录制的接待内容。\n\n" +
            "在安卓 11 及以下的系统上，系统规定「扫描蓝牙设备」必须一并授予位置权限——" +
            "我们仅用它扫描设备，不会采集、不会存储您的地理位置信息。",
    ),

    /** 待办提醒通知。 */
    Notify(
        title = "需要通知权限",
        reason = "用于提醒您有待绑定的接待记录、待查看的复盘报告等待办事项。\n\n" +
            "不接收也不影响其他功能的正常使用。",
    ),
    ;

    /** 该用途在当前系统版本上真正需要申请的权限（按版本裁剪，不多要一个）。 */
    fun permissions(): List<String> = when (this) {
        Record -> listOf(Manifest.permission.RECORD_AUDIO)
        Pen -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // 安卓 12+：用「附近的设备」，且 manifest 已标 neverForLocation，不需要定位
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            // 安卓 11-：系统规定扫描蓝牙需要定位权限
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        Notify -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyList()   // 安卓 12 及以下无需申请
        }
    }
}

/** 该用途的权限是否已全部到手（到手就别再弹说明，直接干活）。 */
fun PermissionPurpose.isGranted(ctx: Context): Boolean =
    permissions().all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }

/**
 * ★2026-07-16 隐私合规 —— 「拒绝后不再纠缠」的记账本。
 *
 * 监管明令整治「用户明确拒绝权限申请后，频繁弹窗、反复申请」。
 * 真机实测发现:通知权限的说明框挂在提醒页,用户点了「暂不」,**退出再进又弹**——
 * 因为原来的「已问过」标记是 Compose 的 remember,页面一销毁就重置。
 *
 * 这里改为**进程级**记账:某个用途被用户拒绝过,本次进程内不再主动弹说明框。
 * (进程重启后允许再问一次——这是行业惯例，也给用户回心转意的机会；
 *  真要用该功能时用户会主动点按钮，那条路径仍会弹，不算「主动骚扰」。)
 */
object PermissionMemo {
    private val declined = mutableSetOf<PermissionPurpose>()

    fun markDeclined(p: PermissionPurpose) { declined.add(p) }
    fun wasDeclined(p: PermissionPurpose): Boolean = p in declined
    fun clear(p: PermissionPurpose) { declined.remove(p) }
}

/**
 * 权限申请器。用法：
 * ```
 * val gate = rememberPermissionGate()
 * // 用户点「开启陪伴」时：
 * gate.require(PermissionPurpose.Record, onGranted = { vm.toggleCompanion() })
 * ```
 * 已授权 → 直接执行 onGranted，不打扰；未授权 → 先弹说明，同意后才拉系统框。
 */
class PermissionGate internal constructor(
    private val ctx: Context,
    private val showRationale: (PermissionPurpose, () -> Unit, () -> Unit) -> Unit,
) {
    /**
     * @param passive true=页面自动触发的(非用户点按钮)。这类申请**一旦被拒过，本进程内不再弹**，
     *   避免「用户拒绝后反复弹窗」(监管整治项)。用户主动点功能按钮的申请传 false(默认)，
     *   因为那是用户当下的明确意图，弹说明是应该的。
     */
    fun require(
        purpose: PermissionPurpose,
        passive: Boolean = false,
        onDenied: (() -> Unit)? = null,
        onGranted: () -> Unit,
    ) {
        if (purpose.permissions().isEmpty() || purpose.isGranted(ctx)) {
            onGranted()
            return
        }
        // 被动申请 + 之前拒过 → 闭嘴，别再骚扰
        if (passive && PermissionMemo.wasDeclined(purpose)) return
        showRationale(purpose, onGranted, onDenied ?: {})
    }
}

@Composable
fun rememberPermissionGate(): PermissionGate {
    val ctx = LocalContext.current
    var pending by remember {
        mutableStateOf<Triple<PermissionPurpose, () -> Unit, () -> Unit>?>(null)
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val p = pending
        pending = null
        if (p == null) return@rememberLauncherForActivityResult
        // 全给了才算成功；拒绝就走降级回调，不再二次弹（监管整治「反复申请」）
        if (result.values.all { it }) {
            PermissionMemo.clear(p.first)   // 给了就清账
            p.second()
        } else {
            PermissionMemo.markDeclined(p.first)   // 系统框里拒的,同样别再骚扰
            p.third()
        }
    }

    // 说明框：申请前同步告知目的与用途（监管硬性要求的「弹窗/蒙层」）
    pending?.let { (purpose, onGranted, onDenied) ->
        AlertDialog(
            onDismissRequest = {
                pending = null
                PermissionMemo.markDeclined(purpose)   // 记一笔:被拒过,被动申请别再弹
                onDenied()
            },
            containerColor = MeiliPalette.Surface,
            titleContentColor = MeiliPalette.Ink,
            textContentColor = MeiliPalette.Ink2,
            title = { Text(purpose.title, style = MaterialTheme.typography.titleLarge) },
            text = { Text(purpose.reason, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = {
                    val perms = purpose.permissions().toTypedArray()
                    // 说明已展示 → 现在才拉系统框（pending 保留，等 launcher 回调消费）
                    launcher.launch(perms)
                }) {
                    Text("去授权", color = MeiliPalette.ClayDeep, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    pending = null
                    PermissionMemo.markDeclined(purpose)   // 记一笔:被拒过,被动申请别再弹
                    onDenied()
                }) {
                    Text("暂不", color = MeiliPalette.Ink2)
                }
            },
        )
    }

    return remember {
        PermissionGate(ctx) { purpose, onGranted, onDenied ->
            pending = Triple(purpose, onGranted, onDenied)
        }
    }
}
