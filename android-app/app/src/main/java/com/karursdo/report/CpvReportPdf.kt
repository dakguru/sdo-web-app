package com.karursdo.report

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.karursdo.data.repo.CpvAccount
import java.io.File
import java.io.FileOutputStream
import java.text.NumberFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Header metadata for a Cent Percent Verification report. */
data class CpvReportMeta(
    val officeName: String,
    val schemeLabel: String,
    val solId: String?,
    val branchId: String?,
    val scheme: String = ""          // "PLI"/"RPLI" switch the report to insurance columns
)

/**
 * Renders a landscape, letterhead-style Cent Percent Verification report — the filtered account
 * list with each account's verification state and remarks — and opens/shares it. Drawn directly
 * with [PdfDocument] so the result is a real file (no print dialog).
 */
object CpvReportPdf {

    // A4 landscape @ 72dpi.
    private const val PAGE_W = 842
    private const val PAGE_H = 595
    private const val MARGIN = 32f

    private const val NAVY = 0xFF0F766E.toInt()       // teal-navy to match the CPV module
    private const val NAVY_DEEP = 0xFF115E59.toInt()
    private const val INK = 0xFF0F172A.toInt()
    private const val MUTED = 0xFF5B6472.toInt()
    private const val LINE = 0xFFCBD5E1.toInt()
    private const val ROW_ALT = 0xFFF0FAF8.toInt()
    private const val GREEN = 0xFF107850.toInt()
    private const val ACCENT = 0xFF0E7490.toInt()

    private val STAMP = DateTimeFormatter.ofPattern("dd MMM yyyy, h:mm a", Locale.ENGLISH)

    // Column x-offsets from the left margin.
    private const val W_SL = 26f
    private const val W_ACCT = 92f
    private const val W_NAME = 150f
    private const val W_CIF = 64f
    private const val W_BAL = 78f
    private const val W_STATUS = 116f
    private const val W_VER = 108f
    // Remarks takes the rest.
    private const val CELL_PAD = 6f
    private const val BODY_SIZE = 8.5f
    private const val LINE_H = 11.5f

    private fun isPli(scheme: String?): Boolean {
        val s = (scheme ?: "").uppercase(); return s == "PLI" || s == "RPLI"
    }

