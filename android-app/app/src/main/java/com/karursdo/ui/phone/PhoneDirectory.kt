package com.karursdo.ui.phone

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.karursdo.data.db.EmployeeEntity
import com.karursdo.data.db.OfficeMasterEntity
import com.karursdo.data.db.OutsiderEntity
import java.io.File
import java.time.LocalDate

/**
 * Phone Directory — the mobile twin of the web module (/phone-directory,
 * `Leave Orders/phone-directory.html`). The short-designation table, the
 * 10-digit normalisation, the initial-stripping and both export formats are
 * ported verbatim so a contact saved from the phone is byte-for-byte the same
 * as one saved from the portal.
 *
 * Rows in this app's Room DB are already Karur-scoped, so no extra filter is
 * applied here. Departmental/GDS e-mails live only in the web's
 * app_staff_phones EMPLOYEE_EMAIL rows, which this app does not sync — so an
 * e-mail is exported for outsiders only.
 */

/** One directory contact, in one of the three export categories. */
data class Contact(
    val cat: String,            // "DS" | "GDS" | "OUT"
    val id: String,             // employeeId or resourceId
    val name: String,           // full payroll name, shown on the card
    val designation: String,    // full designation
    val short: String,          // official short form (PA / BPM / ABPM / OS …)
    val officeId: String,
    val office: String,
    val boso: String,           // BO / SO / HO / …
    val pincode: String,
    val acctOffice: String,
    val mobile: String,         // bare 10 digits, "" when not on record
    val email: String
)

/** Display metadata for each category, keyed by [Contact.cat]. */
enum class ContactCat(val key: String, val label: String, val emoji: String, val fileTag: String) {
    DS("DS", "Departmental", "💼", "Departmental"),
    GDS("GDS", "GDS", "🏠", "GDS"),
    OUT("OUT", "Outsiders", "🤝", "Outsiders");

    companion object {
        fun of(key: String): ContactCat = entries.firstOrNull { it.key == key } ?: DS
    }
}

private fun cln(v: String?): String = (v ?: "").replace(Regex("\\s+"), " ").trim()

// ── designation → official short form ────────────────────────────────────────
// Ordered most-specific first: "Sub Postmaster" must not fall through to the
// plain Postmaster rule, and "Sorting Postman" must not collide with SPM.
private val DESIG_RULES: List<Pair<Regex, String>> = listOf(
    Regex("""assistant\s*branch\s*post\s*-?\s*master|^\s*abpm\s*$""", RegexOption.IGNORE_CASE) to "ABPM",
    Regex("""branch\s*post\s*-?\s*master|^\s*bpm\s*$""", RegexOption.IGNORE_CASE) to "BPM",
    Regex("""assistant\s*superintendent""", RegexOption.IGNORE_CASE) to "ASP",
    Regex("""superintendent\s*of\s*post""", RegexOption.IGNORE_CASE) to "SPOs",
    Regex("""public\s*relation\s*inspector""", RegexOption.IGNORE_CASE) to "PRI(P)",
    Regex("""inspector\s*(of\s*)?posts?""", RegexOption.IGNORE_CASE) to "IP",
    Regex("""development\s*officer""", RegexOption.IGNORE_CASE) to "DO(PLI)",
    Regex("""deputy\s*post\s*-?\s*master""", RegexOption.IGNORE_CASE) to "Dy.PM",
    Regex("""sub\s*post\s*-?\s*master""", RegexOption.IGNORE_CASE) to "SPM",
    Regex("""assistant\s*post\s*-?\s*master""", RegexOption.IGNORE_CASE) to "APM",
    Regex("""sorting\s*post\s*-?\s*man""", RegexOption.IGNORE_CASE) to "Sorting PM",
    Regex("""head\s*post\s*-?\s*man""", RegexOption.IGNORE_CASE) to "HPM",
    Regex("""post\s*-?\s*master""", RegexOption.IGNORE_CASE) to "PM Gr.",
    Regex("""post\s*-?\s*man""", RegexOption.IGNORE_CASE) to "PM",
    Regex("""postal\s*assistant""", RegexOption.IGNORE_CASE) to "PA",
    Regex("""sorting\s*assistant""", RegexOption.IGNORE_CASE) to "SA",
    Regex("""multi\s*-?\s*tasking""", RegexOption.IGNORE_CASE) to "MTS",
    Regex("""office\s*assistant""", RegexOption.IGNORE_CASE) to "OA",
    Regex("""mail\s*overseer""", RegexOption.IGNORE_CASE) to "MO",
    Regex("""cash\s*overseer""", RegexOption.IGNORE_CASE) to "CO",
    Regex("""section\s*supervisor.*hsg|hsg.*section\s*supervisor""", RegexOption.IGNORE_CASE) to "HSG-II",
    Regex("""section\s*supervisor""", RegexOption.IGNORE_CASE) to "Sec.Supvr",
    Regex("""\bhsg\s*-?\s*(ii|2)\b""", RegexOption.IGNORE_CASE) to "HSG-II",
    Regex("""\bhsg\b""", RegexOption.IGNORE_CASE) to "HSG",
    Regex("""\blsg\b""", RegexOption.IGNORE_CASE) to "LSG",
    Regex("""dak\s*sevak""", RegexOption.IGNORE_CASE) to "DS",
    Regex("""delivery\s*agent""", RegexOption.IGNORE_CASE) to "DA",
    Regex("""mail\s*carrier""", RegexOption.IGNORE_CASE) to "MC",
    Regex("""stamp\s*vendor""", RegexOption.IGNORE_CASE) to "SV",
    Regex("""packer""", RegexOption.IGNORE_CASE) to "Packer",
    Regex("""accountant""", RegexOption.IGNORE_CASE) to "Accts",
    Regex("""driver""", RegexOption.IGNORE_CASE) to "Driver",
    Regex("""watch\s*man""", RegexOption.IGNORE_CASE) to "Watchman"
)

