import SwiftUI

// ============================================================================
// 导入历史 sheet。android 端对应 FollowupScreen 的 HistorySheet。
// ============================================================================

struct FollowupHistorySheet: View {
    @ObservedObject var vm: FollowupViewModel
    @State private var expanded: String?

    var body: some View {
        ZStack {
            MeiliColor.bg.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: MeiliMetric.s2) {
                    Text("导入历史")
                        .font(MeiliFont.sheetH3).foregroundStyle(MeiliColor.ink)
                        .padding(.top, 22)
                    Text("生成过的顾客都在这里 · 点 ⭐ 收藏 · 可导入回填/登记战果")
                        .font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3)
                        .padding(.bottom, 8)

                    if vm.historyLoading {
                        HStack { Spacer(); ProgressView().tint(MeiliColor.clay); Spacer() }
                            .padding(.vertical, 30)
                    } else if vm.customers.isEmpty {
                        Text("还没有历史记录,生成一次话术后会自动保存到这里")
                            .font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3)
                            .padding(.vertical, 20)
                    } else {
                        // 收藏在前,其余按保存时间倒序
                        let sorted = vm.customers.sorted { a, b in
                            let fa = a.favorited == true, fb = b.favorited == true
                            if fa != fb { return fa }
                            return (a.savedAt ?? "") > (b.savedAt ?? "")
                        }
                        ForEach(Array(sorted.enumerated()), id: \.offset) { _, c in
                            CustomerRow(c: c,
                                        expanded: expanded == c.name,
                                        onToggleExpand: { expanded = expanded == c.name ? nil : c.name },
                                        vm: vm)
                        }
                    }
                }
                .padding(.horizontal, MeiliMetric.screenH)
                .padding(.bottom, 24)
            }
        }
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
    }
}

private struct CustomerRow: View {
    let c: FuCustomer
    let expanded: Bool
    let onToggleExpand: () -> Void
    @ObservedObject var vm: FollowupViewModel

    var body: some View {
        VStack(alignment: .leading, spacing: MeiliMetric.s2) {
            HStack(spacing: 8) {
                Button { vm.toggleFavorite(c) } label: {
                    MeiliIcon(MeiliIcons.star, size: 22)
                        .foregroundStyle(c.favorited == true ? MeiliColor.honey : MeiliColor.ink4)
                }
                .buttonStyle(.plain)
                VStack(alignment: .leading, spacing: 2) {
                    Text(c.name ?? "未命名")
                        .font(.sz(14, weight: .bold)).foregroundStyle(MeiliColor.ink)
                    Text("\((c.savedAt ?? "").prefix(16).replacingOccurrences(of: "T", with: " ")) · \(c.scripts?.count ?? 0) 版话术")
                        .font(MeiliFont.labelSm).foregroundStyle(MeiliColor.ink4)
                        .lineLimit(1)
                }
                Spacer()
            }
            HStack(spacing: MeiliMetric.s2) {
                MeiliButton("导入", kind: .soft, size: .xs) { vm.importCustomer(c) }
                MeiliButton("战果登记", kind: .ghost, size: .xs) { vm.businessFor = c }
                MeiliButton(expanded ? "收起话术" : "看话术", kind: .ghost, size: .xs, action: onToggleExpand)
            }
            if expanded, let script = c.lastScript?.nilIfBlank {
                MarkdownLiteView(text: script, accent: MeiliColor.clayDeep)
                    .padding(MeiliMetric.s2)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(MeiliColor.surfaceSoft)
                    .clipShape(RoundedRectangle(cornerRadius: MeiliRadius.sm, style: .continuous))
            }
        }
        .padding(MeiliMetric.s3)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(MeiliColor.surface)
        .clipShape(RoundedRectangle(cornerRadius: MeiliRadius.md, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: MeiliRadius.md, style: .continuous)
                .strokeBorder(MeiliColor.lineSoft, lineWidth: MeiliMetric.borderThin)
        }
    }
}

// ============================================================================
// 战果登记 sheet(字段对齐服务端 BusinessRequest;12 步引导式展开)。
// android 端对应 FollowupScreen 的 BusinessSheet,展开链严格对齐网页
// selectBReplied/selectBVisited/selectBDeal:答了⑧才出⑩;考虑/拒绝才出⑨回复内容+
// 未到店原因;已预约才出⑪到店;已到店才出 到店日期+成交;没成交出原因、成交了出金额+项目。
// ============================================================================

struct FollowupBusinessSheet: View {
    let customer: FuCustomer
    @ObservedObject var vm: FollowupViewModel

    /// 草稿态(打开时从最新版本已有战果回填)。
    @State private var draft: [String: Any]

    init(customer: FuCustomer, vm: FollowupViewModel) {
        self.customer = customer
        self.vm = vm
        let last = customer.scripts?.last?.business ?? [:]
        _draft = State(initialValue: last.mapValues(\.anyValue))
    }

    private func str(_ k: String) -> String { draft[k] as? String ?? "" }
    private func list(_ k: String) -> [String] { (draft[k] as? [Any])?.compactMap { $0 as? String } ?? [] }

