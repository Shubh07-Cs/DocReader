package com.example.docreader.reader

import android.content.Context
import android.util.Base64
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.TreeMap

/**
 * Industrial-standard PowerPoint converter.
 *
 * .pptx  – Enhanced OOXML parser: absolute positioning, text styling, shape fills, images.
 * .ppt   – Apache POI HSLF with graceful fallback for Android (no java.awt).
 */
object PptToHtmlConverter {

    private const val EMU_PER_PX = 9525.0   // 914400 EMU/inch ÷ 96 px/inch
    private const val RENDER_W = 960.0       // Fixed viewport width in pixels

    // ── Internal models ─────────────────────────────────────────────────────

    private data class Shape(
        var x: Double = 0.0, var y: Double = 0.0,
        var w: Double = 0.0, var h: Double = 0.0,
        val paragraphs: MutableList<Para> = mutableListOf(),
        var fill: String? = null, var imgPath: String? = null
    )

    private data class Para(
        val runs: MutableList<Run> = mutableListOf(), var align: String? = null
    )

    private data class Run(
        var text: String = "", var bold: Boolean = false, var italic: Boolean = false,
        var underline: Boolean = false, var fontSize: Double? = null, var color: String? = null
    )

    // ══════════════════════════════════════════════════════════════════════════
    //  PUBLIC: .pptx (uses raw XML parsing — works on Android, no java.awt)
    // ══════════════════════════════════════════════════════════════════════════

    fun convertPptx(rootDir: File): String { return try {
        val (ew, eh) = slideDimensions(rootDir)
        val slideW = ew / EMU_PER_PX
        val slideH = eh / EMU_PER_PX

        val slidesDir = File(rootDir, "ppt/slides")
        val relsDir = File(rootDir, "ppt/slides/_rels")
        if (!slidesDir.exists()) return errHtml("No slides directory found.")

        val files = slidesDir.listFiles { _, n -> n.startsWith("slide") && n.endsWith(".xml") }
            ?: return errHtml("No slide files found.")

        val ordered = TreeMap<Int, List<Shape>>()
        for (f in files) {
            val num = f.nameWithoutExtension.removePrefix("slide").toIntOrNull() ?: continue
            val rels = File(relsDir, "${f.name}.rels")
            ordered[num] = parseSlide(f, parseRels(rels))
        }

        val sb = StringBuilder(header(slideW.toInt()))
        for ((num, shapes) in ordered) {
            sb.append("<div class='lbl'>Slide $num</div>")
            sb.append("<div class='slide' style='width:${slideW.toInt()}px;height:${slideH.toInt()}px;'>")
            for (s in shapes) {
                val sx = s.x.toInt()
                val sy = s.y.toInt()
                val sw = s.w.toInt()
                val sh = s.h.toInt()
                val bg = s.fill?.let { "background:$it;" } ?: ""
                
                val posCss = if (sw > 0 && sh > 0) {
                    "position:absolute; left:${sx}px; top:${sy}px; width:${sw}px; height:${sh}px;"
                } else {
                    "position:relative; width:90%; margin: 16px auto; min-height: 48px;"
                }
                
                sb.append("<div class='sh' style='$posCss$bg'>")
                if (s.imgPath != null) sb.append("<img src='${s.imgPath}'/>")
                for (p in s.paragraphs) {
                    val al = when (p.align) { "ctr" -> "text-align:center;"; "r" -> "text-align:right;"; "just" -> "text-align:justify;"; else -> "" }
                    sb.append("<p style='$al'>")
                    for (r in p.runs) if (r.text.isNotEmpty()) sb.append("<span style='${runCss(r)}'>${esc(r.text)}</span>")
                    sb.append("</p>")
                }
                sb.append("</div>")
            }
            sb.append("</div>")
        }
        sb.append("</body></html>"); sb.toString()
    } catch (e: Exception) { errHtml("Error reading PPTX: ${e.message}") } }

    // ══════════════════════════════════════════════════════════════════════════
    //  PUBLIC: .ppt (Apache POI HSLF — text + images, no positioning on Android)
    // ══════════════════════════════════════════════════════════════════════════

    private fun getReflectObj(obj: Any?, method: String): Any? {
        if (obj == null) return null
        return try { obj.javaClass.getMethod(method).invoke(obj) } catch (_: Throwable) { null }
    }

    private fun getReflectDouble(obj: Any?, method: String, field: String): Double {
        if (obj == null) return 0.0
        try { return (obj.javaClass.getMethod(method).invoke(obj) as Number).toDouble() } catch (_: Throwable) {}
        try { return (obj.javaClass.getField(field).get(obj) as Number).toDouble() } catch (_: Throwable) {}
        return 0.0
    }

