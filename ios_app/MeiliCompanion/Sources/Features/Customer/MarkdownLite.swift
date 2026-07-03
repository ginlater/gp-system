import SwiftUI

/// 轻量 Markdown 渲染(1:1 复刻 web customer_profile.html 的 `mdLite`)。
/// 支持:`#`标题(左侧 accent 竖条) / `▶`子标题 / `-`圆点列表 / `1.`数字圈列表 /
/// `| 表格 |`(accent 表头) / `**粗**` / `★☆`金色星 / `✓`绿 `✗`红 / 段落。
enum MDBlock {
    case heading(String)
    case sub(String)
    case bullets([String])
    case numbered([String])
    case table(header: [String]?, rows: [[String]])
    case paragraph(String)
}

func parseMarkdownLite(_ raw: String) -> [MDBlock] {
    var blocks: [MDBlock] = []
    var ul: [String] = []
    var ol: [String] = []
    var tbl: [[String]] = []
    var tblHeader = false

    func flushUL() { if !ul.isEmpty { blocks.append(.bullets(ul)); ul = [] } }
    func flushOL() { if !ol.isEmpty { blocks.append(.numbered(ol)); ol = [] } }
    func flushTbl() {
        if !tbl.isEmpty {
            if tblHeader, let head = tbl.first {
                blocks.append(.table(header: head, rows: Array(tbl.dropFirst())))
            } else {
                blocks.append(.table(header: nil, rows: tbl))
            }
            tbl = []; tblHeader = false
        }
    }
    func flushAll() { flushUL(); flushOL(); flushTbl() }

    func cellsOf(_ t: String) -> [String] {
        var s = t
        if s.hasPrefix("|") { s.removeFirst() }
        if s.hasSuffix("|") { s.removeLast() }
        return s.components(separatedBy: "|").map { $0.trimmingCharacters(in: .whitespaces) }
    }
    func isSep(_ arr: [String]) -> Bool {
        !arr.isEmpty && arr.allSatisfy { c in
            let x = c.replacingOccurrences(of: " ", with: "")
            return !x.isEmpty && x.allSatisfy { $0 == "-" || $0 == ":" } && x.contains("-")
        }
    }

    for rawLine in raw.split(separator: "\n", omittingEmptySubsequences: false) {
        let t = rawLine.trimmingCharacters(in: .whitespaces)
        if t.isEmpty { flushAll(); continue }

        // 表格行
        if t.hasPrefix("|"), t.dropFirst().contains("|") {
            flushUL(); flushOL()
            let arr = cellsOf(t)
            if isSep(arr) { if tbl.count == 1 { tblHeader = true }; continue }
            tbl.append(arr); continue
        }
        flushTbl()

        if let h = firstMatch(t, prefixes: ["######", "#####", "####", "###", "##", "#"]) {
            flushUL(); flushOL(); blocks.append(.heading(h)); continue
        }
        if let s = firstMatch(t, prefixes: ["▶", "▷", "►"]) {
            flushUL(); flushOL(); blocks.append(.sub(s)); continue
        }
        if let b = bulletBody(t) {
            flushOL(); ul.append(b); continue
        }
        if let n = numberedBody(t) {
            flushUL(); ol.append(n); continue
        }
        flushUL(); flushOL(); blocks.append(.paragraph(t))
    }
    flushAll()
    return blocks
}

private func firstMatch(_ t: String, prefixes: [String]) -> String? {
    for p in prefixes where t.hasPrefix(p) {
        let body = String(t.dropFirst(p.count)).trimmingCharacters(in: .whitespaces)
        if !body.isEmpty || p.first == "#" { return body }
    }
    return nil
}
private func bulletBody(_ t: String) -> String? {
    for p in ["- ", "* ", "• ", "· "] where t.hasPrefix(p) {
        return String(t.dropFirst(p.count)).trimmingCharacters(in: .whitespaces)
    }
    return nil
}
private func numberedBody(_ t: String) -> String? {
    // ^\d+[.、)]\s*(.+)
    var i = t.startIndex
    var digits = 0
    while i < t.endIndex, t[i].isNumber { i = t.index(after: i); digits += 1 }
    guard digits > 0, i < t.endIndex, ".、)".contains(t[i]) else { return nil }
    let body = String(t[t.index(after: i)...]).trimmingCharacters(in: .whitespaces)
    return body.isEmpty ? nil : body
}

/// 渲染 [MDBlock]。`accent` = 当前卡片主色(标题竖条/表头/数字圈)。
struct MarkdownLiteView: View {
    let text: String
    var accent: Color = MeiliColor.clay

