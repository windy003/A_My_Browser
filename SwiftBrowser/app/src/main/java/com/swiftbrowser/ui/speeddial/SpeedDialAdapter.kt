package com.swiftbrowser.ui.speeddial

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import java.util.Collections
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

        /** 手机震动 50ms */
        fun vibratePhone(context: Context) {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                manager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            vibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    override fun getItem(position: Int): SpeedDialItem {
        return dragOrderList?.getOrNull(position) ?: super.getItem(position)
    }

    var highlightPosition: Int = -1
        set(value) {
            val old = field
            field = value
            if (old >= 0 && old < itemCount) notifyItemChanged(old)
            if (value >= 0 && value < itemCount) notifyItemChanged(value)
        }

    var isDragging: Boolean = false

    // 拖拽期间的临时排序列表
    private var dragOrderList: MutableList<SpeedDialItem>? = null

    /** 获取当前显示用的列表（拖拽中返回重排后的副本，否则返回原始列表） */
    fun getDisplayList(): List<SpeedDialItem> = dragOrderList ?: currentList

    /** 开始拖拽重排：复制当前列表 */
    fun startDragReorder() {
        dragOrderList = currentList.toMutableList()
    }

    /** 拖拽中交换两个位置的数据 */
    fun swapDragItems(from: Int, to: Int): Boolean {
        val list = dragOrderList ?: return false
        if (from in list.indices && to in list.indices && from != to) {
            Collections.swap(list, from, to)
            notifyItemMoved(from, to)
            return true
        }
        return false
    }

    /** 结束拖拽：返回最终排序后的列表，清理临时数据 */
    fun finishDragReorder(): List<SpeedDialItem> {
        val result = dragOrderList ?: currentList
        dragOrderList = null
        return result
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

    fun cancelLongPressOnHolder(holder: RecyclerView.ViewHolder) {
        // no-op：长按状态由 DragHelper 管理
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val isHighlighted = position == highlightPosition
        when {
            isDragging && isHighlighted -> {
                // 目标位置：放大突出，仿佛在"邀请"放入
                holder.itemView.scaleX = 1.25f
                holder.itemView.scaleY = 1.25f
                holder.itemView.alpha = 1.0f
                holder.itemView.translationZ = 16f
            }
            isDragging -> {
                // 其他图标"害羞"地缩小变淡，让出视觉空间
                holder.itemView.scaleX = 0.85f
                holder.itemView.scaleY = 0.85f
                holder.itemView.alpha = 0.4f
                holder.itemView.translationZ = 0f
            }
            else -> {
                holder.itemView.scaleX = 1.0f
                holder.itemView.scaleY = 1.0f
                holder.itemView.alpha = 1.0f
                holder.itemView.translationZ = 0f
            }
        }

        when (val item = getItem(position)) {
            is SpeedDialItem.Site -> (holder as SiteViewHolder).bind(item.bookmark)
            is SpeedDialItem.Folder -> (holder as FolderViewHolder).bind(item.folder, item.children)
        }
    }

    inner class SiteViewHolder(
        private val binding: ItemSpeedDialBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var currentBookmark: Bookmark? = null

        fun bind(bookmark: Bookmark) {
            currentBookmark = bookmark
            binding.tvTitle.text = bookmark.title

            FaviconProvider.loadSpeedDialIcon(
                imageView = binding.ivIcon,
                url = bookmark.url ?: "",
                customIconUrl = bookmark.favicon
            )

            binding.btnDelete.visibility = if (batchDeleteMode) View.VISIBLE else View.GONE

            binding.root.setOnClickListener {
                if (batchDeleteMode) {
                    onBatchDelete(bookmark)
                } else {
                    onClickSite(bookmark)
                }
            }

            // 长按：震动 + 浮起 + 立即启动拖拽
            // 拖拽结束后由 DragHelper 判断：没移动就弹对话框，移动了就正常重排
            binding.root.setOnLongClickListener {
                if (batchDeleteMode) return@setOnLongClickListener false
                vibratePhone(binding.root.context)
                binding.root.animate()
                    .scaleX(1.1f)
                    .scaleY(1.1f)
                    .translationZ(12f)
                    .setDuration(150)
                    .start()
                onStartDrag?.invoke(this)
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
