package com.airec.bledemo.data.net

import android.content.Context
import android.content.SharedPreferences
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * 用 SharedPreferences 持久化 Cookie 的 OkHttp CookieJar（自包含，不引第三方）。
 *
 * - 登录 POST /login 成功后，服务端 Set-Cookie(session) 会被这里 saveFromResponse 落盘；
 *   App 杀掉重启后 loadForRequest 仍能带上会话，无需重新登录。
 * - 引擎层(Uploader.java 等)原来从 WebView CookieManager 取 Cookie，
 *   集成阶段改用本 jar 承载的会话（见 SPEC §5）。
 *
 * 序列化：每条 cookie 存为一行 `name\tvalue\texpiresAt\tdomain\tpath\tsecure\thttpOnly\thostOnly\tpersistent`，
 * 整体用 \n 拼接存到一个 String key。简单稳妥，避免依赖任何 JSON 库。
 */
class PrefsCookieJar(context: Context) : CookieJar {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 内存缓存：host -> (cookieKey -> Cookie)，cookieKey = name|domain|path。 */
    private val cache: MutableMap<String, MutableMap<String, Cookie>> = HashMap()

    init {
        loadAll()
    }

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val host = url.host
        val map = cache.getOrPut(host) { HashMap() }
        val now = System.currentTimeMillis()
        for (c in cookies) {
            val key = cookieKey(c)
            // 已过期 / 被服务端置空 → 删除
            if (c.expiresAt <= now) {
                map.remove(key)
            } else {
                map[key] = c
            }
        }
        // 清掉本 host 下已过期的
        val it = map.values.iterator()
        while (it.hasNext()) {
            if (it.next().expiresAt <= now) it.remove()
        }
        if (map.isEmpty()) cache.remove(host)
        persistHost(host)
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val result = ArrayList<Cookie>()
        // 跨所有 host：凡是 matches(url) 的 cookie 都带上（同站子域/路径）
        val expiredHosts = HashSet<String>()
        for ((host, map) in cache) {
            val expired = ArrayList<String>()
            for ((k, c) in map) {
                if (c.expiresAt <= now) {
                    expired.add(k)
                } else if (c.matches(url)) {
                    result.add(c)
                }
            }
            if (expired.isNotEmpty()) {
                expired.forEach { map.remove(it) }
                expiredHosts.add(host)
            }
        }
        expiredHosts.forEach { persistHost(it) }
        return result
    }

    /**
     * 取某 url 当前应携带的 Cookie 头串（"name=value; name=value"），与 WebView
     * CookieManager.getCookie 同格式。录音引擎（PhoneMicService / PenController）上传音频时要这个串；
     * 登录后由 [com.airec.bledemo.recording.RecordingModule] 注入给引擎。
     */
    fun cookieHeader(url: HttpUrl): String =
        loadForRequest(url).joinToString("; ") { "${it.name}=${it.value}" }

    /** 退出登录：清空所有持久化 Cookie。 */
    @Synchronized
    fun clear() {
        cache.clear()
        prefs.edit().clear().apply()
    }

    /** 是否存在尚未过期、且属于会话的 cookie（用于粗判是否登录）。 */
    @Synchronized
    fun hasSessionCookie(): Boolean {
        val now = System.currentTimeMillis()
        return cache.values.any { m -> m.values.any { it.expiresAt > now } }
    }

    // ───────────── 持久化 ─────────────

    private fun cookieKey(c: Cookie): String = "${c.name}|${c.domain}|${c.path}"

    private fun hostPrefKey(host: String): String = "$KEY_PREFIX$host"

    private fun persistHost(host: String) {
        val map = cache[host]
        if (map.isNullOrEmpty()) {
            prefs.edit().remove(hostPrefKey(host)).apply()
            removeHostFromIndex(host)
            return
        }
        val sb = StringBuilder()
        for (c in map.values) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(serialize(c))
        }
        prefs.edit().putString(hostPrefKey(host), sb.toString()).apply()
        addHostToIndex(host)
    }

    private fun loadAll() {
        val hosts = prefs.getStringSet(KEY_INDEX, emptySet()) ?: emptySet()
        val now = System.currentTimeMillis()
        for (host in hosts) {
            val raw = prefs.getString(hostPrefKey(host), null) ?: continue
            val map = HashMap<String, Cookie>()
            for (line in raw.split('\n')) {
                if (line.isBlank()) continue
                val c = deserialize(line) ?: continue
                if (c.expiresAt > now) map[cookieKey(c)] = c
            }
            if (map.isNotEmpty()) cache[host] = map
        }
    }

    private fun addHostToIndex(host: String) {
        val cur = HashSet(prefs.getStringSet(KEY_INDEX, emptySet()) ?: emptySet())
        if (cur.add(host)) prefs.edit().putStringSet(KEY_INDEX, cur).apply()
    }

    private fun removeHostFromIndex(host: String) {
        val cur = HashSet(prefs.getStringSet(KEY_INDEX, emptySet()) ?: emptySet())
        if (cur.remove(host)) prefs.edit().putStringSet(KEY_INDEX, cur).apply()
    }

    private fun serialize(c: Cookie): String = listOf(
        c.name, c.value, c.expiresAt.toString(), c.domain, c.path,
        c.secure.toString(), c.httpOnly.toString(), c.hostOnly.toString(), c.persistent.toString(),
    ).joinToString("\t") { it.replace("\t", " ").replace("\n", " ") }

    private fun deserialize(line: String): Cookie? {
        val p = line.split('\t')
        if (p.size < 9) return null
        return try {
            val builder = Cookie.Builder()
                .name(p[0])
                .value(p[1])
                .expiresAt(p[2].toLong())
                .path(p[4])
            // hostOnly → domain() 严格匹配；否则 domain(...) 允许子域
            if (p[7].toBoolean()) builder.hostOnlyDomain(p[3]) else builder.domain(p[3])
            if (p[5].toBoolean()) builder.secure()
            if (p[6].toBoolean()) builder.httpOnly()
            builder.build()
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val PREFS_NAME = "companion_cookies"
        private const val KEY_PREFIX = "ck_"
        private const val KEY_INDEX = "ck_hosts"
    }
}
