package com.airec.bledemo.ui.followup

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.airec.bledemo.data.model.FormField
import com.airec.bledemo.data.model.FuCustomer
import com.airec.bledemo.designsystem.Dimens
import com.airec.bledemo.designsystem.MeiliIcons
import com.airec.bledemo.designsystem.MeiliPalette
import com.airec.bledemo.designsystem.MeiliShapes
import com.airec.bledemo.designsystem.components.GhostButton
import com.airec.bledemo.designsystem.components.MeiliBottomSheet
import com.airec.bledemo.designsystem.components.MeiliCard
import com.airec.bledemo.designsystem.components.MeiliTopBar
import com.airec.bledemo.designsystem.components.PillKind
import com.airec.bledemo.designsystem.components.PrimaryButton
import com.airec.bledemo.designsystem.components.SoftButton
import com.airec.bledemo.designsystem.components.StatusPill

/**
 * 回访话术 / 高情商话术 v3(规格驱动原生表单)。
 *
 * 对齐网页版体验的三块(用户反馈):
 *  1. 生成结果**内联在表单下方**同一滚动页——生成时自动滚到结果,随时能滑回上面改表单;
 *  2. 话术做轻量 markdown 渲染(### 标题/**粗体**/列表),不再露原始符号;
 *  3. 「导入历史」完整回归:顾客档案列表(收藏 ⭐/导入回填/查看历史话术)+ 战果登记。
 * 页内 A－/A＋ 字号(在全局字体档位上再乘系数,持久化)。
 */
@Composable
fun FollowupScreen(
    systemKey: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    vm: FollowupViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(systemKey) { vm.init(systemKey) }
    LaunchedEffect(state.toast) {
        state.toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.toastShown()
        }
    }

    // 页内字号(A－/A＋)
    val prefs = remember { context.getSharedPreferences("followup_ui", Context.MODE_PRIVATE) }
    var fontFactor by remember { mutableFloatStateOf(prefs.getFloat("font_factor", 1.0f)) }
    LaunchedEffect(fontFactor) { prefs.edit().putFloat("font_factor", fontFactor).apply() }
    val baseDensity = LocalDensity.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MeiliPalette.Bg)
            .statusBarsPadding()
            .padding(horizontal = Dimens.ScreenH)
            .imePadding(),
    ) {
        MeiliTopBar(
            title = state.system.displayName,
            subtitle = state.quotaText.ifBlank { "填写情况 · AI 生成话术" },
            onBack = onBack,
            actions = {
                FontZoomButton("A－") { fontFactor = (fontFactor - 0.1f).coerceAtLeast(0.8f) }
                FontZoomButton("A＋") { fontFactor = (fontFactor + 0.1f).coerceAtMost(1.5f) }
            },
        )

        CompositionLocalProvider(
            LocalDensity provides Density(baseDensity.density, baseDensity.fontScale * fontFactor),
        ) {
            when {
                state.loginError != null -> ErrorRetry(state.loginError!!) { vm.loadQuota() }
                state.spec == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MeiliPalette.Clay, strokeWidth = 2.5.dp, modifier = Modifier.size(26.dp))
                }
                else -> FormWithResult(state = state, vm = vm, context = context)
            }
        }
    }

    // 导入历史 sheet
    HistorySheet(state = state, vm = vm)
    // 战果登记 sheet
    state.businessFor?.let { c -> BusinessSheet(customer = c, vm = vm) }
}

// ============================================================================
// 表单 + 内联结果(同一滚动列表)
// ============================================================================

