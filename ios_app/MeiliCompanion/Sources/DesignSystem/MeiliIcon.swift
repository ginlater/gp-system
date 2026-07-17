import SwiftUI

/// 「暖玉柔光」定制线性图标集 —— `d` 串 1:1 取自 warm_2.html `<symbol id="ic-…">`。
/// android 端对应 `designsystem/MeiliIcons.kt`。
///
/// 统一规范:24 栅格、描边 1.75、圆端点/圆角连接、`fill:none stroke:currentColor`
/// (在 SwiftUI 中即随 `.foregroundStyle`)。红线:绝不用 emoji / 系统默认图标凑数。

/// 图标基元:SVG `d` 串 / 圆 / 圆角矩形。
enum IconPrim {
    case d(String)
    case circle(CGFloat, CGFloat, CGFloat)               // cx, cy, r
    case rrect(CGFloat, CGFloat, CGFloat, CGFloat, CGFloat) // x, y, w, h, rx
}

/// 一个图标 = 一组描边基元 +(可选)一组实心填充基元(如电池电量块)。
struct MeiliGlyph {
    let stroke: [IconPrim]
    var fill: [IconPrim] = []
}

/// 把一组基元在 24×24 栅格内拼成 Path,再等比缩放到 rect。
private struct MeiliIconShape: Shape {
    let prims: [IconPrim]
    func path(in rect: CGRect) -> Path {
        var p = Path()
        for prim in prims {
            switch prim {
            case .d(let s):
                p.addPath(parseSVGPath(s))
            case .circle(let cx, let cy, let r):
                p.addEllipse(in: CGRect(x: cx - r, y: cy - r, width: r * 2, height: r * 2))
            case .rrect(let x, let y, let w, let h, let rx):
                p.addRoundedRect(in: CGRect(x: x, y: y, width: w, height: h),
                                 cornerSize: CGSize(width: rx, height: rx))
            }
        }
        let s = min(rect.width, rect.height) / 24
        return p.applying(CGAffineTransform(scaleX: s, y: s))
    }
}

/// 渲染一个 [MeiliGlyph]。颜色随 `.foregroundStyle`(等价 CSS currentColor)。
struct MeiliIcon: View {
    let glyph: MeiliGlyph
    var size: CGFloat = MeiliMetric.icon
    var lineWidth: CGFloat = 1.75

    init(_ glyph: MeiliGlyph, size: CGFloat = MeiliMetric.icon, lineWidth: CGFloat = 1.75) {
        self.glyph = glyph
        self.size = size
        self.lineWidth = lineWidth
    }

    var body: some View {
        let lw = lineWidth * size / 24
        ZStack {
            MeiliIconShape(prims: glyph.stroke)
                .stroke(style: StrokeStyle(lineWidth: lw, lineCap: .round, lineJoin: .round))
            if !glyph.fill.isEmpty {
                MeiliIconShape(prims: glyph.fill).fill()
            }
        }
        .frame(width: size, height: size)
    }
}

