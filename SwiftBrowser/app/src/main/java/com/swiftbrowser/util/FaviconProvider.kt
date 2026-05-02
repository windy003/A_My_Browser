package com.swiftbrowser.util

import android.net.Uri
import android.util.LruCache
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.swiftbrowser.R
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
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
     * key = domain, value = 按分辨率从高到低排列的图标 URL 列表
     */
    private val linkIconCache = LruCache<String, List<String>>(200)

    // ==================== 域名提取 ====================

    private fun extractDomain(url: String): String? {
        return try {
            val host = Uri.parse(url).host ?: return null
            host.removePrefix("www.")
        } catch (e: Exception) {
            null
        }
    }

    private fun getBaseUrl(url: String): String? {
        return try {
            val uri = Uri.parse(url)
            "${uri.scheme}://${uri.host}"
        } catch (e: Exception) {
            null
        }
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
        val domain = extractDomain(pageUrl) ?: return emptyList()

        // 先查缓存
        linkIconCache.get(domain)?.let { return it }

        return withContext(Dispatchers.IO) {
            try {
                val baseUrl = getBaseUrl(pageUrl) ?: return@withContext emptyList()
                val html = fetchHead(baseUrl)
                val icons = parseIcons(html, baseUrl)

                // 排序：apple-touch-icon 优先，然后按分辨率从高到低
                val sorted = icons.sortedWith(
                    compareByDescending<LinkIcon> { it.rel == "apple-touch-icon" }
                        .thenByDescending { it.size }
                )

                val urls = sorted.map { it.href }
                if (urls.isNotEmpty()) {
                    linkIconCache.put(domain, urls)
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
    private fun parseIcons(html: String, baseUrl: String): List<LinkIcon> {
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
            val fullUrl = resolveUrl(href, baseUrl)

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
     */
    private fun resolveUrl(href: String, baseUrl: String): String {
        return when {
            href.startsWith("http://") || href.startsWith("https://") -> href
            href.startsWith("//") -> "https:$href"
            href.startsWith("/") -> "$baseUrl$href"
            else -> "$baseUrl/$href"
        }
    }

    // ==================== 图标加载（快速拨号 & 书签） ====================

    /**
     * 为快速拨号加载图标（圆角矩形，较大尺寸）
     * 先异步解析网站 link 标签，再用 Glide 链式降级
     */
    fun loadSpeedDialIcon(imageView: ImageView, url: String, customIconUrl: String? = null) {
        val domain = extractDomain(url)

        // 如果用户指定了自定义图标，直接用
        if (customIconUrl != null) {
            Glide.with(imageView.context)
                .load(customIconUrl)
                .transform(CenterCrop(), RoundedCorners(24))
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .placeholder(R.drawable.ic_speed_dial_default)
                .error(
                    Glide.with(imageView.context)
                        .load(domain?.let { getBrandfetchUrl(it) })
                        .transform(CenterCrop(), RoundedCorners(24))
                        .error(R.drawable.ic_speed_dial_default)
                )
                .into(imageView)
            return
        }

        if (domain == null) {
            imageView.setImageResource(R.drawable.ic_speed_dial_default)
            return
        }

        // 先显示占位图，然后异步解析 link 标签图标
        imageView.setImageResource(R.drawable.ic_speed_dial_default)

        CoroutineScope(Dispatchers.Main).launch {
            val linkIcons = resolveIconsFromHtml(url)
            loadWithFallbackChain(imageView, domain, linkIcons, isSpeedDial = true)
        }
    }

    /**
     * 为书签列表加载图标（圆形，较小尺寸）
     */
    fun loadBookmarkFavicon(imageView: ImageView, url: String, customFavicon: String? = null) {
        val domain = extractDomain(url)

        if (customFavicon != null) {
            Glide.with(imageView.context)
                .load(customFavicon)
                .circleCrop()
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .placeholder(android.R.drawable.ic_menu_compass)
                .error(
                    Glide.with(imageView.context)
                        .load(domain?.let { getBrandfetchUrl(it, 128) })
                        .circleCrop()
                        .error(android.R.drawable.ic_menu_compass)
                )
                .into(imageView)
            return
        }

        if (domain == null) {
            imageView.setImageResource(android.R.drawable.ic_menu_compass)
            return
        }

        imageView.setImageResource(android.R.drawable.ic_menu_compass)

        CoroutineScope(Dispatchers.Main).launch {
            val linkIcons = resolveIconsFromHtml(url)
            loadWithFallbackChain(imageView, domain, linkIcons, isSpeedDial = false)
        }
    }

    /**
     * 构建完整降级链:
     * Brandfetch -> link 标签图标(按分辨率从高到低) -> DuckDuckGo -> Google Favicon -> 默认
     */
    private fun loadWithFallbackChain(
        imageView: ImageView,
        domain: String,
        linkIcons: List<String>,
        isSpeedDial: Boolean
    ) {
        val defaultRes = if (isSpeedDial) R.drawable.ic_speed_dial_default
                         else android.R.drawable.ic_menu_compass

        // 从最低优先级开始，向外包装 error() 降级
        // 最内层: Google Favicon -> 默认
        var request = Glide.with(imageView.context)
            .load(getGoogleFaviconUrl(domain))
            .apply { if (isSpeedDial) transform(CenterCrop(), RoundedCorners(24)) else circleCrop() }
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .error(defaultRes)

        // 倒数第二层: DuckDuckGo -> (Google -> 默认)
        request = Glide.with(imageView.context)
            .load(getDuckDuckGoUrl(domain))
            .apply { if (isSpeedDial) transform(CenterCrop(), RoundedCorners(24)) else circleCrop() }
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .error(request)

        // link 标签图标，从分辨率最低的开始包装（最高分辨率在最外层最先尝试）
        for (iconUrl in linkIcons.reversed()) {
            request = Glide.with(imageView.context)
                .load(iconUrl)
                .apply { if (isSpeedDial) transform(CenterCrop(), RoundedCorners(24)) else circleCrop() }
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .error(request)
        }

        // 最外层: Brandfetch -> (link 标签 -> DuckDuckGo -> Google -> 默认)
        Glide.with(imageView.context)
            .load(getBrandfetchUrl(domain, if (isSpeedDial) 256 else 128))
            .apply { if (isSpeedDial) transform(CenterCrop(), RoundedCorners(24)) else circleCrop() }
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .placeholder(defaultRes)
            .error(request)
            .into(imageView)
    }
}
