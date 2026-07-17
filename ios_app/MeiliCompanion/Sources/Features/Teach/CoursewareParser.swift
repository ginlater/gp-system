import Foundation

/* ===================================================================
 * 课件 HTML → 原生内容模型(2026-07-16 课件正文原生化)。
 *
 * 课件是 teach 服务器上的静态 html(9 章同一套模板,内容随时会改),
 * 原生渲染的关键是**解析时只认模板结构、不认具体内容**:
 *   nav-btn(可选 tab 导航)→ .tab 段 → .card(card-title/card-tag/card-body)
 *   → 块:h1/h4 标题、p 段落、ul/li 列表、table、.step 步骤、.audio-player 音频。
 * 服务器改文字/加卡片/加音频 → App 即时生效;模板大改时块提取降级为纯文本,
 * 内容仍可读(不会白屏)。
 * =================================================================== */

enum CourseBlock {
    case heading(String)
    case paragraph(String)
    case bullets([String])
    case step(n: String, text: String)
    case table([[String]])
    case audio(title: String, url: URL)
}

struct CourseCard {
    let num: String
    let title: String
    let tag: String
    var blocks: [CourseBlock]
}

struct CourseTab {
    let title: String
    var intro: [CourseBlock]
    var cards: [CourseCard]
}

enum CoursewareParser {