@Composable
private fun FormWithResult(state: FollowupUiState, vm: FollowupViewModel, context: Context) {
    val visibleFields = state.visibleBranchFields()
    val listState = rememberLazyListState()

    // 点了生成 → 自动滚到结果区(倒数第2项=结果卡);用户仍可随时往上滑回表单
    LaunchedEffect(state.generating) {
        if (state.generating) {
            kotlinx.coroutines.delay(120)   // 等结果卡进列表
            val last = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
            listState.animateScrollToItem(last)
        }
    }

    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(Dimens.S2),
        modifier = Modifier.fillMaxSize(),
    ) {
        // ---- AI 模型(胶囊上带 剩余/总额度) ----
        item(key = "model") {
            MeiliCard(tight = true) {
                FieldLabel("AI 模型", required = false, note = null)
                val labelOf = FollowupViewModel.MODELS.associate { (m, l) -> "$l${state.quotaSuffix(m)}" to m }
                OptionChips(
                    options = labelOf.keys.toList(),
                    selected = labelOf.entries.filter { it.value == state.model }.map { it.key }.toSet(),
                    onClick = { label -> labelOf[label]?.let { vm.setModel(it) } },
                )
            }
        }
        // ---- 顾客姓名 + 手机尾号 + 导入历史(一行,对齐网页 customer-bar) ----
        item(key = "customer") {
            MeiliCard(tight = true) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Dimens.S2),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = state.texts["customerName"].orEmpty(),
                        onValueChange = { vm.setText("customerName", it) },
                        placeholder = { Text("顾客姓名*", color = MeiliPalette.Ink4) },
                        singleLine = true,
                        shape = MeiliShapes.Sm,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        colors = fieldColors(),
                        modifier = Modifier.weight(1.1f),
                    )
                    OutlinedTextField(
                        value = state.texts["customerPhoneSuffix"].orEmpty(),
                        onValueChange = { v -> vm.setText("customerPhoneSuffix", v.filter { it.isDigit() }.take(4)) },
                        placeholder = { Text("尾号4位", color = MeiliPalette.Ink4) },
                        singleLine = true,
                        shape = MeiliShapes.Sm,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        colors = fieldColors(),
                        modifier = Modifier.weight(0.8f),
                    )
                    SoftButton("导入历史", onClick = { vm.openHistory() })
                }
            }
        }
        // ---- 顾问 / 门店(自动带出,只读) ----
        item(key = "advisor") {
            MeiliCard(tight = true) {
                Row {
                    Column(Modifier.weight(1f)) {
                        Text("顾问姓名", style = MaterialTheme.typography.labelSmall, color = MeiliPalette.Ink3)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            state.texts["consultantName"].orEmpty().ifBlank { "—" },
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = MeiliPalette.Ink,
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text("所属门店", style = MaterialTheme.typography.labelSmall, color = MeiliPalette.Ink3)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            state.texts["companyName"].orEmpty().ifBlank { "—" },
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = MeiliPalette.Ink,
                        )
                    }
                }
            }
        }
        // ---- 其余公共字段 ----
        val preCommon = state.spec?.common.orEmpty().filter {
            it.id !in HANDLED_COMMON_IDS && it.renderAfterBranches != true
        }
        items(preCommon.size, key = { "c${preCommon[it].id}" }) { i ->
            FieldCard(preCommon[i], state, vm)
        }
        // ---- 消息性质 ----
        item(key = "nature") {
            MeiliCard(tight = true) {
                FieldLabel("本次消息性质", required = true, note = "决定下方要填的内容")
                OptionChips(
                    options = state.branches.mapNotNull { it.nature },
                    selected = setOf(state.natureText),
                    onClick = { nature ->
                        vm.selectNature(state.branches.indexOfFirst { it.nature == nature }.coerceAtLeast(0))
                    },
                )
            }
        }
        // ---- 分支字段(级联) ----
        items(visibleFields.size, key = { "f${state.natureIndex}-$it-${visibleFields[it].id}" }) { i ->
            FieldCard(visibleFields[i], state, vm)
        }
        // ---- 分支后公共字段(noteDetail) ----
        val postCommon = state.spec?.common.orEmpty().filter { it.renderAfterBranches == true }
        items(postCommon.size, key = { "pc${postCommon[it].id}" }) { i ->
            FieldCard(postCommon[i], state, vm)
        }
        // ---- 生成按钮 ----
        item(key = "go") {
            PrimaryButton(
                text = if (state.generating) "正在生成…" else "生成话术",
                icon = MeiliIcons.Spark,
                onClick = { vm.generate() },
                enabled = !state.generating,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // ---- 生成结果(内联;可滑回上方改表单) ----
        if (state.generating || state.output.isNotBlank() || state.genError != null) {
            item(key = "result") {
                MeiliCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (state.generating) "正在生成…" else "生成结果",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MeiliPalette.Ink,
                        )
                        Spacer(Modifier.weight(1f))
                        if (state.generating) {
                            CircularProgressIndicator(color = MeiliPalette.Clay, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        }
                    }
                    Spacer(Modifier.height(Dimens.S2))
                    when {
                        state.genError != null -> Text(state.genError, style = MaterialTheme.typography.bodyMedium, color = MeiliPalette.RoseText)
                        state.output.isBlank() -> Text(
                            "AI 正在思考,首段通常几秒内出现…",
                            style = MaterialTheme.typography.bodySmall, color = MeiliPalette.Ink3,
                        )
                        else -> MarkdownText(state.output)
                    }
                    if (state.warnWords.isNotEmpty()) {
                        Spacer(Modifier.height(Dimens.S2))
                        StatusPill("含敏感词提醒:${state.warnWords.joinToString("、")}", PillKind.Warn, icon = MeiliIcons.Warn)
                    }
                    Spacer(Modifier.height(Dimens.S3))
                    if (state.generating) {
                        GhostButton("停止", onClick = { vm.stopGenerate() })
                    } else {
                        // 话术含 版本1/2/3 时按版本分开复制;否则整篇复制
                        val versions = remember(state.output) { splitVersions(state.output) }
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(Dimens.S2),
                            verticalArrangement = Arrangement.spacedBy(Dimens.S2),
                        ) {
                            if (versions.size >= 2) {
                                versions.forEach { (label, content) ->
                                    PrimaryButton("复制$label", onClick = {
                                        copyToClipboard(context, content)
                                        Toast.makeText(context, "$label 已复制", Toast.LENGTH_SHORT).show()
                                    })
                                }
                                GhostButton("复制全部", onClick = {
                                    copyToClipboard(context, plainCopyText(state.output))
                                    Toast.makeText(context, "已复制全部", Toast.LENGTH_SHORT).show()
                                })
                            } else {
                                PrimaryButton("复制话术", onClick = {
                                    copyToClipboard(context, plainCopyText(state.output))
                                    Toast.makeText(context, "话术已复制", Toast.LENGTH_SHORT).show()
                                }, enabled = state.output.isNotBlank())
                            }
                            SoftButton("重新生成", onClick = { vm.generate() })
                        }
                    }
                }
            }
        }
        item(key = "bottom") { Spacer(Modifier.navigationBarsPadding()) }
    }
}

