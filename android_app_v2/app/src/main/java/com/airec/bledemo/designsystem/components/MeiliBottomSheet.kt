package com.airec.bledemo.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.airec.bledemo.designsystem.MeiliIcons
import com.airec.bledemo.designsystem.MeiliPalette
import com.airec.bledemo.designsystem.MeiliShapes
import com.airec.bledemo.designsystem.MeiliTheme

/**
 * 底部弹层 [MeiliBottomSheet]，还原 warm_2.html 的 .sheet：
 * bg 暖灰米底、顶部 r-xl(30) 圆角、抓手 grab、衬线标题 h3、可选副标题 sheetsub，
 * 内容滚动区。点遮罩 / 下滑关闭回调 [onDismiss]。
 *
 * 基于 M3 [ModalBottomSheet]，scrim/动画交给系统；视觉 token 全部对齐原型。
 *
 * @param visible 是否显示（false 时不组合任何内容）
 * @param onDismiss 关闭回调（点遮罩 / 下滑 / 系统返回）
 * @param title 衬线大标题
 * @param subtitle 可选副标题说明
 * @param onClose 传入则在标题行右上角显示 ✕ 关闭按钮（点 = onClose）
 * @param scrollable 内容过长时是否纵向滚动（默认 false；明细类长面板设 true，避免底部被裁切）
 * @param content sheet 主体，置于 [ColumnScope]，自带左右 20 内边距
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeiliBottomSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClose: (() -> Unit)? = null,
    scrollable: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!visible) return

    // SheetState 是 M3 实验 API；藏在函数体内（配合 @OptIn），不暴露到公开签名，
    // 这样调用方无需自己加 @OptIn(ExperimentalMaterial3Api)。
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = MeiliShapes.SheetTop,
        containerColor = MeiliPalette.Bg,
        contentColor = MeiliPalette.Ink,
        scrimColor = Color(0x66343030), // rgba(52,48,44,.40)
        dragHandle = { MeiliDragHandle() },
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 26.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MeiliPalette.Ink,
                    modifier = Modifier.weight(1f),
                )
                if (onClose != null) {
                    Surface(
                        onClick = onClose,
                        shape = MeiliShapes.Pill,
                        color = MeiliPalette.SurfaceSoft,
                        contentColor = MeiliPalette.Ink2,
                    ) {
                        Icon(
                            MeiliIcons.Close,
                            contentDescription = "关闭",
                            tint = MeiliPalette.Ink2,
                            modifier = Modifier.padding(7.dp).size(18.dp),
                        )
                    }
                }
            }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MeiliPalette.Ink3,
                    modifier = Modifier.padding(top = 5.dp, bottom = 16.dp),
                )
            } else {
                Spacer(Modifier.height(8.dp))
            }
            if (scrollable) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) { content() }
            } else {
                content()
            }
        }
    }
}

/** sheet 抓手 .grab：42×5 line 色圆条，居中。 */
@Composable
private fun MeiliDragHandle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 6.dp),
        contentAlignment = androidx.compose.ui.Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .width(42.dp)
                .height(5.dp)
                .background(MeiliPalette.Line, MeiliShapes.Pill),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, backgroundColor = 0xFFF8F3ED, widthDp = 360, heightDp = 420)
@Composable
private fun MeiliBottomSheetPreview() {
    MeiliTheme {
        // 预览里直接渲染 sheet 的「内容样式」骨架（ModalBottomSheet 在 IDE 预览中不展开）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("加入今日接诊", style = MaterialTheme.typography.headlineMedium, color = MeiliPalette.Ink)
            Text(
                "把已有或新顾客加入今天的接诊列表。陪伴结束后只能绑定到这里的人。",
                style = MaterialTheme.typography.bodySmall,
                color = MeiliPalette.Ink3,
            )
        }
    }
}
