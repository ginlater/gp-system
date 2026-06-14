package com.airec.bledemo.designsystem

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * 主题皮肤管理（方案A「我的/设置 · 主题皮肤」）。
 *
 * 4 套配色，**只换陶土主色系列（clay 家族）**，其余（鼠尾草/墨/线/状态色/底色）保持不变——
 * 对齐原型 `redesign_2026-06-13.html` 的换肤（`.phone.theme-*` 仅覆盖 `--clay/-deep/-soft/-tint`）。
 *
 * 实现要点：[MeiliPalette] 的 clay 家族改成读本对象的当前皮肤；[currentId] 背后是 Compose
 * [mutableStateOf]，任何 composable 读 `MeiliPalette.Clay` 即建立快照订阅 → [apply] 改 id 后**全 app 重组换色**，
 * 无需逐屏改色。选择持久化在 SharedPreferences，重启沿用。
 */
object ThemeManager {

    /** 一套皮肤：只定义陶土家族 4 色，其余共用 [MeiliPalette]。 */
    data class Skin(
        val id: String,
        val name: String,
        val desc: String,
        val clay: Color,
        val clayDeep: Color,
        val claySoft: Color,
        val clayTint: Color,
    )

    val skins: List<Skin> = listOf(
        Skin("jade", "暖玉柔光", "陶土 + 鼠尾草（默认）", Color(0xFFBE7459), Color(0xFFA35E45), Color(0xFFEFDED4), Color(0xFFF8ECE4)),
        Skin("honey", "蜜糖暖金", "暖金色调", Color(0xFFC2954E), Color(0xFF9E7634), Color(0xFFECDDBE), Color(0xFFF7EFDD)),
        Skin("rose", "胭脂玫瑰", "柔粉色调", Color(0xFFC06B79), Color(0xFF9E4F5D), Color(0xFFEBD2D7), Color(0xFFF7E8EB)),
        Skin("sage", "青玉鼠尾", "沉静绿调", Color(0xFF7F9A82), Color(0xFF5F7A63), Color(0xFFD9E4DA), Color(0xFFECF2EC)),
    )

    // Compose State：读它的 composable 会在 apply() 时重组。
    private var idState by mutableStateOf("jade")
    private var prefs: android.content.SharedPreferences? = null

    val currentId: String get() = idState
    val current: Skin get() = skins.firstOrNull { it.id == idState } ?: skins[0]

    /** 主按钮渐变起点（略亮）/ 圆钮高光（更亮）：按当前主色朝白插值，任何皮肤都协调。 */
    val clayLight: Color get() = lerp(current.clay, Color.White, 0.13f)
    val clayGlow: Color get() = lerp(current.clay, Color.White, 0.32f)

    /** 进程启动时调（MeiliActivity.onCreate）：载入持久化皮肤。 */
    fun init(context: Context) {
        val p = context.getSharedPreferences("meili_theme", Context.MODE_PRIVATE)
        prefs = p
        val saved = p.getString("skin", "jade") ?: "jade"
        if (skins.any { it.id == saved }) idState = saved
    }

    /** 切换皮肤并持久化；全 app 立即重组换色。 */
    fun apply(id: String) {
        if (skins.none { it.id == id }) return
        idState = id
        prefs?.edit()?.putString("skin", id)?.apply()
    }
}