// ============================================================================
// 轻量 markdown 渲染(### 标题 / **粗体** / - 列表)
// ============================================================================

@Composable
private fun MarkdownText(text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        text.split("\n").forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.startsWith("#") -> {
                    val title = line.trimStart('#').trim()
                    if (title.isNotBlank()) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MeiliPalette.ClayDeep,
                            modifier = Modifier.padding(top = 7.dp, bottom = 2.dp),
                        )
                    }
                }
                line.startsWith("- ") || line.startsWith("* ") -> Text(
                    inlineBold("•  " + line.drop(2).trim()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MeiliPalette.Ink,
                )
                line.isBlank() -> Spacer(Modifier.height(3.dp))
                else -> Text(
                    inlineBold(line),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 1.12,
                    ),
                    color = MeiliPalette.Ink,
                )
            }
        }
    }
}

/** 解析行内 **粗体**。 */
private fun inlineBold(s: String): AnnotatedString = buildAnnotatedString {
    val parts = s.split("**")
    parts.forEachIndexed { i, part ->
        if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(part) }
        else append(part)
    }
}

// ============================================================================
// 导入历史 sheet
// ============================================================================

@Composable
private fun HistorySheet(state: FollowupUiState, vm: FollowupViewModel) {
    if (!state.historyOpen) return
    var expanded by remember { mutableStateOf<String?>(null) }

    MeiliBottomSheet(
        visible = true,
        onDismiss = { vm.closeHistory() },
        title = "导入历史",
        subtitle = "生成过的顾客都在这里 · 点 ⭐ 收藏 · 可导入回填/登记战果",
        scrollable = true,
    ) {
        when {
            state.historyLoading -> Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MeiliPalette.Clay, strokeWidth = 2.5.dp, modifier = Modifier.size(24.dp))
            }
            state.customers.isEmpty() -> Text(
                "还没有历史记录,生成一次话术后会自动保存到这里",
                style = MaterialTheme.typography.bodySmall, color = MeiliPalette.Ink3,
                modifier = Modifier.padding(vertical = 20.dp),
            )
            else -> {
                // 收藏在前,其余按保存时间倒序
                val sorted = state.customers.sortedWith(
                    compareByDescending<FuCustomer> { it.favorited == true }
                        .thenByDescending { it.savedAt.orEmpty() },
                )
                sorted.forEach { c ->
                    CustomerRow(
                        c = c,
                        expanded = expanded == c.name,
                        onToggleExpand = { expanded = if (expanded == c.name) null else c.name },
                        vm = vm,
                    )
                    Spacer(Modifier.height(Dimens.S2))
                }
            }
        }
    }
}

