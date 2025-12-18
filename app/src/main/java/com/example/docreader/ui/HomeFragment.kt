package com.example.docreader.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.docreader.R
import com.example.docreader.data.FileType
import com.example.docreader.databinding.FragmentHomeBinding

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: HomeViewModel by viewModels()
    private lateinit var adapter: DocumentsAdapter

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            viewModel.loadDocuments()
        } else {
            Toast.makeText(context, "Permission denied.", Toast.LENGTH_SHORT).show()
        }
    }
    
    private val openMultipleDocumentsLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            viewModel.importDocuments(uris)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupFilters()
        setupToolbar()
        observeViewModel()
        
        checkPermissionAndLoad()
    }

    override fun onResume() {
        super.onResume()
        // If we came back from settings for MANAGE_EXTERNAL_STORAGE, reload.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                viewModel.loadDocuments()
            }
        }
    }

    private fun checkPermissionAndLoad() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { // Android 11+ (API 30+)
             if (Environment.isExternalStorageManager()) {
                 viewModel.loadDocuments()
             } else {
                 // Request "All Files Access"
                 try {
                     val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                     intent.addCategory("android.intent.category.DEFAULT")
                     intent.data = Uri.parse("package:${requireContext().packageName}")
                     startActivity(intent)
                     Toast.makeText(context, "Please grant 'All Files Access' to scan documents.", Toast.LENGTH_LONG).show()
                 } catch (e: Exception) {
                     val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                     startActivity(intent)
                 }
             }
        } else {
            // Android < 11: Request READ_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                viewModel.loadDocuments()
            } else {
                requestPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
    }

    private fun observeViewModel() {
        viewModel.uiState.observe(viewLifecycleOwner) { documents ->
            adapter.updateList(documents)
        }
    }

    private fun setupToolbar() {
        binding.topAppBar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_refresh -> {
                    checkPermissionAndLoad()
                    Toast.makeText(context, "Refreshing...", Toast.LENGTH_SHORT).show()
                    true
                }
                R.id.action_add_file -> {
                     openMultipleDocumentsLauncher.launch(arrayOf("*/*"))
                     true
                }
                R.id.action_search -> {
                    true
                }
                else -> false
            }
        }
    }

    private fun setupFilters() {
        binding.chipGroupFilters.setOnCheckedStateChangeListener { group, checkedIds ->
             val filter = when (checkedIds.firstOrNull()) {
                 R.id.chip_pdf -> FileType.PDF
                 R.id.chip_word -> FileType.WORD
                 R.id.chip_slides -> FileType.SLIDES
                 R.id.chip_sheets -> FileType.SHEETS
                 R.id.chip_text -> FileType.TEXT
                 else -> null
             }
             viewModel.setFilter(filter)
        }
    }

    private fun setupRecyclerView() {
        adapter = DocumentsAdapter(emptyList()) { item ->
            val bundle = Bundle().apply {
                putString("documentUri", item.uri)
                putString("documentName", item.name)
                putString("documentType", item.extension) 
            }
            findNavController().navigate(R.id.action_homeFragment_to_readerFragment, bundle)
        }

        binding.recyclerViewDocuments.apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter = this@HomeFragment.adapter
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}