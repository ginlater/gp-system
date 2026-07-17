import Foundation

/// 销售话术表单规格 —— 1:1 提取自 sale-agent beauty_consultant.html(2026-07-16 上服务器核对)。
/// 表单不走服务端下发(页面写死),字段改版时同步改这里 + 发版。
/// 拼 Prompt 的格式必须与网页 buildFormDescription 逐行一致(服务端按此喂 Skill 管道)。
enum ChatFormSpec {
    static let ageOptions = ["18-25岁", "25-30岁", "30-35岁", "35-42岁", "42-50岁", "50岁以上"]
    static let visitOptions = ["首次到店", "久未回访", "稳定回头客", "朋友介绍"]
    static let projectOptions = ["面部护理", "身体SPA", "美体塑形", "养生调理", "综合护理"]
    static let concernOptions = ["暗沉无光", "色斑", "法令纹", "松弛下垂", "干燥缺水", "毛孔粗大", "肤色不均"]
    static let bodyConcernOptions = [
        "肩颈僵硬", "肩颈酸痛", "腰背酸痛", "肌肉酸痛 / 乳酸堆积", "小腿酸胀", "腿部疲劳 / 沉重",
        "关节不适", "手脚冰凉", "身体水肿", "含胸 / 驼背", "头部紧张 / 头痛", "睡眠质量差", "长期疲惫 / 精力差",
    ]
    static let shapeOptions = [
        "腰腹赘肉", "腹部松弛 / 产后腹型", "大腿粗壮", "小腿 / 腿型", "手臂赘肉", "背部 / 虎背",
        "全身减脂塑形", "体重偏高", "产后修复", "腹直肌分离", "假胯宽 / 体态", "臀型 / 提臀",
    ]
    static let wellnessOptions = [
        "手脚冰凉 / 怕冷", "经期不适 / 痛经", "气色暗黄 / 气血不足", "睡眠差 / 易醒", "易疲劳 / 亚健康",
        "湿气重 / 身体沉重", "脾胃 / 消化偏弱", "腰肾酸软", "肩颈劳损（理疗）", "头部紧张 / 头痛", "情绪压力大",
    ]
    static let goalOptions = ["体验首次护理", "升级抗衰项目", "建立长期护理计划", "唤回复购"]
    static let styleOptions = ["从状态切入", "从感受切入", "从观察切入"]

    /// 开场风格 → 给模型的展开提示(与网页 styleHint 一致)。
    static let styleHint: [String: String] = [
        "从状态切入": "开场从顾客今天的状态切入，先让她感觉被看见，再自然过渡到本次项目。",
        "从感受切入": "开场直接温柔问她今天最想改善什么，用\"您说说看\"引导她主动开口。",
        "从观察切入": "开场先静静观察几秒，说出一句她心里有但说不出来的话，让她第一句就说\"对对对\"。",
    ]

    /// 项目 → 显示哪张困扰卡(选「综合护理」= 全显)。
    static func showsCard(_ card: String, projects: [String]) -> Bool {
        projects.contains(card) || projects.contains("综合护理")
    }

    /// 与网页 buildFormDescription 逐行同构:把表单拼成自然语言 prompt。
    struct FormData {
        var name = ""
        var age = ""
        var visitType = ""
        var projects: [String] = []
        var concerns: [String] = []
        var highlights = ""
        var bodyConcerns: [String] = []
        var bodyObserve = ""
        var shapeNeeds: [String] = []
        var shapeObserve = ""
        var wellNeeds: [String] = []
        var wellObserve = ""
        var lifestyle = ""
        var goal = ""
        var style = ""
        var special = ""

        /// 至少一项困扰/诉求(与网页 anyConcern 校验一致)。
        var anyConcern: Bool {
            !(concerns.isEmpty && bodyConcerns.isEmpty && shapeNeeds.isEmpty && wellNeeds.isEmpty)
        }

        /// 必填缺项清单(与网页一致;空 = 可生成)。
        var missing: [String] {
            var m: [String] = []
            if name.isEmpty { m.append("顾客称呼") }
            if age.isEmpty { m.append("年龄段") }
            if visitType.isEmpty { m.append("顾客类型") }
            if projects.isEmpty { m.append("本次到店项目") }
            if !anyConcern { m.append("至少一项困扰/诉求") }
            if style.isEmpty { m.append("开场风格") }
            return m
        }

        func buildPrompt() -> String {
            var lines: [String] = []
            if !projects.isEmpty { lines.append("本次到店项目：\(projects.joined(separator: "、"))。") }
            lines.append("顾客称呼：\(name)，\(age)，\(visitType)。")
            if !concerns.isEmpty { lines.append("面部主要困扰：\(concerns.joined(separator: "、"))。") }
            if !highlights.isEmpty { lines.append("美容师观察到的亮点：\(highlights)。") }
            if !bodyConcerns.isEmpty { lines.append("身体主要困扰：\(bodyConcerns.joined(separator: "、"))。") }
            if !bodyObserve.isEmpty { lines.append("美容师触诊/身体观察：\(bodyObserve)。") }
            if !shapeNeeds.isEmpty { lines.append("美体塑形诉求：\(shapeNeeds.joined(separator: "、"))。") }
            if !shapeObserve.isEmpty { lines.append("美容师体型测量/观察：\(shapeObserve)。") }
            if !wellNeeds.isEmpty { lines.append("养生调理诉求：\(wellNeeds.joined(separator: "、"))。") }
            if !wellObserve.isEmpty { lines.append("美容师养生观察：\(wellObserve)。") }
            if !lifestyle.isEmpty { lines.append("生活状态：\(lifestyle)。") }
            if !goal.isEmpty { lines.append("本次引导目标：\(goal)。") }
            lines.append("开场风格：\(ChatFormSpec.styleHint[style] ?? style)")
            if !special.isEmpty { lines.append("特殊情况：\(special)") }
            return lines.joined(separator: "\n")
        }
    }

    /// 把完整输出拆成「顾问思考」和「话术」(与网页 parseThinkingScript 同构)。
    /// 思考模型会双层包裹:统一取「最后一个【话术】」之后为话术,
    /// 取它前面最近的【思考】~【话术】之间为顾问思考。
    static func parseThinkingScript(_ text: String) -> (thinking: String, script: String?) {
        let tm = "【思考】", sm = "【话术】"
        guard !text.isEmpty else { return ("", nil) }
        guard let si = text.range(of: sm, options: .backwards) else {
            if let ti = text.range(of: tm, options: .backwards) {
                return (String(text[ti.upperBound...]).trimmingCharacters(in: .whitespacesAndNewlines), nil)
            }
            return ("", nil)
        }
        let script = String(text[si.upperBound...]).trimmingCharacters(in: .whitespacesAndNewlines)
        let head = text[..<si.lowerBound]
        if let ti = head.range(of: tm, options: .backwards) {
            let thinking = String(text[ti.upperBound..<si.lowerBound]).trimmingCharacters(in: .whitespacesAndNewlines)
            return (thinking, script)
        }
        return ("", script)
    }
}
