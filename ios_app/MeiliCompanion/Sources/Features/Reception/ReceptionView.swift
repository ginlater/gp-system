import SwiftUI

/// 今日接诊(warm_2 #reception)。android 端对应 `ui/reception/ReceptionScreen.kt` 的今日接诊部分。
/// 本轮:日期切换 + 今日接诊列表(看报告/开始分析入口) + 新增/移除。待整理 + 从陪伴笔同步 后续补。
struct ReceptionView: View {
    var onOpenReport: (Int) -> Void = { _ in }
    var onOpenPreview: (Int, String) -> Void = { _, _ in }   // customerId, date
    var onBind: (Int) -> Void = { _ in }                     // recordingId → 绑定顾客
    var onOpenReminders: () -> Void = {}

    @StateObject private var vm = ReceptionViewModel()
    @StateObject private var pendingVM = PendingViewModel()
    @StateObject private var player = AudioPlayer()
    @State private var playingRid: Int?
    @State private var showDatePicker = false
    @State private var deleteAsk: Int?   // 待删除的片段 id(确认弹窗)

    var body: some View {
        ScrollView {
            VStack(spacing: MeiliMetric.cardGap) {
                MeiliTopBar(title: "今日接诊", subtitle: "陪伴前后，把今天要接诊的顾客加进来") {
                    MeiliButton("刷新", kind: .ghost, size: .small) { vm.refresh(); pendingVM.refresh() }
                    TopBarIconButton(icon: MeiliIcons.reminder) { onOpenReminders() }
                }
                dateNav
                MeiliButton("加入今日接诊", icon: MeiliIcons.add, block: true) { vm.openAdd() }
                content
                pendingSection
            }
            .padding(.horizontal, MeiliMetric.screenH)
            .padding(.bottom, MeiliMetric.bottomNavInset)
        }
        .background(MeiliColor.bg)
        .onAppear { vm.onAppear(); pendingVM.onAppear() }
        .onDisappear { player.stop() }
        .sheet(isPresented: $vm.showAdd) { addSheet }
        .sheet(isPresented: $pendingVM.syncSheet) { PenSyncSheet(vm: pendingVM) }
        .alert("删除这段陪伴？", isPresented: Binding(
            get: { deleteAsk != nil },
            set: { if !$0 { deleteAsk = nil } })) {
            Button("取消", role: .cancel) {}
            Button("删除", role: .destructive) {
                if let rid = deleteAsk { pendingVM.requestDelete(rid) }
            }
        } message: {
            Text("不超过 5 分钟的会立即删除、不可恢复；超过 5 分钟的将提交管理员审批。")
        }
        .overlay(alignment: .bottom) { toastBar }
    }

    // MARK: 待整理(未绑定陪伴)

