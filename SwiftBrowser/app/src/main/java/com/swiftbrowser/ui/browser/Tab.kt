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
    var openerTabId: Long? = null
)
