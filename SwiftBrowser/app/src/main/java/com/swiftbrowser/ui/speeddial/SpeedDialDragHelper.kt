package com.swiftbrowser.ui.speeddial

import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

class SpeedDialDragHelper(
    private val adapter: SpeedDialAdapter,
    private val onMergeSites: (drag: SpeedDialItem.Site, target: SpeedDialItem.Site) -> Unit,
    private val onMoveToFolder: (drag: SpeedDialItem.Site, target: SpeedDialItem.Folder) -> Unit
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
        dropTargetPosition = target.bindingAdapterPosition
        adapter.highlightPosition = dropTargetPosition
        return true
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

    override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
        super.onSelectedChanged(viewHolder, actionState)
        if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
            viewHolder?.itemView?.apply {
                alpha = 0.7f
                scaleX = 1.2f
                scaleY = 1.2f
                elevation = 20f
            }
            dragFromPosition = viewHolder?.bindingAdapterPosition ?: -1
            dropTargetPosition = -1
        }
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)

        viewHolder.itemView.apply {
            alpha = 1.0f
            scaleX = 1.0f
            scaleY = 1.0f
            elevation = 0f
        }

        adapter.highlightPosition = -1

        if (dragFromPosition >= 0 && dropTargetPosition >= 0 && dragFromPosition != dropTargetPosition) {
            val dragItem = adapter.currentList.getOrNull(dragFromPosition)
            val targetItem = adapter.currentList.getOrNull(dropTargetPosition)

            if (dragItem is SpeedDialItem.Site && targetItem != null) {
                when (targetItem) {
                    is SpeedDialItem.Site -> onMergeSites(dragItem, targetItem)
                    is SpeedDialItem.Folder -> onMoveToFolder(dragItem, targetItem)
                }
            }
        }

        dragFromPosition = -1
        dropTargetPosition = -1
    }
}
