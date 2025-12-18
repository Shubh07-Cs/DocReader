package com.example.docreader.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.docreader.R
import com.example.docreader.databinding.ItemDocumentBinding

data class DocumentItem(
    val uri: String,
    val name: String,
    val size: String,
    val date: String,
    val mimeType: String,
    val extension: String,
    val isBookmarked: Boolean
)

class DocumentsAdapter(
    private var items: List<DocumentItem>,
    private val onItemClick: (DocumentItem) -> Unit
) : RecyclerView.Adapter<DocumentsAdapter.DocumentViewHolder>() {

    fun updateList(newItems: List<DocumentItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DocumentViewHolder {
        val binding = ItemDocumentBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return DocumentViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DocumentViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class DocumentViewHolder(
        private val binding: ItemDocumentBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: DocumentItem) {
            binding.textDocName.text = item.name
            binding.textDocInfo.text = "${item.size} • ${item.date}"
            
            val iconRes = when (item.extension.lowercase()) {
                "pdf" -> android.R.drawable.ic_menu_agenda
                "word" -> android.R.drawable.ic_menu_edit
                "sheets" -> android.R.drawable.ic_menu_my_calendar
                "slides" -> android.R.drawable.ic_menu_slideshow
                "text" -> android.R.drawable.ic_menu_info_details
                else -> android.R.drawable.ic_menu_crop
            }
            binding.iconFileType.setImageResource(iconRes)
            
            binding.iconBookmark.visibility = if (item.isBookmarked) View.VISIBLE else View.GONE

            binding.root.setOnClickListener {
                onItemClick(item)
            }
        }
    }
}