package com.karursdo.report

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A minimal .xlsx writer — one sheet, every cell an inline string.
 *
 * This is the Kotlin counterpart of the web app's xlsx-lite.js, deliberately so: the two
 * produce the same shape of workbook, so a report downloaded on the phone and one downloaded
 * in the browser open identically. Apache POI's ooxml half is far too heavy for the app, and
 * the plain `poi` artifact we already carry writes only the legacy .xls format.
 *
 * Everything is written as text (number format "@"). Account numbers, SOL ids and policy
 * numbers are digit strings that Excel would otherwise mangle into scientific notation or
 * quietly strip leading zeroes from, and an account number is not a quantity.
 */
object XlsxWriter {

    private const val CONTENT_TYPES =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
        "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
        "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
        "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
        "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
        "</Types>"

    private const val ROOT_RELS =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
        "</Relationships>"

    private const val WORKBOOK =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
        "<sheets><sheet name=\"Data\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"

    private const val WORKBOOK_RELS =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
        "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>" +
        "</Relationships>"

    /** style 0 = default · 1 = text (@) · 2 = bold text header */
    private const val STYLES =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
        "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>" +
        "<fills count=\"1\"><fill><patternFill patternType=\"none\"/></fill></fills>" +
        "<borders count=\"1\"><border/></borders>" +
        "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
        "<cellXfs count=\"3\">" +
        "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
        "<xf numFmtId=\"49\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>" +
        "<xf numFmtId=\"49\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\"/>" +
        "</cellXfs>" +
        "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>" +
        "</styleSheet>"

    private fun colLetter(index: Int): String {
        var i = index; val sb = StringBuilder()
        while (true) { sb.insert(0, ('A' + i % 26)); i = i / 26 - 1; if (i < 0) break }
        return sb.toString()
    }

    /** XML text escaping, plus dropping the control characters Excel refuses to open. */
    private fun esc(raw: String?): String {
        val s = raw ?: return ""
        val sb = StringBuilder(s.length + 16)
        for (ch in s) when {
            ch == '&' -> sb.append("&amp;")
            ch == '<' -> sb.append("&lt;")
            ch == '>' -> sb.append("&gt;")
            ch == '"' -> sb.append("&quot;")
            ch == '\'' -> sb.append("&#39;")
            ch == '\t' || ch == '\n' || ch == '\r' -> sb.append(' ')
            ch.code < 0x20 -> Unit
            else -> sb.append(ch)
        }
        return sb.toString()
    }

    private fun sheetXml(headers: List<String>, rows: List<List<String>>): String {
        val n = headers.size
        val sb = StringBuilder(64 * 1024)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
            .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
            .append("<cols>")
        for (i in 0 until n) {
            sb.append("<col min=\"").append(i + 1).append("\" max=\"").append(i + 1)
                .append("\" width=\"18\" style=\"1\" customWidth=\"1\"/>")
        }
        sb.append("</cols><sheetData>")
        fun cell(ci: Int, ri: Int, v: String, style: Int) {
            sb.append("<c r=\"").append(colLetter(ci)).append(ri).append("\" t=\"inlineStr\" s=\"").append(style)
                .append("\"><is><t xml:space=\"preserve\">").append(esc(v)).append("</t></is></c>")
        }
        sb.append("<row r=\"1\">")
        for (c in 0 until n) cell(c, 1, headers[c], 2)
        sb.append("</row>")
        var r = 2
        for (row in rows) {
            sb.append("<row r=\"").append(r).append("\">")
            for (c in 0 until n) cell(c, r, row.getOrNull(c) ?: "", 1)
            sb.append("</row>")
            r++
        }
        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    /** Write one sheet of [headers] + [rows] to [file]. Returns the same file. */
    fun write(file: File, headers: List<String>, rows: List<List<String>>): File {
        file.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(file).buffered()).use { zip ->
            fun part(name: String, body: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            part("[Content_Types].xml", CONTENT_TYPES)
            part("_rels/.rels", ROOT_RELS)
            part("xl/workbook.xml", WORKBOOK)
            part("xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
            part("xl/styles.xml", STYLES)
            part("xl/worksheets/sheet1.xml", sheetXml(headers, rows))
        }
        return file
    }
}