@Composable
private fun CustomerRow(
    c: FuCustomer,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    vm: FollowupViewModel,
) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        Modifier
            .fillMaxWidth()
            .background(MeiliPalette.Surface, MeiliShapes.Md)
            .border(Dimens.BorderThin, MeiliPalette.LineSoft, MeiliShapes.Md)
            .padding(Dimens.S3),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 收藏星
            Icon(
                MeiliIcons.Star,
                contentDescription = "收藏",
                tint = if (c.favorited == true) MeiliPalette.Honey else MeiliPalette.Ink4,
                modifier = Modifier
                    .size(22.dp)
                    .clickable(interactionSource = interaction, indication = null) { vm.toggleFavorite(c) },
            )
            Spacer(Modifier.size(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    c.name ?: "未命名",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MeiliPalette.Ink,
                )
                Text(
                    "${c.savedAt.orEmpty().take(16).replace('T', ' ')} · ${c.scripts?.size ?: 0} 版话术",
                    style = MaterialTheme.typography.labelSmall,
                    color = MeiliPalette.Ink4,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(Dimens.S2))
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.S2)) {
            SoftButton("导入", onClick = { vm.importCustomer(c) })
            GhostButton("战果登记", onClick = { vm.openBusiness(c) })
            GhostButton(if (expanded) "收起话术" else "看话术", onClick = onToggleExpand)
        }
        if (expanded && !c.lastScript.isNullOrBlank()) {
            Spacer(Modifier.height(Dimens.S2))
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(MeiliPalette.SurfaceSoft, MeiliShapes.Sm)
                    .padding(Dimens.S2),
            ) {
                MarkdownText(c.lastScript)
            }
        }
    }
}

// ============================================================================
// 战果登记 sheet(字段对齐服务端 BusinessRequest)
// ============================================================================

