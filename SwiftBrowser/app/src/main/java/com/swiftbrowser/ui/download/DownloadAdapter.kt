package com.swiftbrowser.ui.download

import android.app.DownloadManager
import android.content.Context
import android.graphics.drawable.Drawable
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

    private val iconCache = mutableMapOf<String, Drawable?>()

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

            if (isApk) {
                val cached = iconCache[record.fileName]
                if (cached != null) {
                    binding.ivIcon.setImageDrawable(cached)
                } else if (iconCache.containsKey(record.fileName)) {
                    binding.ivIcon.setImageResource(R.drawable.ic_apk)
                } else {
                    binding.ivIcon.setImageResource(R.drawable.ic_apk)
                    val fileName = record.fileName
                    scope.launch {
                        val icon = withContext(Dispatchers.IO) {
                            extractApkIcon(binding.root.context, fileName)
                        }
                        iconCache[fileName] = icon
                        if (bindingAdapterPosition != RecyclerView.NO_POSITION
                            && getItem(bindingAdapterPosition).fileName == fileName
                        ) {
                            if (icon != null) {
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

        // 方式1：直接通过文件路径
        try {
            val apkFile = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                fileName
            )
            if (apkFile.canRead()) {
                val icon = extractIconFromPath(pm, apkFile.absolutePath)
                if (icon != null) return icon
            }
        } catch (_: Exception) { }

        // 方式2：通过 DownloadManager 打开文件，复制到缓存目录后提取
        try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val query = DownloadManager.Query().setFilterByStatus(DownloadManager.STATUS_SUCCESSFUL)
            dm.query(query)?.use { cursor ->
                val titleCol = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE)
                val idCol = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
                while (cursor.moveToNext()) {
                    if (cursor.getString(titleCol) == fileName) {
                        val downloadId = cursor.getLong(idCol)
                        val tempFile = File(context.cacheDir, "temp_apk_icon.apk")
                        try {
                            dm.openDownloadedFile(downloadId).use { pfd ->
                                FileInputStream(pfd.fileDescriptor).use { input ->
                                    tempFile.outputStream().use { output ->
                                        input.copyTo(output)
                                    }
                                }
                            }
                            val icon = extractIconFromPath(pm, tempFile.absolutePath)
                            if (icon != null) return icon
                        } finally {
                            tempFile.delete()
                        }
                        break
                    }
                }
            }
        } catch (_: Exception) { }

        return null
    }

    private fun extractIconFromPath(pm: android.content.pm.PackageManager, path: String): Drawable? {
        val info = pm.getPackageArchiveInfo(path, 0) ?: return null
        val appInfo = info.applicationInfo ?: return null
        appInfo.sourceDir = path
        appInfo.publicSourceDir = path
        return appInfo.loadIcon(pm)
    }

    class DiffCallback : DiffUtil.ItemCallback<DownloadRecord>() {
        override fun areItemsTheSame(oldItem: DownloadRecord, newItem: DownloadRecord) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: DownloadRecord, newItem: DownloadRecord) = oldItem == newItem
    }
}
