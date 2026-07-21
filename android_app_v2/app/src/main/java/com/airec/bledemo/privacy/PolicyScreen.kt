package com.airec.bledemo.privacy

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airec.bledemo.designsystem.Dimens
import com.airec.bledemo.designsystem.MeiliPalette
import com.airec.bledemo.designsystem.MeiliShapes
import com.airec.bledemo.designsystem.components.MeiliTopBar
import org.json.JSONObject

/*
 * ★2026-07-19 隐私合规 —— 应用内隐私政策/用户协议原生页面。
 *
 * 【为什么有这个文件】vivo 驳回原文:
 *   「应用内无隐私政策或未向用户提供易于访问的隐私政策(APP内部无隐私政策)」
 * 审核员反馈:首启弹窗看到了,但点《隐私政策》是**跳外部浏览器**,
 * 商店要求政策必须在**应用内**可查看。
 *
 * 【做法】政策全文离线提取成 assets/policy_*.json(块结构:h2/h3/p/ul/table/meta),
 * 打进包里原生渲染:
 *   - 不跳浏览器、不依赖网络(飞行模式也能看,审核员必定看得到);
 *   - 与公网政策页同源同文(提取脚本在会话 scratchpad extract_policy.py,
 *     覆盖率校验过;网页版改了要重新提取并发版)。
 *
 * ⚠️ 三处入口(首启弹窗 / 登录页 / 设置页)全部指向本页面,不要再用
 *    uriHandler.openUri 跳浏览器。商店后台提交的隐私政策网址仍是
 *    PrivacyConsent.PRIVACY_URL,与本页内容一致。
 */

/** 政策文档:标题 + 块列表。 */
data class PolicyDoc(val title: String, val blocks: List<PolicyBlock>)

sealed class PolicyBlock {
    data class H2(val text: String) : PolicyBlock()
    data class H3(val text: String) : PolicyBlock()
    data class P(val text: String) : PolicyBlock()
    data class Meta(val text: String) : PolicyBlock()
    data class Ul(val items: List<String>) : PolicyBlock()
    data class Table(val rows: List<List<String>>) : PolicyBlock()
}

/** 政策类型 → assets 文件名。 */
enum class PolicyKind(val asset: String, val fallbackTitle: String) {
    Privacy("policy_privacy.json", "隐私政策"),
    Terms("policy_terms.json", "用户协议"),
    ;

    companion object {
        fun byKey(key: String): PolicyKind = if (key == Terms.name.lowercase()) Terms else Privacy
    }
}

/** 读 assets 里的政策 JSON。解析失败返回带提示的空文档(绝不崩)。 */
fun loadPolicyDoc(ctx: Context, kind: PolicyKind): PolicyDoc = runCatching {
    val raw = ctx.assets.open(kind.asset).bufferedReader().use { it.readText() }
    val obj = JSONObject(raw)
    val arr = obj.optJSONArray("blocks")
    val blocks = buildList {
        for (i in 0 until (arr?.length() ?: 0)) {
            val b = arr!!.getJSONObject(i)
            when (b.optString("t")) {
                "h2" -> add(PolicyBlock.H2(b.optString("text")))
                "h3" -> add(PolicyBlock.H3(b.optString("text")))
                "meta" -> add(PolicyBlock.Meta(b.optString("text")))
                "p" -> add(PolicyBlock.P(b.optString("text")))
                "ul" -> {
                    val items = b.optJSONArray("items")
                    add(PolicyBlock.Ul((0 until (items?.length() ?: 0)).map { items!!.getString(it) }))
                }
                "table" -> {
                    val rows = b.optJSONArray("rows")
                    add(
                        PolicyBlock.Table(
                            (0 until (rows?.length() ?: 0)).map { r ->
                                val row = rows!!.getJSONArray(r)
                                (0 until row.length()).map { row.getString(it) }
                            },
                        ),
                    )
                }
            }
        }
    }
    PolicyDoc(obj.optString("title").ifBlank { kind.fallbackTitle }, blocks)
}.getOrElse {
    PolicyDoc(
        kind.fallbackTitle,
        listOf(PolicyBlock.P("政策内容加载失败,请访问 ${PrivacyConsent.PRIVACY_URL} 查看完整版本。")),
    )
}

