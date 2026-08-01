package com.swiftbrowser.ui.bookmark

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LiveData
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.swiftbrowser.R
import com.swiftbrowser.SwiftBrowserApp
import com.swiftbrowser.data.entity.Bookmark
import com.swiftbrowser.databinding.ActivityBookmarkBinding
import com.swiftbrowser.sync.CloudSyncManager
import com.swiftbrowser.util.FaviconProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BookmarkActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBookmarkBinding
    private lateinit var adapter: BookmarkAdapter

    private val app get() = application as SwiftBrowserApp
    private val bookmarkDao get() = app.database.bookmarkDao()

    // 文件夹导航栈
    private val folderStack = mutableListOf<Bookmark?>() // null = 根目录
    private val currentFolder: Bookmark? get() = folderStack.lastOrNull()

    /** 判断是否为快速拨号根文件夹（需保护，不可在根目录层删除） */
    private fun isSpeedDialFolder(bookmark: Bookmark): Boolean {
        if (!bookmark.isFolder) return false
        if (bookmark.id == app.speedDialFolderId && app.speedDialFolderId != -1L) return true
        return bookmark.title == Bookmark.SPEED_DIAL_FOLDER_NAME && bookmark.parentId == null
    }

    // 当前活跃的 LiveData，用于在切换文件夹时移除旧观察者
    private var currentLiveData: LiveData<List<Bookmark>>? = null

    // 搜索状态
    private var isSearching = false

    // 文件选择器：导入 Chrome 书签
    private val importFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { parseAndImportBookmarks(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBookmarkBinding.inflate(layoutInflater)
        setContentView(binding.root)

        folderStack.add(null) // 从根目录开始

        setupToolbar()
        setupRecyclerView()
        setupImportButton()
        setupBatchActionBar()
        loadCurrentFolder()
    }

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener {
            if (isSearching) {
                closeSearch()
            } else if (folderStack.size > 1) {
                folderStack.removeLast()
                loadCurrentFolder()
            } else {
                finish()
            }
        }

        // 刷新书签图标按钮：清缓存并联网重取当前列表所有书签的图标
        binding.btnRefreshIcons.setOnClickListener {
            refreshBookmarkIcons()
        }

        // 与云端比较按钮
        binding.btnCompare.setOnClickListener {
            compareWithCloud()
        }

        // 新建文件夹按钮
        binding.btnNewFolder.setOnClickListener {
            showNewFolderDialog()
        }

        // 点击标题：在子文件夹内时重命名当前文件夹（快速拨号文件夹除外）
        binding.tvToolbarTitle.setOnClickListener {
            val folder = currentFolder
            if (folder != null && !isSpeedDialFolder(folder)) {
                showRenameDialog(folder)
            }
        }

        // 搜索按钮
        binding.btnSearch.setOnClickListener { openSearch() }
        binding.btnSearchClose.setOnClickListener { closeSearch() }

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim() ?: ""
                performSearch(query)
            }
        })

        binding.etSearch.setOnEditorActionListener { _, _, _ ->
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
            true
        }
    }

    private fun openSearch() {
        isSearching = true
        binding.searchBar.visibility = View.VISIBLE
        binding.etSearch.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.etSearch, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeSearch() {
        isSearching = false
        binding.searchBar.visibility = View.GONE
        binding.etSearch.text.clear()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
        loadCurrentFolder()
    }

    private fun performSearch(query: String) {
        if (query.isEmpty()) {
            if (isSearching) {
                // 搜索框为空时显示当前文件夹内容
                loadCurrentFolder()
            }
            return
        }
        // 移除旧观察者
        currentLiveData?.removeObservers(this)
        currentLiveData = null

        lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                val folder = currentFolder
                if (folder != null) {
                    bookmarkDao.searchInFolder(folder.id, query)
                } else {
                    bookmarkDao.searchAll(query)
                }
            }
            adapter.submitList(results)
            binding.tvEmpty.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
            binding.rvBookmarks.visibility = if (results.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun setupRecyclerView() {
        adapter = BookmarkAdapter(
            onClickBookmark = { bookmark ->
                val mainIntent = Intent(
                    this,
                    com.swiftbrowser.ui.browser.MainActivity::class.java
                ).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    data = android.net.Uri.parse(bookmark.url)
                }
                startActivity(mainIntent)
                finish()
            },
            onClickFolder = { folder ->
                folderStack.add(folder)
                loadCurrentFolder()
            },
            onDelete = { bookmark ->
                confirmDelete(bookmark)
            },
            onLongClick = { bookmark ->
                showItemOptions(bookmark)
            },
            onSelectionChanged = { count ->
                binding.tvBatchCount.text = "已选择 $count 项"
            },
            isProtected = { bookmark -> isSpeedDialFolder(bookmark) }
        )

        binding.rvBookmarks.layoutManager = LinearLayoutManager(this)
        binding.rvBookmarks.adapter = adapter
    }

    private fun loadCurrentFolder() {
        val folder = currentFolder

        // 更新标题
        binding.tvToolbarTitle.text = folder?.title ?: getString(R.string.bookmarks)

        // 移除旧观察者，防止多次 observe 导致观察者堆积
        currentLiveData?.removeObservers(this)

        // 加载子项
        val liveData = if (folder != null) {
            bookmarkDao.getChildren(folder.id)
        } else {
            bookmarkDao.getRootItems()
        }

        currentLiveData = liveData
        liveData.observe(this) { items ->
            adapter.submitList(items)
            binding.tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            binding.rvBookmarks.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    /**
     * 刷新当前列表所有书签的图标。与快速拨号的刷新一致：
     * 清除 Glide 缓存与 link 解析缓存，标记当前书签 URL 本轮需要联网重取，
     * 然后重新绑定列表触发重新加载。平时书签图标只读本地缓存、不联网。
     */
    private fun refreshBookmarkIcons() {
        lifecycleScope.launch {
            // 清除 Glide 磁盘缓存（必须在 IO 线程）
            withContext(Dispatchers.IO) {
                com.bumptech.glide.Glide.get(this@BookmarkActivity).clearDiskCache()
            }
            // 清除 Glide 内存缓存（必须在主线程）
            com.bumptech.glide.Glide.get(this@BookmarkActivity).clearMemory()
            // 清除 link 图标解析缓存，强制重新抓取网页 <link>
            FaviconProvider.clearLinkIconCache()
            // 标记当前列表中所有书签的 URL 本轮需要联网重取
            val refreshUrls = adapter.currentList.mapNotNull { if (it.isFolder) null else it.url }
            FaviconProvider.beginForceRefresh(refreshUrls)
            // 重新绑定所有项，触发图标重新加载
            adapter.notifyItemRangeChanged(0, adapter.itemCount)
            Toast.makeText(this@BookmarkActivity, "图标已刷新", Toast.LENGTH_SHORT).show()
        }
    }

    // ==================== 长按选项 ====================

    private fun showItemOptions(bookmark: Bookmark) {
        val options = mutableListOf<String>()
        if (bookmark.isFolder) {
            if (!isSpeedDialFolder(bookmark)) {
                options.add("重命名")
            }
            options.add("复制文件夹到...")
            // 快速拨号根文件夹不允许删除
            if (!isSpeedDialFolder(bookmark)) {
                options.add("删除文件夹")
            }
        } else {
            options.add("重命名")
            options.add("移动到文件夹")
            options.add("复制到文件夹")
            // 如果在子文件夹中，显示"移出文件夹"
            if (currentFolder != null) {
                options.add("移出到上级")
            }
            options.add("删除")
        }
        options.add("多选删除")

        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle(bookmark.title)
            .setItems(options.toTypedArray()) { _, which ->
                val selected = options[which]
                when (selected) {
                    "重命名" -> showRenameDialog(bookmark)
                    "删除文件夹" -> confirmDeleteFolder(bookmark)
                    "移动到文件夹" -> showMoveToFolderDialog(bookmark)
                    "复制到文件夹" -> showCopyToFolderDialog(bookmark)
                    "复制文件夹到..." -> showCopyToFolderDialog(bookmark)
                    "移出到上级" -> {
                        lifecycleScope.launch {
                            val targetId = currentFolder?.parentId
                            if (bookmark.url != null) {
                                val duplicate = if (targetId != null) {
                                    bookmarkDao.findDuplicate(bookmark.title, bookmark.url, targetId)
                                } else {
                                    bookmarkDao.findDuplicateInRoot(bookmark.title, bookmark.url)
                                }
                                if (duplicate != null) {
                                    Toast.makeText(this@BookmarkActivity, "上级文件夹中已存在相同书签", Toast.LENGTH_SHORT).show()
                                    return@launch
                                }
                            }
                            bookmarkDao.moveTo(bookmark.id, targetId)
                        }
                    }
                    "删除" -> confirmDelete(bookmark)
                    "多选删除" -> enterBatchDeleteMode(bookmark)
                }
            }
            .show()
    }

    // ==================== 新建文件夹 ====================

    private fun showNewFolderDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.folder_name)
            setPadding(60, 40, 60, 20)
        }

        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle(R.string.new_folder)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    lifecycleScope.launch {
                        val parentId = currentFolder?.id
                        // 检查同文件夹下是否已有同名文件夹
                        val siblings = if (parentId != null) {
                            bookmarkDao.getChildrenList(parentId)
                        } else {
                            bookmarkDao.getRootItemsList()
                        }
                        if (siblings.any { it.isFolder && it.title == name }) {
                            Toast.makeText(this@BookmarkActivity, "已存在同名文件夹", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        val maxPos = if (parentId != null) {
                            bookmarkDao.getMaxPosition(parentId) ?: -1
                        } else {
                            bookmarkDao.getMaxPositionRoot() ?: -1
                        }
                        bookmarkDao.insert(
                            Bookmark(
                                title = name,
                                isFolder = true,
                                parentId = parentId,
                                position = maxPos + 1
                            )
                        )
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ==================== 移动到文件夹 ====================

    private fun showMoveToFolderDialog(bookmark: Bookmark) {
        lifecycleScope.launch {
            // 获取所有根目录文件夹
            val rootItems = bookmarkDao.getRootItemsList()
            val folders = rootItems.filter { it.isFolder && it.id != bookmark.id }

            // 如果当前在子文件夹中，也获取当前文件夹的兄弟文件夹
            val currentFolderId = currentFolder?.id

            // 构建选项列表
            val targetNames = mutableListOf<String>()
            val targetIds = mutableListOf<Long?>() // null = 根目录

            // 第一项：移到根目录（移出文件夹）
            if (currentFolder != null) {
                targetNames.add("根目录（移出文件夹）")
                targetIds.add(null)
            }

            // 所有可选文件夹
            for (folder in folders) {
                // 不显示当前所在的文件夹
                if (folder.id != currentFolderId) {
                    targetNames.add(folder.title)
                    targetIds.add(folder.id)
                }
            }

            if (targetNames.isEmpty()) {
                Toast.makeText(this@BookmarkActivity, "没有可用的目标", Toast.LENGTH_SHORT).show()
                return@launch
            }

            runOnUiThread {
                AlertDialog.Builder(this@BookmarkActivity, R.style.DialogTheme)
                    .setTitle(R.string.move_to_folder)
                    .setItems(targetNames.toTypedArray()) { _, which ->
                        lifecycleScope.launch {
                            val targetId = targetIds[which]
                            if (bookmark.url != null) {
                                val duplicate = if (targetId != null) {
                                    bookmarkDao.findDuplicate(bookmark.title, bookmark.url, targetId)
                                } else {
                                    bookmarkDao.findDuplicateInRoot(bookmark.title, bookmark.url)
                                }
                                if (duplicate != null) {
                                    Toast.makeText(this@BookmarkActivity, "目标文件夹中已存在相同书签", Toast.LENGTH_SHORT).show()
                                    return@launch
                                }
                            }
                            bookmarkDao.moveTo(bookmark.id, targetId)
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }

    // ==================== 复制到文件夹 ====================

    private fun showCopyToFolderDialog(bookmark: Bookmark) {
        lifecycleScope.launch {
            val rootItems = bookmarkDao.getRootItemsList()
            val folders = rootItems.filter { it.isFolder && it.id != bookmark.id }

            val currentFolderId = currentFolder?.id

            val targetNames = mutableListOf<String>()
            val targetIds = mutableListOf<Long?>() // null = 根目录

            // 第一项：根目录
            if (currentFolder != null) {
                targetNames.add("根目录")
                targetIds.add(null)
            }

            // 所有可选文件夹（排除当前所在文件夹，避免原地复制）
            for (folder in folders) {
                if (folder.id != currentFolderId) {
                    targetNames.add(folder.title)
                    targetIds.add(folder.id)
                }
            }

            if (targetNames.isEmpty()) {
                Toast.makeText(this@BookmarkActivity, "没有可用的目标", Toast.LENGTH_SHORT).show()
                return@launch
            }

            runOnUiThread {
                AlertDialog.Builder(this@BookmarkActivity, R.style.DialogTheme)
                    .setTitle("复制到文件夹")
                    .setItems(targetNames.toTypedArray()) { _, which ->
                        lifecycleScope.launch {
                            val targetId = targetIds[which]
                            if (bookmark.isFolder) {
                                copyFolderRecursive(bookmark.id, targetId)
                                Toast.makeText(
                                    this@BookmarkActivity,
                                    "已复制到目标文件夹",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                val copied = copyBookmarkTo(bookmark, targetId)
                                Toast.makeText(
                                    this@BookmarkActivity,
                                    if (copied) "已复制到目标文件夹" else "目标文件夹中已存在相同书签",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }

    /** 复制单个书签到目标文件夹（同文件夹内相同标题+URL不重复） */
    private suspend fun copyBookmarkTo(bookmark: Bookmark, targetParentId: Long?): Boolean {
        if (bookmark.url != null) {
            val duplicate = if (targetParentId != null) {
                bookmarkDao.findDuplicate(bookmark.title, bookmark.url, targetParentId)
            } else {
                bookmarkDao.findDuplicateInRoot(bookmark.title, bookmark.url)
            }
            if (duplicate != null) return false
        }
        val maxPos = if (targetParentId != null) {
            bookmarkDao.getMaxPosition(targetParentId) ?: -1
        } else {
            bookmarkDao.getMaxPositionRoot() ?: -1
        }
        bookmarkDao.insert(
            Bookmark(
                title = bookmark.title,
                url = bookmark.url,
                isFolder = false,
                parentId = targetParentId,
                position = maxPos + 1,
                favicon = bookmark.favicon
            )
        )
        return true
    }

    /** 递归复制文件夹及其所有内容到目标文件夹 */
    private suspend fun copyFolderRecursive(sourceFolderId: Long, targetParentId: Long?): Long {
        val source = bookmarkDao.getById(sourceFolderId) ?: return -1L
        val maxPos = if (targetParentId != null) {
            bookmarkDao.getMaxPosition(targetParentId) ?: -1
        } else {
            bookmarkDao.getMaxPositionRoot() ?: -1
        }
        // 创建新文件夹
        val newFolderId = bookmarkDao.insert(
            Bookmark(
                title = source.title,
                isFolder = true,
                parentId = targetParentId,
                position = maxPos + 1
            )
        )
        // 递归复制子项
        val children = bookmarkDao.getChildrenList(sourceFolderId)
        for (child in children) {
            if (child.isFolder) {
                copyFolderRecursive(child.id, newFolderId)
            } else {
                copyBookmarkTo(child, newFolderId)
            }
        }
        return newFolderId
    }

    // ==================== 重命名 ====================

    private fun showRenameDialog(bookmark: Bookmark) {
        if (isSpeedDialFolder(bookmark)) {
            Toast.makeText(this, "快速拨号文件夹不可重命名", Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this).apply {
            setText(bookmark.title)
            setPadding(60, 40, 60, 20)
        }

        val dialogTitle = if (bookmark.isFolder) R.string.rename_folder else R.string.rename_bookmark

        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle(dialogTitle)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    lifecycleScope.launch {
                        val updated = bookmark.copy(title = newName)
                        bookmarkDao.update(updated)
                        // 如果重命名的是当前打开的文件夹，更新栈和标题
                        if (currentFolder?.id == bookmark.id) {
                            folderStack[folderStack.lastIndex] = updated
                            runOnUiThread { binding.tvToolbarTitle.text = newName }
                        }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ==================== 删除 ====================

    private fun confirmDelete(bookmark: Bookmark) {
        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle(R.string.confirm_delete)
            .setMessage(getString(R.string.confirm_delete_message, bookmark.title))
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    bookmarkDao.deleteById(bookmark.id)
                    Toast.makeText(
                        this@BookmarkActivity,
                        R.string.bookmark_deleted,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteFolder(folder: Bookmark) {
        if (isSpeedDialFolder(folder)) {
            Toast.makeText(this, "快速拨号文件夹不可删除", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle(R.string.confirm_delete)
            .setMessage("确定要删除「${folder.title}」文件夹及其所有内容吗？\n此操作不可撤销。")
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    deleteFolderRecursive(folder.id)
                    Toast.makeText(
                        this@BookmarkActivity,
                        R.string.bookmark_deleted,
                        Toast.LENGTH_SHORT
                    ).show()
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

    // ==================== 批量删除 ====================

    private fun setupBatchActionBar() {
        binding.btnBatchSelectAll.setOnClickListener { adapter.selectAll() }
        binding.btnBatchDelete.setOnClickListener { confirmBatchDelete() }
        binding.btnBatchCancel.setOnClickListener { exitBatchDeleteMode() }
    }

    private fun enterBatchDeleteMode(initialBookmark: Bookmark? = null) {
        adapter.enterBatchDeleteMode()
        if (initialBookmark != null) {
            adapter.toggleSelection(initialBookmark)
        }
        binding.batchActionBar.visibility = View.VISIBLE
        binding.btnImport.visibility = View.GONE
        binding.btnNewFolder.visibility = View.GONE
        binding.btnCompare.visibility = View.GONE
        binding.btnRefreshIcons.visibility = View.GONE
    }

    private fun exitBatchDeleteMode() {
        adapter.exitBatchDeleteMode()
        binding.batchActionBar.visibility = View.GONE
        binding.btnImport.visibility = View.VISIBLE
        binding.btnNewFolder.visibility = View.VISIBLE
        binding.btnCompare.visibility = View.VISIBLE
        binding.btnRefreshIcons.visibility = View.VISIBLE
    }

    private fun confirmBatchDelete() {
        val selected = adapter.getSelectedBookmarks()
        if (selected.isEmpty()) {
            Toast.makeText(this, "请先选择要删除的书签", Toast.LENGTH_SHORT).show()
            return
        }

        val folderCount = selected.count { it.isFolder && !isSpeedDialFolder(it) }
        val message = if (folderCount > 0) {
            "确定要删除选中的 ${selected.size} 个书签（含 $folderCount 个文件夹）吗？\n文件夹及其所有内容将被彻底删除，此操作不可撤销。"
        } else {
            "确定要删除选中的 ${selected.size} 个书签吗？"
        }

        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle(R.string.confirm_delete)
            .setMessage(message)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    for (bookmark in selected) {
                        // 跳过快速拨号根文件夹
                        if (isSpeedDialFolder(bookmark)) continue
                        if (bookmark.isFolder) {
                            deleteFolderRecursive(bookmark.id)
                        } else {
                            bookmarkDao.deleteById(bookmark.id)
                        }
                    }
                    exitBatchDeleteMode()
                    Toast.makeText(
                        this@BookmarkActivity,
                        "已删除 ${selected.size} 个书签",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ==================== 与云端比较 ====================

    private fun compareWithCloud() {
        val cloudSync = app.cloudSyncManager
        if (!cloudSync.isLoggedIn) {
            Toast.makeText(this, "请先登录云端账号", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                Toast.makeText(this@BookmarkActivity, "正在获取云端数据...", Toast.LENGTH_SHORT).show()

                val cloudBookmarks = withContext(Dispatchers.IO) { cloudSync.fetchCloudBookmarks() }
                val localBookmarks = bookmarkDao.getAllList()

                // 用 路径 + URL + 标题 作为唯一标识进行比较
                // 必须路径完全相同+书签相同，才视为相同
                val localIdMap = localBookmarks.associateBy { it.id }
                fun localFolderPath(bookmark: Bookmark): String {
                    val parts = mutableListOf<String>()
                    var parentId = bookmark.parentId
                    while (parentId != null) {
                        val parent = localIdMap[parentId] ?: break
                        parts.add(0, parent.title)
                        parentId = parent.parentId
                    }
                    return parts.joinToString("/")
                }

                val cloudIdMap = cloudBookmarks.associateBy { it.id }
                fun cloudFolderPath(cloud: CloudSyncManager.CloudBookmark): String {
                    val parts = mutableListOf<String>()
                    var parentId = cloud.parentIndex
                    while (parentId != null) {
                        val parent = cloudIdMap[parentId] ?: break
                        parts.add(0, parent.title)
                        parentId = parent.parentIndex
                    }
                    return parts.joinToString("/")
                }

                val localKeys = localBookmarks.filter { !it.isFolder && it.url != null }
                    .map { "${localFolderPath(it)}\n${it.url}\n${it.title}" }.toSet()
                val cloudKeys = cloudBookmarks.filter { !it.isFolder && it.url != null }
                    .map { "${cloudFolderPath(it)}\n${it.url}\n${it.title}" }.toSet()

                // 本地有、云端没有的
                val onlyLocal = localBookmarks.filter {
                    !it.isFolder && it.url != null && "${localFolderPath(it)}\n${it.url}\n${it.title}" !in cloudKeys
                }
                // 云端有、本地没有的
                val onlyCloud = cloudBookmarks.filter {
                    !it.isFolder && it.url != null && "${cloudFolderPath(it)}\n${it.url}\n${it.title}" !in localKeys
                }

                showCompareResultDialog(onlyLocal, onlyCloud, localBookmarks, cloudBookmarks)
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@BookmarkActivity, "比较失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** 比较结果中的条目 */
    private data class CompareItem(
        val title: String,
        val path: String, // 完整路径，如 "文件夹1 - 文件夹2 - 书签名"
        val url: String,
        val isLocal: Boolean, // true=本地独有, false=云端独有
        val localBookmark: Bookmark? = null,
        val cloudBookmark: CloudSyncManager.CloudBookmark? = null
    )

    /** 构建本地书签的完整路径 */
    private suspend fun buildLocalPath(bookmark: Bookmark, allBookmarks: List<Bookmark>): String {
        val idMap = allBookmarks.associateBy { it.id }
        val parts = mutableListOf(bookmark.title)
        var parentId = bookmark.parentId
        while (parentId != null) {
            val parent = idMap[parentId] ?: break
            parts.add(0, "【${parent.title}】")
            parentId = parent.parentId
        }
        return parts.joinToString(" - ")
    }

    /** 构建云端书签的完整路径（按 parentId 查找，而非列表索引） */
    private fun buildCloudPath(
        cloud: CloudSyncManager.CloudBookmark,
        allCloud: List<CloudSyncManager.CloudBookmark>
    ): String {
        val idMap = allCloud.associateBy { it.id }
        val parts = mutableListOf(cloud.title)
        var parentId = cloud.parentIndex
        while (parentId != null) {
            val parent = idMap[parentId] ?: break
            parts.add(0, "【${parent.title}】")
            parentId = parent.parentIndex
        }
        return parts.joinToString(" - ")
    }

    /** 比较面板中的分组显示项（文件夹标题 或 书签条目） */
    private data class CompareDisplayItem(
        val text: String,
        val isHeader: Boolean, // 是否为文件夹分组标题
        val folderName: String, // 所属文件夹名（用于按文件夹删除）
        val compareItem: CompareItem? = null // 非标题行才有
    )

    private fun showCompareResultDialog(
        onlyLocal: List<Bookmark>,
        onlyCloud: List<CloudSyncManager.CloudBookmark>,
        allLocalBookmarks: List<Bookmark>,
        allCloudBookmarks: List<CloudSyncManager.CloudBookmark>
    ) {
        if (onlyLocal.isEmpty() && onlyCloud.isEmpty()) {
            Toast.makeText(this, "本地与云端书签完全一致", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            // 构建本地独有的 CompareItem
            val localItems = mutableListOf<CompareItem>()
            for (b in onlyLocal) {
                val path = buildLocalPath(b, allLocalBookmarks)
                localItems.add(CompareItem(
                    title = b.title, path = path, url = b.url!!,
                    isLocal = true, localBookmark = b
                ))
            }
            // 构建云端独有的 CompareItem
            val cloudItems = mutableListOf<CompareItem>()
            for (b in onlyCloud) {
                val path = buildCloudPath(b, allCloudBookmarks)
                cloudItems.add(CompareItem(
                    title = b.title, path = path, url = b.url!!,
                    isLocal = false, cloudBookmark = b
                ))
            }

            // 按文件夹分组的辅助函数：从 path 中提取文件夹部分
            fun extractFolder(path: String): String {
                val lastSep = path.lastIndexOf(" - ")
                return if (lastSep > 0) path.substring(0, lastSep) else "根目录"
            }

            // 构建带分组标题的显示列表
            fun buildDisplayList(items: List<CompareItem>): List<CompareDisplayItem> {
                val sorted = items.sortedBy { it.path }
                val result = mutableListOf<CompareDisplayItem>()
                var lastFolder = ""
                for (item in sorted) {
                    val folder = extractFolder(item.path)
                    if (folder != lastFolder) {
                        result.add(CompareDisplayItem(
                            text = "📁 $folder",
                            isHeader = true,
                            folderName = folder
                        ))
                        lastFolder = folder
                    }
                    result.add(CompareDisplayItem(
                        text = "    ${item.title}",
                        isHeader = false,
                        folderName = folder,
                        compareItem = item
                    ))
                }
                return result
            }

            val localDisplayItems = buildDisplayList(localItems).toMutableList()
            val cloudDisplayItems = buildDisplayList(cloudItems).toMutableList()

            // 用于追踪被用户删除的条目（从同步列表中排除）
            val removedLocalBookmarks = mutableSetOf<Long>() // 本地书签id
            val removedCloudBookmarkIds = mutableSetOf<Int>() // 云端书签id

            // 文件夹折叠状态（存储已折叠的文件夹名）
            val collapsedLocalFolders = mutableSetOf<String>()
            val collapsedCloudFolders = mutableSetOf<String>()

            // 从完整列表 + 折叠状态构建可见项和显示文本
            fun buildVisibleList(
                allItems: List<CompareDisplayItem>,
                collapsed: Set<String>
            ): Pair<List<CompareDisplayItem>, List<String>> {
                val visible = mutableListOf<CompareDisplayItem>()
                val texts = mutableListOf<String>()
                for (item in allItems) {
                    if (item.isHeader) {
                        val count = allItems.count { !it.isHeader && it.folderName == item.folderName }
                        val arrow = if (item.folderName in collapsed) "▶" else "▼"
                        visible.add(item)
                        texts.add("📁 $arrow ${item.folderName} ($count)")
                    } else if (item.folderName !in collapsed) {
                        visible.add(item)
                        texts.add(item.text)
                    }
                }
                return visible to texts
            }

            runOnUiThread {
                var compareDialog: AlertDialog? = null

                val ctx = this@BookmarkActivity

                // 手动展开 ListView 高度使其在 ScrollView 中完整显示
                fun expandListView(listView: ListView) {
                    val listAdapter = listView.adapter ?: return
                    // 宽度必须用 EXACTLY 传入真实宽度，否则标题过长时测量阶段
                    // 不会换行（按单行计算高度），而实际显示时会换行，导致
                    // 算出的总高度比真实渲染高度小，末尾内容被裁掉且无法再滑动
                    val widthSpec = if (listView.width > 0) {
                        View.MeasureSpec.makeMeasureSpec(listView.width, View.MeasureSpec.EXACTLY)
                    } else {
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                    }
                    var totalHeight = 0
                    for (i in 0 until listAdapter.count) {
                        val item = listAdapter.getView(i, null, listView)
                        item.measure(widthSpec, View.MeasureSpec.UNSPECIFIED)
                        totalHeight += item.measuredHeight
                    }
                    listView.layoutParams = listView.layoutParams?.apply {
                        height = totalHeight + listView.dividerHeight * (listAdapter.count - 1).coerceAtLeast(0)
                    }
                }

                // ===== 本地独有面板 =====
                val localLabel = android.widget.TextView(ctx).apply {
                    text = "本地独有（${localItems.size} 项）"
                    setPadding(24, 16, 24, 8)
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    textSize = 15f
                }
                var (localVisibleItems, localTexts) = buildVisibleList(localDisplayItems, collapsedLocalFolders)
                val localAdapter = ArrayAdapter(ctx, android.R.layout.simple_list_item_1, localTexts.toMutableList())
                val localListView = ListView(ctx).apply {
                    adapter = localAdapter
                }

                fun refreshLocalList() {
                    val (visible, texts) = buildVisibleList(localDisplayItems, collapsedLocalFolders)
                    localVisibleItems = visible
                    localAdapter.clear()
                    localAdapter.addAll(texts)
                    localAdapter.notifyDataSetChanged()
                    localListView.post { expandListView(localListView) }
                    localLabel.text = "本地独有（${localDisplayItems.count { !it.isHeader }} 项）"
                }

                // 点击文件夹标题：折叠/展开
                localListView.setOnItemClickListener { _, _, position, _ ->
                    val displayItem = localVisibleItems.getOrNull(position) ?: return@setOnItemClickListener
                    if (displayItem.isHeader) {
                        if (displayItem.folderName in collapsedLocalFolders) {
                            collapsedLocalFolders.remove(displayItem.folderName)
                        } else {
                            collapsedLocalFolders.add(displayItem.folderName)
                        }
                        refreshLocalList()
                    }
                }

                // 长按：删除
                localListView.setOnItemLongClickListener { _, _, position, _ ->
                    val displayItem = localVisibleItems.getOrNull(position) ?: return@setOnItemLongClickListener true
                    if (displayItem.isHeader) {
                        val folderName = displayItem.folderName
                        val folderItems = localDisplayItems.filter {
                            !it.isHeader && it.folderName == folderName
                        }
                        val count = folderItems.size
                        AlertDialog.Builder(ctx, R.style.DialogTheme)
                            .setTitle("删除本地书签")
                            .setMessage("确定从本地删除「$folderName」下的 $count 条书签？")
                            .setPositiveButton("删除") { _, _ ->
                                lifecycleScope.launch {
                                    for (fi in folderItems) {
                                        fi.compareItem?.localBookmark?.let { bm ->
                                            bookmarkDao.deleteById(bm.id)
                                            removedLocalBookmarks.add(bm.id)
                                        }
                                    }
                                    localDisplayItems.removeAll { it.folderName == folderName }
                                    collapsedLocalFolders.remove(folderName)
                                    refreshLocalList()
                                    Toast.makeText(ctx, "已删除 $count 条本地书签", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    } else {
                        val item = displayItem.compareItem ?: return@setOnItemLongClickListener true
                        AlertDialog.Builder(ctx, R.style.DialogTheme)
                            .setTitle("删除本地书签")
                            .setMessage("确定从本地删除「${item.title}」？")
                            .setPositiveButton("删除") { _, _ ->
                                lifecycleScope.launch {
                                    item.localBookmark?.let { bm ->
                                        bookmarkDao.deleteById(bm.id)
                                        removedLocalBookmarks.add(bm.id)
                                    }
                                    localDisplayItems.remove(displayItem)
                                    val folder = displayItem.folderName
                                    if (localDisplayItems.none { !it.isHeader && it.folderName == folder }) {
                                        localDisplayItems.removeAll { it.isHeader && it.folderName == folder }
                                        collapsedLocalFolders.remove(folder)
                                    }
                                    refreshLocalList()
                                    Toast.makeText(ctx, "已删除", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                    true
                }

                // ===== 云端独有面板 =====
                val cloudLabel = android.widget.TextView(ctx).apply {
                    text = "云端独有（${cloudItems.size} 项）"
                    setPadding(24, 16, 24, 8)
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    textSize = 15f
                }
                var (cloudVisibleItems, cloudTexts) = buildVisibleList(cloudDisplayItems, collapsedCloudFolders)
                val cloudAdapter = ArrayAdapter(ctx, android.R.layout.simple_list_item_1, cloudTexts.toMutableList())
                val cloudListView = ListView(ctx).apply {
                    adapter = cloudAdapter
                }

                fun refreshCloudList() {
                    val (visible, texts) = buildVisibleList(cloudDisplayItems, collapsedCloudFolders)
                    cloudVisibleItems = visible
                    cloudAdapter.clear()
                    cloudAdapter.addAll(texts)
                    cloudAdapter.notifyDataSetChanged()
                    cloudListView.post { expandListView(cloudListView) }
                    cloudLabel.text = "云端独有（${cloudDisplayItems.count { !it.isHeader }} 项）"
                }

                // 点击文件夹标题：折叠/展开
                cloudListView.setOnItemClickListener { _, _, position, _ ->
                    val displayItem = cloudVisibleItems.getOrNull(position) ?: return@setOnItemClickListener
                    if (displayItem.isHeader) {
                        if (displayItem.folderName in collapsedCloudFolders) {
                            collapsedCloudFolders.remove(displayItem.folderName)
                        } else {
                            collapsedCloudFolders.add(displayItem.folderName)
                        }
                        refreshCloudList()
                    }
                }

                // 长按：删除
                cloudListView.setOnItemLongClickListener { _, _, position, _ ->
                    val displayItem = cloudVisibleItems.getOrNull(position) ?: return@setOnItemLongClickListener true
                    if (displayItem.isHeader) {
                        val folderName = displayItem.folderName
                        val folderItems = cloudDisplayItems.filter {
                            !it.isHeader && it.folderName == folderName
                        }
                        val count = folderItems.size
                        AlertDialog.Builder(ctx, R.style.DialogTheme)
                            .setTitle("删除云端书签")
                            .setMessage("确定从云端删除「$folderName」下的 $count 条书签？")
                            .setPositiveButton("删除") { _, _ ->
                                lifecycleScope.launch {
                                    try {
                                        withContext(Dispatchers.IO) {
                                            for (fi in folderItems) {
                                                fi.compareItem?.cloudBookmark?.let { cb ->
                                                    app.cloudSyncManager.deleteCloudBookmark(cb.id)
                                                    removedCloudBookmarkIds.add(cb.id)
                                                }
                                            }
                                        }
                                        cloudDisplayItems.removeAll { it.folderName == folderName }
                                        collapsedCloudFolders.remove(folderName)
                                        refreshCloudList()
                                        Toast.makeText(ctx, "已删除 $count 条云端书签", Toast.LENGTH_SHORT).show()
                                    } catch (e: Exception) {
                                        Toast.makeText(ctx, "删除失败: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    } else {
                        val item = displayItem.compareItem ?: return@setOnItemLongClickListener true
                        AlertDialog.Builder(ctx, R.style.DialogTheme)
                            .setTitle("删除云端书签")
                            .setMessage("确定从云端删除「${item.title}」？")
                            .setPositiveButton("删除") { _, _ ->
                                lifecycleScope.launch {
                                    try {
                                        withContext(Dispatchers.IO) {
                                            item.cloudBookmark?.let { cb ->
                                                app.cloudSyncManager.deleteCloudBookmark(cb.id)
                                                removedCloudBookmarkIds.add(cb.id)
                                            }
                                        }
                                        cloudDisplayItems.remove(displayItem)
                                        val folder = displayItem.folderName
                                        if (cloudDisplayItems.none { !it.isHeader && it.folderName == folder }) {
                                            cloudDisplayItems.removeAll { it.isHeader && it.folderName == folder }
                                            collapsedCloudFolders.remove(folder)
                                        }
                                        refreshCloudList()
                                        Toast.makeText(ctx, "已删除", Toast.LENGTH_SHORT).show()
                                    } catch (e: Exception) {
                                        Toast.makeText(ctx, "删除失败: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                    true
                }

                // ===== 按钮栏 =====
                val btnBidirectional = android.widget.Button(ctx).apply {
                    text = "增量双向同步"
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(16, 8, 16, 0) }
                }

                val btnDedup = android.widget.Button(ctx).apply {
                    text = "两端去重"
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(16, 0, 16, 0) }
                }

                val btnRefresh = android.widget.Button(ctx).apply {
                    text = "刷新比较"
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(16, 0, 16, 8) }
                }

                val hint = android.widget.TextView(ctx).apply {
                    text = "长按书签或文件夹可删除，确认无误后点击同步"
                    setPadding(24, 8, 24, 4)
                    textSize = 12f
                    setTextColor(0xFF888888.toInt())
                }

                // ===== 组装布局 =====
                val scrollContent = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.VERTICAL

                    addView(localLabel)
                    addView(localListView, android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ))

                    addView(cloudLabel)
                    addView(cloudListView, android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ))
                }

                val scrollView = android.widget.ScrollView(ctx).apply {
                    addView(scrollContent)
                    // 展开 ListView 需要在布局完成后执行
                    post {
                        expandListView(localListView)
                        expandListView(cloudListView)
                    }
                }

                val dialogView = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    addView(hint)
                    addView(scrollView, android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                    ))
                    addView(btnBidirectional)
                    addView(btnDedup)
                    addView(btnRefresh)
                }

                val titleText = "本地独有: ${onlyLocal.size} 项 | 云端独有: ${onlyCloud.size} 项"
                val dialog = AlertDialog.Builder(ctx, R.style.DialogTheme)
                    .setTitle(titleText)
                    .setView(dialogView)
                    .setNegativeButton("关闭", null)
                    .show()

                // 固定对话框窗口高度（屏幕的 85%），避免 WRAP_CONTENT 与
                // expandListView 的异步高度回填产生竞争，导致底部按钮区域
                // 偶尔被挤出屏幕且内部 ScrollView 无法继续滚动到底部
                dialog.window?.setLayout(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    (ctx.resources.displayMetrics.heightPixels * 0.85).toInt()
                )

                compareDialog = dialog

                btnBidirectional.setOnClickListener {
                    // 过滤掉已被用户删除的条目
                    val finalLocal = onlyLocal.filter { it.id !in removedLocalBookmarks }
                    val finalCloud = onlyCloud.filter { it.id !in removedCloudBookmarkIds }
                    performBidirectionalSync(finalLocal, finalCloud, compareDialog)
                }

                btnDedup.setOnClickListener {
                    performDedup(dialog)
                }

                btnRefresh.setOnClickListener {
                    dialog.dismiss()
                    compareWithCloud()
                }
            }
        }
    }

    /** 获取本地书签的父文件夹链（从根到直接父级的文件夹名列表） */
    private fun getLocalFolderChain(bookmark: Bookmark, idMap: Map<Long, Bookmark>): List<String> {
        val chain = mutableListOf<String>()
        var parentId = bookmark.parentId
        while (parentId != null) {
            val parent = idMap[parentId] ?: break
            chain.add(0, parent.title)
            parentId = parent.parentId
        }
        return chain
    }

    /** 获取云端书签的父文件夹链（从根到直接父级的文件夹名列表） */
    private fun getCloudFolderChain(
        cloud: CloudSyncManager.CloudBookmark,
        idMap: Map<Int, CloudSyncManager.CloudBookmark>
    ): List<String> {
        val chain = mutableListOf<String>()
        var parentId = cloud.parentIndex
        while (parentId != null) {
            val parent = idMap[parentId] ?: break
            chain.add(0, parent.title)
            parentId = parent.parentIndex
        }
        return chain
    }

    /** 确保云端存在指定的文件夹路径，返回最末级文件夹的云端ID（空路径返回null=根目录） */
    private suspend fun ensureCloudFolderPath(
        folderChain: List<String>,
        cloudSync: CloudSyncManager,
        cache: MutableMap<String, Int>
    ): Int? {
        if (folderChain.isEmpty()) return null
        var currentPath = ""
        var parentId: Int? = null
        for (folderName in folderChain) {
            currentPath = if (currentPath.isEmpty()) folderName else "$currentPath/$folderName"
            if (cache.containsKey(currentPath)) {
                parentId = cache[currentPath]
            } else {
                val newId = cloudSync.createCloudFolder(folderName, parentId)
                cache[currentPath] = newId
                parentId = newId
            }
        }
        return parentId
    }

    /** 确保本地存在指定的文件夹路径，返回最末级文件夹的本地ID（空路径返回null=根目录） */
    private suspend fun ensureLocalFolderPath(
        folderChain: List<String>,
        cache: MutableMap<String, Long>
    ): Long? {
        if (folderChain.isEmpty()) return null
        var currentPath = ""
        var parentId: Long? = null
        for (folderName in folderChain) {
            currentPath = if (currentPath.isEmpty()) folderName else "$currentPath/$folderName"
            if (cache.containsKey(currentPath)) {
                parentId = cache[currentPath]
            } else {
                val maxPos = if (parentId != null) {
                    bookmarkDao.getMaxPosition(parentId) ?: -1
                } else {
                    bookmarkDao.getMaxPositionRoot() ?: -1
                }
                val newId = bookmarkDao.insert(
                    Bookmark(
                        title = folderName,
                        isFolder = true,
                        parentId = parentId,
                        position = maxPos + 1
                    )
                )
                cache[currentPath] = newId
                parentId = newId
            }
        }
        return parentId
    }

    /** 两端去重：本地和云端各自在同文件夹下去除 title+url 相同的重复书签 */
    private fun performDedup(parentDialog: AlertDialog? = null) {
        val cloudSync = app.cloudSyncManager
        lifecycleScope.launch {
            try {
                Toast.makeText(this@BookmarkActivity, "正在两端去重...", Toast.LENGTH_SHORT).show()

                val (localRemoved, cloudRemoved) = withContext(Dispatchers.IO) {
                    // === 本地去重 ===
                    val allLocal = bookmarkDao.getAllList()
                    // 按 parentId 分组，每组内按 title+url 去重（保留第一条）
                    var localCount = 0
                    val localNonFolders = allLocal.filter { !it.isFolder && it.url != null }
                    val localGroups = localNonFolders.groupBy { Triple(it.parentId, it.title, it.url) }
                    for ((_, group) in localGroups) {
                        if (group.size > 1) {
                            // 保留第一条，删除其余
                            for (i in 1 until group.size) {
                                bookmarkDao.deleteById(group[i].id)
                                localCount++
                            }
                        }
                    }

                    // === 云端去重 ===
                    var cloudCount = 0
                    if (cloudSync.isLoggedIn) {
                        val allCloud = cloudSync.fetchCloudBookmarks()
                        val cloudNonFolders = allCloud.filter { !it.isFolder && it.url != null }
                        val cloudGroups = cloudNonFolders.groupBy { Triple(it.parentIndex, it.title, it.url) }
                        for ((_, group) in cloudGroups) {
                            if (group.size > 1) {
                                for (i in 1 until group.size) {
                                    cloudSync.deleteCloudBookmark(group[i].id)
                                    cloudCount++
                                }
                            }
                        }
                    }

                    localCount to cloudCount
                }

                val msg = "去重完成：本地删除 $localRemoved 条，云端删除 $cloudRemoved 条"
                Toast.makeText(this@BookmarkActivity, msg, Toast.LENGTH_LONG).show()
                loadCurrentFolder()
                parentDialog?.dismiss()
                compareWithCloud()
            } catch (e: Exception) {
                Toast.makeText(this@BookmarkActivity, "去重失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun performBidirectionalSync(
        onlyLocal: List<Bookmark>,
        onlyCloud: List<CloudSyncManager.CloudBookmark>,
        parentDialog: AlertDialog? = null
    ) {
        val cloudSync = app.cloudSyncManager
        if (!cloudSync.isLoggedIn) {
            Toast.makeText(this, "请先登录云端账号", Toast.LENGTH_SHORT).show()
            return
        }
        if (onlyLocal.isEmpty() && onlyCloud.isEmpty()) {
            Toast.makeText(this, "没有需要同步的书签", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "正在双向同步：上传 ${onlyLocal.size} 条，下载 ${onlyCloud.size} 条...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // 获取最新的完整数据用于路径解析
                    val allLocal = bookmarkDao.getAllList()
                    val allCloud = cloudSync.fetchCloudBookmarks()
                    val localIdMap = allLocal.associateBy { it.id }
                    val cloudIdMap = allCloud.associateBy { it.id }

                    // === 上传本地独有的书签到云端（保留文件夹结构） ===
                    // 预填充云端已有的文件夹路径缓存
                    val cloudFolderCache = mutableMapOf<String, Int>()
                    for (cf in allCloud.filter { it.isFolder }) {
                        val chain = getCloudFolderChain(cf, cloudIdMap) + cf.title
                        cloudFolderCache[chain.joinToString("/")] = cf.id
                    }
                    for (bookmark in onlyLocal) {
                        val folderChain = getLocalFolderChain(bookmark, localIdMap)
                        val cloudParentId = ensureCloudFolderPath(folderChain, cloudSync, cloudFolderCache)
                        cloudSync.uploadSingleBookmark(bookmark, cloudParentId)
                    }

                    // === 下载云端独有的书签到本地（保留文件夹结构） ===
                    // 预填充本地已有的文件夹路径缓存
                    val localFolderCache = mutableMapOf<String, Long>()
                    for (lf in allLocal.filter { it.isFolder }) {
                        val chain = getLocalFolderChain(lf, localIdMap) + lf.title
                        localFolderCache[chain.joinToString("/")] = lf.id
                    }
                    for (cloud in onlyCloud) {
                        val folderChain = getCloudFolderChain(cloud, cloudIdMap)
                        val localParentId = ensureLocalFolderPath(folderChain, localFolderCache)
                        // 同文件夹内相同标题+URL不重复下载
                        if (cloud.url != null) {
                            val duplicate = if (localParentId != null) {
                                bookmarkDao.findDuplicate(cloud.title, cloud.url, localParentId)
                            } else {
                                bookmarkDao.findDuplicateInRoot(cloud.title, cloud.url)
                            }
                            if (duplicate != null) continue
                        }
                        val maxPos = if (localParentId != null) {
                            bookmarkDao.getMaxPosition(localParentId) ?: -1
                        } else {
                            bookmarkDao.getMaxPositionRoot() ?: -1
                        }
                        bookmarkDao.insert(
                            Bookmark(
                                title = cloud.title,
                                url = cloud.url,
                                isFolder = false,
                                parentId = localParentId,
                                position = maxPos + 1,
                                favicon = cloud.favicon
                            )
                        )
                    }
                }
                Toast.makeText(this@BookmarkActivity, "双向同步成功（上传 ${onlyLocal.size} 条，下载 ${onlyCloud.size} 条）", Toast.LENGTH_SHORT).show()
                loadCurrentFolder()
                parentDialog?.dismiss()
                compareWithCloud()
            } catch (e: Exception) {
                Toast.makeText(this@BookmarkActivity, "同步失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }


    // ==================== 导入 Chrome 书签 ====================

    private fun setupImportButton() {
        binding.btnImport.setOnClickListener {
            importFileLauncher.launch(arrayOf("text/html", "*/*"))
        }
    }

    private fun parseAndImportBookmarks(uri: Uri) {
        lifecycleScope.launch {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                val html = inputStream?.bufferedReader()?.readText() ?: return@launch
                inputStream.close()

                val parentId = currentFolder?.id
                val imported = withContext(Dispatchers.IO) {
                    val rootItems = parseChromeBookmarks(html)
                    insertParsedItems(rootItems, parentId)
                }

                Toast.makeText(
                    this@BookmarkActivity,
                    getString(R.string.import_success, imported),
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    this@BookmarkActivity,
                    "导入失败: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * 解析 Chrome 导出的书签 HTML。
     * 使用栈式状态机：遇到 H3 创建文件夹，遇到 <DL> 进入子层级，遇到 </DL> 返回上级。
     */
    private fun parseChromeBookmarks(html: String): List<ParsedBookmark> {
        val root = mutableListOf<ParsedBookmark>()
        val stack = mutableListOf(root) // 栈顶是当前正在填充的列表
        var pendingFolder: ParsedBookmark? = null // 最近创建的文件夹，等待 <DL> 进入

        val lines = html.lines()
        var started = false

        for (line in lines) {
            // 跳过直到第一个 <DL>（根列表开始）
            if (!started) {
                if (line.contains("<DL", ignoreCase = true)) {
                    started = true
                }
                continue
            }

            val trimmed = line.trim()

            // 开启子列表：将 pendingFolder 的 children 入栈
            if (trimmed.contains("<DL", ignoreCase = true) &&
                !trimmed.contains("</DL>", ignoreCase = true)) {
                val folder = pendingFolder
                if (folder != null) {
                    stack.add(folder.children)
                    pendingFolder = null
                }
                continue
            }

            // 关闭当前列表层级
            if (trimmed.contains("</DL>", ignoreCase = true)) {
                if (stack.size > 1) {
                    stack.removeLast()
                }
                continue
            }

            // 文件夹：<DT><H3 ...>Name</H3>
            if (trimmed.contains("<H3", ignoreCase = true)) {
                val name = extractTagContent(trimmed, "H3")
                if (name.isNotEmpty()) {
                    val folder = ParsedBookmark(title = name, isFolder = true)
                    stack.last().add(folder)
                    pendingFolder = folder
                }
                continue
            }

            // 书签：<DT><A HREF="..." ...>Name</A>
            if (trimmed.contains("<A ", ignoreCase = true)) {
                val href = Regex("HREF=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
                    .find(trimmed)?.groupValues?.getOrNull(1)
                val icon = Regex("ICON=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
                    .find(trimmed)?.groupValues?.getOrNull(1)
                val name = extractTagContent(trimmed, "A")

                if (!href.isNullOrBlank()) {
                    stack.last().add(
                        ParsedBookmark(
                            title = name.ifBlank { href },
                            url = href,
                            isFolder = false,
                            favicon = icon
                        )
                    )
                }
            }
        }

        return root
    }

    /** 从 HTML 行中提取标签内容，例如 <H3 ...>Name</H3> → "Name" */
    private fun extractTagContent(line: String, tag: String): String {
        val closeTag = "</$tag>"
        val endIndex = line.indexOf(closeTag)
        if (endIndex < 0) return ""
        val beforeClose = line.substring(0, endIndex)
        val startIndex = beforeClose.lastIndexOf('>')
        if (startIndex < 0) return ""
        return beforeClose.substring(startIndex + 1).trim()
    }

    /**
     * 递归插入解析后的书签数据到数据库。
     */
    private suspend fun insertParsedItems(
        items: List<ParsedBookmark>,
        parentId: Long?
    ): Int {
        var count = 0
        for (item in items) {
            if (item.isFolder) {
                val maxPos = if (parentId != null) {
                    bookmarkDao.getMaxPosition(parentId) ?: -1
                } else {
                    bookmarkDao.getMaxPositionRoot() ?: -1
                }
                val folderId = bookmarkDao.insert(
                    Bookmark(
                        title = item.title,
                        isFolder = true,
                        parentId = parentId,
                        position = maxPos + 1
                    )
                )
                count++
                count += insertParsedItems(item.children, folderId)
            } else {
                if (item.url.isNullOrBlank()) continue
                // 同文件夹内相同标题+URL不重复导入
                val duplicate = if (parentId != null) {
                    bookmarkDao.findDuplicate(item.title, item.url, parentId)
                } else {
                    bookmarkDao.findDuplicateInRoot(item.title, item.url)
                }
                if (duplicate == null) {
                    val maxPos = if (parentId != null) {
                        bookmarkDao.getMaxPosition(parentId) ?: -1
                    } else {
                        bookmarkDao.getMaxPositionRoot() ?: -1
                    }
                    bookmarkDao.insert(
                        Bookmark(
                            title = item.title,
                            url = item.url,
                            favicon = item.favicon,
                            parentId = parentId,
                            position = maxPos + 1
                        )
                    )
                    count++
                }
            }
        }
        return count
    }

    // ==================== 返回键 ====================

    @Deprecated("Use OnBackPressedCallback")
    override fun onBackPressed() {
        if (adapter.batchDeleteMode) {
            exitBatchDeleteMode()
        } else if (folderStack.size > 1) {
            folderStack.removeLast()
            loadCurrentFolder()
        } else {
            super.onBackPressed()
        }
    }
}

/** Chrome 书签解析中间数据结构 */
private data class ParsedBookmark(
    val title: String,
    val url: String? = null,
    val isFolder: Boolean = false,
    val favicon: String? = null,
    val children: MutableList<ParsedBookmark> = mutableListOf()
)
