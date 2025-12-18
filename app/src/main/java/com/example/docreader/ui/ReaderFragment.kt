package com.example.docreader.ui

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.example.docreader.R
import com.example.docreader.data.FileType
import com.example.docreader.databinding.FragmentReaderBinding
import com.example.docreader.reader.ReaderEngine
import com.example.docreader.reader.ReaderManager

class ReaderFragment : Fragment() {

    private var _binding: FragmentReaderBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: HomeViewModel by activityViewModels()
    private var readerEngine: ReaderEngine? = null
    private var fileType: FileType = FileType.UNKNOWN
    private var documentUri: String? = null
    private var isBookmarked: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentReaderBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        documentUri = arguments?.getString("documentUri")
        val fileTypeString = arguments?.getString("documentType")
        val docName = arguments?.getString("documentName") ?: "Document"
        isBookmarked = arguments?.getBoolean("isBookmarked") ?: false

        setupToolbar(docName)

        if (documentUri != null && fileTypeString != null) {
            val uri = Uri.parse(documentUri)
            fileType = try {
                FileType.valueOf(fileTypeString)
            } catch (e: Exception) {
                FileType.UNKNOWN
            }

            loadDocument(uri, fileType)
        }
    }

    private fun loadDocument(uri: Uri, fileType: FileType) {
        readerEngine = ReaderManager.getEngine(fileType)
        readerEngine?.load(requireContext(), uri, fileType, binding.readerContainer)
    }

    private fun setupToolbar(title: String) {
        binding.toolbarTitle.text = title
        binding.readerToolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
        
        binding.readerToolbar.menu.clear()
        binding.readerToolbar.inflateMenu(R.menu.reader_menu)
        updateBookmarkIcon(binding.readerToolbar.menu.findItem(R.id.action_bookmark))

        binding.readerToolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_search_doc -> {
                    if (fileType == FileType.PDF) {
                        Toast.makeText(context, "Search not supported for PDF in this mode.", Toast.LENGTH_SHORT).show()
                    } else {
                        showSearchBar()
                    }
                    true
                }
                R.id.action_bookmark -> {
                    documentUri?.let { viewModel.toggleBookmarkStatus(it) }
                    isBookmarked = !isBookmarked
                    updateBookmarkIcon(menuItem)
                    true
                }
                else -> false
            }
        }
        
        setupSearchLogic()
    }
    
    private fun updateBookmarkIcon(item: MenuItem) {
        val iconRes = if (isBookmarked) android.R.drawable.btn_star_big_on else android.R.drawable.btn_star_big_off
        item.icon = ContextCompat.getDrawable(requireContext(), iconRes)
    }

    private fun showSearchBar() {
        binding.toolbarTitle.visibility = View.GONE
        binding.searchContainer.visibility = View.VISIBLE
        binding.searchInput.requestFocus()
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.searchInput, InputMethodManager.SHOW_IMPLICIT)
    }
    
    private fun hideSearchBar() {
        binding.searchContainer.visibility = View.GONE
        binding.toolbarTitle.visibility = View.VISIBLE
        binding.searchInput.text.clear()
        readerEngine?.search("")
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
    }
    
    private fun setupSearchLogic() {
        binding.btnCloseSearch.setOnClickListener { hideSearchBar() }
        
        binding.searchInput.setOnEditorActionListener { v, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                performSearch(v.text.toString())
                val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(v.windowToken, 0)
                true
            } else {
                false
            }
        }
    }
    
    private fun performSearch(query: String) {
        readerEngine?.search(query)
    }

    override fun onDestroyView() {
        readerEngine?.onDestroy()
        super.onDestroyView()
        _binding = null
    }
}