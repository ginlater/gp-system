package com.airec.bledemo.ui.customer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.airec.bledemo.data.model.Customer
import com.airec.bledemo.designsystem.Dimens
import com.airec.bledemo.designsystem.MeiliIcons
import com.airec.bledemo.designsystem.MeiliPalette
import com.airec.bledemo.designsystem.MeiliShapes
import com.airec.bledemo.designsystem.MeiliTheme
import com.airec.bledemo.designsystem.components.MeiliCard
import com.airec.bledemo.designsystem.components.MeiliTopBar

/**
 * 「客户」搜索屏（redesign 原型 #custsearch / 方案A 新增 tab）。
 *
 * header「客户」+ sub「搜顾客，看 TA 的历史陪伴与画像」；搜索框「输入姓名 / 会员卡号」(去抖 ~300ms)；
 * 一张 MeiliCard 列出顾客（头像首字 + 姓名 + 会员卡号/尾号/新客 meta + chevron），点行进 [CustomerDetailScreen]。
 * 空关键字默认拉一批本人可见顾客（customer_lookup），进 tab 即有内容。
 *
 * 红线：对外零「录音/录制」。meta「陪伴 N 次」需模型带次数才显示，现有 [Customer] 不带 → 此屏 meta 省略次数
 * （详情屏用 session_count 展示「陪伴 N 次」）。窄屏 360–390dp：姓名 ellipsis、meta 单行，不溢出。
 *
 * @param onOpenCustomerDetail 进某位顾客的历史陪伴与画像（customerId）。
 * @param modifier 由导航/Scaffold 传入（含底栏避让 padding）。
 * @param viewModel 状态机（默认走 [androidx.lifecycle.viewmodel.compose.viewModel]）。
 */
@Composable
fun CustomerScreen(
    onOpenCustomerDetail: (customerId: Long) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: CustomerViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MeiliPalette.Bg),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Dimens.ScreenH),
            contentPadding = PaddingValues(bottom = Dimens.BottomNavInset),
            verticalArrangement = Arrangement.spacedBy(Dimens.CardGap),
        ) {
            item {
                MeiliTopBar(
                    title = "客户",
                    subtitle = "默认看最近一个月接待的顾客；也可搜姓名 / 卡号查任意顾客",
                )
            }

            // ── 搜索框（姓名 / 会员卡号；去抖在 VM）──
            item {
                CustomerSearchField(value = state.query, onValueChange = viewModel::setQuery)
            }

            // ── 列表区小标题：默认显示「最近一个月接待」，搜索时显示「搜索结果」──
            if (state.customers.isNotEmpty() || !state.loading) {
                item {
                    Text(
                        text = if (state.query.isBlank()) "最近一个月接待" else "搜索结果",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = MeiliPalette.Ink2,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                    )
                }
            }

            // ── 列表 / 加载 / 空 / 错误 ──
            when {
                state.loading && state.customers.isEmpty() -> item {
                    MeiliCard { InlineLoading(text = "正在查找顾客…") }
                }
                state.error != null && state.customers.isEmpty() -> item {
                    MeiliCard {
                        EmptyHint(icon = MeiliIcons.Warn, title = "查找失败", sub = state.error)
                    }
                }
                state.customers.isEmpty() -> item {
                    MeiliCard {
                        EmptyHint(
                            icon = MeiliIcons.Profile,
                            title = if (state.query.isBlank()) "最近一个月还没有接待记录" else "没有匹配的顾客",
                            sub = if (state.query.isBlank()) {
                                "完成接诊并绑定顾客后，会在这里看到最近接待的顾客；也可在上方搜索任意顾客"
                            } else {
                                "换个姓名 / 会员卡号试试"
                            },
                        )
                    }
                }
                else -> item {
                    MeiliCard(tight = true) {
                        state.customers.forEachIndexed { idx, c ->
                            CustomerRow(
                                customer = c,
                                showDivider = idx != state.customers.lastIndex,
                                onClick = { c.cid?.let(onOpenCustomerDetail) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 搜索框：复用 ArchiveScreen 的 OutlinedTextField 样式（陶土聚焦 / surface-soft 浅底）。 */
@Composable
private fun CustomerSearchField(
    value: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = {
            Text(
                "输入姓名 / 会员卡号",
                style = MaterialTheme.typography.bodyLarge,
                color = MeiliPalette.Ink3,
            )
        },
        leadingIcon = {
            Icon(
                MeiliIcons.Search,
                contentDescription = null,
                tint = MeiliPalette.Ink3,
                modifier = Modifier.size(Dimens.IconSm),
            )
        },
        shape = MeiliShapes.Sm,
        textStyle = MaterialTheme.typography.bodyLarge,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = {}),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MeiliPalette.White,
            unfocusedContainerColor = MeiliPalette.SurfaceSoft,
            focusedBorderColor = MeiliPalette.Clay,
            unfocusedBorderColor = MeiliPalette.Line,
            cursorColor = MeiliPalette.Clay,
            focusedTextColor = MeiliPalette.Ink,
            unfocusedTextColor = MeiliPalette.Ink,
        ),
    )
}

/** 单个顾客行：头像首字 + 姓名 + meta（会员卡号 / 尾号X / 新客）+ chevron。 */
@Composable
private fun CustomerRow(
    customer: Customer,
    showDivider: Boolean,
    onClick: () -> Unit,
) {
    val name = customer.name?.takeIf { it.isNotBlank() } ?: "未知顾客"
    val enabled = customer.cid != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Avatar(name = name, sage = ((customer.cid ?: 0L) % 2L == 0L))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                color = MeiliPalette.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = metaLine(customer),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Normal,
                    letterSpacing = 0.sp,
                ),
                color = MeiliPalette.Ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Icon(
            MeiliIcons.ChevRight,
            contentDescription = null,
            tint = MeiliPalette.Clay,
            modifier = Modifier.size(Dimens.IconSm),
        )
    }
    if (showDivider) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MeiliPalette.LineSoft),
        )
    }
}

