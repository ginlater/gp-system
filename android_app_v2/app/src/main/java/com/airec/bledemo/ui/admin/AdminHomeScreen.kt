package com.airec.bledemo.ui.admin

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.airec.bledemo.designsystem.Dimens
import com.airec.bledemo.designsystem.MeiliIcons
import com.airec.bledemo.designsystem.MeiliShapes
import com.airec.bledemo.designsystem.MeiliTheme
import com.airec.bledemo.designsystem.components.GhostButton
import com.airec.bledemo.designsystem.components.MeiliButtonSize
import com.airec.bledemo.designsystem.components.SectionLabel
import com.airec.bledemo.designsystem.components.StatusPill
import com.airec.bledemo.designsystem.components.PillKind

/**
 * 管理台首页（admin / super 的 landing）。
 *
 * 结构（暖玉柔光、克制高级）：
 *  - 顶部「管理台」标题 + 管理员姓名/角色卡 + 退出登录。
 *  - 4 个分组（[SectionLabel] 起头），共 17 个管理模块卡：看板与预警 / 审批 / 人·店·号管理 / 标签与项目。
 *
 * 路由策略（WAVE 1）：只有「运营看板」落地真实屏——点它走 [onOpenModule]("ops_dashboard")，
 * 由 AppScaffold 导航到 Routes.AdminOps；其余模块点了只冒一句 toast「即将上线」（可见但明确未建）。
 * 加真实屏只需：给该模块 key 在 AppScaffold 的 onOpenModule when 里加一条导航 + 这里把它从「即将上线」放出来。
 *
 * 这是内部管理界面：词汇对齐 web admin.html（运营看板/顾问/门店/接诊 等），不走顾客可见的「陪伴」红线。
 *
 * @param onOpenModule 打开某个已落地的管理模块（传 moduleKey，如 "ops_dashboard"）
 * @param onLoggedOut 退出登录后回调（上层把导航起点切回登录）
 * @param modifier 由 AppScaffold 传入
 */
@Composable
fun AdminHomeScreen(
    onOpenModule: (moduleKey: String) -> Unit = {},
    onLoggedOut: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: AdminHomeViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    AdminHomeContent(
        state = state,
        onModuleClick = { module ->
            if (module.ready) {
                onOpenModule(module.key)
            } else {
                Toast.makeText(context, NOT_READY_MSG, Toast.LENGTH_SHORT).show()
            }
        },
        onLogout = { if (!state.loggingOut) viewModel.logout(onLoggedOut) },
        modifier = modifier,
    )
}

private const val NOT_READY_MSG = "该模块即将上线（当前请用网页版管理后台）"