    fun generate(
        context: Context,
        meta: CpvReportMeta,
        accounts: List<CpvAccount>
    ): File {
        if (isPli(meta.scheme)) return generatePli(context, meta, accounts)
        val serif = loadFont(context, "fonts/pala.ttf") ?: Typeface.SERIF
        val serifBold = loadFont(context, "fonts/palab.ttf")
            ?: Typeface.create(Typeface.SERIF, Typeface.BOLD)

        val money = NumberFormat.getNumberInstance(Locale("en", "IN")).apply {
            minimumFractionDigits = 2; maximumFractionDigits = 2
        }

        val doc = PdfDocument()

        val titlePaint = paint(Color.WHITE, 16f, serifBold)
        val subPaint = paint(0xFFDCEFEC.toInt(), 9.5f, serif)
        val kicker = paint(0xFFB9E4DD.toInt(), 8f, serifBold).apply { letterSpacing = 0.12f }
        val metaRight = paint(0xFFDCEFEC.toInt(), 9f, serif)
        val thHead = paint(Color.WHITE, 8.5f, serifBold)
        val tdBody = paint(INK, BODY_SIZE, serif)
        val tdBold = paint(INK, BODY_SIZE, serifBold)
        val tdGreen = paint(GREEN, BODY_SIZE, serifBold)
        val tdMuted = paint(MUTED, BODY_SIZE, serif)
        val footPaint = paint(MUTED, 8f, serif)

        val right = PAGE_W - MARGIN
        // Column boundaries.
        val xSl = MARGIN
        val xAcct = xSl + W_SL
        val xName = xAcct + W_ACCT
        val xCif = xName + W_NAME
        val xBal = xCif + W_CIF
        val xStatus = xBal + W_BAL
        val xVer = xStatus + W_STATUS
        val xRmk = xVer + W_VER
        val wRmk = right - xRmk
        val wName = W_NAME - 2 * CELL_PAD
        val wStatus = W_STATUS - 2 * CELL_PAD
        val wVer = W_VER - 2 * CELL_PAD

        val verifiedCount = accounts.count { it.verified }

        var pageNo = 0
        var page: PdfDocument.Page? = null
        var canvas: Canvas? = null
        var y = 0f

        fun footer(c: Canvas) {
            c.drawLine(MARGIN, PAGE_H - 26f, right, PAGE_H - 26f, stroke(LINE, 0.8f))
            c.drawText(
                "O/o the Assistant Superintendent of Post Offices, Karur Sub Division",
                MARGIN, PAGE_H - 14f, footPaint
            )
            val pg = "Page $pageNo"
            c.drawText(pg, right - footPaint.measureText(pg), PAGE_H - 14f, footPaint)
        }

        fun header(c: Canvas) {
            val bandH = 58f
            c.drawRect(0f, 0f, PAGE_W.toFloat(), bandH, fill(NAVY))
            // Tricolour rule.
            val t = PAGE_W / 3f
            c.drawRect(0f, bandH, t, bandH + 3f, fill(0xFFFF9933.toInt()))
            c.drawRect(t, bandH, 2 * t, bandH + 3f, fill(Color.WHITE))
            c.drawRect(2 * t, bandH, PAGE_W.toFloat(), bandH + 3f, fill(0xFF138808.toInt()))
            c.drawText("DEPARTMENT OF POSTS  ·  KARUR SUB DIVISION", MARGIN, 20f, kicker)
            c.drawText("Cent Percent Verification — ${meta.officeName}", MARGIN, 40f, titlePaint)
            val sub = buildString {
                append(meta.schemeLabel)
                meta.solId?.takeIf { it.isNotBlank() }?.let { append("   ·   SOL $it") }
                meta.branchId?.takeIf { it.isNotBlank() }?.let { append("   ·   Branch $it") }
            }
            c.drawText(sub, MARGIN, 53f, subPaint)
            val r1 = "Accounts: ${accounts.size}   ·   Verified: $verifiedCount / ${accounts.size}"
            val r2 = "Generated: ${LocalDateTime.now().format(STAMP)}"
            c.drawText(r1, right - metaRight.measureText(r1), 26f, metaRight)
            c.drawText(r2, right - metaRight.measureText(r2), 44f, metaRight)
        }

        fun tableHeader(c: Canvas, top: Float): Float {
            val h = 20f
            c.drawRect(xSl, top, right, top + h, fill(NAVY_DEEP))
            val by = top + h / 2f + (-thHead.fontMetrics.ascent - thHead.fontMetrics.descent) / 2f
            c.drawText("#", xSl + CELL_PAD, by, thHead)
            c.drawText("ACCOUNT NO.", xAcct + CELL_PAD, by, thHead)
            c.drawText("CUSTOMER", xName + CELL_PAD, by, thHead)
            c.drawText("CIF ID", xCif + CELL_PAD, by, thHead)
            c.drawText("BALANCE", xBal + CELL_PAD, by, thHead)
            c.drawText("STATUS", xStatus + CELL_PAD, by, thHead)
            c.drawText("VERIFIED", xVer + CELL_PAD, by, thHead)
            c.drawText("REMARKS", xRmk + CELL_PAD, by, thHead)
            return top + h
        }

        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
            canvas = page!!.canvas
            header(canvas!!)
            footer(canvas!!)
            y = tableHeader(canvas!!, 66f)
        }

        newPage()

        accounts.forEachIndexed { idx, a ->
            val r = a.record
            val nameLines = wrap(r.name.ifBlank { "—" }, tdBold, wName)
            val statusLines = wrap(r.status.ifBlank { "—" }, tdMuted, wStatus)
            val verText = if (a.verified) {
                buildString {
                    append("Yes")
                    if (a.verifiedBy.isNotBlank()) append(" · ${a.verifiedBy}")
                    if (a.verifiedAtMs != null) append(" · ${fmtDate(a.verifiedAtMs)}")
                }
            } else "—"
            val verLines = wrap(verText, if (a.verified) tdGreen else tdMuted, wVer)
            val rmkLines = wrap(a.remarks.ifBlank { "—" }, tdBody, wRmk - 2 * CELL_PAD)
            val lines = maxOf(nameLines.size, statusLines.size, verLines.size, rmkLines.size, 1)
            val rowH = lines * LINE_H + 6f

            if (y + rowH > PAGE_H - 32f) newPage()
            val c = canvas!!

            if (idx % 2 == 1) c.drawRect(xSl, y, right, y + rowH, fill(ROW_ALT))

            val baseY = y + LINE_H
            c.drawText("${idx + 1}", xSl + CELL_PAD, baseY, tdMuted)
            c.drawText(r.acct, xAcct + CELL_PAD, baseY, tdBold)
            nameLines.forEachIndexed { i, ln -> c.drawText(ln, xName + CELL_PAD, baseY + i * LINE_H, tdBold) }
            c.drawText(r.cif.ifBlank { "—" }, xCif + CELL_PAD, baseY, tdMuted)
            val bal = r.balance?.let { money.format(it) } ?: "—"
            c.drawText(bal, xBal + W_BAL - CELL_PAD - tdBody.measureText(bal), baseY, tdBody)
            statusLines.forEachIndexed { i, ln -> c.drawText(ln, xStatus + CELL_PAD, baseY + i * LINE_H, tdMuted) }
            verLines.forEachIndexed { i, ln -> c.drawText(ln, xVer + CELL_PAD, baseY + i * LINE_H, if (a.verified) tdGreen else tdMuted) }
            rmkLines.forEachIndexed { i, ln -> c.drawText(ln, xRmk + CELL_PAD, baseY + i * LINE_H, tdBody) }

            y += rowH
            c.drawLine(xSl, y, right, y, stroke(LINE, 0.5f))
        }

