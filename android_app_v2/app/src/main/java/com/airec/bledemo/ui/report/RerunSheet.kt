package com.airec.bledemo.ui.report

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airec.bledemo.data.model.Task
import com.airec.bledemo.designsystem.Dimens
import com.airec.bledemo.designsystem.MeiliIcons
import com.airec.bledemo.designsystem.MeiliPalette
import com.airec.bledemo.designsystem.MeiliShapes
import com.airec.bledemo.designsystem.MeiliTheme
import com.airec.bledemo.designsystem.components.MeiliBottomSheet
import com.airec.bledemo.designsystem.components.PillKind
import com.airec.bledemo.designsystem.components.PrimaryButton
import com.airec.bledemo.designsystem.components.GhostButton
import com.airec.bledemo.designsystem.components.MeiliButtonSize
import com.airec.bledemo.designsystem.components.SoftButton
import com.airec.bledemo.designsystem.components.StatusPill

/* ===================================================================
 * 重跑弹层（warm_2 #m-rerun）。
 * 「9 / 11 完成」汇总 + 平铺 11 个任务 T1–T11（不分调用组、不出现"调用1/2/3"）
 * + 状态 pill（已完成/进行中/失败）+ 勾选重跑 + 全部重跑 + 开始重跑(N) + 补齐缺失。
 * =================================================================== */

/**
 * @param visible 是否显示
 * @param onDismiss 关闭
 * @param tasks 11 个任务（平铺；call 字段不展示）
 * @param loading 任务加载中
 * @param submitting 重跑/补齐提交中（按钮禁用）
 * @param onRerun 提交重跑所选任务（taskIds）
 * @param onFillMissing 补齐缺失任务
 */
@Composable
fun RerunSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    tasks: List<Task>,
    loading: Boolean,
    submitting: Boolean,
    onRerun: (List<String>) -> Unit,
    onFillMissing: () -> Unit,
) {
    if (!visible) return

    // 选中集合。默认勾选待处理（失败/进行中）任务。key 随任务列表变化重置。
    val selected = remember(tasks) {
        mutableStateMapOf(*tasks.map { it.id to (it.isFailed || it.isRunning) }.toTypedArray())
    }

    val done = tasks.count { it.isDone }
    val total = tasks.size
    val pending = total - done
    val pickedIds = tasks.map { it.id }.filter { selected[it] == true }
    val allOn = tasks.isNotEmpty() && pickedIds.size == tasks.size

    MeiliBottomSheet(
        visible = true,
        onDismiss = onDismiss,
        title = "任务执行状态",
        subtitle = "查看各任务状态，勾选可重跑指定任务。重跑只覆盖所选任务的结果，其余保持不变。",
    ) {
        // ---- 汇总 ----
        Row(
            modifier = Modifier.padding(bottom = 14.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "$done / ${total.takeIf { it > 0 } ?: 11}",
                style = MeiliTheme.summaryStyle,
                color = MeiliPalette.ClayDeep,
            )
            Text(
                if (total == 0) "加载中…" else "完成 · $pending 个待处理",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal),
                color = MeiliPalette.Ink3,
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }

        // ---- 全部重跑 ----
        CheckRow(
            checked = allOn,
            onToggle = {
                val turnOn = pickedIds.size != tasks.size
                tasks.forEach { selected[it.id] = turnOn }
            },
            dashed = true,
            enabled = tasks.isNotEmpty(),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("全部重跑", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.ExtraBold), color = MeiliPalette.Ink)
                Text("共 ${total.takeIf { it > 0 } ?: 11} 个任务", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal), color = MeiliPalette.Ink3)
            }
            StatusPill("全部", PillKind.Clay, icon = MeiliIcons.Refresh)
        }

        // ---- 任务列表（平铺 T1–T11）----
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 340.dp)
                .verticalScroll(rememberScrollState())
                .padding(top = 14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            if (loading && tasks.isEmpty()) {
                Text("任务加载中…", style = MaterialTheme.typography.bodyMedium, color = MeiliPalette.Ink3, modifier = Modifier.padding(vertical = 14.dp))
            } else if (tasks.isEmpty()) {
                Text("暂无任务数据", style = MaterialTheme.typography.bodyMedium, color = MeiliPalette.Ink3, modifier = Modifier.padding(vertical = 14.dp))
            } else {
                tasks.forEach { task ->
                    CheckRow(
                        checked = selected[task.id] == true,
                        onToggle = { selected[task.id] = !(selected[task.id] ?: false) },
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                task.id,
                                style = MeiliTheme.summaryStyle.copy(fontSize = 13.sp),
                                color = MeiliPalette.Ink3,
                                modifier = Modifier.padding(end = 7.dp),
                            )
                            Text(
                                task.name ?: task.id,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = MeiliPalette.Ink,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        TaskStatusPill(task)
                    }
                }
            }
        }

        // ---- 操作 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            PrimaryButton(
                text = "开始重跑 (${pickedIds.size})",
                onClick = { onRerun(pickedIds) },
                icon = MeiliIcons.Refresh,
                enabled = pickedIds.isNotEmpty() && !submitting,
                modifier = Modifier.weight(1f),
            )
            SoftButton(
                text = "补齐缺失",
                onClick = onFillMissing,
                icon = MeiliIcons.Refresh,
                enabled = !submitting,
            )
        }
        GhostButton(
            text = "关闭",
            onClick = onDismiss,
            size = MeiliButtonSize.Small,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
        )
    }
}

@Composable
private fun TaskStatusPill(task: Task) {
    when {
        task.isDone -> StatusPill("已完成", PillKind.Ok, icon = MeiliIcons.Check)
        task.isRunning -> StatusPill("进行中", PillKind.Clay, icon = MeiliIcons.Sync)
        task.isFailed -> StatusPill("失败", PillKind.Danger, icon = MeiliIcons.Warn)
        task.status == "missing" -> StatusPill("缺失", PillKind.Warn, icon = MeiliIcons.Clock)
        else -> StatusPill("待处理", PillKind.Warn, icon = MeiliIcons.Clock)
    }
}

/** .checkrow：勾选框 + 内容（行式选项）。选中态陶土 tint 边框/底。 */
@Composable
private fun CheckRow(
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    dashed: Boolean = false,
    enabled: Boolean = true,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val bg = when {
        checked -> MeiliPalette.ClayTint
        dashed -> MeiliPalette.SurfaceSoft
        else -> MeiliPalette.Surface
    }
    val borderColor = if (checked) MeiliPalette.Clay else MeiliPalette.Line
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable { onToggle() } else Modifier),
        shape = MeiliShapes.Sm,
        color = bg,
        border = BorderStroke(Dimens.BorderField, borderColor),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            CheckBox(checked)
            content()
        }
    }
}

/** .cbx：22×22 勾选格，选中态陶土底白勾。 */
@Composable
private fun CheckBox(checked: Boolean) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .background(if (checked) MeiliPalette.Clay else MeiliPalette.Surface, MeiliShapes.Xs)
            .borderXs(if (checked) MeiliPalette.Clay else MeiliPalette.Ink4),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(MeiliIcons.Check, contentDescription = null, tint = MeiliPalette.White, modifier = Modifier.size(15.dp))
        }
    }
}

private fun Modifier.borderXs(color: Color): Modifier =
    this.border(2.dp, color, MeiliShapes.Xs)
