package com.airec.bledemo.designsystem

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 字体大小档位管理（设置 · 「字体大小」5 档滑块）。
 *
 * 用户在设置里拖 5 档滑块 → [level] 变化 → [scale] 随之变 → [MeiliTheme] 把它乘到
 * `LocalDensity.fontScale` 上 → 全 app 所有 `.sp` 文字按倍数放大/缩小。
 *
 * 实现对齐 [ThemeManager]：[level] 背后是 Compose [mutableStateOf]，任何 composable 读
 * [scale] 即建立快照订阅 → [setLevel] 改档后全 app 重组换字号。选择持久化在 SharedPreferences，重启沿用。
 */
object FontScaleManager {

    /** 5 档倍数：小 / 标准 / 大 / 更大 / 特大。默认第 2 档（标准 = 1.0）。 */
    val steps = listOf(0.85f, 1.0f, 1.15f, 1.30f, 1.45f)
    val labels = listOf("小", "标准", "大", "更大", "特大")

    private var levelState by mutableStateOf(1) // 0..4
    private var prefs: android.content.SharedPreferences? = null

    /** 当前档位下标（0..4），设置页滑块读它回显。 */
    val level: Int get() = levelState

    /** 当前字体倍数，[MeiliTheme] 读它缩放全 app 文字。 */
    val scale: Float get() = steps.getOrElse(levelState) { 1.0f }

    /** 当前档位名（如"标准"）。 */
    val label: String get() = labels.getOrElse(levelState) { "标准" }

    /** 进程启动时调（MeiliActivity.onCreate）：载入持久化档位。 */
    fun init(context: Context) {
        val p = context.getSharedPreferences("meili_font", Context.MODE_PRIVATE)
        prefs = p
        levelState = p.getInt("level", 1).coerceIn(0, steps.lastIndex)
    }

    /** 设定档位（0..4）：立即生效 + 持久化。 */
    fun setLevel(l: Int) {
        val v = l.coerceIn(0, steps.lastIndex)
        if (v == levelState) return
        levelState = v
        prefs?.edit()?.putInt("level", v)?.apply()
    }
}
