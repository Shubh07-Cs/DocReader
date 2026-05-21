package com.example.docreader.reader

import android.content.Context
import android.util.Base64
import org.apache.poi.hwpf.HWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import org.apache.poi.xwpf.usermodel.XWPFTable
import java.io.InputStream

object WordToHtmlConverter {

    // Track whether we just emitted a page break to avoid duplicates
    private var lastWasPageBreak = false

    fun convertDocx(context: Context, inputStream: InputStream): String {
        return try {
            lastWasPageBreak = false
            val doc = XWPFDocument(inputStream)
            val sb = StringBuilder()
            startHtml(sb)

            for (element in doc.bodyElements) {
                if (element is XWPFParagraph) {
                    processParagraph(element, sb)
                } else if (element is XWPFTable) {
                    processTable(element, sb)
                }
            }
            endHtml(sb)
            sb.toString()
        } catch (e: Exception) {
            errorHtml(e)
        }
    }

    fun convertDoc(context: Context, inputStream: InputStream): String {
        return try {
            lastWasPageBreak = false
            val doc = HWPFDocument(inputStream)
            val range = doc.range
            val sb = StringBuilder()
            startHtml(sb)

            val numParagraphs = range.numParagraphs()
            var inTable = false
            
            for (i in 0 until numParagraphs) {
                val paragraph = range.getParagraph(i)
                
                if (paragraph.isInTable) {
                    if (!inTable) {
                        sb.append("<table>")
                        inTable = true
                    }
                    sb.append("<tr><td>")
                    processDocParagraph(paragraph, sb, doc)
                    sb.append("</td></tr>")
                } else {
                    if (inTable) {
                        sb.append("</table>")
                        inTable = false
                    }
                    processDocParagraph(paragraph, sb, doc)
                }
            }
            if (inTable) sb.append("</table>")
            endHtml(sb)
            sb.toString()
        } catch (e: Exception) {
            errorHtml(e)
        }
    }

    private fun startHtml(sb: StringBuilder) {
        sb.append("<html><head>")
        sb.append("<meta name=\"viewport\" content=\"width=960, user-scalable=yes\">")
        sb.append("<style>")
        sb.append("html { -webkit-text-size-adjust: none; text-size-adjust: none; }")
        sb.append("body { font-family: 'Segoe UI', Roboto, sans-serif; padding: 24px 16px; line-height: 1.5; color: #333; background: #e8ebf0; }")
        sb.append(".page { max-width: 800px; margin: 0 auto 24px auto; background: #fff; padding: 48px 56px; box-shadow: 0 4px 16px rgba(0,0,0,0.06), 0 2px 4px rgba(0,0,0,0.04); border-radius: 4px; box-sizing: border-box; min-height: 200px; }")
        sb.append("table { width: 100%; border-collapse: collapse; margin-bottom: 16px; table-layout: fixed; word-wrap: break-word; }")
        sb.append("td, th { border: 1px solid #ddd; padding: 8px; vertical-align: top; }")
        sb.append("img { max-width: 100%; height: auto; display: block; margin: 8px auto; }")
        // TOC line style
        sb.append(".toc-line { display: flex; align-items: baseline; }")
        sb.append(".toc-left { flex-shrink: 1; padding-right: 4px; }")
        sb.append(".toc-dots { flex-grow: 1; border-bottom: 1px dotted #888; margin: 0 6px; position: relative; top: -4px; min-width: 16px; }")
        sb.append(".toc-right { flex-shrink: 0; text-align: right; }")
        sb.append("</style></head><body><div class='page'>")
    }

    private fun endHtml(sb: StringBuilder) {
        sb.append("</div></body></html>")
    }

    private fun errorHtml(e: Exception): String {
        return "<html><body style='padding:24px;font-family:sans-serif'><h3>Error reading document</h3><p>${e.message}</p></body></html>"
    }

