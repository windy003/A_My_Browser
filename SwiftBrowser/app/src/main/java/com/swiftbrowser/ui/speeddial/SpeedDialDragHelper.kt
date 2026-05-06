package com.swiftbrowser.ui.speeddial

import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

class SpeedDialDragHelper(
    private val adapter: SpeedDialAdapter,
    private val onReorder: (reorderedList: List<SpeedDialItem>) -> Unit = {},
    private val onMerge: (drag: SpeedDialItem, target: SpeedDialItem) -> Unit = { _, _ -> }
) : ItemTouchHelper.Callback() {

    private var hasMoved = false

    // 文件夹模式：拖到图标上方合并，不交换位置
    private var folderMergeTarget: SpeedDialItem? = null
    private var folderMergeTargetPos: Int = -1

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

        if (adapter.folderMode) {
            // 文件夹模式：不交换，只记录目标并高亮
            clearFolderHighlight()
            folderMergeTarget = adapter.getDisplayList().getOrNull(to)
            folderMergeTargetPos = to
            // 高亮目标
            val holder = recyclerView.findViewHolderForAdapterPosition(to)
            holder?.itemView?.apply {
                scaleX = 1.2f
                scaleY = 1.2f
                translationZ = 8f
            }
            return true
        }

        // 普通移动模式：交换位置
        adapter.swapDragItems(from, to)
        hasMoved = true
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
            folderMergeTarget = null
            folderMergeTargetPos = -1
            adapter.startDragReorder()
        }
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)

        // 恢复拖拽项样式
        viewHolder.itemView.apply {
            alpha = 1.0f
            scaleX = 1.0f
            scaleY = 1.0f
            translationZ = 0f
        }

        // 恢复目标高亮
        clearFolderHighlight(recyclerView)

        val finalList = adapter.finishDragReorder()

        if (adapter.folderMode) {
            // 文件夹模式：有目标就合并
            val mergeTarget = folderMergeTarget
            if (mergeTarget != null) {
                val dragPos = viewHolder.bindingAdapterPosition
                val dragItem = if (dragPos != RecyclerView.NO_POSITION) {
                    finalList.getOrNull(dragPos)
                } else null
                if (dragItem != null && dragItem != mergeTarget) {
                    onMerge(dragItem, mergeTarget)
                }
            }
            // 没有目标就自动恢复原位（ItemTouchHelper 默认行为）
        } else if (hasMoved) {
            onReorder(finalList)
        }

        hasMoved = false
        folderMergeTarget = null
        folderMergeTargetPos = -1
    }

    private var lastHighlightedHolder: RecyclerView.ViewHolder? = null

    private fun clearFolderHighlight(recyclerView: RecyclerView? = null) {
        if (folderMergeTargetPos >= 0 && recyclerView != null) {
            val holder = recyclerView.findViewHolderForAdapterPosition(folderMergeTargetPos)
            holder?.itemView?.apply {
                scaleX = 1.0f
                scaleY = 1.0f
                translationZ = 0f
            }
        }
    }
}