        page?.let { doc.finishPage(it) }

        val dir = File(context.cacheDir, "reports").apply { mkdirs() }
        val safe = "CPV_${meta.officeName}_${meta.schemeLabel}"
            .replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').take(60)
        val file = File(dir, "$safe.pdf")
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
        return file
    }

    // ── PLI / RPLI report — insurance columns: # · Policy · Insured · Sum Assured ·
    //    Premium · Entry · Paid upto · Months · Verified · Remarks ──
    private fun generatePli(
        context: Context,
        meta: CpvReportMeta,
        accounts: List<CpvAccount>
    ): File {
        val serif = loadFont(context, "fonts/pala.ttf") ?: Typeface.SERIF
        val serifBold = loadFont(context, "fonts/palab.ttf")
            ?: Typeface.create(Typeface.SERIF, Typeface.BOLD)
        val money = NumberFormat.getNumberInstance(Locale("en", "IN")).apply {
            minimumFractionDigits = 2; maximumFractionDigits = 2
        }

        val doc = PdfDocument()
        val titlePaint = paint(Color.WHITE, 16f, serifBold)
        val subPaint = paint(0xFFDCEFEC.toInt(), 9.5f, serif)
        val kicker = paint(0xFFB9E4DD.toInt(), 8f, serifBold).apply { letterSpacing = 0.12f }
        val metaRight = paint(0xFFDCEFEC.toInt(), 9f, serif)
        val thHead = paint(Color.WHITE, 8.5f, serifBold)
        val tdBody = paint(INK, BODY_SIZE, serif)
        val tdBold = paint(INK, BODY_SIZE, serifBold)
        val tdGreen = paint(GREEN, BODY_SIZE, serifBold)
        val tdMuted = paint(MUTED, BODY_SIZE, serif)
        val footPaint = paint(MUTED, 8f, serif)

        val right = PAGE_W - MARGIN
        // Column widths (usable width ≈ 778pt).
        val wSl = 26f; val wPol = 96f; val wName = 150f; val wSa = 72f; val wPrem = 58f
        val wDoe = 64f; val wPaid = 58f; val wMon = 40f; val wVer = 100f
        val xSl = MARGIN
        val xPol = xSl + wSl
        val xName = xPol + wPol
        val xSa = xName + wName
        val xPrem = xSa + wSa
        val xDoe = xPrem + wPrem
        val xPaid = xDoe + wDoe
        val xMon = xPaid + wPaid
        val xVer = xMon + wMon
        val xRmk = xVer + wVer
        val wRmk = right - xRmk
        val nameW = wName - 2 * CELL_PAD
        val verW = wVer - 2 * CELL_PAD

        val verifiedCount = accounts.count { it.verified }
        var sumSA = 0.0; var sumPrem = 0.0

        var pageNo = 0
        var page: PdfDocument.Page? = null
        var canvas: Canvas? = null
        var y = 0f

        fun footer(c: Canvas) {
            c.drawLine(MARGIN, PAGE_H - 26f, right, PAGE_H - 26f, stroke(LINE, 0.8f))
            c.drawText("O/o the Assistant Superintendent of Post Offices, Karur Sub Division", MARGIN, PAGE_H - 14f, footPaint)
            val pg = "Page $pageNo"
            c.drawText(pg, right - footPaint.measureText(pg), PAGE_H - 14f, footPaint)
        }
        fun header(c: Canvas) {
            val bandH = 58f
            c.drawRect(0f, 0f, PAGE_W.toFloat(), bandH, fill(NAVY))
            val t = PAGE_W / 3f
            c.drawRect(0f, bandH, t, bandH + 3f, fill(0xFFFF9933.toInt()))
            c.drawRect(t, bandH, 2 * t, bandH + 3f, fill(Color.WHITE))
            c.drawRect(2 * t, bandH, PAGE_W.toFloat(), bandH + 3f, fill(0xFF138808.toInt()))
            c.drawText("DEPARTMENT OF POSTS  ·  KARUR SUB DIVISION", MARGIN, 20f, kicker)
            c.drawText("Cent Percent Verification — ${meta.officeName}", MARGIN, 40f, titlePaint)
            val sub = buildString {
                append(meta.schemeLabel)
                meta.solId?.takeIf { it.isNotBlank() }?.let { append("   ·   SOL $it") }
                meta.branchId?.takeIf { it.isNotBlank() }?.let { append("   ·   Branch $it") }
            }
            c.drawText(sub, MARGIN, 53f, subPaint)
            val r1 = "Policies: ${accounts.size}   ·   Verified: $verifiedCount / ${accounts.size}"
            val r2 = "Generated: ${LocalDateTime.now().format(STAMP)}"
            c.drawText(r1, right - metaRight.measureText(r1), 26f, metaRight)
            c.drawText(r2, right - metaRight.measureText(r2), 44f, metaRight)
        }
        fun tableHeader(c: Canvas, top: Float): Float {
            val h = 20f
            c.drawRect(xSl, top, right, top + h, fill(NAVY_DEEP))
            val by = top + h / 2f + (-thHead.fontMetrics.ascent - thHead.fontMetrics.descent) / 2f
            c.drawText("#", xSl + CELL_PAD, by, thHead)
            c.drawText("POLICY NO.", xPol + CELL_PAD, by, thHead)
            c.drawText("INSURED NAME", xName + CELL_PAD, by, thHead)
            c.drawText("SUM ASSURED", xSa + CELL_PAD, by, thHead)
            c.drawText("PREMIUM", xPrem + CELL_PAD, by, thHead)
            c.drawText("ENTRY", xDoe + CELL_PAD, by, thHead)
            c.drawText("PAID UPTO", xPaid + CELL_PAD, by, thHead)
            c.drawText("MONTHS", xMon + CELL_PAD, by, thHead)
            c.drawText("VERIFIED", xVer + CELL_PAD, by, thHead)
            c.drawText("REMARKS", xRmk + CELL_PAD, by, thHead)
            return top + h
        }
        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
            canvas = page!!.canvas
            header(canvas!!); footer(canvas!!)
            y = tableHeader(canvas!!, 66f)
        }
        newPage()

        accounts.forEachIndexed { idx, a ->
            val r = a.record
            r.sumAssured?.let { sumSA += it }
            r.premium?.let { sumPrem += it }
            val nameLines = wrap(r.name.ifBlank { "—" }, tdBold, nameW)
            val verText = if (a.verified) buildString {
                append("Yes")
                if (a.verifiedBy.isNotBlank()) append(" · ${a.verifiedBy}")
                if (a.verifiedAtMs != null) append(" · ${fmtDate(a.verifiedAtMs)}")
            } else "—"
            val verLines = wrap(verText, if (a.verified) tdGreen else tdMuted, verW)
            val rmkLines = wrap(a.remarks.ifBlank { "—" }, tdBody, wRmk - 2 * CELL_PAD)
            val lines = maxOf(nameLines.size, verLines.size, rmkLines.size, 1)
            val rowH = lines * LINE_H + 6f

            if (y + rowH > PAGE_H - 32f) newPage()
            val c = canvas!!
            if (idx % 2 == 1) c.drawRect(xSl, y, right, y + rowH, fill(ROW_ALT))

            val baseY = y + LINE_H
            c.drawText("${idx + 1}", xSl + CELL_PAD, baseY, tdMuted)
            c.drawText(r.policy.ifBlank { r.acct }, xPol + CELL_PAD, baseY, tdBold)
            nameLines.forEachIndexed { i, ln -> c.drawText(ln, xName + CELL_PAD, baseY + i * LINE_H, tdBold) }
            val sa = r.sumAssured?.let { money.format(it) } ?: "—"
            c.drawText(sa, xSa + wSa - CELL_PAD - tdBody.measureText(sa), baseY, tdBody)
            val prem = r.premium?.let { money.format(it) } ?: "—"
            c.drawText(prem, xPrem + wPrem - CELL_PAD - tdBody.measureText(prem), baseY, tdBody)
            c.drawText(fmtTxnIso(r.doeIso, r.doeRaw), xDoe + CELL_PAD, baseY, tdMuted)
            c.drawText(monYearIso(r.paidIso, r.paidUpto, r.paidRaw), xPaid + CELL_PAD, baseY, tdMuted)
            val mon = r.monthsPaid?.toString() ?: "—"
            c.drawText(mon, xMon + wMon - CELL_PAD - tdBody.measureText(mon), baseY, tdBody)
            verLines.forEachIndexed { i, ln -> c.drawText(ln, xVer + CELL_PAD, baseY + i * LINE_H, if (a.verified) tdGreen else tdMuted) }
            rmkLines.forEachIndexed { i, ln -> c.drawText(ln, xRmk + CELL_PAD, baseY + i * LINE_H, tdBody) }

            y += rowH
            c.drawLine(xSl, y, right, y, stroke(LINE, 0.5f))
        }
        // Totals line.
        run {
            val c = canvas!!
            if (y + LINE_H + 8f > PAGE_H - 32f) newPage()
            val label = "Total for ${accounts.size} policy(ies):  Sum Assured ₹ ${money.format(sumSA)}   ·   Premium ₹ ${money.format(sumPrem)}"
            c.drawText(label, right - tdBold.measureText(label), y + LINE_H, tdGreen)
        }

        page?.let { doc.finishPage(it) }
        val dir = File(context.cacheDir, "reports").apply { mkdirs() }
        val safe = "CPV_${meta.officeName}_${meta.schemeLabel}"
            .replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').take(60)
        val file = File(dir, "$safe.pdf")
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
        return file
    }

    /** dd-MM-yyyy from an iso yyyy-mm-dd, else the raw text. */
    private fun fmtTxnIso(iso: String, raw: String): String {
        val m = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$").find(iso)
        return if (m != null) "${m.groupValues[3]}-${m.groupValues[2]}-${m.groupValues[1]}" else raw
    }
    private val MON_ABBR = arrayOf("Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec")
    /** MMM-YYYY from an iso yyyy-mm-dd, preferring a stored label, else raw. */
    private fun monYearIso(iso: String, label: String, raw: String): String {
        if (label.isNotBlank()) return label
        val m = Regex("^(\\d{4})-(\\d{2})").find(iso)
        return if (m != null) "${MON_ABBR[m.groupValues[2].toInt() - 1]}-${m.groupValues[1]}" else raw
    }

    private fun fmtDate(ms: Long): String {
        val d = java.util.Date(ms)
        return java.text.SimpleDateFormat("dd-MM-yyyy", Locale.ENGLISH).format(d)
    }

    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (maxWidth <= 0f) return listOf(text)
        val out = ArrayList<String>()
        var line = StringBuilder()
        for (word in text.split(" ")) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(candidate) <= maxWidth) {
                line = StringBuilder(candidate)
            } else {
                if (line.isNotEmpty()) { out.add(line.toString()); line = StringBuilder() }
                if (paint.measureText(word) > maxWidth) {
                    var chunk = StringBuilder()
                    for (ch in word) {
                        if (paint.measureText(chunk.toString() + ch) > maxWidth && chunk.isNotEmpty()) {
                            out.add(chunk.toString()); chunk = StringBuilder()
                        }
                        chunk.append(ch)
                    }
                    line = chunk
                } else line = StringBuilder(word)
            }
        }
        if (line.isNotEmpty()) out.add(line.toString())
        return if (out.isEmpty()) listOf("") else out.take(3)   // cap wrapping to keep rows compact
    }

    fun open(context: Context, file: File) = launch(context, file, Intent.ACTION_VIEW)
    fun share(context: Context, file: File) = launch(context, file, Intent.ACTION_SEND)

    private fun launch(context: Context, file: File, action: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(action).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            if (action == Intent.ACTION_SEND) {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
            } else {
                setDataAndType(uri, "application/pdf")
            }
        }
        val title = if (action == Intent.ACTION_SEND) "Share report" else "Open report"
        runCatching {
            context.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun loadFont(context: Context, asset: String): Typeface? =
        runCatching { Typeface.createFromAsset(context.assets, asset) }.getOrNull()

    private fun paint(color: Int, size: Float, tf: Typeface) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; textSize = size; typeface = tf
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.FILL
    }

    private fun stroke(color: Int, w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeWidth = w
    }
}