/** meta：会员卡号 if present else 尾号X else 新客。 */
private fun metaLine(customer: Customer): String {
    customer.memberCard?.takeIf { it.isNotBlank() }?.let { return it }
    customer.phoneTail?.takeIf { it.isNotBlank() }?.let { return "尾号$it" }
    return "新客"
}

// ─────────────────────────── 头像 ───────────────────────────

/** 顾客头像（姓名首字；陶土/鼠尾草双色，对齐 ReceptionScreen 的 Avatar）。 */
@Composable
private fun Avatar(name: String, sage: Boolean) {
    Box(
        modifier = Modifier
            .size(Dimens.Avatar)
            .background(
                if (sage) MeiliPalette.SageSoft else MeiliPalette.ClaySoft,
                MeiliShapes.Sm,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.take(1),
            style = MaterialTheme.typography.headlineMedium.copy(fontSize = 17.sp),
            color = if (sage) MeiliPalette.SageDeep else MeiliPalette.ClayDeep,
        )
    }
}

// ─────────────────────────── 公共小件（对齐 ArchiveScreen）───────────────────────────

@Composable
private fun InlineLoading(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            color = MeiliPalette.Clay,
            strokeWidth = 2.dp,
        )
        Text(text, style = MaterialTheme.typography.bodySmall, color = MeiliPalette.Ink3)
    }
}

@Composable
private fun EmptyHint(icon: ImageVector, title: String, sub: String?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(58.dp)
                .background(MeiliPalette.SurfaceSoft, RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MeiliPalette.Ink4,
                modifier = Modifier.size(30.dp),
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
            color = MeiliPalette.Ink2,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 14.dp),
        )
        if (!sub.isNullOrBlank()) {
            Text(
                sub,
                style = MaterialTheme.typography.bodySmall,
                color = MeiliPalette.Ink3,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF8F3ED, widthDp = 360, heightDp = 760)
@Composable
private fun CustomerScreenPreview() {
    MeiliTheme { CustomerScreen() }
}
