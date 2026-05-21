package com.example.docreader.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.docreader.data.FileType
import com.github.junrar.Archive
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

class ComicReaderEngine : ReaderEngine {

    private var recyclerView: RecyclerView? = null
    private var loadJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private var unzippedDir: File? = null
    private var loadingCallback: ((Boolean) -> Unit)? = null

    override fun load(context: Context, uri: Uri, fileType: FileType, container: ViewGroup) {
        recyclerView = RecyclerView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            layoutManager = LinearLayoutManager(context)
            itemAnimator = null
        }
        container.removeAllViews()
        container.addView(recyclerView)
        
        loadingCallback?.invoke(true)
        
        loadJob = scope.launch(Dispatchers.IO) {
            try {
                val tempFile = File(context.cacheDir, "temp_comic_${System.currentTimeMillis()}")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                unzippedDir = File(context.cacheDir, "comic_extracted_${System.currentTimeMillis()}")
                unzippedDir?.mkdirs()

                if (fileType == FileType.CBZ) {
                    ZipFile(tempFile).use { zip ->
                        zip.entries().asSequence().forEach { entry ->
                            if (!entry.isDirectory && isImageFile(entry.name)) {
                                val cleanName = entry.name.replace("\\", "/").substringAfterLast("/")
                                val outFile = File(unzippedDir, cleanName)
                                zip.getInputStream(entry).use { input ->
                                    outFile.outputStream().use { output ->
                                        input.copyTo(output)
                                    }
                                }
                            }
                        }
                    }
                } else if (fileType == FileType.CBR) {
                    Archive(tempFile).use { archive ->
                        var fileHeader = archive.nextFileHeader()
                        while (fileHeader != null) {
                            if (!fileHeader.isDirectory && isImageFile(fileHeader.fileNameString)) {
                                val cleanName = fileHeader.fileNameString.replace("\\", "/").substringAfterLast("/")
                                val outFile = File(unzippedDir, cleanName)
                                FileOutputStream(outFile).use { output ->
                                    archive.extractFile(fileHeader, output)
                                }
                            }
                            fileHeader = archive.nextFileHeader()
                        }
                    }
                }

                tempFile.delete()

                val imageFiles = unzippedDir?.listFiles { file -> isImageFile(file.name) }
                    ?.sortedBy { it.name }
                    ?.toList() ?: emptyList()

                withContext(Dispatchers.Main) {
                    recyclerView?.adapter = ComicAdapter(imageFiles)
                    loadingCallback?.invoke(false)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    loadingCallback?.invoke(false)
                }
            }
        }
    }

    private fun isImageFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp")
    }

    override fun onDestroy() {
        loadJob?.cancel()
        recyclerView = null
        scope.launch(Dispatchers.IO) {
            unzippedDir?.deleteRecursively()
        }
    }

    override fun search(query: String) {}

    override fun setOnLoadingStateListener(callback: (Boolean) -> Unit) {
        this.loadingCallback = callback
    }
}

class ComicAdapter(private val images: List<File>) : RecyclerView.Adapter<ComicAdapter.ComicViewHolder>() {

    private val scope = CoroutineScope(Dispatchers.Main)

    class ComicViewHolder(val imageView: com.github.chrisbanes.photoview.PhotoView) : RecyclerView.ViewHolder(imageView) {
        var currentJob: Job? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ComicViewHolder {
        val imageView = com.github.chrisbanes.photoview.PhotoView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(android.graphics.Color.parseColor("#1A000000")) // Subtle loading placeholder
        }
        return ComicViewHolder(imageView)
    }

    override fun onBindViewHolder(holder: ComicViewHolder, position: Int) {
        val file = images[position]
        holder.imageView.setImageBitmap(null)
        holder.currentJob?.cancel()
        
        holder.currentJob = scope.launch(Dispatchers.IO) {
            try {
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeFile(file.absolutePath, options)
                options.inSampleSize = calculateInSampleSize(options, 2048, 2048) // Allow high-res for zooming
                options.inJustDecodeBounds = false
                options.inPreferredConfig = Bitmap.Config.ARGB_8888
                val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
                
                withContext(Dispatchers.Main) {
                    holder.imageView.setImageBitmap(bitmap)
                }
            } catch (e: Exception) {
            }
        }
    }

    override fun onViewRecycled(holder: ComicViewHolder) {
        super.onViewRecycled(holder)
        holder.currentJob?.cancel()
        holder.imageView.setImageBitmap(null)
    }

    override fun getItemCount(): Int = images.size

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
