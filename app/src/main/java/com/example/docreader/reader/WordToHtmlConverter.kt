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
                    sb.append("<tr><td>${paragraph.text()}</td></tr>")
                } else {
                    if (inTable) {
                        sb.append("</table>")
                        inTable = false
                    }
                    processDocParagraph(paragraph, sb)
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
        sb.append("<html><head><style>")
        sb.append("body { font-family: sans-serif; padding: 16px; line-height: 1.5; color: #333; }")
        sb.append("p { margin-bottom: 10px; }")
        sb.append("table { width: 100%; border-collapse: collapse; margin-bottom: 16px; }")
        sb.append("td, th { border: 1px solid #aaa; padding: 8px; vertical-align: top; }")
        sb.append("img { max-width: 100%; height: auto; display: block; margin: 8px 0; }")
        sb.append(".bold { font-weight: bold; }")
        sb.append(".italic { font-style: italic; }")
        sb.append("</style></head><body>")
    }

    private fun endHtml(sb: StringBuilder) {
        sb.append("</body></html>")
    }

    private fun errorHtml(e: Exception): String {
        return "<html><body><h3>Error reading document</h3><p>${e.message}</p></body></html>"
    }

    private fun processParagraph(paragraph: XWPFParagraph, sb: StringBuilder) {
        sb.append("<p>")
        for (run in paragraph.runs) {
             val style = StringBuilder()
             if (run.isBold) style.append("font-weight:bold;")
             if (run.isItalic) style.append("font-style:italic;")
             if (run.underline != org.apache.poi.xwpf.usermodel.UnderlinePatterns.NONE) style.append("text-decoration:underline;")
             
             if (run.embeddedPictures.isNotEmpty()) {
                 for (pic in run.embeddedPictures) {
                     val data = pic.pictureData.data
                     val base64 = Base64.encodeToString(data, Base64.NO_WRAP)
                     val mime = pic.pictureData.packagePart.contentType
                     sb.append("<img src='data:$mime;base64,$base64' />")
                 }
             }
             sb.append("<span style='$style'>${run.text()}</span>")
        }
        sb.append("</p>")
    }

    private fun processTable(table: XWPFTable, sb: StringBuilder) {
        sb.append("<table>")
        for (row in table.rows) {
            sb.append("<tr>")
            for (cell in row.tableCells) {
                sb.append("<td>")
                for (paragraph in cell.paragraphs) {
                    sb.append("<p>${paragraph.text}</p>") // Simplified
                }
                sb.append("</td>")
            }
            sb.append("</tr>")
        }
        sb.append("</table>")
    }

    private fun processDocParagraph(paragraph: org.apache.poi.hwpf.usermodel.Paragraph, sb: StringBuilder) {
        sb.append("<p>")
        // Basic plain text fallback for DOC to avoid complex Run iteration crashes
        sb.append(paragraph.text())
        sb.append("</p>")
    }
}