    var body: some View {
        let replied = str("replied")
        let visited = str("visited")
        let deal = str("deal")

        ZStack {
            MeiliColor.bg.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Text("战果登记 · \(customer.name ?? "")")
                        .font(MeiliFont.sheetH3).foregroundStyle(MeiliColor.ink)
                        .padding(.top, 22)
                    Text("话术发出后,客人实际情况如何(写最新一版)")
                        .font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3)
                        .padding(.top, 3).padding(.bottom, 14)

                    chips("① 客户类型", ["常到店", "久违客", "新客"], "customer_type")
                    chips("② 上次到店时间",
                          ["2周以内", "2周到1个月", "1个月到3个月", "3个月到6个月", "6个月到1年", "1年到2年", "2年以上"],
                          "last_visit_period")
                    chipsMulti("③ 今日跟进动作(可多选)", ["邀约", "日常维护", "唤醒"], "today_actions")
                    chips("④ 3个月内客人回店没有?", ["有", "没有"], "recent_visit_3m")
                    chips("④ 3个月内客人充值没有?", ["有", "没有"], "recent_recharge_3m")
                    textField("⑤ 回店客情如何做(必填)", "return_visit_plan")
                    chips("⑥ 沟通方式是什么?", ["电话", "微信文字"], "channel")
                    chips("⑦ 话术的版本", ["V1", "V2", "V3", "自定义"], "msg_version") { v in
                        if v != "自定义" { draft["msg_version_custom"] = "" }
                    }
                    if str("msg_version") == "自定义" {
                        textField("自定义消息内容", "msg_version_custom")
                    }
                    chips("⑧ 顾客回复了吗?", ["没回复", "已读未回", "已预约", "考虑", "拒绝"], "replied") { v in
                        // 对齐网页:切换回复态时清掉不再显示的下游值
                        if !["考虑", "拒绝"].contains(v) {
                            draft["reply_content"] = ""; draft["no_visit_reason"] = ""
                        }
                        if v != "已预约" {
                            draft["visited"] = ""; draft["visit_date"] = ""
                            draft["deal"] = ""; draft["no_deal_reason"] = ""
                            draft["deal_amount_range"] = ""; draft["deal_amount_exact"] = NSNull(); draft["deal_project"] = ""
                        }
                    }
                    // —— ⑧ 之后才逐步展开 ——
                    if ["考虑", "拒绝"].contains(replied) {
                        textField("⑨ 回复内容", "reply_content")
                        textField("未到店原因", "no_visit_reason")
                    }
                    if !replied.isEmpty {
                        textField("⑩ 下次跟进时间(如 2026-07-20)", "next_followup_time")
                    }
                    if replied == "已预约" {
                        chips("⑪ 到店了吗?", ["还没到店", "约了改期", "已到店"], "visited") { v in
                            if v != "已到店" {
                                draft["visit_date"] = ""
                                draft["deal"] = ""; draft["no_deal_reason"] = ""
                                draft["deal_amount_range"] = ""; draft["deal_amount_exact"] = NSNull(); draft["deal_project"] = ""
                            }
                        }
                    }
                    if replied == "已预约", visited == "已到店" {
                        textField("到店日期(如 2026-07-18)", "visit_date")
                        chips("⑪ 成交了吗?", ["没成交", "成交了"], "deal") { v in
                            if v == "没成交" {
                                draft["deal_amount_range"] = ""; draft["deal_amount_exact"] = NSNull(); draft["deal_project"] = ""
                            } else {
                                draft["no_deal_reason"] = ""
                            }
                        }
                        if deal == "没成交" {
                            textField("未成交原因", "no_deal_reason")
                        }
                        if deal == "成交了" {
                            chips("⑫ 成交金额段位", ["1千以内", "1-3千", "3-5千", "5千-1万", "1-3万"], "deal_amount_range") { _ in
                                draft["deal_amount_exact"] = NSNull()   // 选段位则清精确金额(对齐网页)
                            }
                            exactAmountField
                            textField("成交项目", "deal_project")
                        }
                    }

                    MeiliButton("提交战果", block: true) {
                        vm.submitBusiness(draft)
                    }
                    .padding(.top, MeiliMetric.s3)
                }
                .padding(.horizontal, MeiliMetric.screenH)
                .padding(.bottom, 24)
            }
            .scrollDismissesKeyboard(.interactively)
        }
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
    }

    // ---- 小组件 ----

    private func chips(_ label: String, _ options: [String], _ key: String,
                       onPick: @escaping (String) -> Void = { _ in }) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(label).font(.sz(13, weight: .bold)).foregroundStyle(MeiliColor.ink)
            OptionChips(options: options, selected: [str(key)]) { o in
                let next = o == str(key) ? "" : o
                draft[key] = next
                onPick(next)
            }
        }
        .padding(.bottom, MeiliMetric.s2)
    }

    private func chipsMulti(_ label: String, _ options: [String], _ key: String) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(label).font(.sz(13, weight: .bold)).foregroundStyle(MeiliColor.ink)
            OptionChips(options: options, selected: Set(list(key))) { o in
                var cur = list(key)
                if let i = cur.firstIndex(of: o) { cur.remove(at: i) } else { cur.append(o) }
                draft[key] = cur
            }
        }
        .padding(.bottom, MeiliMetric.s2)
    }

    private func textField(_ label: String, _ key: String) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(label).font(.sz(13, weight: .bold)).foregroundStyle(MeiliColor.ink)
            PlainInput(text: Binding(get: { str(key) }, set: { draft[key] = $0 }))
        }
        .padding(.bottom, MeiliMetric.s2)
    }

    /// 精确金额(填了会取消段位)。
    private var exactAmountField: some View {
        let text = Binding<String>(
            get: {
                if let d = draft["deal_amount_exact"] as? Double {
                    return d.truncatingRemainder(dividingBy: 1) == 0 ? String(Int(d)) : String(d)
                }
                return ""
            },
            set: { v in
                draft["deal_amount_exact"] = Double(v) ?? NSNull()
                if !v.isEmpty { draft["deal_amount_range"] = "" }
            })
        return VStack(alignment: .leading, spacing: 5) {
            Text("精确金额(填了会取消段位)").font(.sz(13, weight: .bold)).foregroundStyle(MeiliColor.ink)
            PlainInput(keyboard: .decimalPad, text: text)
        }
        .padding(.bottom, MeiliMetric.s2)
    }
}
