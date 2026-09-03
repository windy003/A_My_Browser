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
import kotlin.math.abs

/** 提起后弹出菜单中的一个操作项 */
data class LiftAction(
    val label: String,
    val destructive: Boolean = false,
    val onClick: () -> Unit
)

class SpeedDialAdapter(
    private val onClickSite: (Bookmark) -> Unit,
    private val onClickFolder: (Bookmark, List<Bookmark>) -> Unit,
    private val onDeleteSite: (Bookmark) -> Unit,
    private val onRenameSite: (Bookmark) -> Unit,
    private val onDeleteFolder: (Bookmark) -> Unit = {},
    private val onRenameFolder: (Bookmark) -> Unit = {},
    private val onMoveOutSite: ((Bookmark) -> Unit)? = null,
    private val onStartDrag: ((RecyclerView.ViewHolder) -> Unit)? = null,
    private val onBatchDelete: (Bookmark) -> Unit = {},
    // 单独刷新某个快速拨号的图标
    private val onRefreshSite: (Bookmark) -> Unit = {},
    // 提起时在锚点附近弹出大菜单 / 退出提起时收起菜单，由外部（Activity）实现
    private val onShowLiftMenu: (anchor: View, actions: List<LiftAction>) -> Unit = { _, _ -> },
    private val onHideLiftMenu: () -> Unit = {}
) : ListAdapter<SpeedDialItem, RecyclerView.ViewHolder>(DiffCallback()) {

    companion object {
        const val TYPE_SITE = 0
        const val TYPE_FOLDER = 1
        private const val PAYLOAD_BATCH_MODE = "batch_mode"
        private const val PAYLOAD_LIFT = "lift"

        /** 长按提起所需时长（毫秒）。用户要求约 2 秒，集中成常量便于调整。 */
        private const val LIFT_LONG_PRESS_MS = 1000L
    }

    /** 触摸移动判定阈值，首次创建 ViewHolder 时初始化 */
    private var touchSlop = 0
    private val handler = Handler(Looper.getMainLooper())

    override fun getItem(position: Int): SpeedDialItem {
        return dragOrderList?.getOrNull(position) ?: super.getItem(position)
    }


    var isDragging: Boolean = false

    // 拖拽期间的临时排序列表
    private var dragOrderList: MutableList<SpeedDialItem>? = null

    /** 获取当前显示用的列表（拖拽中返回重排后的副本，否则返回原始列表） */
    fun getDisplayList(): List<SpeedDialItem> = dragOrderList ?: currentList

    /**
     * 重新绑定某个书签所在的条目（单独刷新图标时用）。
     * 站点直接命中；若它是文件夹里的子项，则重绑该文件夹（缩略图里也含它的图标）。
     * @return 是否找到并通知了对应条目
     */
    fun rebindBookmark(bookmarkId: Long): Boolean {
        val index = getDisplayList().indexOfFirst { item ->
            when (item) {
                is SpeedDialItem.Site -> item.bookmark.id == bookmarkId
                is SpeedDialItem.Folder ->
                    item.folder.id == bookmarkId || item.children.any { it.id == bookmarkId }
            }
        }
        if (index < 0) return false
        notifyItemChanged(index)
        return true
    }

    /** 开始拖拽重排：复制当前列表 */
    fun startDragReorder() {
        dragOrderList = currentList.toMutableList()
    }

    /**
     * 拖拽中把源头移动到目标位置（插入语义，不是交换）。
     * from < to：源头插到目标的右边；from > to：源头插到目标的左边。
     * 中间的图标整体平移一格，目标图标只相对源头移动一格，
     * 不会跳到源头原来的位置。
     */
    fun moveDragItem(from: Int, to: Int): Boolean {
        val list = dragOrderList ?: return false
        if (from in list.indices && to in list.indices && from != to) {
            val item = list.removeAt(from)
            list.add(to, item)
            notifyItemMoved(from, to)
            return true
        }
        return false
    }

    /**
     * 结束拖拽：提交新顺序到 ListAdapter 并返回最终列表。
     * dragOrderList 保持到 submitList 的 DiffUtil 完成后才清空，
     * 防止中间 getItem() 回退到旧的 currentList。
     */
    fun finishDragReorder(): List<SpeedDialItem> {
        val result = dragOrderList?.toList() ?: currentList.toList()
        // 立即提交新顺序，但保留 dragOrderList 直到 DiffUtil 完成
        // 这样在异步 diff 期间 getItem() 仍返回正确顺序，不会弹回
        submitList(result) {
            dragOrderList = null
        }
        return result
    }

    // ==================== 提起态 ====================
    // 长按某个条目约 2 秒后该条目"提起"（放大 + 在附近弹出大菜单）。
    // 提起后手指继续移动一定距离即收起菜单、进入拖拽（排序 / 合并）。

    /** 当前被提起的条目 id（Site 为 bookmark.id，Folder 为 folder.id），null 表示无 */
    var liftedItemId: Long? = null
        private set

    fun hasLifted(): Boolean = liftedItemId != null

    private fun performLift(id: Long?, anchor: View, actions: List<LiftAction>) {
        if (id == null) return
        val old = liftedItemId
        liftedItemId = id
        if (old != id) notifyItemRangeChanged(0, itemCount, PAYLOAD_LIFT)
        onShowLiftMenu(anchor, actions)
    }

    /** 退出提起态（同时收起菜单） */
    fun clearLift() {
        if (liftedItemId == null) return
        liftedItemId = null
        notifyItemRangeChanged(0, itemCount, PAYLOAD_LIFT)
        onHideLiftMenu()
    }

    /** 进入拖拽时静默清除提起态：视觉交给拖拽接管，仅收起菜单 */
    private fun dismissLiftForDrag() {
        liftedItemId = null
        onHideLiftMenu()
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
        if (touchSlop == 0) {
            touchSlop = ViewConfiguration.get(parent.context).scaledTouchSlop
        }
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
            if (holder is SiteViewHolder) holder.updateBatchDeleteUI()
            return
        }
        if (payloads.contains(PAYLOAD_LIFT)) {
            val id = itemId(getItem(position))
            when (holder) {
                is SiteViewHolder -> holder.applyLiftVisual(id == liftedItemId)
                is FolderViewHolder -> holder.applyLiftVisual(id == liftedItemId)
            }
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is SpeedDialItem.Site -> (holder as SiteViewHolder).bind(item.bookmark)
            is SpeedDialItem.Folder -> (holder as FolderViewHolder).bind(item.folder, item.children)
        }
    }

    private fun itemId(item: SpeedDialItem): Long = when (item) {
        is SpeedDialItem.Site -> item.bookmark.id
        is SpeedDialItem.Folder -> item.folder.id
    }

    /**
     * 给某个条目装配"长按提起 + 提起后拖拽"的触摸状态机。
     *
     * - 按下：启动 2 秒计时器。
     * - 计时到（手指未明显移动）：提起本条目并在附近弹出大菜单。
     * - 移动超阈值：未提起 → 取消计时（视为列表滚动，交还 RecyclerView）；
     *   已提起本条目 → 收起菜单并触发拖拽（onStartDrag，由 ItemTouchHelper 接管）。
     * - 抬起：本次手势刚提起 → 保留菜单不动；否则若是轻点 → onTap。
     */
    private fun attachGestureHandler(
        holder: RecyclerView.ViewHolder,
        root: View,
        idProvider: () -> Long?,
        actionsProvider: () -> List<LiftAction>,
        onTap: () -> Unit
    ) {
        var downX = 0f
        var downY = 0f
        var dragStarted = false
        var liftedThisGesture = false
        val liftRunnable = Runnable {
            liftedThisGesture = true
            performLift(idProvider(), root, actionsProvider())
        }

        root.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    dragStarted = false
                    liftedThisGesture = false
                    if (!batchDeleteMode) {
                        handler.postDelayed(liftRunnable, LIFT_LONG_PRESS_MS)
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val moved = abs(e.rawX - downX) > touchSlop || abs(e.rawY - downY) > touchSlop
                    if (moved && !dragStarted) {
                        if (!batchDeleteMode && liftedItemId == idProvider()) {
                            // 本条目已提起 → 收起菜单并进入拖拽
                            dragStarted = true
                            handler.removeCallbacks(liftRunnable)
                            dismissLiftForDrag()
                            onStartDrag?.invoke(holder)
                        } else {
                            // 提起前移动 → 视为滚动，取消计时
                            handler.removeCallbacks(liftRunnable)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(liftRunnable)
                    val isTap = abs(e.rawX - downX) <= touchSlop && abs(e.rawY - downY) <= touchSlop
                    // 刚刚由长按提起的这次手势，抬手时保留菜单，不当作轻点
                    if (!dragStarted && isTap && !liftedThisGesture) onTap()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(liftRunnable)
                    true
                }
                else -> false
            }
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
            applyLiftVisual(bookmark.id == liftedItemId)

            attachGestureHandler(
                holder = this,
                root = binding.root,
                idProvider = { currentBookmark?.id },
                actionsProvider = { currentBookmark?.let { buildSiteActions(it) } ?: emptyList() }
            ) {
                when {
                    batchDeleteMode -> onBatchDelete(bookmark)
                    liftedItemId != null -> clearLift()
                    else -> onClickSite(bookmark)
                }
            }
        }

        private fun buildSiteActions(bookmark: Bookmark): List<LiftAction> {
            val actions = mutableListOf<LiftAction>()
            actions.add(LiftAction("重命名") { onRenameSite(bookmark) })
            actions.add(LiftAction("刷新此快速拨号") { onRefreshSite(bookmark) })
            onMoveOutSite?.let { mo ->
                actions.add(LiftAction("移出文件夹") { mo(bookmark) })
            }
            actions.add(LiftAction("删除", destructive = true) { onDeleteSite(bookmark) })
            return actions
        }

        fun applyLiftVisual(lifted: Boolean) {
            val scale = if (lifted) 1.18f else 1f
            val z = if (lifted) 16f else 0f
            binding.root.animate()
                .scaleX(scale).scaleY(scale).translationZ(z)
                .setDuration(120).start()
        }

        fun updateBatchDeleteUI() {
            binding.btnDelete.visibility = if (batchDeleteMode) View.VISIBLE else View.GONE
        }
    }

    inner class FolderViewHolder(
        private val binding: ItemSpeedDialFolderBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var currentFolder: Bookmark? = null

        fun bind(folder: Bookmark, children: List<Bookmark>) {
            currentFolder = folder
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

            applyLiftVisual(folder.id == liftedItemId)

            attachGestureHandler(
                holder = this,
                root = binding.root,
                idProvider = { currentFolder?.id },
                actionsProvider = { currentFolder?.let { buildFolderActions(it) } ?: emptyList() }
            ) {
                when {
                    liftedItemId != null -> clearLift()
                    else -> onClickFolder(folder, children)
                }
            }
        }

        private fun buildFolderActions(folder: Bookmark): List<LiftAction> = listOf(
            LiftAction("重命名") { onRenameFolder(folder) },
            LiftAction("删除", destructive = true) { onDeleteFolder(folder) }
        )

        fun applyLiftVisual(lifted: Boolean) {
            val scale = if (lifted) 1.18f else 1f
            val z = if (lifted) 16f else 0f
            binding.root.animate()
                .scaleX(scale).scaleY(scale).translationZ(z)
                .setDuration(120).start()
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
