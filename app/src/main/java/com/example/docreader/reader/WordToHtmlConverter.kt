package com.example.docreader.reader

import android.content.Context
import android.util.Base64
import org.apache.poi.hwpf.HWPFDocument
import org.apache.poi.xwpf.usermodel.IBodyElement
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import org.apache.poi.xwpf.usermodel.XWPFTable
import java.io.InputStream

object WordToHtmlConverter {

    fun convertDocx(context: Context, inputStream: InputStream): String {
        return try {
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
        // Fix: Emulates an A4 document viewport width (960px) rather than squeezing into mobile width (400px)
        sb.append("<meta name=\"viewport\" content=\"width=960, user-scalable=yes\">")
        sb.append("<style>")
        sb.append("html { -webkit-text-size-adjust: none; text-size-adjust: none; }")
        sb.append("body { font-family: 'Segoe UI', Roboto, sans-serif; padding: 24px 16px; line-height: 1.5; color: #333; background: #e0e0e0; }")
        sb.append(".page { max-width: 800px; margin: 0 auto; background: #fff; padding: 32px; box-shadow: 0 4px 16px rgba(0,0,0,0.1); }")
        sb.append("table { width: 100%; border-collapse: collapse; margin-bottom: 16px; table-layout: fixed; word-wrap: break-word; }")
        sb.append("td, th { border: 1px solid #ddd; padding: 8px; vertical-align: top; }")
        sb.append("img { max-width: 100%; height: auto; display: block; margin: 8px auto; }")
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

    private fun processParagraph(paragraph: XWPFParagraph, sb: StringBuilder) {
        val align = when (paragraph.alignment?.name) {
            "CENTER" -> "center"
            "RIGHT" -> "right"
            "BOTH" -> "justify"
            else -> "left"
        }
        
        var marginHtml = ""
        val numId = paragraph.numID
        if (numId != null) {
            val lvl = paragraph.numIlvl?.toInt() ?: 0
            val bullet = if (paragraph.numFmt == "decimal") "1." else "•"
            sb.append("<div style='display:flex; margin-left:${lvl * 24}px; margin-bottom:12px;'>")
            sb.append("<div style='width:24px; flex-shrink:0;'>$bullet</div>")
            sb.append("<div style='flex-grow:1;'><p style='text-align:$align; margin:0;'>")
            marginHtml = "</div></div>"
        } else {
            sb.append("<p style='text-align:$align; margin:0 0 12px 0;'>")
            marginHtml = "</p>"
        }

        for (run in paragraph.runs) {
             val style = StringBuilder()
             if (run.isBold) style.append("font-weight:bold;")
             if (run.isItalic) style.append("font-style:italic;")
             if (run.underline != org.apache.poi.xwpf.usermodel.UnderlinePatterns.NONE) style.append("text-decoration:underline;")
             
             run.color?.let { 
                 if (it.length == 6) style.append("color:#$it;") 
                 else if (it != "auto") style.append("color:$it;")
             }
             val sz = run.fontSize
             if (sz != -1) style.append("font-size:${sz}pt;")
             
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
             val text = esc(run.text()?.replace("\r", ""))
             sb.append("<span style='$style'>$text</span>")
        }
        sb.append(marginHtml)
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

    private fun processDocParagraph(paragraph: org.apache.poi.hwpf.usermodel.Paragraph, sb: StringBuilder, doc: HWPFDocument) {
        val align = when (paragraph.justification.toInt()) {
            1 -> "center"
            2 -> "right"
            3 -> "justify"
            else -> "left"
        }
        
        var prefixHtml = ""
        var suffixHtml = ""
        if (paragraph.ilfo > 0) {
            val lvl = paragraph.ilvl.toInt()
            val indent = lvl * 24
            prefixHtml = "<div style='display:flex; margin-left:${indent}px; margin-bottom:12px;'><div style='width:24px; flex-shrink:0;'>•</div><div style='flex-grow:1;'>"
            suffixHtml = "</div></div>"
        }
        
        sb.append(prefixHtml)
        sb.append("<p style='text-align:$align; margin:0;'>")
        
        for (i in 0 until paragraph.numCharacterRuns()) {
            val run = paragraph.getCharacterRun(i)
            val text = run.text().replace("\r", "").replace("\u0007", "") // remove DOC cell markers
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
                sb.append("<span style='$style'>${esc(text)}</span>")
            }
        }
        sb.append("</p>")
        sb.append(suffixHtml)
        if (prefixHtml.isEmpty()) sb.append("<div style='height:12px;'></div>")
    }
}
