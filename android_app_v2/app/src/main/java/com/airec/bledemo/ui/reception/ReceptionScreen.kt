package com.airec.bledemo.ui.reception

import android.app.DatePickerDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.airec.bledemo.data.model.Customer
import com.airec.bledemo.data.model.TodayReception
import com.airec.bledemo.designsystem.Dimens
import com.airec.bledemo.designsystem.MeiliIcons
import com.airec.bledemo.designsystem.MeiliPalette
import com.airec.bledemo.designsystem.MeiliShapes
import com.airec.bledemo.designsystem.MeiliTheme
import com.airec.bledemo.designsystem.components.GhostButton
import com.airec.bledemo.designsystem.components.MeiliBottomSheet
import com.airec.bledemo.designsystem.components.MeiliCard
import com.airec.bledemo.designsystem.components.PillKind
import com.airec.bledemo.designsystem.components.PrimaryButton
import com.airec.bledemo.designsystem.components.StatusPill
import com.airec.bledemo.designsystem.components.TopBarIconButton
import com.airec.bledemo.designsystem.components.MeiliTopBar
import java.util.Calendar

/**
 * 今日接诊（SPEC §4.5 / warm_2 #reception）。
 *
 * 今日接诊列表（顾客名 / 尾号 / 新老客 / 状态），顶部刷新 + 提醒铃；日期切换条（前一天 / 后一天 / 选日期，
 * 受补登窗口约束、不越过今天）；「加入今日接诊」弹层（搜本公司顾客→选已有 / 填姓名+尾号建新人）；
 * 长按某条→移出今日接诊。每 20s 自动刷新（仅看「今天」时静默轮询）。
 *
 * 数据 / 状态全在 [ReceptionViewModel]；导航回调由 [com.airec.bledemo.nav.AppScaffold] 注入。
 *
 * @param onOpenReminders 右上角铃→提醒
 * @param modifier 由 AppScaffold 传入（含底栏避让 padding）
 * @param viewModel 状态机（默认走 [androidx.lifecycle.viewmodel.compose.viewModel]）
 */
