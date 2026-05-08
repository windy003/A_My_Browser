package com.swiftbrowser.ui.download

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.swiftbrowser.R
import com.swiftbrowser.SwiftBrowserApp
import com.swiftbrowser.data.entity.DownloadRecord
import com.swiftbrowser.databinding.ActivityDownloadBinding
import kotlinx.coroutines.launch
import java.io.File

class DownloadActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDownloadBinding
    private lateinit var adapter: DownloadAdapter

    private val app get() = application as SwiftBrowserApp
    private val downloadRecordDao get() = app.database.downloadRecordDao()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDownloadBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        binding.btnClearAll.setOnClickListener {
            AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle("清空下载记录")
                .setMessage("确定要清空所有下载记录吗？")
                .setPositiveButton(R.string.delete) { _, _ ->
                    lifecycleScope.launch {
                        downloadRecordDao.deleteAll()
                        Toast.makeText(this@DownloadActivity, "已清空", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        adapter = DownloadAdapter { record -> installApk(record) }
        binding.rvDownloads.layoutManager = LinearLayoutManager(this)
        binding.rvDownloads.adapter = adapter

        downloadRecordDao.getAll().observe(this) { items ->
            adapter.submitList(items)
            binding.tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            binding.rvDownloads.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun installApk(record: DownloadRecord) {
        val file = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            record.fileName
        )
        if (!file.exists()) {
            Toast.makeText(this, "文件不存在，可能已被删除", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "com.swiftbrowser.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }
}
