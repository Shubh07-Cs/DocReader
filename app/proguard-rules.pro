# ===============================
# Apache POI FULL PROTECTION
# ===============================

# Keep ALL POI
-keep class org.apache.poi.** { *; }

# Keep XML (for xlsx / pptx)
-keep class org.apache.xmlbeans.** { *; }
-keep class org.openxmlformats.** { *; }

# 🔥 VERY IMPORTANT (binary formats)
-keep class org.apache.poi.hssf.** { *; }
-keep class org.apache.poi.hslf.** { *; }
-keep class org.apache.poi.poifs.** { *; }

# Prevent reflection issues
-keepclassmembers class org.apache.poi.** {
    public *;
}

# Ignore warnings
-dontwarn org.apache.poi.**
-dontwarn org.apache.xmlbeans.**
-dontwarn org.openxmlformats.**
-dontwarn org.codehaus.stax2.**

# Android unsupported stuff
-dontwarn java.awt.**
-dontwarn org.apache.poi.hwmf.**
-dontwarn org.apache.poi.xslf.**

# Optional dependencies
-dontwarn org.osgi.framework.**
-dontwarn aQute.bnd.annotation.spi.**
-dontwarn com.gemalto.jp2.**
-dontwarn com.sun.msv.**

# ===============================
# OTHER LIBRARIES
# ===============================

# PdfBox-Android
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**

# TalbotGooday PDF Viewer
-keep class com.github.barteksc.pdfviewer.** { *; }
-dontwarn com.github.barteksc.pdfviewer.**

# Woodstox (needed by POI)
-keep class com.ctc.wstx.** { *; }
-dontwarn com.ctc.wstx.**

# ===============================
# DATA & UI MODELS (Avoid Obfuscation for ViewBinding/Serialization)
# ===============================

-keep class com.example.docreader.data.** { *; }
-keep class com.example.docreader.ui.DocumentItem { *; }
-keep class com.example.docreader.reader.** { *; }

# ===============================
# CRITICAL APACHE POI XML & REFLECTION RULES
# ===============================
-keep class schemaorg_apache_xmlbeans.** { *; }
-keep class org.apache.xmlbeans.impl.** { *; }
-keep class org.codehaus.stax2.** { *; }
-keep class javax.xml.stream.** { *; }
-keep class com.fasterxml.woodstox.** { *; }
-keep class * extends javax.xml.stream.XMLInputFactory { *; }
-keep class * extends javax.xml.stream.XMLOutputFactory { *; }
-keep class * extends javax.xml.stream.XMLEventFactory { *; }

-keepclassmembers class * extends javax.xml.stream.XMLInputFactory {
    public <init>();
}
-keepclassmembers class * extends javax.xml.stream.XMLOutputFactory {
    public <init>();
}
-keepclassmembers class * extends javax.xml.stream.XMLEventFactory {
    public <init>();
}
