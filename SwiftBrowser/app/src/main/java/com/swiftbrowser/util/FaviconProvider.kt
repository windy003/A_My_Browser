package com.swiftbrowser.util

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.LruCache
import android.widget.ImageView
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.graphics.drawable.BitmapDrawable
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.signature.ObjectKey
import com.swiftbrowser.R
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.regex.Pattern

/**
 * 网站图标获取工具，按以下优先级加载：
 * 1. 用户手动设置的 iconUrl
 * 2. Brandfetch CDN（品牌级高清 Logo）
 * 3. 网站 <link> 标签（apple-touch-icon > icon，分辨率从高到低）
 * 4. DuckDuckGo 图标服务
 * 5. Google Favicon 服务
 * 6. 默认图标
 */
object FaviconProvider {

    /**
     * 缓存已解析的网站 link 标签图标地址，避免重复请求
     * key = pageKey（域名+路径目录）, value = 按分辨率从高到低排列的图标 URL 列表
     */
    private val linkIconCache = LruCache<String, List<String>>(200)

    /** 缓存每个页面最终成功加载的图标 URL，重启后直接用，无需重跑降级链 */
    private val resolvedIconCache = LruCache<String, String>(200)

    /**
     * 本轮「强制联网刷新」还未消费的 pageKey 集合。
     * 用户点刷新按钮时由 [beginForceRefresh] 填充；每个图标联网加载一次后即从集合移除，
     * 避免刷新后滚动列表时重复发起网络请求。平时（集合为空）所有加载都只读缓存、不联网。
     * 仅在主线程访问。
     */
    private val pendingRefreshKeys = HashSet<String>()

    private const val PREFS_NAME = "favicon_link_cache"
    private const val PREFS_RESOLVED = "favicon_resolved_cache"
    private const val SEPARATOR = "\u001F" // 单元分隔符，用于拼接 URL 列表
    // pageKey schema 版本。pageKey 计算规则变更时把这里 +1，
    // init 会一次性清掉用旧规则生成的脏缓存。
    private const val CACHE_SCHEMA_VERSION = 2
    private const val SCHEMA_VERSION_KEY = "__schema_version__"
    private var prefs: SharedPreferences? = null
    private var resolvedPrefs: SharedPreferences? = null

    /**
     * 图标图片持久化目录，位于 filesDir（不是 cacheDir），不会被系统当缓存清理。
     * 快速拨号图标下载成功后存成 PNG，平时直接从这里读取，保证离线、快速、稳定。
     */
    private var iconDir: File? = null

    /**
     * 初始化持久化缓存，在 Application.onCreate 中调用
     */
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        resolvedPrefs = context.getSharedPreferences(PREFS_RESOLVED, Context.MODE_PRIVATE)
        iconDir = File(context.filesDir, "speeddial_icons").apply { if (!exists()) mkdirs() }

        // pageKey schema 变更时一次性清理旧缓存，避免老用户继续读到错误的图标
        val storedVersion = prefs?.getInt(SCHEMA_VERSION_KEY, 0) ?: 0
        if (storedVersion != CACHE_SCHEMA_VERSION) {
            prefs?.edit()?.clear()?.putInt(SCHEMA_VERSION_KEY, CACHE_SCHEMA_VERSION)?.apply()
            resolvedPrefs?.edit()?.clear()?.apply()
            return
        }

