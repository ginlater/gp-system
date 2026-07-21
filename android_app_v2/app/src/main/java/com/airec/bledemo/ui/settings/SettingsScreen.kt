package com.airec.bledemo.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.airec.bledemo.designsystem.components.MeiliBottomSheet
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.airec.bledemo.designsystem.Dimens
import com.airec.bledemo.designsystem.MeiliIcons
import com.airec.bledemo.designsystem.MeiliPalette
import com.airec.bledemo.designsystem.MeiliShapes
import com.airec.bledemo.designsystem.MeiliTheme
import com.airec.bledemo.designsystem.FontScaleManager
import com.airec.bledemo.designsystem.ThemeManager
import com.airec.bledemo.designsystem.components.GhostButton
import com.airec.bledemo.designsystem.components.MeiliButtonSize
import com.airec.bledemo.designsystem.components.MeiliCard
import com.airec.bledemo.designsystem.components.MeiliTopBar
import com.airec.bledemo.designsystem.components.PrimaryButton
import com.airec.bledemo.designsystem.components.SectionLabel
import com.airec.bledemo.designsystem.components.StatusPill
import com.airec.bledemo.designsystem.components.PillKind

/**
 * 设置 / 强制更新（SPEC §4.11）。
 *
 * 内容（暖玉柔光、克制）：
 *  - 顶栏「设置」+ 返回。
 *  - 陪伴师信息卡（头像首字 + 姓名 + 角色胶囊 + 用户名/手机）。
 *  - 关于「美丽陪伴」：当前版本（本机已装 versionName，从 PackageManager 实读）+ 最新版本。
 *    · installed < minVersionCode → 顶部红色【需要更新】不可忽略卡 + 「立即更新」打开下载。
 *    · 非强制但有新版 → 柔和蜜色「可更新」提示。
 *  - 退出登录（AuthManager.logout → 回登录）。
 *
 * @param onBack 返回上一页
 * @param onLoggedOut 退出登录后回调（上层把导航起点切回登录）
 * @param onSwitchWorkspace 「切换系统工作台」（仅多系统账号显示该区块；回工作台宫格选系统）
 * @param modifier 由 AppScaffold 传入
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit = {},
    onLoggedOut: () -> Unit = {},
    onSwitchWorkspace: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // ★2026-07-19 应用内政策页(vivo 合规):非空时整屏显示政策全文,返回回到设置页。
    var policyKind by remember { mutableStateOf<String?>(null) }
    if (policyKind != null) {
        com.airec.bledemo.privacy.PolicyScreen(
            kind = com.airec.bledemo.privacy.PolicyKind.byKey(policyKind!!),
            onBack = { policyKind = null },
            modifier = modifier,
        )
        return
    }

    // 本机已安装版本从 PackageManager 实读（AGP 未开 buildConfig，BuildConfig.VERSION_NAME 不可用），
    // 注入 ViewModel 后由其触发 me/version 加载（只触发一次）。
    LaunchedEffect(Unit) {
        val (name, code) = readInstalledVersion(context)
        viewModel.setInstalledVersion(name, code)
    }

    val openUpdate: () -> Unit = open@{
        val url = state.updateUrl ?: return@open
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            // 打不开浏览器时静默；用户可手动升级。不阻断本屏。
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MeiliPalette.Bg),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.ScreenH),
        ) {
            MeiliTopBar(title = "设置", onBack = onBack)

            // 强制升级卡：installed < min，置顶、红色、不可忽略
            if (state.mustUpgrade) {
                ForceUpgradeCard(
                    latestName = state.latestVersionName,
                    note = state.updateNote,
                    canOpen = state.updateUrl != null,
                    onUpdate = openUpdate,
                )
                Spacer(Modifier.height(Dimens.CardGap))
            }

            // 陪伴师信息
            SectionLabel("陪伴师", icon = MeiliIcons.Profile)
            Spacer(Modifier.height(9.dp))
            ConsultantCard(state = state)
            Spacer(Modifier.height(9.dp))
            // 修改密码(改统一密码,全系统同步)
            val showChangePwd = remember { mutableStateOf(false) }
            ChangePasswordEntry(onClick = { showChangePwd.value = true })
            if (showChangePwd.value) {
                ChangePasswordSheet(onDismiss = { showChangePwd.value = false })
            }
            Spacer(Modifier.height(Dimens.CardGap))

            // 多系统工作台（仅 ≥2 系统的账号显示；点击回工作台宫格选系统，不用退出重登）
            val meCache = com.airec.bledemo.data.auth.AuthManager.lastMe
            if (meCache?.multiSystem == true) {
                SectionLabel("工作台", icon = MeiliIcons.Workspace)
                Spacer(Modifier.height(9.dp))
                WorkspaceSwitchCard(
                    systemCount = meCache.systems?.size ?: 0,
                    onClick = onSwitchWorkspace,
                )
                Spacer(Modifier.height(Dimens.CardGap))
            }

            // 主题皮肤（方案A：4 套配色随便换，全 app 立即生效）
            SectionLabel("主题皮肤", icon = MeiliIcons.Palette)
            Spacer(Modifier.height(9.dp))
            ThemePickerCard()
            Spacer(Modifier.height(Dimens.CardGap))

            // 字体大小（5 档滑块，全 app 文字按倍数放大，选择持久化）
            SectionLabel("字体大小", icon = MeiliIcons.Doc)
            Spacer(Modifier.height(9.dp))
            FontScaleCard()
            Spacer(Modifier.height(Dimens.CardGap))

            // C7：后台保活引导（vivo 重点）——白名单系统框只弹一次、可拒，拒了以后这里能随时补救
            SectionLabel("后台保活", icon = MeiliIcons.Info)
            Spacer(Modifier.height(9.dp))
            KeepAliveCard()
            Spacer(Modifier.height(Dimens.CardGap))

            // 关于美丽陪伴 / 版本
            SectionLabel("关于美业私教", icon = MeiliIcons.Info)
            Spacer(Modifier.height(9.dp))
            AboutCard(
                state = state,
                onUpdate = openUpdate,
                onCheck = { viewModel.checkVersion() },
                onUploadDiag = { viewModel.uploadDiag() },
                onOpenPolicy = { policyKind = it },
            )
            Spacer(Modifier.height(Dimens.S6))

            // 退出登录
            GhostButton(
                text = if (state.loggingOut) "正在退出…" else "退出登录",
                onClick = { if (!state.loggingOut) viewModel.logout(onLoggedOut) },
                icon = MeiliIcons.Lock,
                enabled = !state.loggingOut,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(Dimens.S3))

            // ★2026-07-17 合规 —— 账号注销入口。
            // 小米驳回明确要求「应用内的账号注销入口」,还要录进演示视频;苹果 5.1.1(v)
            // 同样强制。注意跟「退出登录」是两码事:退出只是清会话,注销是真删账号。
            var showDeleteAccount by remember { mutableStateOf(false) }
            Text(
                text = "注销账号",
                style = MaterialTheme.typography.bodyMedium,
                color = MeiliPalette.Ink3,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showDeleteAccount = true }
                    .padding(vertical = Dimens.S3),
            )
            if (showDeleteAccount) {
                DeleteAccountDialog(
                    deleting = state.deletingAccount,
                    error = state.deleteAccountError,
                    onDismiss = {
                        showDeleteAccount = false
                        viewModel.clearDeleteAccountError()
                    },
                    onConfirm = { pw -> viewModel.deleteAccount(pw) { onLoggedOut() } },
                )
            }

            Spacer(Modifier.height(14.dp))
            Text(
                text = "美业私教 · 高端身体美容陪伴助手",
                style = MaterialTheme.typography.bodySmall,
                color = MeiliPalette.Ink3,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = Dimens.BottomNavInset),
            )
        }
    }
}

/**
 * ★2026-07-17 合规 —— 注销账号的二次确认弹窗。
 *
 * 【为什么要输密码】注销不可逆。手机放桌上被人顺手点两下就把号注销了,这种事必须堵死。
 * 密码由服务端校验(见后端 api_delete_my_account),前端不碰哈希。
 *
 * 【为什么要写清删什么留什么】注销 ≠ 数据全没。录音和报告是门店花钱买的经营资产,
 * 归门店所有、不跟着删(隐私政策已载明)。不讲清楚,员工会以为点了这个老板的报告就没了,
 * 或者反过来以为自己的记录能一键抹掉——两种误解都会出事。
 */
