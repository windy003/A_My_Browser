package com.swiftbrowser.ui.browser

import android.graphics.Bitmap
import android.webkit.WebView

data class Tab(
    val id: Long = System.currentTimeMillis(),
    var webView: WebView? = null,
    var url: String? = null,
    var title: String? = null,
    var thumbnail: Bitmap? = null,
    // window.open() 弹窗标签页对应的发起标签页 id：弹窗执行 window.close()
    // （如 OAuth 登录流程结束时）应切回这个标签页，而不是留在已经没用的空白弹窗上
    var openerTabId: Long? = null,
    // 放大字体模式：每个标签页独立开关，互不影响
    var bigFontModeEnabled: Boolean = false,
    // 从上次退出前恢复的阅读进度（纵向滚动像素）；页面加载完成后消费一次即清空
    var pendingScrollRestore: Int? = null,
    // 首页被当成一条正常历史记录叠在当前网页之上（在网页中按了首页键）：
    // 此时按后退键应回到这个网页，而不是把首页当作导航起点
    var homeOverWebPage: Boolean = false
)