@Composable
fun ReceptionScreen(
    onOpenReminders: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: ReceptionViewModel = viewModel(),
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 一次性 toast（成交 / 删除 / 错误）——用系统返回值不便，这里走轻量内联条
    LaunchedEffect(state.toast) {
        if (state.toast != null) {
            kotlinx.coroutines.delay(2200)
            viewModel.toastShown()
        }
    }

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
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            item {
                MeiliTopBar(
                    title = "今日接诊",
                    subtitle = "陪伴前后，请把今天要接诊的顾客加进来",
                    actions = {
                        TopBarIconButton(MeiliIcons.Refresh, onClick = { viewModel.refresh() })
                        TopBarIconButton(MeiliIcons.Reminder, onClick = onOpenReminders)
                    },
                )
            }

            // 日期切换条
            item {
                Spacer(Modifier.height(4.dp))
                DateSwitch(
                    date = state.date,
                    isToday = state.isToday,
                    onPrev = { viewModel.prevDay() },
                    onNext = { viewModel.nextDay() },
                    onToday = { viewModel.goToday() },
                    onPick = {
                        showDatePicker(
                            context = context,
                            current = state.date,
                            minDate = viewModel.minBackfillDate,
                            maxDate = viewModel.maxDate,
                            onPicked = { viewModel.selectDate(it) },
                        )
                    },
                )
                Spacer(Modifier.height(Dimens.S3))
            }

            // 信息横幅：每 20s 自动刷新
            item {
                InfoBanner(
                    text = "陪伴结束后，只能绑定到这个列表里的人。今日列表每 20 秒自动刷新。",
                )
                Spacer(Modifier.height(Dimens.CardGap))
            }

            // 列表 / 空态 / 加载 / 错误
            when {
                state.loading && state.items.isEmpty() -> item { LoadingRow() }
                state.errorMsg != null && state.items.isEmpty() -> item {
                    EmptyState(
                        icon = MeiliIcons.Warn,
                        title = "没能加载接诊列表",
                        sub = state.errorMsg ?: "请稍后重试",
                    )
                }
                state.items.isEmpty() -> item {
                    EmptyState(
                        icon = MeiliIcons.Reception,
                        title = if (state.isToday) "今天还没有接诊顾客" else "这一天没有接诊记录",
                        sub = "点下方「加入今日接诊」，把要陪伴的顾客加进来",
                    )
                }
                else -> item {
                    MeiliCard(tight = true) {
                        state.items.forEachIndexed { idx, item ->
                            ReceptionRow(
                                item = item,
                                showDivider = idx != state.items.lastIndex,
                                onEditDate = { viewModel.openEditDate(item) },
                                onRemove = { viewModel.askRemove(item) },
                            )
                        }
                    }
                }
            }

            // 加入按钮
            item {
                Spacer(Modifier.height(Dimens.S1))
                PrimaryButton(
                    text = "加入今日接诊",
                    onClick = { viewModel.openAddSheet() },
                    icon = MeiliIcons.Add,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // toast 浮条（贴底，避让底栏）
        state.toast?.let { msg ->
            ToastBar(
                text = msg,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = Dimens.ScreenH, vertical = 8.dp),
            )
        }
    }

    // 新增 / 补登 弹层
    state.addSheet?.let { sheet ->
        AddReceptionSheet(
            sheet = sheet,
            onDismiss = { viewModel.closeAddSheet() },
            onSwitchMode = { viewModel.switchAddMode(it) },
            onQueryChange = { viewModel.onSearchQueryChange(it) },
            onPickExisting = { viewModel.addExisting(it) },
            onNewNameChange = { viewModel.onNewNameChange(it) },
            onNewPhoneTailChange = { viewModel.onNewPhoneTailChange(it) },
            onNewMemberCardChange = { viewModel.onNewMemberCardChange(it) },
            onSubmitNew = { viewModel.addNew() },
        )
    }

    // 删除确认
    state.confirmRemove?.let { item ->
        ConfirmRemoveSheet(
            item = item,
            onCancel = { viewModel.cancelRemove() },
            onConfirm = { viewModel.confirmRemove() },
        )
    }

    // 改接诊日期
    state.editDate?.let { ed ->
        EditDateSheet(
            state = ed,
            onDismiss = { viewModel.closeEditDate() },
            onPickDate = {
                showDatePicker(
                    context = context,
                    current = ed.target,
                    minDate = ed.minDate,
                    maxDate = ed.maxDate,
                    onPicked = { viewModel.onEditDatePicked(it) },
                )
            },
            onConfirm = { viewModel.confirmEditDate() },
        )
    }
}

// ─────────────────────────── 日期切换条 ───────────────────────────

@Composable
private fun DateSwitch(
    date: String,
    isToday: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    onPick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        DateNavButton(MeiliIcons.ChevLeft, onClick = onPrev)
        Surface(
            onClick = onPick,
            modifier = Modifier.weight(1f),
            shape = MeiliShapes.Sm,
            color = MeiliPalette.Surface,
            contentColor = MeiliPalette.Ink,
            border = BorderStroke(Dimens.BorderThin, MeiliPalette.Line),
            shadowElevation = Dimens.Elev1,
        ) {
            Text(
                text = date,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                color = MeiliPalette.Ink,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 11.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        DateNavButton(
            MeiliIcons.ChevRight,
            onClick = onNext,
            enabled = !isToday,
        )
        if (isToday) {
            Surface(
                shape = MeiliShapes.Pill,
                color = MeiliPalette.LeafSoft,
                contentColor = MeiliPalette.LeafText,
            ) {
                Text(
                    text = "今天",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.sp,
                    ),
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
                )
            }
        } else {
            // 回到今天（对齐 web #todayBtn，仅非今天时显示）
            Surface(
                onClick = onToday,
                shape = MeiliShapes.Pill,
                color = MeiliPalette.ClaySoft,
                contentColor = MeiliPalette.ClayDeep,
                border = BorderStroke(Dimens.BorderThin, MeiliPalette.Line),
            ) {
                Text(
                    text = "回到今天",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.sp,
                    ),
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
                )
            }
        }
    }
}

@Composable
private fun DateNavButton(
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MeiliShapes.Sm,
        color = MeiliPalette.Surface,
        contentColor = if (enabled) MeiliPalette.Ink2 else MeiliPalette.Ink4,
        border = BorderStroke(Dimens.BorderThin, MeiliPalette.Line),
        shadowElevation = Dimens.Elev1,
        modifier = Modifier.size(38.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(Dimens.IconSm))
        }
    }
}