@Composable
private fun DeleteAccountDialog(
    deleting: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!deleting) onDismiss() },
        containerColor = MeiliPalette.Surface,
        titleContentColor = MeiliPalette.Ink,
        textContentColor = MeiliPalette.Ink2,
        title = { Text("注销账号", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column {
                Text(
                    "注销后将删除您的登录账号与个人信息（手机号、姓名、工号），此操作不可恢复，" +
                        "您将无法再用该账号登录。\n\n" +
                        "您此前录制的接待记录与分析报告属于所属机构的经营数据，将按隐私政策由机构继续保留，" +
                        "不会随注销一并删除。\n\n" +
                        "请输入登录密码以确认。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(Dimens.S3))
                OutlinedTextField(
                    value = pw,
                    onValueChange = { pw = it },
                    label = { Text("登录密码") },
                    singleLine = true,
                    enabled = !deleting,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Spacer(Modifier.height(Dimens.S2))
                    Text(error, style = MaterialTheme.typography.bodySmall, color = MeiliPalette.RoseText)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (!deleting && pw.isNotBlank()) onConfirm(pw) },
                enabled = !deleting && pw.isNotBlank(),
            ) {
                Text(
                    if (deleting) "注销中…" else "确认注销",
                    color = MeiliPalette.RoseText,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !deleting) {
                Text("取消", color = MeiliPalette.Ink2)
            }
        },
    )
}

/**
 * C7：后台保活体检卡。vivo 等国产 ROM 默认激进杀后台——录音/补传半路被掐。
 * 系统的白名单弹框只在首启弹一次、可拒；这里提供随时可点的三个入口：
 * 自启动管理（vivo 专属页，别机型兜底到应用详情）、电池优化白名单、应用详情（手动关"后台高耗电限制"）。
 */
@Composable
private fun KeepAliveCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    MeiliCard {
        Text(
            text = "为了录音和后台同步不被手机中断，建议逐个设置：允许自启动、忽略电池优化、后台高耗电改为「允许」。设置一次即可。",
            style = MaterialTheme.typography.bodySmall,
            color = MeiliPalette.Ink2,
        )
        Spacer(Modifier.height(11.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.fillMaxWidth()) {
            GhostButton(
                text = "自启动",
                onClick = { openVivoAutoStart(context) },
                size = MeiliButtonSize.Small,
                modifier = Modifier.weight(1f),
            )
            GhostButton(
                text = "电池优化",
                onClick = { openBatteryWhitelist(context) },
                size = MeiliButtonSize.Small,
                modifier = Modifier.weight(1f),
            )
            GhostButton(
                text = "应用详情",
                onClick = { openAppDetails(context) },
                size = MeiliButtonSize.Small,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** vivo 自启动管理页；非 vivo/打不开时兜底到本应用详情页（里面也有自启动/省电项）。 */
private fun openVivoAutoStart(context: android.content.Context) {
    val candidates = listOf(
        // vivo 新版 i管家 权限管理-自启动
        android.content.Intent().setClassName(
            "com.vivo.permissionmanager",
            "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        ),
        android.content.Intent().setClassName(
            "com.vivo.permissionmanager",
            "com.vivo.permissionmanager.activity.PurviewTabActivity",
        ),
        // vivo 后台高耗电
        android.content.Intent().setClassName(
            "com.vivo.abe",
            "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity",
        ),
    )
    for (i in candidates) {
        try {
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
            return
        } catch (_: Exception) {
        }
    }
    openAppDetails(context)
}

/** 请求加入电池优化白名单（系统标准弹框；已在白名单则打开电池优化列表）。 */
private fun openBatteryWhitelist(context: android.content.Context) {
    try {
        val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
        val intent = if (pm != null && !pm.isIgnoringBatteryOptimizations(context.packageName)) {
            android.content.Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:${context.packageName}"),
            )
        } else {
            android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        }
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (_: Exception) {
        openAppDetails(context)
    }
}

/** 本应用详情页（自启动/电池/通知等入口都在里面，万能兜底）。 */
private fun openAppDetails(context: android.content.Context) {
    try {
        val i = android.content.Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.parse("package:${context.packageName}"),
        )
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
    } catch (_: Exception) {
    }
}

/** 从 PackageManager 读本机已安装版本（versionName, versionCode）。取不到返回 ("—", -1)。 */
private fun readInstalledVersion(context: android.content.Context): Pair<String, Long> {
    return try {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        val name = pi.versionName ?: "—"
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode
        else @Suppress("DEPRECATION") pi.versionCode.toLong()
        name to code
    } catch (e: Exception) {
        "—" to -1L
    }
}

/* ───────────────────────── 修改密码 ───────────────────────── */

/** 「修改密码」入口卡。 */
@Composable
private fun ChangePasswordEntry(onClick: () -> Unit) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    MeiliCard(
        modifier = Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Icon(MeiliIcons.Lock, contentDescription = null, tint = MeiliPalette.Ink2, modifier = Modifier.size(Dimens.Icon))
            Spacer(Modifier.width(Dimens.S2))
            Column(Modifier.weight(1f)) {
                Text(
                    "修改密码",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MeiliPalette.Ink,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "改一次,全部工作台一起改",
                    style = MaterialTheme.typography.bodySmall,
                    color = MeiliPalette.Ink3,
                )
            }
            Icon(MeiliIcons.ChevRight, contentDescription = null, tint = MeiliPalette.Ink4, modifier = Modifier.size(Dimens.IconSm))
        }
    }
}

/** 修改密码底部弹窗:旧密码 + 新密码 + 确认 → 全系统同步。 */
@Composable
private fun ChangePasswordSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var oldPwd by remember { mutableStateOf("") }
    var newPwd by remember { mutableStateOf("") }
    var confirmPwd by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    MeiliBottomSheet(
        visible = true,
        onDismiss = { if (!busy) onDismiss() },
        title = "修改密码",
        subtitle = "改的是登录密码,工牌 + 各工作台会一起同步",
    ) {
        PwdField("原密码", oldPwd) { oldPwd = it; error = null }
        Spacer(Modifier.height(Dimens.S2))
        PwdField("新密码(至少 6 位)", newPwd) { newPwd = it; error = null }
        Spacer(Modifier.height(Dimens.S2))
        PwdField("确认新密码", confirmPwd) { confirmPwd = it; error = null }
        if (error != null) {
            Spacer(Modifier.height(Dimens.S2))
            Text(error!!, style = MaterialTheme.typography.bodySmall, color = MeiliPalette.RoseText)
        }
        Spacer(Modifier.height(Dimens.S4))
        PrimaryButton(
            text = if (busy) "提交中…" else "确认修改",
            onClick = {
                when {
                    oldPwd.isBlank() || newPwd.isBlank() -> error = "请填写完整"
                    newPwd.length < 6 -> error = "新密码至少 6 位"
                    newPwd != confirmPwd -> error = "两次新密码不一致"
                    newPwd == oldPwd -> error = "新密码不能与原密码相同"
                    else -> {
                        busy = true
                        error = null
                        scope.launch {
                            val r = com.airec.bledemo.data.auth.UnifiedPasswordRepository().changeAll(oldPwd, newPwd)
                            busy = false
                            if (r.ok) {
                                android.widget.Toast.makeText(context, r.message, android.widget.Toast.LENGTH_LONG).show()
                                onDismiss()
                            } else {
                                error = r.message
                            }
                        }
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Dimens.S2))
    }
}

@Composable
private fun PwdField(label: String, value: String, onChange: (String) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MeiliPalette.Ink3)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            shape = MeiliShapes.Sm,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MeiliPalette.Clay,
                unfocusedBorderColor = MeiliPalette.Line,
                focusedContainerColor = MeiliPalette.Surface,
                unfocusedContainerColor = MeiliPalette.SurfaceSoft,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/* ───────────────────────── 多系统工作台切换卡 ───────────────────────── */

/** 「切换系统工作台」入口卡（仅多系统账号显示）：点击回工作台宫格。 */
@Composable
private fun WorkspaceSwitchCard(systemCount: Int, onClick: () -> Unit) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    MeiliCard(
        modifier = Modifier.clickable(
            interactionSource = interaction,
            indication = null,
            onClick = onClick,
        ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "切换系统工作台",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MeiliPalette.Ink,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "本账号已开通 $systemCount 个系统，点击切换",
                    style = MaterialTheme.typography.bodySmall,
                    color = MeiliPalette.Ink3,
                )
            }
            Icon(MeiliIcons.ChevRight, contentDescription = null, tint = MeiliPalette.Ink4, modifier = Modifier.size(Dimens.IconSm))
        }
    }
}

/* ───────────────────────── 陪伴师信息卡 ───────────────────────── */

@Composable
private fun ConsultantCard(state: SettingsUiState) {
    MeiliCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 头像（首字）
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MeiliPalette.ClayTint,
                contentColor = MeiliPalette.ClayDeep,
                modifier = Modifier.size(Dimens.Avatar),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = state.avatarChar,
                        style = MaterialTheme.typography.titleLarge,
                        color = MeiliPalette.ClayDeep,
                    )
                }
            }
            Spacer(Modifier.width(13.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.displayName,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold,
                    ),
                    color = MeiliPalette.Ink,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(state.roleLabel, PillKind.Run, icon = MeiliIcons.Profile)
                    val sub = state.me?.username?.takeIf { it.isNotBlank() }
                    if (sub != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = sub,
                            style = MaterialTheme.typography.bodySmall,
                            color = MeiliPalette.Ink3,
                        )
                    }
                }
            }
        }

        val phone = state.me?.phone?.takeIf { it.isNotBlank() }
        if (phone != null) {
            Spacer(Modifier.height(13.dp))
            KvRow(key = "联系电话", value = phone, divider = false)
        }
        if (state.meLoading && state.me == null) {
            Spacer(Modifier.height(13.dp))
            Text(
                text = "正在载入陪伴师信息…",
                style = MaterialTheme.typography.bodySmall,
                color = MeiliPalette.Ink3,
            )
        } else if (state.me == null && state.meError != null) {
            Spacer(Modifier.height(13.dp))
            Text(
                text = state.meError,
                style = MaterialTheme.typography.bodySmall,
                color = MeiliPalette.RoseText,
            )
        }
    }
}

