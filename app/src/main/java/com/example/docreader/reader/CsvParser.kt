package com.example.docreader.reader

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

object CsvParser {

    fun parse(inputStream: InputStream): String {
        val sb = StringBuilder()
        sb.append("<html><head><style>")
        sb.append("table { border-collapse: collapse; width: 100%; font-family: Arial, sans-serif; }")
        sb.append("th, td { border: 1px solid #ddd; padding: 8px; text-align: left; }")
        sb.append("tr:nth-child(even) { background-color: #f2f2f2; }")
        sb.append("th { background-color: #4CAF50; color: white; position: sticky; top: 0; }")
        sb.append("</style></head><body>")
        sb.append("<table>")

        try {
            val reader = BufferedReader(InputStreamReader(inputStream))
            var line = reader.readLine()
            var isHeader = true

            while (line != null) {
                // Basic CSV parsing regex (handles quoted values)
                val tokens = line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)".toRegex())
                
                sb.append("<tr>")
                for (token in tokens) {
                    val cleanToken = token.trim().removeSurrounding("\"")
                    if (isHeader) {
                        sb.append("<th>$cleanToken</th>")
                    } else {
                        sb.append("<td>$cleanToken</td>")
                    }
                }
                sb.append("</tr>")
                
                isHeader = false
                line = reader.readLine()
            }
        } catch (e: Exception) {
            sb.append("<tr><td colspan='100%'>Error reading CSV: ${e.message}</td></tr>")
        }

        sb.append("</table></body></html>")
        return sb.toString()
    }
}