        // 从 SharedPreferences 恢复到内存缓存
        prefs?.all?.forEach { (domain, value) ->
            if (domain == SCHEMA_VERSION_KEY) return@forEach
            if (value is String && value.isNotEmpty()) {
                linkIconCache.put(domain, value.split(SEPARATOR))
            }
        }
        resolvedPrefs?.all?.forEach { (domain, value) ->
            if (value is String && value.isNotEmpty()) {
                resolvedIconCache.put(domain, value)
            }
        }
    }

    /**
     * 将域名对应的图标 URL 列表持久化
     */
    private fun persistToPrefs(domain: String, urls: List<String>) {
        prefs?.edit()?.putString(domain, urls.joinToString(SEPARATOR))?.apply()
    }

    /** 记录某个域名最终成功加载的图标 URL */
    private fun saveResolvedIcon(domain: String, url: String) {
        resolvedIconCache.put(domain, url)
        resolvedPrefs?.edit()?.putString(domain, url)?.apply()
    }

    /** 返回某个 pageKey 对应的本地图标文件（用 MD5 做文件名，避免非法字符）。iconDir 未初始化时返回 null */
    private fun iconFileFor(pageKey: String): File? {
        val dir = iconDir ?: return null
        val name = MessageDigest.getInstance("MD5")
            .digest(pageKey.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(dir, "$name.png")
    }

    /** 把加载成功的图标 bitmap 持久化到本地文件（后台线程写，先写临时文件再改名，避免半截文件） */
    private fun persistIconBitmap(bitmap: Bitmap, file: File) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val tmp = File(file.parentFile, "${file.name}.tmp")
                FileOutputStream(tmp).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                if (file.exists()) file.delete()
                tmp.renameTo(file)
            } catch (_: Exception) {
                // 存图失败不影响显示，忽略
            }
        }
    }

    /**
     * 标记这批 URL 在「下一次加载」时需要联网重新获取（用户点刷新按钮时调用）。
     * 应在清空缓存后、通知列表重绑之前调用。传入快速拨号里所有站点/文件夹子项的原始 URL。
     * 只有在此集合中的 pageKey 才会联网；其余一律只读缓存，缓存没有就显示默认图标。
     */
    fun beginForceRefresh(urls: Collection<String>) {
        pendingRefreshKeys.clear()
        urls.forEach { url -> extractPageKey(url)?.let { pendingRefreshKeys.add(it) } }
    }

    /**
     * 清除内存和磁盘中的 link 图标缓存，配合 Glide 缓存清理实现完整刷新
     */
    fun clearLinkIconCache() {
        linkIconCache.evictAll()
        resolvedIconCache.evictAll()
        prefs?.edit()?.clear()?.apply()
        resolvedPrefs?.edit()?.clear()?.apply()
    }

    // ==================== 域名提取 ====================

    private fun extractDomain(url: String): String? {
        return try {
            val uri = Uri.parse(url)
            val host = uri.host ?: return null
            val domain = host.removePrefix("www.")
            val port = uri.port
            // IP 地址带非标准端口时，端口作为标识的一部分，避免缓存混淆
            if (port != -1 && port != 80 && port != 443 && isIpAddress(domain)) {
                "${domain}:${port}"
            } else {
                domain
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 提取页面级缓存 key：域名 + 完整路径（含文件名）。
     * 每个具体页面都按完整 URL 独立缓存图标，避免不同页面共享同一份图标。
     * 例如：
     *   https://windy003.github.io/web_project/ -> windy003.github.io/web_project
     *   https://windy003.github.io/web_project/github-repos_finder.html
     *       -> windy003.github.io/web_project/github-repos_finder.html
     *   https://windy003.github.io/web_project/youtube-cc-finder/index.html
     *       -> windy003.github.io/web_project/youtube-cc-finder/index.html
     */
    private fun extractPageKey(url: String): String? {
        return try {
            val uri = Uri.parse(url)
            val host = uri.host ?: return null
            val domain = host.removePrefix("www.")
            val port = uri.port
            val hostPart = if (port != -1 && port != 80 && port != 443 && isIpAddress(domain)) {
                "${domain}:${port}"
            } else {
                domain
            }
            val path = uri.path?.trimEnd('/') ?: ""
            if (path.isEmpty()) hostPart else "$hostPart$path"
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 获取页面所在目录的 URL（去掉文件名部分）
     * 如 https://example.com/repo/page.html -> https://example.com/repo
     */
    private fun getPageDir(url: String): String {
        val uri = Uri.parse(url)
        val path = uri.path ?: return getBaseUrl(url) ?: url
        val lastSlash = path.lastIndexOf('/')
        val dirPath = if (lastSlash > 0) path.substring(0, lastSlash) else ""
        val port = uri.port
        val hostPort = if (port != -1 && port != 80 && port != 443) "${uri.host}:$port" else "${uri.host}"
        return "${uri.scheme}://$hostPort$dirPath"
    }

    private fun getBaseUrl(url: String): String? {
        return try {
            val uri = Uri.parse(url)
            val port = uri.port
            if (port != -1 && port != 80 && port != 443) {
                "${uri.scheme}://${uri.host}:${port}"
            } else {
                "${uri.scheme}://${uri.host}"
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 判断域名是否为 IP 地址（外部图标服务对 IP 无效） */
    private fun isIpAddress(domain: String): Boolean {
        return domain.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")) || domain.contains(":")
    }

    // ==================== 各来源 URL 生成 ====================

    fun getBrandfetchUrl(domain: String, size: Int = 256): String {
        return "https://cdn.brandfetch.io/${domain}/w/${size}/h/${size}?c=1idL2s2l2pH"
    }

    fun getDuckDuckGoUrl(domain: String): String {
        return "https://icons.duckduckgo.com/ip3/${domain}.ico"
    }

    fun getGoogleFaviconUrl(domain: String, size: Int = 128): String {
        return "https://www.google.com/s2/favicons?domain=${domain}&sz=${size}"
    }

    // ==================== HTML <link> 标签解析 ====================

    /**
     * 表示从 <link> 标签解析出来的图标信息
     */
    private data class LinkIcon(
        val href: String,
        val rel: String,       // "apple-touch-icon" 或 "icon"
        val size: Int          // 最大边长，0 表示未指定
    )

    /**
     * 从网站 HTML 的 <head> 中提取所有图标 <link> 标签，
     * 按 rel 类型和分辨率排序（apple-touch-icon 优先，分辨率从高到低）
     */
    suspend fun resolveIconsFromHtml(pageUrl: String): List<String> {
        val pageKey = extractPageKey(pageUrl) ?: return emptyList()

        // 先查缓存
        linkIconCache.get(pageKey)?.let { return it }

        return withContext(Dispatchers.IO) {
            try {
                val baseUrl = getBaseUrl(pageUrl) ?: return@withContext emptyList()
                val html = fetchHead(pageUrl)
                val pageDir = getPageDir(pageUrl)
                val icons = parseIcons(html, baseUrl, pageDir)

                // 排序：apple-touch-icon 优先，然后按分辨率从高到低
                val sorted = icons.sortedWith(
                    compareByDescending<LinkIcon> { it.rel == "apple-touch-icon" }
                        .thenByDescending { it.size }
                )

                val urls = sorted.map { it.href }
                if (urls.isNotEmpty()) {
                    linkIconCache.put(pageKey, urls)
                    persistToPrefs(pageKey, urls)
                }
                urls
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    /**
     * 只获取网页 <head> 部分，限制读取量防止下载整页
     */
    private fun fetchHead(baseUrl: String): String {
        val conn = URL(baseUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")

        return try {
            val reader = conn.inputStream.bufferedReader()
            val sb = StringBuilder()
            val buf = CharArray(4096)
            var total = 0
            val limit = 64 * 1024 // 最多读 64KB，足够拿到 <head>

            while (total < limit) {
                val n = reader.read(buf)
                if (n == -1) break
                sb.append(buf, 0, n)
                total += n
                // 遇到 </head> 就停
                if (sb.contains("</head>", ignoreCase = true)) break
            }
            sb.toString()
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 解析 HTML 中的 <link rel="apple-touch-icon"...> 和 <link rel="icon"...>
     */
    private fun parseIcons(html: String, baseUrl: String, pageDir: String? = null): List<LinkIcon> {
        val result = mutableListOf<LinkIcon>()

        // 匹配所有 <link ... > 标签
        val linkPattern = Pattern.compile(
            "<link\\s[^>]*>",
            Pattern.CASE_INSENSITIVE or Pattern.DOTALL
        )
        val matcher = linkPattern.matcher(html)

        while (matcher.find()) {
            val tag = matcher.group()

            // 提取 rel 属性
            val rel = extractAttr(tag, "rel")?.lowercase() ?: continue

            // 只关心 apple-touch-icon 和 icon
            val normalizedRel = when {
                rel.contains("apple-touch-icon") -> "apple-touch-icon"
                rel == "icon" || rel == "shortcut icon" -> "icon"
                else -> continue
            }

            // 提取 href
            val href = extractAttr(tag, "href") ?: continue
            val fullUrl = resolveUrl(href, baseUrl, pageDir)

            // 提取 sizes（如 "180x180"、"32x32"）
            val sizesStr = extractAttr(tag, "sizes")
            val size = parseLargestSize(sizesStr)

            result.add(LinkIcon(href = fullUrl, rel = normalizedRel, size = size))
        }

        return result
    }

    private fun extractAttr(tag: String, attrName: String): String? {
        // 匹配 attrName="value" 或 attrName='value'
        val pattern = Pattern.compile(
            """$attrName\s*=\s*["']([^"']*)["']""",
            Pattern.CASE_INSENSITIVE
        )
        val m = pattern.matcher(tag)
        return if (m.find()) m.group(1) else null
    }

    /**
     * 解析 sizes 属性，取最大边长。如 "180x180" -> 180, "any" -> Int.MAX_VALUE
     */
    private fun parseLargestSize(sizes: String?): Int {
        if (sizes == null) return 0
        if (sizes.equals("any", ignoreCase = true)) return Int.MAX_VALUE

        // 可能有多个尺寸用空格分隔: "32x32 64x64"
        return sizes.split("\\s+".toRegex()).maxOfOrNull { sizeStr ->
            val parts = sizeStr.lowercase().split("x")
            if (parts.size == 2) {
                maxOf(parts[0].toIntOrNull() ?: 0, parts[1].toIntOrNull() ?: 0)
            } else {
                0
            }
        } ?: 0
    }

    /**
     * 将可能的相对路径转为绝对路径
     * @param baseUrl 域名根 URL（如 https://example.com）
     * @param pageDir 页面所在目录 URL（如 https://example.com/repo/），用于解析 ./ 开头的相对路径
     */
    private fun resolveUrl(href: String, baseUrl: String, pageDir: String? = null): String {
        return when {
            href.startsWith("http://") || href.startsWith("https://") -> href
            href.startsWith("//") -> "https:$href"
            href.startsWith("/") -> "$baseUrl$href"
            else -> "${pageDir ?: baseUrl}/$href"
        }
    }

    // ==================== 图标加载（快速拨号 & 书签） ====================

    /**
     * 为快速拨号加载图标（圆角矩形，较大尺寸）。
     *
     * 平时：只从本地持久化文件（filesDir/speeddial_icons）读取，读不到就显示默认图标，绝不联网。
     * 刷新（用户点刷新按钮）：联网走降级链重新获取，成功后把图标持久化到本地文件。
     */
    fun loadSpeedDialIcon(imageView: ImageView, url: String, customIconUrl: String? = null) {
        val domain = extractDomain(url)
        val pageKey = extractPageKey(url)

        if (domain == null || pageKey == null) {
            imageView.setImageResource(R.drawable.ic_speed_dial_default)
            return
        }

        val iconFile = iconFileFor(pageKey)
        // 本轮刷新是否需要联网重取该图标；命中即消费，避免滚动时重复联网
        val forceNetwork = pendingRefreshKeys.remove(pageKey)

        if (!forceNetwork) {
            // 平时：直接从本地文件显示，命中最快、离线可用、不依赖易失的 Glide 缓存
            if (iconFile != null && iconFile.exists()) {
                Glide.with(imageView.context)
                    .load(iconFile)
                    // 文件被刷新覆盖后，用修改时间做签名让 Glide 失效旧的内存缓存
                    .signature(ObjectKey(iconFile.lastModified()))
                    .placeholder(R.drawable.ic_speed_dial_default)
                    .error(R.drawable.ic_speed_dial_default)
                    .into(imageView)
            } else {
                imageView.setImageResource(R.drawable.ic_speed_dial_default)
            }
            return
        }

        // 刷新：联网重新解析并下载，成功后持久化到 iconFile
        CoroutineScope(Dispatchers.Main).launch {
            val linkIcons = resolveIconsFromHtml(url)
            loadWithFallbackChain(
                imageView, domain, pageKey, linkIcons,
                isSpeedDial = true, baseUrl = getBaseUrl(url),
                forceNetwork = true, persistFile = iconFile
            )
        }
    }

    /**
     * 为书签列表加载图标（圆形，较小尺寸）
     */
    fun loadBookmarkFavicon(imageView: ImageView, url: String, customFavicon: String? = null) {
        val domain = extractDomain(url)
        val pageKey = extractPageKey(url)

        if (domain == null || pageKey == null) {
            imageView.setImageResource(android.R.drawable.ic_menu_compass)
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            val resolvedUrl = resolvedIconCache.get(pageKey)
            val linkIcons = if (resolvedUrl != null) listOf(resolvedUrl) else resolveIconsFromHtml(url)
            // 书签列表保持原有联网获取行为（不受快速拨号「只读缓存」策略影响）
            loadWithFallbackChain(
                imageView, domain, pageKey, linkIcons,
                isSpeedDial = false, baseUrl = getBaseUrl(url), forceNetwork = true
            )
        }
    }

    /**
     * 构建完整降级链:
     * 域名: Brandfetch -> link 标签图标(按分辨率从高到低) -> DuckDuckGo -> Google Favicon -> 默认
     * IP地址: link 标签图标 -> /favicon.ico -> 默认
     */
    private fun loadWithFallbackChain(
        imageView: ImageView,
        domain: String,
        pageKey: String,
        linkIcons: List<String>,
        isSpeedDial: Boolean,
        baseUrl: String? = null,
        forceNetwork: Boolean = true,
        persistFile: File? = null
    ) {
        val defaultRes = if (isSpeedDial) R.drawable.ic_speed_dial_default
                         else android.R.drawable.ic_menu_compass
        val isIp = isIpAddress(domain)
        // forceNetwork=false 时只从 Glide 磁盘/内存缓存取图，不发任何网络请求；
        // 缓存未命中则逐层 error 降级，最终落到默认图标
        val cacheOnly = !forceNetwork

        // 每一层都加 listener，记录最终成功加载的图标 URL，并在需要时持久化到本地文件
        val successListener = object : RequestListener<Drawable> {
            override fun onLoadFailed(
                e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean
            ): Boolean = false

            override fun onResourceReady(
                resource: Drawable, model: Any, target: Target<Drawable>,
                dataSource: DataSource, isFirstResource: Boolean
            ): Boolean {
                val url = model as? String
                if (url != null) {
                    saveResolvedIcon(pageKey, url)
                    // 刷新时把真实下载到的图标持久化到本地文件，之后平时打开直接读文件。
                    // 只有 model 是 URL（真图标）才存；兜底的默认图标 model 是资源 id，不会误存。
                    if (persistFile != null) {
                        (resource as? BitmapDrawable)?.bitmap?.let { persistIconBitmap(it, persistFile) }
                    }
                }
                return false
            }
        }

        // 从最低优先级开始，向外包装 error() 降级
        var request = if (isIp && baseUrl != null) {
            Glide.with(imageView.context)
                .load("${baseUrl}/favicon.ico")
                .apply { if (isSpeedDial) transform(CenterCrop(), RoundedCorners(24)) else circleCrop() }
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .onlyRetrieveFromCache(cacheOnly)
                .listener(successListener)
                .error(defaultRes)
        } else {
            val googleRequest = Glide.with(imageView.context)
                .load(getGoogleFaviconUrl(domain))
                .apply { if (isSpeedDial) transform(CenterCrop(), RoundedCorners(24)) else circleCrop() }
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .onlyRetrieveFromCache(cacheOnly)
                .listener(successListener)
                .error(defaultRes)

            Glide.with(imageView.context)
                .load(getDuckDuckGoUrl(domain))
                .apply { if (isSpeedDial) transform(CenterCrop(), RoundedCorners(24)) else circleCrop() }
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .onlyRetrieveFromCache(cacheOnly)
                .listener(successListener)
                .error(googleRequest)
        }

        // link 标签图标，从分辨率最低的开始包装
        for (iconUrl in linkIcons.reversed()) {
            request = Glide.with(imageView.context)
                .load(iconUrl)
                .apply { if (isSpeedDial) transform(CenterCrop(), RoundedCorners(24)) else circleCrop() }
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .onlyRetrieveFromCache(cacheOnly)
                .listener(successListener)
                .error(request)
        }

        if (isIp) {
            val topUrl = if (linkIcons.isNotEmpty()) linkIcons.first() else "${baseUrl}/favicon.ico"
            Glide.with(imageView.context)
                .load(topUrl)
                .apply { if (isSpeedDial) transform(CenterCrop(), RoundedCorners(24)) else circleCrop() }
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .placeholder(defaultRes)
                .listener(successListener)
                .error(request)
                .into(imageView)
        } else {
            Glide.with(imageView.context)
                .load(getBrandfetchUrl(domain, if (isSpeedDial) 256 else 128))
                .apply { if (isSpeedDial) transform(CenterCrop(), RoundedCorners(24)) else circleCrop() }
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .placeholder(defaultRes)
                .listener(successListener)
                .error(request)
                .into(imageView)
        }
    }
}