fun shortDesig(full: String?, cat: String): String {
    if (cat == "OUT") return "OS"                       // Outsourced resource
    val s = cln(full)
    if (s.isEmpty()) return if (cat == "GDS") "GDS" else "Staff"
    DESIG_RULES.firstOrNull { it.first.containsMatchIn(s) }?.let { return it.second }
    val ini = s.split(Regex("""[\s\-/()]+""")).filter { it.isNotEmpty() }
        .joinToString("") { it.first().uppercase() }
    return if (ini.isEmpty()) "Staff" else ini.take(5)
}

// ── phone helpers ────────────────────────────────────────────────────────────
/** Reduce anything the roster holds (+91, 0-prefix, spaces) to 10 bare digits. */
fun tenDigit(v: String?): String {
    var d = cln(v).filter { it.isDigit() }
    if (d.length > 10 && d.startsWith("91")) d = d.takeLast(10)
    else if (d.length == 11 && d.startsWith("0")) d = d.drop(1)
    else if (d.length > 10) d = d.takeLast(10)
    return if (d.length == 10) d else ""
}

fun prettyPhone(d: String): String = if (d.length == 10) "${d.take(5)} ${d.drop(5)}" else d
fun e164(d: String): String = if (d.isEmpty()) "" else "+91$d"

/**
 * Payroll names carry a single-letter initial, leading or trailing
 * ("RAJESWARI S", "P NACHIMUTHU"). Drop them so the phonebook reads and sorts
 * on the real name. An all-initials name is kept as it is.
 */
fun stripInitials(name: String): String {
    val parts = cln(name).split(" ").filter { !Regex("""^[A-Za-z]\.?$""").matches(it) }
    return if (parts.isEmpty()) cln(name) else parts.joinToString(" ")
}

/** The saved contact name: "Name  SHORT-DESIGNATION  Office". */
fun contactName(c: Contact): String =
    listOf(stripInitials(c.name), c.short, c.office).filter { it.isNotEmpty() }.joinToString(" ")

private val BOSO = mapOf(
    "BPO" to "BO", "SPO" to "SO", "SDO" to "SO", "HPO" to "HO",
    "BPC" to "BPC", "LPC" to "LPC", "PDN" to "PDN", "WTC" to "WTC"
)

private fun outsiderName(o: OutsiderEntity): String =
    listOfNotNull(o.firstName, o.middleName, o.lastName).joinToString(" ").trim()
        .ifBlank { o.fullName.ifBlank { o.resourceId } }

/**
 * Build the full contact list from the local Room tables. [phones] is the
 * mobile_map (employeeId → number), which already carries any applied edits;
 * outsiders keep their number on their own row.
 */
