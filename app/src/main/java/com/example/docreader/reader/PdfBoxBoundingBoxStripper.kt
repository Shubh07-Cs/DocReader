package com.example.docreader.reader

import android.graphics.RectF
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.IOException

/**
 * Holds a single word's text and its normalized bounding box (0f..1f)
 * relative to the PDF page dimensions.
 */
data class WordWithBounds(
    val word: String,
    val bounds: RectF
)

/**
 * A custom PDFTextStripper that extracts every word on a page
 * along with its precise normalized bounding box.
 */
class PdfBoxBoundingBoxStripper : PDFTextStripper() {

    private val wordList = mutableListOf<WordWithBounds>()

    private var pageW = 0f
    private var pageH = 0f

    init {
        sortByPosition = true
    }

    @Throws(IOException::class)
    fun extractWordsWithBounds(document: PDDocument, pageIndex: Int): List<WordWithBounds> {
        this.startPage = pageIndex + 1
        this.endPage = pageIndex + 1

        wordList.clear()

        val page = document.getPage(pageIndex)
        val mediaBox = page.mediaBox
        pageW = mediaBox.width
        pageH = mediaBox.height

        getText(document)

        return wordList.toList()
    }

    @Throws(IOException::class)
    override fun writeString(text: String?, textPositions: List<TextPosition>?) {
        super.writeString(text, textPositions)
        if (textPositions.isNullOrEmpty()) return

        var currentWordChars = mutableListOf<TextPosition>()

        for (pos in textPositions) {
            if (pos.unicode.isBlank()) {
                flushWord(currentWordChars)
                currentWordChars = mutableListOf()
            } else {
                currentWordChars.add(pos)
            }
        }
        flushWord(currentWordChars)
    }

    private fun flushWord(chars: List<TextPosition>) {
        if (chars.isEmpty()) return

        val wordText = chars.joinToString("") { it.unicode }
        if (wordText.isBlank()) return

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE

        for (pos in chars) {
            val x = pos.xDirAdj
            val y = pos.yDirAdj
            val w = pos.widthDirAdj
            val h = pos.heightDir

            if (x < minX) minX = x
            if (y - h < minY) minY = y - h
            if (x + w > maxX) maxX = x + w
            if (y > maxY) maxY = y
        }

        val normalizedRect = RectF(
            (minX / pageW).coerceIn(0f, 1f),
            (minY / pageH).coerceIn(0f, 1f),
            (maxX / pageW).coerceIn(0f, 1f),
            (maxY / pageH).coerceIn(0f, 1f)
        )

        wordList.add(WordWithBounds(wordText, normalizedRect))
    }
}