/** 拉起系统日期选择器，限制在补登窗口内、不越过今天。 */
private fun showDatePicker(
    context: android.content.Context,
    current: String,
    minDate: String,
    maxDate: String,
    onPicked: (String) -> Unit,
) {
    fun toMillis(s: String): Long? {
        val p = s.split("-").mapNotNull { it.toIntOrNull() }
        if (p.size != 3) return null
        return Calendar.getInstance().apply {
            clear()
            set(p[0], p[1] - 1, p[2], 0, 0, 0)
        }.timeInMillis
    }
    val minMs = toMillis(minDate)
    val maxMs = toMillis(maxDate)
    // 初始日期夹到 [min,max] 区间内，避免越界时系统抛 IllegalArgumentException
    val initMs = (toMillis(current) ?: maxMs)?.let { ms ->
        var v = ms
        if (minMs != null && v < minMs) v = minMs
        if (maxMs != null && v > maxMs) v = maxMs
        v
    }
    val cal = Calendar.getInstance()
    if (initMs != null) cal.timeInMillis = initMs
    val dlg = DatePickerDialog(
        context,
        { _, y, m, d ->
            onPicked("%04d-%02d-%02d".format(y, m + 1, d))
        },
        cal.get(Calendar.YEAR),
        cal.get(Calendar.MONTH),
        cal.get(Calendar.DAY_OF_MONTH),
    )
    minMs?.let { dlg.datePicker.minDate = it }
    maxMs?.let { dlg.datePicker.maxDate = it }
    dlg.show()
}

// ─────────────────────────── 列表行 ───────────────────────────

@Composable
private fun ReceptionRow(
    item: TodayReception,
    showDivider: Boolean,
    onEditDate: () -> Unit,
    onRemove: () -> Unit,
) {
    val name = item.name?.takeIf { it.isNotBlank() } ?: "未知顾客"
    // 可编辑态（未分析/未运行 + 近 7 天内）才给「改接诊日期」按钮，对齐 web editable。
    val editable = item.editable
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Avatar(name = name, sage = (item.id % 2L == 0L))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                color = MeiliPalette.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = metaLine(item),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Normal,
                    letterSpacing = 0.sp,
                ),
                color = MeiliPalette.Ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            statusFor(item)?.let { (text, kind, icon) ->
                Spacer(Modifier.height(7.dp))
                StatusPill(text = text, kind = kind, icon = icon)
            }
        }
        // 改接诊日期（仅可编辑态）—— 日历类图标用 MeiliIcons.Reception（日历+心）
        if (editable) {
            RowIconButton(
                icon = MeiliIcons.Reception,
                contentDescription = "改接诊日期",
                onClick = onEditDate,
            )
        }
        // 移出今日接诊
        RowIconButton(
            icon = MeiliIcons.Trash,
            contentDescription = "移出今日接诊",
            onClick = onRemove,
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

/** 列表行右侧的方形线性图标按钮（改日期 / 移出共用）。 */
@Composable
private fun RowIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MeiliShapes.Sm,
        color = MeiliPalette.Surface,
        contentColor = MeiliPalette.Ink3,
        border = BorderStroke(Dimens.BorderThin, MeiliPalette.Line),
        modifier = Modifier.size(36.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(Dimens.IconSm))
        }
    }
}

/** 尾号 + 会员号 / 新老客拼一行 meta。 */
private fun metaLine(item: TodayReception): String {
    val parts = mutableListOf<String>()
    item.phoneTail?.takeIf { it.isNotBlank() }?.let { parts.add("尾号$it") }
    val card = item.memberCard?.takeIf { it.isNotBlank() }
    if (card != null) parts.add(card) else parts.add("新客")
    return parts.joinToString(" · ")
}

