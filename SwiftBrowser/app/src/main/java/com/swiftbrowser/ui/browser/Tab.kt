package com.swiftbrowser.ui.browser

import android.graphics.Bitmap
import android.webkit.WebView

data class Tab(
    val id: Long = System.currentTimeMillis(),
    var webView: WebView? = null,
    var url: String? = null,
    var title: String? = null,
    var thumbnail: Bitmap? = null
)
