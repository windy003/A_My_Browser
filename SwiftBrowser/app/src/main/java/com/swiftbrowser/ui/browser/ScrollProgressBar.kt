package com.swiftbrowser.ui.browser

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.swiftbrowser.R
import kotlin.math.roundToInt

/**
 * 网页阅读进度条：贴在屏幕右侧，随 WebView 滚动同步显示当前位置；
 * 静止时收成一条细线，滚动或拖动时变粗便于抓取；只有按住滑块本身拖动才会跳转，
 * 点击/按压滑块以外的轨道区域不做任何响应（事件透传给下面的网页）。
 */
class ScrollProgressBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 拖动进度条时回调，progress 范围 0f..1f；宿主据此把 WebView 滚动到对应位置 */
    var onDragTo: ((progress: Float) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val collapsedWidthPx = 3f * density
    private val expandedWidthPx = 8f * density
    private val minThumbHeightPx = 32f * density
    private val hideDelayMs = 900L

    private var widthFraction = 0f      // 0=收起细线，1=展开变粗，由动画驱动
    private var progress = 0f           // 当前滚动比例 0..1
    private var visibleRatio = 1f       // 可视区域占内容总高度比例，决定滑块长度
    private var dragging = false
    private var hasContent = false      // 内容是否足够长可滚动，不可滚动时不绘制也不拦截触摸
    private var dragOffsetInThumb = 0f  // 按下点相对滑块顶部的偏移，保证拖动时滑块不跳动
    private val thumbTouchSlopPx = 12f * density  // 滑块上下各放宽一点，细线状态下也便于按住

    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent)
    }

    private val widthAnimator = ValueAnimator().apply {
        addUpdateListener {
            widthFraction = it.animatedValue as Float
            invalidate()
        }
    }

    private val hideRunnable = Runnable { animateWidth(expand = false) }

    /** WebView 滚动时调用：scrollY/视口高度/内容总高度均为像素单位 */
    fun update(scrollY: Int, viewportHeight: Int, contentHeight: Int) {
        if (dragging) return
        hasContent = contentHeight > viewportHeight + density
        if (!hasContent) {
            invalidate()
            return
        }
        val maxScroll = (contentHeight - viewportHeight).coerceAtLeast(1)
        progress = (scrollY.toFloat() / maxScroll).coerceIn(0f, 1f)
        visibleRatio = (viewportHeight.toFloat() / contentHeight).coerceIn(0.04f, 1f)
        invalidate()
        animateWidth(expand = true)
        removeCallbacks(hideRunnable)
        postDelayed(hideRunnable, hideDelayMs)
    }

    /** 切换标签/回到首页等场景下重置状态，避免残留上一个页面的进度条 */
    fun reset() {
        removeCallbacks(hideRunnable)
        dragging = false
        hasContent = false
        progress = 0f
        widthAnimator.cancel()
        widthFraction = 0f
        invalidate()
    }

    private fun animateWidth(expand: Boolean) {
        val target = if (expand) 1f else 0f
        if (widthFraction == target && !widthAnimator.isRunning) return
        widthAnimator.cancel()
        widthAnimator.setFloatValues(widthFraction, target)
        widthAnimator.duration = if (expand) 120L else 260L
        widthAnimator.start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!hasContent) return
        val barWidth = collapsedWidthPx + (expandedWidthPx - collapsedWidthPx) * widthFraction
        val thumbHeight = currentThumbHeight()
        val thumbTop = currentThumbTop()
        val left = width - barWidth
        thumbPaint.alpha = (140 + 115 * widthFraction).roundToInt().coerceIn(0, 255)
        canvas.drawRoundRect(
            left, thumbTop, width.toFloat(), thumbTop + thumbHeight,
            barWidth / 2f, barWidth / 2f, thumbPaint
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!hasContent) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 只有按在滑块上才接管手势；按在空白轨道上直接放弃事件，不做点击跳转
                val thumbTop = currentThumbTop()
                val thumbBottom = thumbTop + currentThumbHeight()
                if (event.y < thumbTop - thumbTouchSlopPx || event.y > thumbBottom + thumbTouchSlopPx) {
                    return false
                }
                dragging = true
                dragOffsetInThumb = event.y - thumbTop
                removeCallbacks(hideRunnable)
                animateWidth(expand = true)
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                moveThumbTo(event.y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return false
                dragging = false
                postDelayed(hideRunnable, hideDelayMs)
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun currentThumbHeight(): Float =
        (height * visibleRatio).coerceAtLeast(minThumbHeightPx).coerceAtMost(height.toFloat())

    private fun currentThumbTop(): Float =
        (height - currentThumbHeight()).coerceAtLeast(0f) * progress

    /** 按住滑块拖动：保持手指与滑块的相对位置，滑块不会跳到手指中心 */
    private fun moveThumbTo(y: Float) {
        val thumbHeight = currentThumbHeight()
        val trackHeight = (height - thumbHeight).coerceAtLeast(1f)
        val thumbTop = (y - dragOffsetInThumb).coerceIn(0f, trackHeight)
        progress = thumbTop / trackHeight
        invalidate()
        onDragTo?.invoke(progress)
    }
}
