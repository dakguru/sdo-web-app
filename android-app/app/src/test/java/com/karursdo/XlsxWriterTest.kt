package com.karursdo

import com.karursdo.report.XlsxWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The consolidated Excel reports are built by hand, part by part, so what matters is that the
 * package Excel receives is actually a package: the six parts present, each one well-formed
 * XML, and the cells where the rows said they would be.
 */
class XlsxWriterTest {

    private val parts = listOf(
        "[Content_Types].xml", "_rels/.rels", "xl/workbook.xml",
        "xl/_rels/workbook.xml.rels", "xl/styles.xml", "xl/worksheets/sheet1.xml"
    )

    private fun build(headers: List<String>, rows: List<List<String>>): File {
        val f = File.createTempFile("cpv-test", ".xlsx").apply { deleteOnExit() }
        return XlsxWriter.write(f, headers, rows)
    }

    private fun entryText(file: File, name: String): String =
        ZipFile(file).use { z -> z.getInputStream(z.getEntry(name)!!).readBytes().toString(Charsets.UTF_8) }

    private fun parse(xml: String) =
        DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(xml.byteInputStream(Charsets.UTF_8))

    @Test
    fun `writes every part of the package and each is well-formed xml`() {
        val f = build(listOf("A", "B"), listOf(listOf("1", "2"), listOf("3", "4")))
        ZipFile(f).use { z ->
            val names = z.entries().toList().map { it.name }
            parts.forEach { assertTrue("missing part $it", names.contains(it)) }
        }
        parts.forEach { parse(entryText(f, it)) }   // throws if malformed
    }

    @Test
    fun `header and every data cell land in the right row and column`() {
        val headers = listOf("Sl.No", "Office", "Account")
        val rows = listOf(listOf("1", "EAST SO", "0200147"), listOf("2", "KADAPARAI SO", "0200148"))
        val sheet = entryText(build(headers, rows), "xl/worksheets/sheet1.xml")
        val doc = parse(sheet)
        val cells = doc.getElementsByTagName("c")
        assertEquals(9, cells.length)                       // 3 columns x (1 header + 2 rows)
        val refs = (0 until cells.length).map { cells.item(it).attributes.getNamedItem("r").nodeValue }
        assertEquals(listOf("A1", "B1", "C1", "A2", "B2", "C2", "A3", "B3", "C3"), refs)
        assertTrue(sheet.contains("<t xml:space=\"preserve\">KADAPARAI SO</t>"))
        // Account numbers must stay text, or Excel eats the leading zero.
        assertTrue(sheet.contains("t=\"inlineStr\""))
    }

    @Test
    fun `a short row is padded rather than shifting later columns`() {
        val sheet = entryText(build(listOf("A", "B", "C"), listOf(listOf("x"))), "xl/worksheets/sheet1.xml")
        val doc = parse(sheet)
        val rowEls = doc.getElementsByTagName("row")
        assertEquals(2, rowEls.length)
        assertEquals(3, rowEls.item(1).childNodes.length)
    }

    @Test
    fun `ampersands, angle brackets and control characters cannot break the sheet`() {
        val nasty = "Tom & Jerry <b> \"quoted\" 'x' \u0007bell\ttab"
        val sheet = entryText(build(listOf("H"), listOf(listOf(nasty))), "xl/worksheets/sheet1.xml")
        parse(sheet)                                        // the real assertion: it still parses
        assertTrue(sheet.contains("Tom &amp; Jerry &lt;b&gt;"))
        assertTrue("the BEL should be dropped", !sheet.contains("\u0007"))
    }

    @Test
    fun `columns past Z get the right letters`() {
        val headers = (0 until 28).map { "H$it" }
        val sheet = entryText(build(headers, listOf(headers)), "xl/worksheets/sheet1.xml")
        assertTrue(sheet.contains("r=\"Z1\""))
        assertTrue(sheet.contains("r=\"AA1\""))
        assertTrue(sheet.contains("r=\"AB1\""))
    }

    // A round-trip through the app's own SheetReader would be the strongest check of all,
    // but it parses with Android's XmlPullParser, which is stubbed out in host-side unit tests.
    // It belongs in androidTest, on a device.

    @Test
    fun `a report-sized sheet stays well-formed`() {
        val headers = (1..12).map { "Col$it" }
        val rows = (1..20_000).map { r -> (1..12).map { c -> "r${r}c$c" } }
        val f = build(headers, rows)
        val doc = parse(entryText(f, "xl/worksheets/sheet1.xml"))
        assertEquals(20_001, doc.getElementsByTagName("row").length)
        assertTrue("deflate should keep this well under 10 MB", f.length() < 10L * 1024 * 1024)
    }
}
