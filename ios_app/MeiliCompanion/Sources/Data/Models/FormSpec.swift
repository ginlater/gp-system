import Foundation

/* ===================================================================
 * 回访/高情商话术表单规格(多系统整合 P1.3)。
 * android 端对应 `data/model/FormSpec.kt`,规格 JSON 与安卓共用同一份
 * (followup_form_spec.json,2026-07-15 从线上 index.html 离线提取)。
 *
 * 网页版是几百字段的级联动态表单,原生 1:1 复刻的做法:
 * 把表单结构+显隐规则提取成 JSON 规格,App 用规格驱动的通用渲染器渲染。
 * 服务端表单大改时:重新提取规格 → 换 bundle 文件 → 发版。
 * ⚠️ 规格 key 本就是 camelCase,用 plainDecoder 解析。
 * =================================================================== */

/// 整份规格:顶层公共字段 + 四个分支。
struct FormSpecFile: Decodable {
    var common: [FormField]?
    var branches: [FormBranch]?
}

/// 一个分支(本次消息性质)。prefix = 网页字段 id 前缀(pc/inv/sp/nc)。
struct FormBranch: Decodable {
    var nature: String?
    var prefix: String?
    var fields: [FormField]?
}

/// 单个表单字段。
/// kind: options(选项组)| text(单行)| textarea(多行)| select(下拉,渲染同 options 单选)。
/// 同一 data-group 拆多块时 id 相同、optionsSection 标小节名,收集数据按 id 合并。
struct FormField: Decodable {
    var kind: String?
    var id: String?
    var label: String?
    var required: Bool?
    var multi: Bool?
    var options: [String]?
    var placeholder: String?
    var sublabelNote: String?
    var optionsSection: String?
    var customInput: FormCustomInput?
    var visibleWhen: FormCondition?
    var parentVisibleWhen: FormCondition?
    // 选项由服务端 GET /project-options 按公司动态下发的类目 key(如 body_services);
    // 渲染时用动态选项替换 options,只保留「自定义/都不是」这类静态兜底项
    var dynamicCat: String?
    // 该字段在页面上位于分支面板之后(noteDetail),渲染顺序照此
    var renderAfterBranches: Bool?

    var isOptions: Bool { kind == "options" || kind == "select" }
    var isText: Bool { kind == "text" }
    var isTextarea: Bool { kind == "textarea" }
}

/// 选中特定选项才出现的自定义文本框(如渠道选「自定义」)。
struct FormCustomInput: Decodable {
    var id: String?
    var placeholder: String?
    var showWhen: [String]?
}

/// 显隐条件:某组的当前选中值与 anyOf 有交集才显示。
struct FormCondition: Decodable {
    var group: String?
    var anyOf: [String]?

    /// - Parameter selections: 组 id → 已选选项
    func matches(_ selections: [String: [String]]) -> Bool {
        guard let g = group else { return true }
        guard let need = anyOf else { return true }
        guard let sel = selections[g] else { return false }
        return sel.contains { need.contains($0) }
    }
}
