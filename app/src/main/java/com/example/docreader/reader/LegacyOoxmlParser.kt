package com.example.docreader.reader

import android.content.Context
import android.net.Uri
import org.apache.poi.hslf.usermodel.HSLFSlideShow
import org.apache.poi.hslf.usermodel.HSLFTextShape
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
            
            val formatter = DataFormatter()

            for (i in 0 until wb.numberOfSheets) {
                val sheet = wb.getSheetAt(i)
                sb.append("<h3>${sheet.sheetName}</h3>")
                sb.append("<table>")
                sheet.forEach { row ->
                    sb.append("<tr>")
                    row.forEach { cell ->
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
        return try {
            val ppt = HSLFSlideShow(inputStream)
            val sb = StringBuilder("<html><head><style>div.slide { border: 1px solid #ccc; margin: 20px; padding: 20px; min-height: 200px; background-color: #f9f9f9; } h3 { border-bottom: 1px solid #eee; } p { margin: 5px 0; }</style></head><body>")

            val slides = ppt.slides
            if (slides.isEmpty()) {
                sb.append("<p>No slides found in this presentation.</p>")
            } else {
                for ((index, slide) in slides.withIndex()) {
                    sb.append("<div class='slide'>")
                    sb.append("<h3>Slide ${index + 1}</h3>")
                    
                    // New robust method: Iterate through shapes
                    val shapes = slide.shapes
                    if (shapes != null) {
                        for (shape in shapes) {
                            if (shape is HSLFTextShape) {
                                val text = shape.text
                                if (text != null && text.isNotBlank()) {
                                    sb.append("<p>${text.replace("\n", "<br/>")}</p>")
                                }
                            }
                        }
                    }
                    sb.append("</div>")
                }
            }
            
            sb.append("</body></html>")
            sb.toString()
        } catch (e: Exception) {
            e.printStackTrace()
            "<h3>Error reading legacy .ppt file</h3><p>${e.message}</p>"
        }
    }
}