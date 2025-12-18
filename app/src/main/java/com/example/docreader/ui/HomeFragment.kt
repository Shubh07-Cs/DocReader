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
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.docreader.R
import com.example.docreader.data.FileType
import com.example.docreader.databinding.FragmentHomeBinding

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: HomeViewModel by activityViewModels()
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
        (requireActivity() as? androidx.appcompat.app.AppCompatActivity)?.setSupportActionBar(binding.topAppBar)
        setupMenu()
        observeViewModel()
        checkPermissionAndLoad()
    }

    private fun setupMenu() {
        val menuHost: MenuHost = requireActivity()
        menuHost.addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.home_menu, menu)
                
                val searchItem = menu.findItem(R.id.action_search)
                val searchView = searchItem.actionView as? SearchView
                
                searchView?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                    override fun onQueryTextSubmit(query: String?): Boolean {
                        viewModel.searchDocuments(query ?: "")
                        searchView.clearFocus()
                        return true
                    }

                    override fun onQueryTextChange(newText: String?): Boolean {
                        viewModel.searchDocuments(newText ?: "")
                        return true
                    }
                })
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when (menuItem.itemId) {
                    R.id.action_refresh -> {
                        checkPermissionAndLoad()
                        Toast.makeText(context, "Refreshing...", Toast.LENGTH_SHORT).show()
                        true
                    }
                    R.id.action_add_file -> {
                        openMultipleDocumentsLauncher.launch(arrayOf("*/*"))
                        true
                    }
                    else -> false
                }
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                viewModel.loadDocuments()
            }
        }
    }

    private fun checkPermissionAndLoad() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
             if (Environment.isExternalStorageManager()) {
                 viewModel.loadDocuments()
             } else {
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

    private fun setupFilters() {
        binding.chipGroupFilters.setOnCheckedChangeListener { group, checkedId ->
             viewModel.toggleBookmarkFilter(false)
             viewModel.setFilter(null)
             
             when (checkedId) {
                R.id.chip_bookmarks -> {
                    viewModel.toggleBookmarkFilter(true)
                }
                R.id.chip_pdf -> viewModel.setFilter(FileType.PDF)
                R.id.chip_word -> viewModel.setFilter(FileType.WORD)
                R.id.chip_slides -> viewModel.setFilter(FileType.SLIDES)
                R.id.chip_sheets -> viewModel.setFilter(FileType.SHEETS)
                R.id.chip_text -> viewModel.setFilter(FileType.TEXT)
                R.id.chip_all -> {
                     // No specific type filter, show all
                }
                else -> {
                    // This case handles when selection is cleared
                    viewModel.toggleBookmarkFilter(false)
                    viewModel.setFilter(null)
                }
             }
        }
    }

    private fun setupRecyclerView() {
        adapter = DocumentsAdapter(emptyList()) { item ->
            val bundle = Bundle().apply {
                putString("documentUri", item.uri)
                putString("documentName", item.name)
                putString("documentType", item.extension)
                putBoolean("isBookmarked", item.isBookmarked)
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