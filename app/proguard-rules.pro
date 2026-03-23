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
