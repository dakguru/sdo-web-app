package com.karursdo

import com.karursdo.ui.phone.Contact
import com.karursdo.ui.phone.buildGoogleCsv
import com.karursdo.ui.phone.buildVcf
import com.karursdo.ui.phone.contactName
import com.karursdo.ui.phone.matches
import com.karursdo.ui.phone.prettyPhone
import com.karursdo.ui.phone.shortDesig
import com.karursdo.ui.phone.stripInitials
import com.karursdo.ui.phone.tenDigit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the Phone Directory logic that is shared, by hand, with the web module
 * (Leave Orders/phone-directory.html). Every designation below is one that
 * actually appears in the Karur payroll.
 */
class PhoneDirectoryTest {

    @Test
    fun `short designation covers every live payroll designation`() {
        val ds = mapOf(
            "Postal Assistant" to "PA",
            "Postal Assistant - X" to "PA",
            "Post Man" to "PM",
            "Sub Postmaster" to "SPM",
            "Multi Tasking Staff" to "MTS",
            "Office Assistant" to "OA",
            "HSG II" to "HSG-II",
            "Mail Overseer" to "MO",
            "Public Relation Inspector (P)" to "PRI(P)",
            "Section Supervisor HSG-II" to "HSG-II",
            "Section Supervisor" to "Sec.Supvr",
            "Deputy Postmaster" to "Dy.PM",
            "Postmaster" to "PM Gr.",
            "Inspector Posts" to "IP",
            "Sorting Postman" to "Sorting PM",
            "Cash Overseer" to "CO",
            "Head Postman" to "HPM",
            "Superintendent Of Post Offices" to "SPOs",
            "Assistant Superintendent Of Post Offices" to "ASP",
            "Development Officer (PLI)" to "DO(PLI)",
            "Accountant" to "Accts",
            "Driver" to "Driver"
        )
        ds.forEach { (full, short) -> assertEquals(full, short, shortDesig(full, "DS")) }

        val gds = mapOf(
            "Branch Postmaster" to "BPM",
            "Assistant Branch Postmaster" to "ABPM",
            "Delivery Agent" to "DA",
            "Dak Sevak" to "DS",
            "Packer" to "Packer",
            "Mail Carrier" to "MC"
        )
        gds.forEach { (full, short) -> assertEquals(full, short, shortDesig(full, "GDS")) }

        // Outsourced resources are always OS, whatever the designation says.
        assertEquals("OS", shortDesig("Outsourced Resource", "OUT"))
        assertEquals("OS", shortDesig("Postal Assistant", "OUT"))
    }

    @Test
    fun `sub postmaster does not collide with postmaster or sorting postman`() {
        assertEquals("SPM", shortDesig("Sub Postmaster", "DS"))
        assertEquals("PM Gr.", shortDesig("Postmaster", "DS"))
        assertEquals("Sorting PM", shortDesig("Sorting Postman", "DS"))
        assertEquals("PM", shortDesig("Post Man", "DS"))
        assertEquals("ABPM", shortDesig("Assistant Branch Postmaster", "GDS"))
        assertEquals("BPM", shortDesig("Branch Postmaster", "GDS"))
    }

    @Test
    fun `ten digit normalisation handles the shapes the roster holds`() {
        assertEquals("9787315897", tenDigit("9787315897"))
        assertEquals("9787315897", tenDigit("+91 97873 15897"))
        assertEquals("9787315897", tenDigit("09787315897"))
        assertEquals("9787315897", tenDigit("919787315897"))
        assertEquals("", tenDigit("12345"))
        assertEquals("", tenDigit(null))
        assertEquals("", tenDigit(""))
        assertEquals("97873 15897", prettyPhone("9787315897"))
    }

    @Test
    fun `initials are stripped leading and trailing but never the whole name`() {
        assertEquals("RAJESWARI", stripInitials("RAJESWARI S"))
        assertEquals("NACHIMUTHU", stripInitials("P NACHIMUTHU"))
        assertEquals("GAYATHRI PITCHAI", stripInitials("GAYATHRI PITCHAI"))
        assertEquals("SHARMA", stripInitials("R K SHARMA"))
        assertEquals("Dharshini", stripInitials("Dharshini V"))
        // Doubled spaces in the payroll export collapse, not leak into the name.
        assertEquals("RAJESWARI", stripInitials("RAJESWARI  S"))
        // An all-initials name is kept as it is rather than vanishing.
        assertEquals("R K", stripInitials("R K"))
    }

