package com.example.docreader.reader

import androidx.fragment.app.Fragment
import com.example.docreader.data.FileType

object ReaderManager {

    fun getEngine(fileType: FileType, parentFragment: Fragment): ReaderEngine {
        return when (fileType) {
            FileType.TEXT, FileType.EPUB -> TextReaderEngine()
            FileType.PDF -> PdfReaderEngine(parentFragment)
            FileType.WORD, FileType.SLIDES, FileType.SHEETS -> OfficeReaderEngine()
            else -> UnsupportedReaderEngine()
        }
    }
}