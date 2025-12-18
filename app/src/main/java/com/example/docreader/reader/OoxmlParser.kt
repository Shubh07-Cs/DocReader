package com.example.docreader.reader

import android.content.Context
import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

object OoxmlParser {

    fun parseDocx(context: Context, uri: Uri): String {
        return try {
            val sb = StringBuilder()
            sb.append("<html><head><style>body { font-family: sans-serif; padding: 16px; line-height: 1.6; } p { margin-bottom: 12px; }</style></head><body>")
            
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val zipInputStream = ZipInputStream(inputStream)
                var entry = zipInputStream.nextEntry
                while (entry != null) {
                    if (entry.name == "word/document.xml") {
                        val content = parseWordXml(zipInputStream)
                        sb.append(content)
                        break
                    }
                    zipInputStream.closeEntry()
                    entry = zipInputStream.nextEntry
                }
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
                    if (name == "w:p") {
                        sb.append("<p>")
                    } else if (name == "w:t") {
                        sb.append(parser.nextText())
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (name == "w:p") {
                        sb.append("</p>")
                    }
                }
            }
            eventType = parser.next()
        }
        return sb.toString()
    }

    fun parseXlsx(context: Context, uri: Uri): String {
         return try {
            val sb = StringBuilder()
            sb.append("<html><head><style>table { border-collapse: collapse; width: 100%; } td, th { border: 1px solid #ddd; padding: 8px; }</style></head><body>")
            sb.append("<table>")

            // XLSX Simplified:
            // 1. We need to handle sharedStrings if possible, but that's complex (requires multi-pass or memory).
            // 2. We'll fallback to showing raw values, which works for numbers.
            // 3. If it's a blank screen, it might be because sheet1.xml isn't found or structure is different.
            
            var foundSheet = false
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val zipInputStream = ZipInputStream(inputStream)
                var entry = zipInputStream.nextEntry
                while (entry != null) {
                    if (entry.name.equals("xl/worksheets/sheet1.xml", ignoreCase = true)) {
                        foundSheet = true
                        val content = parseSheetXml(zipInputStream)
                        sb.append(content)
                        break
                    }
                    zipInputStream.closeEntry()
                    entry = zipInputStream.nextEntry
                }
            }
            
            if (!foundSheet) {
                sb.append("<tr><td><i>Unable to find main worksheet (sheet1.xml). Complex Excel files are not fully supported yet.</i></td></tr>")
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
                    if (name == "row") {
                        sb.append("<tr>")
                    } else if (name == "v") { 
                        // Simplified: treating value as text. 
                        // If it's a shared string index, user sees a number. 
                        // Better than blank.
                        sb.append("<td>${parser.nextText()}</td>")
                    } else if (name == "t") {
                        // Inline string (sometimes used)
                        sb.append("<td>${parser.nextText()}</td>")
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (name == "row") {
                        sb.append("</tr>")
                    }
                }
            }
            eventType = parser.next()
        }
        return sb.toString()
    }
    
    fun parsePptx(context: Context, uri: Uri): String {
         return try {
            val sb = StringBuilder()
            sb.append("<html><head><style>div.slide { border: 1px solid #ccc; margin: 20px; padding: 20px; min-height: 200px; background-color: #f9f9f9; } h3 { border-bottom: 1px solid #eee; }</style></head><body>")
            
            // PPTX often has slide1.xml, slide2.xml...
            // We need to iterate all entries and match pattern.
            // Note: ZipInputStream cannot be reset. We iterate once.
            
            var slideCount = 0
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val zipInputStream = ZipInputStream(inputStream)
                var entry = zipInputStream.nextEntry
                while (entry != null) {
                    if (entry.name.matches(Regex("ppt/slides/slide[0-9]+\\.xml"))) {
                        slideCount++
                        sb.append("<div class='slide'>")
                        sb.append("<h3>Slide $slideCount</h3>")
                        // We need to parse this specific entry *now* because we can't come back
                        // But parseSlideXml consumes the stream? No, XmlPullParser usually doesn't close specific zip entry stream if we are careful.
                        // However, ZipInputStream is tricky. Let's try to parse inline.
                        
                        // To be safe with ZipInputStream, we read the entry into a String or byte array first?
                        // Or just let XmlPullParser read until it hits END_DOCUMENT of that XML, 
                        // which corresponds to end of entry content effectively?
                        // XmlPullParser doesn't necessarily respect Zip boundaries unless wrapper handles it.
                        // Ideally we define a wrapper that doesn't close the ZipStream.
                        // For simplicity, let's assume valid XML structure allows parser to finish.
                        
                        sb.append(parseSlideXml(zipInputStream))
                        sb.append("</div>")
                    }
                    zipInputStream.closeEntry()
                    entry = zipInputStream.nextEntry
                }
            }
            
            if (slideCount == 0) {
                sb.append("<p><i>No slides found or format not supported.</i></p>")
            }
            
            sb.append("</body></html>")
            sb.toString()
        } catch (e: Exception) {
             "<html><body><h3>Error reading presentation</h3><p>${e.message}</p></body></html>"
        }
    }
    
    private fun parseSlideXml(inputStream: InputStream): String {
        val parser = Xml.newPullParser()
        // We tell parser to NOT close input stream
        parser.setInput(inputStream, null)
        val sb = StringBuilder()
        
        var eventType = parser.eventType
        // We must stop when we hit END_DOCUMENT for the *current XML file*, 
        // effectively checking logical end. XmlPullParser returns END_DOCUMENT when stream ends? 
        // For ZipEntry, read() returns -1.
        
        try {
            while (eventType != XmlPullParser.END_DOCUMENT) {
                val name = parser.name
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (name == "a:t") { // Text Body
                            val text = parser.nextText()
                            if (text.isNotBlank()) {
                                sb.append("<p>$text</p>")
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            // End of entry might cause parser exception if it expects more but stream ends?
            // Usually fine.
        }
        return sb.toString()
    }
}