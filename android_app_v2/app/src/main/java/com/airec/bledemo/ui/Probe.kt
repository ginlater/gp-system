package com.airec.bledemo.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.airec.bledemo.designsystem.MeiliTheme

/** 工具链探针：验证 Kotlin + Compose + Material3 编译链路。后续可删。 */
@Composable
fun Probe() {
    MeiliTheme {
        Column {
            Text("美丽陪伴")
            Button(onClick = {}) { Text("点击开启陪伴") }
        }
    }
}

@Preview
@Composable
private fun ProbePreview() {
    Probe()
}
