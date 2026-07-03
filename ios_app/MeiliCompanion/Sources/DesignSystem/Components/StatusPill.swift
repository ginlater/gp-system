import SwiftUI

/// 状态胶囊。android 端对应 `components/StatusPill.kt`,色值取自 warm_2.html `.pill.*`。
///  - `.ok`      叶绿:已分析 / 已完成 / 已成交
///  - `.warn`    蜜色:待分析 / 待开始 / 审批中
///  - `.danger`  玫瑰:失败 / 跨日 / 高风险
///  - `.neutral` surface-soft + 描边:未绑定 / 已锁定
///  - `.clay`    陶土 tint:陪伴笔 / 同步中 / 留意
///  - `.run`     鼠尾草:分析中… 5/11
enum PillKind { case ok, warn, danger, neutral, clay, run }

struct StatusPill: View {
    let text: String
    let kind: PillKind
    var icon: MeiliGlyph? = nil

    var body: some View {
        HStack(spacing: 5) {
            if let icon { MeiliIcon(icon, size: 13) }
            Text(text).font(.sz(11.5, weight: .bold))
        }
        .padding(.horizontal, 11)
        .padding(.vertical, 5)
        .foregroundStyle(fg)
        .background(bg)
        .clipShape(Capsule())
        .overlay {
            if kind == .neutral {
                Capsule().strokeBorder(MeiliColor.line, lineWidth: MeiliMetric.borderThin)
            }
        }
    }

    private var fg: Color {
        switch kind {
        case .ok: return MeiliColor.leafText
        case .warn: return MeiliColor.honeyText
        case .danger: return MeiliColor.roseText
        case .neutral: return MeiliColor.ink2
        case .clay: return MeiliColor.clayDeep
        case .run: return MeiliColor.sageDeep
        }
    }
    private var bg: Color {
        switch kind {
        case .ok: return MeiliColor.leafSoft
        case .warn: return MeiliColor.honeySoft
        case .danger: return MeiliColor.roseSoft
        case .neutral: return MeiliColor.surfaceSoft
        case .clay: return MeiliColor.clayTint
        case .run: return MeiliColor.sageTint
        }
    }
}