    private fun getReflectInt(obj: Any?, method: String, field: String): Int {
        if (obj == null) return 0
        try { return (obj.javaClass.getMethod(method).invoke(obj) as Number).toInt() } catch (_: Throwable) {}
        try { return (obj.javaClass.getField(field).get(obj) as Number).toInt() } catch (_: Throwable) {}
        return 0
    }

    private fun extractRgb(colorObj: Any?): String? {
        if (colorObj == null) return null
        var actual = colorObj
        if (actual.javaClass.name.contains("Paint")) {
            actual = getReflectObj(actual, "getSolidColor") ?: actual
        }
        val c = getReflectObj(actual, "getColor") ?: actual
        val r = getReflectInt(c, "getRed", "red")
        val g = getReflectInt(c, "getGreen", "green")
        val b = getReflectInt(c, "getBlue", "blue")
        return String.format("#%02x%02x%02x", r, g, b)
    }

    fun convertPpt(context: Context, inputStream: InputStream): String { return try {
        val buf = if (inputStream.markSupported()) inputStream else BufferedInputStream(inputStream)
        val show = org.apache.poi.hslf.usermodel.HSLFSlideShow(buf)
        val pageSizeObj = getReflectObj(show, "getPageSize")
        // Default to a vertical scrolling layout if dimensions unavailable
        val slideW = getReflectDouble(pageSizeObj, "getWidth", "width").takeIf { it > 0 } ?: 960.0
        val slideH = getReflectDouble(pageSizeObj, "getHeight", "height").takeIf { it > 0 } ?: 720.0
        
        val sb = StringBuilder(header(slideW.toInt()))
        var sNum = 1
        for (slide in show.slides) {
            sb.append("<div class='lbl'>Slide ${sNum++}</div>")
            
            var bgCol = "background:#ffffff;"
            try {
                val bgObj = getReflectObj(slide, "getBackground")
                val fillObj = getReflectObj(bgObj, "getFill")
                val fgColor = getReflectObj(fillObj, "getForegroundColor")
                if (fgColor != null) {
                    val hex = extractRgb(fgColor)
                    if (hex != null) bgCol = "background:$hex;"
                }
            } catch (_: Throwable) {}
            
            sb.append("<div class='slide' style='width:${slideW.toInt()}px;height:${slideH.toInt()}px;$bgCol'>")
            
            try {
                val master = getReflectObj(slide, "getMasterSheet") as? Iterable<*>
                if (master != null) {
                    val shapesObj = getReflectObj(master, "getShapes") as? Iterable<*>
                    val shapeList = shapesObj ?: master
                    for (shape in shapeList) {
                        try {
                            if (shape is org.apache.poi.hslf.usermodel.HSLFShape) renderHslfShape(shape, sb, true)
                        } catch (_: Throwable) {}
                    }
                }
            } catch (_: Throwable) {}
            
            var shapesOk = false
            try {
                for (shape in slide.shapes) {
                    try {
                        renderHslfShape(shape, sb, false)
                    } catch (_: Throwable) {}
                }
                shapesOk = true
            } catch (_: Throwable) {}

            if (!shapesOk) {
                try {
                    val textParasObj = getReflectObj(slide, "getTextParagraphs") as? Iterable<*>
                    if (textParasObj != null) {
                        for (paraList in textParasObj) {
                            if (paraList is Iterable<*>) {
                                for (paraObj in paraList) {
                                    val runsObj = getReflectObj(paraObj, "getTextRuns") as? Iterable<*> ?: continue
                                    sb.append("<p style='padding:4px'>")
                                    for (run in runsObj) {
                                        if (run is org.apache.poi.hslf.usermodel.HSLFTextRun) {
                                            val text = esc((run.rawText ?: "").replace("\r", ""))
                                            sb.append("<span style='font-size:16pt;color:#000;'>$text</span>")
                                        }
                                    }
                                    sb.append("</p>")
                                }
                            }
                        }
                    }
                } catch (_: Throwable) {}
            }
            
            sb.append("</div>")
        }
        show.close()
        sb.append("</body></html>"); sb.toString()
    } catch (e: Throwable) { errHtml("Cannot read legacy .ppt: ${e.message}") } }

