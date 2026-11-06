package com.karursdo.report

import android.content.Context
import com.karursdo.data.repo.CpvConsRow
import java.io.File
import java.text.NumberFormat
import java.util.Locale

/**
 * The consolidated Cent-Percent-Verification lists as .xlsx — the same columns, in the same
 * order, as the web app's consolidated download, so the two are interchangeable.
 *
 * For anything longer than a few hundred rows this is the format to reach for: the PDF is for
 * reading and signing, the spreadsheet is for working through.
 */
object CpvReportXlsx {

    private val HEAD = listOf(
        "Sl.No", "Scheme", "Account / Policy No", "Name", "Address", "Type",
        "Balance / Sum Assured ₹", "Last Txn / Entry", "Status"
    )
    private val VER_COLS = listOf("Verified By", "Verified On", "Remarks")

    private fun head(withOffice: Boolean, verified: Boolean): List<String> {
        val base = if (withOffice) listOf(HEAD[0], "Office") + HEAD.drop(1) else HEAD
        return if (verified) base + VER_COLS else base
    }

    private fun money(v: Double?): String {
        if (v == null) return ""
        val f = NumberFormat.getNumberInstance(Locale("en", "IN")).apply {
            minimumFractionDigits = 2; maximumFractionDigits = 2
        }
        return f.format(v)
    }

    /** dd-MM-yyyy, matching the web's fmtVerAt. */
    private fun stamp(ms: Long?): String {
        if (ms == null || ms <= 0L) return ""
        val c = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        return "%02d-%02d-%04d".format(
            c.get(java.util.Calendar.DAY_OF_MONTH), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.YEAR)
        )
    }

    /**
     * Build the sheet for one [kind] ("Verified" / "Unverified") of [rows]. [scopeName] names
     * the office, or the division when [withOffice] is set and each row carries its own office.
     */
    fun generateConsolidated(
        context: Context,
        scopeName: String,
        kind: String,
        rows: List<CpvConsRow>,
        withOffice: Boolean
    ): File {
        val verified = kind.equals("Verified", true)
        val head = head(withOffice, verified)
        val body = ArrayList<List<String>>(rows.size)
        rows.forEachIndexed { i, r ->
            val cells = ArrayList<String>(head.size)
            cells.add((i + 1).toString())
            if (withOffice) cells.add(r.office)
            cells.add(r.scheme)
            cells.add(r.acct)
            cells.add(r.name)
            cells.add(r.address)
            cells.add(r.type)
            cells.add(money(r.balance))
            cells.add(r.date)
            cells.add(r.status)
            if (verified) {
                cells.add(r.verifiedBy)
                cells.add(stamp(r.verifiedAtMs))
                cells.add(r.remarks)
            }
            body.add(cells)
        }
        val dir = File(context.cacheDir, "reports").apply { mkdirs() }
        val safe = "CPV_${kind}_$scopeName".replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').take(60)
        return XlsxWriter.write(File(dir, "$safe.xlsx"), head, body)
    }
}
