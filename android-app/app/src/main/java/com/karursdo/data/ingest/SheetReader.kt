package com.karursdo.data.ingest

import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DateUtil
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/** A parsed sheet: header row + data rows, all cells as raw strings (numbers unformatted). */
data class SheetData(val headers: List<String>, val rows: List<List<String>>)

/**
 * Reads the FIRST worksheet of an .xlsx (custom lightweight OOXML parser â€” the same
 * approach the web app's xlsx-lite.js proves sufficient for these files) or of a
 * legacy binary .xls (Apache POI HSSF).
 */
object SheetReader {

    fun read(fileName: String, stream: InputStream): SheetData {
        val bytes = stream.readBytes()
        // Many HRMS "export to Excel" downloads are actually an HTML <table> saved with an
        // .xls extension (e.g. the Temporary Arrangements export). POI cannot read those, so
        // sniff the leading bytes and parse HTML instead.
        if (looksLikeHtml(bytes)) return readHtmlTable(bytes)
        return if (fileName.lowercase().endsWith(".xls")) readXls(bytes) else readXlsx(bytes)
    }

    /** True when the payload starts with an HTML/XML doctype or tag rather than a binary sheet. */
    private fun looksLikeHtml(bytes: ByteArray): Boolean {
        val head = String(bytes, 0, minOf(bytes.size, 512), Charsets.UTF_8)
            .replace("﻿", "").trimStart().lowercase()
        return head.startsWith("<html") || head.startsWith("<!doctype") ||
            head.startsWith("<?xml") || head.startsWith("<table") || head.contains("<table")
    }

    // ---------- HTML <table> export (HRMS "Excel" download) ----------

    private val ROW_RE = Regex("<tr[^>]*>(.*?)</tr>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val CELL_RE = Regex("<t[dh][^>]*>(.*?)</t[dh]>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val TAG_RE = Regex("<[^>]+>")

    private fun readHtmlTable(bytes: ByteArray): SheetData {
        val text = String(bytes, Charsets.UTF_8)
        val rows = mutableListOf<List<String>>()
        var width = 0
        for (rowMatch in ROW_RE.findAll(text)) {
            val cells = CELL_RE.findAll(rowMatch.groupValues[1]).map { cell ->
                unescapeHtml(TAG_RE.replace(cell.groupValues[1], "")).trim()
            }.toList()
            if (cells.isNotEmpty()) {
                width = maxOf(width, cells.size)
                rows += cells
            }
        }
        return toSheetData(rows, width)
    }

    private fun unescapeHtml(s: String): String {
        if (s.indexOf('&') < 0) return s
        var out = s
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&apos;", "'")
        // Numeric entities (&#123; / &#x1F;).
        out = Regex("&#(x?[0-9a-fA-F]+);").replace(out) { m ->
            val g = m.groupValues[1]
            val code = if (g.startsWith("x") || g.startsWith("X"))
                g.substring(1).toIntOrNull(16) else g.toIntOrNull()
            code?.let { runCatching { String(Character.toChars(it)) }.getOrNull() } ?: m.value
        }
        return out
    }

    // ---------- legacy .xls via POI HSSF ----------

    private fun readXls(bytes: ByteArray): SheetData {
        HSSFWorkbook(ByteArrayInputStream(bytes)).use { wb ->
            val sheet = wb.getSheetAt(0)
            val out = mutableListOf<List<String>>()
            var width = 0
            for (row in sheet) {
                val cells = mutableListOf<String>()
                val last = row.lastCellNum.toInt()
                for (c in 0 until maxOf(last, 0)) {
                    val cell = row.getCell(c)
                    cells += when (cell?.cellType) {
                        CellType.STRING -> cell.stringCellValue.orEmpty()
                        CellType.NUMERIC ->
                            if (DateUtil.isCellDateFormatted(cell)) formatSerialDate(cell.numericCellValue)
                            else trimNumber(cell.numericCellValue)
                        CellType.BOOLEAN -> if (cell.booleanCellValue) "TRUE" else "FALSE"
                        CellType.FORMULA -> runCatching { cell.stringCellValue }.getOrElse {
                            runCatching { trimNumber(cell.numericCellValue) }.getOrDefault("")
                        }
                        else -> ""
                    }
                }
                width = maxOf(width, cells.size)
                out += cells
            }
            return toSheetData(out, width)
        }
    }

    // ---------- .xlsx via zip + XML pull parsing ----------

    private fun readXlsx(bytes: ByteArray): SheetData {
        var sharedXml: ByteArray? = null
        var sheetXml: ByteArray? = null
        var firstSheetPath: String? = null
        var workbookXml: ByteArray? = null
        var relsXml: ByteArray? = null
        val sheets = mutableMapOf<String, ByteArray>()

        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                val name = e.name
                when {
                    name == "xl/sharedStrings.xml" -> sharedXml = zip.readBytes()
                    name == "xl/workbook.xml" -> workbookXml = zip.readBytes()
                    name == "xl/_rels/workbook.xml.rels" -> relsXml = zip.readBytes()
                    name.startsWith("xl/worksheets/") && name.endsWith(".xml") ->
                        sheets[name.removePrefix("xl/")] = zip.readBytes()
                }
                e = zip.nextEntry
            }
        }
        // Resolve the first sheet listed in workbook.xml via its relationship id.
        if (workbookXml != null && relsXml != null) {
            val firstRid = firstSheetRid(workbookXml!!)
            if (firstRid != null) firstSheetPath = relTarget(relsXml!!, firstRid)
        }
        sheetXml = firstSheetPath?.let { sheets[it.removePrefix("/xl/").removePrefix("xl/")] }
            ?: sheets["worksheets/sheet1.xml"] ?: sheets.values.firstOrNull()
            ?: error("No worksheet found in xlsx")

