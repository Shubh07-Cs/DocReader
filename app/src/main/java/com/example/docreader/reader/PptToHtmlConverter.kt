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

    fun convertPpt(context: Context, inputStream: InputStream): String { return try {
        val buf = if (inputStream.markSupported()) inputStream else BufferedInputStream(inputStream)
        val show = org.apache.poi.hslf.usermodel.HSLFSlideShow(buf)
        val sb = StringBuilder(header())

        show.slides.forEachIndexed { i, slide ->
            sb.append("<div class='lbl'>Slide ${i + 1}</div>")
            sb.append("<div class='slide' style='padding:32px;min-height:160px;'>")
            try {
                for (shape in slide.shapes) {
                    if (shape is org.apache.poi.hslf.usermodel.HSLFTextShape) {
                        for (para in shape.textParagraphs) {
                            sb.append("<p>")
                            for (run in para.textRuns) {
                                val css = StringBuilder()
                                try { if (run.isBold) css.append("font-weight:bold;") } catch (_: Throwable) {}
                                try { if (run.isItalic) css.append("font-style:italic;") } catch (_: Throwable) {}
                                try { if (run.isUnderlined) css.append("text-decoration:underline;") } catch (_: Throwable) {}
                                try { run.fontSize?.let { css.append("font-size:${it}pt;") } } catch (_: Throwable) {}
                                sb.append("<span style='$css'>${esc(run.rawText)}</span>")
                            }
                            sb.append("</p>")
                        }
                    } else if (shape is org.apache.poi.hslf.usermodel.HSLFPictureShape) {
                        try {
                            val d = shape.pictureData
                            val b64 = Base64.encodeToString(d.data, Base64.NO_WRAP)
                            sb.append("<img style='max-width:100%;margin:8px 0;' src='data:${d.contentType};base64,$b64'/>")
                        } catch (_: Throwable) {}
                    }
                }
            } catch (_: Throwable) {
                sb.append("<p style='color:#888;'><i>Could not fully read this slide.</i></p>")
            }
            sb.append("</div>")
        }
        show.close()
        sb.append("</body></html>"); sb.toString()
    } catch (e: Throwable) { errHtml("Cannot read legacy .ppt: ${e.message}") } }

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
