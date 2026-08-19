package com.karursdo.ui.people

import com.karursdo.data.db.EmployeeEntity
import com.karursdo.data.db.OutsiderEntity
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Birthday / retirement computation ported verbatim from the web tools
 * (Leave Orders/birthdays.html and retirements.html) so the mobile figures
 * always agree with the web app. The employee/outsider rows in this app are
 * already scoped to the Karur Sub Division, so no extra scope filter is needed.
 */

/** A staff/outsider birthday, resolved to their next occurrence from "today". */
data class BirthdayPerson(
    val cat: String,          // "DS" | "GDS" | "OUT"
    val name: String,
    val id: String,
    val designation: String?,
    val office: String?,
    val day: Int,
    val month: Int,           // 1-based
    val birthYear: Int,
    val next: LocalDate,      // next birthday from today (today if it's today)
    val days: Int,            // whole days until [next] (0 = today)
    val turning: Int          // age they turn on [next]
)

/** A departmental/GDS retirement (superannuation) or GDS discharge. */
data class RetirementPerson(
    val cat: String,          // "DS" | "GDS"
    val name: String,
    val id: String,
    val designation: String?,
    val office: String?,
    val dob: LocalDate?,
    val retire: LocalDate,
    val days: Int             // whole days until [retire] (negative = past)
)

/** Parse dd-mm-yyyy / dd/mm/yyyy / dd.mm.yyyy → (day, month, year), or null. */
fun parseDMY(s: String?): Triple<Int, Int, Int>? {
    val m = Regex("""^(\d{1,2})[-/.](\d{1,2})[-/.](\d{4})$""").find((s ?: "").trim()) ?: return null
    val d = m.groupValues[1].toInt()
    val mo = m.groupValues[2].toInt()
    val y = m.groupValues[3].toInt()
    if (mo !in 1..12 || d !in 1..31) return null
    return Triple(d, mo, y)
}

/** A calendar date, clamping the day to the month's length (guards Feb-29 etc.). */
private fun safeDate(year: Int, month: Int, day: Int): LocalDate {
    val ym = java.time.YearMonth.of(year, month)
    return LocalDate.of(year, month, day.coerceAtMost(ym.lengthOfMonth()))
}

/** Next occurrence of (month, day) on/after [today]. */
private fun nextBirthday(month: Int, day: Int, today: LocalDate): LocalDate {
    val thisYear = safeDate(today.year, month, day)
    return if (thisYear.isBefore(today)) safeDate(today.year + 1, month, day) else thisYear
}

/**
 * Departmental superannuation: last day of the birth month on attaining 60.
 * Born on the 1st → last day of the previous month.
 */
fun deptRetire(dob: String?): LocalDate? {
    val (d, mo, y) = parseDMY(dob) ?: return null
    val year = y + 60
    return if (d == 1) {
        var m = mo - 1; var yy = year
        if (m == 0) { m = 12; yy = year - 1 }
        java.time.YearMonth.of(yy, m).atEndOfMonth()
    } else {
        java.time.YearMonth.of(year, mo).atEndOfMonth()
    }
}

/** GDS discharge: the day before the 65th anniversary of the birth date. */
fun gdsRetire(dob: String?): LocalDate? {
    val (d, mo, y) = parseDMY(dob) ?: return null
    return safeDate(y + 65, mo, d).minusDays(1)
}

private fun outsiderName(o: OutsiderEntity): String =
    listOfNotNull(o.firstName, o.middleName, o.lastName)
        .joinToString(" ").trim().ifBlank { o.fullName.ifBlank { o.resourceId } }

/** Build the full birthday list (DS + GDS + outsiders), sorted by next occurrence. */
fun buildBirthdays(
    employees: List<EmployeeEntity>,
    outsiders: List<OutsiderEntity>,
    today: LocalDate = LocalDate.now()
): List<BirthdayPerson> {
    val out = ArrayList<BirthdayPerson>()
    fun add(cat: String, name: String, id: String, desig: String?, office: String?, dob: String?) {
        val (d, mo, y) = parseDMY(dob) ?: return
        val nb = nextBirthday(mo, d, today)
        out += BirthdayPerson(
            cat, name, id, desig, office, d, mo, y,
            next = nb,
            days = ChronoUnit.DAYS.between(today, nb).toInt(),
            turning = nb.year - y
        )
    }
    for (e in employees) add(e.type, e.name, e.employeeId, e.designation, e.officeName, e.dateOfBirth)
    for (o in outsiders) add("OUT", outsiderName(o), o.resourceId, "Outsource Resource", o.officeName, o.dateOfBirth)
    return out.sortedBy { it.next }
}

/** Build the full retirement list (DS + GDS), sorted by retirement date. */
fun buildRetirements(
    employees: List<EmployeeEntity>,
    today: LocalDate = LocalDate.now()
): List<RetirementPerson> {
    val out = ArrayList<RetirementPerson>()
    for (e in employees) {
        val retire = (if (e.type == "GDS") gdsRetire(e.dateOfBirth) else deptRetire(e.dateOfBirth)) ?: continue
        val dob = parseDMY(e.dateOfBirth)?.let { (d, mo, y) -> safeDate(y, mo, d) }
        out += RetirementPerson(
            e.type, e.name, e.employeeId, e.designation, e.officeName, dob, retire,
            days = ChronoUnit.DAYS.between(today, retire).toInt()
        )
    }
    return out.sortedBy { it.retire }
}