/** 把 analysis_status + 录音数 + 换绑审批数映射成一个状态胶囊（顾客可见，零「录音」字样）。 */
private fun statusFor(item: TodayReception): Triple<String, PillKind, ImageVector?>? {
    val rebind = item.pendingRebindCount ?: 0
    if (rebind > 0) {
        return Triple("换绑审批中（$rebind）", PillKind.Warn, MeiliIcons.Clock)
    }
    return when (item.analysisStatus) {
        "done" -> Triple("已分析", PillKind.Ok, MeiliIcons.Check)
        "failed" -> Triple("分析失败，可重试", PillKind.Danger, MeiliIcons.Warn)
        "running", "queued" -> Triple("分析中…", PillKind.Run, MeiliIcons.Sync)
        "pending" -> Triple("待分析", PillKind.Warn, MeiliIcons.Clock)
        null, "" -> {
            val n = item.recordingCount ?: 0
            if (n > 0) {
                Triple("$n 段陪伴待分析", PillKind.Warn, MeiliIcons.Clock)
            } else {
                Triple("尚未开启陪伴", PillKind.Neutral, null)
            }
        }
        else -> Triple("待分析", PillKind.Warn, MeiliIcons.Clock)
    }
}

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

// ─────────────────────────── 新增 / 补登 弹层 ───────────────────────────

@Composable
private fun AddReceptionSheet(
    sheet: AddSheetState,
    onDismiss: () -> Unit,
    onSwitchMode: (AddMode) -> Unit,
    onQueryChange: (String) -> Unit,
    onPickExisting: (Customer) -> Unit,
    onNewNameChange: (String) -> Unit,
    onNewPhoneTailChange: (String) -> Unit,
    onNewMemberCardChange: (String) -> Unit,
    onSubmitNew: () -> Unit,
) {
    val sub = if (sheet.targetDate == ReceptionViewModel.todayStr()) {
        "把已有或新顾客加入今天的接诊列表。陪伴结束后只能绑定到这里的人。"
    } else {
        "补登到 ${sheet.targetDate}。陪伴结束后只能绑定到这里的人。"
    }
    MeiliBottomSheet(
        visible = true,
        onDismiss = onDismiss,
        title = "加入今日接诊",
        subtitle = sub,
    ) {
        // 模式切换 segmented
        SegmentedTwo(
            leftText = "搜已有顾客",
            rightText = "新建顾客",
            leftOn = sheet.mode == AddMode.Existing,
            onLeft = { onSwitchMode(AddMode.Existing) },
            onRight = { onSwitchMode(AddMode.New) },
        )
        Spacer(Modifier.height(Dimens.S4))

        if (sheet.mode == AddMode.Existing) {
            MeiliTextField(
                value = sheet.query,
                onValueChange = onQueryChange,
                placeholder = "搜姓名 / 会员号 / 手机尾号",
                leadingIcon = MeiliIcons.Search,
            )
            Spacer(Modifier.height(Dimens.S3))
            when {
                sheet.searching -> LoadingRow()
                sheet.query.isBlank() -> Text(
                    text = "输入关键词搜本公司顾客",
                    style = MaterialTheme.typography.bodySmall,
                    color = MeiliPalette.Ink3,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                sheet.results.isEmpty() -> Text(
                    text = "没有匹配的顾客，可切到「新建顾客」",
                    style = MaterialTheme.typography.bodySmall,
                    color = MeiliPalette.Ink3,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                else -> LazyColumn(
                    modifier = Modifier.heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    items(sheet.results, key = { it.cid ?: it.hashCode().toLong() }) { c ->
                        SearchResultRow(
                            customer = c,
                            enabled = !sheet.submitting,
                            onClick = { onPickExisting(c) },
                        )
                    }
                }
            }
        } else {
            MeiliTextField(
                value = sheet.newName,
                onValueChange = onNewNameChange,
                placeholder = "顾客姓名（必填）",
                leadingIcon = MeiliIcons.Profile,
            )
            Spacer(Modifier.height(Dimens.S3))
            MeiliTextField(
                value = sheet.newPhoneTail,
                onValueChange = onNewPhoneTailChange,
                placeholder = "手机尾号 4 位（必填）",
                leadingIcon = MeiliIcons.Phone,
                keyboardType = KeyboardType.Number,
            )
            Spacer(Modifier.height(Dimens.S3))
            MeiliTextField(
                value = sheet.newMemberCard,
                onValueChange = onNewMemberCardChange,
                placeholder = "会员号（可留空，自动生成）",
                leadingIcon = MeiliIcons.Star,
            )
            Spacer(Modifier.height(Dimens.S4))
            PrimaryButton(
                text = if (sheet.submitting) "提交中…" else "新建并加入",
                onClick = onSubmitNew,
                icon = MeiliIcons.Add,
                enabled = !sheet.submitting,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        sheet.errorMsg?.let {
            Spacer(Modifier.height(Dimens.S3))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MeiliPalette.RoseText,
            )
        }
        Spacer(Modifier.height(Dimens.S2))
    }
}

@Composable
private fun SearchResultRow(
    customer: Customer,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val name = customer.name?.takeIf { it.isNotBlank() } ?: "未知顾客"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Avatar(name = name, sage = false)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                color = MeiliPalette.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = buildList {
                customer.phoneTail?.takeIf { it.isNotBlank() }?.let { add("尾号$it") }
                customer.memberCard?.takeIf { it.isNotBlank() }?.let { add(it) }
            }.joinToString(" · ").ifEmpty { "无尾号 / 会员号" }
            Text(
                text = meta,
                style = MaterialTheme.typography.bodySmall,
                color = MeiliPalette.Ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Icon(
            MeiliIcons.Add,
            contentDescription = null,
            tint = MeiliPalette.Clay,
            modifier = Modifier.size(Dimens.IconSm),
        )
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MeiliPalette.LineSoft),
    )
}

// ─────────────────────────── 删除确认 弹层 ───────────────────────────

@Composable
private fun ConfirmRemoveSheet(
    item: TodayReception,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val name = item.name?.takeIf { it.isNotBlank() } ?: "该顾客"
    MeiliBottomSheet(
        visible = true,
        onDismiss = onCancel,
        title = "移出今日接诊",
        subtitle = "把「$name」移出 ${item.serviceDate ?: "今日"} 的接诊列表？若已开启过陪伴并绑定，将无法移除。",
    ) {
        PrimaryButton(
            text = "确认移出",
            onClick = onConfirm,
            icon = MeiliIcons.Trash,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Dimens.S2))
        GhostButton(
            text = "取消",
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Dimens.S2))
    }
}

