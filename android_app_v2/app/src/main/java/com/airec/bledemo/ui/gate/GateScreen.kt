package com.airec.bledemo.ui.gate

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.airec.bledemo.data.auth.AuthManager
import com.airec.bledemo.designsystem.MeiliIcons
import com.airec.bledemo.designsystem.MeiliPalette

/**
 * 角色路由门（[com.airec.bledemo.nav.Routes.Gate]）。
 *
 * 启动 / 登录成功后统一落到这里，在唯一一处按角色分流（避免在 Activity 与登录回调各判一次）：
 *  - 拉 [AuthManager.currentUser]（suspend，打 /api/me 实判会话 + 拿 role）。
 *  - admin / super        → 管理台首页（onAdmin）
 *  - consultant/store_mgr → 顾问端主壳（onConsultant）
 *  - null（未登录/失败）   → 登录页（onLogin）
 *
 * 分流回调里由 NavHost 负责把 Gate 自身 pop 掉（见 AppScaffold）。
 * 加载中显示「美丽陪伴」品牌启动页（暖玉柔光底 + 呼吸徽标 + 整体淡入），
 * 而非白屏 + 裸转圈——缓存自动登录这段空窗看起来是「正在开启」，不是「坏了」。
 *
 * @param onAdmin 进管理台
 * @param onConsultant 进顾问端主壳
 * @param onLogin 回登录
 * @param modifier 由 AppScaffold 传入
 * @param auth 鉴权管理（默认 NetworkModule 默认实例；可注入测试）
 */
@Composable
fun GateScreen(
    onAdmin: () -> Unit,
    onConsultant: () -> Unit,
    onLogin: () -> Unit,
    modifier: Modifier = Modifier,
    auth: AuthManager = AuthManager(),
) {
    // 只在首次组合时判一次角色并分流；分流后 Gate 已 pop，不会重入。
    LaunchedEffect(Unit) {
        val me = auth.currentUser()
        when {
            me == null -> onLogin()
            me.isAdminOrSuper -> onAdmin()
            else -> onConsultant()   // consultant / store_manager（及任何其它有效会话兜底进顾问端）
        }
    }

    BrandSplash(modifier)
}

/**
 * 品牌启动页：暖玉柔光底 + 呼吸的并蒂花徽标 + 「美丽陪伴」+ 副文案 + 陶土转圈，整体淡入。
 * 用于缓存自动登录的空窗与任何角色分流前的等待，替代白屏裸转圈。
 */
@Composable
private fun BrandSplash(modifier: Modifier = Modifier) {
    // 整体淡入（约 0.5s 缓入），让内容柔和出现而不是硬弹。
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, animationSpec = tween(500, easing = FastOutSlowInEasing)) }

    // 徽标呼吸（缓慢放大缩小），传达「正在进行中」的生命力。
    val pulse = rememberInfiniteTransition(label = "splash-pulse")
    val scale by pulse.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "splash-scale",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MeiliPalette.Bg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.alpha(appear.value),
        ) {
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .scale(scale)
                    .background(MeiliPalette.ClaySoft, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    MeiliIcons.Companion,
                    contentDescription = null,
                    tint = MeiliPalette.ClayDeep,
                    modifier = Modifier.size(46.dp),
                )
            }
            Spacer(Modifier.height(22.dp))
            Text(
                "美丽陪伴",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = MeiliPalette.Ink,
            )
            Spacer(Modifier.height(7.dp))
            Text(
                "正在为你开启…",
                style = MaterialTheme.typography.bodySmall,
                color = MeiliPalette.Ink3,
            )
            Spacer(Modifier.height(26.dp))
            CircularProgressIndicator(
                color = MeiliPalette.Clay,
                strokeWidth = 2.5.dp,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