    /// 下载并解析一章课件。
    static func fetch(_ url: URL) async throws -> [CourseTab] {
        var req = URLRequest(url: url)
        req.cachePolicy = .reloadIgnoringLocalCacheData
        let (data, resp) = try await URLSession.shared.data(for: req)
        guard let http = resp as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw SubsystemError("课件加载失败,请检查网络后重试")
        }
        guard let html = String(data: data, encoding: .utf8) else {
            throw SubsystemError("课件内容解析失败")
        }
        let tabs = parse(html, baseURL: url)
        guard !tabs.isEmpty else { throw SubsystemError("课件内容为空") }
        return tabs
    }

    static func parse(_ rawHtml: String, baseURL: URL) -> [CourseTab] {
        // 去掉样式/脚本/注释
        var html = rawHtml
        for pattern in ["<style[\\s\\S]*?</style>", "<script[\\s\\S]*?</script>", "<!--[\\s\\S]*?-->"] {
            html = html.replacingOccurrences(of: pattern, with: "", options: .regularExpression)
        }

        // nav 导航(可选):showTab('t1',this)>标题<
        let navPairs = matches(#"showTab\('([^']+)'[^>]*>([^<]+)<"#, in: html).map {
            (id: $0[1], title: decode($0[2]))
        }

        var tabs: [CourseTab] = []
        if navPairs.isEmpty {
            tabs = [parseSegment(html, title: "", baseURL: baseURL)]
        } else {
            for (i, pair) in navPairs.enumerated() {
                // 每个 tab 的内容段:id="tX" 的 div 起,到下一个 tab 的 div 或文末
                guard let startRange = html.range(of: "id=\"\(pair.id)\"") else { continue }
                let start = startRange.upperBound
                let end: String.Index
                if i + 1 < navPairs.count, let next = html.range(of: "id=\"\(navPairs[i + 1].id)\"") {
                    end = next.lowerBound
                } else {
                    end = html.endIndex
                }
                tabs.append(parseSegment(String(html[start..<end]), title: pair.title, baseURL: baseURL))
            }
        }
        // 全空(模板大改)→ 降级:整页纯文本
        if tabs.allSatisfy({ $0.intro.isEmpty && $0.cards.isEmpty }) {
            let text = plainText(html)
            guard !text.isEmpty else { return [] }
            return [CourseTab(title: "", intro: [.paragraph(text)], cards: [])]
        }
        return tabs
    }

    // ---- 段解析:卡片切分 ----

    private static func parseSegment(_ segment: String, title: String, baseURL: URL) -> CourseTab {
        var tab = CourseTab(title: title, intro: [], cards: [])
        // 卡片边界
        let cardMarks = ranges(#"<div class="card" id="[^"]*">"#, in: segment)
        let introEnd = cardMarks.first?.lowerBound ?? segment.endIndex
        tab.intro = parseBlocks(String(segment[..<introEnd]), baseURL: baseURL)

        for (i, mark) in cardMarks.enumerated() {
            let end = i + 1 < cardMarks.count ? cardMarks[i + 1].lowerBound : segment.endIndex
            let chunk = String(segment[mark.lowerBound..<end])
            let num = firstMatch(#"class="card-num">([^<]*)<"#, in: chunk).map(decode) ?? ""
            let cardTitle = firstMatch(#"class="card-title">([^<]*)<"#, in: chunk).map(decode) ?? ""
            let tag = firstMatch(#"class="card-tag">([^<]*)<"#, in: chunk).map(decode) ?? ""
            // 正文从 card-body 起(去掉头部,避免标题重复出现在块里)
            let bodyStart = chunk.range(of: "card-body")?.upperBound ?? chunk.startIndex
            let blocks = parseBlocks(String(chunk[bodyStart...]), baseURL: baseURL)
            tab.cards.append(CourseCard(num: num, title: cardTitle, tag: tag, blocks: blocks))
        }
        return tab
    }

    // ---- 块提取(位置排序 + 嵌套去重) ----

    private static func parseBlocks(_ chunk: String, baseURL: URL) -> [CourseBlock] {
        var found: [(range: Range<String.Index>, block: CourseBlock)] = []

        // 音频:ap-title + 其后最近的 <audio src>
        let titleRanges = ranges(#"class="ap-title">"#, in: chunk)
        for tr in titleRanges {
            let after = String(chunk[tr.upperBound...])
            guard let t = firstMatch(#"^([^<]*)"#, in: after),
                  let src = firstMatch(#"<audio[^>]*src="([^"]*)""#, in: String(after.prefix(1200))),
                  let url = URL(string: src, relativeTo: baseURL) else { continue }
            found.append((tr, .audio(title: decode(t), url: url)))
        }
        // 标题
        for m in matchRanges(#"<h[1-6][^>]*>([\s\S]*?)</h[1-6]>"#, in: chunk) {
            let text = plainText(m.groups[0])
            if !text.isEmpty { found.append((m.range, .heading(text))) }
        }
        // 步骤(.step:step-n + step-text)
        for m in matchRanges(#"class="step-n">([\s\S]*?)</div>[\s\S]{0,60}?class="step-text">([\s\S]*?)</div>"#, in: chunk) {
            let n = plainText(m.groups[0])
            let text = plainText(m.groups[1])
            if !text.isEmpty { found.append((m.range, .step(n: n, text: text))) }
        }
        // 七天计划类:day-label + day-content
        for m in matchRanges(#"class="day-label">([\s\S]*?)</div>[\s\S]{0,40}?class="day-content">([\s\S]*?)</div>"#, in: chunk) {
            let n = plainText(m.groups[0])
            let text = plainText(m.groups[1])
            if !text.isEmpty { found.append((m.range, .step(n: n, text: text))) }
        }
        // 表格
        for m in matchRanges(#"<table[\s\S]*?</table>"#, in: chunk) {
            var rows: [[String]] = []
            for tr in matchRanges(#"<tr[\s\S]*?</tr>"#, in: m.matched) {
                let cells = matchRanges(#"<t[hd][^>]*>([\s\S]*?)</t[hd]>"#, in: tr.matched)
                    .map { plainText($0.groups[0]) }
                if !cells.isEmpty { rows.append(cells) }
            }
            if !rows.isEmpty { found.append((m.range, .table(rows))) }
        }
        // 列表
        for m in matchRanges(#"<[uo]l[^>]*>([\s\S]*?)</[uo]l>"#, in: chunk) {
            let items = matchRanges(#"<li[^>]*>([\s\S]*?)</li>"#, in: m.groups[0])
                .map { plainText($0.groups[0]) }
                .filter { !$0.isEmpty }
            if !items.isEmpty { found.append((m.range, .bullets(items))) }
        }
        // 段落
        for m in matchRanges(#"<p[^>]*>([\s\S]*?)</p>"#, in: chunk) {
            let text = plainText(m.groups[0])
            if !text.isEmpty { found.append((m.range, .paragraph(text))) }
        }

        // 位置排序 + 丢掉被更早接受的块包住的嵌套块(如表格里的 p)
        found.sort { $0.range.lowerBound < $1.range.lowerBound }
        var out: [CourseBlock] = []
        var coveredUntil = chunk.startIndex
        for item in found {
            if item.range.lowerBound < coveredUntil { continue }
            out.append(item.block)
            if item.range.upperBound > coveredUntil { coveredUntil = item.range.upperBound }
        }
        return out
    }

    // ---- 文本工具 ----

    /// 去标签 + 实体解码 + <br>→换行。
    static func plainText(_ s: String) -> String {
        var t = s.replacingOccurrences(of: #"<br\s*/?>"#, with: "\n", options: .regularExpression)
        t = t.replacingOccurrences(of: #"<[^>]+>"#, with: "", options: .regularExpression)
        return decode(t).trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static func decode(_ s: String) -> String {
        s.replacingOccurrences(of: "&nbsp;", with: " ")
            .replacingOccurrences(of: "&amp;", with: "&")
            .replacingOccurrences(of: "&lt;", with: "<")
            .replacingOccurrences(of: "&gt;", with: ">")
            .replacingOccurrences(of: "&quot;", with: "\"")
            .replacingOccurrences(of: "&#39;", with: "'")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    // ---- 正则小工具 ----

    /// [0]=整段匹配,[1..]=捕获组。
    private static func matches(_ pattern: String, in s: String) -> [[String]] {
        matchRanges(pattern, in: s).map { [$0.matched] + $0.groups }
    }

    private static func firstMatch(_ pattern: String, in s: String) -> String? {
        matchRanges(pattern, in: s).first?.groups.first
    }

    private static func ranges(_ pattern: String, in s: String) -> [Range<String.Index>] {
        matchRanges(pattern, in: s).map(\.range)
    }

    struct RegexMatch {
        let range: Range<String.Index>
        let matched: String
        let groups: [String]
    }

    static func matchRanges(_ pattern: String, in s: String) -> [RegexMatch] {
        guard let re = try? NSRegularExpression(pattern: pattern) else { return [] }
        let ns = s as NSString
        return re.matches(in: s, range: NSRange(location: 0, length: ns.length)).compactMap { m in
            guard let r = Range(m.range, in: s) else { return nil }
            var groups: [String] = []
            for gi in 1..<m.numberOfRanges {
                if let gr = Range(m.range(at: gi), in: s) {
                    groups.append(String(s[gr]))
                } else {
                    groups.append("")
                }
            }
            return RegexMatch(range: r, matched: String(s[r]), groups: groups)
        }
    }
}
