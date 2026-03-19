package com.example.docreader.reader

import android.content.Context
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.BufferedInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale

object ExcelToHtmlConverter {

    fun convertXls(context: Context, inputStream: InputStream): String {
        return try {
            // HSSFWorkbook requires a mark/reset-capable stream to parse the OLE2
            // container header. A plain FileInputStream/ContentResolver stream does
            // NOT support mark/reset, causing a silent crash. BufferedInputStream fixes this.
            val buffered = if (inputStream.markSupported()) inputStream
                           else BufferedInputStream(inputStream)
            val workbook = HSSFWorkbook(buffered)
            convertWorkbook(workbook)
        } catch (e: Exception) {
            errorHtml(e)
        }
    }

    fun convertXlsx(context: Context, inputStream: InputStream): String {
        return try {
            val workbook = XSSFWorkbook(inputStream)
            convertWorkbook(workbook)
        } catch (e: Exception) {
            errorHtml(e)
        }
    }

    private fun convertWorkbook(workbook: Workbook): String {
        val sb = StringBuilder()
        startHtml(sb)

        // Iterate through all sheets
        for (i in 0 until workbook.numberOfSheets) {
            val sheet = workbook.getSheetAt(i)
            sb.append("<div class='sheet'>")
            sb.append("<h3>${sheet.sheetName}</h3>")
            sb.append("<table>")

            for (row in sheet) {
                sb.append("<tr>")
                for (cell in row) {
                    val style = StringBuilder()
                    // Basic styling
                    // if (cell.cellStyle.fillForegroundColorColor != null) ... (Complexity skipped for now)
                    
                    sb.append("<td>")
                    sb.append(getCellValue(cell))
                    sb.append("</td>")
                }
                sb.append("</tr>")
            }
            sb.append("</table>")
            sb.append("</div>")
        }

        endHtml(sb)
        return sb.toString()
    }

    private fun getCellValue(cell: Cell): String {
        return try {
            when (cell.cellType) {
                CellType.STRING -> cell.stringCellValue
                CellType.NUMERIC -> {
                    if (DateUtil.isCellDateFormatted(cell)) {
                        val rawValue = cell.numericCellValue
                        // In Excel, times are stored as fractions of a day (0.0–1.0).
                        // A value with no integer part (< 1) is a pure time with no date.
                        // Formatting it as a date yields "31/12/1899" (Excel's epoch origin).
                        val format = when {
                            rawValue < 1.0 -> SimpleDateFormat("h:mm a", Locale.getDefault())
                            rawValue % 1.0 != 0.0 -> SimpleDateFormat("dd/MM/yyyy h:mm a", Locale.getDefault())
                            else -> SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                        }
                        format.format(cell.dateCellValue)
                    } else {
                        // Avoid scientific notation for integers
                        val value = cell.numericCellValue
                        if (value == value.toLong().toDouble()) {
                            value.toLong().toString()
                        } else {
                            value.toString()
                        }
                    }
                }
                CellType.BOOLEAN -> cell.booleanCellValue.toString()
                CellType.FORMULA -> {
                    try {
                        cell.richStringCellValue.string
                    } catch (e: Exception) {
                        try {
                             cell.numericCellValue.toString()
                        } catch (e2: Exception) {
                            cell.cellFormula
                        }
                    }
                }
                else -> ""
            }
        } catch (e: Exception) {
            ""
        }
    }

    private fun startHtml(sb: StringBuilder) {
        sb.append("<html><head><style>")
        sb.append("body { font-family: sans-serif; padding: 16px; line-height: 1.5; color: #333; }")
        sb.append("h3 { background: #f0f0f0; padding: 8px; border-bottom: 2px solid #ccc; margin-top: 24px; }")
        sb.append("table { border-collapse: collapse; width: 100%; margin-bottom: 16px; font-size: 14px; }")
        sb.append("td, th { border: 1px solid #ddd; padding: 6px; min-width: 50px; }")
        sb.append("tr:nth-child(even) { background-color: #f9f9f9; }")
        sb.append("</style></head><body>")
    }

    private fun endHtml(sb: StringBuilder) {
        sb.append("</body></html>")
    }

    private fun errorHtml(e: Exception): String {
        return "<html><body><h3>Error reading spreadsheet</h3><p>${e.message}</p></body></html>"
    }
}
