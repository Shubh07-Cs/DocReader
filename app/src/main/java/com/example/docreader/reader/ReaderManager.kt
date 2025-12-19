package com.example.docreader.reader

import androidx.fragment.app.Fragment
import com.example.docreader.data.FileType

object ReaderManager {

    fun getEngine(fileType: FileType, parentFragment: Fragment): ReaderEngine {
        return when (fileType) {
            FileType.TEXT -> TextReaderEngine()
            FileType.PDF -> PdfReaderEngine(parentFragment)
            FileType.WORD, FileType.SLIDES, FileType.SHEETS -> OfficeReaderEngine()
            FileType.EPUB -> TextReaderEngine() // Placeholder, ideally specific EPUB engine
            else -> UnsupportedReaderEngine()
        }
    }
}