package com.swiftbrowser.ui.download

import android.content.Context
import android.content.pm.PackageManager
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
        try {
            val apkFile = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                fileName
            )
            if (!apkFile.exists()) return null
            val pm = context.packageManager
            val info = pm.getPackageArchiveInfo(apkFile.absolutePath, 0) ?: return null
            val appInfo = info.applicationInfo ?: return null
            appInfo.sourceDir = apkFile.absolutePath
            appInfo.publicSourceDir = apkFile.absolutePath
            return appInfo.loadIcon(pm)
        } catch (_: Exception) {
            return null
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<DownloadRecord>() {
        override fun areItemsTheSame(oldItem: DownloadRecord, newItem: DownloadRecord) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: DownloadRecord, newItem: DownloadRecord) = oldItem == newItem
    }
}