/* ───────────────────────── 字体大小 ───────────────────────── */

/**
 * 字体大小：5 档滑块（小 / 标准 / 大 / 更大 / 特大）。
 * 拖动即经 [FontScaleManager] 改倍数 → [MeiliTheme] 缩放全 app 文字（含本卡片预览，实时可见）。
 */
@Composable
private fun FontScaleCard() {
    val level = FontScaleManager.level
    MeiliCard {
        Text(
            "调整整个 App 的文字大小。拖动下面的滑块即可，选好会自动记住。",
            style = MaterialTheme.typography.bodySmall,
            color = MeiliPalette.Ink3,
        )
        Spacer(Modifier.height(14.dp))
        // 预览行：本卡片也在 MeiliTheme 内，字号随滑块实时变化 → 拖动所见即所得
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MeiliPalette.SurfaceSoft,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "预览：美业私教 · 接待记录 Aa 123",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = MeiliPalette.Ink,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        androidx.compose.material3.Slider(
            value = level.toFloat(),
            onValueChange = { FontScaleManager.setLevel(Math.round(it)) },
            valueRange = 0f..FontScaleManager.steps.lastIndex.toFloat(),
            steps = FontScaleManager.steps.size - 2, // 5 档 → 中间 3 个刻度点
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = MeiliPalette.Clay,
                activeTrackColor = MeiliPalette.Clay,
                inactiveTrackColor = MeiliPalette.Line,
                activeTickColor = MeiliPalette.White,
                inactiveTickColor = MeiliPalette.Line,
            ),
        )
        // 档位标签：当前档加粗高亮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            FontScaleManager.labels.forEachIndexed { i, l ->
                Text(
                    l,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = if (i == level) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 11.sp,
                    ),
                    color = if (i == level) MeiliPalette.ClayDeep else MeiliPalette.Ink3,
                )
            }
        }
    }
}