fun buildContacts(
    employees: List<EmployeeEntity>,
    outsiders: List<OutsiderEntity>,
    offices: List<OfficeMasterEntity>,
    phones: Map<String, String>
): List<Contact> {
    val om = offices.associateBy { it.officeId }
    val out = ArrayList<Contact>(employees.size + outsiders.size)

    fun make(
        cat: String, id: String, name: String, designation: String,
        officeId: String, officeFallback: String?, mobile: String?,
        email: String?, pinFallback: String? = null
    ): Contact {
        val o = om[officeId]
        return Contact(
            cat = cat,
            id = id,
            name = cln(name),
            designation = cln(designation),
            short = shortDesig(designation, cat),
            officeId = officeId,
            office = o?.officeName?.let { cln(it) }
                ?: cln(officeFallback).ifEmpty { "(Office not mapped)" },
            boso = o?.officeType?.let { BOSO[cln(it)] ?: cln(it) } ?: "",
            pincode = cln(o?.pincode ?: pinFallback),
            // A BO accounts to its Sub Office; SOs and the HO account to the Head Office.
            acctOffice = o?.let {
                if (cln(it.officeType) == "BPO") cln(it.soName).ifEmpty { cln(it.hoName) }
                else cln(it.hoName).ifEmpty { cln(it.officeName) }
            } ?: "",
            mobile = tenDigit(mobile),
            email = cln(email)
        )
    }

    for (e in employees) {
        val name = cln(e.name)
        if (name.isEmpty()) continue
        val cat = if (e.type.uppercase() == "GDS") "GDS" else "DS"
        out += make(
            cat = cat, id = e.employeeId, name = name,
            designation = e.designation.orEmpty(),
            officeId = e.officeId, officeFallback = e.officeName,
            mobile = phones[e.employeeId], email = null
        )
    }
    for (o in outsiders) {
        val name = outsiderName(o)
        if (name.isEmpty()) continue
        out += make(
            cat = "OUT", id = o.resourceId, name = name,
            designation = "Outsourced Resource",
            officeId = o.officeId.orEmpty(), officeFallback = o.officeName,
            mobile = o.mobile, email = o.email, pinFallback = o.pinCode
        )
    }
    return out
}

/** Designation precedence inside an office — mirrors the web page's DRANK. */
private val DRANK = listOf(
    "SPOs", "ASP", "IP", "PRI(P)", "DO(PLI)", "PM Gr.", "Dy.PM", "SPM", "APM",
    "HSG-II", "HSG", "LSG", "Sec.Supvr", "PA", "SA", "Accts", "MO", "CO", "HPM",
    "Sorting PM", "PM", "MTS", "OA", "Driver", "Watchman",
    "BPM", "ABPM", "DS", "DA", "MC", "Packer", "SV", "OS"
)
fun desigRank(short: String): Int = DRANK.indexOf(short).let { if (it < 0) 99 else it }

/** Does this contact match a free-text query (office, name, number, designation, pincode)? */
fun Contact.matches(query: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return true
    val digits = q.filter { it.isDigit() }
    return name.lowercase().contains(q) ||
        office.lowercase().contains(q) ||
        designation.lowercase().contains(q) ||
        short.lowercase().contains(q) ||
        acctOffice.lowercase().contains(q) ||
        id.lowercase().contains(q) ||
        (digits.length >= 3 && (mobile.contains(digits) || pincode.contains(digits)))
}

// ═════════════════════════════ exports ═════════════════════════════

private fun vEsc(s: String): String =
    cln(s).replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")

