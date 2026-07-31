package com.karursdo.data.ingest

/** The import types the app recognises, matching the web app's detectors. */
enum class ImportType { DS, GDS, OUT, MOBILE, ARRANGEMENT }

/** One parsed record = ordered column->value map (order preserved for pay breakdown). */
typealias Record = LinkedHashMap<String, String>

data class ParsedFile(
    val fileName: String,
    val type: ImportType,
    val records: List<Record>,
    val skippedRows: List<Int>          // 1-based sheet row numbers dropped as malformed
)

object ImportEngine {

    /** Port of the web app's detectType(headers): matched on lowercased header names. */
    fun detectType(headers: List<String>): ImportType? {
        val h = headers.map { it.trim().lowercase() }
        return when {
            h.any { it == "arrangement_type_desc" || it == "new_office_name" || it == "arrangement_from_date" } ->
                ImportType.ARRANGEMENT
            h.any { it == "basic pay" } -> ImportType.DS
            h.any { it == "trca" || it == "officiating pay" || it == "sdbs" } -> ImportType.GDS
            h.any { it == "resource_id" || it == "aadhaar_number" } ||
                (h.contains("first_name") && h.contains("last_name")) -> ImportType.OUT
            h.any { it.startsWith("ph number") || it == "name of the offices" } -> ImportType.MOBILE
            else -> null
        }
    }

    private val SKIP_COLS = setOf("concat")
    private val DATE_COLS = setOf("date_of_birth", "date_of_join")

    /** Convert a raw sheet into typed records, mirroring the web importer's cleaning. */
    fun parse(fileName: String, sheet: SheetData): ParsedFile? {
        val type = detectType(sheet.headers) ?: return null
        val records = mutableListOf<Record>()
        val skipped = mutableListOf<Int>()

        sheet.rows.forEachIndexed { i, row ->
            val rec = Record()
            sheet.headers.forEachIndexed { c, rawHeader ->
                val header = rawHeader.trim()
                if (header.isEmpty()) return@forEachIndexed
                if (type != ImportType.MOBILE && header.lowercase() in SKIP_COLS) return@forEachIndexed
                var value = row.getOrElse(c) { "" }.trim()
                if (header.lowercase() in DATE_COLS && value.isNotEmpty()) {
                    value = normalizeDate(value)
                }
                rec[header] = value
            }
            val keep = when (type) {
                ImportType.ARRANGEMENT -> rec.entries.any {
                    it.key.equals("arrangement_type_desc", true) && it.value.isNotBlank()
                }
                ImportType.MOBILE -> rec.entries.any {
                    it.key.equals("Name of the offices", true) && it.value.isNotBlank()
                }
                ImportType.OUT -> rec.entries.any {
                    (it.key.equals("resource_id", true) || it.key.equals("first_name", true)) &&
                        it.value.isNotBlank()
                }
                else -> rec.entries.any {
                    (it.key.equals("Employee_id", true) || it.key.equals("Employee_name", true)) &&
                        it.value.isNotBlank()
                }
            }
            if (keep) records += rec else skipped += i + 2   // +2: 1-based + header row
        }
        return ParsedFile(fileName, type, records, skipped)
    }

    /** dd-mm-yyyy from Excel serial numbers or ISO yyyy-mm-ddT... strings. */
    fun normalizeDate(value: String): String {
        value.toDoubleOrNull()?.let { serial ->
            if (serial > 1000) return SheetReader.formatSerialDate(serial)
        }
        Regex("^(\\d{4})-(\\d{2})-(\\d{2})").find(value)?.let { m ->
            return "${m.groupValues[3]}-${m.groupValues[2]}-${m.groupValues[1]}"
        }
        return value
    }
}

/** Port of the web app's mobile-roster office/name fuzzy matcher. */
object MobileMatcher {

    private val OFFICE_ALIAS = mapOf(
        "etpalayam" to "eastthavittupalayam",
        "ptkurichi" to "punjaithottakurichi",
        "pugalursf" to "pugalursugarfactory"
    )

    fun normOffice(name: String): String {
        var s = name.lowercase().replace(Regex("[^a-z0-9]"), "")
        s = s.replace(Regex("(so|bo|ho|sso)$"), "")
        return s
    }

    fun normPhone(raw: String): String? {
        var d = raw.replace(Regex("\\D"), "")
        if (d.length == 11 && d.startsWith("0")) d = d.substring(1)
        if (d.length == 12 && d.startsWith("91")) d = d.substring(2)
        return if (d.length == 10) d else null
    }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(
                    dp[j] + 1, dp[j - 1] + 1,
                    prev + if (a[i - 1] == b[j - 1]) 0 else 1
                )
                prev = tmp
            }
        }
        return dp[b.length]
    }

    fun officeMatches(rosterOffice: String, staffOffice: String): Boolean {
        val a = normOffice(rosterOffice).let { OFFICE_ALIAS[it] ?: it }
        val b = normOffice(staffOffice).let { OFFICE_ALIAS[it] ?: it }
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        return levenshtein(a, b) <= 2
    }

    private fun tokens(name: String): List<String> =
        name.lowercase().split(Regex("[^a-z]+")).filter { it.isNotEmpty() }

    /**
     * Token-overlap score between a roster name and a staff name; fuzzy Levenshtein<=1
     * fallback for tokens of length >= 5. Requires at least one shared token >= 4 chars.
     */
    fun nameScore(rosterName: String, staffName: String): Int {
        val rt = tokens(rosterName)
        val st = tokens(staffName)
        if (rt.isEmpty() || st.isEmpty()) return 0
        var score = 0
        var hasLongShared = false
        for (t in rt) {
            val exact = st.any { it == t }
            val fuzzy = !exact && t.length >= 5 && st.any { it.length >= 5 && levenshtein(it, t) <= 1 }
            if (exact || fuzzy) {
                score += t.length
                if (t.length >= 4) hasLongShared = true
            }
        }
        return if (hasLongShared) score else 0
    }
}