    @ViewBuilder private var pendingSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                SectionLabel("待整理 · 未绑定陪伴", icon: MeiliIcons.tidy)
                Spacer()
                if !pendingVM.recordings.isEmpty {
                    Text("\(pendingVM.recordings.count) 段").font(.sz(11, weight: .bold)).foregroundStyle(MeiliColor.ink3)
                }
            }
            .padding(.leading, 2).padding(.top, 6)

            if pendingVM.loading && pendingVM.recordings.isEmpty {
                MeiliCard { loadingRow("正在调取待整理…") }
            } else if let e = pendingVM.error, pendingVM.recordings.isEmpty {
                MeiliCard { emptyHint(MeiliIcons.warn, "调取失败", e) }
            } else if pendingVM.recordings.isEmpty {
                MeiliCard { emptyHint(MeiliIcons.tidy, "没有待整理的陪伴", "陪伴结束后，未绑定的片段会出现在这里") }
            } else {
                MeiliCard(tight: true) {
                    VStack(spacing: 0) {
                        ForEach(Array(pendingVM.pagedRecordings.enumerated()), id: \.offset) { idx, rec in
                            if idx > 0 { Rectangle().fill(MeiliColor.lineSoft).frame(height: 1) }
                            pendingRow(rec)
                        }
                    }
                }
                if pendingVM.totalPages > 1 {
                    HStack {
                        MeiliButton("上一页", kind: .ghost, size: .xs, enabled: pendingVM.page > 1) { pendingVM.goPage(-1) }
                        Text("第 \(pendingVM.page) / \(pendingVM.totalPages) 页")
                            .font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3).padding(.horizontal, 14)
                        MeiliButton("下一页", kind: .ghost, size: .xs, enabled: pendingVM.page < pendingVM.totalPages) { pendingVM.goPage(1) }
                    }
                    .frame(maxWidth: .infinity)
                }
            }
            // 对齐 android:待整理区底部满宽入口
            MeiliButton("从陪伴笔同步", kind: .ghost, icon: MeiliIcons.sync, block: true) {
                pendingVM.openPenSync()
            }
        }
    }

    private func pendingRow(_ rec: PendingRecording) -> some View {
        let (label, kind) = rec.pendingStatus
        return VStack(spacing: 6) {
            HStack(spacing: 10) {
                if rec.isProcessing {
                    ProgressView().tint(MeiliColor.clay).frame(width: 40, height: 40)
                } else {
                    pendingPlayButton(rec)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(rec.recordedAt?.nilIfBlank ?? "陪伴片段").font(MeiliFont.body).foregroundStyle(MeiliColor.ink).lineLimit(1)
                    HStack(spacing: 6) {
                        if let d = rec.durationLabel?.nilIfBlank {
                            Text(d).font(.sz(11)).foregroundStyle(MeiliColor.ink3)
                        }
                        StatusPill(text: label, kind: kind)
                    }
                }
                Spacer(minLength: 6)
                if rec.deletePending {
                    MeiliButton("撤回删除申请", kind: .ghost, size: .xs) { pendingVM.withdrawDelete(rec.id) }
                } else if !rec.isProcessing {
                    MeiliButton("删除", kind: .ghost, size: .xs) { deleteAsk = rec.id }
                    MeiliButton("绑定顾客", size: .xs, icon: MeiliIcons.link) { onBind(rec.id) }
                }
            }
            // 删除申请被拒:显示拒绝理由 + 知道了
            if rec.deleteRejected {
                HStack(spacing: 8) {
                    MeiliBanner(message: "删除申请被拒绝" + (rec.deleteRejectReason?.nilIfBlank.map { "：\($0)" } ?? ""), kind: .danger)
                    MeiliButton("知道了", kind: .ghost, size: .xs) { pendingVM.dismissReject(rec.id) }
                }
            }
            // 正在试听的这条:显示可拖动进度条,方便跳到后面听
            if playingRid == rec.id {
                AuditionScrubber(player: player)
            }
        }
        .padding(.vertical, 12)
    }

    private func pendingPlayButton(_ rec: PendingRecording) -> some View {
        let isThis = playingRid == rec.id && player.playing
        return Button {
            if isThis { player.toggle(); return }
            guard let url = rec.audioUrl?.nilIfBlank else { vm.toast = "暂无可试听的音频"; return }
            player.load(url); if !player.playing { player.toggle() }; playingRid = rec.id
        } label: {
            ZStack {
                Circle().fill(MeiliColor.clayTint).frame(width: 40, height: 40)
                if isThis {
                    HStack(spacing: 3) {
                        Capsule().fill(MeiliColor.clayDeep).frame(width: 3, height: 13)
                        Capsule().fill(MeiliColor.clayDeep).frame(width: 3, height: 13)
                    }
                } else {
                    MeiliIcon(MeiliIcons.play, size: 16).foregroundStyle(MeiliColor.clayDeep)
                }
            }
        }
        .buttonStyle(PressScaleButtonStyle())
    }

    // MARK: 日期切换

    private var dateNav: some View {
        HStack(spacing: 0) {
            navArrow(MeiliIcons.chevLeft, enabled: true) { vm.prevDay() }
            Button { showDatePicker = true } label: {
                VStack(spacing: 1) {
                    Text(vm.isToday ? "今天" : vm.date)
                        .font(MeiliFont.cardTitle).foregroundStyle(MeiliColor.ink)
                    Text(vm.isToday ? "点这里选其他日期" : "\(vm.date) · 点这里换日期")
                        .font(.sz(10.5, weight: .bold)).foregroundStyle(MeiliColor.clay)
                }
                .frame(maxWidth: .infinity)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            navArrow(MeiliIcons.chevRight, enabled: !vm.isToday) { vm.nextDay() }
        }
        .padding(.vertical, 8).padding(.horizontal, 6)
        .background(MeiliColor.surface)
        .clipShape(RoundedRectangle(cornerRadius: MeiliRadius.md, style: .continuous))
        .overlay { RoundedRectangle(cornerRadius: MeiliRadius.md, style: .continuous).strokeBorder(MeiliColor.line, lineWidth: 1) }
        .sheet(isPresented: $showDatePicker) { datePickerSheet }
    }

    private var datePickerSheet: some View {
        VStack(spacing: 14) {
            HStack {
                Text("选择接诊日期").font(MeiliFont.sheetH3).foregroundStyle(MeiliColor.ink)
                Spacer()
                Button { vm.goToday(); showDatePicker = false } label: {
                    Text("回到今天").font(.sz(13, weight: .bold)).foregroundStyle(MeiliColor.clayDeep)
                }
            }
            DatePicker("", selection: Binding(
                get: { DateHelper.date(from: vm.date) ?? Date() },
                set: { vm.selectDate($0); showDatePicker = false }
            ), in: ...Date(), displayedComponents: .date)
            .datePickerStyle(.graphical)
            .tint(MeiliColor.clay)
            .environment(\.locale, Locale(identifier: "zh_CN"))
            Spacer(minLength: 0)
        }
        .padding(MeiliMetric.cardPad)
        .presentationDetents([.medium, .large])
    }

    private func navArrow(_ icon: MeiliGlyph, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            MeiliIcon(icon, size: 20).foregroundStyle(enabled ? MeiliColor.ink2 : MeiliColor.ink4)
                .frame(width: 40, height: 40)
        }
        .buttonStyle(PressScaleButtonStyle(scale: 0.9))
        .disabled(!enabled)
    }

    // MARK: 列表

    @ViewBuilder private var content: some View {
        if vm.loading && vm.items.isEmpty {
            MeiliCard { loadingRow("正在调取今日接诊…") }
        } else if let e = vm.error, vm.items.isEmpty {
            MeiliCard { emptyHint(MeiliIcons.warn, "调取失败", e) }
        } else if vm.items.isEmpty {
            MeiliCard {
                emptyHint(MeiliIcons.reception, vm.isToday ? "今天还没有接诊顾客" : "这天没有接诊记录",
                          "点上方「加入今日接诊」把要接诊的顾客加进来")
            }
        } else {
            MeiliCard(tight: true) {
                VStack(spacing: 0) {
                    ForEach(Array(vm.items.enumerated()), id: \.offset) { idx, item in
                        if idx > 0 { Rectangle().fill(MeiliColor.lineSoft).frame(height: 1) }
                        row(item)
                    }
                }
            }
        }
    }

    private func row(_ item: TodayReception) -> some View {
        let name = item.name?.nilIfBlank ?? "未知顾客"
        let (label, kind) = item.statusInfo
        return Button {
            if let cid = item.customerId { onOpenPreview(cid, item.serviceDate ?? vm.date) }
        } label: {
            HStack(spacing: 12) {
                MeiliAvatar(name: name)
                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 7) {
                        Text(name).font(MeiliFont.rowTitle).foregroundStyle(MeiliColor.ink).lineLimit(1)
                        if let m = item.memberCard?.nilIfBlank {
                            Text(m).font(.sz(11)).foregroundStyle(MeiliColor.ink3).lineLimit(1)
                        }
                    }
                    HStack(spacing: 7) {
                        StatusPill(text: label, kind: kind)
                        if let rc = item.recordingCount, rc > 0 {
                            Text("\(rc) 段陪伴").font(.sz(11)).foregroundStyle(MeiliColor.ink3)
                        }
                    }
                }
                Spacer(minLength: 6)
                actionButton(item)
            }
            .padding(.vertical, 14)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .contextMenu {
            Button(role: .destructive) { vm.remove(item) } label: { Label("移出今日接诊", systemImage: "trash") }
        }
    }

    @ViewBuilder private func actionButton(_ item: TodayReception) -> some View {
        let cid = item.customerId
        let date = item.serviceDate ?? vm.date
        switch item.analysisStatus {
        case "done":
            if let sid = item.sessionId {
                MeiliButton("看报告", kind: .honey, size: .xs) { onOpenReport(sid) }
            }
        case "running", "queued":
            MeiliButton("看进度", kind: .soft, size: .xs) { if let cid { onOpenPreview(cid, date) } }
        default:
            if (item.recordingCount ?? 0) > 0 {
                MeiliButton("开始分析", size: .xs) { if let cid { onOpenPreview(cid, date) } }
            } else {
                MeiliButton("绑定", kind: .soft, size: .xs, icon: MeiliIcons.link) { if let cid { onOpenPreview(cid, date) } }
            }
        }
    }

    // MARK: 新增弹层

    private var addSheet: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("加入今日接诊").font(MeiliFont.sheetH3).foregroundStyle(MeiliColor.ink)

            Picker("", selection: $vm.addExistingMode) {
                Text("搜已有顾客").tag(true)
                Text("新增顾客").tag(false)
            }
            .pickerStyle(.segmented)

            if vm.addExistingMode {
                HStack(spacing: 8) {
                    MeiliIcon(MeiliIcons.search, size: 18).foregroundStyle(MeiliColor.ink3)
                    TextField("姓名 / 手机尾号 / 会员卡号",
                              text: Binding(get: { vm.addQuery }, set: { vm.setAddQuery($0) }))
                        .font(MeiliFont.bodyLarge).tint(MeiliColor.clay).autocorrectionDisabled()
                }
                .padding(.horizontal, 14).frame(height: 48)
                .background(MeiliColor.surfaceSoft).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))

                ScrollView {
                    VStack(spacing: 0) {
                        ForEach(Array(vm.addResults.enumerated()), id: \.offset) { idx, c in
                            if idx > 0 { Rectangle().fill(MeiliColor.lineSoft).frame(height: 1) }
                            Button { vm.addExisting(c) } label: {
                                HStack(spacing: 10) {
                                    MeiliAvatar(name: c.name ?? "?", size: 38)
                                    VStack(alignment: .leading, spacing: 1) {
                                        Text(c.name ?? "—").font(MeiliFont.body).foregroundStyle(MeiliColor.ink)
                                        Text(c.memberCard?.nilIfBlank ?? (c.phoneTail.map { "尾号\($0)" } ?? "新客"))
                                            .font(.sz(11)).foregroundStyle(MeiliColor.ink3)
                                    }
                                    Spacer()
                                    MeiliIcon(MeiliIcons.add, size: 18).foregroundStyle(MeiliColor.clay)
                                }
                                .padding(.vertical, 11).contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
                .frame(maxHeight: 240)
            } else {
                MeiliField(label: "姓名", text: $vm.newName, placeholder: "顾客姓名", icon: MeiliIcons.profile)
                MeiliField(label: "手机尾号", text: Binding(get: { vm.newPhoneTail }, set: { vm.setNewPhoneTail($0) }),
                           placeholder: "4 位数字", icon: MeiliIcons.phone, keyboard: .numberPad)
                MeiliField(label: "会员卡号（选填）", text: $vm.newMemberCard, placeholder: "会员卡号", icon: MeiliIcons.star)
                MeiliButton(vm.addSubmitting ? "提交中…" : "确认新增并加入", block: true, enabled: !vm.addSubmitting) { vm.addNew() }
            }

            if let e = vm.addError { MeiliBanner(message: e) }
            Spacer(minLength: 0)
        }
        .padding(MeiliMetric.cardPad)
        .frame(maxWidth: .infinity, alignment: .leading)
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }

    // MARK: 小件

    @ViewBuilder private var toastBar: some View {
        if let t = vm.toast {
            Text(t).font(MeiliFont.bodySm).foregroundStyle(.white)
                .padding(.horizontal, 16).padding(.vertical, 11)
                .background(MeiliColor.inkSurface).clipShape(Capsule())
                .padding(.bottom, MeiliMetric.bottomNavInset + 8)
                .transition(.move(edge: .bottom).combined(with: .opacity))
                .task { try? await Task.sleep(nanoseconds: 1_800_000_000); vm.toast = nil }
        }
    }

    private func loadingRow(_ text: String) -> some View {
        HStack(spacing: 10) { ProgressView().tint(MeiliColor.clay); Text(text).font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3) }
            .frame(maxWidth: .infinity).padding(.vertical, 22)
    }
    private func emptyHint(_ icon: MeiliGlyph, _ title: String, _ sub: String?) -> some View {
        VStack(spacing: 0) {
            MeiliIcon(icon, size: 30).foregroundStyle(MeiliColor.ink4)
                .frame(width: 58, height: 58).background(MeiliColor.surfaceSoft)
                .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
            Text(title).font(.sz(14.5, weight: .bold)).foregroundStyle(MeiliColor.ink2).padding(.top, 14)
            if let sub, !sub.isEmpty {
                Text(sub).font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3).multilineTextAlignment(.center).padding(.top, 6)
            }
        }
        .frame(maxWidth: .infinity).padding(.vertical, 36)
    }
}

