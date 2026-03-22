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
            val tempFile = java.io.File(context.cacheDir, "poi_temp.xls")
            tempFile.outputStream().use { fos ->
                inputStream.copyTo(fos)
            }
            val fs = org.apache.poi.poifs.filesystem.POIFSFileSystem(tempFile)
            val workbook = HSSFWorkbook(fs)
            val html = convertWorkbook(workbook)
            tempFile.delete()
            html
        } catch (t: Throwable) {
            errorHtml(t)
        }
    }

    fun convertXlsx(context: Context, inputStream: InputStream): String {
        return try {
            val tempFile = java.io.File(context.cacheDir, "poi_temp.xlsx")
            tempFile.outputStream().use { fos ->
                inputStream.copyTo(fos)
            }
            // Passing a File directly forces a Lazy ZipFile memory mapping, saving 100MB+ peak RAM!
            val workbook = XSSFWorkbook(tempFile)
            val html = convertWorkbook(workbook)
            tempFile.delete()
            html
        } catch (t: Throwable) {
            errorHtml(t)
        }
    }

    private fun convertWorkbook(workbook: Workbook): String {
        val sb = StringBuilder(10000)
        startHtml(sb)

        val tabIds = mutableListOf<String>()
        val tabNames = mutableListOf<String>()

        sb.append("<div class='sheet-container'>")
        
        // Hard limit on global HTML generation density to save RAM buffer allocation on dense files
        var totalCellsGenerated = 0
        val GLOBAL_CELL_LIMIT = 40_000 

        for (i in 0 until workbook.numberOfSheets) {
            val sheet = workbook.getSheetAt(i)
            val sheetId = "sheet-$i"
            tabIds.add(sheetId)
            tabNames.add(esc(sheet.sheetName))
            
            val activeClass = if (i == 0) " active" else ""
            sb.append("<div id='$sheetId' class='sheet$activeClass'>")
            
            // 1. Process Merged Regions with ZERO memory allocation
            val mergedRegions = mutableListOf<Pair<IntArray, Pair<Int, Int>>>()

            for (m in 0 until sheet.numMergedRegions) {
                val region = sheet.getMergedRegion(m)
                mergedRegions.add(Pair(
                    intArrayOf(region.firstRow, region.lastRow, region.firstColumn, region.lastColumn),
                    Pair((region.lastRow - region.firstRow + 1), (region.lastColumn - region.firstColumn + 1))
                ))
            }

            sb.append("<table>")
            
            // Limit absolute max boundaries to prevent OutOfMemoryError for errant clicks (e.g., cell XFD1048576)
            val maxAllowedRows = 2000
            val maxAllowedCols = 100
            
            val firstRow = 0 // Anchor the visual grid to Row 1 natively
            val lastRow = minOf(sheet.lastRowNum, maxAllowedRows)
            var sheetMaxCol = 0
            
            // Pass 1: find absolute max column limit safely bounded
            for (r in firstRow..lastRow) {
                val rBound = sheet.getRow(r)?.lastCellNum?.toInt()?.minus(1) ?: 0
                if (rBound > sheetMaxCol) sheetMaxCol = rBound
            }
            sheetMaxCol = minOf(sheetMaxCol, maxAllowedCols)

            // Top Header Row (A, B, C...)
            sb.append("<tr><th class='row-header'></th>")
            for (c in 0..sheetMaxCol) {
                sb.append("<th class='col-header'>${getColumnName(c)}</th>")
            }
            sb.append("</tr>")

            // Pass 2: generate grid
            for (r in firstRow..lastRow) {
                val row = sheet.getRow(r)
                sb.append("<tr>")
                
                // Left Row Header (1, 2, 3...)
                sb.append("<th class='row-header'>${r + 1}</th>")
                
                val limit = row?.lastCellNum?.toInt()?.minus(1)?.coerceAtLeast(0) ?: 0
                val targetCols = minOf(limit.coerceAtLeast(sheetMaxCol), maxAllowedCols)

                for (c in 0..targetCols) {
                    if (totalCellsGenerated >= GLOBAL_CELL_LIMIT) {
                        break // Short-circuit row density injection
                    }
                    totalCellsGenerated++
                    
                    var attrs = ""
                    var skip = false
                    
                    for (region in mergedRegions) {
                        val bounds = region.first
                        if (r >= bounds[0] && r <= bounds[1] && c >= bounds[2] && c <= bounds[3]) {
                            if (r == bounds[0] && c == bounds[2]) {
                                val spans = region.second
                                if (spans.first > 1) {
                                     val safeRowSpan = minOf(spans.first, lastRow - r + 1)
                                     attrs += " rowspan='$safeRowSpan'"
                                }
                                if (spans.second > 1) {
                                     val safeColSpan = minOf(spans.second, targetCols - c + 1)
                                     attrs += " colspan='$safeColSpan'"
                                }
                            } else {
                                skip = true
                            }
                            break
                        }
                    }
                    if (skip) continue
                    
                    var style = ""
                    val cell = row?.getCell(c)
                    val isXlsx = workbook !is org.apache.poi.hssf.usermodel.HSSFWorkbook
                    
                    cell?.let {
                        val align = when (it.cellStyle.alignment) {
                            org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER -> "center"
                            org.apache.poi.ss.usermodel.HorizontalAlignment.RIGHT -> "right"
                            else -> ""
                        }
                        if (align.isNotEmpty()) style += "text-align:$align;"
                        
                        if (isXlsx) {
                            try {
                                val font = workbook.getFontAt(it.cellStyle.fontIndexAsInt)
                                if (font.bold) style += "font-weight:bold;"
                                if (font.italic) style += "font-style:italic;"
                            } catch (t: Throwable) {}
                            
                            try {
                                val colorObj = it.cellStyle.fillForegroundColorColor
                                if (colorObj != null) {
                                    val m = colorObj.javaClass.getMethod("getARGBHex")
                                    val hex = m.invoke(colorObj) as? String
                                    if (hex != null && hex.length >= 6) {
                                        val cssHex = if (hex.length == 8) "#" + hex.substring(2) else "#$hex"
                                        if (cssHex != "#000000" && cssHex != "#FFFFFF") {
                                            style += "background-color:$cssHex;"
                                        }
                                    }
                                }
                            } catch (t: Throwable) {}
                        }
                    }
                    
                    val content = cell?.let { getCellValue(it) } ?: ""
                    val formattedContent = esc(content).replace("\n", "<br>")
                    sb.append("<td$attrs style='$style'>$formattedContent</td>")
                }
                sb.append("</tr>")
            }
            sb.append("</table>")
            sb.append("</div>")
        }

        endHtml(sb, tabIds, tabNames)
        return sb.toString()
    }

    private fun getCellValue(cell: Cell): String {
        return try {
            when (cell.cellType) {
                CellType.STRING -> cell.stringCellValue
                CellType.NUMERIC -> {
                    if (DateUtil.isCellDateFormatted(cell)) {
                        val rawValue = cell.numericCellValue
                        val format = when {
                            rawValue < 1.0 -> SimpleDateFormat("h:mm a", Locale.getDefault())
                            rawValue % 1.0 != 0.0 -> SimpleDateFormat("dd/MM/yyyy h:mm a", Locale.getDefault())
                            else -> SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                        }
                        format.format(cell.dateCellValue)
                    } else {
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
        } catch (t: Throwable) {
            ""
        }
    }

    private fun startHtml(sb: StringBuilder) {
        sb.append("<html><head>")
        // Allow native zooming down to 0.1 by eliminating device-width and hard flex-locks
        sb.append("<meta name=\"viewport\" content=\"initial-scale=1.0, minimum-scale=0.1, maximum-scale=5.0, user-scalable=yes\">")
        sb.append("<style>")
        sb.append("html { -webkit-text-size-adjust: none; text-size-adjust: none; }")
        sb.append("body { font-family: 'Segoe UI', Roboto, sans-serif; padding: 0; margin: 0; background: #fff; }")
        sb.append(".sheet-container { padding-bottom: 54px; display: inline-block; min-width: 100%; }")
        sb.append(".sheet { display: none; margin: 0; padding: 0; }")
        sb.append(".sheet.active { display: block; }")
        sb.append("table { border-collapse: collapse; white-space: nowrap; font-size: 13px; }")
        sb.append("td, th { border: 1px solid #d0d0d0; padding: 4px 8px; min-width: 60px; height: 24px; vertical-align: bottom; }")
        sb.append(".row-header { background: #e6e6e6; color: #333; font-weight: 500; text-align: center; border: 1px solid #ccc; min-width: 40px; position: sticky; left: 0; z-index: 1; }")
        sb.append(".col-header { background: #e6e6e6; color: #333; font-weight: 500; text-align: center; border: 1px solid #ccc; position: sticky; top: 0; z-index: 2; height: 24px; }")
        sb.append("tr:first-child th:first-child { z-index: 3; }")
        sb.append(".tab-bar { position: fixed; bottom: 0; left: 0; right: 0; height: 48px; background: #f1f1f1; border-top: 1px solid #ccc; display: flex; overflow-x: auto; white-space: nowrap; align-items: center; z-index: 9999; }")
        sb.append(".tab-button { padding: 0 24px; height: 100%; border: none; background: transparent; font-size: 14px; color: #555; cursor: pointer; border-right: 1px solid #d0d0d0; }")
        sb.append(".tab-button.active { background: #fff; color: #217346; font-weight: bold; border-top: 3px solid #217346; border-bottom: none; }")
        sb.append("</style>")
        sb.append("<script>")
        sb.append("function switchTab(sheetId, btn) {")
        sb.append("  var sheets = document.getElementsByClassName('sheet');")
        sb.append("  for(var i=0; i<sheets.length; i++) sheets[i].className = 'sheet';")
        sb.append("  document.getElementById(sheetId).className = 'sheet active';")
        sb.append("  var btns = document.getElementsByClassName('tab-button');")
        sb.append("  for(var i=0; i<btns.length; i++) btns[i].className = 'tab-button';")
        sb.append("  btn.className = 'tab-button active';")
        sb.append("}")
        sb.append("</script>")
        sb.append("</head><body>")
    }

    private fun endHtml(sb: StringBuilder, tabIds: List<String>, tabNames: List<String>) {
        sb.append("</div>")
        if (tabIds.size > 1) {
            sb.append("<div class='tab-bar'>")
            for (i in tabIds.indices) {
                val activeClass = if (i == 0) " active" else ""
                val id = tabIds[i]
                val name = tabNames[i]
                sb.append("<button class='tab-button$activeClass' onclick=\"switchTab('$id', this)\">$name</button>")
            }
            sb.append("</div>")
        }
        sb.append("</body></html>")
    }

    private fun errorHtml(t: Throwable): String {
        return "<html><body style='padding:24px;font-family:sans-serif'><h3>Error reading spreadsheet</h3><p>${t.message}</p></body></html>"
    }

    private fun esc(s: String?): String {
        return (s ?: "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }

    private fun getColumnName(index: Int): String {
        var n = index
        val name = java.lang.StringBuilder()
        while (n >= 0) {
            name.insert(0, ('A' + (n % 26)).toChar())
            n = (n / 26) - 1
        }
        return name.toString()
    }
}
