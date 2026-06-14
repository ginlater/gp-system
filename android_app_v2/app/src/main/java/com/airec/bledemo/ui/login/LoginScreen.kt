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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
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
                text = "美丽陪伴",
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
                    keyboardActions = KeyboardActions(onDone = { viewModel.login() }),
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
                        enabled = state.canSubmit,
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

                Spacer(Modifier.height(16.dp))

                Text(
                    text = "登录即代表同意《服务协议》与《隐私政策》",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal),
                    color = MeiliPalette.Ink3,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(12.dp))

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
