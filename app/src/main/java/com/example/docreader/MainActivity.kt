package com.example.docreader

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.docreader.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        handleIntent(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: android.content.Intent?) {
        if (intent?.action == android.content.Intent.ACTION_VIEW) {
            val uri = intent.data
            if (uri != null) {
                val mimeType = contentResolver.getType(uri) ?: ""
                val fileName = getFileName(uri)?.lowercase() ?: ""
                
                val fileType = when {
                    mimeType.contains("pdf") || fileName.endsWith(".pdf") -> com.example.docreader.data.FileType.PDF
                    mimeType.contains("word") || mimeType.contains("doc") || fileName.endsWith(".doc") || fileName.endsWith(".docx") -> com.example.docreader.data.FileType.WORD
                    mimeType.contains("excel") || mimeType.contains("sheet") || mimeType.contains("xls") || mimeType.contains("csv") || mimeType.contains("comma") || fileName.endsWith(".xls") || fileName.endsWith(".xlsx") || fileName.endsWith(".csv") -> com.example.docreader.data.FileType.SHEETS
                    mimeType.contains("powerpoint") || mimeType.contains("presentation") || mimeType.contains("ppt") || fileName.endsWith(".ppt") || fileName.endsWith(".pptx") -> com.example.docreader.data.FileType.SLIDES
                    mimeType.contains("text") || mimeType.contains("txt") || fileName.endsWith(".txt") -> com.example.docreader.data.FileType.TEXT
                    else -> com.example.docreader.data.FileType.UNKNOWN
                }

                if (fileType != com.example.docreader.data.FileType.UNKNOWN) {
                    val bundle = Bundle().apply {
                        putString("documentUri", uri.toString())
                        putString("documentType", fileType.name)
                        putString("documentName", getFileName(uri) ?: "Document")
                        putBoolean("isBookmarked", false)
                    }

                    // Navigate to ReaderFragment
                    val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? androidx.navigation.fragment.NavHostFragment
                    val navController = navHostFragment?.navController
                    navController?.navigate(R.id.readerFragment, bundle)
                } else {
                    android.widget.Toast.makeText(this, "Unsupported file type", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun getFileName(uri: android.net.Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        result = cursor.getString(index)
                    }
                }
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != null && cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result
    }
}