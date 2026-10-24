package com.swiftbrowser.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.swiftbrowser.R
import com.swiftbrowser.SwiftBrowserApp
import com.swiftbrowser.data.entity.DownloadRecord
import com.swiftbrowser.ui.browser.SiteProxyManager
import com.swiftbrowser.ui.download.DownloadActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * 带代理的下载服务。
 *
 * 系统 DownloadManager 运行在系统进程里，不读 WebView 的 ProxyController，也没有
 * 按请求指定代理的接口，所以下载改由本服务用 HttpURLConnection 完成，并显式挂上
 * 「站点代理设置」里的 HTTP 代理。代理不可用时直接失败，不静默回退直连。
 */
class ProxyDownloadService : Service() {

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val EXTRA_URL = "url"
        private const val EXTRA_FILE_NAME = "file_name"
        private const val EXTRA_MIME_TYPE = "mime_type"
        private const val EXTRA_USER_AGENT = "user_agent"
        private const val EXTRA_REFERER = "referer"

        fun start(
            context: Context,
            url: String,
            fileName: String,
            mimeType: String?,
            userAgent: String?,
            referer: String?
        ) {
            val intent = Intent(context, ProxyDownloadService::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_FILE_NAME, fileName)
                putExtra(EXTRA_MIME_TYPE, mimeType)
                putExtra(EXTRA_USER_AGENT, userAgent)
                putExtra(EXTRA_REFERER, referer)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = AtomicInteger(0)
    private val nextNotificationId = AtomicInteger(2000)

    private val notificationManager by lazy {
        getSystemService(NotificationManager::class.java)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val fileName = intent.getStringExtra(EXTRA_FILE_NAME).orEmpty().ifBlank { "download" }
        val mimeType = intent.getStringExtra(EXTRA_MIME_TYPE)
        val userAgent = intent.getStringExtra(EXTRA_USER_AGENT)
        val referer = intent.getStringExtra(EXTRA_REFERER)
        val notificationId = nextNotificationId.incrementAndGet()

        activeJobs.incrementAndGet()
        try {
            startForeground(notificationId, buildProgressNotification(notificationId, fileName, 0, 0))
        } catch (e: Exception) {
            activeJobs.decrementAndGet()
            stopSelf(startId)
            return START_NOT_STICKY
        }

        scope.launch {
            try {
                download(url, fileName, mimeType, userAgent, referer, notificationId)
            } finally {
                if (activeJobs.decrementAndGet() == 0) {
                    stopForeground(STOP_FOREGROUND_DETACH)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ==================== 下载 ====================

    private suspend fun download(
        url: String,
        rawFileName: String,
        mimeType: String?,
        userAgent: String?,
        referer: String?,
        notificationId: Int
    ) {
        var mediaUri: Uri? = null
        var targetFile: File? = null
        var finalName = rawFileName
        try {
            val proxy = SiteProxyManager.httpProxy(this)
                ?: throw IOException("代理地址无效，请在站点代理设置里填写 host:port")

            val conn = openFollowingRedirects(url, proxy, userAgent, referer)
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                throw IOException("HTTP $code")
            }

            val contentType = conn.contentType?.substringBefore(';')?.trim()
            val mime = mimeType?.takeIf { it.isNotBlank() }
                ?: contentType?.takeIf { it.isNotBlank() }
                ?: guessMime(rawFileName)
            val total = conn.contentLengthLong
            finalName = uniqueFileName(rawFileName)

            val output: OutputStream
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, finalName)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("无法在下载目录创建文件")
                mediaUri = uri
                output = contentResolver.openOutputStream(uri)
                    ?: throw IOException("无法写入下载目录")
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                val file = File(dir, finalName)
                targetFile = file
                output = file.outputStream()
            }

            var downloaded = 0L
            var lastUpdate = 0L
            output.use { out ->
                conn.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        downloaded += read
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastUpdate >= 300) {
                            lastUpdate = now
                            notifyProgress(notificationId, finalName, downloaded, total)
                        }
                    }
                }
            }
            conn.disconnect()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val doneValues = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                mediaUri?.let { contentResolver.update(it, doneValues, null, null) }
            }