/* ───────────────────────── 主题皮肤选择 ───────────────────────── */

/**
 * 主题皮肤：
 *  - 「自动日夜切换」开关：开 → 晚 18:00–早 6:00 用夜间皮肤，白天用白天皮肤；
 *    下方段控切「白天 / 晚上」，分别给两个时段挑皮肤（默认 白天=暖玉柔光、晚上=曜夜鎏金）。
 *  - 关 → 单选一套手动皮肤（点某套即关自动、记为手动）。
 */
@Composable
private fun ThemePickerCard() {
    val auto = ThemeManager.autoMode
    var editNight by remember { mutableStateOf(ThemeManager.isNightNow()) }
    MeiliCard {
        // ── 自动日夜切换开关 ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "自动日夜切换",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, fontSize = 13.5f.sp),
                    color = MeiliPalette.Ink,
                )
                Text(
                    "晚 18:00–早 6:00 自动用夜间皮肤",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = MeiliPalette.Ink3,
                )
            }
            androidx.compose.material3.Switch(
                checked = auto,
                onCheckedChange = { ThemeManager.setAuto(it) },
                colors = androidx.compose.material3.SwitchDefaults.colors(
                    checkedThumbColor = MeiliPalette.White,
                    checkedTrackColor = MeiliPalette.Clay,
                    uncheckedThumbColor = MeiliPalette.White,
                    uncheckedTrackColor = MeiliPalette.Line,
                    uncheckedBorderColor = MeiliPalette.Line,
                ),
            )
        }
        Spacer(Modifier.height(13.dp))

        if (auto) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                SegChip("☀ 白天", selected = !editNight, onClick = { editNight = false }, modifier = Modifier.weight(1f))
                SegChip("🌙 晚上", selected = editNight, onClick = { editNight = true }, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (editNight) "夜间（18:00–6:00）用这套：" else "白天（6:00–18:00）用这套：",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5f.sp),
                color = MeiliPalette.Ink3,
            )
            Spacer(Modifier.height(11.dp))
        } else {
            Text(
                "选一套喜欢的配色，整个 App 会跟着变。",
                style = MaterialTheme.typography.bodySmall,
                color = MeiliPalette.Ink3,
            )
            Spacer(Modifier.height(12.dp))
        }

        val targetId = when {
            !auto -> ThemeManager.currentId
            editNight -> ThemeManager.nightSkinId
            else -> ThemeManager.daySkinId
        }
        ThemeManager.skins.forEachIndexed { i, skin ->
            SkinRow(
                skin = skin,
                selected = skin.id == targetId,
                last = i == ThemeManager.skins.lastIndex,
                onClick = {
                    when {
                        !auto -> ThemeManager.apply(skin.id)
                        editNight -> ThemeManager.setNightSkin(skin.id)
                        else -> ThemeManager.setDaySkin(skin.id)
                    }
                },
            )
        }
    }
}

