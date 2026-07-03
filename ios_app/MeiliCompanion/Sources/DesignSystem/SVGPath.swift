import SwiftUI

/// 极简 SVG `path` 的 `d` 解析器 → SwiftUI `Path`。
///
/// 支持子集:M m L l H h V v C c S s Q q T t A a Z z(覆盖暖玉柔光图标集所需)。
/// 圆弧 A/a 用标准「端点参数 → 中心参数 → 分段三次贝塞尔」算法转换。
/// 让我们能直接粘贴 warm_2.html `<symbol>` 里的 `d` 字符串,与设计稿同源,而非手工重画。
func parseSVGPath(_ d: String) -> Path {
    var path = Path()
    var sc = SVGScanner(d)
    var current = CGPoint.zero
    var start = CGPoint.zero
    var lastCmd: Character = " "
    var lastCtrl: CGPoint? = nil

    while let cmd = sc.nextCommand(prev: lastCmd) {
        switch cmd {
        case "M", "m":
            let rel = cmd == "m"
            var p = sc.point(); if rel { p = current + p }
            path.move(to: p); current = p; start = p; lastCtrl = nil
            while sc.hasMoreNumbers() {
                var q = sc.point(); if rel { q = current + q }
                path.addLine(to: q); current = q
            }
        case "L", "l":
            let rel = cmd == "l"
            while sc.hasMoreNumbers() {
                var q = sc.point(); if rel { q = current + q }
                path.addLine(to: q); current = q
            }
            lastCtrl = nil
        case "H", "h":
            let rel = cmd == "h"
            while sc.hasMoreNumbers() {
                let x = sc.number()
                current = CGPoint(x: rel ? current.x + x : x, y: current.y)
                path.addLine(to: current)
            }
            lastCtrl = nil
        case "V", "v":
            let rel = cmd == "v"
            while sc.hasMoreNumbers() {
                let y = sc.number()
                current = CGPoint(x: current.x, y: rel ? current.y + y : y)
                path.addLine(to: current)
            }
            lastCtrl = nil
        case "C", "c":
            let rel = cmd == "c"
            while sc.hasMoreNumbers() {
                var c1 = sc.point(); var c2 = sc.point(); var e = sc.point()
                if rel { c1 = current + c1; c2 = current + c2; e = current + e }
                path.addCurve(to: e, control1: c1, control2: c2)
                lastCtrl = c2; current = e
            }
        case "S", "s":
            let rel = cmd == "s"
            while sc.hasMoreNumbers() {
                var c2 = sc.point(); var e = sc.point()
                if rel { c2 = current + c2; e = current + e }
                let reflect = "CcSs".contains(lastCmd)
                let c1 = reflect && lastCtrl != nil
                    ? CGPoint(x: 2 * current.x - lastCtrl!.x, y: 2 * current.y - lastCtrl!.y)
                    : current
                path.addCurve(to: e, control1: c1, control2: c2)
                lastCtrl = c2; current = e; lastCmd = cmd
            }
        case "Q", "q":
            let rel = cmd == "q"
            while sc.hasMoreNumbers() {
                var c = sc.point(); var e = sc.point()
                if rel { c = current + c; e = current + e }
                path.addQuadCurve(to: e, control: c)
                lastCtrl = c; current = e
            }
        case "T", "t":
            let rel = cmd == "t"
            while sc.hasMoreNumbers() {
                var e = sc.point(); if rel { e = current + e }
                let reflect = "QqTt".contains(lastCmd)
                let c = reflect && lastCtrl != nil
                    ? CGPoint(x: 2 * current.x - lastCtrl!.x, y: 2 * current.y - lastCtrl!.y)
                    : current
                path.addQuadCurve(to: e, control: c)
                lastCtrl = c; current = e; lastCmd = cmd
            }
        case "A", "a":
            let rel = cmd == "a"
            while sc.hasMoreNumbers() {
                let rx = sc.number(); let ry = sc.number(); let rot = sc.number()
                let large = sc.flag(); let sweep = sc.flag()
                var e = sc.point(); if rel { e = current + e }
                appendArc(&path, from: current, to: e, rx: rx, ry: ry, xRotDeg: rot, largeArc: large, sweep: sweep)
                current = e
            }
            lastCtrl = nil
        case "Z", "z":
            path.closeSubpath(); current = start; lastCtrl = nil
        default:
            break
        }
        lastCmd = cmd
    }
    return path
}

