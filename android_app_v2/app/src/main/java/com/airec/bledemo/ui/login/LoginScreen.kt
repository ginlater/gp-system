package com.airec.bledemo.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.airec.bledemo.designsystem.Dimens
import com.airec.bledemo.designsystem.MeiliPalette
import com.airec.bledemo.designsystem.MeiliShapes
import com.airec.bledemo.designsystem.MeiliTheme
import com.airec.bledemo.designsystem.MeiliIcons
import com.airec.bledemo.designsystem.components.MeiliCard
import com.airec.bledemo.designsystem.components.PrimaryButton
import com.airec.bledemo.data.net.NetworkModule

/**
 * 登录（SPEC §4.1 / warm_2 #login）。
 *
 * 暖玉柔光、克制高级：陶土圆形品牌徽标 + 衬线大标题「美丽陪伴」+ 副标，柔光卡内
 * 账号 / 密码输入 + 「登 录」主按钮 + 错误提示 + loading。
 *
 * 调 [LoginViewModel.login]（内部走 [com.airec.bledemo.data.auth.AuthManager.login]：
 * POST /login 存 Cookie + GET /api/me 实判会话）。成功后回调 [onLoggedIn]，
 * 由 NavHost 把起点切到陪伴首页。
 *
 * @param onLoggedIn 登录成功回调
 * @param modifier 由 AppScaffold / NavHost 传入
 * @param viewModel 登录状态机
 */
@Composable
fun LoginScreen(
    onLoggedIn: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: LoginViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val uriHandler = LocalUriHandler.current

    // 隐私合规（vivo 等应用商店要求）：默认【不勾选】同意，未勾选不可登录；政策链接可点开。
    var agreed by rememberSaveable { mutableStateOf(false) }
    // ★2026-07-19 vivo 驳回「APP内部无隐私政策」:政策要在应用内看,不能跳浏览器。
    var policyKind by remember { mutableStateOf<String?>(null) }
    if (policyKind != null) {
        com.airec.bledemo.privacy.PolicyScreen(
            kind = com.airec.bledemo.privacy.PolicyKind.byKey(policyKind!!),
            onBack = { policyKind = null },
        )
        return
    }
    // ★2026-07-17 —— 链接收口到 PrivacyConsent 的常量。小米按「商店后台/首启弹窗/App 内
    // 政策入口三者链接不一致」驳回过,这里再自己拼一份就又破功了。
    val privacyUrl = com.airec.bledemo.privacy.PrivacyConsent.PRIVACY_URL
    val termsUrl = com.airec.bledemo.privacy.PrivacyConsent.TERMS_URL

    // 登录成功一次性信号：消费后回调上层切导航。
    LaunchedEffect(state.loggedIn) {
        if (state.loggedIn) {
            keyboard?.hide()
            onLoggedIn()
            viewModel.onLoggedInHandled()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MeiliPalette.Bg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                // 键盘弹出时给内容留出键盘高度的底部空间，让聚焦的密码框能滚到键盘上方、不被挡住
                .imePadding()
                .padding(horizontal = 30.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ---- 品牌徽标（.brandmark：90×90 圆角 30、陶土径向高光、并蒂花蕊图标） ----
            Box(
                modifier = Modifier
                    .size(90.dp)
                    .clip(MeiliShapes.Xl)
                    .background(MeiliPalette.CompanionGradient),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MeiliIcons.Companion,
                    contentDescription = null,
                    tint = MeiliPalette.White,
                    modifier = Modifier.size(46.dp),
                )
            }

            Spacer(Modifier.height(20.dp))

            // ---- 衬线大标题 + 副标 ----
            Text(
                text = "美业私教",
                style = MeiliTheme.brandStyle,
                color = MeiliPalette.ClayDeep,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "温柔记录每一次陪伴 · 让美更被读懂",
                style = MaterialTheme.typography.bodySmall.copy(letterSpacing = 0.5.sp),
                color = MeiliPalette.Ink2,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(30.dp))

            // ---- 登录卡 ----
            MeiliCard {
                LoginField(
                    label = "账号",
                    value = state.username,
                    onValueChange = viewModel::onUsernameChange,
                    placeholder = "工号 / 手机号",
                    leadingIcon = MeiliIcons.Profile,
                    enabled = !state.loading,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Next,
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Down) },
                    ),
                    isPassword = false,
                )

                Spacer(Modifier.height(15.dp))

                LoginField(
                    label = "密码",
                    value = state.password,
                    onValueChange = viewModel::onPasswordChange,
                    placeholder = "请输入密码",
                    leadingIcon = MeiliIcons.Lock,
                    enabled = !state.loading,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { if (agreed) viewModel.login() }),
                    isPassword = true,
                )

                // ---- 错误提示（.banner.danger） ----
                if (state.error != null) {
                    Spacer(Modifier.height(4.dp))
                    ErrorBanner(message = state.error!!)
                }

                Spacer(Modifier.height(18.dp))

                // ---- 「登 录」主按钮（loading 时转圈 + 禁用） ----
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    PrimaryButton(
                        text = if (state.loading) "登录中…" else "登 录",
                        onClick = { viewModel.login() },
                        modifier = Modifier.fillMaxWidth(),
                        // 未勾选「同意隐私政策」不可登录（合规要求：不默认同意、需用户主动勾选）
                        enabled = state.canSubmit && agreed,
                    )
                    if (state.loading) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(end = 22.dp)
                                .size(18.dp)
                                .align(Alignment.CenterEnd),
                            color = MeiliPalette.White,
                            strokeWidth = 2.dp,
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // ---- 隐私合规：默认【不勾选】的同意框 + 可点开的政策链接（不勾选不可登录） ----
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = agreed,
                        onCheckedChange = { agreed = it },
                        modifier = Modifier.size(22.dp),
                        colors = CheckboxDefaults.colors(
                            checkedColor = MeiliPalette.Clay,
                            uncheckedColor = MeiliPalette.Ink3,
                            checkmarkColor = MeiliPalette.White,
                        ),
                    )
                    Spacer(Modifier.width(8.dp))
                    val consent = buildAnnotatedString {
                        append("我已阅读并同意")
                        pushStringAnnotation("url", privacyUrl)
                        withStyle(SpanStyle(color = MeiliPalette.ClayDeep, fontWeight = FontWeight.Bold)) { append("《隐私政策》") }
                        pop()
                        append("和")
                        pushStringAnnotation("url", termsUrl)
                        withStyle(SpanStyle(color = MeiliPalette.ClayDeep, fontWeight = FontWeight.Bold)) { append("《用户协议》") }
                        pop()
                    }
                    ClickableText(
                        text = consent,
                        style = MaterialTheme.typography.labelSmall.copy(color = MeiliPalette.Ink2, lineHeight = 18.sp),
                        onClick = { offset ->
                            consent.getStringAnnotations("url", offset, offset).firstOrNull()?.let {
                                // 应用内打开(不跳浏览器)——vivo 合规要求
                                policyKind = if (it.item == termsUrl) "terms" else "privacy"
                            }
                        },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ★2026-07-19 vivo 合规 —— 登录页底部的独立政策入口。
            // 商店审核员没有业务账号,进不到设置页;这两个按钮让「未登录也能随时查看政策」,
            // 且是明显按钮而非小字链接(vivo 驳回理由是「未向用户提供易于访问的隐私政策」)。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                TextButton(onClick = { policyKind = "privacy" }) {
                    Text(
                        "隐私政策",
                        style = MaterialTheme.typography.labelSmall,
                        color = MeiliPalette.ClayDeep,
                    )
                }
                Text(
                    "·",
                    style = MaterialTheme.typography.labelSmall,
                    color = MeiliPalette.Ink4,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
                TextButton(onClick = { policyKind = "terms" }) {
                    Text(
                        "用户协议",
                        style = MaterialTheme.typography.labelSmall,
                        color = MeiliPalette.ClayDeep,
                    )
                }
            }

            Text(
                text = "陪伴师端 · 高端身体美容陪伴",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal),
                color = MeiliPalette.Ink3,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 输入字段：label 在上、icon 前置的圆角填充输入框，还原 warm_2 `.field` + `.inputwrap`。
 * 聚焦时陶土描边 + 转白底（由 [OutlinedTextFieldDefaults.colors] 表达）。
 */