@Composable
private fun BusinessSheet(customer: FuCustomer, vm: FollowupViewModel) {
    // 草稿态(打开时从最新版本已有战果回填)
    val draft = remember(customer.name) {
        val last = customer.scripts?.lastOrNull()?.business.orEmpty()
        mutableStateMapOf<String, Any?>().apply { putAll(last) }
    }
    fun str(k: String) = draft[k] as? String ?: ""
    fun list(k: String) = (draft[k] as? List<*>)?.filterIsInstance<String>() ?: emptyList()

    // 展开逻辑 1:1 对齐网页(selectBReplied/selectBVisited/selectBDeal):
    // 答了 ⑧ 才出 ⑩;考虑/拒绝才出 ⑨回复内容+未到店原因;已预约才出 ⑪到店;
    // 已到店才出 到店日期+成交;没成交出原因、成交了出金额段位+精确+项目。
    val replied = str("replied")
    val visited = str("visited")
    val deal = str("deal")

    MeiliBottomSheet(
        visible = true,
        onDismiss = { vm.closeBusiness() },
        title = "战果登记 · ${customer.name}",
        subtitle = "话术发出后,客人实际情况如何(写最新一版)",
        scrollable = true,
    ) {
        BizChips("① 客户类型", listOf("常到店", "久违客", "新客"), str("customer_type")) { draft["customer_type"] = it }
        BizChips(
            "② 上次到店时间",
            listOf("2周以内", "2周到1个月", "1个月到3个月", "3个月到6个月", "6个月到1年", "1年到2年", "2年以上"),
            str("last_visit_period"),
        ) { draft["last_visit_period"] = it }
        BizChipsMulti("③ 今日跟进动作(可多选)", listOf("邀约", "日常维护", "唤醒"), list("today_actions")) { draft["today_actions"] = it }
        BizChips("④ 3个月内客人回店没有?", listOf("有", "没有"), str("recent_visit_3m")) { draft["recent_visit_3m"] = it }
        BizChips("④ 3个月内客人充值没有?", listOf("有", "没有"), str("recent_recharge_3m")) { draft["recent_recharge_3m"] = it }
        BizText("⑤ 回店客情如何做(必填)", str("return_visit_plan")) { draft["return_visit_plan"] = it }
        BizChips("⑥ 沟通方式是什么?", listOf("电话", "微信文字"), str("channel")) { draft["channel"] = it }
        BizChips("⑦ 话术的版本", listOf("V1", "V2", "V3", "自定义"), str("msg_version")) { v ->
            draft["msg_version"] = v
            if (v != "自定义") draft["msg_version_custom"] = ""
        }
        if (str("msg_version") == "自定义") {
            BizText("自定义消息内容", str("msg_version_custom")) { draft["msg_version_custom"] = it }
        }
        BizChips("⑧ 顾客回复了吗?", listOf("没回复", "已读未回", "已预约", "考虑", "拒绝"), replied) { v ->
            draft["replied"] = v
            // 对齐网页:切换回复态时清掉不再显示的下游值
            if (v !in listOf("考虑", "拒绝")) {
                draft["reply_content"] = ""; draft["no_visit_reason"] = ""
            }
            if (v != "已预约") {
                draft["visited"] = ""; draft["visit_date"] = ""
                draft["deal"] = ""; draft["no_deal_reason"] = ""
                draft["deal_amount_range"] = ""; draft["deal_amount_exact"] = null; draft["deal_project"] = ""
            }
        }
        // —— ⑧ 之后才逐步展开 ——
        if (replied in listOf("考虑", "拒绝")) {
            BizText("⑨ 回复内容", str("reply_content")) { draft["reply_content"] = it }
            BizText("未到店原因", str("no_visit_reason")) { draft["no_visit_reason"] = it }
        }
        if (replied.isNotBlank()) {
            BizText("⑩ 下次跟进时间(如 2026-07-20)", str("next_followup_time")) { draft["next_followup_time"] = it }
        }
        if (replied == "已预约") {
            BizChips("⑪ 到店了吗?", listOf("还没到店", "约了改期", "已到店"), visited) { v ->
                draft["visited"] = v
                if (v != "已到店") {
                    draft["visit_date"] = ""
                    draft["deal"] = ""; draft["no_deal_reason"] = ""
                    draft["deal_amount_range"] = ""; draft["deal_amount_exact"] = null; draft["deal_project"] = ""
                }
            }
        }
        if (replied == "已预约" && visited == "已到店") {
            BizText("到店日期(如 2026-07-18)", str("visit_date")) { draft["visit_date"] = it }
            BizChips("⑪ 成交了吗?", listOf("没成交", "成交了"), deal) { v ->
                draft["deal"] = v
                if (v == "没成交") {
                    draft["deal_amount_range"] = ""; draft["deal_amount_exact"] = null; draft["deal_project"] = ""
                } else {
                    draft["no_deal_reason"] = ""
                }
            }
            if (deal == "没成交") {
                BizText("未成交原因", str("no_deal_reason")) { draft["no_deal_reason"] = it }
            }
            if (deal == "成交了") {
                BizChips("⑫ 成交金额段位", listOf("1千以内", "1-3千", "3-5千", "5千-1万", "1-3万"), str("deal_amount_range")) { v ->
                    draft["deal_amount_range"] = v
                    draft["deal_amount_exact"] = null   // 选段位则清精确金额(对齐网页)
                }
                BizText("精确金额(填了会取消段位)", draft["deal_amount_exact"]?.let {
                    if (it is Double && it % 1.0 == 0.0) it.toLong().toString() else it.toString()
                }.orEmpty()) { v ->
                    draft["deal_amount_exact"] = v.toDoubleOrNull()
                    if (!v.isBlank()) draft["deal_amount_range"] = ""
                }
                BizText("成交项目", str("deal_project")) { draft["deal_project"] = it }
            }
        }
        Spacer(Modifier.height(Dimens.S3))
        PrimaryButton("提交战果", onClick = { vm.submitBusiness(draft.toMap()) }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(Dimens.S2))
    }
}