    private fun sample(
        cat: String = "DS",
        name: String = "RAJESWARI S",
        designation: String = "Sub Postmaster",
        office: String = "Sengunthapuram S.O",
        mobile: String = "9787315897",
        email: String = ""
    ) = Contact(
        cat = cat, id = "10003954", name = name, designation = designation,
        short = shortDesig(designation, cat), officeId = "29661945", office = office,
        boso = "SO", pincode = "639002", acctOffice = "Karur H.O", mobile = mobile, email = email
    )

    @Test
    fun `contact name is name short-designation office, without initials`() {
        assertEquals("RAJESWARI SPM Sengunthapuram S.O", contactName(sample()))
        assertEquals(
            "ATCHAYA OS Andankoil S.O",
            contactName(
                sample(cat = "OUT", name = "ATCHAYA V", designation = "Outsourced Resource", office = "Andankoil S.O")
            )
        )
    }

    @Test
    fun `vcard carries the save name, number in e164 and the category label`() {
        val vcf = buildVcf(listOf(sample()), "Karur SDO - Departmental")
        assertTrue(vcf.startsWith("BEGIN:VCARD\r\nVERSION:3.0\r\n"))
        assertTrue(vcf.contains("FN:RAJESWARI SPM Sengunthapuram S.O\r\n"))
        assertTrue(vcf.contains("TEL;TYPE=CELL,VOICE:+919787315897\r\n"))
        assertTrue(vcf.contains("TITLE:Sub Postmaster\r\n"))
        assertTrue(vcf.contains("CATEGORIES:Karur SDO - Departmental\r\n"))
        assertTrue(vcf.trimEnd().endsWith("END:VCARD"))
        // The comma in the ORG value must be escaped, or the field splits in two.
        assertTrue(vcf.contains("ORG:Department of Posts\\, Karur Sub Division;Sengunthapuram S.O"))
        // No e-mail on record → no EMAIL line at all.
        assertFalse(vcf.contains("EMAIL"))
    }

    @Test
    fun `google csv has the header google expects and one labelled row per contact`() {
        val csv = buildGoogleCsv(
            listOf(sample(), sample(name = "P NACHIMUTHU", email = "x@example.com")),
            "Karur SDO - Departmental"
        )
        val lines = csv.trimEnd().split("\r\n")
        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("First Name,Middle Name,Last Name"))
        assertTrue(lines[0].endsWith("Phone 1 - Label,Phone 1 - Value"))
        assertTrue(lines[1].startsWith("RAJESWARI SPM Sengunthapuram S.O,"))
        assertTrue(lines[1].contains("* myContacts ::: Karur SDO - Departmental"))
        assertTrue(lines[1].endsWith("* Mobile,+919787315897"))
        assertTrue(lines[2].startsWith("NACHIMUTHU SPM Sengunthapuram S.O,"))
        assertTrue(lines[2].contains("x@example.com"))
        // The organisation name holds an em-dash but no comma, so it stays unquoted;
        // the notes field does contain " · " separators and must survive intact.
        assertTrue(lines[1].contains("Department of Posts — Karur Sub Division"))
    }

    @Test
    fun `csv quotes any value containing a comma or quote`() {
        val csv = buildGoogleCsv(listOf(sample(office = "Karur H.O, Town")), "L")
        assertTrue(csv.contains("\"RAJESWARI SPM Karur H.O, Town\""))
    }

    @Test
    fun `search matches office, name, number, designation and pincode`() {
        val c = sample()
        assertTrue(c.matches("sengunthapuram"))   // office
        assertTrue(c.matches("rajeswari"))        // name
        assertTrue(c.matches("97873"))            // mobile
        assertTrue(c.matches("sub postmaster"))   // full designation
        assertTrue(c.matches("spm"))              // short designation
        assertTrue(c.matches("639002"))           // pincode
        assertTrue(c.matches("karur h.o"))        // account office
        assertTrue(c.matches(""))                 // empty query matches everything
        assertFalse(c.matches("zzzz"))
        // Fewer than 3 digits is too noisy to match numbers on.
        assertFalse(c.matches("97"))
    }
}
