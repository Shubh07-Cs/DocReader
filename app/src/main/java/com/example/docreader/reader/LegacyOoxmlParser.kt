package com.example.docreader.reader

import java.io.InputStream

object LegacyOoxmlParser {

    private const val UNSUPPORTED_MESSAGE = "<html><body style='padding: 16px; font-family: sans-serif;'><h3>Legacy Format Not Supported</h3><p>This file appears to be a legacy binary format (e.g., .xls, .doc, .ppt from Office 97-2003).</p><p>This lightweight reader only supports modern Open XML formats (e.g., .xlsx, .docx, .pptx).</p><p>Please re-save the file in a newer format to view its contents.</p></body></html>"

    fun parseDoc(inputStream: InputStream): String {
        return UNSUPPORTED_MESSAGE
    }

    fun parseXls(inputStream: InputStream): String {
        return UNSUPPORTED_MESSAGE
    }

    fun parsePpt(inputStream: InputStream): String {
        return UNSUPPORTED_MESSAGE
    }
}