// MARK: - 「从陪伴笔同步」弹层(android 对应 ReceptionScreen.PenSyncSheet)

struct PenSyncSheet: View {
    @ObservedObject var vm: PendingViewModel
    @State private var confirmImport = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("陪伴笔机身记录").font(MeiliFont.cardTitle).foregroundStyle(MeiliColor.ink)
            Text("以下是陪伴笔本地保存、尚未导入的片段，勾选后导入到「待整理」去绑定顾客。")
                .font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3)
            content
            Spacer(minLength: 0)
            MeiliButton(vm.syncSelectedCount > 0 ? "导入选中 (\(vm.syncSelectedCount))" : "导入选中",
                        icon: MeiliIcons.sync, block: true,
                        enabled: vm.syncSelectedCount > 0) { confirmImport = true }
        }
        .padding(18)
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .background(MeiliColor.bg)
        .alert("导入 \(vm.syncSelectedCount) 段到待整理", isPresented: $confirmImport) {
            Button("取消", role: .cancel) {}
            Button("确认导入") { vm.importSelected() }
        } message: {
            Text("将从陪伴笔导入选中的 \(vm.syncSelectedCount) 段到「待整理」，下载和上传会在后台进行。确认导入吗？")
        }
    }

    @ViewBuilder private var content: some View {
        if vm.syncUnavailable {
            emptyBox("未连接陪伴笔", "请在陪伴首页连接陪伴笔后再从机身同步")
        } else if vm.syncBusy {
            VStack(spacing: 8) {
                ProgressView().tint(MeiliColor.clay)
                Text("正在从陪伴笔同步中…").font(MeiliFont.body).foregroundStyle(MeiliColor.ink2)
                Text("传输期间笔无法列出机身记录，等待整理里的「后台同步中」完成后再来导入其他片段")
                    .font(.sz(11)).foregroundStyle(MeiliColor.ink4)
                    .multilineTextAlignment(.center)
            }
            .frame(maxWidth: .infinity).padding(.vertical, 30)
        } else if vm.syncLoading {
            HStack(spacing: 10) {
                ProgressView().tint(MeiliColor.clay)
                Text("正在读取陪伴笔机身记录…").font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3)
            }
            .frame(maxWidth: .infinity).padding(.vertical, 30)
        } else if vm.syncRows.isEmpty {
            emptyBox("暂无可导入的机身片段", "陪伴笔机身已无未导入的片段")
        } else {
            // 全选行
            Button { vm.toggleSyncAll() } label: {
                HStack(spacing: 10) {
                    checkCircle(vm.syncAllChecked)
                    Text("全选").font(MeiliFont.body).foregroundStyle(MeiliColor.ink)
                    Spacer()
                    Text("共 \(vm.syncRows.count) 段 · 陪伴笔脱机时本地保存")
                        .font(.sz(11)).foregroundStyle(MeiliColor.ink3)
                }
            }
            .buttonStyle(.plain)
            ScrollView {
                VStack(spacing: 0) {
                    // 按日期分组:点日期头一键勾选那一天
                    ForEach(vm.syncSections, id: \.date) { section in
                        Button { vm.toggleSyncDate(section.date) } label: {
                            HStack(spacing: 10) {
                                checkCircle(vm.dateAllChecked(section.date))
                                Text(section.date).font(.sz(13, weight: .bold)).foregroundStyle(MeiliColor.clayDeep)
                                Text("\(section.rows.count) 段").font(.sz(11)).foregroundStyle(MeiliColor.ink3)
                                Spacer()
                                Text("点击全选这天").font(.sz(10.5)).foregroundStyle(MeiliColor.ink4)
                            }
                            .padding(.vertical, 10)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .background(MeiliColor.surfaceSoft)

                        ForEach(section.rows) { row in
                            Button { vm.toggleSyncRow(row.id) } label: {
                                HStack(spacing: 10) {
                                    checkCircle(row.checked)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(row.title).font(MeiliFont.body).foregroundStyle(MeiliColor.ink).lineLimit(1)
                                        if !row.sizeLabel.isEmpty {
                                            Text(row.sizeLabel).font(.sz(11)).foregroundStyle(MeiliColor.ink3)
                                        }
                                    }
                                    Spacer()
                                    StatusPill(text: "陪伴笔", kind: .clay)
                                }
                                .padding(.vertical, 10)
                                .padding(.leading, 10)
                            }
                            .buttonStyle(.plain)
                            Rectangle().fill(MeiliColor.lineSoft).frame(height: 1)
                        }
                    }
                }
            }
        }
    }

    private func checkCircle(_ on: Bool) -> some View {
        ZStack {
            Circle()
                .strokeBorder(on ? MeiliColor.clay : MeiliColor.line, lineWidth: 1.6)
                .background(Circle().fill(on ? MeiliColor.clay : .clear))
                .frame(width: 22, height: 22)
            if on {
                MeiliIcon(MeiliIcons.check, size: 12).foregroundStyle(.white)
            }
        }
    }

    private func emptyBox(_ title: String, _ sub: String) -> some View {
        VStack(spacing: 6) {
            Text(title).font(MeiliFont.body).foregroundStyle(MeiliColor.ink2)
            Text(sub).font(.sz(11)).foregroundStyle(MeiliColor.ink4)
        }
        .frame(maxWidth: .infinity).padding(.vertical, 30)
    }
}
