package com.airec.bledemo.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.airec.bledemo.designsystem.Dimens
import com.airec.bledemo.designsystem.MeiliTheme
import com.airec.bledemo.designsystem.components.MeiliTopBar

/**
 * 各占位屏共用的「建设中」骨架：暖玉柔光底 + [MeiliTopBar]（中文屏名 + 可选副标题）+ 居中一句提示。
 *
 * 这是壳阶段的临时填充，**screen agent 接手后可整段替换为真实内容**（删掉本调用即可，
 * 不影响别的屏，因为每个 *Screen 文件独立）。保留它只是为了"装到手机能进、有屏名、不空白"。
 *
 * @param title 顶栏主标题（该屏中文名）
 * @param onBack 非空时顶栏显示返回按钮（次级页用；tab 屏传 null）
 * @param note 居中提示文案（默认「建设中」）
 */
@Composable
fun PlaceholderScreen(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    note: String = "建设中",
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MeiliTheme.colors.bg),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.ScreenH),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            MeiliTopBar(title = title, onBack = onBack)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 120.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MeiliTheme.colors.ink3,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
