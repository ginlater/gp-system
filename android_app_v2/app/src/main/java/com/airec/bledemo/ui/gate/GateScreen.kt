package com.airec.bledemo.ui.gate

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.airec.bledemo.data.auth.AuthManager
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
 * 加载中：暖玉柔光底 + 居中陶土色转圈。
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

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MeiliPalette.Bg),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            color = MeiliPalette.Clay,
            modifier = Modifier.size(36.dp),
        )
    }
}
