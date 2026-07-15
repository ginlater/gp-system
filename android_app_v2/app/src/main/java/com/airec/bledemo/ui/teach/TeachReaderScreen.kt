package com.airec.bledemo.ui.teach

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.airec.bledemo.data.teach.TeachModule
import com.airec.bledemo.data.teach.TeachRepository
import com.airec.bledemo.designsystem.Dimens
import com.airec.bledemo.designsystem.FontScaleManager
import com.airec.bledemo.designsystem.MeiliPalette
import com.airec.bledemo.designsystem.MeiliShapes
import com.airec.bledemo.designsystem.components.MeiliTopBar
import kotlinx.coroutines.delay
import java.io.ByteArrayInputStream

/**
 * teach 课件正文（[com.airec.bledemo.nav.Routes.TeachReader]）。
 *
 * 混合模式的「WebView 那一屏」：课件是服务器上随时会改的富文本静态页
 * （https://teach…/{chapterKey}.html，免鉴权），原生只包壳——顶栏 + 加载态 + 学习心跳。
 * 音频引用 /audio/ 相对路径，WebView 原生播放。
 *
 * 手机适配（用户反馈"别做成缩小的网页"）：
 *  - 课件 html 没有 viewport meta，若开 useWideViewPort 会按桌面 980px 排版再整体缩小
 *    → 这里关掉，按设备宽度排版，课件自身样式是流式的，满宽阅读即原生手感;
 *  - 字号：textZoom 默认跟随 App 全局字体档位（设置页 5 档），顶栏 A－/A＋ 再单独细调，
 *    选择持久化（下次进课件还是这个字号）;
 *  - 课件引用 Google Fonts（国内加载极慢）→ 拦截该域请求直接回空，用系统字体，秒开。
 *
 * 学习心跳：页面处于 RESUMED 期间每 30s POST /teach/heartbeat（服务端 25s 节流），
 * 退到后台/离开页面自动停（repeatOnLifecycle 挂起取消），时长不虚计。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TeachReaderScreen(
    chapterKey: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // 章标题：首页刚拉过 stats，进程内缓存直查；查不到退回通用标题
    val title = remember(chapterKey) {
        TeachModule.lastStats?.progress?.chapters
            ?.firstOrNull { it.key == chapterKey }?.title ?: "课件正文"
    }

    var pageLoading by remember { mutableStateOf(true) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // 字号（textZoom 百分比）：默认跟随全局字体档位，A－/A＋ 细调后持久化
    val prefs = remember { context.getSharedPreferences(READER_PREFS, Context.MODE_PRIVATE) }
    var textZoom by remember {
        mutableIntStateOf(prefs.getInt(KEY_ZOOM, (FontScaleManager.scale * 100).toInt()))
    }
    LaunchedEffect(textZoom) {
        webViewRef?.settings?.textZoom = textZoom
        prefs.edit().putInt(KEY_ZOOM, textZoom).apply()
    }

    // 学习时长心跳：RESUMED 期间 30s 一发（首发立即），离开/退后台即停
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(chapterKey) {
        val repo = TeachRepository()
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                repo.heartbeat(chapterKey)   // 失败不打扰阅读，下一轮再试
                delay(30_000)
            }
        }
    }

    // 系统返回：网页内有历史先回网页（课件内锚点跳转），否则退出本屏
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
            title = title,
            onBack = onBack,
            modifier = Modifier.padding(horizontal = Dimens.ScreenH),
            actions = {
                FontZoomButton("A－") { textZoom = (textZoom - ZOOM_STEP).coerceAtLeast(ZOOM_MIN) }
                FontZoomButton("A＋") { textZoom = (textZoom + ZOOM_STEP).coerceAtMost(ZOOM_MAX) }
            },
        )
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // 手机适配核心：课件无 viewport meta，关掉桌面布局仿真，按设备宽度排版
                        settings.useWideViewPort = false
                        settings.loadWithOverviewMode = false
                        settings.textZoom = textZoom
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                pageLoading = false
                            }

                            // 课件 @import 了 Google Fonts，国内网络加载极慢甚至挂起
                            // → 拦掉该域请求直接回空样式，退回系统字体，页面秒开
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): WebResourceResponse? {
                                val host = request?.url?.host.orEmpty()
                                if (host.endsWith("fonts.googleapis.com") || host.endsWith("fonts.gstatic.com")) {
                                    return WebResourceResponse(
                                        "text/css", "utf-8", ByteArrayInputStream(ByteArray(0)),
                                    )
                                }
                                return super.shouldInterceptRequest(view, request)
                            }
                        }
                        loadUrl(TeachModule.coursewareUrl(chapterKey))
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

    // 离开页面销毁 WebView（停音频、释放内存——录音 App 对内存/音频通道敏感）
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

/** 顶栏字号调节小方钮（与 .iconbtn 同款：44 方圆角 surface + 细描边）。 */
@Composable
private fun FontZoomButton(text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MeiliShapes.IconButton,
        color = MeiliPalette.Surface,
        contentColor = MeiliPalette.Ink2,
        border = androidx.compose.foundation.BorderStroke(Dimens.BorderThin, MeiliPalette.Line),
        shadowElevation = Dimens.Elev1,
        modifier = Modifier.size(Dimens.IconButton),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            )
        }
    }
}

private const val READER_PREFS = "teach_reader"
private const val KEY_ZOOM = "text_zoom"
private const val ZOOM_STEP = 15
private const val ZOOM_MIN = 70
private const val ZOOM_MAX = 190
