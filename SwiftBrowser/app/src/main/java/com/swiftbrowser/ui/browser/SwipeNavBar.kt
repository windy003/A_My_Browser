package com.swiftbrowser.ui.browser

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * 底部导航栏：在整条工具栏上左右滑动可切换标签。
 * 横向滑动时拦截手势触发切换；纵向滑动与点击照常传递给子按钮。
 */
class SwipeNavBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    /** 向左滑动（切换到下一个标签） */
    var onSwipeLeft: (() -> Unit)? = null

    /** 向右滑动（切换到上一个标签） */
    var onSwipeRight: (() -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var swiping = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                swiping = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                // 横向位移明显大于纵向时，判定为滑动并拦截，交给本控件处理
                if (abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.5f) {
                    swiping = true
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!swiping) {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.5f) {
                        swiping = true
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (swiping) {
                    val dx = ev.x - downX
                    if (abs(dx) > touchSlop * 2) {
                        if (dx < 0) onSwipeLeft?.invoke() else onSwipeRight?.invoke()
                    }
                }
                swiping = false
            }
            MotionEvent.ACTION_CANCEL -> swiping = false
        }
        return true
    }
}
