package com.swiftbrowser.ui.speeddial

import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
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
    private val onStartDrag: ((RecyclerView.ViewHolder) -> Unit)? = null
) : ListAdapter<SpeedDialItem, RecyclerView.ViewHolder>(DiffCallback()) {

    companion object {
        const val TYPE_SITE = 0
        const val TYPE_FOLDER = 1
    }

    var highlightPosition: Int = -1
        set(value) {
            val old = field
            field = value
            if (old >= 0 && old < itemCount) notifyItemChanged(old)
            if (value >= 0 && value < itemCount) notifyItemChanged(value)
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

        private val handler = Handler(Looper.getMainLooper())
        private var showDeleteRunnable: Runnable? = null
        private var downX = 0f
        private var downY = 0f
        private val touchSlop by lazy { ViewConfiguration.get(binding.root.context).scaledTouchSlop }

        fun bind(bookmark: Bookmark) {
            // 重置状态（view 可能被复用，仅重置 elevation，scale/alpha 由 onBindViewHolder 管理）
            showDeleteRunnable?.let { handler.removeCallbacks(it) }
            showDeleteRunnable = null
            itemView.elevation = 0f

            binding.tvTitle.text = bookmark.title

            FaviconProvider.loadSpeedDialIcon(
                imageView = binding.ivIcon,
                url = bookmark.url ?: "",
                customIconUrl = bookmark.favicon
            )

            binding.root.setOnClickListener { onClickSite(bookmark) }

            binding.root.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (showDeleteRunnable != null && onStartDrag != null) {
                            val dx = event.rawX - downX
                            val dy = event.rawY - downY
                            val distance = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                            if (distance > touchSlop) {
                                // 移动超过阈值 → 取消删除定时器，触发拖动
                                handler.removeCallbacks(showDeleteRunnable!!)
                                showDeleteRunnable = null
                                itemView.apply {
                                    scaleX = 1.0f
                                    scaleY = 1.0f
                                    alpha = 1.0f
                                    elevation = 0f
                                }
                                onStartDrag.invoke(this@SiteViewHolder)
                            }
                        }
                        false
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        // 手指抬起 → 取消删除定时器，恢复视觉状态
                        showDeleteRunnable?.let { handler.removeCallbacks(it) }
                        showDeleteRunnable = null
                        itemView.apply {
                            scaleX = 1.0f
                            scaleY = 1.0f
                            alpha = 1.0f
                            elevation = 0f
                        }
                        false
                    }
                    else -> false
                }
            }

            binding.root.setOnLongClickListener {
                if (onStartDrag != null) {
                    // 提起视觉效果
                    itemView.apply {
                        scaleX = 1.15f
                        scaleY = 1.15f
                        alpha = 0.8f
                        elevation = 16f
                    }
                    // 1.5 秒后弹出删除面板（加上系统长按 ~500ms，总共约 2 秒）
                    showDeleteRunnable = Runnable {
                        itemView.apply {
                            scaleX = 1.0f
                            scaleY = 1.0f
                            alpha = 1.0f
                            elevation = 0f
                        }
                        onLongClickSite(bookmark)
                    }
                    handler.postDelayed(showDeleteRunnable!!, 1500)
                } else {
                    onLongClickSite(bookmark)
                }
                true
            }
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