@Composable
private fun AdminHomeContent(
    state: AdminHomeUiState,
    onModuleClick: (AdminModule) -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MeiliTheme.colors.bg),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.ScreenH)
                .padding(bottom = Dimens.BottomNavInset),
        ) {
            // 顶栏：标题（管理台是栈内根，不给返回；退出登录在账号卡里）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "管理台",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MeiliTheme.colors.ink,
                    modifier = Modifier.weight(1f),
                )
            }

            // 管理员信息卡 + 退出登录
            AdminProfileCard(state = state, onLogout = onLogout)
            Spacer(Modifier.height(Dimens.CardGap))

            // 4 个分组模块卡
            AdminGroups.forEachIndexed { index, group ->
                if (index > 0) Spacer(Modifier.height(Dimens.S5))
                SectionLabel(text = group.label, icon = group.icon)
                Spacer(Modifier.height(10.dp))
                group.modules.forEachIndexed { i, m ->
                    if (i > 0) Spacer(Modifier.height(10.dp))
                    ModuleRow(module = m, onClick = { onModuleClick(m) })
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = "美丽陪伴 · 管理台",
                style = MaterialTheme.typography.bodySmall,
                color = MeiliTheme.colors.ink3,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/* ───────────────────────── 管理员信息卡 ───────────────────────── */

@Composable
private fun AdminProfileCard(state: AdminHomeUiState, onLogout: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MeiliShapes.Lg,
        color = MeiliTheme.colors.surface,
        contentColor = MeiliTheme.colors.ink,
        border = androidx.compose.foundation.BorderStroke(Dimens.BorderThin, MeiliTheme.colors.lineSoft),
        shadowElevation = Dimens.Elev2,
        tonalElevation = 0.dp,
    ) {
        Column(modifier = Modifier.padding(Dimens.CardPad)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MeiliTheme.colors.clayTint,
                    contentColor = MeiliTheme.colors.clayDeep,
                    modifier = Modifier.size(Dimens.Avatar),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = state.avatarChar,
                            style = MaterialTheme.typography.titleLarge,
                            color = MeiliTheme.colors.clayDeep,
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
                        color = MeiliTheme.colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusPill(state.roleLabel, PillKind.Clay, icon = MeiliIcons.Lock)
                        val sub = state.me?.username?.takeIf { it.isNotBlank() }
                        if (sub != null) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = sub,
                                style = MaterialTheme.typography.bodySmall,
                                color = MeiliTheme.colors.ink3,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            GhostButton(
                text = if (state.loggingOut) "正在退出…" else "退出登录",
                onClick = onLogout,
                icon = MeiliIcons.Lock,
                enabled = !state.loggingOut,
                size = MeiliButtonSize.Small,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/* ───────────────────────── 模块行卡 ───────────────────────── */

/** 单个模块卡：图标 chip + 标题(+一行说明) + 右侧 chevron / 「即将上线」标。 */
@Composable
private fun ModuleRow(module: AdminModule, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        shape = MeiliShapes.Md,
        color = MeiliTheme.colors.surface,
        contentColor = MeiliTheme.colors.ink,
        border = androidx.compose.foundation.BorderStroke(Dimens.BorderThin, MeiliTheme.colors.lineSoft),
        shadowElevation = Dimens.Elev1,
        tonalElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .scale(if (pressed) 0.985f else 1f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 图标 chip
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(
                        color = if (module.ready) MeiliTheme.colors.clayTint else MeiliTheme.colors.surfaceSoft,
                        shape = MeiliShapes.Sm,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    module.icon,
                    contentDescription = null,
                    tint = if (module.ready) MeiliTheme.colors.clayDeep else MeiliTheme.colors.ink3,
                    modifier = Modifier.size(Dimens.IconSm),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = module.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 14.5f.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = MeiliTheme.colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!module.desc.isNullOrBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = module.desc,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = MeiliTheme.colors.ink3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            if (module.ready) {
                Icon(
                    MeiliIcons.ChevRight,
                    contentDescription = null,
                    tint = MeiliTheme.colors.ink3,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                StatusPill("即将上线", PillKind.Neutral)
            }
        }
    }
}

/* ───────────────────────── 模块定义（单一事实源） ───────────────────────── */

/**
 * 一个管理模块入口。
 * @param key 稳定标识（与 AppScaffold onOpenModule 的 when 分发对齐）
 * @param ready 是否已落地真实屏（WAVE 1 仅 ops_dashboard=true）
 */
data class AdminModule(
    val key: String,
    val title: String,
    val icon: ImageVector,
    val desc: String? = null,
    val ready: Boolean = false,
)

private data class AdminGroup(
    val label: String,
    val icon: ImageVector,
    val modules: List<AdminModule>,
)

/**
 * 17 个管理模块，4 组（对齐 web admin.html 的 tab 集合）。
 * 仅 [AdminModule.ready] 为 true 的模块会真正导航；其余点了冒「即将上线」。
 * 新增真实屏：把对应 module 的 ready 改 true，并在 AppScaffold onOpenModule 里加一条导航分支。
 */
private val AdminGroups: List<AdminGroup> = listOf(
    AdminGroup(
        label = "看板与预警",
        icon = MeiliIcons.Trend,
        modules = listOf(
            AdminModule("ops_dashboard", "运营看板", MeiliIcons.Trend, "今日/本周接诊与完成率、顾问明细", ready = true),
            AdminModule("high_risk", "差评高风险预警", MeiliIcons.Warn, "高风险接诊与差评跟进"),
            AdminModule("reception_block", "接诊卡点", MeiliIcons.Target, "卡在某环节的接诊"),
        ),
    ),
    AdminGroup(
        label = "审批",
        icon = MeiliIcons.Check,
        modules = listOf(
            AdminModule("delete_requests", "删除申请", MeiliIcons.Trash, "顾问删除片段的审批"),
            AdminModule("rebind_records", "换绑/解绑记录", MeiliIcons.Unbind, "换绑、解绑的操作记录"),
        ),
    ),
    AdminGroup(
        label = "人·店·号管理",
        icon = MeiliIcons.Profile,
        modules = listOf(
            AdminModule("staff", "员工管理", MeiliIcons.Profile, "顾问/店长账号与门店分配"),
            AdminModule("stores", "门店管理", MeiliIcons.Reception),
            AdminModule("admins", "管理员账号", MeiliIcons.Lock),
            AdminModule("customers", "顾客管理", MeiliIcons.Heart),
            AdminModule("customer_merge", "客户合并", MeiliIcons.Link, "合并重复客户档案"),
            AdminModule("companies", "公司管理", MeiliIcons.Doc),
        ),
    ),
    AdminGroup(
        label = "标签与项目",
        icon = MeiliIcons.Star,
        modules = listOf(
            AdminModule("tag_dict", "标签词典", MeiliIcons.Doc),
            AdminModule("tag_stats", "标签统计", MeiliIcons.Trend),
            AdminModule("tag_merge", "AI建议归并", MeiliIcons.Spark, "AI 建议的标签归并"),
            AdminModule("monthly_focus", "月度主推项目", MeiliIcons.Target),
            AdminModule("reminder_settings", "提醒设置", MeiliIcons.Reminder),
            AdminModule("unbound_recordings", "未绑定录音", MeiliIcons.Tidy),
        ),
    ),
)

@Preview(showBackground = true, widthDp = 390, heightDp = 900)
@Composable
private fun AdminHomeScreenPreview() {
    MeiliTheme {
        AdminHomeContent(
            state = AdminHomeUiState(
                me = com.airec.bledemo.data.model.Me(
                    username = "admin01",
                    role = "admin",
                    advisorName = "王经理",
                ),
            ),
            onModuleClick = {},
            onLogout = {},
        )
    }
}