/// 图标注册表。命名与 android `MeiliIcons` 对齐,便于逐屏 1:1 移植。
enum MeiliIcons {
    static let companion = MeiliGlyph(stroke: [
        .d("M9 13.5c-2.2 0-3.6-1.7-3.6-3.5C5.4 8.2 6.8 7 8.2 7c.9 0 1.6.4 2.1 1.1.5-.7 1.2-1.1 2.1-1.1 1.4 0 2.8 1.2 2.8 3 0 1.8-1.4 3.5-3.6 3.5"),
        .d("M9 13.3c0 2.4 1.1 4.2 3 5.2 1.9-1 3-2.8 3-5.2"),
        .circle(12, 11, 1.05),
    ])
    /// 工作台(2×2 宫格,多系统切换入口)。对齐 android MeiliIcons.Workspace。
    static let workspace = MeiliGlyph(stroke: [
        .rrect(4, 4, 7, 7, 2.2),
        .rrect(13, 4, 7, 7, 2.2),
        .rrect(4, 13, 7, 7, 2.2),
        .rrect(13, 13, 7, 7, 2.2),
    ])
    static let reception = MeiliGlyph(stroke: [
        .rrect(4, 5.5, 16, 14.5, 3.4),
        .d("M8 3.5v3.5M16 3.5v3.5M4 10h16"),
        .d("M12 18.2c-1.5-1-2.5-1.9-2.5-3.1 0-.8.6-1.4 1.3-1.4.5 0 .9.3 1.2.7.3-.4.7-.7 1.2-.7.7 0 1.3.6 1.3 1.4 0 1.2-1 2.1-2.5 3.1z"),
    ])
    static let reminder = MeiliGlyph(stroke: [
        .d("M6.5 16.5c-.5 0-.8-.6-.5-1 .8-1 1.3-2.1 1.3-3.4V10.3c0-2.6 2.1-4.8 4.7-4.8s4.7 2.2 4.7 4.8v1.8c0 1.3.5 2.4 1.3 3.4.3.4 0 1-.5 1z"),
        .d("M10.2 19a1.9 1.9 0 0 0 3.6 0"),
        .d("M12 5.5V3.8"),
    ])
    static let tidy = MeiliGlyph(stroke: [
        .d("M3.5 9.2 12 5l8.5 4.2-8.5 4.2z"),
        .d("M4 12.6 12 16.6l8-4"),
        .d("M4 16 12 20l8-4"),
    ])
    static let profile = MeiliGlyph(stroke: [
        .circle(12, 8.4, 3.7),
        .d("M5.5 19.2c.6-3.3 3.3-5.4 6.5-5.4s5.9 2.1 6.5 5.4"),
    ])
    static let pen = MeiliGlyph(stroke: [
        .d("M6 18 16 8M16 8l1.6-1.6a2.2 2.2 0 0 1 3.1 3.1L18 12.3M14.4 6.4 17.6 9.6"),
        .d("M6 18l-1.4 1.4M4.6 19.4 4 21l1.6-.6z"),
    ])
    static let sync = MeiliGlyph(stroke: [
        .d("M19 11a7 7 0 0 0-12.3-3.4M5 5.5V9h3.5"),
        .d("M5 13a7 7 0 0 0 12.3 3.4M19 18.5V15h-3.5"),
    ])
    static let upload = MeiliGlyph(stroke: [
        .d("M12 16V6.5M8.5 10 12 6.3 15.5 10"),
        .d("M5.5 18.5h13"),
    ])
    static let play = MeiliGlyph(stroke: [.d("M8.5 6.7 17 12l-8.5 5.3z")])
    static let search = MeiliGlyph(stroke: [
        .circle(10.8, 10.8, 6.2),
        .d("m15.5 15.5 3.8 3.8"),
    ])
    static let add = MeiliGlyph(stroke: [.d("M12 5.5v13M5.5 12h13")])
    static let back = MeiliGlyph(stroke: [.d("M14.5 5.5 8 12l6.5 6.5")])
    static let chevRight = MeiliGlyph(stroke: [.d("M9.5 5.5 16 12l-6.5 6.5")])
    static let chevDown = MeiliGlyph(stroke: [.d("M5.5 9.5 12 16l6.5-6.5")])
    static let chevLeft = MeiliGlyph(stroke: [.d("M14.5 5.5 8 12l6.5 6.5")])
    static let check = MeiliGlyph(stroke: [.d("M5 12.5 10 17.5 19 7")])
    static let link = MeiliGlyph(stroke: [
        .d("M10 13.5 14 9.5"),
        .d("M8.6 15a3 3 0 0 1 0-4.2l2-2a3 3 0 0 1 4.2 0"),
        .d("M15.4 9a3 3 0 0 1 0 4.2l-2 2a3 3 0 0 1-4.2 0"),
    ])
    static let unbind = MeiliGlyph(stroke: [
        .d("M8.6 15a3 3 0 0 1 0-4.2l1-1M15.4 9a3 3 0 0 1 0 4.2l-1 1M9 6.5V4.5M6.5 9H4.5M15 17.5v2M17.5 15h2"),
    ])
    static let more = MeiliGlyph(stroke: [.circle(5.5, 12, 1.2), .circle(12, 12, 1.2), .circle(18.5, 12, 1.2)])
    static let close = MeiliGlyph(stroke: [.d("M6.5 6.5 17.5 17.5M17.5 6.5 6.5 17.5")])
    static let settings = MeiliGlyph(stroke: [
        .circle(12, 12, 3.2),
        .d("M12 3.8V6.2M12 17.8V20.2M3.8 12H6.2M17.8 12H20.2M6.2 6.2 7.9 7.9M16.1 16.1 17.8 17.8M17.8 6.2 16.1 7.9M7.9 16.1 6.2 17.8"),
    ])
    static let refresh = MeiliGlyph(stroke: [
        .d("M19.5 11A7.5 7.5 0 0 0 6.5 6.5L4.5 8.5M4.5 5v3.5H8"),
        .d("M4.5 13A7.5 7.5 0 0 0 17.5 17.5L19.5 15.5M19.5 19v-3.5H16"),
    ])
    static let phone = MeiliGlyph(stroke: [.rrect(7, 3.5, 10, 17, 2.6), .d("M10.5 17.5h3")])
    static let warn = MeiliGlyph(stroke: [.d("M12 4.5 21 19.5H3z"), .d("M12 10v4"), .circle(12, 17, 0.4)])
    static let info = MeiliGlyph(stroke: [.circle(12, 12, 8), .d("M12 11v5"), .circle(12, 8, 0.5)])
    static let spark = MeiliGlyph(stroke: [
        .d("M12 4.5c.4 3.5 1.5 4.6 5 5-3.5.4-4.6 1.5-5 5-.4-3.5-1.5-4.6-5-5 3.5-.4 4.6-1.5 5-5z"),
        .d("M18.5 14c.2 1.5.6 1.9 2 2-1.4.2-1.8.6-2 2-.2-1.4-.6-1.9-2-2 1.4-.1 1.8-.5 2-2z"),
    ])
    static let heart = MeiliGlyph(stroke: [
        .d("M12 19c-3-2-7-4.8-7-8.6C5 8 6.8 6.5 8.8 6.5c1.3 0 2.4.6 3.2 1.7.8-1.1 1.9-1.7 3.2-1.7 2 0 3.8 1.5 3.8 3.9C19 14.2 15 17 12 19z"),
    ])
    static let star = MeiliGlyph(stroke: [.d("M12 4.5 14.3 9.3 19.5 10 15.7 13.8 16.7 19 12 16.4 7.3 19 8.3 13.8 4.5 10 9.7 9.3z")])
    static let trash = MeiliGlyph(stroke: [
        .d("M5.5 7h13M9.5 7V5.5a1.5 1.5 0 0 1 1.5-1.5h2a1.5 1.5 0 0 1 1.5 1.5V7M7 7l.8 11a2 2 0 0 0 2 1.9h4.4a2 2 0 0 0 2-1.9L17 7"),
    ])
    static let target = MeiliGlyph(stroke: [.circle(12, 12, 7.5), .circle(12, 12, 3.7)])
    static let route = MeiliGlyph(stroke: [
        .circle(6.5, 6.5, 2.2), .circle(17.5, 17.5, 2.2),
        .d("M8.7 6.5h6a3 3 0 0 1 0 6h-5.4a3 3 0 0 0 0 6h6"),
    ])
    static let trend = MeiliGlyph(stroke: [.d("M4.5 15.5 9 11l3 2.5 7-7M14.5 6.5H19v4.5")])
    static let doc = MeiliGlyph(stroke: [
        .d("M7 4.5h6.5L18 9v10a1.5 1.5 0 0 1-1.5 1.5h-9A1.5 1.5 0 0 1 6 19V6a1.5 1.5 0 0 1 1-1.5z"),
        .d("M13 4.5V9h4.5M9 13h6M9 16h4"),
    ])
    static let gem = MeiliGlyph(stroke: [
        .d("M7 4.5h10l3 4.5-8 10-8-10z"),
        .d("M4 9h16M9.5 4.5 8 9l4 9.5M14.5 4.5 16 9l-4 9.5"),
    ])
    static let headphone = MeiliGlyph(stroke: [
        .d("M5 13v-1a7 7 0 0 1 14 0v1"),
        .d("M5 13h1.5A1.5 1.5 0 0 1 8 14.5v2A1.5 1.5 0 0 1 6.5 18H6a1 1 0 0 1-1-1zM19 13h-1.5a1.5 1.5 0 0 0-1.5 1.5v2a1.5 1.5 0 0 0 1.5 1.5h.5a1 1 0 0 0 1-1z"),
    ])
    static let clock = MeiliGlyph(stroke: [.circle(12, 12, 7.5), .d("M12 8v4.2l2.8 1.8")])
    static let lock = MeiliGlyph(stroke: [.rrect(5.5, 10.5, 13, 9, 2.4), .d("M8.5 10.5V8a3.5 3.5 0 0 1 7 0v2.5")])
    static let wifi = MeiliGlyph(stroke: [
        .d("M4 9.5a12 12 0 0 1 16 0M6.5 13a8 8 0 0 1 11 0M9 16.3a4 4 0 0 1 6 0"),
        .circle(12, 19, 0.5),
    ])
    static let battery = MeiliGlyph(
        stroke: [.rrect(3, 8, 16, 8, 2), .d("M21 11v2")],
        fill: [.rrect(5, 10, 9, 4, 1)]
    )
    static let palette = MeiliGlyph(stroke: [
        .d("M12 4.5a7.5 7.5 0 0 0 0 15c1.2 0 1.8-.9 1.8-1.8 0-1.2-1-1.5-1-2.5 0-.7.6-1.2 1.4-1.2H17a3 3 0 0 0 3-3c0-3.6-3.6-6.5-8-6.5z"),
        .circle(8.5, 11, 0.6), .circle(12, 8.5, 0.6), .circle(15.5, 11, 0.6),
    ])
    static let album = MeiliGlyph(stroke: [
        .rrect(4, 4.5, 16, 15, 3),
        .d("M4 14.5l4-3.5 4 3 3-2.5 5 4.5"),
        .circle(9, 9, 1.3),
    ])
    static let comment = MeiliGlyph(stroke: [
        .d("M5 6.5A1.5 1.5 0 0 1 6.5 5h11A1.5 1.5 0 0 1 19 6.5v8a1.5 1.5 0 0 1-1.5 1.5H10l-4 3.5V16H6.5A1.5 1.5 0 0 1 5 14.5z"),
    ])
    static let waveform = MeiliGlyph(stroke: [.d("M4 11v2M7.5 8v8M11 5.5v13M14.5 8.5v7M18 10v4M21 11v2")])
    static let tasks = MeiliGlyph(stroke: [
        .rrect(4, 4.5, 6, 6, 1.6),
        .d("M5.5 7.4 6.7 8.6 8.6 6.3"),
        .rrect(4, 13.5, 6, 6, 1.6),
        .d("M13.5 6.5h6.5M13.5 15.5h6.5"),
    ])
}
