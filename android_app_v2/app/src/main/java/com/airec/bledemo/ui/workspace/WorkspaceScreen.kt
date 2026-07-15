package com.airec.bledemo.ui.workspace

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.airec.bledemo.data.auth.AuthManager
import com.airec.bledemo.data.model.SystemEntry
import com.airec.bledemo.designsystem.Dimens
import com.airec.bledemo.designsystem.MeiliIcons
import com.airec.bledemo.designsystem.MeiliPalette
import com.airec.bledemo.designsystem.MeiliShapes
import com.airec.bledemo.designsystem.components.MeiliCard

/**
 * 多系统工作台（[com.airec.bledemo.nav.Routes.Workspace]）。
 *
 * /api/me 的 systems ≥2 的账号登录后落这里（Gate 分流），宫格展示本账号开通的系统：
 *  - 「智能工牌」（key=gongpai，native）→ push 顾问端主壳（现有录音首页）。
 *  - 「美业网课」（key=teach，hybrid）→ push teach 原生壳。
 *  - 其余 key（后续系统：回访/扣子/高情商）→ toast「即将上线」。
 *
 * 数据来源 [AuthManager.lastMe]（Gate 刚拉过 /api/me，进程内缓存直读）；
 * 进程被杀后恢复到本屏时缓存为空 → 兜底再拉一次。
 *
 * ⚠️ 苹果审核红线（安卓同构保持一致）：App 主体是原生录音（工牌），本屏只是模块入口，
 * 不做成"一堆网页格子"——native/hybrid 系统都是原生页承载。
 */
@Composable
fun WorkspaceScreen(
    onOpenSystem: (key: String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // Gate 刚拉过 /api/me → lastMe 一般直接命中；为空（进程恢复）时兜底重拉一次。
    val me by produceState(initialValue = AuthManager.lastMe) {
        if (value == null) value = AuthManager().currentUser()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MeiliPalette.Bg)
            .statusBarsPadding()
            .padding(horizontal = Dimens.ScreenH),
    ) {
        Spacer(Modifier.height(Dimens.S4))

        // ---- 头部：问候 + 标题 + 设置 ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val name = me?.advisorName ?: me?.username
                Text(
                    if (name.isNullOrBlank()) "你好" else "你好，$name",
                    style = MaterialTheme.typography.bodySmall,
                    color = MeiliPalette.Ink3,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "工作台",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = MeiliPalette.Ink,
                )
            }
            val interaction = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .size(Dimens.IconButton)
                    .background(MeiliPalette.Surface, MeiliShapes.IconButton)
                    .clickable(interactionSource = interaction, indication = null, onClick = onOpenSettings),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    MeiliIcons.Settings,
                    contentDescription = "设置",
                    tint = MeiliPalette.Ink2,
                    modifier = Modifier.size(Dimens.Icon),
                )
            }
        }

        Spacer(Modifier.height(Dimens.S2))
        Text(
            "选择要进入的系统",
            style = MaterialTheme.typography.bodySmall,
            color = MeiliPalette.Ink3,
        )
        Spacer(Modifier.height(Dimens.S4))

        // ---- 系统宫格 ----
        val systems = me?.systems.orEmpty()
        if (systems.isEmpty()) {
            // lastMe 兜底重拉中（或异常拿不到）：转圈占位，拿到后自动出格子。
            Box(Modifier.fillMaxWidth().padding(top = 80.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MeiliPalette.Clay, strokeWidth = 2.5.dp, modifier = Modifier.size(26.dp))
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(Dimens.CardGap),
                verticalArrangement = Arrangement.spacedBy(Dimens.CardGap),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(systems, key = { it.key ?: it.name ?: it.hashCode().toString() }) { sys ->
                    SystemTile(
                        sys = sys,
                        onClick = {
                            if (sys.key in WIRED_SYSTEM_KEYS) onOpenSystem(sys.key!!)
                            else Toast.makeText(context, "「${sys.name ?: "该系统"}」即将上线", Toast.LENGTH_SHORT).show()
                        },
                    )
                }
            }
        }
    }
}

/** 单个系统格子：图标 chip + 系统名 + 一句话描述。 */
@Composable
private fun SystemTile(
    sys: SystemEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    MeiliCard(
        modifier = modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(MeiliPalette.ClaySoft, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                sys.icon(),
                contentDescription = null,
                tint = MeiliPalette.ClayDeep,
                modifier = Modifier.size(Dimens.IconLg),
            )
        }
        Spacer(Modifier.height(Dimens.S3))
        Text(
            sys.name ?: "未命名系统",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MeiliPalette.Ink,
        )
        if (!sys.desc.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                sys.desc,
                style = MaterialTheme.typography.bodySmall,
                color = MeiliPalette.Ink3,
            )
        }
    }
}

/** 已接入模块的系统 key(点击可进;不在此列 = 即将上线)。 */
internal val WIRED_SYSTEM_KEYS = setOf("gongpai", "teach", "followup", "higheq", "chat", "kpi", "kpi_admin")

/** 系统 key → 图标（未知 key 用 Spark 兜底）。 */
internal fun SystemEntry.icon(): ImageVector = when (key) {
    "gongpai" -> MeiliIcons.Companion
    "teach" -> MeiliIcons.Headphone
    "followup" -> MeiliIcons.Phone
    "higheq" -> MeiliIcons.Heart
    "chat" -> MeiliIcons.Target
    "kpi" -> MeiliIcons.Trend
    "kpi_admin" -> MeiliIcons.Gem
    else -> MeiliIcons.Spark
}