@Composable
private fun LoginField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    leadingIcon: ImageVector,
    enabled: Boolean,
    keyboardOptions: KeyboardOptions,
    keyboardActions: KeyboardActions,
    isPassword: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            ),
            color = MeiliPalette.Ink2,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
            enabled = enabled,
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(
                fontSize = 14.5f.sp,
                color = MeiliPalette.Ink,
            ),
            placeholder = {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MeiliPalette.Ink3,
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = MeiliPalette.Ink3,
                    modifier = Modifier.size(Dimens.IconSm),
                )
            },
            visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            shape = MeiliShapes.Sm,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MeiliPalette.White,
                unfocusedContainerColor = MeiliPalette.SurfaceSoft,
                disabledContainerColor = MeiliPalette.SurfaceSoft,
                focusedBorderColor = MeiliPalette.Clay,
                unfocusedBorderColor = MeiliPalette.Line,
                disabledBorderColor = MeiliPalette.Line,
                cursorColor = MeiliPalette.Clay,
                focusedTextColor = MeiliPalette.Ink,
                unfocusedTextColor = MeiliPalette.Ink,
            ),
        )
    }
}

/** 错误横幅，还原 warm_2 `.banner.danger`：玫瑰柔底 + 玫瑰边 + 玫瑰深字 + warn 图标。 */
@Composable
private fun ErrorBanner(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MeiliShapes.Md)
            .background(MeiliPalette.RoseSoft)
            .padding(horizontal = 15.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = MeiliIcons.Warn,
            contentDescription = null,
            tint = MeiliPalette.RoseText,
            modifier = Modifier.size(Dimens.IconSm),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                lineHeight = 19.sp,
            ),
            color = MeiliPalette.RoseText,
            modifier = Modifier.widthIn(min = 0.dp),
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 760)
@Composable
private fun LoginScreenPreview() {
    MeiliTheme { LoginScreen() }
}