private func appendArc(_ path: inout Path, from p0: CGPoint, to p1: CGPoint,
                       rx rxIn: CGFloat, ry ryIn: CGFloat, xRotDeg: CGFloat,
                       largeArc: Bool, sweep: Bool) {
    var rx = abs(rxIn), ry = abs(ryIn)
    if rx == 0 || ry == 0 || (p0 == p1) { path.addLine(to: p1); return }
    let phi = xRotDeg * .pi / 180
    let cosP = cos(phi), sinP = sin(phi)
    let dx = (p0.x - p1.x) / 2, dy = (p0.y - p1.y) / 2
    let x1p = cosP * dx + sinP * dy
    let y1p = -sinP * dx + cosP * dy
    let lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
    if lambda > 1 { let s = sqrt(lambda); rx *= s; ry *= s }
    let rx2 = rx * rx, ry2 = ry * ry, x1p2 = x1p * x1p, y1p2 = y1p * y1p
    var num = rx2 * ry2 - rx2 * y1p2 - ry2 * x1p2
    let den = rx2 * y1p2 + ry2 * x1p2
    if num < 0 { num = 0 }
    var co = den == 0 ? 0 : sqrt(num / den)
    if largeArc == sweep { co = -co }
    let cxp = co * (rx * y1p / ry)
    let cyp = co * (-ry * x1p / rx)
    let cx = cosP * cxp - sinP * cyp + (p0.x + p1.x) / 2
    let cy = sinP * cxp + cosP * cyp + (p0.y + p1.y) / 2

    func ang(_ ux: CGFloat, _ uy: CGFloat, _ vx: CGFloat, _ vy: CGFloat) -> CGFloat {
        let dot = ux * vx + uy * vy
        let len = sqrt((ux * ux + uy * uy) * (vx * vx + vy * vy))
        var a = len == 0 ? 0 : acos(max(-1, min(1, dot / len)))
        if ux * vy - uy * vx < 0 { a = -a }
        return a
    }
    let ux = (x1p - cxp) / rx, uy = (y1p - cyp) / ry
    let vx = (-x1p - cxp) / rx, vy = (-y1p - cyp) / ry
    let theta1 = ang(1, 0, ux, uy)
    var dTheta = ang(ux, uy, vx, vy)
    if !sweep && dTheta > 0 { dTheta -= 2 * .pi }
    if sweep && dTheta < 0 { dTheta += 2 * .pi }

    let segs = max(1, Int(ceil(abs(dTheta) / (.pi / 2))))
    let delta = dTheta / CGFloat(segs)
    let t = 4.0 / 3.0 * tan(delta / 4)
    var theta = theta1
    var prev = p0
    for _ in 0..<segs {
        let theta2 = theta + delta
        let c1t = cos(theta), s1t = sin(theta)
        let c2t = cos(theta2), s2t = sin(theta2)
        let p2 = CGPoint(x: cx + cosP * rx * c2t - sinP * ry * s2t,
                         y: cy + sinP * rx * c2t + cosP * ry * s2t)
        let d1 = CGPoint(x: -cosP * rx * s1t - sinP * ry * c1t,
                         y: -sinP * rx * s1t + cosP * ry * c1t)
        let d2 = CGPoint(x: -cosP * rx * s2t - sinP * ry * c2t,
                         y: -sinP * rx * s2t + cosP * ry * c2t)
        let c1 = CGPoint(x: prev.x + t * d1.x, y: prev.y + t * d1.y)
        let c2 = CGPoint(x: p2.x - t * d2.x, y: p2.y - t * d2.y)
        path.addCurve(to: p2, control1: c1, control2: c2)
        theta = theta2; prev = p2
    }
}

private func + (a: CGPoint, b: CGPoint) -> CGPoint { CGPoint(x: a.x + b.x, y: a.y + b.y) }

/// 轻量字符扫描器:数字 / 命令字母 / 圆弧 flag。
private struct SVGScanner {
    private let chars: [Character]
    private var i = 0
    init(_ s: String) { chars = Array(s) }

    private mutating func skipSep() {
        while i < chars.count {
            let c = chars[i]
            if c == " " || c == "," || c == "\n" || c == "\t" || c == "\r" { i += 1 } else { break }
        }
    }

    mutating func nextCommand(prev: Character) -> Character? {
        skipSep()
        guard i < chars.count else { return nil }
        let c = chars[i]
        if c.isLetter { i += 1; return c }
        // 隐式重复:M→L,m→l,其余沿用上条命令
        switch prev {
        case " ": return nil
        case "M": return "L"
        case "m": return "l"
        default: return prev
        }
    }

    mutating func hasMoreNumbers() -> Bool {
        skipSep()
        guard i < chars.count else { return false }
        let c = chars[i]
        return c.isNumber || c == "." || c == "-" || c == "+"
    }

    mutating func number() -> CGFloat {
        skipSep()
        var s = ""
        if i < chars.count, chars[i] == "-" || chars[i] == "+" { s.append(chars[i]); i += 1 }
        var seenDot = false, seenExp = false
        while i < chars.count {
            let c = chars[i]
            if c.isNumber { s.append(c); i += 1 }
            else if c == "." && !seenDot && !seenExp { seenDot = true; s.append(c); i += 1 }
            else if (c == "e" || c == "E") && !seenExp {
                seenExp = true; s.append(c); i += 1
                if i < chars.count, chars[i] == "-" || chars[i] == "+" { s.append(chars[i]); i += 1 }
            } else { break }
        }
        return CGFloat(Double(s) ?? 0)
    }

    mutating func point() -> CGPoint { CGPoint(x: number(), y: number()) }

    mutating func flag() -> Bool {
        skipSep()
        guard i < chars.count else { return false }
        let c = chars[i]; i += 1
        return c == "1"
    }
}
