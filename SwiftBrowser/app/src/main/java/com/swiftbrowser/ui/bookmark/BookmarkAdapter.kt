package com.swiftbrowser.ui.bookmark

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.swiftbrowser.data.entity.Bookmark
import com.swiftbrowser.databinding.ItemBookmarkBinding
import com.swiftbrowser.databinding.ItemBookmarkFolderBinding
import com.swiftbrowser.util.FaviconProvider

class BookmarkAdapter(
    private val onClickBookmark: (Bookmark) -> Unit,
    private val onClickFolder: (Bookmark) -> Unit,
    private val onDelete: (Bookmark) -> Unit,
    private val onLongClick: (Bookmark) -> Unit,
    private val onSelectionChanged: (Int) -> Unit = {},
    private val isProtected: (Bookmark) -> Boolean = { false }
) : ListAdapter<Bookmark, RecyclerView.ViewHolder>(DiffCallback()) {

    companion object {
        private const val TYPE_FOLDER = 0
        private const val TYPE_BOOKMARK = 1
        private const val PAYLOAD_BATCH_MODE = "batch_mode"
    }

    /** 当前选中的书签 ID 集合 */
    val selectedIds = mutableSetOf<Long>()

    var batchDeleteMode: Boolean = false
        private set

    fun enterBatchDeleteMode() {
        if (batchDeleteMode) return
        batchDeleteMode = true
        notifyItemRangeChanged(0, itemCount, PAYLOAD_BATCH_MODE)
    }

    fun exitBatchDeleteMode() {
        if (!batchDeleteMode) return
        batchDeleteMode = false
        selectedIds.clear()
        notifyItemRangeChanged(0, itemCount, PAYLOAD_BATCH_MODE)
        onSelectionChanged(0)
    }

    fun toggleSelection(bookmark: Bookmark): Boolean {
        if (isProtected(bookmark)) return false
        if (selectedIds.contains(bookmark.id)) {
            selectedIds.remove(bookmark.id)
        } else {
            selectedIds.add(bookmark.id)
        }
        val pos = currentList.indexOf(bookmark)
        if (pos >= 0) notifyItemChanged(pos, PAYLOAD_BATCH_MODE)
        onSelectionChanged(selectedIds.size)
        return selectedIds.contains(bookmark.id)
    }

    fun selectAll() {
        for (item in currentList) {
            if (!isProtected(item)) {
                selectedIds.add(item.id)
            }
        }
        notifyItemRangeChanged(0, itemCount, PAYLOAD_BATCH_MODE)
        onSelectionChanged(selectedIds.size)
    }

    fun clearSelection() {
        selectedIds.clear()
        notifyItemRangeChanged(0, itemCount, PAYLOAD_BATCH_MODE)
        onSelectionChanged(0)
    }

    fun getSelectedBookmarks(): List<Bookmark> {
        return currentList.filter { selectedIds.contains(it.id) }
    }

    override fun getItemViewType(position: Int): Int {
        return if (getItem(position).isFolder) TYPE_FOLDER else TYPE_BOOKMARK
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_FOLDER -> {
                val binding = ItemBookmarkFolderBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
                FolderViewHolder(binding)
            }
            else -> {
                val binding = ItemBookmarkBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
                BookmarkViewHolder(binding)
            }
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.contains(PAYLOAD_BATCH_MODE)) {
            val item = getItem(position)
            when (holder) {
                is FolderViewHolder -> holder.updateBatchUI(item)
                is BookmarkViewHolder -> holder.updateBatchUI(item)
            }
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (holder) {
            is FolderViewHolder -> holder.bind(item)
            is BookmarkViewHolder -> holder.bind(item)
        }
    }

    inner class FolderViewHolder(
        private val binding: ItemBookmarkFolderBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(folder: Bookmark) {
            binding.tvFolderTitle.text = folder.title
            binding.cbSelect.visibility = if (batchDeleteMode) View.VISIBLE else View.GONE
            binding.cbSelect.isChecked = selectedIds.contains(folder.id)

            binding.root.setOnClickListener {
                if (batchDeleteMode) {
                    toggleSelection(folder)
                } else {
                    onClickFolder(folder)
                }
            }
            binding.root.setOnLongClickListener {
                if (!batchDeleteMode) {
                    onLongClick(folder)
                }
                true
            }
        }

        fun updateBatchUI(folder: Bookmark) {
            binding.cbSelect.visibility = if (batchDeleteMode) View.VISIBLE else View.GONE
            binding.cbSelect.isChecked = selectedIds.contains(folder.id)
        }
    }

    inner class BookmarkViewHolder(
        private val binding: ItemBookmarkBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(bookmark: Bookmark) {
            binding.tvTitle.text = bookmark.title
            binding.tvUrl.text = bookmark.url ?: ""

            FaviconProvider.loadBookmarkFavicon(
                imageView = binding.ivFavicon,
                url = bookmark.url ?: "",
                customFavicon = bookmark.favicon
            )

            binding.cbSelect.visibility = if (batchDeleteMode) View.VISIBLE else View.GONE
            binding.cbSelect.isChecked = selectedIds.contains(bookmark.id)
            binding.btnDelete.visibility = if (batchDeleteMode) View.GONE else View.VISIBLE

            binding.root.setOnClickListener {
                if (batchDeleteMode) {
                    toggleSelection(bookmark)
                } else {
                    onClickBookmark(bookmark)
                }
            }
            binding.btnDelete.setOnClickListener { onDelete(bookmark) }
            binding.root.setOnLongClickListener {
                if (!batchDeleteMode) {
                    onLongClick(bookmark)
                }
                true
            }
        }

        fun updateBatchUI(bookmark: Bookmark) {
            binding.cbSelect.visibility = if (batchDeleteMode) View.VISIBLE else View.GONE
            binding.cbSelect.isChecked = selectedIds.contains(bookmark.id)
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<Bookmark>() {
        override fun areItemsTheSame(oldItem: Bookmark, newItem: Bookmark) =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Bookmark, newItem: Bookmark) =
            oldItem == newItem
    }
}
