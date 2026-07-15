package com.airec.bledemo.data.model

import com.squareup.moshi.Json

/* ===================================================================
 * 回访/高情商话术表单规格(多系统整合 P1.3)。
 *
 * 网页版(followup-agent index.html)是几百字段的级联动态表单,原生 1:1 复刻的做法:
 * 把表单结构+显隐规则离线提取成 JSON 规格(assets/followup_form_spec.json,
 * 2026-07-15 从线上 index.html 提取),App 用规格驱动的通用渲染器渲染。
 * 服务端表单大改时:重新提取规格 → 换 assets 文件 → 发版。
 * =================================================================== */

/** 整份规格:顶层公共字段 + 四个分支。 */
data class FormSpecFile(
    @Json(name = "common") val common: List<FormField>? = null,
    @Json(name = "branches") val branches: List<FormBranch>? = null,
)

/** 一个分支(本次消息性质)。prefix = 网页字段 id 前缀(pc/inv/sp/nc)。 */
data class FormBranch(
    @Json(name = "nature") val nature: String? = null,
    @Json(name = "prefix") val prefix: String? = null,
    @Json(name = "fields") val fields: List<FormField>? = null,
)

/**
 * 单个表单字段。
 * kind: options(选项组)| text(单行)| textarea(多行)| select(下拉,渲染同 options 单选)。
 * 同一 data-group 拆多块时 id 相同、[optionsSection] 标小节名,收集数据按 id 合并。
 */
data class FormField(
    @Json(name = "kind") val kind: String? = null,
    @Json(name = "id") val id: String? = null,
    @Json(name = "label") val label: String? = null,
    @Json(name = "required") val required: Boolean? = null,
    @Json(name = "multi") val multi: Boolean? = null,
    @Json(name = "options") val options: List<String>? = null,
    @Json(name = "placeholder") val placeholder: String? = null,
    @Json(name = "sublabelNote") val sublabelNote: String? = null,
    @Json(name = "optionsSection") val optionsSection: String? = null,
    @Json(name = "customInput") val customInput: FormCustomInput? = null,
    @Json(name = "visibleWhen") val visibleWhen: FormCondition? = null,
    @Json(name = "parentVisibleWhen") val parentVisibleWhen: FormCondition? = null,
    // 选项由服务端 GET /project-options 按公司动态下发的类目 key(如 body_services);
    // 渲染时用动态选项替换 options,只保留「自定义/都不是」这类静态兜底项
    @Json(name = "dynamicCat") val dynamicCat: String? = null,
    // 该字段在页面上位于分支面板之后(noteDetail),渲染顺序照此
    @Json(name = "renderAfterBranches") val renderAfterBranches: Boolean? = null,
) {
    val isOptions: Boolean get() = kind == "options" || kind == "select"
    val isText: Boolean get() = kind == "text"
    val isTextarea: Boolean get() = kind == "textarea"
}

/** 选中特定选项才出现的自定义文本框(如渠道选「自定义」)。 */
data class FormCustomInput(
    @Json(name = "id") val id: String? = null,
    @Json(name = "placeholder") val placeholder: String? = null,
    @Json(name = "showWhen") val showWhen: List<String>? = null,
)

/** 显隐条件:某组的当前选中值与 anyOf 有交集才显示。 */
data class FormCondition(
    @Json(name = "group") val group: String? = null,
    @Json(name = "anyOf") val anyOf: List<String>? = null,
) {
    /** @param selections 组 id → 已选选项 */
    fun matches(selections: Map<String, List<String>>): Boolean {
        val g = group ?: return true
        val need = anyOf ?: return true
        val sel = selections[g] ?: return false
        return sel.any { it in need }
    }
}