/** 段控小胶囊：白天 / 晚上。 */
@Composable
private fun SegChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = MeiliShapes.Sm,
        color = if (selected) MeiliPalette.ClayTint else MeiliPalette.SurfaceSoft,
        contentColor = if (selected) MeiliPalette.ClayDeep else MeiliPalette.Ink2,
        border = androidx.compose.foundation.BorderStroke(
            Dimens.BorderField,
            if (selected) MeiliPalette.Clay else MeiliPalette.Line,
        ),
        modifier = modifier,
    ) {
        Box(modifier = Modifier.padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
            Text(
                text,
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
        }
    }
}

/** 单套皮肤行：色卡（底色环+主色点）+ 名称/描述 + 选中描边/对勾。 */
@Composable
private fun SkinRow(skin: ThemeManager.Skin, selected: Boolean, last: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MeiliShapes.Sm,
        color = if (selected) MeiliPalette.ClayTint else MeiliPalette.Surface,
        contentColor = MeiliPalette.Ink,
        border = androidx.compose.foundation.BorderStroke(
            Dimens.BorderField,
            if (selected) MeiliPalette.Clay else MeiliPalette.Line,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = if (last) 0.dp else 9.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 色卡：外圈=该皮肤底色（黑金会是近黑），内圆=主色 → 一眼看出深浅
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .background(skin.bg, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(15.dp)
                        .background(
                            Brush.radialGradient(listOf(lerp(skin.clay, Color.White, 0.30f), skin.clay)),
                            CircleShape,
                        ),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = skin.name,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, fontSize = 13.5f.sp),
                    color = MeiliPalette.Ink,
                )
                Text(
                    text = skin.desc,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = MeiliPalette.Ink3,
                )
            }
            if (selected) {
                Icon(
                    MeiliIcons.Check,
                    contentDescription = "已选",
                    tint = MeiliPalette.ClayDeep,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/* ───────────────────────── 关于 / 版本卡 ───────────────────────── */

@Composable
private fun AboutCard(
    state: SettingsUiState,
    onUpdate: () -> Unit,
    onCheck: () -> Unit,
    onUploadDiag: () -> Unit,
    onOpenPolicy: (String) -> Unit,
) {
    val hasUpdate = state.updateAvailable || state.mustUpgrade
    MeiliCard {
        KvRow(
            key = "当前版本",
            value = state.installedVersionName,
            divider = true,
        )
        // ★2026-07-16 工信部《移动互联网应用程序备案》强制要求：备案号必须在 App 内展示。
        //   应用商店上架审核会专门核查这一项。
        KvRow(
            key = "ICP 备案号",
            value = "蜀ICP备2024099992号-4A",
            divider = true,
        )
        // 检查更新行：左侧标题 + 状态文案；右侧「检查 / 去更新」
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "检查更新",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, fontSize = 13.5f.sp),
                    color = MeiliPalette.Ink,
                )
                Text(
                    text = when {
                        state.versionLoading -> "正在为你查看最新版…"
                        hasUpdate -> "发现新版本" + (state.latestVersionName?.let { " · $it" } ?: "")
                        state.versionCheckDone -> "已是最新版本，无需更新"
                        else -> "看看有没有更顺手的新版本"
                    },
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5f.sp),
                    color = if (hasUpdate) MeiliPalette.ClayDeep else MeiliPalette.Ink3,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            if (hasUpdate) {
                PrimaryButton(
                    text = "去更新",
                    onClick = onUpdate,
                    icon = MeiliIcons.Upload,
                    size = MeiliButtonSize.Xs,
                )
            } else {
                GhostButton(
                    text = if (state.versionLoading) "检查中" else "检查",
                    onClick = onCheck,
                    enabled = !state.versionLoading,
                    size = MeiliButtonSize.Xs,
                )
            }
        }
        // 上传运行诊断行：陪伴笔出问题时一键把运行日志发给工程师远程排查
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "上传运行诊断",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, fontSize = 13.5f.sp),
                    color = MeiliPalette.Ink,
                )
                Text(
                    text = state.diagResult ?: "陪伴笔遇到问题时，一键把运行日志发给工程师远程排查",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5f.sp),
                    color = MeiliPalette.Ink3,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            GhostButton(
                text = if (state.diagUploading) "上传中" else "上传",
                onClick = onUploadDiag,
                enabled = !state.diagUploading,
                size = MeiliButtonSize.Xs,
            )
        }
        // 隐私政策 / 用户协议入口（合规：App 内须有可随时查看隐私政策的入口）
        // ★2026-07-19 vivo 驳回「APP内部无隐私政策」——原来点这里跳外部浏览器,商店不认。
        // 改为打开应用内政策页(PolicyScreen,内容打在包里,离线可读)。
        // 商店后台提交的网址仍是 PrivacyConsent.PRIVACY_URL,与应用内内容同源同文。
        PolicyRow("隐私政策", "了解我们如何收集与使用信息") { onOpenPolicy("privacy") }
        PolicyRow("用户协议", "使用美业私教的服务条款") { onOpenPolicy("terms") }
    }
}

