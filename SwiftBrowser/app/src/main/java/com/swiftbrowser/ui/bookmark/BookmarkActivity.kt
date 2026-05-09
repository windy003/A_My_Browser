package com.swiftbrowser.ui.bookmark

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
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
            if (folderStack.size > 1) {
                folderStack.removeLast()
                loadCurrentFolder()
            } else {
                finish()
            }
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
                            bookmarkDao.moveTo(bookmark.id, currentFolder?.parentId)
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
                            bookmarkDao.moveTo(bookmark.id, targetIds[which])
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
                            } else {
                                copyBookmarkTo(bookmark, targetId)
                            }
                            Toast.makeText(
                                this@BookmarkActivity,
                                "已复制到目标文件夹",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }

    /** 复制单个书签到目标文件夹 */
    private suspend fun copyBookmarkTo(bookmark: Bookmark, targetParentId: Long?) {
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
    }

    private fun exitBatchDeleteMode() {
        adapter.exitBatchDeleteMode()
        binding.batchActionBar.visibility = View.GONE
        binding.btnImport.visibility = View.VISIBLE
        binding.btnNewFolder.visibility = View.VISIBLE
        binding.btnCompare.visibility = View.VISIBLE
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

                // 用 URL 作为非文件夹书签的唯一标识进行比较
                val localUrls = localBookmarks.filter { !it.isFolder && it.url != null }
                    .map { it.url!! }.toSet()
                val cloudUrls = cloudBookmarks.filter { !it.isFolder && it.url != null }
                    .map { it.url!! }.toSet()

                // 本地有、云端没有的
                val onlyLocal = localBookmarks.filter {
                    !it.isFolder && it.url != null && it.url !in cloudUrls
                }
                // 云端有、本地没有的
                val onlyCloud = cloudBookmarks.filter {
                    !it.isFolder && it.url != null && it.url !in localUrls
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

    /** 构建云端书签的完整路径 */
    private fun buildCloudPath(
        cloud: CloudSyncManager.CloudBookmark,
        allCloud: List<CloudSyncManager.CloudBookmark>
    ): String {
        val parts = mutableListOf(cloud.title)
        var parentIdx = cloud.parentIndex
        while (parentIdx != null && parentIdx in allCloud.indices) {
            val parent = allCloud[parentIdx]
            parts.add(0, "【${parent.title}】")
            parentIdx = parent.parentIndex
        }
        return parts.joinToString(" - ")
    }

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
            val items = mutableListOf<CompareItem>()
            for (b in onlyLocal) {
                val path = buildLocalPath(b, allLocalBookmarks)
                items.add(CompareItem(
                    title = b.title,
                    path = path,
                    url = b.url!!,
                    isLocal = true,
                    localBookmark = b
                ))
            }
            for (b in onlyCloud) {
                val path = buildCloudPath(b, allCloudBookmarks)
                items.add(CompareItem(
                    title = b.title,
                    path = path,
                    url = b.url!!,
                    isLocal = false,
                    cloudBookmark = b
                ))
            }

            items.sortBy { it.path }

            val displayItems = items.map { item ->
                val prefix = if (item.isLocal) "【本地】" else "【云端】"
                "$prefix ${item.path}"
            }

            runOnUiThread {
                var compareDialog: AlertDialog? = null

                val listView = ListView(this@BookmarkActivity).apply {
                    adapter = ArrayAdapter(
                        this@BookmarkActivity,
                        android.R.layout.simple_list_item_1,
                        displayItems
                    )
                    setOnItemLongClickListener { _, _, position, _ ->
                        showCompareItemOptions(items[position], compareDialog)
                        true
                    }
                }

                val titleText = "本地独有: ${onlyLocal.size} 项 | 云端独有: ${onlyCloud.size} 项"

                val btnRefresh = android.widget.Button(this@BookmarkActivity).apply {
                    text = "刷新比较"
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(16, 0, 16, 8)
                    }
                }

                val dialogView = android.widget.LinearLayout(this@BookmarkActivity).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    addView(listView, android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                    ))

                    val btnBar = android.widget.LinearLayout(this@BookmarkActivity).apply {
                        orientation = android.widget.LinearLayout.HORIZONTAL
                        setPadding(16, 8, 16, 8)

                        val btnUpload = android.widget.Button(this@BookmarkActivity).apply {
                            text = "增量同步到云端"
                            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        }
                        val btnDownload = android.widget.Button(this@BookmarkActivity).apply {
                            text = "增量同步到本地"
                            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        }

                        btnUpload.setOnClickListener {
                            performIncrementalUpload(onlyLocal, compareDialog)
                        }
                        btnDownload.setOnClickListener {
                            performIncrementalDownload(onlyCloud, compareDialog)
                        }

                        addView(btnUpload)
                        addView(btnDownload)
                    }
                    addView(btnBar)
                    addView(btnRefresh)
                }

                val dialog = AlertDialog.Builder(this@BookmarkActivity, R.style.DialogTheme)
                    .setTitle(titleText)
                    .setView(dialogView)
                    .setNegativeButton("关闭", null)
                    .show()

                compareDialog = dialog

                btnRefresh.setOnClickListener {
                    dialog.dismiss()
                    compareWithCloud()
                }
            }
        }
    }

    private fun performIncrementalUpload(
        onlyLocal: List<Bookmark>,
        parentDialog: AlertDialog? = null
    ) {
        val cloudSync = app.cloudSyncManager
        if (!cloudSync.isLoggedIn) {
            Toast.makeText(this, "请先登录云端账号", Toast.LENGTH_SHORT).show()
            return
        }
        if (onlyLocal.isEmpty()) {
            Toast.makeText(this, "没有需要上传的书签", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "正在增量上传 ${onlyLocal.size} 条...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    for (bookmark in onlyLocal) {
                        cloudSync.uploadSingleBookmark(bookmark)
                    }
                }
                Toast.makeText(this@BookmarkActivity, "增量上传成功（${onlyLocal.size} 条）", Toast.LENGTH_SHORT).show()
                parentDialog?.dismiss()
                compareWithCloud()
            } catch (e: Exception) {
                Toast.makeText(this@BookmarkActivity, "上传失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun performIncrementalDownload(
        onlyCloud: List<CloudSyncManager.CloudBookmark>,
        parentDialog: AlertDialog? = null
    ) {
        val cloudSync = app.cloudSyncManager
        if (!cloudSync.isLoggedIn) {
            Toast.makeText(this, "请先登录云端账号", Toast.LENGTH_SHORT).show()
            return
        }
        if (onlyCloud.isEmpty()) {
            Toast.makeText(this, "没有需要下载的书签", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "正在增量下载 ${onlyCloud.size} 条...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                val parentId = currentFolder?.id
                var maxPos = if (parentId != null) {
                    bookmarkDao.getMaxPosition(parentId) ?: -1
                } else {
                    bookmarkDao.getMaxPositionRoot() ?: -1
                }
                for (cloud in onlyCloud) {
                    maxPos++
                    bookmarkDao.insert(
                        Bookmark(
                            title = cloud.title,
                            url = cloud.url,
                            isFolder = false,
                            parentId = parentId,
                            position = maxPos,
                            favicon = cloud.favicon
                        )
                    )
                }
                Toast.makeText(this@BookmarkActivity, "增量下载成功（${onlyCloud.size} 条）", Toast.LENGTH_SHORT).show()
                loadCurrentFolder()
                parentDialog?.dismiss()
                compareWithCloud()
            } catch (e: Exception) {
                Toast.makeText(this@BookmarkActivity, "下载失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showCompareItemOptions(item: CompareItem, parentDialog: AlertDialog? = null) {
        val options = if (item.isLocal) {
            arrayOf("上传到云端", "从本地删除")
        } else {
            arrayOf("下载到本地", "从云端删除")
        }

        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle(item.title)
            .setItems(options) { _, which ->
                when (options[which]) {
                    "上传到云端" -> {
                        item.localBookmark?.let { bookmark ->
                            lifecycleScope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        app.cloudSyncManager.uploadSingleBookmark(bookmark)
                                    }
                                    Toast.makeText(this@BookmarkActivity, "已上传到云端", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    Toast.makeText(this@BookmarkActivity, "上传失败: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                    "从本地删除" -> {
                        item.localBookmark?.let { bookmark ->
                            lifecycleScope.launch {
                                bookmarkDao.deleteById(bookmark.id)
                                Toast.makeText(this@BookmarkActivity, "已从本地删除", Toast.LENGTH_SHORT).show()
                                parentDialog?.dismiss()
                                compareWithCloud()
                            }
                        }
                    }
                    "下载到本地" -> {
                        item.cloudBookmark?.let { cloud ->
                            lifecycleScope.launch {
                                val parentId = currentFolder?.id
                                val maxPos = if (parentId != null) {
                                    bookmarkDao.getMaxPosition(parentId) ?: -1
                                } else {
                                    bookmarkDao.getMaxPositionRoot() ?: -1
                                }
                                bookmarkDao.insert(
                                    Bookmark(
                                        title = cloud.title,
                                        url = cloud.url,
                                        isFolder = false,
                                        parentId = parentId,
                                        position = maxPos + 1,
                                        favicon = cloud.favicon
                                    )
                                )
                                Toast.makeText(this@BookmarkActivity, "已下载到本地", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    "从云端删除" -> {
                        item.cloudBookmark?.let { cloud ->
                            lifecycleScope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        app.cloudSyncManager.deleteCloudBookmark(cloud.id)
                                    }
                                    Toast.makeText(this@BookmarkActivity, "已从云端删除", Toast.LENGTH_SHORT).show()
                                    parentDialog?.dismiss()
                                    compareWithCloud()
                                } catch (e: Exception) {
                                    Toast.makeText(this@BookmarkActivity, "删除失败: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                }
            }
            .show()
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
                // 跳过重复的 URL
                val existing = bookmarkDao.getByUrl(item.url)
                if (existing == null) {
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
