package com.swiftbrowser.ui.browser

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.swiftbrowser.R

/**
 * 顶部站点代理指示器：
 * - 外层方形 = 全局默认模式（蓝 = 代理，灰 = 直连）
 * - 内层圆形 = 当前页面实际模式（红 = 代理，白 = 直连）
 */
class SiteProxyIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var globalProxy = false
    private var pageProxy = false

    private val density = resources.displayMetrics.density

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.argb(0x99, 0xFF, 0xFF, 0xFF)
    }
    private val squareRect = RectF()

    init {
        isClickable = true
        isFocusable = true
    }

    fun setState(globalProxy: Boolean, pageProxy: Boolean) {
        val changed = this.globalProxy != globalProxy || this.pageProxy != pageProxy
        this.globalProxy = globalProxy
        this.pageProxy = pageProxy
        contentDescription = buildContentDescription()
        if (changed) invalidate()
    }

    private fun buildContentDescription(): String {
        val global = if (globalProxy) "全局代理" else "全局直连"
        val page = if (pageProxy) "当前网站代理" else "当前网站直连"
        return "$global，$page。点击打开站点代理设置"
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val squareSize = dp(28f)
        val left = (width - squareSize) / 2f
        val top = (height - squareSize) / 2f
        squareRect.set(left, top, left + squareSize, top + squareSize)

        val radius = dp(6f)
        fillPaint.color = ContextCompat.getColor(
            context,
            if (globalProxy) R.color.primary_light else R.color.gray_medium
        )
        canvas.drawRoundRect(squareRect, radius, radius, fillPaint)
        canvas.drawRoundRect(squareRect, radius, radius, strokePaint)

        fillPaint.color = ContextCompat.getColor(
            context,
            if (pageProxy) R.color.red else R.color.white
        )
        canvas.drawCircle(width / 2f, height / 2f, dp(6f), fillPaint)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        alpha = if (isPressed) 0.65f else 1f
    }

    private fun dp(value: Float): Float = value * density
}