/** 关于卡内的「隐私政策 / 用户协议」行：右侧「查看」点开公网政策页。 */
@Composable
private fun PolicyRow(title: String, sub: String, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, fontSize = 13.5f.sp),
                color = MeiliPalette.Ink,
            )
            Text(
                sub,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5f.sp),
                color = MeiliPalette.Ink3,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        GhostButton(text = "查看", onClick = onOpen, size = MeiliButtonSize.Xs)
    }
}

/* ───────────────────────── 强制升级卡（不可忽略） ───────────────────────── */

@Composable
private fun ForceUpgradeCard(
    latestName: String?,
    note: String?,
    canOpen: Boolean,
    onUpdate: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MeiliShapes.Lg,
        color = MeiliPalette.RoseSoft,
        contentColor = MeiliPalette.RoseText,
        border = androidx.compose.foundation.BorderStroke(Dimens.BorderField, MeiliPalette.RoseLine),
        shadowElevation = Dimens.Elev2,
    ) {
        Column(modifier = Modifier.padding(Dimens.CardPad)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    MeiliIcons.Warn,
                    contentDescription = null,
                    tint = MeiliPalette.RoseText,
                    modifier = Modifier.size(Dimens.IconSm),
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    text = "需要更新" + (latestName?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                    ),
                    color = MeiliPalette.RoseText,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = note?.takeIf { it.isNotBlank() }
                    ?: "您当前的版本过旧，需更新到最新版后才能继续使用美业私教。",
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 19.sp),
                color = MeiliPalette.RoseText,
            )
            Spacer(Modifier.height(14.dp))
            PrimaryButton(
                text = if (canOpen) "立即更新" else "请联系管理员获取新版",
                onClick = onUpdate,
                icon = if (canOpen) MeiliIcons.Upload else null,
                enabled = canOpen,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/* ───────────────────────── 小组件 ───────────────────────── */

/** key-value 行，还原 warm_2 .kv（虚线下划线，左 ink-2 / 右 700）。 */
@Composable
private fun KvRow(key: String, value: String, divider: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = key,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.5f.sp),
            color = MeiliPalette.Ink2,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 12.5f.sp,
                fontWeight = FontWeight.Bold,
            ),
            color = MeiliPalette.Ink,
        )
    }
    if (divider) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MeiliPalette.Line),
        )
    }
}

