package com.swiftbrowser.ui.speeddial

import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

class SpeedDialDragHelper(
    private val adapter: SpeedDialAdapter,
    private val onMergeSites: (drag: SpeedDialItem.Site, target: SpeedDialItem.Site) -> Unit,
    private val onMoveToFolder: (drag: SpeedDialItem.Site, target: SpeedDialItem.Folder) -> Unit,
    private val onReorder: (reorderedList: List<SpeedDialItem>) -> Unit = {},
    private val onNoMoveDragEnd: (SpeedDialItem) -> Unit = {}
) : ItemTouchHelper.Callback() {

    private var dragFromPosition = -1
    private var dropTargetPosition = -1

    override fun getMovementFlags(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder
    ): Int {
        return if (viewHolder.itemViewType == SpeedDialAdapter.TYPE_SITE) {
            makeMovementFlags(
                ItemTouchHelper.UP or ItemTouchHelper.DOWN or
                        ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
                0
            )
        } else {
            makeMovementFlags(0, 0)
        }
    }

    override fun isLongPressDragEnabled(): Boolean = false

    override fun onMove(
        recyclerView: RecyclerView,
        source: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean {
        if (dragFromPosition == -1) {
            dragFromPosition = source.bindingAdapterPosition
        }
        val targetPos = target.bindingAdapterPosition
        // 实时交换数据，实现拖拽过程中位置重排预览
        if (adapter.swapDragItems(dragFromPosition, targetPos)) {
            // 交换后拖拽项的当前位置变了
            dragFromPosition = targetPos
        }
        dropTargetPosition = targetPos
        adapter.highlightPosition = dropTargetPosition
        return true
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

    override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
        super.onSelectedChanged(viewHolder, actionState)
        if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
            viewHolder?.itemView?.apply {
                alpha = 0.9f
                scaleX = 1.15f
                scaleY = 1.15f
                elevation = 24f
            }
            dragFromPosition = viewHolder?.bindingAdapterPosition ?: -1
            dropTargetPosition = -1
            adapter.startDragReorder()
            adapter.isDragging = true
            // 拖拽开始时通知所有视图刷新（害羞效果）
            adapter.notifyItemRangeChanged(0, adapter.itemCount)
        } else if (actionState == ItemTouchHelper.ACTION_STATE_IDLE) {
            if (adapter.isDragging) {
                adapter.isDragging = false
                adapter.notifyItemRangeChanged(0, adapter.itemCount)
            }
        }
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)

        // 取消该 ViewHolder 上可能还在等待的长按计时器
        adapter.cancelLongPressOnHolder(viewHolder)

        viewHolder.itemView.apply {
            alpha = 1.0f
            scaleX = 1.0f
            scaleY = 1.0f
            elevation = 0f
            translationZ = 0f
        }

        adapter.isDragging = false
        adapter.notifyItemRangeChanged(0, adapter.itemCount)
        adapter.highlightPosition = -1

        // 获取拖拽结束后的最终排序
        val finalList = adapter.finishDragReorder()

        if (dragFromPosition >= 0 && dropTargetPosition >= 0 && dragFromPosition != dropTargetPosition) {
            val dragItem = finalList.getOrNull(dragFromPosition)
            val targetItem = finalList.getOrNull(dropTargetPosition)

            if (dragItem is SpeedDialItem.Site && targetItem != null) {
                when (targetItem) {
                    is SpeedDialItem.Site -> {
                        onMergeSites(dragItem, targetItem)
                        dragFromPosition = -1
                        dropTargetPosition = -1
                        return
                    }
                    is SpeedDialItem.Folder -> {
                        onMoveToFolder(dragItem, targetItem)
                        dragFromPosition = -1
                        dropTargetPosition = -1
                        return
                    }
                }
            }
        }

        if (dropTargetPosition == -1 && dragFromPosition >= 0) {
            // 拖拽结束但没有移动到其他位置 → 弹出选项对话框
            val item = finalList.getOrNull(dragFromPosition)
            if (item != null) {
                onNoMoveDragEnd(item)
            }
        }

        onReorder(finalList)

        dragFromPosition = -1
        dropTargetPosition = -1
    }
}