/** vCard 3.0 — imports natively into Android & iPhone contacts, and Google. */
fun buildVcf(rows: List<Contact>, label: String): String {
    val sb = StringBuilder()
    for (c in rows) {
        val fn = contactName(c)
        sb.append("BEGIN:VCARD\r\n").append("VERSION:3.0\r\n")
            .append("N:").append(vEsc(fn)).append(";;;;\r\n")
            .append("FN:").append(vEsc(fn)).append("\r\n")
            .append("ORG:").append(vEsc("Department of Posts, Karur Sub Division"))
            .append(";").append(vEsc(c.office)).append("\r\n")
            .append("TITLE:").append(vEsc(c.designation.ifEmpty { ContactCat.of(c.cat).label })).append("\r\n")
            .append("TEL;TYPE=CELL,VOICE:").append(e164(c.mobile)).append("\r\n")
        if (c.email.isNotEmpty()) sb.append("EMAIL;TYPE=INTERNET:").append(vEsc(c.email)).append("\r\n")
        if (c.pincode.isNotEmpty()) {
            val locality = c.office.replace(Regex("""\s+(B\.O|S\.O|H\.O)\.?$""", RegexOption.IGNORE_CASE), "")
            sb.append("ADR;TYPE=WORK:;;").append(vEsc(c.office)).append(";")
                .append(vEsc(locality)).append(";Tamil Nadu;").append(vEsc(c.pincode)).append(";India\r\n")
        }
        sb.append("CATEGORIES:").append(vEsc(label)).append("\r\n")
        val note = listOf(
            ContactCat.of(c.cat).label, c.designation, c.office,
            if (c.boso.isNotEmpty()) "(${c.boso})" else "",
            if (c.pincode.isNotEmpty()) "PIN ${c.pincode}" else "",
            if (c.acctOffice.isNotEmpty()) "A/c Office: ${c.acctOffice}" else "",
            "ID ${c.id}"
        ).filter { it.isNotEmpty() }.joinToString(" · ")
        sb.append("NOTE:").append(vEsc(note)).append("\r\n")
        sb.append("END:VCARD\r\n")
    }
    return sb.toString()
}

private val G_HEAD = listOf(
    "First Name", "Middle Name", "Last Name", "Phonetic First Name", "Phonetic Middle Name",
    "Phonetic Last Name", "Name Prefix", "Name Suffix", "Nickname", "File As",
    "Organization Name", "Organization Title", "Organization Department", "Birthday",
    "Notes", "Photo", "Labels", "E-mail 1 - Label", "E-mail 1 - Value",
    "Phone 1 - Label", "Phone 1 - Value"
)
private fun csvCell(v: String): String =
    if (v.any { it == '"' || it == ',' || it == '\r' || it == '\n' })
        "\"" + v.replace("\"", "\"\"") + "\"" else v

/** Google Contacts CSV — the same column set Google Contacts itself exports. */
fun buildGoogleCsv(rows: List<Contact>, label: String): String {
    val sb = StringBuilder().append(G_HEAD.joinToString(",")).append("\r\n")
    for (c in rows) {
        val nm = contactName(c)
        val notes = listOf(
            ContactCat.of(c.cat).label, c.designation, c.office, c.boso,
            if (c.pincode.isNotEmpty()) "PIN ${c.pincode}" else "",
            if (c.acctOffice.isNotEmpty()) "A/c Office: ${c.acctOffice}" else ""
        ).filter { it.isNotEmpty() }.joinToString(" · ")
        sb.append(
            listOf(
                nm, "", "", "", "", "", "", "", "", nm,
                "Department of Posts — Karur Sub Division",
                c.designation.ifEmpty { ContactCat.of(c.cat).label }, c.office,
                "", notes, "",
                "* myContacts ::: $label",
                if (c.email.isNotEmpty()) "* Personal" else "", c.email,
                "* Mobile", e164(c.mobile)
            ).joinToString(",") { csvCell(it) }
        ).append("\r\n")
    }
    return sb.toString()
}

/** Writing and handing a contact file to the system. */
object ContactFiles {
    private fun today(): String = LocalDate.now().toString()

    fun fileName(scope: String, ext: String): String =
        ("Karur-SDO_Contacts_${scope}_${today()}").replace(Regex("""[^A-Za-z0-9._-]+"""), "-") + "." + ext

    /** Write [text] into cacheDir/contacts/[name] (the FileProvider-shared folder). */
    fun write(context: Context, name: String, text: String): File {
        val dir = File(context.cacheDir, "contacts").apply { mkdirs() }
        return File(dir, name).apply { writeText(text, Charsets.UTF_8) }
    }

    private fun mimeOf(file: File): String =
        if (file.extension.equals("vcf", true)) "text/x-vcard" else "text/csv"

    /** Hand the file to the Contacts app (vCard) or any viewer. */
    fun open(context: Context, file: File) = launch(context, file, Intent.ACTION_VIEW)

    /** Offer the file to any app that can take it (Drive, Gmail, Files…). */
    fun share(context: Context, file: File) = launch(context, file, Intent.ACTION_SEND)

    private fun launch(context: Context, file: File, action: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val mime = mimeOf(file)
        val intent = Intent(action).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            if (action == Intent.ACTION_SEND) {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
            } else {
                setDataAndType(uri, mime)
            }
        }
        val title = if (action == Intent.ACTION_SEND) "Share contacts" else "Import contacts"
        runCatching {
            context.startActivity(
                Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