            // 文件已经完整落盘，后续步骤失败也不该再删除它
            mediaUri = null
            targetFile = null
            try {
                (application as SwiftBrowserApp).database.downloadRecordDao().insert(
                    DownloadRecord(fileName = finalName, url = url, mimeType = mime)
                )
            } catch (_: Exception) {
                // 历史记录写不进去不影响文件本身
            }
            notifyFinished(notificationId, finalName, null)
        } catch (e: Exception) {
            // 清掉半成品，避免下载目录里留下 0 字节残文件
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    mediaUri?.let { contentResolver.delete(it, null, null) }
                } catch (_: Exception) {
                }
            } else {
                targetFile?.delete()
            }
            notifyFinished(notificationId, finalName, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * 建立到目标地址的连接，手动处理重定向（HttpURLConnection 默认不跨协议跟随，
     * 且每跳都要用新地址重新取 Cookie）。
     */
    private fun openFollowingRedirects(
        rawUrl: String,
        proxy: java.net.Proxy,
        userAgent: String?,
        referer: String?
    ): HttpURLConnection {
        var current = rawUrl
        repeat(6) {
            val conn = (URL(current).openConnection(proxy) as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15000
                readTimeout = 30000
                setRequestProperty("Accept", "*/*")
                if (!userAgent.isNullOrBlank()) {
                    setRequestProperty("User-Agent", userAgent)
                }
                CookieManager.getInstance().getCookie(current)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { setRequestProperty("Cookie", it) }
                if (!referer.isNullOrBlank() && referer != current) {
                    setRequestProperty("Referer", referer)
                }
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) throw IOException("重定向缺少 Location")
                current = URL(URL(current), location).toString()
                return@repeat
            }
            return conn
        }
        throw IOException("重定向次数过多")
    }

    // ==================== 文件名 ====================

    private fun uniqueFileName(raw: String): String {
        val fileName = raw.ifBlank { "download" }
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""

        if (!fileExists(fileName)) return fileName
        var counter = 1
        while (fileExists("$base($counter)$ext")) counter++
        return "$base($counter)$ext"
    }

    private fun fileExists(name: String): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val relativePath = Environment.DIRECTORY_DOWNLOADS + "/"
            contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?",
                arrayOf(name, relativePath),
                null
            )?.use { it.count > 0 } ?: false
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            File(dir, name).exists()
        }
    }

    private fun guessMime(fileName: String): String {
        val ext = MimeTypeMap.getFileExtensionFromUrl(fileName).lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }

    // ==================== 通知 ====================

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "下载",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "文件下载进度"
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildProgressNotification(
        id: Int,
        fileName: String,
        downloaded: Long,
        total: Long
    ): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(fileName)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
        if (total > 0) {
            val percent = (downloaded * 100 / total).toInt().coerceIn(0, 100)
            builder.setProgress(100, percent, false)
            builder.setContentText("下载中… $percent%")
        } else {
            builder.setProgress(0, 0, true)
            builder.setContentText("下载中… ${formatBytes(downloaded)}")
        }
        return builder.build()
    }

    private fun notifyProgress(id: Int, fileName: String, downloaded: Long, total: Long) {
        notificationManager.notify(id, buildProgressNotification(id, fileName, downloaded, total))
    }

    private fun notifyFinished(id: Int, fileName: String, error: String?) {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, DownloadActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(fileName)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
        if (error == null) {
            builder.setContentText("下载完成")
        } else {
            builder.setContentText("下载失败：$error")
        }
        notificationManager.notify(id, builder.build())

        Handler(Looper.getMainLooper()).post {
            val text = if (error == null) {
                getString(R.string.download_complete, fileName)
            } else {
                getString(R.string.download_failed, fileName)
            }
            Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        return String.format("%.1f MB", kb / 1024.0)
    }
}
