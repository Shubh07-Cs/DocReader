package com.example.docreader.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.docreader.R
import com.example.docreader.databinding.ItemDocumentBinding

data class DocumentItem(
    val uri: String, // Added URI for future file opening
    val name: String,
    val size: String,
    val date: String,
    val mimeType: String,
    val extension: String
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
                "pdf" -> android.R.drawable.ic_menu_agenda // Placeholder for PDF
                "docx", "doc" -> android.R.drawable.ic_menu_edit // Placeholder for Word
                "xlsx", "xls" -> android.R.drawable.ic_menu_my_calendar // Placeholder for Excel
                "pptx", "ppt" -> android.R.drawable.ic_menu_slideshow // Placeholder for Slides
                "txt" -> android.R.drawable.ic_menu_info_details // Placeholder for Text
                else -> android.R.drawable.ic_menu_crop // Default
            }
            binding.iconFileType.setImageResource(iconRes)
            
            binding.root.setOnClickListener {
                onItemClick(item)
            }
        }
    }
}