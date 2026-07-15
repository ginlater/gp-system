package com.airec.bledemo.ui.workspace

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import com.airec.bledemo.designsystem.components.MeiliBottomSheet
import com.airec.bledemo.designsystem.components.PillKind
import com.airec.bledemo.designsystem.components.StatusPill

/**
 * 系统切换底部弹窗（多系统整合 P1.1）。
 *
 * 在任一系统内部（工牌首页 / 网课首页顶栏、设置页）唤起，列出本账号开通的全部系统
 * （开几个显示几个），当前系统标「当前」，点其它项切过去——不用退出重登。
 *
 * 数据源 [AuthManager.lastMe]（登录后进程内缓存；空则兜底重拉 /api/me）。
 * 尚未接入的系统 key（回访/扣子/高情商）→ toast「即将上线」，弹窗不关。
 *
 * @param visible 是否展开
 * @param currentKey 当前所在系统 key（"gongpai" / "teach"），用于标「当前」+ 点击忽略
 * @param onDismiss 关闭回调
 * @param onSwitch 切换回调（只会带回 gongpai/teach 这类已接入且非当前的 key）
 */
@Composable
fun SystemSwitcherSheet(
    visible: Boolean,
    currentKey: String,
    onDismiss: () -> Unit,
    onSwitch: (key: String) -> Unit,
) {
    if (!visible) return
    val context = LocalContext.current

    // lastMe 一般直接命中（登录时 Gate 拉过）；进程恢复等极端情况兜底重拉
    val me by produceState(initialValue = AuthManager.lastMe) {
        if (value == null) value = AuthManager().currentUser()
    }
    val systems = me?.systems.orEmpty()

    MeiliBottomSheet(
        visible = true,
        onDismiss = onDismiss,
        title = "切换工作台",
        subtitle = "本账号已开通 ${systems.size} 个系统",
    ) {
        systems.forEach { sys ->
            SwitcherRow(
                sys = sys,
                current = sys.key == currentKey,
                onClick = {
                    when {
                        sys.key == currentKey -> onDismiss()
                        sys.key in WIRED_SYSTEM_KEYS -> {
                            onDismiss()
                            onSwitch(sys.key!!)
                        }
                        else -> Toast.makeText(context, "「${sys.name ?: "该系统"}」即将上线", Toast.LENGTH_SHORT).show()
                    }
                },
            )
            Spacer(Modifier.height(Dimens.S2))
        }
    }
}

/** 弹窗里的单个系统行：图标 chip + 名称/描述 + 「当前」pill。 */
@Composable
private fun SwitcherRow(
    sys: SystemEntry,
    current: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (current) MeiliPalette.ClayTint else MeiliPalette.Surface,
                MeiliShapes.Md,
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = Dimens.S3, vertical = 12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(MeiliPalette.ClaySoft, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(sys.switcherIcon(), contentDescription = null, tint = MeiliPalette.ClayDeep, modifier = Modifier.size(Dimens.Icon))
        }
        Spacer(Modifier.width(Dimens.S3))
        Column(Modifier.weight(1f)) {
            Text(
                sys.name ?: "未命名系统",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MeiliPalette.Ink,
            )
            if (!sys.desc.isNullOrBlank()) {
                Text(sys.desc, style = MaterialTheme.typography.bodySmall, color = MeiliPalette.Ink3)
            }
        }
        if (current) {
            StatusPill("当前", PillKind.Clay, icon = MeiliIcons.Check)
        } else {
            Icon(MeiliIcons.ChevRight, null, tint = MeiliPalette.Ink4, modifier = Modifier.size(Dimens.IconSm))
        }
    }
}

/** 系统 key → 图标（与工作台宫格一致）。 */
private fun SystemEntry.switcherIcon(): ImageVector = icon()
