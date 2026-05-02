package com.swiftbrowser.ui.bookmark

import android.view.LayoutInflater
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
    private val onLongClick: (Bookmark) -> Unit
) : ListAdapter<Bookmark, RecyclerView.ViewHolder>(DiffCallback()) {

    companion object {
        private const val TYPE_FOLDER = 0
        private const val TYPE_BOOKMARK = 1
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
            binding.root.setOnClickListener { onClickFolder(folder) }
            binding.root.setOnLongClickListener {
                onLongClick(folder)
                true
            }
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

            binding.root.setOnClickListener { onClickBookmark(bookmark) }
            binding.btnDelete.setOnClickListener { onDelete(bookmark) }
            binding.root.setOnLongClickListener {
                onLongClick(bookmark)
                true
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<Bookmark>() {
        override fun areItemsTheSame(oldItem: Bookmark, newItem: Bookmark) =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Bookmark, newItem: Bookmark) =
            oldItem == newItem
    }
}
