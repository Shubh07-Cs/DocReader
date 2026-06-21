package com.example.docreader.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.docreader.R
import com.example.docreader.databinding.ItemDocumentBinding

import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.DiffUtil

data class DocumentItem(
    val uri: String,
    val name: String,
    val size: String,
    val date: String,
    val mimeType: String,
    val extension: String,
    val isBookmarked: Boolean
)

/** Actions that can be triggered from the file options bottom sheet */
enum class FileAction {
    SELECT, SHARE, OPEN_WITH, RENAME, DELETE, FILE_INFO
}

class DocumentsAdapter(
    private val onItemClick: (DocumentItem) -> Unit,
    private val onMoreClick: (DocumentItem, View) -> Unit = { _, _ -> }
) : ListAdapter<DocumentItem, DocumentsAdapter.DocumentViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DocumentViewHolder {
        val binding = ItemDocumentBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return DocumentViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DocumentViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class DocumentViewHolder(
        private val binding: ItemDocumentBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: DocumentItem) {
            val context = binding.root.context
            
            binding.textDocName.text = item.name
            
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

            // Tap on the row to open the document
            binding.contentRow.setOnClickListener {
                onItemClick(item)
            }
            
            // 3-dot more button opens the bottom sheet
            binding.btnMore.setOnClickListener { view ->
                onMoreClick(item, view)
            }
        }
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<DocumentItem>() {
            override fun areItemsTheSame(oldItem: DocumentItem, newItem: DocumentItem): Boolean {
                return oldItem.uri == newItem.uri
            }

            override fun areContentsTheSame(oldItem: DocumentItem, newItem: DocumentItem): Boolean {
                return oldItem == newItem
            }
        }
    }
}