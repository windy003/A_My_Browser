package com.swiftbrowser.ui.browser

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.KeyEvent
import android.widget.SeekBar
import android.widget.TextView
import java.io.File
import android.view.ActionMode
import android.view.DragEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.swiftbrowser.R
import com.swiftbrowser.SwiftBrowserApp
import com.swiftbrowser.data.entity.Bookmark
import com.swiftbrowser.databinding.ActivityMainBinding
import com.swiftbrowser.ui.auth.LoginActivity
import com.swiftbrowser.ui.bookmark.BookmarkActivity
import com.swiftbrowser.ui.history.HistoryActivity
import com.swiftbrowser.ui.speeddial.LiftAction
import com.swiftbrowser.ui.speeddial.SpeedDialAdapter
import com.swiftbrowser.ui.speeddial.SpeedDialDragHelper
import com.swiftbrowser.ui.speeddial.SpeedDialItem
import com.swiftbrowser.util.FaviconProvider
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var speedDialAdapter: SpeedDialAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper
    private var folderItemTouchHelper: ItemTouchHelper? = null

    private val app get() = application as SwiftBrowserApp
    private val bookmarkDao get() = app.database.bookmarkDao()
    private val historyDao get() = app.database.historyDao()
    private val cloudSync get() = app.cloudSyncManager

    // 音量键翻页设置
    // ratio 含义：0.0 = 一行高度（~50dp），1.0 = 整屏高度；线性插值
    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
    }
    private var volumeScrollEnabled: Boolean
        get() = prefs.getBoolean("volume_scroll_enabled", true)
        set(value) { prefs.edit().putBoolean("volume_scroll_enabled", value).apply() }
    private var volumeScrollRatio: Float
        get() = prefs.getFloat("volume_scroll_ratio", 1.0f)
        set(value) { prefs.edit().putFloat("volume_scroll_ratio", value).apply() }

    // 繁转简
    private var t2sEnabled: Boolean
        get() = prefs.getBoolean("t2s_enabled", false)
        set(value) { prefs.edit().putBoolean("t2s_enabled", value).apply() }
    // 缓存的转换 JS（包含字典）；首次需要时从 assets 加载
    private var t2sScriptCache: String? = null

    // 桌面模式：使用桌面版 User-Agent 并以宽视口渲染
    private var desktopModeEnabled: Boolean
        get() = prefs.getBoolean("desktop_mode_enabled", false)
        set(value) { prefs.edit().putBoolean("desktop_mode_enabled", value).apply() }
    // 缓存的移动版（默认）User-Agent，用于在桌面/普通模式间切换
    private val mobileUserAgent: String by lazy {
        WebSettings.getDefaultUserAgent(this).replace("; wv", "")
    }

    companion object {
        private const val MENU_ID_YOUDAO = 0x59440001
        private const val YOUDAO_PACKAGE = "com.youdao.dict"
    }

    // ==================== 多标签 ====================
    private val tabs = mutableListOf<Tab>()
    private var activeTab: Tab? = null
    private var isShowingWebView = false
    private var isReordering = false
    private var isToolbarHidden = false
    private var isToolbarAnimating = false
    private lateinit var tabAdapter: TabAdapter

    // 全屏视频
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var originalOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    // 当前标签的快捷访问
    private val currentUrl: String? get() = activeTab?.url
    private val currentTitle: String? get() = activeTab?.title

    // 下载
    private var pendingDownload: PendingDownload? = null
    private data class PendingDownload(val url: String, val contentDisposition: String?, val mimeType: String?, val fileName: String)
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingDownload?.let { startDownload(it.url, it.contentDisposition, it.mimeType, it.fileName) }
        } else {
            Toast.makeText(this, R.string.download_need_permission, Toast.LENGTH_SHORT).show()
        }
        pendingDownload = null
    }

    // WebView 文件选择（<input type="file">）
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = fileChooserCallback ?: return@registerForActivityResult
        val uris: Array<Uri>? = if (result.resultCode == RESULT_OK) {
            val data = result.data
            val clipData = data?.clipData
            when {
                clipData != null -> Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
                data?.data != null -> arrayOf(data.data!!)
                else -> null
            }
        } else null
        callback.onReceiveValue(uris)
        fileChooserCallback = null
    }

    // 保存为 MHTML：先存到临时文件，再让用户选择保存位置后写入
    private var pendingMhtmlTempFile: java.io.File? = null
    private val saveMhtmlLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val temp = pendingMhtmlTempFile
        pendingMhtmlTempFile = null
        if (result.resultCode == RESULT_OK && temp != null) {
            val uri = result.data?.data
            if (uri != null) {
                try {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        temp.inputStream().use { it.copyTo(out) }
                    }
                    Toast.makeText(this, "已保存为 MHTML", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        temp?.delete()
    }

    // 打开 MHTML：选择文件后复制到缓存目录并用 WebView 加载
    private val openMhtmlLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { openMhtmlFromUri(it) }
        }
    }

    override fun attachBaseContext(newBase: android.content.Context?) {
        val config = android.content.res.Configuration(newBase?.resources?.configuration)
        config.fontScale = 1.0f
        super.attachBaseContext(newBase?.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSpeedDial()
        setupUrlBar()
        setupBottomBar()
        setupMenuButton()
        setupTabManager()
        setupFindInPage()
        setupKeyboardListener()

        // 创建第一个标签
        createNewTab()

        handleIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        for (tab in tabs) {
            tab.webView?.destroy()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val url = intent.dataString
        if (url != null) {
            loadUrl(url)
        }
    }

    // ==================== 多标签管理 ====================

    private fun createNewTab(): Tab {
        val tab = Tab()
        tab.webView = createWebView()
        tabs.add(tab)
        switchToTab(tab)
        updateTabCount()
        return tab
    }

    private fun switchToTab(tab: Tab) {
        // 保存当前标签的截图
        captureCurrentTabThumbnail()

        activeTab = tab
        updateTabCount()

        // 切换 WebView 显示
        binding.webViewContainer.removeAllViews()
        tab.webView?.let { binding.webViewContainer.addView(it) }

        // 标签面板打开时只切换底层内容，不关闭面板
        if (binding.tabOverlay.visibility == View.VISIBLE) {
            binding.etUrl.setText(tab.url ?: "")
            isShowingWebView = tab.url != null
            return
        }

        // 更新地址栏
        if (tab.url != null) {
            binding.etUrl.setText(tab.url)
            showWebView()
        } else {
            showHomePage()
        }
    }

    private fun closeTab(tab: Tab) {
        val isTabOverlayVisible = binding.tabOverlay.visibility == View.VISIBLE

        if (tabs.size <= 1) {
            // 最后一个标签，不关闭，而是清空
            tab.webView?.loadUrl("about:blank")
            tab.url = null
            tab.title = null
            tab.thumbnail = null
            if (!isTabOverlayVisible) {
                showHomePage()
            }
            return
        }

        val index = tabs.indexOf(tab)
        tabs.remove(tab)
        binding.webViewContainer.removeView(tab.webView)
        tab.webView?.destroy()

        if (tab == activeTab) {
            // 切换到相邻标签
            val newIndex = if (index >= tabs.size) tabs.size - 1 else index
            switchToTab(tabs[newIndex])
        }

        updateTabCount()
    }

    private fun captureCurrentTabThumbnail() {
        val webView = activeTab?.webView ?: return
        if (webView.width > 0 && webView.height > 0) {
            try {
                // 按原始分辨率的一半截图，保持清晰
                val scale = 0.5f
                val w = (webView.width * scale).toInt()
                val h = (webView.height * scale).toInt()
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.scale(scale, scale)
                webView.draw(canvas)
                activeTab?.thumbnail = bitmap
            } catch (_: Exception) { }
        }
    }

    private fun updateTabCount() {
        val current = tabs.indexOf(activeTab) + 1
        binding.tvTabCountBottom.text = if (current > 0) "$current/${tabs.size}" else tabs.size.toString()
    }

    // ==================== 标签管理界面 ====================

    private fun setupTabManager() {
        tabAdapter = TabAdapter(
            onClickTab = { tab ->
                hideTabOverlay()
                switchToTab(tab)
            },
            onCloseTab = { tab ->
                closeTab(tab)
                tabAdapter.activeTabId = activeTab?.id ?: -1L
                tabAdapter.submitList(tabs.toList())
                updateTabCount()
            }
        )

        binding.rvTabs.apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = tabAdapter
            PagerSnapHelper().attachToRecyclerView(this)
        }

        // 上滑删除标签
        val swipeCallback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.UP) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                if (position >= 0 && position < tabs.size) {
                    val tab = tabs[position]
                    closeTab(tab)
                    tabAdapter.activeTabId = activeTab?.id ?: -1L
                    tabAdapter.submitList(tabs.toList())
                    updateTabCount()
                }
            }

            override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float {
                return 0.15f
            }

            override fun getSwipeEscapeVelocity(defaultValue: Float): Float {
                return defaultValue * 0.5f
            }
        }
        ItemTouchHelper(swipeCallback).attachToRecyclerView(binding.rvTabs)

        binding.btnNewTab.setOnClickListener {
            hideTabOverlay()
            createNewTab()
        }

    }

    private fun toggleTabOverlay() {
        if (binding.tabOverlay.visibility == View.VISIBLE) {
            hideTabOverlay()
        } else {
            showTabOverlay()
        }
    }

    private fun showTabOverlay() {
        captureCurrentTabThumbnail()
        tabAdapter.activeTabId = activeTab?.id ?: -1L
        tabAdapter.submitList(tabs.toList())
        binding.tabOverlay.visibility = View.VISIBLE
        // 滚动到当前标签
        val activeIndex = tabs.indexOfFirst { it.id == activeTab?.id }
        if (activeIndex >= 0) {
            binding.rvTabs.scrollToPosition(activeIndex)
        }
    }

    private fun hideTabOverlay() {
        binding.tabOverlay.visibility = View.GONE
    }

    // ==================== WebView 创建 ====================

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        return SelectionWebView(this).apply {
            // 允许第三方 Cookie（Google 登录需要跨域 Cookie）
            val cookieManager = android.webkit.CookieManager.getInstance()
            cookieManager.setAcceptThirdPartyCookies(this, true)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.setSupportZoom(true)
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.mediaPlaybackRequiresUserGesture = false
            settings.databaseEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            // 允许加载本地文件（用于打开 MHTML 离线网页）
            settings.allowFileAccess = true
            // 防止 WebView 字体跟随系统字体缩放
            settings.textZoom = 100

            // 伪装成普通浏览器，避免 Google 拒绝 WebView 中的 OAuth 登录
            // 同时根据当前是否为桌面模式设置 User-Agent
            applyDesktopMode(this, desktopModeEnabled)

            // 暗黑模式：让网页内容也跟随系统暗色主题
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true)
            }

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    val tab = findTabByWebView(view) ?: return
                    tab.url = url
                    if (tab == activeTab) {
                        binding.etUrl.setText(url)
                        binding.progressBar.visibility = View.VISIBLE
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    val tab = findTabByWebView(view) ?: return
                    tab.url = url
                    tab.title = view?.title
                    if (tab == activeTab) {
                        binding.etUrl.setText(url)
                        binding.progressBar.visibility = View.GONE
                    }
                    // 记录历史
                    if (url != null && url != "about:blank") {
                        lifecycleScope.launch {
                            historyDao.insert(
                                com.swiftbrowser.data.entity.History(
                                    title = view?.title ?: url,
                                    url = url
                                )
                            )
                        }
                    }
                    // 繁转简（如已开启）
                    if (t2sEnabled && view != null) {
                        applyT2S(view)
                    }
                    // 注入广告清理脚本：移除广告覆盖层，阻止广告点击劫持
                    view?.evaluateJavascript("""
                        (function() {
                            // 移除透明覆盖层（广告用来劫持点击的）
                            var overlays = document.querySelectorAll('div[style*="z-index"][style*="position"]');
                            overlays.forEach(function(el) {
                                var style = window.getComputedStyle(el);
                                if ((style.opacity === '0' || style.opacity < 0.1 || style.background === 'transparent' || style.backgroundColor === 'transparent')
                                    && parseInt(style.zIndex) > 100
                                    && (style.position === 'fixed' || style.position === 'absolute')
                                    && el.offsetWidth > window.innerWidth * 0.5) {
                                    el.remove();
                                }
                            });
                            // 移除广告 iframe
                            var iframes = document.querySelectorAll('iframe');
                            iframes.forEach(function(iframe) {
                                var src = iframe.src || '';
                                if (src.match(/(magsrv|exoclick|juicyads|trafficjunky|xxxvjmp|mavrtracktor|popads|clickadu)/i)) {
                                    iframe.remove();
                                }
                            });
                            // 阻止 window.open 弹窗
                            window.open = function() { return null; };
                        })();
                    """.trimIndent(), null)
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val url = request?.url?.toString() ?: return false
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        try {
                            if (url.startsWith("intent://")) {
                                // 解析 intent:// 协议（YouTube 等应用使用此方式打开原生 App）
                                val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                                try {
                                    startActivity(intent)
                                } catch (_: android.content.ActivityNotFoundException) {
                                    // App 未安装，尝试跳转到应用商店
                                    val packageName = intent.`package`
                                    if (packageName != null) {
                                        startActivity(Intent(Intent.ACTION_VIEW,
                                            Uri.parse("market://details?id=$packageName")))
                                    }
                                }
                            } else {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }
                        } catch (_: Exception) { }
                        return true
                    }
                    // 拦截广告域名的页面跳转
                    val host = request.url?.host?.lowercase() ?: return false
                    if (isAdHost(host)) {
                        return true // 阻止跳转到广告页面
                    }
                    return false
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val host = request?.url?.host?.lowercase() ?: return null
                    if (isAdHost(host)) {
                        return WebResourceResponse("text/plain", "utf-8", null)
                    }
                    return null
                }

            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (findTabByWebView(view) == activeTab) {
                        binding.progressBar.progress = newProgress
                        if (newProgress == 100) {
                            binding.progressBar.visibility = View.GONE
                        }
                    }
                }

                override fun onReceivedTitle(view: WebView?, title: String?) {
                    val tab = findTabByWebView(view) ?: return
                    tab.title = title
                }

                // 视频全屏播放
                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    if (customView != null) {
                        callback?.onCustomViewHidden()
                        return
                    }
                    customView = view
                    customViewCallback = callback
                    originalOrientation = requestedOrientation

                    // 隐藏主界面，显示全屏视频
                    binding.mainContent.visibility = View.GONE
                    binding.customViewContainer.visibility = View.VISIBLE
                    binding.customViewContainer.addView(view)

                    window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                    window.decorView.systemUiVisibility = (
                        View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    )

                    // 根据视频实际宽高比决定方向：横屏视频自动旋转横屏，竖屏视频保持竖屏。
                    // Why: 对竖屏视频强制 SENSOR_LANDSCAPE 会让网站的 fullscreenchange 逻辑
                    // 主动退出全屏（"转一下就恢复"），因此先按视频宽高判断。
                    val webView = activeTab?.webView
                    if (webView != null) {
                        webView.evaluateJavascript(
                            "(function(){var vs=document.querySelectorAll('video');" +
                            "for(var i=0;i<vs.length;i++){var v=vs[i];" +
                            "if(v.videoWidth>0&&v.videoHeight>0)return v.videoWidth+','+v.videoHeight;}" +
                            "return '';})();"
                        ) { result ->
                            val cleaned = result?.trim('"') ?: ""
                            val parts = cleaned.split(",")
                            val w = parts.getOrNull(0)?.toIntOrNull() ?: 0
                            val h = parts.getOrNull(1)?.toIntOrNull() ?: 0
                            requestedOrientation = when {
                                w > 0 && h > 0 && h > w -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            }
                        }
                    } else {
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    }
                }

                override fun onHideCustomView() {
                    if (customView == null) return

                    // 移除全屏视频，恢复主界面
                    binding.customViewContainer.removeView(customView)
                    binding.customViewContainer.visibility = View.GONE
                    binding.mainContent.visibility = View.VISIBLE

                    customViewCallback?.onCustomViewHidden()
                    customView = null
                    customViewCallback = null

                    // 恢复方向和状态栏
                    requestedOrientation = originalOrientation
                    window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                    window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
                }

                // 处理 <input type="file"> 的文件选择
                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    // 取消之前未完成的回调
                    fileChooserCallback?.onReceiveValue(null)
                    fileChooserCallback = filePathCallback

                    // 不用 fileChooserParams.createIntent()——它会把网页 accept 属性写进 Intent，
                    // 导致系统选择器按 MIME 过滤（如 .srt 这种 MimeTypeMap 没注册的扩展名会被置灰）。
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        if (fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                        }
                    }
                    return try {
                        fileChooserLauncher.launch(intent)
                        true
                    } catch (e: Exception) {
                        fileChooserCallback = null
                        Toast.makeText(this@MainActivity, R.string.file_chooser_unavailable, Toast.LENGTH_SHORT).show()
                        false
                    }
                }

                // 处理 OAuth 等需要弹窗的页面（如 Google 登录），复用当前 WebView
                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: android.os.Message?
                ): Boolean {
                    // 用临时 WebView 获取弹窗的目标 URL，拦截广告弹窗
                    val tempWebView = WebView(this@MainActivity)
                    tempWebView.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val url = request?.url?.toString() ?: return true
                            val host = request.url?.host?.lowercase() ?: return true
                            if (isAdHost(host)) {
                                tempWebView.destroy()
                                return true // 拦截广告弹窗
                            }
                            // 非广告链接，在新标签页中打开
                            tempWebView.destroy()
                            createNewTab()
                            loadUrl(url)
                            return true
                        }
                    }
                    val transport = resultMsg?.obj as? WebView.WebViewTransport
                    transport?.webView = tempWebView
                    resultMsg?.sendToTarget()
                    return true
                }
            }

            // 通过触摸事件检测滑动方向，隐藏/显示工具栏
            // 这样即使页面内部 div 滚动（如 SPA 应用）也能检测到
            var touchStartY = 0f
            var lastTouchY = 0f
            var totalDy = 0f
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        touchStartY = event.y
                        lastTouchY = event.y
                        totalDy = 0f
                    }
                    MotionEvent.ACTION_MOVE -> {
                        // 工具栏动画期间 WebView 的高度/位置在变，event.y 会跳变；
                        // 跳过累加并同步基准点，避免反馈循环导致屏幕抖动
                        if (isToolbarAnimating) {
                            lastTouchY = event.y
                            totalDy = 0f
                        } else {
                            val dy = lastTouchY - event.y // 正值=手指上滑=页面下滚
                            totalDy += dy
                            lastTouchY = event.y
                            if (findTabByWebView(this) == activeTab && isShowingWebView) {
                                if (totalDy > 30 && !isToolbarHidden) {
                                    hideToolbar()
                                    totalDy = 0f
                                } else if (totalDy < -30 && isToolbarHidden) {
                                    showToolbar()
                                    totalDy = 0f
                                }
                            }
                        }
                    }
                }
                false // 不消费事件，让 WebView 正常处理触摸
            }

            // 下载监听
            setDownloadListener { url, _userAgent, contentDisposition, mimeType, _contentLength ->
                val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                showDownloadConfirmDialog(url, contentDisposition, mimeType, fileName)
            }

        }
    }

    // 继承 WebView 以定制文字选择菜单：把"有道翻译"挪到"复制"紧后面
    private inner class SelectionWebView(context: Context) : WebView(context) {
        override fun startActionMode(callback: ActionMode.Callback?): ActionMode? =
            super.startActionMode(wrapCallback(callback))

        override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? =
            super.startActionMode(wrapCallback(callback), type)

        // 用 Callback2 包装：文字选择是浮动工具栏(TYPE_FLOATING)，
        // 必须转发 onGetContentRect 才能浮在选中文字旁，否则会退化成屏幕底部的栏
        private fun wrapCallback(callback: ActionMode.Callback?): ActionMode.Callback =
            object : ActionMode.Callback2() {
                override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean =
                    callback?.onCreateActionMode(mode, menu) ?: true

                override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
                    // 先让 WebView 填充默认项（含异步加入的 PROCESS_TEXT 项）
                    callback?.onPrepareActionMode(mode, menu)
                    if (menu != null) customizeMenu(menu)
                    return true
                }

                override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
                    if (item?.itemId == MENU_ID_YOUDAO) {
                        evaluateJavascript("(function(){return window.getSelection().toString();})();") { value ->
                            val word = decodeJsString(value)
                            if (word.isNotBlank()) openYoudaoDict(word)
                        }
                        mode?.finish()
                        return true
                    }
                    return callback?.onActionItemClicked(mode, item) ?: false
                }

                override fun onDestroyActionMode(mode: ActionMode?) {
                    callback?.onDestroyActionMode(mode)
                }

                override fun onGetContentRect(mode: ActionMode?, view: View?, outRect: Rect?) {
                    val cb2 = callback as? ActionMode.Callback2
                    if (cb2 != null) {
                        cb2.onGetContentRect(mode, view, outRect)
                    } else {
                        super.onGetContentRect(mode, view, outRect)
                    }
                }
            }

        // 移除系统自动加到末尾的有道项，并在"复制"后面插入自定义的"有道翻译"
        private fun customizeMenu(menu: Menu) {
            // 1. 移除 WebView 自动添加的有道 PROCESS_TEXT 项（避免重复）
            for (i in menu.size() - 1 downTo 0) {
                val item = menu.getItem(i)
                if (item.itemId == MENU_ID_YOUDAO) continue
                val pkg = item.intent?.`package` ?: item.intent?.component?.packageName
                val isYoudao = pkg == YOUDAO_PACKAGE || (item.title?.contains("有道") == true)
                if (isYoudao) menu.removeItem(item.itemId)
            }
            // 2. 在"复制"紧后面插入自定义项（order 与复制相同，后插入即排在其后）
            if (menu.findItem(MENU_ID_YOUDAO) == null) {
                val copyTitle = getString(android.R.string.copy)
                var order = 2 // 复制的默认 order
                for (i in 0 until menu.size()) {
                    if (menu.getItem(i).title?.toString() == copyTitle) {
                        order = menu.getItem(i).order
                        break
                    }
                }
                menu.add(0, MENU_ID_YOUDAO, order, "有道翻译")
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            }
        }
    }

    // evaluateJavascript 返回的是 JSON 编码的字符串，解码为原始文本
    private fun decodeJsString(value: String?): String {
        if (value == null || value == "null") return ""
        return try {
            org.json.JSONArray("[$value]").getString(0)
        } catch (_: Exception) {
            value.trim('"')
        }
    }

    // 调用有道词典 App 查询选中的文字（标准 PROCESS_TEXT 划词机制）
    private fun openYoudaoDict(word: String) {
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, word.trim())
            putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
            setPackage(YOUDAO_PACKAGE)
        }
        try {
            startActivity(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(this, "未安装有道词典 App", Toast.LENGTH_SHORT).show()
            try {
                startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=$YOUDAO_PACKAGE")))
            } catch (_: android.content.ActivityNotFoundException) { }
        }
    }

    // ==================== 下载 ====================

    private fun showDownloadConfirmDialog(url: String, contentDisposition: String?, mimeType: String?, fileName: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.download_confirm)
            .setMessage(getString(R.string.download_confirm_message, fileName))
            .setPositiveButton(R.string.save) { _, _ ->
                requestDownload(url, contentDisposition, mimeType, fileName)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun requestDownload(url: String, contentDisposition: String?, mimeType: String?, fileName: String) {
        // Android 10+ 不需要存储权限，使用 MediaStore
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                pendingDownload = PendingDownload(url, contentDisposition, mimeType, fileName)
                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                return
            }
        }
        startDownload(url, contentDisposition, mimeType, fileName)
    }

    private fun startDownload(url: String, contentDisposition: String?, mimeType: String?, fileName: String) {
        // 如果同名文件已存在，自动加编号: app(1).apk, app(2).apk ...
        val actualFileName = getUniqueFileName(fileName)

        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setMimeType(mimeType)
            addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
            setTitle(actualFileName)
            setDescription(actualFileName)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, actualFileName)
        }
        val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        dm.enqueue(request)
        // 保存下载记录（用实际文件名）
        lifecycleScope.launch {
            app.database.downloadRecordDao().insert(
                com.swiftbrowser.data.entity.DownloadRecord(
                    fileName = actualFileName,
                    url = url,
                    mimeType = mimeType
                )
            )
        }
        Toast.makeText(this, getString(R.string.download_started, actualFileName), Toast.LENGTH_SHORT).show()
    }

    private fun getUniqueFileName(fileName: String): String {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        var target = File(downloadsDir, fileName)
        if (!target.exists()) return fileName

        val dotIndex = fileName.lastIndexOf('.')
        val baseName = if (dotIndex > 0) fileName.substring(0, dotIndex) else fileName
        val extension = if (dotIndex > 0) fileName.substring(dotIndex) else ""

        var counter = 1
        while (true) {
            val newName = "$baseName($counter)$extension"
            target = File(downloadsDir, newName)
            if (!target.exists()) return newName
            counter++
        }
    }

    // ==================== 广告拦截 ====================

    /** 广告/追踪域名列表 */
    private val adDomains = setOf(
        // Google 广告
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "pagead2.googlesyndication.com", "adservice.google.com",
        // "googletagservices.com", // 部分网站功能依赖
        // 成人站常见广告网络
        "juicyads.com", "juicyads.net",
        "exoclick.com", "exosrv.com", "exdynsrv.com",
        "trafficjunky.com", "trafficjunky.net",
        "trafficfactory.biz",
        "trafficstars.com",
        "tsyndicate.com",
        "realsrv.com",
        "syndication.realsrv.com",
        // 弹窗/重定向广告
        "popads.net", "popcash.net", "popunder.net",
        "propellerads.com", "propellerads.net", "propellerpops.com",
        "clickadu.com", "clickaine.com",
        "ad-maven.com", "ad-delivery.net",
        "adtng.com", "adtng.net",
        "hilltopads.net", "hilltopads.com",
        "adxpansion.com",
        "a-ads.com",
        "cpmstar.com",
        // 推送/通知广告
        "pushwelcome.com", "pushame.com", "pushails.com",
        "pushnest.com", "pushgroup.net",
        // 内容推荐广告
        "taboola.com", "taboolasyndication.com",
        "outbrain.com", "mgid.com", "revcontent.com",
        // 追踪
        "adsco.re", "adskeeper.co.uk", "adnium.com",
        "bidgear.com", "advertising.com",
        // 其他常见广告
        "acint.net", "dmp.theadex.com",
        "dischub.com", "s-bid.com",
        // missav 相关广告
        "magsrv.com", "myavlive.com", "bluetrafficstream.com",
        "xxxvjmp.com", "mavrtracktor.com"
    )

    private fun isAdHost(host: String): Boolean {
        return adDomains.any { domain ->
            host == domain || host.endsWith(".$domain")
        }
    }

    private fun findTabByWebView(webView: WebView?): Tab? {
        return tabs.find { it.webView == webView }
    }

    // ==================== 快速拨号 ====================

    private fun setupSpeedDial() {
        speedDialAdapter = SpeedDialAdapter(
            onClickSite = { bookmark -> loadUrl(bookmark.url ?: "") },
            onClickFolder = { folder, children -> showFolderContent(folder, children) },
            onDeleteSite = { bookmark -> confirmDeleteSpeedDial(bookmark) },
            onRenameSite = { bookmark -> showEditBookmarkDialog(bookmark) },
            onDeleteFolder = { folder -> confirmDeleteFolder(folder) },
            onRenameFolder = { folder -> showRenameFolderDialog(folder) },
            onStartDrag = { viewHolder -> itemTouchHelper.startDrag(viewHolder) },
            onBatchDelete = { bookmark -> confirmDeleteSpeedDial(bookmark) },
            onShowLiftMenu = { anchor, actions -> showLiftMenu(anchor, actions, speedDialAdapter) },
            onHideLiftMenu = { hideLiftMenuView() }
        )

        // 提起菜单浮层：点空白处关闭，点卡片本身不穿透
        binding.liftMenuOverlay.setOnClickListener { liftedAdapter?.clearLift() ?: hideLiftMenuView() }
        binding.liftMenuCard.setOnClickListener { /* 吸收点击，避免穿透到浮层关闭 */ }

        binding.rvSpeedDial.apply {
            layoutManager = GridLayoutManager(this@MainActivity, 5)
            adapter = speedDialAdapter
        }

        // 点击空白区域退出批量删除或移动模式
        binding.rvSpeedDial.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                if (binding.rvSpeedDial.findChildViewUnder(event.x, event.y) == null) {
                    if (speedDialAdapter.batchDeleteMode) {
                        speedDialAdapter.exitBatchDeleteMode()
                    }
                    if (speedDialAdapter.hasLifted()) {
                        speedDialAdapter.clearLift()
                    }
                }
            }
            false
        }

        val dragCallback = SpeedDialDragHelper(
            adapter = speedDialAdapter,
            onReorder = { reorderedList ->
                isReordering = true
                lifecycleScope.launch {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        app.database.runInTransaction {
                            kotlinx.coroutines.runBlocking {
                                reorderedList.forEachIndexed { index, item ->
                                    val id = when (item) {
                                        is SpeedDialItem.Site -> item.bookmark.id
                                        is SpeedDialItem.Folder -> item.folder.id
                                    }
                                    bookmarkDao.updatePosition(id, index)
                                }
                            }
                        }
                    }
                    isReordering = false
                }
            },
            onMerge = { dragItem, targetItem ->
                lifecycleScope.launch {
                    val sdFolderId = app.speedDialFolderId
                    when {
                        // 两个站点 → 创建新文件夹
                        dragItem is SpeedDialItem.Site && targetItem is SpeedDialItem.Site -> {
                            val maxPos = bookmarkDao.getMaxPosition(sdFolderId) ?: -1
                            val newFolderId = bookmarkDao.insert(
                                Bookmark(
                                    title = targetItem.bookmark.title,
                                    isFolder = true,
                                    parentId = sdFolderId,
                                    position = maxPos + 1
                                )
                            )
                            bookmarkDao.moveTo(targetItem.bookmark.id, newFolderId)
                            bookmarkDao.moveTo(dragItem.bookmark.id, newFolderId)
                        }
                        // 站点拖到文件夹上 → 移入文件夹
                        dragItem is SpeedDialItem.Site && targetItem is SpeedDialItem.Folder -> {
                            bookmarkDao.moveTo(dragItem.bookmark.id, targetItem.folder.id)
                        }
                        // 文件夹拖到站点上 → 把站点移入文件夹
                        dragItem is SpeedDialItem.Folder && targetItem is SpeedDialItem.Site -> {
                            bookmarkDao.moveTo(targetItem.bookmark.id, dragItem.folder.id)
                        }
                        // 两个文件夹 → 合并
                        dragItem is SpeedDialItem.Folder && targetItem is SpeedDialItem.Folder -> {
                            val children = bookmarkDao.getChildrenList(dragItem.folder.id)
                            for (child in children) {
                                bookmarkDao.moveTo(child.id, targetItem.folder.id)
                            }
                            bookmarkDao.deleteById(dragItem.folder.id)
                        }
                    }
                }
            }
        )

        itemTouchHelper = ItemTouchHelper(dragCallback)
        itemTouchHelper.attachToRecyclerView(binding.rvSpeedDial)

        observeSpeedDial()
    }

    private var speedDialObserverFolderId: Long = -1L
    private var speedDialLiveData: androidx.lifecycle.LiveData<List<Bookmark>>? = null

    private fun observeSpeedDial() {
        lifecycleScope.launch {
            while (app.speedDialFolderId == -1L) {
                kotlinx.coroutines.delay(100)
            }
            attachSpeedDialObserver(app.speedDialFolderId)
        }
    }

    private fun attachSpeedDialObserver(sdFolderId: Long) {
        if (sdFolderId == speedDialObserverFolderId) return
        // 移除旧的观察者
        speedDialLiveData?.removeObservers(this)
        speedDialObserverFolderId = sdFolderId

        speedDialLiveData = bookmarkDao.getChildrenByPosition(sdFolderId)
        speedDialLiveData!!.observe(this) { children ->
            if (isReordering) return@observe
            lifecycleScope.launch {
                val items = mutableListOf<SpeedDialItem>()

                for (child in children) {
                    if (child.isFolder) {
                        val grandChildren = bookmarkDao.getChildrenList(child.id)
                        if (grandChildren.isEmpty()) {
                            // 文件夹没有内容，自动删除
                            bookmarkDao.deleteById(child.id)
                        } else if (grandChildren.size == 1) {
                            // 文件夹只剩一个条目，自动移出到快速拨号根目录并删除空文件夹
                            val only = grandChildren[0]
                            bookmarkDao.moveTo(only.id, sdFolderId)
                            bookmarkDao.deleteById(child.id)
                            items.add(SpeedDialItem.Site(only))
                        } else {
                            items.add(SpeedDialItem.Folder(child, grandChildren))
                        }
                    } else {
                        items.add(SpeedDialItem.Site(child))
                    }
                }

                speedDialAdapter.submitList(items)
                binding.tvEmptySpeedDial.visibility =
                    if (items.isEmpty()) View.VISIBLE else View.GONE
                binding.rvSpeedDial.visibility =
                    if (items.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    // ==================== 快速拨号交互 ====================

    private var openFolderId: Long = -1L
    private lateinit var folderAdapter: SpeedDialAdapter
    private var folderLiveData: androidx.lifecycle.LiveData<List<Bookmark>>? = null
    private var folderObserver: androidx.lifecycle.Observer<List<Bookmark>>? = null

    private fun showFolderContent(folder: Bookmark, children: List<Bookmark>) {
        openFolderId = folder.id

        binding.tvFolderTitle.text = folder.title
        binding.tvFolderTitle.setOnClickListener {
            val input = EditText(this).apply {
                setText(folder.title)
                setPadding(60, 40, 60, 20)
            }
            AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle(R.string.rename_folder)
                .setView(input)
                .setPositiveButton(R.string.save) { _, _ ->
                    val newName = input.text.toString().trim()
                    if (newName.isNotEmpty()) {
                        lifecycleScope.launch {
                            bookmarkDao.update(folder.copy(title = newName))
                            binding.tvFolderTitle.text = newName
                        }
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        folderAdapter = SpeedDialAdapter(
            onClickSite = { bookmark ->
                closeFolderOverlay()
                loadUrl(bookmark.url ?: "")
            },
            onClickFolder = { _, _ -> },
            onDeleteSite = { bookmark -> confirmDeleteSpeedDial(bookmark) },
            onRenameSite = { bookmark -> showEditBookmarkDialog(bookmark) },
            // 文件夹内的站点提起后可"移出"到快速拨号根目录
            onMoveOutSite = { bookmark ->
                lifecycleScope.launch { bookmarkDao.moveTo(bookmark.id, app.speedDialFolderId) }
            },
            onStartDrag = { viewHolder -> folderItemTouchHelper?.startDrag(viewHolder) },
            onShowLiftMenu = { anchor, actions -> showLiftMenu(anchor, actions, folderAdapter) },
            onHideLiftMenu = { hideLiftMenuView() }
        )

        // 为文件夹内容配置 ItemTouchHelper 用于拖拽排序（与根目录一致：isReordering 保护写库期间外层 LiveData observer 触发）
        // 文件夹内只含站点、不支持嵌套文件夹，故关闭"中心悬停合并"
        val folderDragCallback = SpeedDialDragHelper(
            adapter = folderAdapter,
            allowMerge = false,
            onReorder = { reorderedList ->
                isReordering = true
                lifecycleScope.launch {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        app.database.runInTransaction {
                            kotlinx.coroutines.runBlocking {
                                reorderedList.forEachIndexed { index, item ->
                                    val id = when (item) {
                                        is SpeedDialItem.Site -> item.bookmark.id
                                        is SpeedDialItem.Folder -> item.folder.id
                                    }
                                    bookmarkDao.updatePosition(id, index)
                                }
                            }
                        }
                    }
                    isReordering = false
                }
            }
        )
        folderItemTouchHelper = ItemTouchHelper(folderDragCallback)

        binding.rvFolderItems.apply {
            layoutManager = GridLayoutManager(this@MainActivity, 4)
            adapter = folderAdapter
        }
        folderItemTouchHelper!!.attachToRecyclerView(binding.rvFolderItems)

        // 给 folderAdapter 接上 LiveData，让数据库写入后能自动同步回 adapter
        // 这跟根目录 attachSpeedDialObserver 的模式完全一致，由此修复"拖完松手回弹"的问题
        detachFolderObserver()
        folderLiveData = bookmarkDao.getChildrenByPosition(folder.id)
        folderObserver = androidx.lifecycle.Observer { items ->
            if (isReordering) return@Observer
            when {
                items.isEmpty() -> {
                    lifecycleScope.launch {
                        bookmarkDao.deleteById(folder.id)
                    }
                    closeFolderOverlay()
                }
                items.size == 1 -> {
                    lifecycleScope.launch {
                        bookmarkDao.moveTo(items[0].id, app.speedDialFolderId)
                        bookmarkDao.deleteById(folder.id)
                    }
                    closeFolderOverlay()
                }
                else -> {
                    folderAdapter.submitList(items.map { SpeedDialItem.Site(it) })
                }
            }
        }
        folderLiveData!!.observe(this, folderObserver!!)

        binding.folderOverlay.setOnClickListener { closeFolderOverlay() }
        binding.folderCard.setOnClickListener { /* 阻止穿透 */ }

        binding.folderOverlay.setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED ->
                    event.clipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true
                DragEvent.ACTION_DROP -> {
                    val bookmark = event.localState as? Bookmark ?: return@setOnDragListener false
                    lifecycleScope.launch {
                        bookmarkDao.moveTo(bookmark.id, app.speedDialFolderId)
                        // LiveData 会自动刷新 folderAdapter
                    }
                    true
                }
                else -> true
            }
        }

        binding.folderCard.setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED ->
                    event.clipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true
                DragEvent.ACTION_DROP -> true
                else -> true
            }
        }

        binding.folderOverlay.visibility = View.VISIBLE
    }

    private fun closeFolderOverlay() {
        // 收起文件夹内可能残留的提起菜单
        if (::folderAdapter.isInitialized && folderAdapter.hasLifted()) folderAdapter.clearLift()
        detachFolderObserver()
        binding.folderOverlay.visibility = View.GONE
        openFolderId = -1L
    }

    private fun detachFolderObserver() {
        folderObserver?.let { obs -> folderLiveData?.removeObserver(obs) }
        folderLiveData = null
        folderObserver = null
    }

    private fun showRenameFolderDialog(folder: Bookmark) {
        val input = EditText(this).apply {
            setText(folder.title)
            setPadding(60, 40, 60, 20)
        }
        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle(R.string.rename_folder)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    lifecycleScope.launch { bookmarkDao.update(folder.copy(title = newName)) }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteFolder(folder: Bookmark) {
        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle(R.string.confirm_delete)
            .setMessage("确定要删除「${folder.title}」文件夹及其所有内容吗？\n此操作不可撤销。")
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    deleteFolderRecursive(folder.id)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 递归删除文件夹及其所有子内容 */
    private suspend fun deleteFolderRecursive(folderId: Long) {
        val children = bookmarkDao.getChildrenList(folderId)
        for (child in children) {
            if (child.isFolder) {
                deleteFolderRecursive(child.id)
            }
            bookmarkDao.deleteById(child.id)
        }
        bookmarkDao.deleteById(folderId)
    }

    private fun showEditBookmarkDialog(bookmark: Bookmark) {
        val input = EditText(this).apply {
            setText(bookmark.title)
            setPadding(60, 40, 60, 20)
        }
        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle("编辑名称")
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val newTitle = input.text.toString().trim()
                if (newTitle.isNotEmpty()) {
                    lifecycleScope.launch { bookmarkDao.update(bookmark.copy(title = newTitle)) }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ==================== 地址栏 ====================

    private fun setupUrlBar() {
        binding.etUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                val input = binding.etUrl.text.toString().trim()
                if (input.isNotEmpty()) {
                    loadUrl(normalizeUrl(input))
                }
                true
            } else false
        }
    }

    private fun normalizeUrl(input: String): String {
        if (input.contains(".") && !input.contains(" ")) {
            if (!input.startsWith("http://") && !input.startsWith("https://")) {
                return "https://$input"
            }
            return input
        }
        return "https://www.google.com/search?q=${Uri.encode(input)}"
    }

    // ==================== 底部栏 ====================

    private fun setupBottomBar() {
        binding.btnBack.setOnClickListener {
            activeTab?.webView?.let { if (it.canGoBack()) it.goBack() }
        }

        binding.btnForward.setOnClickListener {
            val url = activeTab?.url
            if (!isShowingWebView && url != null && url != "about:blank") {
                // 在快速拨号页时，前进键恢复到当前标签之前浏览的页面
                binding.etUrl.setText(url)
                showWebView()
            } else {
                activeTab?.webView?.let { if (it.canGoForward()) it.goForward() }
            }
        }

        binding.btnHome.setOnClickListener { showHomePage() }

        // 底部新建标签按钮：新建一个标签页并显示主页
        binding.btnNewTabBottom.setOnClickListener { createNewTab() }

        binding.btnTabs.setOnClickListener { toggleTabOverlay() }

        // 长按标签按钮超过 2 秒：关闭当前标签页（并抑制随后的单击，避免误打开标签浮层）
        setupTabsLongPress()

        // 在底部工具栏左右滑动切换标签：左滑→下一个，右滑→上一个
        binding.bottomBar.onSwipeLeft = { switchToAdjacentTab(forward = true) }
        binding.bottomBar.onSwipeRight = { switchToAdjacentTab(forward = false) }
    }

    /** 切换到相邻标签；到达两端不循环。 */
    private fun switchToAdjacentTab(forward: Boolean) {
        if (tabs.size <= 1) return
        val current = activeTab ?: return
        val index = tabs.indexOf(current)
        if (index < 0) return
        val newIndex = if (forward) index + 1 else index - 1
        if (newIndex < 0 || newIndex >= tabs.size) return
        switchToTab(tabs[newIndex])
    }

    /** 长按标签按钮超过 2 秒时关闭当前标签页。 */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupTabsLongPress() {
        var longPressFired = false
        val closeRunnable = Runnable {
            longPressFired = true
            val tab = activeTab
            if (tab != null) {
                closeTab(tab)
                Toast.makeText(this, "已关闭标签页（剩 ${tabs.size} 个）", Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnTabs.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    longPressFired = false
                    v.postDelayed(closeRunnable, 1000L)
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    // 手指移出按钮范围时取消长按
                    if (event.x < 0 || event.y < 0 ||
                        event.x > v.width || event.y > v.height
                    ) {
                        v.removeCallbacks(closeRunnable)
                    }
                    false
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(closeRunnable)
                    // 长按已触发关闭时，消费抬起事件以抑制随后的单击
                    longPressFired
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(closeRunnable)
                    false
                }
                else -> false
            }
        }
    }

    // ==================== 菜单 ====================

    private fun setupMenuButton() {
        binding.btnRefresh.setOnClickListener {
            if (isShowingWebView) {
                activeTab?.webView?.reload()
            } else {
                refreshSpeedDialIcons()
            }
        }
        binding.btnMore.setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            popup.menuInflater.inflate(R.menu.main_menu, popup.menu)

            val loginItem = popup.menu.findItem(R.id.action_login)
            loginItem.title = if (cloudSync.isLoggedIn) getString(R.string.sign_out)
            else getString(R.string.sign_in)

            val volumeToggleItem = popup.menu.findItem(R.id.action_volume_scroll_toggle)
            volumeToggleItem.title = "音量键翻页:" + if (volumeScrollEnabled) "开" else "关"

            val t2sToggleItem = popup.menu.findItem(R.id.action_t2s_toggle)
            t2sToggleItem.title = "繁转简:" + if (t2sEnabled) "开" else "关"

            val desktopModeItem = popup.menu.findItem(R.id.action_desktop_mode_toggle)
            desktopModeItem.title = "桌面模式:" + if (desktopModeEnabled) "开" else "关"

            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_find_in_page -> {
                        if (isShowingWebView) {
                            showFindBar()
                        } else {
                            Toast.makeText(this, "请先浏览一个网页", Toast.LENGTH_SHORT).show()
                        }
                        true
                    }
                    R.id.action_save_mhtml -> {
                        saveCurrentPageAsMhtml()
                        true
                    }
                    R.id.action_open_mhtml -> {
                        openMhtmlFile()
                        true
                    }
                    R.id.action_bookmarks -> {
                        startActivity(Intent(this, BookmarkActivity::class.java))
                        true
                    }
                    R.id.action_history -> {
                        startActivity(Intent(this, HistoryActivity::class.java))
                        true
                    }
                    R.id.action_downloads -> {
                        startActivity(Intent(this, com.swiftbrowser.ui.download.DownloadActivity::class.java))
                        true
                    }
                    R.id.action_add_bookmark -> {
                        if (isShowingWebView && currentUrl != null) {
                            addCurrentPageToBookmark()
                        } else {
                            Toast.makeText(this, "请先浏览一个网页", Toast.LENGTH_SHORT).show()
                        }
                        true
                    }
                    R.id.action_add_speed_dial -> {
                        if (isShowingWebView && currentUrl != null) {
                            addCurrentPageToSpeedDial()
                        } else {
                            Toast.makeText(this, "请先浏览一个网页", Toast.LENGTH_SHORT).show()
                        }
                        true
                    }
                    R.id.action_batch_delete -> {
                        speedDialAdapter.enterBatchDeleteMode()
                        true
                    }
                    R.id.action_volume_scroll_toggle -> {
                        volumeScrollEnabled = !volumeScrollEnabled
                        Toast.makeText(
                            this,
                            "音量键翻页已" + if (volumeScrollEnabled) "开启" else "关闭",
                            Toast.LENGTH_SHORT
                        ).show()
                        true
                    }
                    R.id.action_volume_scroll_amount -> {
                        showVolumeScrollAmountDialog()
                        true
                    }
                    R.id.action_t2s_toggle -> {
                        t2sEnabled = !t2sEnabled
                        Toast.makeText(
                            this,
                            "繁转简已" + if (t2sEnabled) "开启" else "关闭",
                            Toast.LENGTH_SHORT
                        ).show()
                        if (t2sEnabled) {
                            // 立即对当前所有标签页应用转换
                            for (tab in tabs) {
                                tab.webView?.let { applyT2S(it) }
                            }
                        } else {
                            // 关闭时重新加载当前页以恢复原文
                            activeTab?.webView?.reload()
                        }
                        true
                    }
                    R.id.action_desktop_mode_toggle -> {
                        desktopModeEnabled = !desktopModeEnabled
                        Toast.makeText(
                            this,
                            "桌面模式已" + if (desktopModeEnabled) "开启" else "关闭",
                            Toast.LENGTH_SHORT
                        ).show()
                        // 对所有标签页应用新模式并重新加载，使布局生效
                        for (tab in tabs) {
                            tab.webView?.let { webView ->
                                applyDesktopMode(webView, desktopModeEnabled)
                                webView.reload()
                            }
                        }
                        true
                    }
                    R.id.action_login -> {
                        if (cloudSync.isLoggedIn) {
                            cloudSync.logout()
                            Toast.makeText(this, "已退出登录", Toast.LENGTH_SHORT).show()
                        } else {
                            startActivity(Intent(this, LoginActivity::class.java))
                        }
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }
    }

    // ==================== 长按提起菜单 ====================

    /** 当前持有提起态的适配器（根目录 / 文件夹内），用于关闭菜单时清除对应状态 */
    private var liftedAdapter: SpeedDialAdapter? = null

    /** 在锚点（被提起的图标）附近弹出大菜单 */
    private fun showLiftMenu(anchor: View, actions: List<LiftAction>, source: SpeedDialAdapter) {
        liftedAdapter = source

        val container = binding.liftMenuContainer
        container.removeAllViews()
        for (action in actions) {
            val btn = layoutInflater.inflate(
                R.layout.item_lift_menu_button, container, false
            ) as TextView
            btn.text = action.label
            if (action.destructive) {
                btn.setTextColor(ContextCompat.getColor(this, R.color.red))
            }
            btn.setOnClickListener {
                source.clearLift()
                action.onClick()
            }
            container.addView(btn)
        }

        binding.liftMenuOverlay.visibility = View.VISIBLE
        // 等卡片测量完成后再定位到图标旁边
        binding.liftMenuCard.post {
            val a = IntArray(2)
            anchor.getLocationOnScreen(a)
            val o = IntArray(2)
            binding.liftMenuOverlay.getLocationOnScreen(o)
            val ax = a[0] - o[0]
            val ay = a[1] - o[1]
            val cw = binding.liftMenuCard.width
            val ch = binding.liftMenuCard.height
            val ow = binding.liftMenuOverlay.width
            val oh = binding.liftMenuOverlay.height
            val gap = dpToPx(8)

            var x = ax + anchor.width / 2 - cw / 2
            var y = ay + anchor.height + gap
            // 下方放不下就放到图标上方
            if (y + ch > oh - gap) y = ay - ch - gap
            x = x.coerceIn(gap, (ow - cw - gap).coerceAtLeast(gap))
            y = y.coerceIn(gap, (oh - ch - gap).coerceAtLeast(gap))

            binding.liftMenuCard.translationX = x.toFloat()
            binding.liftMenuCard.translationY = y.toFloat()
        }
    }

    /** 仅收起菜单视图（提起态的清除由适配器负责） */
    private fun hideLiftMenuView() {
        binding.liftMenuOverlay.visibility = View.GONE
        binding.liftMenuContainer.removeAllViews()
        liftedAdapter = null
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun refreshSpeedDialIcons() {
        lifecycleScope.launch {
            // 清除 Glide 磁盘缓存（必须在 IO 线程）
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.bumptech.glide.Glide.get(this@MainActivity).clearDiskCache()
            }
            // 清除 Glide 内存缓存（必须在主线程）
            com.bumptech.glide.Glide.get(this@MainActivity).clearMemory()
            // 清除 FaviconProvider 的 link 图标缓存
            FaviconProvider.clearLinkIconCache()
            // 通知适配器重新绑定所有项，触发图标重新加载
            speedDialAdapter.notifyDataSetChanged()
            Toast.makeText(this@MainActivity, "图标已刷新", Toast.LENGTH_SHORT).show()
        }
    }

    // ==================== 繁转简 ====================

    /**
     * 对指定 WebView 应用桌面模式或普通模式。
     * 桌面模式：使用桌面版 User-Agent，并以宽视口渲染（让网站返回桌面布局）。
     * 普通模式：恢复移动版 User-Agent。
     */
    private fun applyDesktopMode(webView: WebView, enabled: Boolean) {
        val settings = webView.settings
        if (enabled) {
            // 将移动版 UA 转换为桌面版：替换平台标识并去掉 "Mobile"
            var ua = mobileUserAgent.replace(
                Regex("\\(Linux; Android.*?\\)"), "(X11; Linux x86_64)"
            )
            ua = ua.replace(Regex("\\s*Mobile\\s*"), " ").trim()
            settings.userAgentString = ua
        } else {
            settings.userAgentString = mobileUserAgent
        }
        // 两种模式都保持宽视口与缩略概览，确保页面正确适配
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
    }

    private fun applyT2S(webView: WebView) {
        val script = t2sScriptCache ?: buildT2SScript().also { t2sScriptCache = it }
        webView.evaluateJavascript(script, null)
    }

    private fun buildT2SScript(): String {
        val dict = assets.open("t2s.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        // 遍历 DOM 文本节点替换；用 MutationObserver 处理动态新增内容;
        // 跳过 <script>/<style>/<textarea>/<input>，避免破坏脚本与用户输入。
        return """
            (function() {
              if (window.__t2sInstalled) {
                if (window.__t2sRun) window.__t2sRun();
                return;
              }
              window.__t2sInstalled = true;
              var MAP = $dict;
              function conv(text) {
                if (!text) return text;
                var out = '';
                for (var i = 0; i < text.length; ) {
                  var code = text.charCodeAt(i);
                  var ch;
                  if (code >= 0xD800 && code <= 0xDBFF && i + 1 < text.length) {
                    ch = text.substr(i, 2);
                    i += 2;
                  } else {
                    ch = text.charAt(i);
                    i += 1;
                  }
                  out += MAP[ch] || ch;
                }
                return out;
              }
              var SKIP = { SCRIPT: 1, STYLE: 1, TEXTAREA: 1, INPUT: 1, CODE: 1, PRE: 1 };
              function walk(node) {
                if (!node) return;
                if (node.nodeType === 3) {
                  var v = node.nodeValue;
                  if (v) {
                    var c = conv(v);
                    if (c !== v) node.nodeValue = c;
                  }
                  return;
                }
                if (node.nodeType !== 1) return;
                if (SKIP[node.nodeName]) return;
                var ph = node.getAttribute && node.getAttribute('placeholder');
                if (ph) {
                  var cp = conv(ph);
                  if (cp !== ph) node.setAttribute('placeholder', cp);
                }
                var ttl = node.getAttribute && node.getAttribute('title');
                if (ttl) {
                  var ct = conv(ttl);
                  if (ct !== ttl) node.setAttribute('title', ct);
                }
                var kids = node.childNodes;
                for (var i = 0; i < kids.length; i++) walk(kids[i]);
              }
              window.__t2sRun = function() {
                if (document.body) walk(document.body);
                if (document.title) {
                  var nt = conv(document.title);
                  if (nt !== document.title) document.title = nt;
                }
              };
              window.__t2sRun();
              try {
                var mo = new MutationObserver(function(muts) {
                  for (var i = 0; i < muts.length; i++) {
                    var m = muts[i];
                    if (m.type === 'characterData') {
                      var t = m.target;
                      if (t && t.nodeType === 3) {
                        var p = t.parentNode;
                        if (!(p && SKIP[p.nodeName])) {
                          var v = t.nodeValue;
                          if (v) {
                            var c = conv(v);
                            if (c !== v) t.nodeValue = c;
                          }
                        }
                      }
                    } else if (m.addedNodes) {
                      for (var j = 0; j < m.addedNodes.length; j++) walk(m.addedNodes[j]);
                    }
                  }
                });
                mo.observe(document.documentElement || document.body, {
                  childList: true, subtree: true, characterData: true
                });
              } catch (e) {}
            })();
        """.trimIndent()
    }

    // ==================== 核心功能 ====================

    private fun loadUrl(url: String) {
        if (url.isEmpty()) return
        showWebView()
        activeTab?.webView?.loadUrl(url)
    }

    // ==================== MHTML 保存 / 打开 ====================

    /** 将当前网页保存为 MHTML 文件 */
    private fun saveCurrentPageAsMhtml() {
        val webView = activeTab?.webView
        if (!isShowingWebView || webView == null || currentUrl == null) {
            Toast.makeText(this, "请先浏览一个网页", Toast.LENGTH_SHORT).show()
            return
        }
        val tempFile = java.io.File(cacheDir, "mhtml_${System.currentTimeMillis()}.mhtml")
        webView.saveWebArchive(tempFile.absolutePath, false) { resultPath ->
            runOnUiThread {
                if (resultPath == null) {
                    Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show()
                    tempFile.delete()
                    return@runOnUiThread
                }
                pendingMhtmlTempFile = tempFile
                val baseName = (currentTitle?.takeIf { it.isNotBlank() } ?: "webpage")
                    .replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_")
                    .take(100)
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "multipart/related"
                    putExtra(Intent.EXTRA_TITLE, "$baseName.mhtml")
                }
                try {
                    saveMhtmlLauncher.launch(intent)
                } catch (e: Exception) {
                    pendingMhtmlTempFile = null
                    tempFile.delete()
                    Toast.makeText(this, "无法打开保存对话框", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** 选择并打开一个 MHTML 文件 */
    private fun openMhtmlFile() {
        // 不限制 MIME 类型：很多设备把 .mhtml 识别为 application/octet-stream，
        // 加白名单会导致这些文件变灰不可选，所以这里允许选择任意文件
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        try {
            openMhtmlLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openMhtmlFromUri(uri: Uri) {
        try {
            val tempFile = java.io.File(cacheDir, "open_${System.currentTimeMillis()}.mht")
            contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { input.copyTo(it) }
            } ?: run {
                Toast.makeText(this, "无法读取文件", Toast.LENGTH_SHORT).show()
                return
            }
            showWebView()
            activeTab?.webView?.loadUrl("file://${tempFile.absolutePath}")
        } catch (e: Exception) {
            Toast.makeText(this, "打开失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showWebView() {
        isShowingWebView = true
        binding.webViewContainer.visibility = View.VISIBLE
        binding.speedDialContainer.visibility = View.GONE
        if (speedDialAdapter.hasLifted()) speedDialAdapter.clearLift()
        hideTabOverlay()
    }

    private fun showHomePage() {
        isShowingWebView = false
        binding.webViewContainer.visibility = View.GONE
        binding.speedDialContainer.visibility = View.VISIBLE
        binding.etUrl.setText("")
        hideTabOverlay()
        // 回到首页时确保工具栏可见
        if (isToolbarHidden) showToolbar()
    }

    private fun hideToolbar() {
        if (isToolbarHidden || isToolbarAnimating) return
        isToolbarHidden = true
        isToolbarAnimating = true
        val toolbarH = binding.toolbar.height
        val bottomH = binding.bottomBar.height
        var finished = 0
        val onAnimEnd = {
            finished++
            if (finished == 2) isToolbarAnimating = false
        }
        // 通过 margin 动画平滑地释放空间，避免闪烁
        ValueAnimator.ofInt(0, toolbarH).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val value = it.animatedValue as Int
                val params = binding.toolbar.layoutParams as LinearLayout.LayoutParams
                params.topMargin = -value
                binding.toolbar.layoutParams = params
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { onAnimEnd() }
                override fun onAnimationCancel(animation: android.animation.Animator) { onAnimEnd() }
            })
            start()
        }
        ValueAnimator.ofInt(0, bottomH).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val value = it.animatedValue as Int
                val params = binding.bottomBar.layoutParams as LinearLayout.LayoutParams
                params.bottomMargin = -value
                binding.bottomBar.layoutParams = params
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { onAnimEnd() }
                override fun onAnimationCancel(animation: android.animation.Animator) { onAnimEnd() }
            })
            start()
        }
    }

    private fun showToolbar() {
        if (!isToolbarHidden || isToolbarAnimating) return
        isToolbarHidden = false
        isToolbarAnimating = true
        val toolbarParams = binding.toolbar.layoutParams as LinearLayout.LayoutParams
        val bottomParams = binding.bottomBar.layoutParams as LinearLayout.LayoutParams
        val curToolbarMargin = toolbarParams.topMargin
        val curBottomMargin = bottomParams.bottomMargin
        var finished = 0
        val onAnimEnd = {
            finished++
            if (finished == 2) isToolbarAnimating = false
        }
        ValueAnimator.ofInt(curToolbarMargin, 0).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val value = it.animatedValue as Int
                val params = binding.toolbar.layoutParams as LinearLayout.LayoutParams
                params.topMargin = value
                binding.toolbar.layoutParams = params
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { onAnimEnd() }
                override fun onAnimationCancel(animation: android.animation.Animator) { onAnimEnd() }
            })
            start()
        }
        ValueAnimator.ofInt(curBottomMargin, 0).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val value = it.animatedValue as Int
                val params = binding.bottomBar.layoutParams as LinearLayout.LayoutParams
                params.bottomMargin = value
                binding.bottomBar.layoutParams = params
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { onAnimEnd() }
                override fun onAnimationCancel(animation: android.animation.Animator) { onAnimEnd() }
            })
            start()
        }
    }

    private fun addCurrentPageToBookmark() {
        val url = currentUrl ?: return
        val title = currentTitle ?: url

        lifecycleScope.launch {
            val existing = bookmarkDao.getByUrl(url)

            // 获取所有文件夹，构建带路径的列表
            val allFolders = bookmarkDao.getAllFolders()
            val folderMap = allFolders.associateBy { it.id }

            fun buildPath(folder: Bookmark): String {
                val parts = mutableListOf(folder.title)
                var pid = folder.parentId
                while (pid != null) {
                    val parent = folderMap[pid] ?: break
                    parts.add(0, parent.title)
                    pid = parent.parentId
                }
                return parts.joinToString(" / ")
            }

            val folderNames = mutableListOf("根目录")
            val folderIds = mutableListOf<Long?>(null)
            for (f in allFolders) {
                folderNames.add(buildPath(f))
                folderIds.add(f.id)
            }

            // 已存在时用已有的标题和文件夹，否则用当前页面标题和根目录
            val editTitle = existing?.title ?: title
            val defaultFolderIndex = if (existing != null) {
                val idx = folderIds.indexOf(existing.parentId)
                if (idx >= 0) idx else 0
            } else 0

            runOnUiThread {
                showAddBookmarkDialog(editTitle, url, folderNames, folderIds, defaultFolderIndex, existing)
            }
        }
    }

    private fun showAddBookmarkDialog(
        defaultTitle: String,
        url: String,
        folderNames: List<String>,
        folderIds: List<Long?>,
        defaultFolderIndex: Int = 0,
        existingBookmark: Bookmark? = null
    ) {
        val isEdit = existingBookmark != null

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 32, 48, 0)
        }

        val etTitle = EditText(this).apply {
            setText(defaultTitle)
            hint = "书签标题"
            selectAll()
        }
        layout.addView(etTitle)

        val tvFolderLabel = android.widget.TextView(this).apply {
            text = "保存到："
            setPadding(0, 24, 0, 8)
        }
        layout.addView(tvFolderLabel)

        var selectedIndex = defaultFolderIndex
        val btnFolder = android.widget.Button(this).apply {
            text = folderNames[selectedIndex]
            isAllCaps = false
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("选择文件夹")
                    .setItems(folderNames.toTypedArray()) { _, which ->
                        selectedIndex = which
                        text = folderNames[which]
                    }
                    .show()
            }
        }
        layout.addView(btnFolder)

        AlertDialog.Builder(this)
            .setTitle(if (isEdit) "编辑书签" else "添加书签")
            .setView(layout)
            .setPositiveButton("确认") { _, _ ->
                val finalTitle = etTitle.text.toString().trim().ifEmpty { defaultTitle }
                val parentId = folderIds[selectedIndex]
                lifecycleScope.launch {
                    if (existingBookmark != null) {
                        // 更新已有书签
                        val updated = existingBookmark.copy(title = finalTitle, parentId = parentId)
                        bookmarkDao.update(updated)
                        Toast.makeText(this@MainActivity, "书签已更新", Toast.LENGTH_SHORT).show()
                    } else {
                        // 新增书签：同一文件夹内相同标题+URL不重复添加
                        val duplicate = if (parentId != null) {
                            bookmarkDao.findDuplicate(finalTitle, url, parentId)
                        } else {
                            bookmarkDao.findDuplicateInRoot(finalTitle, url)
                        }
                        if (duplicate != null) {
                            Toast.makeText(this@MainActivity, "该书签已存在于此文件夹中", Toast.LENGTH_SHORT).show()
                        } else {
                            val maxPos = if (parentId != null) {
                                bookmarkDao.getMaxPosition(parentId) ?: -1
                            } else {
                                bookmarkDao.getMaxPositionRoot() ?: -1
                            }
                            bookmarkDao.insert(Bookmark(title = finalTitle, url = url, parentId = parentId, position = maxPos + 1))
                            Toast.makeText(this@MainActivity, R.string.bookmark_added, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun addCurrentPageToSpeedDial() {
        val url = currentUrl ?: return
        val title = currentTitle ?: url

        lifecycleScope.launch {
            val sdFolderId = app.speedDialFolderId
            val duplicate = bookmarkDao.findDuplicate(title, url, sdFolderId)
            if (duplicate != null) {
                Toast.makeText(this@MainActivity, "该网页已在快速拨号中", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val maxPos = bookmarkDao.getMaxPosition(sdFolderId) ?: -1
            bookmarkDao.insert(
                Bookmark(title = title, url = url, parentId = sdFolderId, position = maxPos + 1)
            )
            Toast.makeText(this@MainActivity, R.string.speed_dial_added, Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDeleteSpeedDial(bookmark: Bookmark) {
        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle(R.string.confirm_delete)
            .setMessage(getString(R.string.confirm_delete_message, bookmark.title))
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    bookmarkDao.deleteById(bookmark.id)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }


    // ==================== 网页内搜索 ====================

    private fun setupKeyboardListener() {
        val rootView = binding.root
        rootView.viewTreeObserver.addOnGlobalLayoutListener {
            val rect = android.graphics.Rect()
            rootView.getWindowVisibleDisplayFrame(rect)
            val screenHeight = rootView.rootView.height
            val keypadHeight = screenHeight - rect.bottom
            if (keypadHeight > screenHeight * 0.15) {
                // 键盘弹出时隐藏底栏
                if (!isToolbarHidden) {
                    binding.bottomBar.visibility = View.GONE
                }
            } else {
                // 键盘收起时恢复底栏（仅在工具栏未隐藏时）
                if (!isToolbarHidden) {
                    binding.bottomBar.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun setupFindInPage() {
        binding.etFindInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performFind()
                true
            } else false
        }

        binding.btnFindNext.setOnClickListener {
            activeTab?.webView?.findNext(true)
        }

        binding.btnFindPrev.setOnClickListener {
            activeTab?.webView?.findNext(false)
        }

        binding.btnFindClose.setOnClickListener {
            hideFindBar()
        }
    }

    private fun showFindBar() {
        binding.findBar.visibility = View.VISIBLE
        binding.etFindInput.setText("")
        binding.tvFindCount.text = ""
        binding.etFindInput.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.etFindInput, InputMethodManager.SHOW_IMPLICIT)

        activeTab?.webView?.setFindListener { activeMatchOrdinal, numberOfMatches, isDoneCounting ->
            if (isDoneCounting) {
                binding.tvFindCount.text = if (numberOfMatches > 0)
                    "${activeMatchOrdinal + 1}/$numberOfMatches"
                else
                    "0/0"
            }
        }
    }

    private fun hideFindBar() {
        binding.findBar.visibility = View.GONE
        activeTab?.webView?.clearMatches()
        binding.etFindInput.setText("")
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etFindInput.windowToken, 0)
    }

    private fun performFind() {
        val query = binding.etFindInput.text.toString().trim()
        if (query.isNotEmpty()) {
            activeTab?.webView?.findAllAsync(query)
        }
    }

    // ==================== 音量键翻页 ====================

    private fun showVolumeScrollAmountDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        val hint = TextView(this).apply {
            text = "拖动滑块设置翻页幅度（左：一行，右：整屏）"
        }
        container.addView(hint)

        val valueLabel = TextView(this).apply {
            setPadding(0, 16, 0, 8)
        }
        container.addView(valueLabel)

        val seekBar = SeekBar(this).apply {
            max = 100
            progress = (volumeScrollRatio * 100).toInt().coerceIn(0, 100)
        }
        container.addView(seekBar)

        fun describe(progress: Int): String {
            return when {
                progress <= 0 -> "一行"
                progress >= 100 -> "整屏"
                else -> "${progress}%"
            }
        }
        valueLabel.text = "当前：" + describe(seekBar.progress)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                valueLabel.text = "当前：" + describe(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        AlertDialog.Builder(this)
            .setTitle("音量键翻页幅度")
            .setView(container)
            .setPositiveButton("确认") { _, _ ->
                volumeScrollRatio = seekBar.progress / 100f
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun computeVolumeScrollDistance(webView: WebView): Int {
        // 一行高度约 50dp；整屏 = webView.height。线性插值。
        val oneLinePx = (50 * resources.displayMetrics.density).toInt()
        val fullScreen = webView.height
        if (fullScreen <= oneLinePx) return oneLinePx
        return oneLinePx + ((fullScreen - oneLinePx) * volumeScrollRatio).toInt()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (volumeScrollEnabled && isShowingWebView && customView == null) {
            val webView = activeTab?.webView
            if (webView != null) {
                when (keyCode) {
                    KeyEvent.KEYCODE_VOLUME_DOWN -> {
                        webView.scrollBy(0, computeVolumeScrollDistance(webView))
                        return true
                    }
                    KeyEvent.KEYCODE_VOLUME_UP -> {
                        webView.scrollBy(0, -computeVolumeScrollDistance(webView))
                        return true
                    }
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        // 拦截 KeyUp 防止系统音量 UI 闪现
        if (volumeScrollEnabled && isShowingWebView && customView == null) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    // ==================== 返回键 ====================

    @Deprecated("Use OnBackPressedCallback")
    override fun onBackPressed() {
        // 全屏视频时按返回键退出全屏
        if (customView != null) {
            customViewCallback?.onCustomViewHidden()
            binding.customViewContainer.removeView(customView)
            binding.customViewContainer.visibility = View.GONE
            binding.mainContent.visibility = View.VISIBLE
            customView = null
            customViewCallback = null
            requestedOrientation = originalOrientation
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            return
        }
        if (binding.findBar.visibility == View.VISIBLE) {
            hideFindBar()
        } else if (binding.liftMenuOverlay.visibility == View.VISIBLE) {
            // 优先关闭提起菜单
            liftedAdapter?.clearLift() ?: hideLiftMenuView()
        } else if (binding.folderOverlay.visibility == View.VISIBLE) {
            // 优先关闭文件夹弹层
            closeFolderOverlay()
        } else if (speedDialAdapter.hasLifted()) {
            speedDialAdapter.clearLift()
        } else if (speedDialAdapter.batchDeleteMode) {
            speedDialAdapter.exitBatchDeleteMode()
        } else if (binding.tabOverlay.visibility == View.VISIBLE) {
            hideTabOverlay()
        } else if (isShowingWebView && activeTab?.webView?.canGoBack() == true) {
            activeTab?.webView?.goBack()
        } else if (isShowingWebView) {
            showHomePage()
        } else {
            super.onBackPressed()
        }
    }
}