    var body: some View {
        let blocks = parseMarkdownLite(text)
        VStack(alignment: .leading, spacing: 7) {
            ForEach(Array(blocks.enumerated()), id: \.offset) { _, b in
                block(b)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder private func block(_ b: MDBlock) -> some View {
        switch b {
        case .heading(let s):
            HStack(alignment: .top, spacing: 8) {
                RoundedRectangle(cornerRadius: 2).fill(accent).frame(width: 3).frame(maxHeight: .infinity)
                MDInline.text(s, base: MeiliColor.ink).font(.sz(14, weight: .bold))
            }
            .fixedSize(horizontal: false, vertical: true)
            .padding(.top, 6)
        case .sub(let s):
            MDInline.text(s, base: accent).font(.sz(13.5, weight: .bold)).padding(.top, 3)
        case .bullets(let items):
            VStack(alignment: .leading, spacing: 6) {
                ForEach(Array(items.enumerated()), id: \.offset) { _, it in
                    HStack(alignment: .top, spacing: 9) {
                        Circle().fill(accent).frame(width: 6, height: 6).padding(.top, 7)
                        MDInline.text(it, base: MeiliColor.ink2).font(.sz(13.5)).lineSpacing(4)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
            }
        case .numbered(let items):
            VStack(alignment: .leading, spacing: 6) {
                ForEach(Array(items.enumerated()), id: \.offset) { i, it in
                    HStack(alignment: .top, spacing: 9) {
                        Text("\(i + 1)").font(.sz(11, weight: .bold)).foregroundStyle(.white)
                            .frame(width: 18, height: 18).background(accent).clipShape(Circle())
                        MDInline.text(it, base: MeiliColor.ink2).font(.sz(13.5)).lineSpacing(4)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
            }
        case .table(let header, let rows):
            tableView(header: header, rows: rows)
        case .paragraph(let s):
            MDInline.text(s, base: MeiliColor.ink).font(.sz(13.5)).lineSpacing(5)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func tableView(header: [String]?, rows: [[String]]) -> some View {
        let cols = max(header?.count ?? 0, rows.map(\.count).max() ?? 1)
        let cellW: CGFloat = 132
        return ScrollView(.horizontal, showsIndicators: false) {
            VStack(spacing: 0) {
                if let header {
                    tableRow(header, cols: cols, width: cellW, isHeader: true, even: false)
                }
                ForEach(Array(rows.enumerated()), id: \.offset) { i, r in
                    tableRow(r, cols: cols, width: cellW, isHeader: false, even: i % 2 == 1)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))
            .overlay { RoundedRectangle(cornerRadius: 9, style: .continuous).strokeBorder(MeiliColor.line, lineWidth: 1) }
        }
        .padding(.vertical, 4)
    }

    private func tableRow(_ cells: [String], cols: Int, width: CGFloat, isHeader: Bool, even: Bool) -> some View {
        HStack(spacing: 0) {
            ForEach(0..<cols, id: \.self) { c in
                let val = c < cells.count ? cells[c] : ""
                Group {
                    if isHeader {
                        Text(val).font(.sz(12.5, weight: .semibold)).foregroundStyle(.white)
                    } else {
                        MDInline.text(val, base: MeiliColor.ink).font(.sz(12.5)).lineSpacing(3)
                    }
                }
                .frame(width: width, alignment: .leading)
                .padding(.horizontal, 10).padding(.vertical, 9)
                .background(isHeader ? accent : (even ? MeiliColor.surfaceSoft : MeiliColor.surface))
                .overlay(alignment: .leading) {
                    if c > 0 { Rectangle().fill(MeiliColor.line).frame(width: 1) }
                }
            }
        }
        .overlay(alignment: .top) { if !isHeader { Rectangle().fill(MeiliColor.line).frame(height: 1) } }
    }
}

/// 行内解析:`**粗**` / `★☆`金星 / `✓`绿 `✗`红。返回可拼接的 `Text`。
enum MDInline {
    static func text(_ s: String, base: Color) -> Text {
        var result = Text("")
        var rest = Substring(s)
        while let open = rest.range(of: "**") {
            result = result + plain(String(rest[rest.startIndex..<open.lowerBound]), base)
            let afterOpen = rest[open.upperBound...]
            if let close = afterOpen.range(of: "**") {
                let bold = String(afterOpen[afterOpen.startIndex..<close.lowerBound])
                result = result + Text(bold).bold().foregroundColor(MeiliColor.ink)
                rest = afterOpen[close.upperBound...]
            } else {
                result = result + plain("**" + String(afterOpen), base)
                rest = Substring("")
                break
            }
        }
        result = result + plain(String(rest), base)
        return result
    }

    private static func plain(_ s: String, _ base: Color) -> Text {
        var t = Text("")
        var buf = ""
        func flush() { if !buf.isEmpty { t = t + Text(buf).foregroundColor(base); buf = "" } }
        for ch in s {
            switch ch {
            case "★", "☆": flush(); t = t + Text(String(ch)).foregroundColor(MeiliColor.honey)
            case "✓": flush(); t = t + Text("✓").foregroundColor(MeiliColor.leafText).bold()
            case "✗", "✘", "×": flush(); t = t + Text("✗").foregroundColor(MeiliColor.roseText).bold()
            default: buf.append(ch)
            }
        }
        flush()
        return t
    }
}

/// 价值预测维度卡顶部彩色渐变头(对齐 web .vp-head)。
struct ValueCardHeader: View {
    let title: String
    let icon: MeiliGlyph
    let color: Color
    var body: some View {
        HStack(spacing: 8) {
            MeiliIcon(icon, size: 18).foregroundStyle(.white)
            Text(title).font(.sz(14, weight: .bold)).foregroundStyle(.white).lineLimit(1)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 14).padding(.vertical, 11)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(LinearGradient(colors: [color, color.opacity(0.82)], startPoint: .topLeading, endPoint: .bottomTrailing))
    }
}

/// 价值预测一个维度卡(彩头 + markdown 正文),对齐 web .vp-card。
struct ValueDimCard: View {
    let title: String
    let icon: MeiliGlyph
    let color: Color
    let content: String
    var body: some View {
        VStack(spacing: 0) {
            ValueCardHeader(title: title, icon: icon, color: color)
            MarkdownLiteView(text: content, accent: color)
                .padding(14)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(MeiliColor.surface)
        }
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay { RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(MeiliColor.lineSoft, lineWidth: 1) }
        .shadow(color: Color(hex: 0x785A44, alpha: 0.07), radius: 8, y: 4)
    }
}