private enum class NoticeTone { Honey, Sage }

/** 卡内柔和提示行（蜜色=有更新 / 鼠尾草=已最新）。 */
@Composable
private fun NoticeRow(
    icon: ImageVector,
    tone: NoticeTone,
    title: String,
    body: String?,
) {
    val (bg, fg) = when (tone) {
        NoticeTone.Honey -> MeiliPalette.HoneySoft to MeiliPalette.HoneyText
        NoticeTone.Sage -> MeiliPalette.SageTint to MeiliPalette.SageDeep
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MeiliShapes.Md,
        color = bg,
        contentColor = fg,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier
                    .padding(top = 1.dp)
                    .size(Dimens.IconSm),
            )
            Spacer(Modifier.width(9.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 12.5f.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = fg,
                )
                if (!body.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                        color = fg,
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 760)
@Composable
private fun SettingsScreenPreview() {
    MeiliTheme {
        // 预览用纯静态内容（不触发 ViewModel 的网络/PackageManager），见 SettingsPreviewBody。
        SettingsPreviewBody(
            SettingsUiState(
                installedVersionName = "2.0.8",
                installedVersionCode = 9,
                me = com.airec.bledemo.data.model.Me(
                    username = "zhangmin",
                    role = "consultant",
                    advisorName = "张敏",
                    phone = "138****6677",
                ),
                appVersion = com.airec.bledemo.data.model.AppVersion(
                    latestVersionCode = 10,
                    latestVersionName = "2.0.9",
                    minVersionCode = 9,
                    apkUrl = "https://gp.beautyshining.com/download/app.apk",
                    updateNote = "优化陪伴笔续传稳定性，修复若干问题。",
                ),
                updateAvailable = true,
            )
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 760)
@Composable
private fun SettingsScreenForcePreview() {
    MeiliTheme {
        SettingsPreviewBody(
            SettingsUiState(
                installedVersionName = "1.9.0",
                installedVersionCode = 7,
                me = com.airec.bledemo.data.model.Me(
                    username = "lihua",
                    role = "store_manager",
                    advisorName = "李华",
                ),
                appVersion = com.airec.bledemo.data.model.AppVersion(
                    latestVersionCode = 9,
                    latestVersionName = "2.0.8",
                    minVersionCode = 9,
                    apkUrl = "https://gp.beautyshining.com/download/app.apk",
                    updateNote = "本次为强制升级，必须更新后才能继续使用。",
                ),
                mustUpgrade = true,
            )
        )
    }
}

/** 仅供 @Preview 用的静态主体（与真实 SettingsScreen 同布局，但不依赖 ViewModel）。 */
@Composable
private fun SettingsPreviewBody(state: SettingsUiState) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MeiliPalette.Bg),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.ScreenH),
        ) {
            MeiliTopBar(title = "设置", onBack = {})
            if (state.mustUpgrade) {
                ForceUpgradeCard(state.latestVersionName, state.updateNote, state.updateUrl != null, {})
                Spacer(Modifier.height(Dimens.CardGap))
            }
            SectionLabel("陪伴师", icon = MeiliIcons.Profile)
            Spacer(Modifier.height(9.dp))
            ConsultantCard(state = state)
            Spacer(Modifier.height(Dimens.CardGap))
            SectionLabel("关于美业私教", icon = MeiliIcons.Info)
            Spacer(Modifier.height(9.dp))
            AboutCard(state = state, onUpdate = {}, onCheck = {}, onUploadDiag = {}, onOpenPolicy = {})
            Spacer(Modifier.height(Dimens.S6))
            GhostButton("退出登录", {}, icon = MeiliIcons.Lock, modifier = Modifier.fillMaxWidth())
        }
    }
}
