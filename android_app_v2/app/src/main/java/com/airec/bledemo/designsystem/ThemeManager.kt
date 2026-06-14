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

    /**
     * 一套皮肤：陶土家族 4 色 + 整套底色/文字/线/状态柔底。
     * 浅色皮肤（jade/honey/rose/sage/simple）只填自己要变的字段，其余吃下面的浅色默认值；
     * 暗色皮肤（noir 黑金）把 dark=true 并整套覆盖成深色。
     */
    data class Skin(
        val id: String,
        val name: String,
        val desc: String,
        // 陶土主色家族
        val clay: Color,
        val clayDeep: Color,
        val claySoft: Color,
        val clayTint: Color,
        val dark: Boolean = false,
        // 底色 / 卡面（默认暖玉浅色）
        val bg: Color = Color(0xFFF8F3ED),
        val bg2: Color = Color(0xFFF2EBE2),
        val surface: Color = Color(0xFFFFFCF8),
        val surfaceSoft: Color = Color(0xFFFAF4ED),
        val surfaceFrost: Color = Color(0xBDFFFCF8),
        // 文字
        val ink: Color = Color(0xFF3D3833),
        val ink2: Color = Color(0xFF736A60),
        val ink3: Color = Color(0xFFA89F94),
        val ink4: Color = Color(0xFFC8BFB4),
        // 线
        val line: Color = Color(0xFFEBE2D7),
        val lineSoft: Color = Color(0xFFF2EBE1),
        // 状态柔底 + 文字（暗色皮肤需给暗变体，否则浅底块在深背景上突兀）
        val honeySoft: Color = Color(0xFFF0E2C8),
        val honeyText: Color = Color(0xFF8C6322),
        val roseSoft: Color = Color(0xFFF2DED7),
        val roseText: Color = Color(0xFF9B4B3A),
        val roseLine: Color = Color(0xFFE9C4BA),
        val leafSoft: Color = Color(0xFFDFEADD),
        val leafText: Color = Color(0xFF3D7150),
        val leafLine: Color = Color(0xFFC7DEC9),
        val sageSoft: Color = Color(0xFFE3E9DE),
        val sageTint: Color = Color(0xFFF1F4ED),
        val inkSurface: Color = Color(0xFF3D3833),
    )

    val skins: List<Skin> = listOf(
        Skin("jade", "暖玉柔光", "陶土 + 鼠尾草（默认）", Color(0xFFBE7459), Color(0xFFA35E45), Color(0xFFEFDED4), Color(0xFFF8ECE4)),
        Skin("honey", "蜜糖暖金", "暖金色调", Color(0xFFC2954E), Color(0xFF9E7634), Color(0xFFECDDBE), Color(0xFFF7EFDD)),
        Skin("rose", "胭脂玫瑰", "柔粉色调", Color(0xFFC06B79), Color(0xFF9E4F5D), Color(0xFFEBD2D7), Color(0xFFF7E8EB)),
        Skin("sage", "青玉鼠尾", "沉静绿调", Color(0xFF7F9A82), Color(0xFF5F7A63), Color(0xFFD9E4DA), Color(0xFFECF2EC)),
        // ── 黑金：真·暗色，近黑暖底 + 香槟金 ──
        Skin(
            "noir", "曜夜鎏金", "黑金 · 尊贵豪华",
            clay = Color(0xFFD4AF6A), clayDeep = Color(0xFFB8924A),
            claySoft = Color(0xFF3A3020), clayTint = Color(0xFF2A2418),
            dark = true,
            bg = Color(0xFF1A1613), bg2 = Color(0xFF141009),
            surface = Color(0xFF26201A), surfaceSoft = Color(0xFF221C16),
            surfaceFrost = Color(0xE026201A),
            ink = Color(0xFFF1E7D2), ink2 = Color(0xFFC8B89A),
            ink3 = Color(0xFF93876C), ink4 = Color(0xFF655B49),
            line = Color(0xFF3A332A), lineSoft = Color(0xFF2C2620),
            honeySoft = Color(0xFF3A2E18), honeyText = Color(0xFFE3BE72),
            roseSoft = Color(0xFF3A211A), roseText = Color(0xFFE0917A), roseLine = Color(0xFF5A3328),
            leafSoft = Color(0xFF1E2E20), leafText = Color(0xFF86C79B), leafLine = Color(0xFF2F4A36),
            sageSoft = Color(0xFF26302A), sageTint = Color(0xFF20281F),
            inkSurface = Color(0xFF0F0C09),
        ),
        // ── 简约大气：干净中性浅底 + 沉稳石墨主色 ──
        Skin(
            "simple", "简约大气", "高级灰 · 克制",
            clay = Color(0xFF54585F), clayDeep = Color(0xFF383B41),
            claySoft = Color(0xFFE7E8EA), clayTint = Color(0xFFF2F3F5),
            bg = Color(0xFFF6F6F4), bg2 = Color(0xFFEFEFEC),
            surface = Color(0xFFFFFFFF), surfaceSoft = Color(0xFFF4F4F2),
            surfaceFrost = Color(0xBDFFFFFF),
            ink = Color(0xFF2A2B2E), ink2 = Color(0xFF64666A),
            ink3 = Color(0xFF9A9CA1), ink4 = Color(0xFFC4C6CB),
            line = Color(0xFFE7E7E4), lineSoft = Color(0xFFF0F0EE),
        ),
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