        val shared = sharedXml?.let { parseSharedStrings(it) } ?: emptyList()
        return parseSheet(sheetXml!!, shared)
    }

    private fun newParser(bytes: ByteArray): XmlPullParser {
        val p = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }.newPullParser()
        p.setInput(ByteArrayInputStream(bytes), "UTF-8")
        return p
    }

    private fun firstSheetRid(workbookXml: ByteArray): String? {
        val p = newParser(workbookXml)
        while (p.eventType != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && p.name == "sheet") {
                for (i in 0 until p.attributeCount) {
                    if (p.getAttributeName(i).endsWith("id")) return p.getAttributeValue(i)
                }
            }
            p.next()
        }
        return null
    }

    private fun relTarget(relsXml: ByteArray, rid: String): String? {
        val p = newParser(relsXml)
        while (p.eventType != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && p.name == "Relationship" &&
                p.getAttributeValue(null, "Id") == rid
            ) return p.getAttributeValue(null, "Target")
            p.next()
        }
        return null
    }

    private fun parseSharedStrings(xml: ByteArray): List<String> {
        val p = newParser(xml)
        val list = mutableListOf<String>()
        val sb = StringBuilder()
        var inSi = false
        var inT = false
        while (p.eventType != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "si" -> { inSi = true; sb.setLength(0) }
                    "t" -> inT = inSi
                }
                XmlPullParser.TEXT -> if (inT) sb.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "t" -> inT = false
                    "si" -> { inSi = false; list += sb.toString() }
                }
            }
            p.next()
        }
        return list
    }

    private fun parseSheet(xml: ByteArray, shared: List<String>): SheetData {
        val p = newParser(xml)
        val rows = mutableListOf<MutableList<String>>()
        var width = 0
        var currentRow: MutableList<String>? = null
        var cellType = ""
        var cellCol = -1
        var inV = false
        var inInlineT = false
        val v = StringBuilder()

        while (p.eventType != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> currentRow = mutableListOf()
                    "c" -> {
                        cellType = p.getAttributeValue(null, "t") ?: ""
                        cellCol = colIndex(p.getAttributeValue(null, "r"))
                        v.setLength(0)
                    }
                    "v" -> { inV = true; v.setLength(0) }
                    "t" -> if (cellType == "inlineStr") { inInlineT = true; v.setLength(0) }
                }
                XmlPullParser.TEXT -> if (inV || inInlineT) v.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "v" -> inV = false
                    "t" -> inInlineT = false
                    "c" -> {
                        currentRow?.let { row ->
                            val value = when (cellType) {
                                "s" -> shared.getOrElse(v.toString().toIntOrNull() ?: -1) { "" }
                                "b" -> if (v.toString() == "1") "TRUE" else "FALSE"
                                else -> v.toString()
                            }
                            val idx = if (cellCol >= 0) cellCol else row.size
                            while (row.size <= idx) row.add("")
                            row[idx] = value
                        }
                    }
                    "row" -> currentRow?.let { rows += it; width = maxOf(width, it.size); currentRow = null }
                }
            }
            p.next()
        }
        return toSheetData(rows, width)
    }

    private fun colIndex(ref: String?): Int {
        if (ref == null) return -1
        var idx = 0
        for (ch in ref) {
            if (ch.isLetter()) idx = idx * 26 + (ch.uppercaseChar() - 'A' + 1) else break
        }
        return idx - 1
    }

    // ---------- shared helpers ----------

    private fun toSheetData(rows: List<List<String>>, width: Int): SheetData {
        val headerRowIdx = rows.indexOfFirst { r -> r.any { it.isNotBlank() } }
        if (headerRowIdx < 0) return SheetData(emptyList(), emptyList())
        val headers = rows[headerRowIdx].toMutableList().also { while (it.size < width) it.add("") }
        val data = rows.drop(headerRowIdx + 1)
            .map { r -> r.toMutableList().also { while (it.size < width) it.add("") } as List<String> }
            .filter { r -> r.any { it.isNotBlank() } }
        return SheetData(headers, data)
    }

    fun trimNumber(d: Double): String =
        if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

    /** Excel serial date -> dd-mm-yyyy (1900 date system, matching the web importer). */
    fun formatSerialDate(serial: Double): String {
        val days = serial.toInt()
        // Excel's day 1 = 1900-01-01, with the phantom 1900-02-29 (serial 60).
        val epoch = java.time.LocalDate.of(1899, 12, 30)
        val date = epoch.plusDays(days.toLong())
        return "%02d-%02d-%04d".format(date.dayOfMonth, date.monthValue, date.year)
    }
}
