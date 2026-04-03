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
import androidx.appcompat.app.AppCompatDelegate
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: HomeViewModel by activityViewModels()
    private lateinit var adapter: DocumentsAdapter
    
    private var isDarkModeEnabled = false

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
        
        // Check current app theme state
        val currentNightMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        isDarkModeEnabled = currentNightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
        
        setupRecyclerView()
        setupFilters()
        (requireActivity() as? androidx.appcompat.app.AppCompatActivity)?.setSupportActionBar(binding.topAppBar)
        setupMenu()
        setupSearch()
        observeViewModel()
        checkPermissionAndLoad()
    }

    private fun setupSearch() {
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                viewModel.searchDocuments(query ?: "")
                binding.searchView.clearFocus()
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                viewModel.searchDocuments(newText ?: "")
                return true
            }
        })
    }

    private fun setupMenu() {
        val menuHost: MenuHost = requireActivity()
        menuHost.addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.home_menu, menu)
                val darkModeItem = menu.findItem(R.id.action_dark_mode)
                darkModeItem?.title = if (isDarkModeEnabled) "Light Mode" else "Dark Mode"
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
                    R.id.action_sort -> {
                        showSortDialog()
                        true
                    }
                    R.id.action_dark_mode -> {
                        isDarkModeEnabled = !isDarkModeEnabled
                        if (isDarkModeEnabled) {
                            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                        } else {
                            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                        }
                        true
                    }
                    else -> false
                }
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    private fun showSortDialog() {
        val fieldOptions = arrayOf("📅  Date", "🔤  Name", "📦  Size")

        val currentFieldIndex = when (viewModel.getCurrentSortField()) {
            HomeViewModel.SortField.DATE -> 0
            HomeViewModel.SortField.NAME -> 1
            HomeViewModel.SortField.SIZE -> 2
        }
        val currentDirIndex = when (viewModel.getCurrentSortDirection()) {
            HomeViewModel.SortDirection.ASC  -> 0
            HomeViewModel.SortDirection.DESC -> 1
        }

        var pendingFieldIndex = currentFieldIndex

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Sort by")
            .setSingleChoiceItems(fieldOptions, currentFieldIndex) { _, which ->
                pendingFieldIndex = which
                val field = when (which) {
                    0    -> HomeViewModel.SortField.DATE
                    1    -> HomeViewModel.SortField.NAME
                    else -> HomeViewModel.SortField.SIZE
                }
                viewModel.setSortField(field)
            }
            .setPositiveButton("Set Order…") { _, _ ->
                showDirectionDialog(currentDirIndex)
            }
            .setNegativeButton("Done", null)
            .show()
    }

    private fun showDirectionDialog(preSelected: Int) {
        val dirOptions = arrayOf("⬆  Ascending", "⬇  Descending")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Order")
            .setSingleChoiceItems(dirOptions, preSelected) { dialog, which ->
                val dir = if (which == 0) HomeViewModel.SortDirection.ASC
                          else            HomeViewModel.SortDirection.DESC
                viewModel.setSortDirection(dir)
                dialog.dismiss()
            }
            .show()
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
                 showPermissionChoiceDialog()
             }
        } else {
            if (ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                viewModel.loadDocuments()
            } else {
                showPermissionChoiceDialog()
            }
        }
    }

    private fun showPermissionChoiceDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Storage Access")
            .setMessage("Do you want to grant 'All Files Access' to automatically scan your device for all documents, or would you prefer to manually select specific files yourself without granting global access?")
            .setPositiveButton("Grant All Access") { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        intent.addCategory("android.intent.category.DEFAULT")
                        intent.data = Uri.parse("package:${requireContext().packageName}")
                        startActivity(intent)
                        Toast.makeText(context, "Please allow 'All files access' to automatically scan.", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        startActivity(intent)
                    }
                } else {
                    requestPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
            .setNegativeButton("Select Manually") { _, _ ->
                // This securely launches the system file picker WITHOUT requiring any local permissions!
                openMultipleDocumentsLauncher.launch(arrayOf("*/*"))
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun observeViewModel() {
        viewModel.uiState.observe(viewLifecycleOwner) { documents ->
            adapter.submitList(documents)
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
        adapter = DocumentsAdapter { item ->
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