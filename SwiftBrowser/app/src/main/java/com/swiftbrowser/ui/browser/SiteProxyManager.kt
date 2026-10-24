package com.swiftbrowser.ui.browser

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.net.InetSocketAddress
import java.net.Socket

/** 一条站点代理规则。pattern 支持 host、完整 URL，或 *.site.com 形式的通配符。 */
data class SiteProxyRule(
    val pattern: String,
    val mode: String
)

/**
 * 站点级「直连 / 代理」设置。
 *
 * Android WebView 没有 per-request 的代理选择能力，这里用进程级的
 * [ProxyController] + bypass 规则来表达「全局默认 + 按规则覆盖」：
 *
 * - 全局代理：普通 bypass 列表 = 设为直连的规则，未命中的域名走代理。
 * - 全局直连：reverse bypass 列表 = 设为代理的规则，未命中的域名直连。
 *
 * 规则按 host 匹配，不是按标签页：同一域名的所有标签页共享设置。
 * 规则里的 * 是通配符；*.site.com 同时匹配 site.com 和所有子域名。
 */
class SiteProxyManager(context: Context) {

    companion object {
        const val MODE_DIRECT = "direct"
        const val MODE_PROXY = "proxy"

        /** A_CM_FS 的默认 mixed-port；用户可在站点代理设置里改成 33000 等。 */
        const val DEFAULT_PROXY_URL = "http://127.0.0.1:7890"

        private const val PREF_NAME = "browser_prefs"
        private const val KEY_GLOBAL = "site_proxy_global"
        private const val KEY_RULES = "site_proxy_rules"
        private const val KEY_PROXY_URL = "site_proxy_url"
        private const val KEY_VPN_HINT_SHOWN = "site_proxy_vpn_hint_shown"
        // 旧版本按精确 host 存的两个集合，仅在首次读取时迁移
        private const val KEY_LEGACY_DIRECT_HOSTS = "site_proxy_direct_hosts"
        private const val KEY_LEGACY_PROXY_HOSTS = "site_proxy_proxy_hosts"

        /** 小写、去掉结尾的点；不剥 www.，规则匹配以真实 host 为准。 */
        fun normalizeHost(host: String?): String? {
            var value = host?.trim()?.lowercase() ?: return null
            while (value.endsWith(".")) value = value.dropLast(1)
            return value.ifEmpty { null }
        }

        /** 代理地址规范化：没写 scheme 时补 http://；只保留 scheme://host:port。 */
        fun normalizeProxyUrl(input: String?): String? {
            val raw = input?.trim().orEmpty()
            if (raw.isEmpty()) return null
            val withScheme = if (raw.contains("://")) raw else "http://$raw"
            val uri = Uri.parse(withScheme)
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return null
            val host = uri.host
            if (host.isNullOrBlank()) return null
            if (uri.port !in 1..65535) return null
            val hostPart = if (host.contains(":")) "[$host]" else host
            return "$scheme://$hostPart:${uri.port}"
        }

        /**
         * 规范化规则输入：
         * - 完整 URL / 带路径的网址 -> 取 host
         * - 纯 host -> 原样（小写）
         * - 含 * 的通配规则 -> 保留，只允许 host 级别（不能带 scheme、路径、空格）
         */
        fun normalizePattern(input: String?): String? {
            val raw = input?.trim()?.lowercase() ?: return null
            if (raw.isEmpty()) return null
            if (raw.contains("*")) {
                if (raw.contains("://") || raw.contains("/") || raw.any { it.isWhitespace() }) return null
                if (raw == "*" || raw.replace("*", "").isEmpty()) return null
                return raw
            }
            val withScheme = if (raw.contains("://")) raw else "http://$raw"
            val uri = Uri.parse(withScheme)
            return normalizeHost(uri.host)
        }

        /** host 是否命中规则。*.site.com 同时匹配 site.com 和所有子域名。 */
        fun matches(pattern: String, host: String?): Boolean {
            val p = pattern.trim().lowercase()
            val h = normalizeHost(host) ?: return false
            if (p.isEmpty()) return false
            if (p.startsWith("*.")) {
                val suffix = p.substring(2)
                if (suffix.isEmpty()) return false
                return h == suffix || h.endsWith(".$suffix")
            }
            if (!p.contains("*")) return h == p
            return globRegex(p).matches(h)
        }

        /** 两条规则是否可能命中同一个 host（只覆盖常见的精确/后缀形式，用于提示冲突）。 */
        fun patternsOverlap(a: String, b: String): Boolean {
            val x = a.trim().lowercase()
            val y = b.trim().lowercase()
            if (x == y) return true
            val xWildcard = x.startsWith("*.")
            val yWildcard = y.startsWith("*.")
            val xPlain = !x.contains("*")
            val yPlain = !y.contains("*")
            if (xWildcard && yWildcard) {
                val sx = x.substring(2)
                val sy = y.substring(2)
                return sx.isNotEmpty() && sy.isNotEmpty() &&
                    (sx == sy || sx.endsWith(".$sy") || sy.endsWith(".$sx"))
            }
            if (xWildcard && yPlain) {
                val sx = x.substring(2)
                return sx.isNotEmpty() && (y == sx || y.endsWith(".$sx"))
            }
            if (yWildcard && xPlain) {
                val sy = y.substring(2)
                return sy.isNotEmpty() && (x == sy || x.endsWith(".$sy"))
            }
            return false
        }

        private fun globRegex(pattern: String): Regex {
            val sb = StringBuilder("^")
            for (c in pattern) {
                when (c) {
                    '*' -> sb.append(".*")
                    '.', '\\', '+', '?', '(', ')', '[', ']', '{', '}', '^', '$', '|' -> {
                        sb.append('\\').append(c)
                    }
                    else -> sb.append(c)
                }
            }
            sb.append('$')
            return Regex(sb.toString())
        }

        /** 越具体的规则优先级越高：精确规则 > 通配规则，字面量更长的更具体。 */
        private fun specificity(pattern: String): Int {
            val wildcards = pattern.count { it == '*' }
            return if (wildcards == 0) {
                Int.MAX_VALUE - pattern.length
            } else {
                pattern.replace("*", "").length
            }
        }
    }

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    var globalMode: String
        get() = prefs.getString(KEY_GLOBAL, MODE_DIRECT) ?: MODE_DIRECT
        set(value) {
            prefs.edit().putString(KEY_GLOBAL, value).apply()
        }

