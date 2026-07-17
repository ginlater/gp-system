import Foundation

/// 统一密码修改(全系统同步)。android 端对应 `data/auth/UnifiedPasswordRepository.kt`。
///
/// 「统一密码」= 工牌(主账号)密码,开号时铺到 teach/回访/高情商/扣子。改密要一起改,
/// 否则各系统密码不一致 → 静默登录失败。KPI 是独立拼音账号,不参与统一密码。
///
/// 流程(尽量减少"部分成功"不一致):
///  1. 先改工牌(验旧密码)——失败即整体中止,其它系统一律不动;
///  2. 工牌成功后,只对本账号**已开通**的系统(/api/me systems)逐个改,收集失败清单;
///  3. 更新本地凭证为新密码 + 清各系统 token(下次用新密码静默登录)。
/// 子系统偶发失败不回滚(分布式无事务),但会明确告诉用户哪个没同步,可稍后重试。
struct UnifiedPasswordRepository {
    struct Outcome {
        let ok: Bool
        let message: String
        var failedSystems: [String] = []
    }

    func changeAll(old: String, new: String) async -> Outcome {
        guard let cred = CredentialStore.load() else {
            return Outcome(ok: false, message: "本机没有登录凭证,请退出后重新登录一次")
        }
        if new.count < 6 { return Outcome(ok: false, message: "新密码至少 6 位") }
        if new == old { return Outcome(ok: false, message: "新密码不能与原密码相同") }

        // 1) 工牌主账号(验旧密码)
        do {
            let _: SimpleResult = try await APIClient.shared.postJSON(
                "api/consultant/change-password",
                body: ["old_password": old, "new_password": new])
        } catch let e as APIError {
            return Outcome(ok: false, message: e.errorDescription ?? "原密码不正确")
        } catch {
            return Outcome(ok: false, message: "网络异常,请重试")
        }

        // 2) 已开通的子系统逐个同步
        let me = AuthManager.lastMe
        let enabled = Set((me?.systems ?? []).compactMap(\.key))
        // 绑定了独立 staff 账号的人:回访/高情商用 staff 密码(与工牌密码无关),改密不碰它们
        let hasScriptBinding = !(me?.scriptAccount?.nilIfBlank == nil)
        var failed: [String] = []

        if enabled.contains("teach") {
            do { try await TeachRepository().changePassword(old: old, new: new) }
            catch { failed.append("美业网课") }
        }
        if !hasScriptBinding {
            if enabled.contains("followup") {
                do { try await FollowupRepository(sys: .followup).changePassword(old: old, new: new) }
                catch { failed.append("回访话术") }
            }
            if enabled.contains("higheq") {
                do { try await FollowupRepository(sys: .higheq).changePassword(old: old, new: new) }
                catch { failed.append("高情商话术") }
            }
        }
        if enabled.contains("chat") {
            do { try await ChatRepository().changePassword(old: old, new: new) }
            catch { failed.append("销售话术") }
        }

        // 3) 本地凭证更新为新密码 + 清各系统会话(强制用新密码重登)
        CredentialStore.save(username: cred.u, password: new)
        await TeachAuth.shared.clear()
        await ScriptAuth.of(.followup).clear()
        await ScriptAuth.of(.higheq).clear()
        await ChatAuth.shared.clear()

        if failed.isEmpty {
            return Outcome(ok: true, message: "密码已修改,全部系统已同步")
        }
        return Outcome(ok: true,
                       message: "工牌密码已改,但这些系统暂未同步:\(failed.joined(separator: "、"))(稍后重试即可)",
                       failedSystems: failed)
    }
}
