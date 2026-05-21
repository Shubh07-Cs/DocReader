package com.example.docreader.reader

import androidx.fragment.app.Fragment
import com.example.docreader.data.FileType

object ReaderManager {

    fun getEngine(fileType: FileType, parentFragment: Fragment): ReaderEngine {
        return when (fileType) {
            FileType.TEXT, FileType.MARKDOWN, FileType.JSON, FileType.XML, FileType.RTF, FileType.ODT, FileType.ODP -> TextReaderEngine()
            FileType.PDF -> PdfReaderEngine(parentFragment)
            FileType.WORD, FileType.SLIDES, FileType.SHEETS, FileType.HTML, FileType.ODS, FileType.EPUB, FileType.MOBI -> OfficeReaderEngine()
            FileType.CBZ, FileType.CBR -> ComicReaderEngine()
            else -> UnsupportedReaderEngine()
        }
    }
}