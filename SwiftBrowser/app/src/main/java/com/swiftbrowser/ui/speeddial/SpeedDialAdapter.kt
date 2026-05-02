package com.swiftbrowser.ui.speeddial

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.swiftbrowser.data.entity.Bookmark
import com.swiftbrowser.databinding.ItemSpeedDialBinding
import com.swiftbrowser.databinding.ItemSpeedDialFolderBinding
import com.swiftbrowser.util.FaviconProvider

class SpeedDialAdapter(
    private val onClickSite: (Bookmark) -> Unit,
    private val onLongClickSite: (Bookmark) -> Unit,
    private val onClickFolder: (Bookmark, List<Bookmark>) -> Unit,
    private val onLongClickFolder: (Bookmark) -> Unit,
    private val onStartDrag: ((RecyclerView.ViewHolder) -> Unit)? = null,
    private val onBatchDelete: (Bookmark) -> Unit = {}
) : ListAdapter<SpeedDialItem, RecyclerView.ViewHolder>(DiffCallback()) {

    companion object {
        const val TYPE_SITE = 0
        const val TYPE_FOLDER = 1
        private const val PAYLOAD_BATCH_MODE = "batch_mode"
    }

    var highlightPosition: Int = -1
        set(value) {
            val old = field
            field = value
            if (old >= 0 && old < itemCount) notifyItemChanged(old)
            if (value >= 0 && value < itemCount) notifyItemChanged(value)
        }

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
        notifyItemRangeChanged(0, itemCount, PAYLOAD_BATCH_MODE)
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is SpeedDialItem.Site -> TYPE_SITE
            is SpeedDialItem.Folder -> TYPE_FOLDER
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_FOLDER -> {
                val binding = ItemSpeedDialFolderBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
                FolderViewHolder(binding)
            }
            else -> {
                val binding = ItemSpeedDialBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
                SiteViewHolder(binding)
            }
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.contains(PAYLOAD_BATCH_MODE)) {
            if (holder is SiteViewHolder) {
                holder.updateBatchDeleteUI()
            }
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val isHighlighted = position == highlightPosition
        holder.itemView.scaleX = if (isHighlighted) 1.15f else 1.0f
        holder.itemView.scaleY = if (isHighlighted) 1.15f else 1.0f
        holder.itemView.alpha = if (isHighlighted) 0.8f else 1.0f

        when (val item = getItem(position)) {
            is SpeedDialItem.Site -> (holder as SiteViewHolder).bind(item.bookmark)
            is SpeedDialItem.Folder -> (holder as FolderViewHolder).bind(item.folder, item.children)
        }
    }

    inner class SiteViewHolder(
        private val binding: ItemSpeedDialBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(bookmark: Bookmark) {
            binding.tvTitle.text = bookmark.title

            FaviconProvider.loadSpeedDialIcon(
                imageView = binding.ivIcon,
                url = bookmark.url ?: "",
                customIconUrl = bookmark.favicon
            )

            // X 仅作视觉指示
            binding.btnDelete.visibility = if (batchDeleteMode) View.VISIBLE else View.GONE

            // 点击：批量模式下任意位置删除，否则打开网站
            binding.root.setOnClickListener {
                if (batchDeleteMode) {
                    onBatchDelete(bookmark)
                } else {
                    onClickSite(bookmark)
                }
            }

            // 长按：弹出编辑/删除对话框
            binding.root.setOnLongClickListener {
                onLongClickSite(bookmark)
                true
            }
        }

        fun updateBatchDeleteUI() {
            binding.btnDelete.visibility = if (batchDeleteMode) View.VISIBLE else View.GONE
        }
    }

    inner class FolderViewHolder(
        private val binding: ItemSpeedDialFolderBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(folder: Bookmark, children: List<Bookmark>) {
            binding.tvFolderName.text = folder.title

            val previews = listOf(
                binding.ivPreview1, binding.ivPreview2,
                binding.ivPreview3, binding.ivPreview4
            )

            for (i in previews.indices) {
                if (i < children.size) {
                    previews[i].visibility = View.VISIBLE
                    FaviconProvider.loadSpeedDialIcon(
                        imageView = previews[i],
                        url = children[i].url ?: "",
                        customIconUrl = children[i].favicon
                    )
                } else {
                    previews[i].visibility = View.INVISIBLE
                }
            }

            binding.root.setOnClickListener { onClickFolder(folder, children) }
            binding.root.setOnLongClickListener {
                onLongClickFolder(folder)
                true
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<SpeedDialItem>() {
        override fun areItemsTheSame(oldItem: SpeedDialItem, newItem: SpeedDialItem): Boolean {
            return when {
                oldItem is SpeedDialItem.Site && newItem is SpeedDialItem.Site ->
                    oldItem.bookmark.id == newItem.bookmark.id
                oldItem is SpeedDialItem.Folder && newItem is SpeedDialItem.Folder ->
                    oldItem.folder.id == newItem.folder.id
                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: SpeedDialItem, newItem: SpeedDialItem): Boolean {
            return oldItem == newItem
        }
    }
}
