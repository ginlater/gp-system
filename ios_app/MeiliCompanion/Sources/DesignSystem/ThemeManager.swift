import SwiftUI

/// 主题皮肤管理(设置 · 主题皮肤)。android 端对应 `designsystem/ThemeManager.kt`。
/// 6 套皮肤:jade/honey/rose/sage 只换陶土主色家族;noir(黑金暗)/simple(灰) 换整套。
/// MeiliColor 的可换属性读 `ThemeManager.shared.current`;RootView 观察本对象 → 换肤全 app 重渲染。
final class ThemeManager: ObservableObject {
    static let shared = ThemeManager()

    struct Skin: Identifiable, Equatable {
        let id: String
        let name: String
        let desc: String
        let dark: Bool
        // 陶土主色家族
        let clay, clayDeep, claySoft, clayTint: Color
        // 底/卡
        let bg, bg2, surface, surfaceSoft, surfaceFrost: Color
        // 文字
        let ink, ink2, ink3, ink4: Color
        // 线
        let line, lineSoft: Color
        // 状态柔底/文字
        let honeySoft, honeyText, roseSoft, roseText, roseLine: Color
        let leafSoft, leafText, leafLine, sageSoft, sageTint, inkSurface: Color

        static func == (a: Skin, b: Skin) -> Bool { a.id == b.id }
    }

    @Published private(set) var fontScale: CGFloat = 1.0   // 全局字体缩放(设置·字体大小)
    @Published private(set) var currentId: String = "jade"
    @Published private(set) var autoMode = false
    @Published private(set) var daySkinId = "jade"
    @Published private(set) var nightSkinId = "noir"
    private var manualSkinId = "jade"

    private let defaults = UserDefaults.standard

    var current: Skin { Self.skins.first { $0.id == currentId } ?? Self.skins[0] }
    func skin(_ id: String) -> Skin { Self.skins.first { $0.id == id } ?? Self.skins[0] }

    private init() {
        manualSkinId = valid(defaults.string(forKey: "theme_skin")) ?? "jade"
        daySkinId = valid(defaults.string(forKey: "theme_day")) ?? "jade"
        nightSkinId = valid(defaults.string(forKey: "theme_night")) ?? "noir"
        autoMode = defaults.object(forKey: "theme_auto") != nil
            ? defaults.bool(forKey: "theme_auto")
            : (defaults.string(forKey: "theme_skin") == nil)   // 新装默认自动日夜
        if defaults.object(forKey: "font_scale") != nil {
            fontScale = max(0.85, min(1.4, CGFloat(defaults.double(forKey: "font_scale"))))
        }
        recompute()
    }

    /// 设置·字体大小:整体缩放(0.85~1.4)。@Published → RootView 重渲染 → 全 app 字体随之变。
    func setFontScale(_ s: CGFloat) {
        fontScale = max(0.85, min(1.4, s))
        defaults.set(Double(fontScale), forKey: "font_scale")
    }

    private func valid(_ id: String?) -> String? { id.flatMap { i in Self.skins.contains { $0.id == i } ? i : nil } }

    static func isNightNow() -> Bool {
        let h = Calendar.current.component(.hour, from: Date())
        return h < 6 || h >= 18
    }

    private func recompute() {
        currentId = autoMode ? (Self.isNightNow() ? nightSkinId : daySkinId) : manualSkinId
    }

    func apply(_ id: String) {
        guard valid(id) != nil else { return }
        manualSkinId = id; autoMode = false
        defaults.set(id, forKey: "theme_skin"); defaults.set(false, forKey: "theme_auto")
        recompute()
    }
    func setAuto(_ on: Bool) { autoMode = on; defaults.set(on, forKey: "theme_auto"); recompute() }
    func setDaySkin(_ id: String) { guard valid(id) != nil else { return }; daySkinId = id; defaults.set(id, forKey: "theme_day"); recompute() }
    func setNightSkin(_ id: String) { guard valid(id) != nil else { return }; nightSkinId = id; defaults.set(id, forKey: "theme_night"); recompute() }
    func tick() { if autoMode { recompute() } }

