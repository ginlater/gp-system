import SwiftUI

/// 绑定顾客(SPEC §4.4 / warm_2 #m-bind)。android 端对应 `ui/bind/BindCustomerScreen.kt`(Binding)。
/// 把一段待整理陪伴绑定到顾客 → 进会话预览开始分析。
struct BindCustomerView: View {
    let recordingId: Int
    var onBound: (Int, String) -> Void = { _, _ in }   // customerId, date → 会话预览

    @Environment(\.dismiss) private var dismiss
    @StateObject private var vm: BindCustomerViewModel

    init(recordingId: Int, onBound: @escaping (Int, String) -> Void = { _, _ in }) {
        self.recordingId = recordingId
        self.onBound = onBound
        _vm = StateObject(wrappedValue: BindCustomerViewModel(recordingId: recordingId))
    }

    var body: some View {
        ScrollView {
            VStack(spacing: MeiliMetric.cardGap) {
                MeiliTopBar(title: "绑定顾客", subtitle: vm.recLabel ?? "把这段陪伴绑定到顾客", onBack: { dismiss() })

                Picker("", selection: $vm.existingTab) {
                    Text("选已有顾客").tag(true)
                    Text("新增顾客").tag(false)
                }
                .pickerStyle(.segmented)

                if vm.existingTab { existingTab } else { newTab }
            }
            .padding(.horizontal, MeiliMetric.screenH)
            .padding(.bottom, 28)
        }
        .background(MeiliColor.bg)
        .toolbar(.hidden, for: .navigationBar)
        .scrollDismissesKeyboard(.interactively)
        .onAppear { vm.onAppear() }
        .onChange(of: vm.boundCustomer?.0) { _ in
            if let (cid, date) = vm.boundCustomer { onBound(cid, date); vm.boundCustomer = nil }
        }
        .confirmationDialog("绑定到「\(vm.pendingPick?.name ?? "")」？",
                            isPresented: Binding(get: { vm.pendingPick != nil }, set: { if !$0 { vm.pendingPick = nil } }),
                            titleVisibility: .visible) {
            Button("确认绑定") { vm.confirmBind() }
            Button("取消", role: .cancel) { vm.pendingPick = nil }
        } message: {
            if vm.pendingPick?.inDay == false { Text("将先把 TA 补登到这段陪伴当天的接诊名单，再绑定。") }
        }
        .overlay(alignment: .bottom) { toastBar }
    }

    // MARK: 选已有

    private var existingTab: some View {
        VStack(spacing: MeiliMetric.cardGap) {
            HStack(spacing: 8) {
                MeiliIcon(MeiliIcons.search, size: 18).foregroundStyle(MeiliColor.ink3)
                TextField("搜姓名 / 手机尾号 / 会员卡号",
                          text: Binding(get: { vm.query }, set: { vm.onQueryChange($0) }))
                    .font(MeiliFont.bodyLarge).tint(MeiliColor.clay).autocorrectionDisabled()
            }
            .padding(.horizontal, 14).frame(height: 50)
            .background(MeiliColor.surfaceSoft).clipShape(RoundedRectangle(cornerRadius: MeiliRadius.sm, style: .continuous))
            .overlay { RoundedRectangle(cornerRadius: MeiliRadius.sm, style: .continuous).strokeBorder(MeiliColor.line, lineWidth: 1.5) }

            if vm.searching && vm.picks.isEmpty {
                MeiliCard { loadingRow("正在查找…") }
            } else if vm.picks.isEmpty {
                MeiliCard { emptyHint(MeiliIcons.profile, "没有候选顾客", "换个关键词，或切到「新增顾客」建一位") }
            } else {
                MeiliCard(tight: true) {
                    VStack(spacing: 0) {
                        ForEach(Array(vm.picks.enumerated()), id: \.offset) { idx, p in
                            if idx > 0 { Rectangle().fill(MeiliColor.lineSoft).frame(height: 1) }
                            pickRow(p)
                        }
                    }
                }
            }
        }
    }

    private func pickRow(_ p: BindCustomerViewModel.Pick) -> some View {
        HStack(spacing: 12) {
            MeiliAvatar(name: p.name)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(p.name).font(MeiliFont.rowTitle).foregroundStyle(MeiliColor.ink).lineLimit(1)
                    if p.justCreated { StatusPill(text: "新建", kind: .warn) }
                }
                HStack(spacing: 6) {
                    Text(p.memberCard?.nilIfBlank ?? (p.phoneTail.map { "尾号\($0)" } ?? "新客"))
                        .font(.sz(11)).foregroundStyle(MeiliColor.ink3)
                    if p.inDay { Text("· 今日已接诊").font(.sz(11)).foregroundStyle(MeiliColor.leafText) }
                    if p.boundCount > 0 { Text("· \(p.boundCount)段").font(.sz(11)).foregroundStyle(MeiliColor.ink3) }
                }
            }
            Spacer(minLength: 6)
            if p.locked {
                StatusPill(text: "已锁定", kind: .neutral)
            } else {
                MeiliButton("绑定到这里", size: .xs, icon: MeiliIcons.link, enabled: !vm.submitting) { vm.pick(p) }
            }
        }
        .padding(.vertical, 13)
    }

    // MARK: 新增

    private var newTab: some View {
        MeiliCard {
            VStack(spacing: 15) {
                MeiliField(label: "姓名", text: $vm.newName, placeholder: "顾客姓名", icon: MeiliIcons.profile)
                MeiliField(label: "手机尾号", text: Binding(get: { vm.newPhoneTail }, set: { vm.setNewPhoneTail($0) }),
                           placeholder: "4 位数字", icon: MeiliIcons.phone, keyboard: .numberPad)
                Text("会员卡号由系统自动生成；绑定后可在档案补充资料。")
                    .font(.sz(11)).foregroundStyle(MeiliColor.ink3)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if let e = vm.error { MeiliBanner(message: e) }
                MeiliButton(vm.submitting ? "提交中…" : "新增并选中", block: true, enabled: vm.canSubmitNew) { vm.submitNew() }
            }
        }
    }

    // MARK: 小件

    @ViewBuilder private var toastBar: some View {
        if let t = vm.toast ?? vm.error {
            Text(t).font(MeiliFont.bodySm).foregroundStyle(.white)
                .padding(.horizontal, 16).padding(.vertical, 11)
                .background(vm.error != nil ? MeiliColor.roseDeep : MeiliColor.inkSurface).clipShape(Capsule())
                .padding(.bottom, 24)
                .task { try? await Task.sleep(nanoseconds: 2_000_000_000); vm.toast = nil; vm.error = nil }
        }
    }
    private func loadingRow(_ text: String) -> some View {
        HStack(spacing: 10) { ProgressView().tint(MeiliColor.clay); Text(text).font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3) }
            .frame(maxWidth: .infinity).padding(.vertical, 20)
    }
    private func emptyHint(_ icon: MeiliGlyph, _ title: String, _ sub: String?) -> some View {
        VStack(spacing: 6) {
            MeiliIcon(icon, size: 30).foregroundStyle(MeiliColor.ink4)
            Text(title).font(.sz(14.5, weight: .bold)).foregroundStyle(MeiliColor.ink2)
            if let sub { Text(sub).font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3).multilineTextAlignment(.center) }
        }
        .frame(maxWidth: .infinity).padding(.vertical, 30)
    }
}
