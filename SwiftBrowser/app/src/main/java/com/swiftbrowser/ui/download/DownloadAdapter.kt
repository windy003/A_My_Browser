package com.swiftbrowser.ui.download

import android.content.pm.PackageManager
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
import java.io.File

class DownloadAdapter(
    private val onApkClick: (DownloadRecord) -> Unit
) : ListAdapter<DownloadRecord, DownloadAdapter.ViewHolder>(DiffCallback()) {

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
                val apkPath = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    record.fileName
                ).absolutePath
                val pm = binding.root.context.packageManager
                val info = pm.getPackageArchiveInfo(apkPath, PackageManager.GET_ACTIVITIES)
                if (info != null) {
                    info.applicationInfo?.sourceDir = apkPath
                    info.applicationInfo?.publicSourceDir = apkPath
                    val icon = info.applicationInfo?.loadIcon(pm)
                    binding.ivIcon.setImageDrawable(icon)
                } else {
                    binding.ivIcon.setImageResource(R.drawable.ic_apk)
                }
                binding.root.setOnClickListener { onApkClick(record) }
            } else {
                binding.ivIcon.setImageResource(R.drawable.ic_download)
                binding.root.setOnClickListener(null)
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<DownloadRecord>() {
        override fun areItemsTheSame(oldItem: DownloadRecord, newItem: DownloadRecord) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: DownloadRecord, newItem: DownloadRecord) = oldItem == newItem
    }
}
