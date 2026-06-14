package com.airec.bledemo.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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
 * @param modifier 由 AppScaffold 传入
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit = {},
    onLoggedOut: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

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
            Spacer(Modifier.height(Dimens.CardGap))

            // 主题皮肤（方案A：4 套配色随便换，全 app 立即生效）
            SectionLabel("主题皮肤", icon = MeiliIcons.Palette)
            Spacer(Modifier.height(9.dp))
            ThemePickerCard()
            Spacer(Modifier.height(Dimens.CardGap))

            // 关于美丽陪伴 / 版本
            SectionLabel("关于美丽陪伴", icon = MeiliIcons.Info)
            Spacer(Modifier.height(9.dp))
            AboutCard(
                state = state,
                onUpdate = openUpdate,
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

            Spacer(Modifier.height(14.dp))
            Text(
                text = "美丽陪伴 · 高端身体美容陪伴助手",
                style = MaterialTheme.typography.bodySmall,
                color = MeiliPalette.Ink3,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = Dimens.BottomNavInset),
            )
        }
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

/* ───────────────────────── 主题皮肤选择 ───────────────────────── */

/** 4 套配色皮肤单选：色卡圆点 + 名称/描述 + 选中描边/对勾。点选即 [ThemeManager.apply] → 全 app 换色。 */
@Composable
private fun ThemePickerCard() {
    val currentId = ThemeManager.currentId
    MeiliCard {
        Text(
            text = "选一套喜欢的配色，整个 App 会跟着变。",
            style = MaterialTheme.typography.bodySmall,
            color = MeiliPalette.Ink3,
        )
        Spacer(Modifier.height(12.dp))
        ThemeManager.skins.forEachIndexed { i, skin ->
            val selected = skin.id == currentId
            Surface(
                onClick = { ThemeManager.apply(skin.id) },
                shape = MeiliShapes.Sm,
                color = if (selected) MeiliPalette.ClayTint else MeiliPalette.Surface,
                contentColor = MeiliPalette.Ink,
                border = androidx.compose.foundation.BorderStroke(
                    Dimens.BorderField,
                    if (selected) MeiliPalette.Clay else MeiliPalette.Line,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = if (i == ThemeManager.skins.lastIndex) 0.dp else 9.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .background(
                                Brush.radialGradient(listOf(lerp(skin.clay, Color.White, 0.30f), skin.clay)),
                                CircleShape,
                            ),
                    )
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
    }
}

/* ───────────────────────── 关于 / 版本卡 ───────────────────────── */

@Composable
private fun AboutCard(
    state: SettingsUiState,
    onUpdate: () -> Unit,
) {
    MeiliCard {
        KvRow(
            key = "当前版本",
            value = state.installedVersionName,
            divider = true,
        )
        KvRow(
            key = "最新版本",
            value = state.latestVersionName ?: if (state.versionLoading) "检查中…" else "—",
            divider = false,
        )

        when {
            // 非强制更新：有新版 → 蜜色柔和提示 + 「更新」轻按钮
            state.updateAvailable -> {
                Spacer(Modifier.height(14.dp))
                NoticeRow(
                    icon = MeiliIcons.Spark,
                    tone = NoticeTone.Honey,
                    title = "有可用更新" + (state.latestVersionName?.let { " · $it" } ?: ""),
                    body = state.updateNote,
                )
                if (state.updateUrl != null) {
                    Spacer(Modifier.height(11.dp))
                    PrimaryButton(
                        text = "立即更新",
                        onClick = onUpdate,
                        icon = MeiliIcons.Upload,
                        size = MeiliButtonSize.Small,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            // 已是最新
            !state.versionLoading && state.latestVersionName != null && !state.mustUpgrade -> {
                Spacer(Modifier.height(14.dp))
                NoticeRow(
                    icon = MeiliIcons.Check,
                    tone = NoticeTone.Sage,
                    title = "已是最新版本",
                    body = null,
                )
            }
        }
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
                    ?: "您当前的版本过旧，需更新到最新版后才能继续使用美丽陪伴。",
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
                    apkUrl = "https://gp.aibeautyfulwomen.com/download/app.apk",
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
                    apkUrl = "https://gp.aibeautyfulwomen.com/download/app.apk",
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
            SectionLabel("关于美丽陪伴", icon = MeiliIcons.Info)
            Spacer(Modifier.height(9.dp))
            AboutCard(state = state, onUpdate = {})
            Spacer(Modifier.height(Dimens.S6))
            GhostButton("退出登录", {}, icon = MeiliIcons.Lock, modifier = Modifier.fillMaxWidth())
        }
    }
}
