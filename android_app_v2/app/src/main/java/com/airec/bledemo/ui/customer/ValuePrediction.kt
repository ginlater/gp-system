package com.airec.bledemo.ui.customer

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airec.bledemo.designsystem.MeiliPalette

/* ===================================================================
 * 客户价值预测渲染（1:1 还原 web_v2/templates/customer_profile.html 的
 * 4 个维度卡 + markdown-lite 渲染器 mdLite）。
 *
 * web 的格式：每个维度一张「彩色头部 + 正文」卡（💎客户价值评估 / 🎯攻坚作战方案 /
 * 📋项目规划 / 📈经营规划 / 🤝顾问匹配度），正文是 markdown-lite——
 * #标题 / ▶小标 / -要点 / 1.编号 / **加粗**(蜜色高亮) / ★星级 / ✓✗ / |表格|。
 * 这里照搬其解析与样式，emoji 图标换成本 app 的定制线性图标（守「禁 emoji」红线）。
 * =================================================================== */

// 各维度强调色（对齐 web VP_DIMS）。
internal val VpGold = Color(0xFFB8860B)
internal val VpRed = Color(0xFFD9534F)
internal val VpBlue = Color(0xFF4A90D9)
internal val VpGreen = Color(0xFF2E9E6B)
internal val VpPurple = Color(0xFF7B5EC7)

private val StarGold = Color(0xFFF0A92B)
private val OkGreen = Color(0xFF22A36B)
private val NoRed = Color(0xFFD9534F)
private val HeadInk = Color(0xFF2A2D3A) // 正文标题/加粗的深色（偏中性，配暖卡也不跳）

/**
 * 维度卡：彩色渐变头部（图标 chip + 标题）+ 白底正文。对齐 web .vp-card。
 */
@Composable
internal fun VpCard(
    title: String,
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
    body: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MeiliPalette.Surface,
        contentColor = MeiliPalette.Ink,
        shadowElevation = 3.dp,
    ) {
        Column {
            // 头部：accent → accent(.78) 渐变，白字白图标
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(
                            listOf(accent, accent.copy(alpha = 0.80f)),
                        ),
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.24f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp))
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.5f.sp, fontWeight = FontWeight.Bold),
                    color = Color.White,
                )
            }
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                body()
            }
        }
    }
}

/**
 * markdown-lite 正文渲染器（对齐 web mdLite）。
 * @param accent 维度强调色：用于标题左条 / ▶小标 / 要点圆点 / 编号圆 / 表头底。
 */
@Composable
internal fun MdLiteContent(
    raw: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(raw) { parseMdLite(raw) }
    Column(modifier = modifier.fillMaxWidth()) {
        blocks.forEach { b -> MdBlockView(b, accent) }
    }
}

// ─────────────────────────── 解析 ───────────────────────────

private sealed interface MdBlock
private data class MdHeading(val text: String) : MdBlock
private data class MdSub(val text: String) : MdBlock
private data class MdBullets(val items: List<String>) : MdBlock
private data class MdNumbered(val items: List<String>) : MdBlock
private data class MdParagraph(val text: String) : MdBlock
private data class MdTable(val header: List<String>?, val rows: List<List<String>>) : MdBlock

private val HEADING = Regex("^#{1,6}\\s*(.+)$")
private val SUB = Regex("^[▶▷►]\\s*(.+)$")
private val BULLET = Regex("^[-*•·]\\s+(.+)$")
private val NUMBERED = Regex("^\\d+[.、)]\\s*(.+)$")
private val TABLE_ROW = Regex("^\\|.*\\|")

/** 行级解析，镜像 web mdLite 的状态机（list/table flush）。 */
private fun parseMdLite(raw: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    var ulItems: MutableList<String>? = null
    var olItems: MutableList<String>? = null
    var tbl: MutableList<List<String>>? = null
    var tblHeader = false

    fun flushList() {
        ulItems?.let { if (it.isNotEmpty()) out.add(MdBullets(it.toList())); ulItems = null }
        olItems?.let { if (it.isNotEmpty()) out.add(MdNumbered(it.toList())); olItems = null }
    }
    fun flushTbl() {
        val t = tbl ?: return
        if (t.isNotEmpty()) {
            val header = if (tblHeader) t.first() else null
            val body = if (tblHeader) t.drop(1) else t
            out.add(MdTable(header, body))
        }
        tbl = null; tblHeader = false
    }
    fun flushAll() { flushList(); flushTbl() }

    fun cellsOf(t: String): List<String> =
        t.trim().trim('|').split("|").map { it.trim() }
    fun isSep(arr: List<String>): Boolean =
        arr.isNotEmpty() && arr.all { c -> Regex("^:?-{2,}:?$").matches(c.replace(" ", "")) || Regex("^-+$").matches(c) }

    for (line in raw.split("\n")) {
        val t = line.trim()
        if (t.isEmpty()) { flushAll(); continue }

        if (TABLE_ROW.containsMatchIn(t)) {
            flushList()
            val arr = cellsOf(t)
            if (isSep(arr)) { if (tbl?.size == 1) tblHeader = true; continue }
            (tbl ?: mutableListOf<List<String>>().also { tbl = it }).add(arr)
            continue
        }
        flushTbl()

        HEADING.find(t)?.let { flushList(); out.add(MdHeading(it.groupValues[1])); return@let } ?: run {
            SUB.find(t)?.let { flushList(); out.add(MdSub(it.groupValues[1])) } ?: run {
                BULLET.find(t)?.let { m ->
                    olItems?.let { flushList() }
                    (ulItems ?: mutableListOf<String>().also { ulItems = it }).add(m.groupValues[1])
                } ?: run {
                    NUMBERED.find(t)?.let { m ->
                        ulItems?.let { flushList() }
                        (olItems ?: mutableListOf<String>().also { olItems = it }).add(m.groupValues[1])
                    } ?: run {
                        flushList()
                        out.add(MdParagraph(t))
                    }
                }
            }
        }
    }
    flushAll()
    return out
}

