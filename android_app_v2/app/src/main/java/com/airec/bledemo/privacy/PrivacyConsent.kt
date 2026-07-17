package com.airec.bledemo.privacy

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.text.ClickableText
import com.airec.bledemo.designsystem.MeiliPalette

/**
 * ★2026-07-17 隐私合规 —— 首次启动的隐私政策弹窗。
 *
 * 【为什么有这个文件】OPPO 与小米同时驳回,理由一模一样:
 *   OPPO:「您的应用在首次运行时未通过弹窗等明显方式提醒用户阅读隐私政策」
 *   小米:「首次进入应用的隐私弹窗展示」——还要求录进演示视频里给审核员看
 *
 * 原来我们只在登录页放了个「我已阅读并同意」勾选框(见 LoginScreen)。那个写法
 * 在 vivo 过了,但 OPPO/小米不认——它们要的是**首次运行就糊脸的弹窗**,而且必须有
 * 「不同意」这条路可走。勾选框是「你不勾就不能登录」,弹窗是「你不同意就不能用」,
 * 监管认后者。
 *
 * 【规矩】
 *  - 同意前不进任何页面(连登录页都不给看)。
 *  - 「不同意」必须能退出 App,不能耍赖把用户困住。
 *  - 政策链接必须可点开,不能只是一行灰字。
 *  - 同意状态持久化,只弹一次;用户不会每次开 App 都被烦。
 *
 * ⚠️ 三处隐私政策链接必须完全一致(商店提交的 / 本弹窗里的 / 设置页里的),
 *    小米明确按这条驳回过。改链接请一起改,见 [PRIVACY_URL]。
 */
object PrivacyConsent {
    /**
     * 全 App 唯一的隐私政策地址。
     *
     * ⚠️ 小米驳回原文:「提交至小米开放平台的隐私政策链接,与隐私弹窗以及应用内
     *    独立的隐私政策不一致」。所以这个常量是**唯一真相**:
     *      - 本弹窗里的链接 → 用它
     *      - 设置页「隐私政策」入口 → 用它
     *      - 各商店后台填的链接 → 也必须填它
     *    三处对不上就会被打回。改这里之前先想清楚商店后台也要同步改。
     */
    const val PRIVACY_URL = "https://company.aibeautyfulwomen.com/privacy.html"
    const val TERMS_URL = "https://gp.aibeautyfulwomen.com/terms-of-service"

    private const val PREF = "privacy_consent"
    private const val KEY_AGREED = "agreed_v1"

    fun isAgreed(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_AGREED, false)

    fun setAgreed(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AGREED, true).apply()
    }

    /** 仅供测试/演示重置用(录商店演示视频时要把弹窗重新召出来)。 */
    fun reset(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY_AGREED).apply()
    }
}

/**
 * 首次启动的隐私政策弹窗。
 *
 * @param onAgree 用户点「同意」——调用方负责持久化并放行进 App。
 * @param onDisagree 用户点「不同意并退出」——调用方负责 finish() 掉 Activity。
 */
@Composable
fun PrivacyConsentDialog(
    onAgree: () -> Unit,
    onDisagree: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    // 摘要正文里的《隐私政策》《用户协议》做成可点链接——监管要求「可查阅」,不能只是文字
    val body = buildAnnotatedString {
        append("欢迎使用「美业私教」。我们非常重视您的个人信息保护。在您使用前,请阅读并理解")
        pushStringAnnotation("url", PrivacyConsent.PRIVACY_URL)
        withStyle(SpanStyle(color = MeiliPalette.ClayDeep, fontWeight = FontWeight.Bold)) {
            append("《隐私政策》")
        }
        pop()
        append("和")
        pushStringAnnotation("url", PrivacyConsent.TERMS_URL)
        withStyle(SpanStyle(color = MeiliPalette.ClayDeep, fontWeight = FontWeight.Bold)) {
            append("《用户协议》")
        }
        pop()
        append("。我们将按其中的约定收集和使用您的信息:\n\n")
        append("• 账号信息(手机号、姓名、所属机构):用于登录与身份识别。\n")
        append("• 麦克风 / 蓝牙:仅在您主动使用录音、连接陪伴笔时申请,申请前会单独说明用途。\n")
        append("• 设备与日志信息:用于保障服务稳定与安全。\n\n")
        append("我们不会向第三方出售您的个人信息。您可随时在系统设置中撤回权限,也可在 App 的「设置」中注销账号。\n\n")
        append("点击「同意」表示您已阅读并接受上述条款;点击「不同意」将退出应用。")
    }

    AlertDialog(
        // 不给点外面关掉——必须做出明确选择(监管要求「明示同意」)
        onDismissRequest = {},
        containerColor = MeiliPalette.Surface,
        titleContentColor = MeiliPalette.Ink,
        textContentColor = MeiliPalette.Ink2,
        title = { Text("隐私政策与用户协议", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                ClickableText(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium.copy(color = MeiliPalette.Ink2),
                    onClick = { offset ->
                        body.getStringAnnotations("url", offset, offset).firstOrNull()?.let {
                            uriHandler.openUri(it.item)
                        }
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onAgree) {
                Text("同意", color = MeiliPalette.ClayDeep, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDisagree) {
                Text("不同意并退出", color = MeiliPalette.Ink2)
            }
        },
    )
}
