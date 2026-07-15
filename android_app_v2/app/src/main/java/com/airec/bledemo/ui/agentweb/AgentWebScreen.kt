package com.airec.bledemo.ui.agentweb

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.airec.bledemo.data.auth.CredentialStore
import com.airec.bledemo.data.chat.ChatRepository
import com.airec.bledemo.data.followup.FollowupRepository
import com.airec.bledemo.data.followup.ScriptSystem
import com.airec.bledemo.data.repo.ApiResult
import com.airec.bledemo.designsystem.Dimens
import com.airec.bledemo.designsystem.MeiliIcons
import com.airec.bledemo.designsystem.MeiliPalette
import com.airec.bledemo.designsystem.components.MeiliTopBar
import com.airec.bledemo.designsystem.components.PrimaryButton

/**
 * 话术系统网页壳(回访 followup / 高情商 higheq / 扣子销售 chat 共用,
 * [com.airec.bledemo.nav.Routes.ScriptAgent] + [com.airec.bledemo.nav.Routes.ChatHome])。
 *
 * 这三个系统的网页版是重度动态表单(点选项级联展开几百个字段,服务器随时会改),
 * 原生复刻既做不全也追不上——按用户拍板:**整页 WebView 收进来,原生只做壳 + 免登录**。
 *
 * 免登录:App 用统一密码(CredentialStore)在原生侧换 JWT,onPageFinished 时注入
 * localStorage(followup/higheq 用 auth_token;chat 用 token+username),token 不同则
 * 写入并 reload 一次,网页即跳过登录直进表单。token 过期(7d/24h)时网页会 401 回登录页,
 * 下次进本屏原生侧重新换新 token 注入,自愈。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AgentWebScreen(
    systemKey: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cfg = remember(systemKey) { AgentWebConfig.byKey(systemKey) }

    var token by remember { mutableStateOf<String?>(null) }
    var loginError by remember { mutableStateOf<String?>(null) }
    var retryTick by remember { mutableIntStateOf(0) }
    var pageLoading by remember { mutableStateOf(true) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // 字号(textZoom%):默认跟随全局字体档位,A－/A＋ 细调并持久化(与课件页同一份偏好)
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("teach_reader", android.content.Context.MODE_PRIVATE) }
    var textZoom by remember {
        androidx.compose.runtime.mutableIntStateOf(
            prefs.getInt("text_zoom", (com.airec.bledemo.designsystem.FontScaleManager.scale * 100).toInt()),
        )
    }
    LaunchedEffect(textZoom) {
        webViewRef?.settings?.textZoom = textZoom
        prefs.edit().putInt("text_zoom", textZoom).apply()
    }

    // 原生侧静默换 token(统一密码);失败给错误+重试,不让用户对着网页登录框懵
    LaunchedEffect(systemKey, retryTick) {
        loginError = null
        token = null
        when (val r = cfg.fetchToken()) {
            is ApiResult.Success -> token = r.data
            is ApiResult.Failure -> loginError = r.message
        }
    }

    BackHandler {
        val wv = webViewRef
        if (wv != null && wv.canGoBack()) wv.goBack() else onBack()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MeiliPalette.Bg)
            .statusBarsPadding(),
    ) {
        MeiliTopBar(
            title = cfg.title,
            onBack = onBack,
            modifier = Modifier.padding(horizontal = Dimens.ScreenH),
            actions = {
                FontZoomButton("A－") { textZoom = (textZoom - 15).coerceAtLeast(70) }
                FontZoomButton("A＋") { textZoom = (textZoom + 15).coerceAtMost(190) }
            },
        )
        when {
            loginError != null -> Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 70.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(MeiliIcons.Warn, contentDescription = null, tint = MeiliPalette.Rose, modifier = Modifier.size(34.dp))
                Spacer(Modifier.height(Dimens.S3))
                Text(loginError!!, style = MaterialTheme.typography.bodyMedium, color = MeiliPalette.Ink2)
                Spacer(Modifier.height(Dimens.S4))
                PrimaryButton("重试", onClick = { retryTick++ })
            }
            token == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MeiliPalette.Clay, strokeWidth = 2.5.dp, modifier = Modifier.size(26.dp))
            }
            else -> Box(Modifier.fillMaxSize()) {
                val injectJs = cfg.injectJs(token!!)
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true   // localStorage 必须
                            settings.textZoom = textZoom
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    // 每次加载完都注入:token 相同则无操作;不同(首次/过期换新)写入并 reload 一次。
                                    // 无注入脚本的系统(kpi:cookie 会话+独立账号)跳过。
                                    if (injectJs.isNotBlank()) view?.evaluateJavascript(injectJs, null)
                                    pageLoading = false
                                }
                            }
                            loadUrl(cfg.url)
                            webViewRef = this
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                if (pageLoading) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(MeiliPalette.Bg),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = MeiliPalette.Clay, strokeWidth = 2.5.dp, modifier = Modifier.size(26.dp))
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.apply {
                loadUrl("about:blank")
                stopLoading()
                destroy()
            }
            webViewRef = null
        }
    }
}

/** 顶栏字号调节小方钮(与课件页同款)。 */
@Composable
private fun FontZoomButton(text: String, onClick: () -> Unit) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        shape = com.airec.bledemo.designsystem.MeiliShapes.IconButton,
        color = MeiliPalette.Surface,
        contentColor = MeiliPalette.Ink2,
        border = androidx.compose.foundation.BorderStroke(Dimens.BorderThin, MeiliPalette.Line),
        shadowElevation = Dimens.Elev1,
        modifier = Modifier.size(Dimens.IconButton),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                ),
            )
        }
    }
}

