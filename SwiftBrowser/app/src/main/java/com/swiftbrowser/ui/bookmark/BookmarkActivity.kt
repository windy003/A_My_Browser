package com.swiftbrowser.ui.bookmark

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.swiftbrowser.R
import com.swiftbrowser.SwiftBrowserApp
import com.swiftbrowser.data.entity.Bookmark
import com.swiftbrowser.databinding.ActivityBookmarkBinding
import kotlinx.coroutines.launch

class BookmarkActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBookmarkBinding
    private lateinit var adapter: BookmarkAdapter

    private val app get() = application as SwiftBrowserApp
    private val bookmarkDao get() = app.database.bookmarkDao()
    private val syncManager get() = app.syncManager

    // 文件夹导航栈
    private val folderStack = mutableListOf<Bookmark?>() // null = 根目录
    private val currentFolder: Bookmark? get() = folderStack.lastOrNull()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBookmarkBinding.inflate(layoutInflater)
        setContentView(binding.root)

        folderStack.add(null) // 从根目录开始

        setupToolbar()
        setupRecyclerView()
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

        // 点击标题：在子文件夹内时重命名当前文件夹
        binding.tvToolbarTitle.setOnClickListener {
            val folder = currentFolder
            if (folder != null) {
                showRenameDialog(folder)
            }
        }

        // 长按标题可以新建文件夹
        binding.tvToolbarTitle.setOnLongClickListener {
            showNewFolderDialog()
            true
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
            }
        )

        binding.rvBookmarks.layoutManager = LinearLayoutManager(this)
        binding.rvBookmarks.adapter = adapter
    }

    private fun loadCurrentFolder() {
        val folder = currentFolder

        // 更新标题
        binding.tvToolbarTitle.text = folder?.title ?: getString(R.string.bookmarks)

        // 加载子项
        val liveData = if (folder != null) {
            bookmarkDao.getChildren(folder.id)
        } else {
            bookmarkDao.getRootItems()
        }

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
            options.add("重命名")
            options.add("删除文件夹")
        } else {
            options.add("移动到文件夹")
            // 如果在子文件夹中，显示"移出文件夹"
            if (currentFolder != null) {
                options.add("移出到上级")
            }
            options.add("删除")
        }

        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle(bookmark.title)
            .setItems(options.toTypedArray()) { _, which ->
                val selected = options[which]
                when (selected) {
                    "重命名" -> showRenameDialog(bookmark)
                    "删除文件夹" -> confirmDeleteFolder(bookmark)
                    "移动到文件夹" -> showMoveToFolderDialog(bookmark)
                    "移出到上级" -> {
                        lifecycleScope.launch {
                            bookmarkDao.moveTo(bookmark.id, currentFolder?.parentId)
                        }
                    }
                    "删除" -> confirmDelete(bookmark)
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

    // ==================== 重命名 ====================

    private fun showRenameDialog(bookmark: Bookmark) {
        val input = EditText(this).apply {
            setText(bookmark.title)
            setPadding(60, 40, 60, 20)
        }

        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle(R.string.rename_folder)
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
                    bookmarkDao.delete(bookmark)
                    bookmark.firebaseId?.let { syncManager.deleteFromCloud(it) }
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
        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle(R.string.confirm_delete)
            .setMessage(getString(R.string.delete_folder_message, folder.title))
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    // 子项移到上一级
                    val children = bookmarkDao.getChildrenList(folder.id)
                    for (child in children) {
                        bookmarkDao.moveTo(child.id, folder.parentId)
                    }
                    bookmarkDao.delete(folder)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ==================== 返回键 ====================

    @Deprecated("Use OnBackPressedCallback")
    override fun onBackPressed() {
        if (folderStack.size > 1) {
            folderStack.removeLast()
            loadCurrentFolder()
        } else {
            super.onBackPressed()
        }
    }
}
