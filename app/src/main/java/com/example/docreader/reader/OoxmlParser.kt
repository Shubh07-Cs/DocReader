package com.example.docreader.reader

import android.content.Context
import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.TreeMap
import java.util.regex.Pattern
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

object OoxmlParser {

    // --- Unzip Logic ---
    fun unzip(context: Context, uri: Uri): File? {
        val cacheDir = File(context.cacheDir, "ooxml_cache_${System.currentTimeMillis()}")
        if (cacheDir.exists()) cacheDir.deleteRecursively()
        cacheDir.mkdirs()

        return try {
            var hasEntries = false
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val zipInputStream = ZipInputStream(inputStream)
                var entry = zipInputStream.nextEntry
                while (entry != null) {
                    hasEntries = true
                    val entryFile = File(cacheDir, entry.name)
                    if (!entryFile.canonicalPath.startsWith(cacheDir.canonicalPath)) {
                        throw SecurityException("Invalid Zip Entry")
                    }
                    
                    if (entry.isDirectory) {
                        entryFile.mkdirs()
                    } else {
                        entryFile.parentFile?.mkdirs()
                        FileOutputStream(entryFile).use { output ->
                            zipInputStream.copyTo(output)
                        }
                    }
                    zipInputStream.closeEntry()
                    entry = zipInputStream.nextEntry
                }
            }
            // If no entries were found, it's likely not a ZIP file (e.g., binary .xls)
            if (!hasEntries) {
                // Return null to indicate unzip failure/not a zip
                return null
            }
            cacheDir
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // --- Parsers working on File ---

    fun parseDocx(rootDir: File): String {
        return try {
            val sb = StringBuilder()
            sb.append("<html><head><style>body { font-family: sans-serif; padding: 16px; line-height: 1.6; } p { margin-bottom: 12px; }</style></head><body>")
            
            val docFile = File(rootDir, "word/document.xml")
            if (docFile.exists()) {
                FileInputStream(docFile).use { inputStream ->
                    sb.append(parseWordXml(inputStream))
                }
            } else {
                 sb.append("<p><i>Could not find document content.</i></p>")
            }
            
            sb.append("</body></html>")
            sb.toString()
        } catch (e: Exception) {
            "<html><body><h3>Error reading document</h3><p>${e.message}</p></body></html>"
        }
    }
    
    private fun parseWordXml(inputStream: InputStream): String {
        val parser = Xml.newPullParser()
        parser.setInput(inputStream, null)
        val sb = StringBuilder()
        
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            val name = parser.name
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    if (name != null && (name == "p" || name.endsWith(":p"))) {
                        sb.append("<p>")
                    } else if (name != null && (name == "t" || name.endsWith(":t"))) {
                        sb.append(parser.nextText())
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (name != null && (name == "p" || name.endsWith(":p"))) {
                        sb.append("</p>")
                    }
                }
            }
            eventType = parser.next()
        }
        return sb.toString()
    }

    fun parseXlsx(rootDir: File): String {
         return try {
            val sb = StringBuilder()
            sb.append("<html><head><style>table { border-collapse: collapse; width: 100%; } td, th { border: 1px solid #ddd; padding: 8px; }</style></head><body>")
            sb.append("<table>")

            val worksheetsDir = File(rootDir, "xl/worksheets")
            if (worksheetsDir.exists() && worksheetsDir.isDirectory) {
                val sheetFiles = worksheetsDir.listFiles { _, name -> 
                     name.startsWith("sheet") && name.endsWith(".xml")
                }
                
                if (sheetFiles != null && sheetFiles.isNotEmpty()) {
                    val firstSheet = sheetFiles.first()
                    sb.append("<tr><td colspan='100%' style='background:#f0f0f0'><b>Sheet: ${firstSheet.name}</b></td></tr>")
                    FileInputStream(firstSheet).use { inputStream ->
                        sb.append(parseSheetXml(inputStream))
                    }
                } else {
                    sb.append("<tr><td><i>No worksheets found.</i></td></tr>")
                }
            } else {
                 // Check if it was a valid zip but missing XLSX structure, or just garbage.
                 // But typically if we get here, it's a zip. 
                 // If unzip returned null (non-zip), OfficeReaderEngine handles it separately.
                 sb.append("<tr><td><i>Invalid XLSX structure. This file might be corrupted or encrypted.</i></td></tr>")
            }
            
            sb.append("</table></body></html>")
            sb.toString()
        } catch (e: Exception) {
             "<html><body><h3>Error reading spreadsheet</h3><p>${e.message}</p></body></html>"
        }
    }

    private fun parseSheetXml(inputStream: InputStream): String {
        val parser = Xml.newPullParser()
        parser.setInput(inputStream, null)
        val sb = StringBuilder()
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            val name = parser.name
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    if (name == "row") sb.append("<tr>")
                    else if (name == "v" || name == "t") sb.append("<td>${parser.nextText()}</td>")
                }
                XmlPullParser.END_TAG -> {
                    if (name == "row") sb.append("</tr>")
                }
            }
            eventType = parser.next()
        }
        return sb.toString()
    }

    fun parsePptx(rootDir: File): String {
         return try {
            val sb = StringBuilder()
            sb.append("<html><head><style>div.slide { border: 1px solid #ccc; margin: 20px; padding: 20px; min-height: 200px; background-color: #f9f9f9; position: relative; } h3 { border-bottom: 1px solid #eee; } p { margin: 5px 0; } img { max-width: 100%; height: auto; display: block; margin: 10px 0; }</style></head><body>")
            
            val slidesDir = File(rootDir, "ppt/slides")
            val relsDir = File(rootDir, "ppt/slides/_rels")
            val mediaDir = File(rootDir, "ppt/media")
            
            val slidesMap = TreeMap<Int, String>()
            
            if (slidesDir.exists() && slidesDir.isDirectory) {
                val slideFiles = slidesDir.listFiles { _, name -> 
                     name.startsWith("slide") && name.endsWith(".xml")
                }
                
                slideFiles?.forEach { slideFile ->
                    val name = slideFile.name // slide1.xml
                    val number = name.replace("slide", "").replace(".xml", "").toIntOrNull() ?: 0
                    if (number > 0) {
                        val relsFile = File(relsDir, "${slideFile.name}.rels")
                        val imageMap = parseRelsForImages(relsFile) 
                        
                        FileInputStream(slideFile).use { inputStream ->
                            val content = parseSlideXml(inputStream, imageMap)
                            slidesMap[number] = content
                        }
                    }
                }
            }
            
            if (slidesMap.isEmpty()) {
                sb.append("<p><i>No slides found.</i></p>")
            } else {
                for ((num, content) in slidesMap) {
                    sb.append("<div class='slide'>")
                    sb.append("<h3>Slide $num</h3>")
                    sb.append(content)
                    sb.append("</div>")
                }
            }
            sb.append("</body></html>")
            sb.toString()
        } catch (e: Exception) {
             "<html><body><h3>Error reading presentation</h3><p>${e.message}</p></body></html>"
        }
    }
    
    private fun parseRelsForImages(relsFile: File): Map<String, String> {
        val map = mutableMapOf<String, String>()
        if (!relsFile.exists()) return map
        
        try {
            FileInputStream(relsFile).use { inputStream ->
                val parser = Xml.newPullParser()
                parser.setInput(inputStream, null)
                var eventType = parser.eventType
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    if (eventType == XmlPullParser.START_TAG && parser.name.endsWith("Relationship")) {
                        val id = parser.getAttributeValue(null, "Id")
                        val type = parser.getAttributeValue(null, "Type")
                        val target = parser.getAttributeValue(null, "Target")
                        
                        if (type != null && type.contains("image") && target != null) {
                            val fileName = target.substringAfterLast("/")
                            map[id] = fileName
                        }
                    }
                    eventType = parser.next()
                }
            }
        } catch (e: Exception) { }
        return map
    }
    
    private fun parseSlideXml(inputStream: InputStream, imageMap: Map<String, String>): String {
        val parser = Xml.newPullParser()
        parser.setInput(inputStream, null)
        val sb = StringBuilder()
        var eventType = parser.eventType
        
        try {
            while (eventType != XmlPullParser.END_DOCUMENT) {
                val name = parser.name
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (name != null) {
                            if (name == "t" || name.endsWith(":t")) {
                                val text = parser.nextText()
                                if (text.isNotBlank()) sb.append("<p>$text</p>")
                            } else if (name == "blip" || name.endsWith(":blip")) {
                                var embedId: String? = null
                                for (i in 0 until parser.attributeCount) {
                                    val attrName = parser.getAttributeName(i)
                                    if (attrName.contains("embed")) {
                                        embedId = parser.getAttributeValue(i)
                                        break
                                    }
                                }
                                
                                if (embedId != null) {
                                    val imageName = imageMap[embedId]
                                    if (imageName != null) {
                                        sb.append("<img src='ppt/media/$imageName' />")
                                    }
                                }
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) { }
        return sb.toString()
    }
}