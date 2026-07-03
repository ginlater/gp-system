import SwiftUI
import UIKit

/// 「暖玉柔光 Warm Jade Glow」全套色板。
///
/// 1:1 翻译自 `android_app_v2/mockups/warm_2.html` 的 `:root` CSS 自定义属性,HEX 全部照搬。
/// android 端对应 `designsystem/Color.kt`。v1 实现静态浅色皮肤(android 的多皮肤换肤为后续待办)。
extension Color {
    /// 0xRRGGBB 十六进制构造(sRGB)。
    init(hex: UInt, alpha: Double = 1) {
        let r = Double((hex >> 16) & 0xFF) / 255
        let g = Double((hex >> 8) & 0xFF) / 255
        let b = Double(hex & 0xFF) / 255
        self.init(.sRGB, red: r, green: g, blue: b, opacity: alpha)
    }

    /// 朝白插值(陶土高光派生:clayLight/glow/bright)。
    func mixWhite(_ amount: CGFloat) -> Color {
        let ui = UIColor(self)
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        ui.getRed(&r, green: &g, blue: &b, alpha: &a)
        return Color(.sRGB, red: Double(r + (1 - r) * amount), green: Double(g + (1 - g) * amount),
                     blue: Double(b + (1 - b) * amount), opacity: Double(a))
    }
}

enum MeiliColor {
    // 可换皮肤属性读当前皮肤(ThemeManager);固定辅色(sage/honey/rose/leaf 主色)保持不变。
    private static var s: ThemeManager.Skin { ThemeManager.shared.current }

    // ---- ground 底色(随皮肤) ----
    static var bg: Color { s.bg }
    static var bg2: Color { s.bg2 }
    static var surface: Color { s.surface }
    static var surfaceSoft: Color { s.surfaceSoft }
    static var surfaceFrost: Color { s.surfaceFrost }

    // ---- terracotta 陶土主色(随皮肤) ----
    static var clay: Color { s.clay }
    static var clayDeep: Color { s.clayDeep }
    static var claySoft: Color { s.claySoft }
    static var clayTint: Color { s.clayTint }
    static var clayLight: Color { s.clay.mixWhite(0.13) }   // 主按钮渐变起点
    static var clayGlow: Color { s.clay.mixWhite(0.32) }    // 圆钮空闲态高光
    static var clayBright: Color { s.clay.mixWhite(0.50) }  // 圆钮呼吸态核心高光

    // ---- sage 鼠尾草(辅色,固定) ----
    static let sage = Color(hex: 0x93A38E)
    static let sageDeep = Color(hex: 0x74866F)
    static var sageSoft: Color { s.sageSoft }
    static var sageTint: Color { s.sageTint }
    static let sageLight = Color(hex: 0x9DAE97)

    // ---- ink 文字(随皮肤) ----
    static var ink: Color { s.ink }
    static var ink2: Color { s.ink2 }
    static var ink3: Color { s.ink3 }
    static var ink4: Color { s.ink4 }

    // ---- lines(随皮肤) ----
    static var line: Color { s.line }
    static var lineSoft: Color { s.lineSoft }

    // ---- status：蜜色 honey / 玫瑰 rose / 叶绿 leaf(主色固定,柔底/文字随皮肤) ----
    static let honey = Color(hex: 0xC99A5B)
    static var honeySoft: Color { s.honeySoft }
    static var honeyText: Color { s.honeyText }
    static let honeyLight = Color(hex: 0xD6AC6E)
    static let honeyDeep = Color(hex: 0xB98A45)

    static let rose = Color(hex: 0xC57D6B)
    static var roseSoft: Color { s.roseSoft }
    static var roseText: Color { s.roseText }
    static let roseDeep = Color(hex: 0xA8503C)
    static var roseLine: Color { s.roseLine }

    static let leaf = Color(hex: 0x7FA083)
    static var leafSoft: Color { s.leafSoft }
    static var leafText: Color { s.leafText }
    static var leafLine: Color { s.leafLine }

    // ---- 深色 toast / 标签底(随皮肤) ----
    static var inkSurface: Color { s.inkSurface }
    static let white = Color.white

    // ====== 渐变(还原 CSS linear/radial-gradient) ======

    /// btn-primary: linear-gradient(135deg, #CC846C, clay-deep)
    static var primaryGradient: LinearGradient {
        LinearGradient(colors: [clayLight, clayDeep], startPoint: .topLeading, endPoint: .bottomTrailing)
    }
    /// btn-sage: linear-gradient(135deg, #9DAE97, sage-deep)
    static var sageGradient: LinearGradient {
        LinearGradient(colors: [sageLight, sageDeep], startPoint: .topLeading, endPoint: .bottomTrailing)
    }
    /// btn-honey: linear-gradient(135deg, #D6AC6E, #B98A45)
    static var honeyGradient: LinearGradient {
        LinearGradient(colors: [honeyLight, honeyDeep], startPoint: .topLeading, endPoint: .bottomTrailing)
    }
    /// 进度条 fill: linear-gradient(90deg, clay, honey)
    static var trackGradient: LinearGradient {
        LinearGradient(colors: [clay, honey], startPoint: .leading, endPoint: .trailing)
    }
    /// 头像默认底: linear-gradient(135deg, clay-soft, sage-soft)
    static var avatarGradient: LinearGradient {
        LinearGradient(colors: [claySoft, sageSoft], startPoint: .topLeading, endPoint: .bottomTrailing)
    }

    /// 陪伴圆钮(空闲态): radial circle at 38% 30%, #E6A88F → clay @56% → clay-deep。
    static func companionGradient(diameter: CGFloat) -> RadialGradient {
        RadialGradient(
            gradient: Gradient(stops: [
                .init(color: clayGlow, location: 0),
                .init(color: clay, location: 0.56),
                .init(color: clayDeep, location: 1),
            ]),
            center: UnitPoint(x: 0.38, y: 0.30),
            startRadius: 0,
            endRadius: diameter * 0.80
        )
    }
    /// 陪伴圆钮(进行中/呼吸态): radial at 38% 30%, #DD9883 → rose @56% → #A8503C。
    static func companionLiveGradient(diameter: CGFloat) -> RadialGradient {
        RadialGradient(
            gradient: Gradient(stops: [
                .init(color: clayBright, location: 0),
                .init(color: rose, location: 0.56),
                .init(color: roseDeep, location: 1),
            ]),
            center: UnitPoint(x: 0.38, y: 0.30),
            startRadius: 0,
            endRadius: diameter * 0.80
        )
    }
}
