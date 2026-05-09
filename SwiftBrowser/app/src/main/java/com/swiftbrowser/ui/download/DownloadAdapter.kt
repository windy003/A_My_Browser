package com.swiftbrowser.ui.download

import android.app.DownloadManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Environment
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.swiftbrowser.R
import com.swiftbrowser.data.entity.DownloadRecord
import com.swiftbrowser.databinding.ItemDownloadBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

class DownloadAdapter(
    private val scope: CoroutineScope,
    private val onApkClick: (DownloadRecord) -> Unit
) : ListAdapter<DownloadRecord, DownloadAdapter.ViewHolder>(DiffCallback()) {

    private val iconCache = mutableMapOf<Long, Drawable?>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDownloadBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemDownloadBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(record: DownloadRecord) {
            binding.tvFileName.text = record.fileName
            binding.tvUrl.text = record.url
            binding.tvTime.text = DateUtils.getRelativeTimeSpanString(
                record.createdAt,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS
            )

            val isApk = record.fileName.endsWith(".apk", ignoreCase = true)
            binding.ivIcon.tag = record.id

            if (isApk) {
                val cached = iconCache[record.id]
                if (cached != null) {
                    binding.ivIcon.setImageDrawable(cached)
                } else {
                    binding.ivIcon.setImageResource(R.drawable.ic_apk)
                    if (!iconCache.containsKey(record.id)) {
                        val recordId = record.id
                        val fileName = record.fileName
                        scope.launch {
                            val icon = withContext(Dispatchers.IO) {
                                extractApkIcon(binding.root.context, fileName)
                            }
                            iconCache[recordId] = icon
                            if (binding.ivIcon.tag == recordId && icon != null) {
                                binding.ivIcon.setImageDrawable(icon)
                            }
                        }
                    }
                }
                binding.root.setOnClickListener { onApkClick(record) }
            } else {
                binding.ivIcon.setImageResource(R.drawable.ic_download)
                binding.root.setOnClickListener(null)
            }
        }
    }

    private fun extractApkIcon(context: Context, fileName: String): Drawable? {
        val pm = context.packageManager

        // 优先通过 DownloadManager 获取真实文件路径（解决重命名问题）
        try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val query = DownloadManager.Query().setFilterByStatus(DownloadManager.STATUS_SUCCESSFUL)
            dm.query(query)?.use { cursor ->
                val titleCol = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE)
                val localUriCol = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                // 找最后一条匹配的（最新下载）
                var matchedLocalUri: String? = null
                while (cursor.moveToNext()) {
                    if (cursor.getString(titleCol) == fileName) {
                        matchedLocalUri = cursor.getString(localUriCol)
                    }
                }
                if (matchedLocalUri != null) {
                    val realPath = Uri.parse(matchedLocalUri).path
                    if (realPath != null) {
                        val icon = extractIconFromPath(pm, realPath, context)
                        if (icon != null) return icon
                    }
                }
            }
        } catch (_: Exception) { }

        // 回退：直接通过文件名尝试
        try {
            val apkFile = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                fileName
            )
            if (apkFile.canRead()) {
                val icon = extractIconFromPath(pm, apkFile.absolutePath, context)
                if (icon != null) return icon
            }
        } catch (_: Exception) { }

        return null
    }

    @Suppress("DiscouragedPrivateApi")
    private fun extractIconFromPath(
        pm: android.content.pm.PackageManager,
        path: String,
        context: Context
    ): Drawable? {
        val info = pm.getPackageArchiveInfo(path, 0) ?: return null
        val appInfo = info.applicationInfo ?: return null
        val iconResId = appInfo.icon
        if (iconResId == 0) return null

        // 直接从 APK 创建独立的 AssetManager + Resources，彻底绕过 PM 缓存
        var raw: Drawable? = null
        try {
            val am = android.content.res.AssetManager::class.java.getDeclaredConstructor().newInstance()
            val addAssetPath = android.content.res.AssetManager::class.java.getDeclaredMethod("addAssetPath", String::class.java)
            addAssetPath.isAccessible = true
            addAssetPath.invoke(am, path)
            val res = android.content.res.Resources(am, context.resources.displayMetrics, context.resources.configuration)
            raw = res.getDrawable(iconResId, null)
        } catch (_: Exception) { }

        // 回退到传统方式
        if (raw == null) {
            appInfo.sourceDir = path
            appInfo.publicSourceDir = path
            raw = appInfo.loadIcon(pm)
        }
        if (raw == null) return null

        val size = (48 * context.resources.displayMetrics.density).toInt()
        val w = if (raw.intrinsicWidth > 0) raw.intrinsicWidth else size
        val h = if (raw.intrinsicHeight > 0) raw.intrinsicHeight else size
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        raw.setBounds(0, 0, w, h)
        raw.draw(canvas)
        return BitmapDrawable(context.resources, bitmap)
    }

    class DiffCallback : DiffUtil.ItemCallback<DownloadRecord>() {
        override fun areItemsTheSame(oldItem: DownloadRecord, newItem: DownloadRecord) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: DownloadRecord, newItem: DownloadRecord) = oldItem == newItem
    }
}