@Composable
private fun BizChips(label: String, options: List<String>, selected: String, onPick: (String) -> Unit) {
    Column(Modifier.padding(bottom = Dimens.S2)) {
        Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = MeiliPalette.Ink)
        Spacer(Modifier.height(5.dp))
        OptionChips(options = options, selected = setOf(selected), onClick = { onPick(if (it == selected) "" else it) })
    }
}

@Composable
private fun BizChipsMulti(label: String, options: List<String>, selected: List<String>, onPick: (List<String>) -> Unit) {
    Column(Modifier.padding(bottom = Dimens.S2)) {
        Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = MeiliPalette.Ink)
        Spacer(Modifier.height(5.dp))
        OptionChips(
            options = options,
            selected = selected.toSet(),
            onClick = { o -> onPick(if (o in selected) selected - o else selected + o) },
        )
    }
}

@Composable
private fun BizText(label: String, value: String, onChange: (String) -> Unit) {
    Column(Modifier.padding(bottom = Dimens.S2)) {
        Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = MeiliPalette.Ink)
        Spacer(Modifier.height(5.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            shape = MeiliShapes.Sm,
            textStyle = MaterialTheme.typography.bodyMedium,
            colors = fieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ============================================================================
// 规格字段渲染(与 v2 相同)
// ============================================================================

@Composable
private fun FieldCard(f: FormField, state: FollowupUiState, vm: FollowupViewModel) {
    val id = f.id ?: return
    MeiliCard(tight = true) {
        val title = listOfNotNull(f.optionsSection, f.label).joinToString(" · ").ifBlank { id }
        FieldLabel(title, required = f.required == true, note = f.sublabelNote)
        val opts = state.resolvedOptions(f)
        when {
            f.isOptions && opts.isEmpty() -> OutlinedTextField(
                value = state.texts[id].orEmpty(),
                onValueChange = { vm.setText(id, it) },
                placeholder = { Text(f.placeholder ?: f.label.orEmpty(), color = MeiliPalette.Ink4) },
                singleLine = true,
                shape = MeiliShapes.Sm,
                textStyle = MaterialTheme.typography.bodyMedium,
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            f.isOptions -> {
                OptionChips(
                    options = opts,
                    selected = state.selections[id].orEmpty().toSet(),
                    onClick = { opt -> vm.toggleOption(id, opt, f.multi == true) },
                )
                val ci = f.customInput
                if (ci?.id != null && ci.showWhen.orEmpty().any { it in state.selections[id].orEmpty() }) {
                    Spacer(Modifier.height(Dimens.S2))
                    OutlinedTextField(
                        value = state.texts[ci.id].orEmpty(),
                        onValueChange = { vm.setText(ci.id!!, it) },
                        placeholder = { Text(ci.placeholder.orEmpty(), color = MeiliPalette.Ink4) },
                        minLines = 1,
                        shape = MeiliShapes.Sm,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        colors = fieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            else -> {
                val single = f.isText || id.endsWith("Addressing")
                OutlinedTextField(
                    value = state.texts[id].orEmpty(),
                    onValueChange = { v ->
                        vm.setText(id, if (id == "customerPhoneSuffix") v.filter { it.isDigit() }.take(4) else v)
                    },
                    placeholder = { Text(f.placeholder.orEmpty(), color = MeiliPalette.Ink4) },
                    singleLine = single,
                    minLines = if (single) 1 else 3,
                    shape = MeiliShapes.Sm,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String, required: Boolean, note: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = MeiliPalette.Ink,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(
            if (required) " 必填" else " 选填",
            style = MaterialTheme.typography.labelSmall,
            color = if (required) MeiliPalette.RoseText else MeiliPalette.Ink4,
        )
    }
    if (!note.isNullOrBlank()) {
        Text(
            note,
            style = MaterialTheme.typography.labelSmall,
            color = MeiliPalette.Ink4,
            modifier = Modifier.padding(bottom = 5.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionChips(
    options: List<String>,
    selected: Set<String>,
    onClick: (String) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Dimens.S2),
        verticalArrangement = Arrangement.spacedBy(Dimens.S2),
    ) {
        options.forEach { text ->
            val isSel = text in selected
            val interaction = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .background(
                        if (isSel) MeiliPalette.ClayTint else MeiliPalette.SurfaceSoft,
                        MeiliShapes.Pill,
                    )
                    .border(
                        Dimens.BorderField,
                        if (isSel) MeiliPalette.Clay else MeiliPalette.LineSoft,
                        MeiliShapes.Pill,
                    )
                    .clickable(interactionSource = interaction, indication = null) { onClick(text) }
                    .padding(horizontal = 11.dp, vertical = 6.dp),
            ) {
                Text(
                    text,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = if (isSel) MeiliPalette.ClayDeep else MeiliPalette.Ink2,
                )
            }
        }
    }
}

// ============================================================================
// 杂项
// ============================================================================

/** 已在专属卡里渲染的公共字段 id(通用渲染跳过)。 */
private val HANDLED_COMMON_IDS = setOf(
    "modelSelect", "visitNature", "customerName", "customerPhoneSuffix", "consultantName", "companyName",
)

@Composable
private fun FontZoomButton(text: String, onClick: () -> Unit) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        shape = MeiliShapes.IconButton,
        color = MeiliPalette.Surface,
        contentColor = MeiliPalette.Ink2,
        border = androidx.compose.foundation.BorderStroke(Dimens.BorderThin, MeiliPalette.Line),
        shadowElevation = Dimens.Elev1,
        modifier = Modifier.size(Dimens.IconButton),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
        }
    }
}

@Composable
private fun ErrorRetry(message: String, onRetry: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 70.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(MeiliIcons.Warn, contentDescription = null, tint = MeiliPalette.Rose, modifier = Modifier.size(34.dp))
        Spacer(Modifier.height(Dimens.S3))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MeiliPalette.Ink2)
        Spacer(Modifier.height(Dimens.S4))
        PrimaryButton("重试", onClick = onRetry)
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MeiliPalette.Clay,
    unfocusedBorderColor = MeiliPalette.Line,
    focusedContainerColor = MeiliPalette.Surface,
    unfocusedContainerColor = MeiliPalette.SurfaceSoft,
)

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("话术", text))
}

/** 复制时去掉 markdown 符号(**加粗、#标题),微信里直接可发。 */
private fun plainCopyText(md: String): String =
    md.replace("**", "")
        .lines()
        .joinToString("\n") { it.trimStart('#').trimStart() }
        .trim()

private val VERSION_MARK = Regex("""(?m)^\s*\*{0,2}版本\s*([0-9一二三])[^\n]*""")

/**
 * 把话术按「版本1/2/3」标记拆成 (标签, 正文) 列表(正文已去 markdown,不含版本标题行)。
 * 找不到 ≥2 个版本标记时返回空列表(按整篇复制处理)。
 */
private fun splitVersions(text: String): List<Pair<String, String>> {
    val marks = VERSION_MARK.findAll(text).toList()
    if (marks.size < 2) return emptyList()
    return marks.mapIndexed { i, m ->
        val start = m.range.last + 1
        val end = if (i + 1 < marks.size) marks[i + 1].range.first else text.length
        val label = "版本${m.groupValues[1]}"
        label to plainCopyText(text.substring(start, end))
    }.filter { it.second.isNotBlank() }
}