    private fun renderHslfShape(shape: org.apache.poi.hslf.usermodel.HSLFShape, sb: StringBuilder, isMaster: Boolean) {
        val anchorObj = getReflectObj(shape, "getAnchor")
        val sx = getReflectDouble(anchorObj, "getX", "x").toInt()
        val sy = getReflectDouble(anchorObj, "getY", "y").toInt()
        val sw = getReflectDouble(anchorObj, "getWidth", "width").toInt()
        val sh = getReflectDouble(anchorObj, "getHeight", "height").toInt()
        
        val hasBounds = sw > 0 && sh > 0

        // If it's a Master shape and we have no bounds, it would stretch incorrectly, so skip
        if (isMaster && !hasBounds) return

        var bgCss = ""
        if (shape is org.apache.poi.hslf.usermodel.HSLFSimpleShape) {
            val fillColObj = getReflectObj(shape, "getFillColor")
            if (fillColObj != null) {
                var a = getReflectInt(fillColObj, "getAlpha", "alpha")
                if (a == 0) a = 255
                val r = getReflectInt(fillColObj, "getRed", "red")
                val g = getReflectInt(fillColObj, "getGreen", "green")
                val b = getReflectInt(fillColObj, "getBlue", "blue")
                bgCss = String.format("background:rgba(%d,%d,%d,%.2f);", r, g, b, a / 255f)
            }
        }

        var isPicture = false
        var isText = false
        if (shape is org.apache.poi.hslf.usermodel.HSLFTextShape) isText = true
        if (shape is org.apache.poi.hslf.usermodel.HSLFPictureShape) isPicture = true

        // Don't render invisible boundless shapes to avoid messing up stacking
        if (!hasBounds && !isText && !isPicture) return

        val posCss = if (hasBounds) {
            "position:absolute; left:${sx}px; top:${sy}px; width:${sw}px; height:${sh}px;"
        } else {
            "position:relative; width:90%; margin: 16px auto; min-height: 40px;"
        }

        sb.append("<div class='sh' style='$posCss$bgCss'>")

        if (shape is org.apache.poi.hslf.usermodel.HSLFTextShape) {
            try {
                for (para in shape.textParagraphs) {
                    val align = when (para.textAlign?.name) {
                        "CENTER" -> "center"
                        "RIGHT" -> "right"
                        "JUSTIFY" -> "justify"
                        else -> "left"
                    }
                    sb.append("<p style='text-align:$align'>")
                    for (run in para.textRuns) {
                        try {
                            val text = esc((run.rawText ?: "").replace("\r", ""))
                            if (text.isEmpty()) continue
                            val size = (run.fontSize ?: 18.0) * 0.90
                            var css = "font-size:${size}pt;"
                            if (run.isBold) css += "font-weight:bold;"
                            if (run.isItalic) css += "font-style:italic;"
                            if (run.isUnderlined) css += "text-decoration:underline;"
                            val colorObj = getReflectObj(run, "getFontColor")
                            val hex = extractRgb(colorObj)
                            if (hex != null) css += "color:$hex;"
                            sb.append("<span style='$css'>$text</span>")
                        } catch (e: Throwable) {}
                    }
                    sb.append("</p>")
                }
            } catch (e: Throwable) {}
        } else if (shape is org.apache.poi.hslf.usermodel.HSLFPictureShape) {
            try {
                val pd = shape.pictureData
                if (pd != null) {
                    val base64 = android.util.Base64.encodeToString(pd.data, android.util.Base64.NO_WRAP)
                    sb.append("<img src='data:${pd.contentType};base64,$base64'/>")
                }
            } catch (e: Throwable) {}
        }
        sb.append("</div>")
    }

    // ── Slide dimensions from presentation.xml ──────────────────────────────