    var proxyUrl: String
        get() = prefs.getString(KEY_PROXY_URL, DEFAULT_PROXY_URL) ?: DEFAULT_PROXY_URL
        set(value) {
            prefs.edit().putString(KEY_PROXY_URL, value).apply()
        }

    var vpnHintShown: Boolean
        get() = prefs.getBoolean(KEY_VPN_HINT_SHOWN, false)
        set(value) {
            prefs.edit().putBoolean(KEY_VPN_HINT_SHOWN, value).apply()
        }

    /** 读取全部规则；首次读取时把旧版的两个 host 集合迁移过来。 */
    fun rules(): List<SiteProxyRule> {
        val raw = prefs.getString(KEY_RULES, null)
        if (raw != null) {
            return try {
                val arr = org.json.JSONArray(raw)
                val result = mutableListOf<SiteProxyRule>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val pattern = obj.optString("pattern").trim()
                    val mode = obj.optString("mode")
                    if (pattern.isNotEmpty() && (mode == MODE_DIRECT || mode == MODE_PROXY)) {
                        result.add(SiteProxyRule(pattern, mode))
                    }
                }
                result
            } catch (e: Exception) {
                emptyList()
            }
        }

        val legacyDirect = prefs.getStringSet(KEY_LEGACY_DIRECT_HOSTS, emptySet()) ?: emptySet()
        val legacyProxy = prefs.getStringSet(KEY_LEGACY_PROXY_HOSTS, emptySet()) ?: emptySet()
        val migrated = mutableListOf<SiteProxyRule>()
        legacyDirect.forEach { migrated.add(SiteProxyRule(it, MODE_DIRECT)) }
        legacyProxy.forEach { migrated.add(SiteProxyRule(it, MODE_PROXY)) }
        saveRules(migrated)
        prefs.edit()
            .remove(KEY_LEGACY_DIRECT_HOSTS)
            .remove(KEY_LEGACY_PROXY_HOSTS)
            .apply()
        return migrated
    }

    fun saveRules(list: List<SiteProxyRule>) {
        val arr = org.json.JSONArray()
        list.forEach { rule ->
            val obj = org.json.JSONObject()
            obj.put("pattern", rule.pattern)
            obj.put("mode", rule.mode)
            arr.put(obj)
        }
        prefs.edit().putString(KEY_RULES, arr.toString()).apply()
    }

    /** 添加或覆盖同 pattern 的规则，返回规范化后的 pattern；非法输入返回 null。 */
    fun addOrUpdateRule(rawPattern: String, mode: String): String? {
        val pattern = normalizePattern(rawPattern) ?: return null
        val list = rules().toMutableList()
        val index = list.indexOfFirst { it.pattern == pattern }
        if (index >= 0) {
            list[index] = SiteProxyRule(pattern, mode)
        } else {
            list.add(SiteProxyRule(pattern, mode))
        }
        saveRules(list)
        return pattern
    }

    /** 编辑已有规则：允许改名；改名后与已有规则重复时合并。非法输入返回 null。 */
    fun updateRule(oldPattern: String, rawPattern: String, mode: String): String? {
        val pattern = normalizePattern(rawPattern) ?: return null
        val list = rules().toMutableList()
        list.removeAll { it.pattern == oldPattern }
        val index = list.indexOfFirst { it.pattern == pattern }
        if (index >= 0) {
            list[index] = SiteProxyRule(pattern, mode)
        } else {
            list.add(SiteProxyRule(pattern, mode))
        }
        saveRules(list)
        return pattern
    }

    fun deleteRule(pattern: String) {
        saveRules(rules().filterNot { it.pattern == pattern })
    }

    /** 与 newPattern 重叠、且模式不同的已有规则，用于保存时提示。 */
    fun ruleConflicts(newPattern: String, mode: String): List<SiteProxyRule> {
        val pattern = normalizePattern(newPattern) ?: return emptyList()
        return rules().filter {
            it.pattern != pattern && it.mode != mode && patternsOverlap(it.pattern, pattern)
        }
    }

    /** 当前 host 实际生效的模式：命中规则里最具体的一条，否则用全局。 */
    fun effectiveMode(rawHost: String?): String {
        val host = normalizeHost(rawHost) ?: return globalMode
        val matching = rules().withIndex().filter { matches(it.value.pattern, host) }
        val best = matching.maxWithOrNull(
            compareBy<IndexedValue<SiteProxyRule>> { specificity(it.value.pattern) }
                .thenBy { it.index }
        )
        return best?.value?.mode ?: globalMode
    }

    /**
     * 当前设备/WebView 是否支持所需的代理覆盖能力。
     * 全局直连模式依赖 reverse bypass，旧 WebView 上不可用。
     */
    fun isSupported(): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) return false
        if (globalMode == MODE_DIRECT &&
            !WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE_REVERSE_BYPASS)
        ) {
            return false
        }
        return true
    }

    fun buildProxyConfig(): ProxyConfig {
        val url = normalizeProxyUrl(proxyUrl) ?: DEFAULT_PROXY_URL
        val builder = ProxyConfig.Builder().addProxyRule(url)
        if (globalMode == MODE_PROXY) {
            // 普通 bypass：设为直连的规则生效，其余走代理
            rules().filter { it.mode == MODE_DIRECT }.forEach { addPatternBypass(builder, it.pattern) }
        } else {
            // 反向 bypass：设为代理的规则生效，其余直连
            rules().filter { it.mode == MODE_PROXY }.forEach { addPatternBypass(builder, it.pattern) }
            builder.setReverseBypassEnabled(true)
        }
        return builder.build()
    }

    private fun addPatternBypass(builder: ProxyConfig.Builder, pattern: String) {
        if (pattern.startsWith("*.")) {
            val suffix = pattern.substring(2)
            if (suffix.isNotEmpty()) {
                // *.site.com 也要包含裸域名 site.com
                builder.addBypassRule(suffix)
                builder.addBypassRule("*.$suffix")
            }
        } else {
            builder.addBypassRule(pattern)
        }
    }

    /**
     * 把当前设置应用到所有 WebView。回调始终在主线程，[onApplied] 的参数表示是否成功。
     */
    fun apply(onApplied: (Boolean) -> Unit) {
        if (!isSupported()) {
            onApplied(false)
            return
        }
        try {
            ProxyController.getInstance().setProxyOverride(
                buildProxyConfig(),
                ContextCompat.getMainExecutor(appContext)
            ) {
                onApplied(true)
            }
        } catch (e: Exception) {
            onApplied(false)
        }
    }

    /** 本应用的默认网络是否仍被 VPN 接管。 */
    fun isAppInVpn(): Boolean {
        return try {
            val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 快速检查代理端口是否可连接。失败只用于提示，不阻止用户保存/切换。
     * 回调在主线程。
     */
    fun checkProxyReachable(onResult: (Boolean) -> Unit) {
        val uri = Uri.parse(normalizeProxyUrl(proxyUrl) ?: DEFAULT_PROXY_URL)
        val host = uri.host ?: run {
            onResult(false)
            return
        }
        val port = uri.port
        Thread {
            val ok = try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), 300)
                }
                true
            } catch (e: Exception) {
                false
            }
            Handler(Looper.getMainLooper()).post { onResult(ok) }
        }.start()
    }
}
