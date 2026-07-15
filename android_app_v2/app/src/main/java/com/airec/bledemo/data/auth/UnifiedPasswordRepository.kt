package com.airec.bledemo.data.auth

import com.airec.bledemo.data.api.ConsultantApi
import com.airec.bledemo.data.chat.ChatModule
import com.airec.bledemo.data.chat.ChatRepository
import com.airec.bledemo.data.followup.FollowupModule
import com.airec.bledemo.data.followup.FollowupRepository
import com.airec.bledemo.data.followup.ScriptSystem
import com.airec.bledemo.data.net.NetworkModule
import com.airec.bledemo.data.repo.ApiResult
import com.airec.bledemo.data.teach.TeachModule
import com.airec.bledemo.data.teach.TeachRepository
import com.squareup.moshi.Types

/**
 * 统一密码修改(全系统同步)。
 *
 * 「统一密码」= 工牌(主账号)密码,开号时铺到 teach/回访/高情商/扣子。改密要一起改,
 * 否则各系统密码不一致 → 静默登录失败。KPI 是独立拼音账号,不参与统一密码。
 *
 * 流程(尽量减少"部分成功"不一致):
 *  1. 先改工牌(验旧密码)——失败即整体中止,其它系统一律不动;
 *  2. 工牌成功后,只对本账号**已开通**的系统(/api/me systems)逐个改,收集失败清单;
 *  3. 更新本地凭证为新密码 + 清各系统 token(下次用新密码静默登录)。
 * 子系统偶发失败不回滚(分布式无事务),但会明确告诉用户哪个没同步,可稍后重试。
 */
class UnifiedPasswordRepository(
    private val api: ConsultantApi = NetworkModule.api,
    private val creds: CredentialStore = CredentialStore(),
) {
    data class Result(
        val ok: Boolean,
        val message: String,
        val failedSystems: List<String> = emptyList(),
    )

    suspend fun changeAll(old: String, new: String): Result {
        val username = creds.username()?.takeIf { it.isNotBlank() }
            ?: return Result(false, "本机没有登录凭证,请退出后重新登录一次")
        if (new.length < 6) return Result(false, "新密码至少 6 位")
        if (new == old) return Result(false, "新密码不能与原密码相同")

        // 1) 工牌主账号(验旧密码)
        val gp = try {
            api.changePassword(mapOf("old_password" to old, "new_password" to new))
        } catch (_: Exception) {
            return Result(false, "网络异常,请重试")
        }
        if (!gp.isSuccessful) {
            return Result(false, gpError(gp) ?: "原密码不正确")
        }

        // 2) 已开通的子系统逐个同步
        val me = AuthManager.lastMe
        val enabled = me?.systems?.mapNotNull { it.key }?.toSet().orEmpty()
        // 绑定了独立 staff 账号的人:回访/高情商用 staff 密码(与工牌密码无关),改密不碰它们
        val hasScriptBinding = !me?.scriptAccount.isNullOrBlank()
        val failed = mutableListOf<String>()
        suspend fun sync(key: String, name: String, block: suspend () -> ApiResult<*>) {
            if (key !in enabled) return
            if (block() is ApiResult.Failure) failed.add(name)
        }
        sync("teach", "美业网课") { TeachRepository().changePassword(old, new) }
        if (!hasScriptBinding) {
            sync("followup", "回访话术") { FollowupRepository(ScriptSystem.Followup).changePassword(old, new) }
            sync("higheq", "高情商话术") { FollowupRepository(ScriptSystem.HighEq).changePassword(old, new) }
        }
        sync("chat", "销售话术") { ChatRepository().changePassword(old, new) }

        // 3) 本地凭证更新为新密码 + 清各系统会话(强制用新密码重登)
        runCatching { creds.save(username, new) }
        runCatching { TeachModule.clear(); FollowupModule.clear(); ChatModule.clear() }

        return if (failed.isEmpty()) {
            Result(true, "密码已修改,全部系统已同步")
        } else {
            Result(true, "工牌密码已改,但这些系统暂未同步:${failed.joinToString("、")}(稍后重试即可)", failed)
        }
    }

    private fun gpError(resp: retrofit2.Response<*>): String? = try {
        val raw = resp.errorBody()?.string()
        if (raw.isNullOrBlank()) null
        else NetworkModule.moshi
            .adapter<Map<String, Any?>>(
                Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java),
            )
            .lenient()
            .fromJson(raw)?.get("error") as? String
    } catch (_: Exception) {
        null
    }
}
