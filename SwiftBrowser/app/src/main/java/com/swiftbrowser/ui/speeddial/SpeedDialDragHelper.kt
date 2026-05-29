package com.swiftbrowser.ui.speeddial

import android.graphics.Canvas
import android.view.View
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

/**
 * 快速拨号一体化拖拽：
 * - 拖到图标间隙 → 实时插入重排（排序）。
 * - 拖到另一图标正中央并停留 → 该目标高亮，松手即合并/放入文件夹。
 *
 * 排序与合并在同一次拖拽中智能切换：当拖拽中心落在某个目标的中央区域并停留
 * 超过 [MERGE_DWELL_MS] 时进入"合并武装"，此时暂停重排并高亮目标；中心离开
 * 中央区即解除武装、恢复排序。
 */
class SpeedDialDragHelper(
    private val adapter: SpeedDialAdapter,
    private val onReorder: (reorderedList: List<SpeedDialItem>) -> Unit = {},
    private val onMerge: (drag: SpeedDialItem, target: SpeedDialItem) -> Unit = { _, _ -> },
    /** 是否允许"中心悬停合并"。文件夹内部只含站点、不支持嵌套文件夹，应关闭。 */
    private val allowMerge: Boolean = true
) : ItemTouchHelper.Callback() {

    companion object {
        /** 中心悬停多久判定为"放入/合并"（毫秒） */
        private const val MERGE_DWELL_MS = 500L
        /** 目标中央命中区域占比（0.5 表示中央 50%） */
        private const val CENTRAL_FRACTION = 0.5f
    }

    private var hasMoved = false

    // 合并悬停判定状态
    private var hoveringCentral = false          // 拖拽中心当前是否落在某目标中央区
    private var dwellTargetView: View? = null    // 当前悬停的目标 view
    private var dwellTargetPos: Int = RecyclerView.NO_POSITION
    private var dwellStartTime: Long = 0L
    private var mergeArmed = false               // 是否已武装合并
    private var highlightedView: View? = null    // 当前高亮的目标 view

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
        // 正在合并悬停时暂停重排，保持目标位置稳定
        if (hoveringCentral) return false

        val from = source.bindingAdapterPosition
        val to = target.bindingAdapterPosition
        if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false

        // 插入到目标左边或右边（不交换位置）
        adapter.moveDragItem(from, to)
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
            resetMergeState()
            adapter.startDragReorder()
        }
    }

    override fun onChildDraw(
        c: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean
    ) {
        super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
        if (!allowMerge) return
        if (actionState != ItemTouchHelper.ACTION_STATE_DRAG || !isCurrentlyActive) return

        val dragView = viewHolder.itemView
        val cx = dragView.left + dX + dragView.width / 2f
        val cy = dragView.top + dY + dragView.height / 2f

        val target = findCentralTargetUnder(recyclerView, dragView, cx, cy)
        if (target == null) {
            disarmMerge()
            return
        }

        val targetPos = recyclerView.getChildAdapterPosition(target)
        if (targetPos == RecyclerView.NO_POSITION) {
            disarmMerge()
            return
        }

        if (target !== dwellTargetView) {
            // 切换到新的悬停目标，重新计时
            disarmMerge()
            dwellTargetView = target
            dwellTargetPos = targetPos
            dwellStartTime = System.currentTimeMillis()
            hoveringCentral = true
        } else {
            hoveringCentral = true
            if (!mergeArmed && System.currentTimeMillis() - dwellStartTime >= MERGE_DWELL_MS) {
                armMerge(target)
            }
        }
    }

    /** 找到拖拽中心落在其"中央区域"内、且非拖拽自身的目标子 view */
    private fun findCentralTargetUnder(
        recyclerView: RecyclerView,
        dragView: View,
        cx: Float,
        cy: Float
    ): View? {
        for (i in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(i)
            if (child === dragView) continue
            val w = child.width
            val h = child.height
            val marginX = w * (1f - CENTRAL_FRACTION) / 2f
            val marginY = h * (1f - CENTRAL_FRACTION) / 2f
            val left = child.left + marginX
            val right = child.right - marginX
            val top = child.top + marginY
            val bottom = child.bottom - marginY
            if (cx in left..right && cy in top..bottom) return child
        }
        return null
    }

    private fun armMerge(target: View) {
        mergeArmed = true
        clearHighlight()
        highlightedView = target
        target.apply {
            scaleX = 1.2f
            scaleY = 1.2f
            translationZ = 8f
        }
    }

    private fun disarmMerge() {
        hoveringCentral = false
        mergeArmed = false
        dwellTargetView = null
        dwellTargetPos = RecyclerView.NO_POSITION
        dwellStartTime = 0L
        clearHighlight()
    }

    private fun resetMergeState() {
        disarmMerge()
    }

    private fun clearHighlight() {
        highlightedView?.apply {
            scaleX = 1.0f
            scaleY = 1.0f
            translationZ = 0f
        }
        highlightedView = null
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

        val armed = mergeArmed
        val mergeTargetPos = dwellTargetPos
        clearHighlight()

        val finalList = adapter.finishDragReorder()

        if (armed && mergeTargetPos != RecyclerView.NO_POSITION) {
            // 合并：拖拽项与目标项
            val dragPos = viewHolder.bindingAdapterPosition
            val dragItem = if (dragPos != RecyclerView.NO_POSITION) finalList.getOrNull(dragPos) else null
            val targetItem = finalList.getOrNull(mergeTargetPos)
            if (dragItem != null && targetItem != null && dragItem != targetItem) {
                onMerge(dragItem, targetItem)
            }
        } else if (hasMoved) {
            onReorder(finalList)
        }

        hasMoved = false
        resetMergeState()
    }
}
