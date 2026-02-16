package com.example.docreader.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
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
            val context = binding.root.context
            
            binding.textDocName.text = item.name
            binding.textDocInfo.text = "${item.size}  •  ${item.date}"
            
            // Determine file type icon and color based on extension
            val (iconRes, colorRes) = when (item.extension.lowercase()) {
                "pdf" -> R.drawable.ic_pdf to R.color.file_pdf
                "doc", "docx", "word" -> R.drawable.ic_word to R.color.file_word
                "xls", "xlsx", "sheets" -> R.drawable.ic_excel to R.color.file_excel
                "ppt", "pptx", "slides" -> R.drawable.ic_powerpoint to R.color.file_powerpoint
                "txt", "text" -> R.drawable.ic_text to R.color.file_text
                else -> R.drawable.ic_text to R.color.file_unknown
            }
            
            val color = ContextCompat.getColor(context, colorRes)
            
            // Set icon and color
            binding.iconFileType.setImageResource(iconRes)
            binding.iconFileType.imageTintList = ColorStateList.valueOf(color)
            
            // Set accent stripe color
            binding.accentStripe.setBackgroundColor(color)
            
            // Set circular icon background tint
            binding.iconBackground.backgroundTintList = ColorStateList.valueOf(color)
            
            // Handle bookmark visibility and icon
            if (item.isBookmarked) {
                binding.iconBookmark.visibility = View.VISIBLE
                binding.iconBookmark.setImageResource(android.R.drawable.btn_star_big_on)
                binding.iconBookmark.imageTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(context, R.color.accent)
                )
            } else {
                binding.iconBookmark.visibility = View.VISIBLE
                binding.iconBookmark.setImageResource(android.R.drawable.btn_star_big_off)
                binding.iconBookmark.imageTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(context, R.color.text_secondary_light)
                )
            }

            binding.root.setOnClickListener {
                onItemClick(item)
            }
        }
    }
}