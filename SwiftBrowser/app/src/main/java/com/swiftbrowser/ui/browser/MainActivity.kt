package com.swiftbrowser.ui.browser

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.view.DragEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
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
import com.swiftbrowser.ui.speeddial.SpeedDialAdapter
import com.swiftbrowser.ui.speeddial.SpeedDialDragHelper
import com.swiftbrowser.ui.speeddial.SpeedDialItem
import com.swiftbrowser.util.FaviconProvider
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var speedDialAdapter: SpeedDialAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper

    private val app get() = application as SwiftBrowserApp
    private val bookmarkDao get() = app.database.bookmarkDao()
    private val historyDao get() = app.database.historyDao()
    private val cloudSync get() = app.cloudSyncManager

    // ==================== 多标签 ====================
    private val tabs = mutableListOf<Tab>()
    private var activeTab: Tab? = null
    private var isShowingWebView = false
    private var isReordering = false
    private var isToolbarHidden = false
    private lateinit var tabAdapter: TabAdapter

    // 全屏视频
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var originalOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    // 当前标签的快捷访问
    private val currentUrl: String? get() = activeTab?.url
    private val currentTitle: String? get() = activeTab?.title

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
        binding.tvTabCountBottom.text = tabs.size.toString()
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
        return WebView(this).apply {
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
            // 防止 WebView 字体跟随系统字体缩放
            settings.textZoom = 100

            // 伪装成普通浏览器，避免 Google 拒绝 WebView 中的 OAuth 登录
            settings.userAgentString = settings.userAgentString.replace("; wv", "")

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
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
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

                    // 横屏 + 全屏
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                    window.decorView.systemUiVisibility = (
                        View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    )
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
                        val dy = lastTouchY - event.y // 正值=手指上滑=页面下滚
                        totalDy += dy
                        lastTouchY = event.y
                        if (findTabByWebView(this) == activeTab && isShowingWebView) {
                            if (totalDy > 30 && !isToolbarHidden) {
                                hideToolbar()
                            } else if (totalDy < -30 && isToolbarHidden) {
                                showToolbar()
                            }
                        }
                    }
                }
                false // 不消费事件，让 WebView 正常处理触摸
            }

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
            onLongClickSite = { bookmark -> showSpeedDialSiteOptions(bookmark) },
            onClickFolder = { folder, children -> showFolderContent(folder, children) },
            onLongClickFolder = { folder -> showFolderOptions(folder) },
            onStartDrag = { viewHolder -> itemTouchHelper.startDrag(viewHolder) },
            onBatchDelete = { bookmark -> confirmDeleteSpeedDial(bookmark) }
        )

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
                    if (speedDialAdapter.moveMode) {
                        exitMoveMode()
                    }
                    if (speedDialAdapter.folderMode) {
                        exitFolderMode()
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

        speedDialLiveData = bookmarkDao.getChildren(sdFolderId)
        speedDialLiveData!!.observe(this) { children ->
            if (isReordering) return@observe
            lifecycleScope.launch {
                val items = mutableListOf<SpeedDialItem>()

                for (child in children) {
                    if (child.isFolder) {
                        val grandChildren = bookmarkDao.getChildrenList(child.id)
                        if (grandChildren.size == 1) {
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
            onLongClickSite = { bookmark -> showSpeedDialSiteOptions(bookmark) },
            onStartDrag = { viewHolder ->
                val position = viewHolder.bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return@SpeedDialAdapter
                val item = folderAdapter.currentList.getOrNull(position) as? SpeedDialItem.Site ?: return@SpeedDialAdapter
                val bookmark = item.bookmark
                val clipData = ClipData.newPlainText("bookmarkId", bookmark.id.toString())
                val shadow = View.DragShadowBuilder(viewHolder.itemView)
                binding.rvFolderItems.startDragAndDrop(clipData, shadow, bookmark, 0)
            },
            onClickFolder = { _, _ -> },
            onLongClickFolder = { }
        )

        binding.rvFolderItems.apply {
            layoutManager = GridLayoutManager(this@MainActivity, 4)
            adapter = folderAdapter
        }

        folderAdapter.submitList(children.map { SpeedDialItem.Site(it) })

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
                        refreshFolderOverlay()
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
        binding.folderOverlay.visibility = View.GONE
        openFolderId = -1L
    }

    private fun refreshFolderOverlay() {
        if (openFolderId == -1L) return
        lifecycleScope.launch {
            val children = bookmarkDao.getChildrenList(openFolderId)
            if (children.isEmpty()) {
                bookmarkDao.getById(openFolderId)?.let { bookmarkDao.deleteById(it.id) }
                closeFolderOverlay()
            } else if (children.size == 1) {
                bookmarkDao.moveTo(children[0].id, app.speedDialFolderId)
                bookmarkDao.getById(openFolderId)?.let { bookmarkDao.deleteById(it.id) }
                closeFolderOverlay()
            } else {
                folderAdapter.submitList(children.map { SpeedDialItem.Site(it) })
            }
        }
    }

    private fun showSpeedDialSiteOptions(bookmark: Bookmark) {
        val moveOutLabel = "移出文件夹"
        val options = mutableListOf("编辑", "删除")
        if (bookmark.parentId != app.speedDialFolderId) {
            options.add(moveOutLabel)
        }

        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle(bookmark.title)
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "编辑" -> showEditBookmarkDialog(bookmark)
                    "删除" -> confirmDeleteSpeedDial(bookmark)
                    moveOutLabel -> {
                        lifecycleScope.launch {
                            bookmarkDao.moveTo(bookmark.id, app.speedDialFolderId)
                        }
                    }
                }
            }
            .show()
    }

    private fun showFolderOptions(folder: Bookmark) {
        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle(folder.title)
            .setItems(arrayOf(
                getString(R.string.rename_folder),
                getString(R.string.delete_folder)
            )) { _, which ->
                when (which) {
                    0 -> showRenameFolderDialog(folder)
                    1 -> confirmDeleteFolder(folder)
                }
            }
            .show()
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
            activeTab?.webView?.let { if (it.canGoForward()) it.goForward() }
        }

        binding.btnHome.setOnClickListener { showHomePage() }

        binding.btnTabs.setOnClickListener { toggleTabOverlay() }
    }

    // ==================== 菜单 ====================

    private fun setupMenuButton() {
        binding.btnMore.setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            popup.menuInflater.inflate(R.menu.main_menu, popup.menu)

            val loginItem = popup.menu.findItem(R.id.action_login)
            loginItem.title = if (cloudSync.isLoggedIn) getString(R.string.sign_out)
            else getString(R.string.sign_in)

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
                    R.id.action_refresh -> {
                        if (isShowingWebView) {
                            activeTab?.webView?.reload()
                        } else {
                            refreshSpeedDialIcons()
                        }
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
                    R.id.action_move_mode -> {
                        enterMoveMode()
                        true
                    }
                    R.id.action_move_to_folder -> {
                        enterFolderMode()
                        true
                    }
                    R.id.action_batch_delete -> {
                        speedDialAdapter.enterBatchDeleteMode()
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

    private fun enterMoveMode() {
        speedDialAdapter.enterMoveMode()
        binding.tvMoveMode.text = "移动模式 — 长按图标拖拽排序，点击空白区域退出"
        binding.tvMoveMode.visibility = View.VISIBLE
        Toast.makeText(this, "已进入移动模式，长按图标拖拽排序", Toast.LENGTH_SHORT).show()
    }

    private fun exitMoveMode() {
        speedDialAdapter.exitMoveMode()
        binding.tvMoveMode.visibility = View.GONE
    }


    // ==================== 移入文件夹模式 ====================

    private fun enterFolderMode() {
        speedDialAdapter.enterFolderMode()
        binding.tvMoveMode.text = "移入文件夹模式 — 长按图标拖到另一个图标上松手，点击空白退出"
        binding.tvMoveMode.visibility = View.VISIBLE
        Toast.makeText(this, "已进入移入文件夹模式", Toast.LENGTH_SHORT).show()
    }

    private fun exitFolderMode() {
        speedDialAdapter.exitFolderMode()
        binding.tvMoveMode.visibility = View.GONE
    }

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

    // ==================== 核心功能 ====================

    private fun loadUrl(url: String) {
        if (url.isEmpty()) return
        showWebView()
        activeTab?.webView?.loadUrl(url)
    }

    private fun showWebView() {
        isShowingWebView = true
        binding.webViewContainer.visibility = View.VISIBLE
        binding.speedDialContainer.visibility = View.GONE
        if (speedDialAdapter.moveMode) exitMoveMode()
        if (speedDialAdapter.folderMode) exitFolderMode()
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
        if (isToolbarHidden) return
        isToolbarHidden = true
        binding.toolbar.animate()
            .translationY(-binding.toolbar.height.toFloat())
            .setDuration(200)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                binding.toolbar.visibility = View.GONE
            }
            .start()
    }

    private fun showToolbar() {
        if (!isToolbarHidden) return
        isToolbarHidden = false
        binding.toolbar.translationY = -binding.toolbar.height.toFloat()
        binding.toolbar.visibility = View.VISIBLE
        binding.toolbar.animate()
            .translationY(0f)
            .setDuration(200)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun addCurrentPageToBookmark() {
        val url = currentUrl ?: return
        val title = currentTitle ?: url

        lifecycleScope.launch {
            val existing = bookmarkDao.getByUrl(url)
            if (existing != null) {
                Toast.makeText(this@MainActivity, R.string.bookmark_exists, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val maxPos = bookmarkDao.getMaxPositionRoot() ?: -1
            bookmarkDao.insert(Bookmark(title = title, url = url, parentId = null, position = maxPos + 1))
            Toast.makeText(this@MainActivity, R.string.bookmark_added, Toast.LENGTH_SHORT).show()
        }
    }

    private fun addCurrentPageToSpeedDial() {
        val url = currentUrl ?: return
        val title = currentTitle ?: url

        lifecycleScope.launch {
            val existing = bookmarkDao.getByUrl(url)
            if (existing != null) {
                Toast.makeText(this@MainActivity, "该网页已在书签中", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val sdFolderId = app.speedDialFolderId
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
                // 键盘弹出
                binding.bottomBar.visibility = View.GONE
            } else {
                // 键盘收起
                binding.bottomBar.visibility = View.VISIBLE
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
        } else if (speedDialAdapter.moveMode) {
            exitMoveMode()
        } else if (speedDialAdapter.folderMode) {
            exitFolderMode()
        } else if (speedDialAdapter.batchDeleteMode) {
            speedDialAdapter.exitBatchDeleteMode()
        } else if (binding.tabOverlay.visibility == View.VISIBLE) {
            hideTabOverlay()
        } else if (binding.folderOverlay.visibility == View.VISIBLE) {
            closeFolderOverlay()
        } else if (isShowingWebView && activeTab?.webView?.canGoBack() == true) {
            activeTab?.webView?.goBack()
        } else if (isShowingWebView) {
            showHomePage()
        } else {
            super.onBackPressed()
        }
    }
}
