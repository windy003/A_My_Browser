package com.swiftbrowser.ui.speeddial

import android.os.Handler
import android.os.Looper
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

class SpeedDialDragHelper(
    private val adapter: SpeedDialAdapter,
    private val onReorder: (reorderedList: List<SpeedDialItem>) -> Unit = {},
    private val onMerge: (drag: SpeedDialItem, target: SpeedDialItem) -> Unit = { _, _ -> }
) : ItemTouchHelper.Callback() {

    private var hasMoved = false

    // 合并文件夹相关
    private val handler = Handler(Looper.getMainLooper())
    private var lastTargetPosition = -1       // 上次 onMove 的目标位置
    private var pendingMergeItem: SpeedDialItem? = null  // 交换前记住的目标项
    private var confirmedMergeItem: SpeedDialItem? = null // 停留超时后确认要合并的目标项
    private val HOVER_DELAY = 600L

    private val hoverRunnable = Runnable {
        // 停留足够久，确认合并
        confirmedMergeItem = pendingMergeItem
        // 找到目标项当前位置并高亮
        val list = adapter.getDisplayList()
        val pos = list.indexOf(confirmedMergeItem)
        if (pos >= 0) {
            adapter.highlightPosition = pos
        }
    }

    override fun getMovementFlags(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder
    ): Int {
        return makeMovementFlags(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or
                    ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
            0
        )
    }

    override fun isLongPressDragEnabled(): Boolean = false

    override fun onMove(
        recyclerView: RecyclerView,
        source: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean {
        val from = source.bindingAdapterPosition
        val to = target.bindingAdapterPosition
        if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false

        if (confirmedMergeItem != null) {
            // 已进入合并模式，不再交换，等松手
            return true
        }

        if (to == lastTargetPosition) {
            // 仍在同一个目标上，等待计时器，不交换
            return true
        }

        // 移到新目标 → 重置合并计时
        cancelHover()
        lastTargetPosition = to

        // 交换前记住目标项
        pendingMergeItem = adapter.getDisplayList().getOrNull(to)

        // 交换位置（实时预览）
        adapter.swapDragItems(from, to)
        hasMoved = true

        // 开始合并计时
        handler.postDelayed(hoverRunnable, HOVER_DELAY)
        return true
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

    override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
        super.onSelectedChanged(viewHolder, actionState)
        if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
            viewHolder.itemView.apply {
                alpha = 0.85f
                scaleX = 1.15f
                scaleY = 1.15f
            }
            hasMoved = false
            confirmedMergeItem = null
            pendingMergeItem = null
            lastTargetPosition = -1
            adapter.startDragReorder()
        }
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)

        // 先保存合并目标，再取消 hover（cancelHover 会清掉 confirmedMergeItem）
        val mergeTarget = confirmedMergeItem
        handler.removeCallbacks(hoverRunnable)
        adapter.highlightPosition = -1

        viewHolder.itemView.apply {
            alpha = 1.0f
            scaleX = 1.0f
            scaleY = 1.0f
            translationZ = 0f
        }

        val finalList = adapter.finishDragReorder()

        if (mergeTarget != null) {
            // 找到拖拽项（松手时 viewHolder 对应的位置）
            val dragPos = viewHolder.bindingAdapterPosition.let {
                if (it == RecyclerView.NO_POSITION) return@clearView else it
            }
            val dragItem = finalList.getOrNull(dragPos)
            if (dragItem != null && dragItem != mergeTarget) {
                onMerge(dragItem, mergeTarget)
            }
        } else if (hasMoved) {
            onReorder(finalList)
        }

        hasMoved = false
        confirmedMergeItem = null
        pendingMergeItem = null
        lastTargetPosition = -1
    }

    private fun cancelHover() {
        handler.removeCallbacks(hoverRunnable)
        pendingMergeItem = null
        if (confirmedMergeItem != null) {
            adapter.highlightPosition = -1
            confirmedMergeItem = null
        }
        lastTargetPosition = -1
    }
}