    private fun slideDimensions(root: File): Pair<Double, Double> {
        val presFile = File(root, "ppt/presentation.xml")
        if (!presFile.exists()) return Pair(12192000.0, 6858000.0) // default 16:9
        FileInputStream(presFile).use { stream ->
            val p = Xml.newPullParser(); p.setInput(stream, null)
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && local(p.name) == "sldSz") {
                    val cx = attr(p, "cx")?.toDoubleOrNull() ?: 12192000.0
                    val cy = attr(p, "cy")?.toDoubleOrNull() ?: 6858000.0
                    return Pair(cx, cy)
                }
                ev = p.next()
            }
        }
        return Pair(12192000.0, 6858000.0)
    }

    // ── Relationship parser (maps rId → image filename) ─────────────────────

    private fun parseRels(relsFile: File): Map<String, String> {
        val map = mutableMapOf<String, String>()
        if (!relsFile.exists()) return map
        try {
            FileInputStream(relsFile).use { s ->
                val p = Xml.newPullParser(); p.setInput(s, null)
                var ev = p.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG && p.name.endsWith("Relationship")) {
                        val id = p.getAttributeValue(null, "Id")
                        val type = p.getAttributeValue(null, "Type")
                        val target = p.getAttributeValue(null, "Target")
                        if (type?.contains("image") == true && target != null)
                            map[id] = target.substringAfterLast("/")
                    }
                    ev = p.next()
                }
            }
        } catch (_: Exception) {}
        return map
    }

    // ── Main slide XML parser ───────────────────────────────────────────────

    private fun parseSlide(file: File, imgMap: Map<String, String>): List<Shape> {
        val shapes = mutableListOf<Shape>()
        FileInputStream(file).use { stream ->
            val p = Xml.newPullParser(); p.setInput(stream, null)
            var cur: Shape? = null; var para: Para? = null; var run: Run? = null
            var inSpPr = false; var inTxBody = false; var inRPr = false
            var fillCtx = 0  // 0=none, 1=shapeFill, 2=runFill
            var ev = p.eventType

            while (ev != XmlPullParser.END_DOCUMENT) {
                val tag = local(p.name)
                when (ev) {
                    XmlPullParser.START_TAG -> when (tag) {
                        "sp", "pic" -> { cur = Shape() }
                        "spPr" -> inSpPr = true
                        "txBody" -> inTxBody = true
                        "off" -> if (inSpPr && cur != null) {
                            cur.x = (attr(p, "x")?.toLongOrNull() ?: 0L) / EMU_PER_PX
                            cur.y = (attr(p, "y")?.toLongOrNull() ?: 0L) / EMU_PER_PX
                        }
                        "ext" -> if (inSpPr && cur != null) {
                            cur.w = (attr(p, "cx")?.toLongOrNull() ?: 0L) / EMU_PER_PX
                            cur.h = (attr(p, "cy")?.toLongOrNull() ?: 0L) / EMU_PER_PX
                        }
                        "solidFill" -> fillCtx = if (inRPr) 2 else if (inSpPr && !inTxBody) 1 else 0
                        "srgbClr" -> {
                            val v = attr(p, "val")
                            if (v != null) when (fillCtx) {
                                1 -> cur?.fill = "#$v"
                                2 -> run?.color = "#$v"
                            }
                        }
                        "p" -> if (inTxBody) { para = Para() }
                        "pPr" -> if (inTxBody) para?.align = attr(p, "algn")
                        "r" -> if (inTxBody) { run = Run() }
                        "rPr" -> if (inTxBody && run != null) {
                            inRPr = true
                            run.bold = attr(p, "b") == "1"
                            run.italic = attr(p, "i") == "1"
                            run.underline = attr(p, "u") == "sng"
                            attr(p, "sz")?.toDoubleOrNull()?.let { run.fontSize = it / 100.0 }
                        }
                        "t" -> if (inTxBody && run != null) run.text = p.nextText()
                        "blip" -> if (cur != null) {
                            for (i in 0 until p.attributeCount)
                                if (p.getAttributeName(i).contains("embed")) {
                                    imgMap[p.getAttributeValue(i)]?.let { cur.imgPath = "ppt/media/$it" }
                                    break
                                }
                        }
                    }
                    XmlPullParser.END_TAG -> when (tag) {
                        "sp", "pic" -> { cur?.let { shapes.add(it) }; cur = null }
                        "spPr" -> inSpPr = false
                        "txBody" -> inTxBody = false
                        "solidFill" -> fillCtx = 0
                        "rPr" -> inRPr = false
                        "r" -> { run?.let { para?.runs?.add(it) }; run = null }
                        "p" -> if (inTxBody) { para?.let { cur?.paragraphs?.add(it) }; para = null }
                    }
                }
                ev = p.next()
            }
        }
        return shapes
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun local(name: String?): String = name?.substringAfterLast(":") ?: ""

    private fun attr(p: XmlPullParser, name: String): String? {
        for (i in 0 until p.attributeCount)
            if (p.getAttributeName(i).let { it == name || it.endsWith(":$name") })
                return p.getAttributeValue(i)
        return null
    }

    private fun runCss(r: Run): String {
        val sb = StringBuilder()
        if (r.bold) sb.append("font-weight:bold;")
        if (r.italic) sb.append("font-style:italic;")
        if (r.underline) sb.append("text-decoration:underline;")
        r.fontSize?.let {
            val size = it * 0.90 // Prevent wrapping overflows from wider Android fonts
            sb.append("font-size:${size}pt;")
        }
        r.color?.let { sb.append("color:$it;") }
        return sb.toString()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun header(viewportW: Int = 960) = """<html><head>
<meta name="viewport" content="width=$viewportW, user-scalable=yes">
<style>
html { -webkit-text-size-adjust: none; text-size-adjust: none; }
*{box-sizing:border-box;margin:0;padding:0}
body{background:#2c2c2c;padding:16px 0;font-family:'Segoe UI',Roboto,sans-serif}
.lbl{color:#aaa;text-align:center;padding:12px;font-size:18px}
.slide{position:relative;margin:0 auto 24px;background:#fff;box-shadow:0 8px 32px rgba(0,0,0,.4);overflow:visible}
.sh{word-wrap:break-word;overflow:visible;padding:8px}
.sh p{line-height:1.25;margin:0}
.sh img{width:100%;height:100%;object-fit:contain}
</style></head><body>"""

    private fun errHtml(msg: String) =
        "<html><body style='padding:24px;font-family:sans-serif'><h3>Error</h3><p>$msg</p></body></html>"
}