// ─────────────────────────── 改接诊日期 弹层 ───────────────────────────

@Composable
private fun EditDateSheet(
    state: EditDateState,
    onDismiss: () -> Unit,
    onPickDate: () -> Unit,
    onConfirm: () -> Unit,
) {
    val name = state.item.name?.takeIf { it.isNotBlank() } ?: "该顾客"
    MeiliBottomSheet(
        visible = true,
        onDismiss = onDismiss,
        title = "改接诊日期",
        subtitle = "把「$name」的接诊改到另一天（限原日期前 7 天内、不超过今天）。",
    ) {
        // 原日期
        Text(
            text = "原日期：${state.item.serviceDate ?: "—"}",
            style = MaterialTheme.typography.bodySmall,
            color = MeiliPalette.Ink3,
        )
        Spacer(Modifier.height(Dimens.S3))

        // 新日期：点开系统日期选择器（限 [原日期-7, 今天]）
        Text(
            text = "改到",
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.sp),
            color = MeiliPalette.Ink2,
        )
        Spacer(Modifier.height(6.dp))
        Surface(
            onClick = { if (!state.submitting) onPickDate() },
            modifier = Modifier.fillMaxWidth(),
            shape = MeiliShapes.Sm,
            color = MeiliPalette.SurfaceSoft,
            contentColor = MeiliPalette.Ink,
            border = BorderStroke(Dimens.BorderThin, MeiliPalette.Line),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 15.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Icon(MeiliIcons.Reception, contentDescription = null, modifier = Modifier.size(Dimens.IconSm), tint = MeiliPalette.Ink3)
                Text(
                    text = state.target,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                    color = MeiliPalette.Ink,
                    modifier = Modifier.weight(1f),
                )
                Icon(MeiliIcons.ChevDown, contentDescription = null, modifier = Modifier.size(Dimens.IconSm), tint = MeiliPalette.Ink4)
            }
        }

        // 已绑录音提示：确认后会自动解绑
        if (state.recordingCount > 0) {
            Spacer(Modifier.height(Dimens.S3))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MeiliShapes.Md,
                color = MeiliPalette.HoneySoft,
                contentColor = MeiliPalette.HoneyText,
                border = BorderStroke(Dimens.BorderThin, MeiliPalette.Honey),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 15.dp, vertical = 13.dp),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(MeiliIcons.Warn, contentDescription = null, modifier = Modifier.size(Dimens.IconSm))
                    Text(
                        text = "已绑 ${state.recordingCount} 段陪伴，改日期后会自动解绑、退回待整理。",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MeiliPalette.HoneyText,
                    )
                }
            }
        }

        state.errorMsg?.let {
            Spacer(Modifier.height(Dimens.S3))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MeiliPalette.RoseText,
            )
        }

        Spacer(Modifier.height(Dimens.S4))
        PrimaryButton(
            text = if (state.submitting) "修改中…" else "确认修改",
            onClick = onConfirm,
            icon = MeiliIcons.Check,
            enabled = !state.submitting,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Dimens.S2))
        GhostButton(
            text = "取消",
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Dimens.S2))
    }
}

