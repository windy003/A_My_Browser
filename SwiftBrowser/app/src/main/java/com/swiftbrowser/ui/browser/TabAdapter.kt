package com.swiftbrowser.ui.browser

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.swiftbrowser.R
import com.swiftbrowser.databinding.ItemTabBinding

class TabAdapter(
    private val onClickTab: (Tab) -> Unit,
    private val onCloseTab: (Tab) -> Unit
) : RecyclerView.Adapter<TabAdapter.TabViewHolder>() {

    private var tabs = listOf<Tab>()
    var activeTabId: Long = -1L

    fun submitList(list: List<Tab>) {
        tabs = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = tabs.size

    private var parentWidth = 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TabViewHolder {
        parentWidth = parent.measuredWidth
        val binding = ItemTabBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return TabViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TabViewHolder, position: Int) {
        // 确保每个 item 宽度等于 RecyclerView 宽度
        holder.itemView.layoutParams = RecyclerView.LayoutParams(
            parentWidth,
            RecyclerView.LayoutParams.MATCH_PARENT
        )
        holder.bind(tabs[position])
    }

    inner class TabViewHolder(
        private val binding: ItemTabBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(tab: Tab) {
            binding.tvTabTitle.text = tab.title ?: "新标签页"

            if (tab.thumbnail != null) {
                binding.ivThumbnail.setImageBitmap(tab.thumbnail)
                // 在 layout 完成后设置缩放矩阵：宽度撑满、顶部对齐
                binding.ivThumbnail.post {
                    val bmp = tab.thumbnail ?: return@post
                    val iv = binding.ivThumbnail
                    if (iv.width > 0 && bmp.width > 0) {
                        val scale = iv.width.toFloat() / bmp.width.toFloat()
                        val matrix = android.graphics.Matrix()
                        matrix.setScale(scale, scale)
                        iv.imageMatrix = matrix
                    }
                }
            } else {
                binding.ivThumbnail.setImageResource(R.color.speed_dial_bg)
            }

            // 高亮当前标签
            val card = (binding.root as android.view.ViewGroup).getChildAt(0) as com.google.android.material.card.MaterialCardView
            card.strokeWidth = if (tab.id == activeTabId) 4 else 0
            card.strokeColor = binding.root.context.getColor(R.color.accent)

            binding.root.setOnClickListener { onClickTab(tab) }
            binding.btnClose.setOnClickListener { onCloseTab(tab) }
        }
    }
}
