package com.swiftbrowser.ui.history

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.swiftbrowser.R
import com.swiftbrowser.SwiftBrowserApp
import com.swiftbrowser.databinding.ActivityHistoryBinding
import kotlinx.coroutines.launch

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var adapter: HistoryAdapter

    private val app get() = application as SwiftBrowserApp
    private val historyDao get() = app.database.historyDao()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        binding.btnClearAll.setOnClickListener {
            AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle("清空历史记录")
                .setMessage("确定要清空所有历史记录吗？")
                .setPositiveButton(R.string.delete) { _, _ ->
                    lifecycleScope.launch {
                        historyDao.deleteAll()
                        Toast.makeText(this@HistoryActivity, "已清空", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        adapter = HistoryAdapter { history ->
            val mainIntent = Intent(
                this,
                com.swiftbrowser.ui.browser.MainActivity::class.java
            ).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                data = android.net.Uri.parse(history.url)
            }
            startActivity(mainIntent)
            finish()
        }

        binding.rvHistory.layoutManager = LinearLayoutManager(this)
        binding.rvHistory.adapter = adapter

        historyDao.getAll().observe(this) { items ->
            adapter.submitList(items)
            binding.tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            binding.rvHistory.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        }
    }
}
