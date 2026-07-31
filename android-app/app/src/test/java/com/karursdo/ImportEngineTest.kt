package com.karursdo

import com.karursdo.data.ingest.ImportEngine
import com.karursdo.data.ingest.ImportType
import com.karursdo.data.ingest.MobileMatcher
import com.karursdo.data.ingest.RecordMappers
import com.karursdo.data.ingest.SheetData
import com.karursdo.data.ingest.SheetReader
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportEngineTest {

    @Test
    fun `detects DS from Basic Pay header`() {
        assertEquals(
            ImportType.DS,
            ImportEngine.detectType(listOf("Employee_id", "Employee_name", "Basic Pay", "DA"))
        )
    }

    @Test
    fun `detects GDS from TRCA and tolerates trailing-space headers`() {
        assertEquals(
            ImportType.GDS,
            ImportEngine.detectType(listOf("Employee_id", "TRCA", "Comp. Allw.(N) ", "SDBS"))
        )
    }

    @Test
    fun `detects outsiders from resource_id`() {
        assertEquals(
            ImportType.OUT,
            ImportEngine.detectType(listOf("resource_id", "first_name", "last_name"))
        )
    }

    @Test
    fun `detects mobile roster from Ph Number with trailing space`() {
        assertEquals(
            ImportType.MOBILE,
            ImportEngine.detectType(listOf("S.NO", "Name of the offices", "Designation", "Name", "Ph Number "))
        )
    }

    @Test
    fun `detects arrangements from arrangement_type_desc`() {
        assertEquals(
            ImportType.ARRANGEMENT,
            ImportEngine.detectType(
                listOf("arrangement_type_desc", "new_designation", "new_office_name", "employee_id")
            )
        )
    }

    @Test
    fun `arrangements parse keeps only the three tracked categories`() {
        val sheet = SheetData(
            headers = listOf(
                "arrangement_type_desc", "new_designation", "new_office_name", "new_office_id",
                "employee_id", "employee_name", "resource_id", "resource_name",
                "arrangement_from_date", "arrangement_to_date",
                "revised_arrangement_to_date", "approve_status"
            ),
            rows = listOf(
                listOf("GDS Post by Outsrc Resource", "Branch Post Master", "Ayanporuvai B.O",
                    "29106754", "0", "", "60004282", "Kokila R", "2025-06-28", "2025-07-13", "", "Approved"),
                listOf("Non Sanc Post by Outsrc Resource", "MTS", "Karur H.O",
                    "29106700", "0", "", "60009999", "Test N", "2025-08-01", "2025-08-10", "2025-08-15", "Approved"),
                listOf("Non Sanc Post by GDS Emp", "Postman", "Vangal S.O",
                    "29106701", "10012345", "Murugan S", "0", "", "2025-07-01", "2025-07-31", "", "Approved"),
                listOf("Dept Post by Dept Emp", "Postman", "Karur H.O",
                    "29106700", "60001111", "Skip Me", "0", "", "2025-01-01", "2025-01-02", "", "Approved")
            )
        )
        val parsed = ImportEngine.parse("arrangements.xlsx", sheet)!!
        assertEquals(ImportType.ARRANGEMENT, parsed.type)

        val mapped = parsed.records.mapNotNull { RecordMappers.toArrangement(it) }
        assertEquals(3, mapped.size)                        // Dept-by-Dept row dropped
        assertEquals("GDS", mapped[0].category)
        assertEquals("Branch Post Master", mapped[0].post)
        assertEquals("Ayanporuvai B.O", mapped[0].officeName)
        assertEquals("Kokila R", mapped[0].personName)      // resource_name for outsource cover
        assertEquals("28-06-2025", mapped[0].fromDate)      // ISO -> dd-mm-yyyy
        assertEquals("NONSANC_OUT", mapped[1].category)
        assertEquals("15-08-2025", mapped[1].toDate)        // revised_to_date preferred over to_date
        assertEquals("NONSANC_GDS", mapped[2].category)
        assertEquals("Murugan S", mapped[2].personName)     // employee_name for GDS-emp cover
    }

    @Test
    fun `reads an HTML-table export saved with an xls extension`() {
        // The HRMS "Temporary Arrangements" export is really an HTML table.
        val html = """
            <html><body><table>
              <tr><th>arrangement_type_desc</th><th>new_office_name</th><th>approve_status</th></tr>
              <tr><td>GDS Post by Outsrc Resource</td><td>Ayanporuvai &amp; B.O</td><td>Approved</td></tr>
            </table></body></html>
        """.trimIndent()
        val sheet = SheetReader.read("Temporary Arrangements.xls", ByteArrayInputStream(html.toByteArray()))
        assertEquals(listOf("arrangement_type_desc", "new_office_name", "approve_status"), sheet.headers)
        assertEquals(1, sheet.rows.size)
        assertEquals("GDS Post by Outsrc Resource", sheet.rows[0][0])
        assertEquals("Ayanporuvai & B.O", sheet.rows[0][1])   // &amp; unescaped
    }

    @Test
    fun `unknown headers yield null`() {
        assertNull(ImportEngine.detectType(listOf("foo", "bar")))
    }

    @Test
    fun `parse drops Concat column and rows without id or name`() {
        val sheet = SheetData(
            headers = listOf("Employee_id", "Employee_name", "TRCA", "Concat"),
            rows = listOf(
                listOf("60090001", "Sample Sevak", "10000", "ABPM, X B.O"),
                listOf("", "", "9999", "junk")
            )
        )
        val parsed = ImportEngine.parse("GS TEST.xlsx", sheet)!!
        assertEquals(ImportType.GDS, parsed.type)
        assertEquals(1, parsed.records.size)
        assertEquals(1, parsed.skippedRows.size)
        assertFalse(parsed.records[0].containsKey("Concat"))
    }

    @Test
    fun `normalizes excel serial and ISO dates to dd-mm-yyyy`() {
        // 26809 = 1973-05-25 in the 1900 date system
        assertEquals("25-05-1973", ImportEngine.normalizeDate("26809"))
        assertEquals("15-06-2000", ImportEngine.normalizeDate("2000-06-15T00:00:00Z"))
        assertEquals("01-02-1999", ImportEngine.normalizeDate("01-02-1999"))
    }
}

class MobileMatcherTest {

    @Test
    fun `normalizes office names stripping suffix and punctuation`() {
        assertEquals(MobileMatcher.normOffice("Andankovil So"), MobileMatcher.normOffice("ANDANKOVIL S.O"))
    }

    @Test
    fun `applies office aliases`() {
        assertTrue(MobileMatcher.officeMatches("etpalayam", "East Thavittupalayam B.O"))
    }

    @Test
    fun `fuzzy office match within levenshtein 2`() {
        assertTrue(MobileMatcher.officeMatches("Manavassi B.O", "Manavasi B.O"))
    }

    @Test
    fun `phone normalization strips leading 0 and 91`() {
        assertEquals("9952402413", MobileMatcher.normPhone("09952402413"))
        assertEquals("9952402413", MobileMatcher.normPhone("919952402413"))
        assertEquals("9952402413", MobileMatcher.normPhone("99524 02413"))
        assertNull(MobileMatcher.normPhone("12345"))
    }

    @Test
    fun `name matching by token overlap`() {
        assertTrue(MobileMatcher.nameScore("Duraisamy", "DURAISAMY K") > 0)
        assertTrue(MobileMatcher.nameScore("Manivel M", "Manivel M") > 0)
        assertEquals(0, MobileMatcher.nameScore("Ravi", "Kumar S"))
    }
}