    // ── 6 套皮肤 ──
    static let skins: [Skin] = {
        func c(_ h: UInt, _ a: Double = 1) -> Color { Color(hex: h, alpha: a) }
        // 浅色默认(jade/honey/rose/sage 共用,仅陶土家族各异)
        func light(_ id: String, _ name: String, _ desc: String, _ clay: UInt, _ deep: UInt, _ soft: UInt, _ tint: UInt) -> Skin {
            Skin(id: id, name: name, desc: desc, dark: false,
                 clay: c(clay), clayDeep: c(deep), claySoft: c(soft), clayTint: c(tint),
                 bg: c(0xF8F3ED), bg2: c(0xF2EBE2), surface: c(0xFFFCF8), surfaceSoft: c(0xFAF4ED), surfaceFrost: c(0xFFFCF8, 0.74),
                 ink: c(0x3D3833), ink2: c(0x736A60), ink3: c(0xA89F94), ink4: c(0xC8BFB4),
                 line: c(0xEBE2D7), lineSoft: c(0xF2EBE1),
                 honeySoft: c(0xF0E2C8), honeyText: c(0x8C6322), roseSoft: c(0xF2DED7), roseText: c(0x9B4B3A), roseLine: c(0xE9C4BA),
                 leafSoft: c(0xDFEADD), leafText: c(0x3D7150), leafLine: c(0xC7DEC9), sageSoft: c(0xE3E9DE), sageTint: c(0xF1F4ED), inkSurface: c(0x3D3833))
        }
        return [
            light("jade", "暖玉柔光", "陶土 + 鼠尾草（默认）", 0xBE7459, 0xA35E45, 0xEFDED4, 0xF8ECE4),
            light("honey", "蜜糖暖金", "暖金色调", 0xC2954E, 0x9E7634, 0xECDDBE, 0xF7EFDD),
            light("rose", "胭脂玫瑰", "柔粉色调", 0xC06B79, 0x9E4F5D, 0xEBD2D7, 0xF7E8EB),
            light("sage", "青玉鼠尾", "沉静绿调", 0x7F9A82, 0x5F7A63, 0xD9E4DA, 0xECF2EC),
            Skin(id: "noir", name: "曜夜鎏金", desc: "黑金 · 尊贵豪华", dark: true,
                 clay: c(0xD4AF6A), clayDeep: c(0xB8924A), claySoft: c(0x3A3020), clayTint: c(0x2A2418),
                 bg: c(0x1A1613), bg2: c(0x141009), surface: c(0x26201A), surfaceSoft: c(0x221C16), surfaceFrost: c(0x26201A, 0.88),
                 ink: c(0xF1E7D2), ink2: c(0xC8B89A), ink3: c(0x93876C), ink4: c(0x655B49),
                 line: c(0x3A332A), lineSoft: c(0x2C2620),
                 honeySoft: c(0x3A2E18), honeyText: c(0xE3BE72), roseSoft: c(0x3A211A), roseText: c(0xE0917A), roseLine: c(0x5A3328),
                 leafSoft: c(0x1E2E20), leafText: c(0x86C79B), leafLine: c(0x2F4A36), sageSoft: c(0x26302A), sageTint: c(0x20281F), inkSurface: c(0x0F0C09)),
            Skin(id: "simple", name: "简约大气", desc: "高级灰 · 克制", dark: false,
                 clay: c(0x54585F), clayDeep: c(0x383B41), claySoft: c(0xE7E8EA), clayTint: c(0xF2F3F5),
                 bg: c(0xF6F6F4), bg2: c(0xEFEFEC), surface: c(0xFFFFFF), surfaceSoft: c(0xF4F4F2), surfaceFrost: c(0xFFFFFF, 0.74),
                 ink: c(0x2A2B2E), ink2: c(0x64666A), ink3: c(0x9A9CA1), ink4: c(0xC4C6CB),
                 line: c(0xE7E7E4), lineSoft: c(0xF0F0EE),
                 honeySoft: c(0xF0E2C8), honeyText: c(0x8C6322), roseSoft: c(0xF2DED7), roseText: c(0x9B4B3A), roseLine: c(0xE9C4BA),
                 leafSoft: c(0xDFEADD), leafText: c(0x3D7150), leafLine: c(0xC7DEC9), sageSoft: c(0xE3E9DE), sageTint: c(0xF1F4ED), inkSurface: c(0x3D3833)),
        ]
    }()
}
