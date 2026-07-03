import SwiftUI

/// 「客户」搜索屏(方案A 新增 tab)。android 端对应 `ui/customer/CustomerScreen.kt`。
/// 搜顾客 → 进 TA 的历史陪伴与画像。默认拉最近接待一批。
/// 红线:对外零「录音/录制」。
struct CustomerView: View {
    var onOpenDetail: (Int) -> Void = { _ in }
    @StateObject private var vm = CustomerViewModel()

    var body: some View {
        ScrollView {
            VStack(spacing: MeiliMetric.cardGap) {
                MeiliTopBar(title: "客户", subtitle: "默认看最近一个月接待的顾客；也可搜姓名 / 卡号查任意顾客")

                searchField

                if !vm.customers.isEmpty || !vm.loading {
                    Text(vm.query.trimmingCharacters(in: .whitespaces).isEmpty ? "最近一个月接待" : "搜索结果")
                        .font(.sz(13, weight: .bold)).foregroundStyle(MeiliColor.ink2)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.leading, 4)
                }

                content
            }
            .padding(.horizontal, MeiliMetric.screenH)
            .padding(.bottom, MeiliMetric.bottomNavInset)
        }
        .background(MeiliColor.bg)
        .scrollDismissesKeyboard(.interactively)
        .onAppear { vm.onAppear() }
    }

    private var searchField: some View {
        HStack(spacing: 8) {
            MeiliIcon(MeiliIcons.search, size: 18).foregroundStyle(MeiliColor.ink3)
            TextField("输入姓名 / 会员卡号",
                      text: Binding(get: { vm.query }, set: { vm.setQuery($0) }))
                .font(MeiliFont.bodyLarge).foregroundStyle(MeiliColor.ink).tint(MeiliColor.clay)
                .autocorrectionDisabled().submitLabel(.search)
        }
        .padding(.horizontal, 14).frame(minHeight: 50)
        .background(MeiliColor.surfaceSoft)
        .clipShape(RoundedRectangle(cornerRadius: MeiliRadius.sm, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: MeiliRadius.sm, style: .continuous)
                .strokeBorder(MeiliColor.line, lineWidth: MeiliMetric.borderField)
        }
    }

    @ViewBuilder private var content: some View {
        if vm.loading && vm.customers.isEmpty {
            MeiliCard { inlineLoading("正在查找顾客…") }
        } else if let e = vm.error, vm.customers.isEmpty {
            MeiliCard { emptyHint(MeiliIcons.warn, "查找失败", e) }
        } else if vm.customers.isEmpty {
            MeiliCard {
                emptyHint(MeiliIcons.profile,
                          vm.query.isEmpty ? "最近一个月还没有接待记录" : "没有匹配的顾客",
                          vm.query.isEmpty ? "完成接诊并绑定顾客后，会在这里看到最近接待的顾客；也可在上方搜索任意顾客"
                                           : "换个姓名 / 会员卡号试试")
            }
        } else {
            MeiliCard(tight: true) {
                VStack(spacing: 0) {
                    ForEach(Array(vm.customers.enumerated()), id: \.offset) { idx, c in
                        if idx > 0 { Rectangle().fill(MeiliColor.lineSoft).frame(height: 1) }
                        row(c)
                    }
                }
            }
        }
    }

    private func row(_ c: Customer) -> some View {
        let name = c.name?.nilIfBlank ?? "未知顾客"
        let sage = ((c.cid ?? 0) % 2 == 0)
        return Button {
            if let id = c.cid { onOpenDetail(id) }
        } label: {
            HStack(spacing: 13) {
                Text(String(name.prefix(1)))
                    .font(MeiliFont.serif(17))
                    .foregroundStyle(sage ? MeiliColor.sageDeep : MeiliColor.clayDeep)
                    .frame(width: MeiliMetric.avatar, height: MeiliMetric.avatar)
                    .background(sage ? MeiliColor.sageSoft : MeiliColor.claySoft)
                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                VStack(alignment: .leading, spacing: 2) {
                    Text(name).font(MeiliFont.rowTitle).foregroundStyle(MeiliColor.ink).lineLimit(1)
                    Text(meta(c)).font(.sz(11.5)).foregroundStyle(MeiliColor.ink3).lineLimit(1)
                }
                Spacer(minLength: 8)
                MeiliIcon(MeiliIcons.chevRight, size: 18).foregroundStyle(MeiliColor.clay)
            }
            .padding(.vertical, 15)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func meta(_ c: Customer) -> String {
        if let m = c.memberCard?.nilIfBlank { return m }
        if let t = c.phoneTail?.nilIfBlank { return "尾号\(t)" }
        return "新客"
    }

    private func inlineLoading(_ text: String) -> some View {
        HStack(spacing: 10) {
            ProgressView().tint(MeiliColor.clay)
            Text(text).font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3)
        }
        .frame(maxWidth: .infinity).padding(.vertical, 22)
    }
    private func emptyHint(_ icon: MeiliGlyph, _ title: String, _ sub: String?) -> some View {
        VStack(spacing: 0) {
            MeiliIcon(icon, size: 30).foregroundStyle(MeiliColor.ink4)
                .frame(width: 58, height: 58).background(MeiliColor.surfaceSoft)
                .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
            Text(title).font(.sz(14.5, weight: .bold)).foregroundStyle(MeiliColor.ink2).padding(.top, 14)
            if let sub, !sub.isEmpty {
                Text(sub).font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3)
                    .multilineTextAlignment(.center).padding(.top, 6)
            }
        }
        .frame(maxWidth: .infinity).padding(.vertical, 36)
    }
}
