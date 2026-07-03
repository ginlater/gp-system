import SwiftUI

/// 绑定顾客(SPEC §4.4)。android 端对应 `ui/bind/BindCustomerViewModel.kt` 的 Binding 模式。
/// 待整理片段 → 绑定到顾客:候选(当日接诊名单 + 搜索本人接待过的顾客) / 新增当日顾客。
/// 换绑/退回/删除申请(Rebinding 模式)后续补。
@MainActor
final class BindCustomerViewModel: ObservableObject {
    let recordingId: Int

    struct Pick: Identifiable {
        let customerId: Int
        let name: String
        var phoneTail: String? = nil
        var memberCard: String? = nil
        var inDay: Bool = false
        var boundCount: Int = 0
        var locked: Bool = false
        var justCreated: Bool = false
        var id: Int { customerId }
    }

    @Published var existingTab = true
    @Published var serviceDate: String?
    @Published var recLabel: String?
    @Published var query = ""
    @Published var picks: [Pick] = []
    @Published var searching = false
    @Published var newName = ""
    @Published var newPhoneTail = ""
    @Published var submitting = false
    @Published var error: String?
    @Published var toast: String?
    @Published var pendingPick: Pick?          // 确认绑定弹窗
    @Published var boundCustomer: (Int, String)?   // (customerId, date) 成功 → 跳预览

    private var loaded = false
    private var searchTask: Task<Void, Never>?

    init(recordingId: Int) { self.recordingId = recordingId }

    var canSubmitNew: Bool { !newName.trimmingCharacters(in: .whitespaces).isEmpty && newPhoneTail.count == 4 && !submitting }

    func onAppear() { guard !loaded else { return }; loaded = true; boot() }

    private func boot() {
        Task {
            if let rec = (try? await ConsultantRepo.pending())?.first(where: { $0.id == recordingId }) {
                serviceDate = rec.serviceDate ?? serviceDate
                recLabel = [rec.recordedAt?.nilIfBlank, rec.durationLabel?.nilIfBlank].compactMap { $0 }.joined(separator: " · ").nilIfBlank
            }
            loadPicks()
        }
    }

    func onQueryChange(_ q: String) {
        query = q
        searchTask?.cancel()
        searchTask = Task {
            try? await Task.sleep(nanoseconds: 280_000_000)
            if Task.isCancelled { return }
            loadPicks()
        }
    }

    func loadPicks() {
        Task {
            searching = true
            let q = query.trimmingCharacters(in: .whitespaces).nilIfBlank
            let cand = try? await ConsultantRepo.rebindCandidates(rid: recordingId, q: q)
            let candidates = cand?.items ?? []
            let svc = cand?.serviceDate ?? serviceDate
            // 无关键词时叠加当日接诊名单(带已绑段数/锁定)
            var reception: [TodayReception] = []
            if q == nil {
                reception = (try? await ConsultantRepo.todayReception(svc))?.items ?? []
            }
            let inDayIds = Set(candidates.filter { $0.inDay == true }.compactMap { $0.cid })
            var seen = Set<Int>()
            var merged: [Pick] = []
            for r in reception {
                guard let cid = r.customerId else { continue }
                seen.insert(cid)
                merged.append(Pick(customerId: cid, name: r.name ?? "未命名顾客", phoneTail: r.phoneTail,
                                   memberCard: r.memberCard, inDay: true, boundCount: r.recordingCount ?? 0, locked: r.locked == true))
            }
            for c in candidates {
                guard let cid = c.cid, !seen.contains(cid) else { continue }
                seen.insert(cid)
                merged.append(Pick(customerId: cid, name: c.name ?? "未命名顾客", phoneTail: c.phoneTail,
                                   memberCard: c.memberCard, inDay: inDayIds.contains(cid) || c.inDay == true))
            }
            picks = merged
            serviceDate = svc ?? serviceDate
            searching = false
        }
    }

    func pick(_ p: Pick) {
        if p.locked { error = "「\(p.name)」的接诊包已锁定，无法再加入"; return }
        pendingPick = p
    }

    func confirmBind() {
        guard let p = pendingPick, !submitting else { return }
        submitting = true; error = nil
        Task {
            do {
                if !p.inDay {
                    _ = try await ConsultantRepo.addTodayReception(customerId: p.customerId, date: serviceDate)
                }
                let r = try await ConsultantRepo.bind(recordingId: recordingId, customerId: p.customerId)
                submitting = false; pendingPick = nil
                if let e = r.error { error = e; return }
                toast = "已绑定到\(p.name)"
                boundCustomer = (p.customerId, serviceDate ?? "")
            } catch let err {
                submitting = false; pendingPick = nil
                error = (err as? APIError)?.errorDescription ?? "绑定失败"
            }
        }
    }

    func setNewPhoneTail(_ v: String) { newPhoneTail = String(v.filter(\.isNumber).prefix(4)) }

    func submitNew() {
        guard canSubmitNew else { return }
        submitting = true; error = nil
        let name = newName.trimmingCharacters(in: .whitespaces)
        Task {
            do {
                let r = try await ConsultantRepo.addDayCustomer(rid: recordingId, name: name, phoneTail: newPhoneTail.nilIfBlank)
                submitting = false
                guard let cid = r.customerId else { error = "新增成功但未返回顾客，请重试"; return }
                let np = Pick(customerId: cid, name: r.name ?? name, phoneTail: newPhoneTail.nilIfBlank, inDay: true, justCreated: true)
                existingTab = true; newName = ""; newPhoneTail = ""
                picks = [np] + picks.filter { $0.customerId != cid }
                toast = "已新增「\(np.name)」并补登当天接诊，已替您选中"
            } catch let err {
                submitting = false
                error = (err as? APIError)?.errorDescription ?? "新增失败"
            }
        }
    }
}
