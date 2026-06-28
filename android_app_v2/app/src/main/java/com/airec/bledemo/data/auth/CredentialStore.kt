package com.airec.bledemo.data.auth

import android.content.Context
import android.util.Base64
import com.airec.bledemo.data.net.NetworkModule

/**
 * 本地凭证存储：登录成功后保存账号密码，供「上传遇 401 自动重登」用。
 *
 * 安全权衡：B2B 工作 App（顾问端），为了"凭证失效后自动续登、顾问全程无感"，需要本地留存账号密码。
 * 这里用 XOR + Base64 做轻量混淆后存进应用私有 SharedPreferences（应用沙箱内，其它 App 读不到），
 * 不引 Tink/Keystore——后者在部分国产 ROM 上偶发崩溃，对录音可靠性是更大的风险。
 * 退出登录会清除。
 */
class CredentialStore(
    context: Context = NetworkModule.appContext,
) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 登录成功后保存（明文经混淆后落盘）。 */
    fun save(username: String, password: String) {
        prefs.edit()
            .putString(KEY_U, obfuscate(username))
            .putString(KEY_P, obfuscate(password))
            .apply()
    }

    fun username(): String? = prefs.getString(KEY_U, null)?.let { deobfuscate(it) }
    fun password(): String? = prefs.getString(KEY_P, null)?.let { deobfuscate(it) }

    /** 是否有可用于自动重登的完整凭证。 */
    fun hasCredentials(): Boolean = !username().isNullOrBlank() && !password().isNullOrBlank()

    /** 退出登录时清除。 */
    fun clear() {
        prefs.edit().remove(KEY_U).remove(KEY_P).apply()
    }

    private fun obfuscate(s: String): String {
        val raw = s.toByteArray(Charsets.UTF_8)
        for (i in raw.indices) raw[i] = (raw[i].toInt() xor MASK[i % MASK.length].code).toByte()
        return Base64.encodeToString(raw, Base64.NO_WRAP)
    }

    private fun deobfuscate(s: String): String? = try {
        val raw = Base64.decode(s, Base64.NO_WRAP)
        for (i in raw.indices) raw[i] = (raw[i].toInt() xor MASK[i % MASK.length].code).toByte()
        String(raw, Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val PREFS = "meili_cred"
        const val KEY_U = "u"
        const val KEY_P = "p"
        // 固定混淆密钥（非强加密，仅防一眼明文）。
        const val MASK = "meili-companion-2026"
    }
}
