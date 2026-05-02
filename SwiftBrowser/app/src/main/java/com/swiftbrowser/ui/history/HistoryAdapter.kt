package com.swiftbrowser.ui.history

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.swiftbrowser.data.entity.History
import com.swiftbrowser.databinding.ItemHistoryBinding
import com.swiftbrowser.util.FaviconProvider

class HistoryAdapter(
    private val onClick: (History) -> Unit
) : ListAdapter<History, HistoryAdapter.HistoryViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val binding = ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return HistoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class HistoryViewHolder(
        private val binding: ItemHistoryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(history: History) {
            binding.tvTitle.text = history.title
            binding.tvUrl.text = history.url
            binding.tvTime.text = DateUtils.getRelativeTimeSpanString(
                history.visitedAt,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS
            )

            FaviconProvider.loadBookmarkFavicon(
                imageView = binding.ivFavicon,
                url = history.url
            )

            binding.root.setOnClickListener { onClick(history) }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<History>() {
        override fun areItemsTheSame(oldItem: History, newItem: History) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: History, newItem: History) = oldItem == newItem
    }
}