/** 三个话术系统的网页壳配置:标题 / URL / token 获取 / localStorage 注入脚本。 */
private class AgentWebConfig(
    val title: String,
    val url: String,
    val fetchToken: suspend () -> ApiResult<String>,
    val injectJs: (token: String) -> String,
) {
    companion object {
        fun byKey(key: String): AgentWebConfig = when (key) {
            "higheq" -> scriptAgent(ScriptSystem.HighEq)
            // KPI 积分(员工端):账号是拼音(与统一密码不通),用「顾问姓名→拼音账号」映射自动登录。
            // fetchToken 返回当前顾问对应的拼音账号(查不到=空串→显示登录页,不报错);
            // injectJs 用它 + 统一密码探测/自动登录(与其它系统一样免码)。
            "kpi" -> AgentWebConfig(
                title = "KPI积分",
                url = com.airec.bledemo.data.kpi.KpiModule.BASE_URL,
                fetchToken = {
                    com.airec.bledemo.data.kpi.KpiRepository()
                        .resolveUsername(com.airec.bledemo.data.auth.AuthManager.lastMe?.advisorName)
                },
                injectJs = { username ->
                    if (username.isBlank()) ""
                    else """
                    (async function(){
                      try {
                        var r = await fetch('/api/me');
                        if (r.ok) return;
                        var lr = await fetch('/api/login', {
                          method: 'POST',
                          headers: {'Content-Type': 'application/json'},
                          body: JSON.stringify({username: '$username', password: '${com.airec.bledemo.data.kpi.KpiModule.STAFF_PASS}'})
                        });
                        if (lr.ok) location.reload();
                      } catch(e) {}
                    })();
                    """.trimIndent()
                },
            )
            // KPI 记分考核(管理后台):老板用,固定管理员账号自动登录——
            // 每次加载完探一次 /api/admin/employees,401 才 POST /api/admin/login 后 reload(幂等)。
            "kpi_admin" -> AgentWebConfig(
                title = "KPI记分考核",
                url = "https://kpi.beautyshining.com/admin",
                fetchToken = { ApiResult.Success("") },
                injectJs = {
                    """
                    (async function(){
                      try {
                        var r = await fetch('/api/admin/employees');
                        if (r.ok) return;
                        var lr = await fetch('/api/admin/login', {
                          method: 'POST',
                          headers: {'Content-Type': 'application/json'},
                          body: JSON.stringify({username: 'diaojie', password: '123456'})
                        });
                        if (lr.ok) location.reload();
                      } catch(e) {}
                    })();
                    """.trimIndent()
                },
            )
            "chat" -> AgentWebConfig(
                title = "销售话术",
                url = com.airec.bledemo.data.chat.ChatModule.BASE_URL,
                fetchToken = { ChatRepository().ensureLogin() },
                injectJs = { t ->
                    val u = CredentialStore().username().orEmpty()
                    """
                    (function(){
                      var t='$t', u='$u';
                      if (localStorage.getItem('token') !== t) {
                        localStorage.setItem('token', t);
                        localStorage.setItem('username', u);
                        location.reload();
                      }
                    })();
                    """.trimIndent()
                },
            )
            else -> scriptAgent(ScriptSystem.Followup)
        }

        private fun scriptAgent(sys: ScriptSystem) = AgentWebConfig(
            title = sys.displayName,
            url = sys.baseUrl,
            fetchToken = { FollowupRepository(sys).ensureLogin() },
            injectJs = { t ->
                """
                (function(){
                  var t='$t';
                  if (localStorage.getItem('auth_token') !== t) {
                    localStorage.setItem('auth_token', t);
                    location.reload();
                  }
                })();
                """.trimIndent()
            },
        )
    }
}