// ─────────────────────────── 局部小组件 ───────────────────────────

@Composable
private fun SegmentedTwo(
    leftText: String,
    rightText: String,
    leftOn: Boolean,
    onLeft: () -> Unit,
    onRight: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MeiliPalette.SurfaceSoft, MeiliShapes.Pill)
            .border(BorderStroke(Dimens.BorderThin, MeiliPalette.Line), MeiliShapes.Pill)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SegItem(leftText, leftOn, onLeft, Modifier.weight(1f))
        SegItem(rightText, !leftOn, onRight, Modifier.weight(1f))
    }
}

@Composable
private fun SegItem(text: String, on: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = MeiliShapes.Pill,
        color = if (on) MeiliPalette.Surface else androidx.compose.ui.graphics.Color.Transparent,
        contentColor = if (on) MeiliPalette.ClayDeep else MeiliPalette.Ink2,
        shadowElevation = if (on) Dimens.Elev1 else 0.dp,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.sp,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun MeiliTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    leadingIcon: ImageVector? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = {
            Text(placeholder, style = LocalTextStyle.current, color = MeiliPalette.Ink3)
        },
        leadingIcon = leadingIcon?.let {
            { Icon(it, contentDescription = null, modifier = Modifier.size(Dimens.IconSm), tint = MeiliPalette.Ink3) }
        },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge,
        shape = MeiliShapes.Sm,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MeiliPalette.Clay,
            unfocusedBorderColor = MeiliPalette.Line,
            focusedContainerColor = MeiliPalette.White,
            unfocusedContainerColor = MeiliPalette.SurfaceSoft,
            cursorColor = MeiliPalette.Clay,
            focusedTextColor = MeiliPalette.Ink,
            unfocusedTextColor = MeiliPalette.Ink,
        ),
    )
}

@Composable
private fun InfoBanner(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MeiliShapes.Md,
        color = MeiliPalette.SageTint,
        contentColor = MeiliPalette.SageDeep,
        border = BorderStroke(Dimens.BorderThin, MeiliPalette.SageSoft),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 15.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(MeiliIcons.Info, contentDescription = null, modifier = Modifier.size(Dimens.IconSm))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = MeiliPalette.SageDeep,
            )
        }
    }
}

@Composable
private fun LoadingRow() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            color = MeiliPalette.Clay,
            strokeWidth = 2.5.dp,
            modifier = Modifier.size(28.dp),
        )
    }
}

@Composable
private fun EmptyState(icon: ImageVector, title: String, sub: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp, horizontal = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(58.dp)
                .background(MeiliPalette.SurfaceSoft, MeiliShapes.Lg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MeiliPalette.Ink4, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
            color = MeiliPalette.Ink2,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = sub,
            style = MaterialTheme.typography.bodySmall,
            color = MeiliPalette.Ink3,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun ToastBar(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MeiliShapes.Sm,
        color = MeiliPalette.InkSurface,
        contentColor = MeiliPalette.OnInk,
        shadowElevation = Dimens.Elev2,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MeiliPalette.OnInk,
            modifier = Modifier.padding(horizontal = 17.dp, vertical = 14.dp),
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 760)
@Composable
private fun ReceptionScreenPreview() {
    MeiliTheme { ReceptionScreen() }
}
