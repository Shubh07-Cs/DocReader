package com.example.docreader.reader

import com.example.docreader.data.FileType

object ReaderManager {

    fun getEngine(fileType: FileType): ReaderEngine {
        return when (fileType) {
            FileType.TEXT -> TextReaderEngine()
            FileType.PDF -> PdfReaderEngine()
            FileType.WORD, FileType.SLIDES, FileType.SHEETS -> OfficeReaderEngine()
            FileType.EPUB -> TextReaderEngine() // Placeholder, ideally specific EPUB engine
            else -> UnsupportedReaderEngine()
        }
    }
}