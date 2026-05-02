package com.swiftbrowser.ui.browser

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.view.DragEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.*
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.swiftbrowser.R
import com.swiftbrowser.SwiftBrowserApp
import com.swiftbrowser.data.entity.Bookmark
import com.swiftbrowser.databinding.ActivityMainBinding
import com.swiftbrowser.sync.SyncResult
import com.swiftbrowser.ui.auth.LoginActivity
import com.swiftbrowser.ui.bookmark.BookmarkActivity
import com.swiftbrowser.ui.history.HistoryActivity
import com.swiftbrowser.ui.speeddial.SpeedDialAdapter
import com.swiftbrowser.ui.speeddial.SpeedDialDragHelper
import com.swiftbrowser.ui.speeddial.SpeedDialItem
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var speedDialAdapter: SpeedDialAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper

    private val app get() = application as SwiftBrowserApp
    private val bookmarkDao get() = app.database.bookmarkDao()
    private val historyDao get() = app.database.historyDao()
    private val syncManager get() = app.syncManager

    // ==================== 多标签 ====================
    private val tabs = mutableListOf<Tab>()
    private var activeTab: Tab? = null
    private var isShowingWebView = false
    private var autoSyncJob: kotlinx.coroutines.Job? = null
    private lateinit var tabAdapter: TabAdapter

    // 当前标签的快捷访问
    private val currentUrl: String? get() = activeTab?.url
    private val currentTitle: String? get() = activeTab?.title

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSpeedDial()
        setupUrlBar()
        setupBottomBar()
        setupMenuButton()
        setupTabManager()
        setupSwipeRefresh()
        startAutoSync()

        // 创建第一个标签
        createNewTab()

        handleIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        autoSyncJob?.cancel()
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

        // 更新地址栏
        if (tab.url != null) {
            binding.etUrl.setText(tab.url)
            showWebView()
        } else {
            showHomePage()
        }
    }

    private fun closeTab(tab: Tab) {
        if (tabs.size <= 1) {
            // 最后一个标签，不关闭，而是清空
            tab.webView?.loadUrl("about:blank")
            tab.url = null
            tab.title = null
            tab.thumbnail = null
            showHomePage()
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
                        binding.swipeRefresh.isRefreshing = false
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
                    return false
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
            }

            setOnScrollChangeListener { _, _, scrollY, _, _ ->
                if (this == activeTab?.webView) {
                    binding.swipeRefresh.isEnabled = scrollY == 0
                }
            }
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

        // 批量删除模式下，点击空白区域退出
        binding.rvSpeedDial.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_DOWN && speedDialAdapter.batchDeleteMode) {
                if (binding.rvSpeedDial.findChildViewUnder(event.x, event.y) == null) {
                    speedDialAdapter.exitBatchDeleteMode()
                }
            }
            false
        }

        val dragCallback = SpeedDialDragHelper(
            adapter = speedDialAdapter,
            onMergeSites = { dragItem, targetItem ->
                lifecycleScope.launch {
                    val sdFolderId = app.speedDialFolderId
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
            },
            onMoveToFolder = { dragItem, targetFolder ->
                lifecycleScope.launch {
                    bookmarkDao.moveTo(dragItem.bookmark.id, targetFolder.folder.id)
                }
            }
        )

        itemTouchHelper = ItemTouchHelper(dragCallback)
        itemTouchHelper.attachToRecyclerView(binding.rvSpeedDial)

        observeSpeedDial()
    }

    private fun observeSpeedDial() {
        lifecycleScope.launch {
            while (app.speedDialFolderId == -1L) {
                kotlinx.coroutines.delay(100)
            }

            val sdFolderId = app.speedDialFolderId

            bookmarkDao.getChildren(sdFolderId).observe(this@MainActivity) { children ->
                lifecycleScope.launch {
                    val items = mutableListOf<SpeedDialItem>()

                    for (child in children) {
                        if (child.isFolder) {
                            val grandChildren = bookmarkDao.getChildrenList(child.id)
                            if (grandChildren.size == 1) {
                                bookmarkDao.moveTo(grandChildren[0].id, sdFolderId)
                                bookmarkDao.delete(child)
                                return@launch
                            } else if (grandChildren.isEmpty()) {
                                bookmarkDao.delete(child)
                                return@launch
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
            onLongClickSite = { bookmark ->
                val clipData = ClipData.newPlainText("bookmarkId", bookmark.id.toString())
                val shadow = View.DragShadowBuilder(
                    binding.rvFolderItems.findViewHolderForAdapterPosition(
                        folderAdapter.currentList.indexOfFirst {
                            it is SpeedDialItem.Site && it.bookmark.id == bookmark.id
                        }
                    )?.itemView
                )
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
                bookmarkDao.getById(openFolderId)?.let { bookmarkDao.delete(it) }
                closeFolderOverlay()
            } else if (children.size == 1) {
                bookmarkDao.moveTo(children[0].id, app.speedDialFolderId)
                bookmarkDao.getById(openFolderId)?.let { bookmarkDao.delete(it) }
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
            .setMessage(getString(R.string.delete_folder_message, folder.title))
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    val children = bookmarkDao.getChildrenList(folder.id)
                    for (child in children) {
                        bookmarkDao.moveTo(child.id, app.speedDialFolderId)
                    }
                    bookmarkDao.delete(folder)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
            loginItem.title = if (syncManager.isLoggedIn) getString(R.string.sign_out)
            else getString(R.string.sign_in)

            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
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
                    R.id.action_batch_delete -> {
                        speedDialAdapter.enterBatchDeleteMode()
                        true
                    }
                    R.id.action_sync -> {
                        performSync()
                        true
                    }
                    R.id.action_login -> {
                        if (syncManager.isLoggedIn) {
                            com.google.firebase.auth.FirebaseAuth.getInstance().signOut()
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

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.isEnabled = false
        binding.swipeRefresh.setOnRefreshListener {
            activeTab?.webView?.reload()
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
        binding.swipeRefresh.isEnabled = activeTab?.webView?.scrollY == 0
        hideTabOverlay()
    }

    private fun showHomePage() {
        isShowingWebView = false
        binding.webViewContainer.visibility = View.GONE
        binding.speedDialContainer.visibility = View.VISIBLE
        binding.swipeRefresh.isEnabled = false
        binding.etUrl.setText("")
        hideTabOverlay()
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
                    bookmarkDao.delete(bookmark)
                    bookmark.firebaseId?.let { syncManager.deleteFromCloud(it) }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startAutoSync() {
        autoSyncJob = lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(60_000)
                if (syncManager.isLoggedIn) {
                    syncManager.syncAll()
                }
            }
        }
    }

    private fun performSync() {
        if (!syncManager.isLoggedIn) {
            Toast.makeText(this, R.string.please_login, Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, LoginActivity::class.java))
            return
        }
        Toast.makeText(this, R.string.syncing, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            when (val result = syncManager.syncAll()) {
                is SyncResult.Success ->
                    Toast.makeText(this@MainActivity, R.string.sync_success, Toast.LENGTH_SHORT).show()
                is SyncResult.NotLoggedIn ->
                    Toast.makeText(this@MainActivity, R.string.please_login, Toast.LENGTH_SHORT).show()
                is SyncResult.Error ->
                    Toast.makeText(this@MainActivity, getString(R.string.sync_failed, result.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    // ==================== 返回键 ====================

    @Deprecated("Use OnBackPressedCallback")
    override fun onBackPressed() {
        if (speedDialAdapter.batchDeleteMode) {
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