/**
 * 应用内政策页(整屏,自带返回)。首启弹窗/登录页/设置页三处入口共用。
 */
@Composable
fun PolicyScreen(
    kind: PolicyKind,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val doc = remember(kind) { loadPolicyDoc(ctx, kind) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MeiliPalette.Bg)
            .statusBarsPadding(),
    ) {
        MeiliTopBar(
            title = doc.title,
            subtitle = "成都永兴晟企业管理咨询有限公司",
            onBack = onBack,
            modifier = Modifier.padding(horizontal = Dimens.ScreenH),
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Dimens.ScreenH),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(doc.blocks.size) { i -> PolicyBlockView(doc.blocks[i]) }
            item { Spacer(Modifier.height(20.dp).navigationBarsPadding()) }
        }
    }
}

/** 政策弹窗内嵌用:无顶栏、可放进对话框/卡片的内容体。 */
@Composable
fun PolicyContent(kind: PolicyKind, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val doc = remember(kind) { loadPolicyDoc(ctx, kind) }
    Column(modifier = modifier) {
        doc.blocks.forEach { PolicyBlockView(it) }
    }
}

@Composable
private fun PolicyBlockView(block: PolicyBlock) {
    when (block) {
        is PolicyBlock.Meta -> Text(
            block.text,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5f.sp),
            color = MeiliPalette.Ink4,
            modifier = Modifier.padding(bottom = 8.dp, top = 4.dp),
        )
        is PolicyBlock.H2 -> Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 18.dp, bottom = 6.dp),
        ) {
            Box(
                Modifier
                    .size(width = 3.dp, height = 16.dp)
                    .background(MeiliPalette.Clay, MeiliShapes.Pill),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                block.text,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold, fontSize = 15.5f.sp,
                ),
                color = MeiliPalette.Ink,
            )
        }
        is PolicyBlock.H3 -> Text(
            block.text,
            style = MaterialTheme.typography.titleSmall.copy(
                fontWeight = FontWeight.Bold, fontSize = 13.5f.sp,
            ),
            color = MeiliPalette.ClayDeep,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )
        is PolicyBlock.P -> Text(
            block.text,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5f.sp, lineHeight = 21.sp),
            color = MeiliPalette.Ink2,
            modifier = Modifier.padding(vertical = 3.dp),
        )
        is PolicyBlock.Ul -> Column(modifier = Modifier.padding(vertical = 3.dp)) {
            block.items.forEach { item ->
                Row(modifier = Modifier.padding(vertical = 3.dp)) {
                    Box(
                        Modifier
                            .padding(top = 7.dp)
                            .size(5.dp)
                            .background(MeiliPalette.Clay, CircleShape),
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(
                        item,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 13.5f.sp, lineHeight = 21.sp,
                        ),
                        color = MeiliPalette.Ink2,
                    )
                }
            }
        }
        is PolicyBlock.Table -> PolicyTable(block.rows)
    }
}

/** 权限清单表:首行表头,横向可滚(手机窄屏不挤)。 */
@Composable
private fun PolicyTable(rows: List<List<String>>) {
    val cols = rows.maxOfOrNull { it.size } ?: 1
    val cellWidth = if (cols >= 3) 150.dp else 190.dp
    Box(
        modifier = Modifier
            .padding(vertical = 8.dp)
            .horizontalScroll(rememberScrollState()),
    ) {
        Column(
            modifier = Modifier
                .background(MeiliPalette.Surface, RoundedCornerShape(10.dp)),
        ) {
            rows.forEachIndexed { r, row ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    (0 until cols).forEach { c ->
                        Text(
                            row.getOrElse(c) { "" },
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                fontWeight = if (r == 0) FontWeight.Bold else FontWeight.Normal,
                            ),
                            color = if (r == 0) MeiliPalette.White else MeiliPalette.Ink2,
                            modifier = Modifier
                                .width(cellWidth)
                                .background(
                                    when {
                                        r == 0 -> MeiliPalette.Clay
                                        r % 2 == 0 -> MeiliPalette.SurfaceSoft
                                        else -> MeiliPalette.Surface
                                    },
                                )
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}