// ─────────────────────────── 渲染各块 ───────────────────────────

@Composable
private fun MdBlockView(block: MdBlock, accent: Color) {
    when (block) {
        is MdHeading -> Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.width(3.dp).size(width = 3.dp, height = 15.dp).background(accent))
            Text(
                text = inlineMd(block.text),
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.5f.sp, fontWeight = FontWeight.Bold),
                color = HeadInk,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        is MdSub -> Text(
            text = inlineMd(block.text),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold),
            color = accent,
            modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
        )
        is MdParagraph -> Text(
            text = inlineMd(block.text),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 22.sp),
            color = MeiliPalette.Ink,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        is MdBullets -> Column(modifier = Modifier.padding(vertical = 3.dp)) {
            block.items.forEach { item ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                    Box(
                        modifier = Modifier
                            .padding(top = 7.dp)
                            .size(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(accent),
                    )
                    Text(
                        text = inlineMd(item),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 21.sp),
                        color = MeiliPalette.Ink,
                        modifier = Modifier.padding(start = 11.dp),
                    )
                }
            }
        }
        is MdNumbered -> Column(modifier = Modifier.padding(vertical = 3.dp)) {
            block.items.forEachIndexed { i, item ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(accent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "${i + 1}",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5f.sp, fontWeight = FontWeight.Bold),
                            color = Color.White,
                        )
                    }
                    Text(
                        text = inlineMd(item),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 21.sp),
                        color = MeiliPalette.Ink,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }
        }
        is MdTable -> MdTableView(block, accent)
    }
}

@Composable
private fun MdTableView(table: MdTable, accent: Color) {
    val cols = (table.header?.size ?: table.rows.maxOfOrNull { it.size } ?: 1).coerceAtLeast(1)
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
        shape = RoundedCornerShape(9.dp),
        color = MeiliPalette.Surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MeiliPalette.Line),
    ) {
        Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            // 每列固定 128dp，超宽横向滚动（对齐 web min-width + overflow-x:auto）
            table.header?.let { h ->
                Row(modifier = Modifier.background(accent)) {
                    for (ci in 0 until cols) {
                        Text(
                            text = h.getOrElse(ci) { "" },
                            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                            color = Color.White,
                            modifier = Modifier.width(128.dp).padding(horizontal = 11.dp, vertical = 9.dp),
                        )
                    }
                }
            }
            table.rows.forEachIndexed { ri, row ->
                Row(
                    modifier = Modifier.background(
                        if (ri % 2 == 1) MeiliPalette.SurfaceSoft else MeiliPalette.Surface,
                    ),
                ) {
                    for (ci in 0 until cols) {
                        Box(
                            modifier = Modifier
                                .width(128.dp)
                                .background(MeiliPalette.Line)
                                .padding(top = 1.dp, start = if (ci == 0) 0.dp else 1.dp),
                        ) {
                            Text(
                                text = inlineMd(row.getOrElse(ci) { "" }),
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5f.sp, lineHeight = 18.sp),
                                color = MeiliPalette.Ink,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(if (ri % 2 == 1) MeiliPalette.SurfaceSoft else MeiliPalette.Surface)
                                    .padding(horizontal = 11.dp, vertical = 9.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────── 行内：**加粗** / ★星级 / ✓✗ ───────────────────────────

/** 行内格式 → AnnotatedString（对齐 web inline）：**加粗**(蜜色高亮)、★☆(金)、✓(绿)、✗×(红)。 */
private fun inlineMd(text: String): AnnotatedString = buildAnnotatedString {
    // 先按 ** 切分：奇数段为加粗
    text.split("**").forEachIndexed { idx, part ->
        val bold = idx % 2 == 1
        if (bold) {
            pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = HeadInk, background = MeiliPalette.HoneySoft))
        }
        appendColored(part)
        if (bold) pop()
    }
}

/** 在当前样式栈上，把 ★☆/✓/✗× 上色，其余原样追加。 */
private fun AnnotatedString.Builder.appendColored(s: String) {
    var i = 0
    val specials = charArrayOf('★', '☆', '✓', '✗', '✘', '×')
    while (i < s.length) {
        val c = s[i]
        when {
            c == '★' || c == '☆' -> {
                val start = i
                while (i < s.length && (s[i] == '★' || s[i] == '☆')) i++
                pushStyle(SpanStyle(color = StarGold, fontWeight = FontWeight.Bold))
                append(s.substring(start, i))
                pop()
            }
            c == '✓' -> { pushStyle(SpanStyle(color = OkGreen, fontWeight = FontWeight.Bold)); append("✓"); pop(); i++ }
            c == '✗' || c == '✘' || c == '×' -> { pushStyle(SpanStyle(color = NoRed, fontWeight = FontWeight.Bold)); append(c.toString()); pop(); i++ }
            else -> {
                val start = i
                while (i < s.length && s[i] !in specials) i++
                append(s.substring(start, i))
            }
        }
    }
}