    private fun esc(s: String?): String {
        return (s ?: "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }

    /**
     * Inserts a visual page break (closes the current .page div, opens a new one).
     */
    private fun emitPageBreak(sb: StringBuilder) {
        if (!lastWasPageBreak) {
            sb.append("</div><div class='page'>")
            lastWasPageBreak = true
        }
    }

    /**
     * Uses raw XML string parsing to reliably detect page breaks.
     * Returns true if a new page should start BEFORE this paragraph.
     */
    private fun hasPageBreakBefore(paragraph: XWPFParagraph): Boolean {
        try {
            // 1. High-level API: paragraph-level "page break before"
            if (paragraph.isPageBreak) return true

            // 2. Parse raw XML for run-level <w:br w:type="page"/>
            val xml = paragraph.getCTP().xmlText()
            if (xml.contains("w:type=\"page\"") || xml.contains("type=\"page\"")) return true

            // 3. Check for pageBreakBefore property in pPr
            if (xml.contains("pageBreakBefore")) return true

            // 4. Form-feed character (ASCII 12)
            val text = paragraph.text
            if (text != null && text.contains('\u000c')) return true

        } catch (_: Exception) {}
        return false
    }

    /**
     * Uses raw XML string parsing to detect section breaks.
     * A section break in pPr means this paragraph ENDS the current section,
     * so the page break goes AFTER this paragraph's content.
     */
    private fun hasSectionBreak(paragraph: XWPFParagraph): Boolean {
        try {
            val xml = paragraph.getCTP().xmlText()
            // <w:sectPr> inside <w:pPr> = section break
            if (xml.contains("sectPr")) return true
        } catch (_: Exception) {}
        return false
    }

    /**
     * Checks if a paragraph's tab-separated content looks like a TOC entry
     * (left side is a title, right side is a page number).
     * Simple heuristic: the right-side content after the last tab is short (≤5 chars)
     * and looks numeric or like a roman numeral.
     */
    private fun isTocLine(fullText: String): Boolean {
        val lastTab = fullText.lastIndexOf('\t')
        if (lastTab < 0) return false
        val rightPart = fullText.substring(lastTab + 1).trim()
        if (rightPart.isEmpty()) return false
        // If right part is short and mostly digits/roman numerals, it's TOC
        if (rightPart.length <= 6) {
            val cleaned = rightPart.replace(".", "").replace(" ", "")
            if (cleaned.all { it.isDigit() || it in "ivxlcIVXLC" }) return true
        }
        return false
    }

    private fun processParagraph(paragraph: XWPFParagraph, sb: StringBuilder) {
        // --- Page break BEFORE this paragraph (explicit page break) ---
        if (hasPageBreakBefore(paragraph)) {
            emitPageBreak(sb)
        }

        val align = when (paragraph.alignment?.name) {
            "CENTER" -> "center"
            "RIGHT" -> "right"
            "BOTH" -> "justify"
            else -> "left"
        }

        // Extract native MS Word Twips and convert to Pixels (1 px ≈ 15 twips)
        var indLeft = 0; var indRight = 0; var indFirst = 0; var spaceBefore = 0; var spaceAfter = 12
        try {
            indLeft = Math.max(0, paragraph.indentationLeft / 15)
            indRight = Math.max(0, paragraph.indentationRight / 15)
            indFirst = Math.max(0, paragraph.indentationFirstLine / 15)
            spaceBefore = Math.max(0, paragraph.spacingBefore / 15)
            spaceAfter = Math.max(12, paragraph.spacingAfter / 15)
        } catch (_: Exception) {}

        // Extract line spacing safely
        var lineHeight = ""
        try {
            val xml = paragraph.getCTP().xmlText()
            // Look for w:line="NNN" in the spacing element
            val lineRegex = Regex("""w:line="(\d+)"""")
            val lineMatch = lineRegex.find(xml)
            if (lineMatch != null) {
                val lineVal = lineMatch.groupValues[1].toIntOrNull() ?: 0
                if (lineVal > 0) {
                    val multiplier = lineVal.toFloat() / 240f
                    if (multiplier > 0.5f && multiplier < 5f) {
                        lineHeight = "line-height:${multiplier};"
                    }
                }
            }
        } catch (_: Throwable) {}

        // Check if paragraph contains tab character
        val pText = paragraph.text ?: ""
        val hasTab = pText.contains("\t")

        if (hasTab && isTocLine(pText)) {
            renderTocLineDocx(paragraph, sb, align, indLeft, indRight, spaceBefore, spaceAfter)
            lastWasPageBreak = false
            // Section break AFTER this paragraph
            if (hasSectionBreak(paragraph)) emitPageBreak(sb)
            return
        }

        var marginHtml = ""
        val numId = paragraph.numID
        if (numId != null) {
            val lvl = paragraph.numIlvl?.toInt() ?: 0
            val bullet = if (paragraph.numFmt == "decimal") "1." else "•"
            sb.append("<div style='display:flex; margin-left:${(lvl * 24) + indLeft}px; margin-right:${indRight}px; margin-top:${spaceBefore}px; margin-bottom:${spaceAfter}px; $lineHeight'>")
            sb.append("<div style='width:24px; flex-shrink:0;'>$bullet</div>")
            sb.append("<div style='flex-grow:1;'><p style='text-align:$align; margin:0;'>")
            marginHtml = "</div></div>"
        } else {
            sb.append("<p style='text-align:$align; margin:${spaceBefore}px ${indRight}px ${spaceAfter}px ${indLeft}px; text-indent:${indFirst}px; $lineHeight'>")
            marginHtml = "</p>"
        }

        for (run in paragraph.runs) {
             val style = StringBuilder()
             try {
                 if (run.isBold) style.append("font-weight:bold;")
                 if (run.isItalic) style.append("font-style:italic;")
                 if (run.underline != org.apache.poi.xwpf.usermodel.UnderlinePatterns.NONE) style.append("text-decoration:underline;")
                 
                 run.color?.let { 
                     if (it.length == 6) style.append("color:#$it;") 
                     else if (it != "auto") style.append("color:$it;")
                 }
                 val sz = run.fontSize
                 if (sz != -1) style.append("font-size:${sz}pt;")
             } catch (_: Throwable) {}
             
             if (run.embeddedPictures.isNotEmpty()) {
                 for (pic in run.embeddedPictures) {
                     try {
                         val data = pic.pictureData.data
                         val base64 = Base64.encodeToString(data, Base64.NO_WRAP)
                         val mime = pic.pictureData.packagePart.contentType
                         sb.append("<img src='data:$mime;base64,$base64' />")
                     } catch (_: Throwable) {}
                 }
             }
             var text = run.text()?.replace("\r", "") ?: ""
             text = text.replace("\t", "\u00A0\u00A0\u00A0\u00A0\u00A0\u00A0\u00A0\u00A0")
             sb.append("<span style='$style'>${esc(text)}</span>")
        }
        sb.append(marginHtml)
        lastWasPageBreak = false

        // --- Section break AFTER this paragraph (section ends here, new page starts next) ---
        if (hasSectionBreak(paragraph)) {
            emitPageBreak(sb)
        }
    }

    /**
     * Renders a TOC line with dot leaders for DOCX paragraphs.
     */
    private fun renderTocLineDocx(paragraph: XWPFParagraph, sb: StringBuilder, align: String,
                                   indLeft: Int, indRight: Int, spaceBefore: Int, spaceAfter: Int) {
        val leftSb = StringBuilder()
        val rightSb = StringBuilder()
        var onRightSide = false

        for (run in paragraph.runs) {
            val runText = run.text() ?: ""
            val style = StringBuilder()
            try {
                if (run.isBold) style.append("font-weight:bold;")
                if (run.isItalic) style.append("font-style:italic;")
                if (run.underline != org.apache.poi.xwpf.usermodel.UnderlinePatterns.NONE) style.append("text-decoration:underline;")
                run.color?.let {
                    if (it.length == 6) style.append("color:#$it;")
                    else if (it != "auto") style.append("color:$it;")
                }
                val sz = run.fontSize
                if (sz != -1) style.append("font-size:${sz}pt;")
            } catch (_: Throwable) {}

            if (runText.contains("\t")) {
                val firstTab = runText.indexOf('\t')
                val lastTab = runText.lastIndexOf('\t')
                val before = runText.substring(0, firstTab)
                val after = runText.substring(lastTab + 1)
                if (before.isNotEmpty()) leftSb.append("<span style='$style'>${esc(before)}</span>")
                onRightSide = true
                if (after.isNotEmpty()) rightSb.append("<span style='$style'>${esc(after)}</span>")
            } else {
                if (!onRightSide) leftSb.append("<span style='$style'>${esc(runText)}</span>")
                else rightSb.append("<span style='$style'>${esc(runText)}</span>")
            }
        }

        sb.append("<div class='toc-line' style='margin:${spaceBefore}px ${indRight}px ${spaceAfter}px ${indLeft}px;'>")
        sb.append("<div class='toc-left'>$leftSb</div>")
        sb.append("<div class='toc-dots'></div>")
        sb.append("<div class='toc-right'>$rightSb</div>")
        sb.append("</div>")
    }

    private fun processTable(table: XWPFTable, sb: StringBuilder) {
        sb.append("<table>")
        for (row in table.rows) {
            sb.append("<tr>")
            for (cell in row.tableCells) {
                sb.append("<td>")
                for (paragraph in cell.paragraphs) {
                    processParagraph(paragraph, sb)
                }
                sb.append("</td>")
            }
            sb.append("</tr>")
        }
        sb.append("</table>")
    }

    // ===================== DOC (.doc) PROCESSING =====================

    private fun processDocParagraph(paragraph: org.apache.poi.hwpf.usermodel.Paragraph, sb: StringBuilder, doc: HWPFDocument) {
        // --- Page break detection for .doc ---
        val hasPageBreak = try {
            paragraph.pageBreakBefore() || (0 until paragraph.numCharacterRuns()).any { i ->
                val text = paragraph.getCharacterRun(i).text()
                text.contains('\u000c') // form-feed = page break
            }
        } catch (_: Exception) {
            false
        }

        if (hasPageBreak) {
            emitPageBreak(sb)
        }

        val align = when (paragraph.justification.toInt()) {
            1 -> "center"
            2 -> "right"
            3 -> "justify"
            else -> "left"
        }

        var indLeft = 0; var indRight = 0; var indFirst = 0; var spaceBefore = 0; var spaceAfter = 12
        try {
            indLeft = Math.max(0, paragraph.indentFromLeft / 15)
            indRight = Math.max(0, paragraph.indentFromRight / 15)
            indFirst = Math.max(0, paragraph.firstLineIndent / 15)
            spaceBefore = Math.max(0, paragraph.spacingBefore / 15)
            spaceAfter = Math.max(12, paragraph.spacingAfter / 15)
        } catch (_: Exception) { }

        // Check for TOC lines in .doc
        val pText = paragraph.text() ?: ""
        val hasTab = pText.contains("\t")

        if (hasTab && isTocLine(pText)) {
            renderTocLineDoc(paragraph, sb, align, indLeft, indRight, spaceBefore, spaceAfter, doc)
            lastWasPageBreak = false
            return
        }
        
        var prefixHtml = ""
        var suffixHtml = ""
        if (paragraph.ilfo > 0) {
            val lvl = paragraph.ilvl.toInt()
            prefixHtml = "<div style='display:flex; margin-left:${(lvl * 24) + indLeft}px; margin-right:${indRight}px; margin-top:${spaceBefore}px; margin-bottom:${spaceAfter}px;'><div style='width:24px; flex-shrink:0;'>•</div><div style='flex-grow:1;'>"
            suffixHtml = "</div></div>"
            sb.append(prefixHtml)
            sb.append("<p style='text-align:$align; margin:0;'>")
        } else {
            sb.append("<p style='text-align:$align; margin:${spaceBefore}px ${indRight}px ${spaceAfter}px ${indLeft}px; text-indent:${indFirst}px;'>")
        }
        
        for (i in 0 until paragraph.numCharacterRuns()) {
            val run = paragraph.getCharacterRun(i)
            var text = run.text().replace("\r", "").replace("\u0007", "")
            if (text.isEmpty() && !doc.picturesTable.hasPicture(run)) continue
            
            val style = StringBuilder()
            if (run.isBold) style.append("font-weight:bold;")
            if (run.isItalic) style.append("font-style:italic;")
            
            val sz = run.fontSize / 2
            if (sz > 0) style.append("font-size:${sz}pt;")
            
            if (doc.picturesTable.hasPicture(run)) {
                try {
                    val pic = doc.picturesTable.extractPicture(run, true)
                    if (pic != null) {
                        val base64 = Base64.encodeToString(pic.content, Base64.NO_WRAP)
                        sb.append("<img src='data:${pic.mimeType};base64,$base64' />")
                    }
                } catch (_: Throwable) {}
            } else {
                // Replace tab with flexible space for non-TOC content
                text = text.replace("\t", "\u00A0\u00A0\u00A0\u00A0\u00A0\u00A0\u00A0\u00A0")
                sb.append("<span style='$style'>${esc(text)}</span>")
            }
        }
        sb.append("</p>")
        sb.append(suffixHtml)
        lastWasPageBreak = false
    }

    /**
     * Renders a TOC line with dot leaders for DOC paragraphs.
     */
    private fun renderTocLineDoc(paragraph: org.apache.poi.hwpf.usermodel.Paragraph, sb: StringBuilder,
                                  align: String, indLeft: Int, indRight: Int, spaceBefore: Int, spaceAfter: Int,
                                  doc: HWPFDocument) {
        val leftSb = StringBuilder()
        val rightSb = StringBuilder()
        var onRightSide = false

        for (i in 0 until paragraph.numCharacterRuns()) {
            val run = paragraph.getCharacterRun(i)
            val runText = run.text().replace("\r", "").replace("\u0007", "")
            if (runText.isEmpty() && !doc.picturesTable.hasPicture(run)) continue

            val style = StringBuilder()
            if (run.isBold) style.append("font-weight:bold;")
            if (run.isItalic) style.append("font-style:italic;")
            val sz = run.fontSize / 2
            if (sz > 0) style.append("font-size:${sz}pt;")

            if (runText.contains("\t")) {
                val firstTab = runText.indexOf('\t')
                val lastTab = runText.lastIndexOf('\t')
                val before = runText.substring(0, firstTab)
                val after = runText.substring(lastTab + 1)
                if (before.isNotEmpty()) leftSb.append("<span style='$style'>${esc(before)}</span>")
                onRightSide = true
                if (after.isNotEmpty()) rightSb.append("<span style='$style'>${esc(after)}</span>")
            } else {
                if (!onRightSide) leftSb.append("<span style='$style'>${esc(runText)}</span>")
                else rightSb.append("<span style='$style'>${esc(runText)}</span>")
            }
        }

        sb.append("<div class='toc-line' style='margin:${spaceBefore}px ${indRight}px ${spaceAfter}px ${indLeft}px;'>")
        sb.append("<div class='toc-left'>$leftSb</div>")
        sb.append("<div class='toc-dots'></div>")
        sb.append("<div class='toc-right'>$rightSb</div>")
        sb.append("</div>")
    }
}
