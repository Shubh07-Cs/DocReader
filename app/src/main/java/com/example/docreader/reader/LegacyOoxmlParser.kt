package com.example.docreader.reader

import android.content.Context
import android.net.Uri
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.hwpf.HWPFDocument
import org.apache.poi.hwpf.extractor.WordExtractor
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.Workbook
import java.io.InputStream

object LegacyOoxmlParser {

    fun parseDoc(inputStream: InputStream): String {
        return try {
            val doc = HWPFDocument(inputStream)
            val extractor = WordExtractor(doc)
            val text = extractor.text
            text.replace("\n", "<br/>")
        } catch (e: Exception) {
            e.printStackTrace()
            "<h3>Error reading legacy .doc file</h3><p>${e.message}</p>"
        }
    }

    fun parseXls(inputStream: InputStream): String {
        return try {
            val wb: Workbook = HSSFWorkbook(inputStream)
            val sb = StringBuilder("<html><head><style>table { border-collapse: collapse; width: 100%; } td, th { border: 1px solid #ddd; padding: 8px; }</style></head><body>")
            
            // Use DataFormatter to read cell values as they are displayed in Excel
            val formatter = DataFormatter()

            for (i in 0 until wb.numberOfSheets) {
                val sheet = wb.getSheetAt(i)
                sb.append("<h3>${sheet.sheetName}</h3>")
                sb.append("<table>")
                sheet.forEach { row ->
                    sb.append("<tr>")
                    row.forEach { cell ->
                        // Use the formatter to get the displayed value
                        val cellValue = formatter.formatCellValue(cell)
                        sb.append("<td>$cellValue</td>")
                    }
                    sb.append("</tr>")
                }
                sb.append("</table><br/><br/>")
            }
            
            sb.append("</body></html>")
            sb.toString()
        } catch (e: Exception) {
            e.printStackTrace()
            "<h3>Error reading legacy .xls file</h3><p>${e.message}</p>"
        }
    }

    fun parsePpt(inputStream: InputStream): String {
        return "<html><body><h3>Legacy .ppt Not Yet Supported</h3><p>Support for legacy PowerPoint files is under development.</p></body></html>"
    }
}