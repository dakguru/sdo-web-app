package com.karursdo.data.repo

import android.content.Context
import android.net.Uri
import com.karursdo.data.db.ArrangementDao
import com.karursdo.data.db.DatasetSnapshotDao
import com.karursdo.data.db.DatasetSnapshotEntity
import com.karursdo.data.db.EmployeeDao
import com.karursdo.data.db.ImportMetaDao
import com.karursdo.data.db.ImportMetaEntity
import com.karursdo.data.db.MoBeatDao
import com.karursdo.data.db.MoProgrammeDao
import com.karursdo.data.db.MoProgrammeEntity
import com.karursdo.data.db.EmployeeEntity
import com.karursdo.data.db.MobileDao
import com.karursdo.data.db.MobileMapEntity
import com.karursdo.data.db.OfficeEditDao
import com.karursdo.data.db.OfficeEditEntity
import com.karursdo.data.db.OfficeMasterDao
import com.karursdo.data.db.OutsiderDao
import com.karursdo.data.db.PhoneEditDao
import com.karursdo.data.db.PhoneEditEntity
import com.karursdo.data.db.StaffEditDao
import com.karursdo.data.db.StaffEditEntity
import com.karursdo.data.ingest.ImportEngine
import com.karursdo.data.ingest.ImportType
import com.karursdo.data.ingest.MobileMatcher
import com.karursdo.data.ingest.ParsedFile
import com.karursdo.data.ingest.RecordMappers
import com.karursdo.data.ingest.SheetReader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class FileImportResult(
    val fileName: String,
    val type: ImportType?,
    val added: Int,
    val updated: Int,
    val skipped: Int,
    val matched: Int = 0,      // MOBILE only: rows matched to staff
    val totalRows: Int = 0,
    val error: String? = null
)

@Singleton
class DirectoryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    val employeeDao: EmployeeDao,
    val outsiderDao: OutsiderDao,
    val mobileDao: MobileDao,
    val officeMasterDao: OfficeMasterDao,
    val arrangementDao: ArrangementDao,
    val importMetaDao: ImportMetaDao,
    val moBeatDao: MoBeatDao,
    val moProgrammeDao: MoProgrammeDao,
    val phoneEditDao: PhoneEditDao,
    val officeEditDao: OfficeEditDao,
    val staffEditDao: StaffEditDao,
    val datasetDao: DatasetSnapshotDao,
    private val session: SessionManager
) {
    private val snapshotJson = kotlinx.serialization.json.Json { encodeDefaults = true }

    /** Stamp a whole-set snapshot as sync-pending so this upload reflects to every user. */
    private suspend inline fun <reified T> saveSnapshot(type: String, rows: List<T>) {
        datasetDao.upsert(
            DatasetSnapshotEntity(
                type = type,
                payloadJson = snapshotJson.encodeToString(rows),
                count = rows.size,
                uploadedBy = session.authorName(),
                uploadedAt = System.currentTimeMillis(),
                syncState = "P"
            )
        )
    }

    /** Parse one picked file off the main thread. Returns null when type is undetectable. */
    suspend fun parseFile(uri: Uri, displayName: String): ParsedFile? = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            ImportEngine.parse(displayName, SheetReader.read(displayName, input))
        }
    }

    /**
     * Apply parsed files. Merge/upsert by (type, id) — or, with [replaceWholeSet],
     * remove records of an uploaded type that are absent from the upload.
     */
    suspend fun applyImport(files: List<ParsedFile>, replaceWholeSet: Boolean): List<FileImportResult> =
        withContext(Dispatchers.IO) {
            val results = mutableListOf<FileImportResult>()
            val today = LocalDate.now().toString()

            // Accumulate multi-file uploads per type (session de-dup by id, like the web app).
            val byType = files.groupBy { it.type }

            for ((type, group) in byType) {
                val joinedNames = group.joinToString(" + ") { it.fileName }
                when (type) {
                    ImportType.DS, ImportType.GDS -> {
                        val seen = LinkedHashMap<String, com.karursdo.data.db.EmployeeEntity>()
                        var skipped = 0
                        for (f in group) {
                            skipped += f.skippedRows.size
                            for (rec in f.records) {
                                RecordMappers.toEmployee(type, rec)?.let { seen[it.employeeId] = it }
                                    ?: skipped++
                            }
                        }
                        val typeKey = if (type == ImportType.DS) "DS" else "GDS"
                        val existingCount = seen.keys.count {
                            employeeDao.byId(typeKey, it) != null
                        }
                        if (replaceWholeSet) employeeDao.deleteMissing(typeKey, seen.keys.toList())
                        employeeDao.upsertAll(seen.values.toList())
                        // Re-apply manual additions / exit marks so they survive this re-import.
                        reapplyStaffEdits(typeKey)
                        saveSnapshot(typeKey, seen.values.toList())
                        importMetaDao.upsert(
                            ImportMetaEntity(typeKey, joinedNames, seen.size, today)
                        )
                        results += FileImportResult(
                            joinedNames, type,
                            added = seen.size - existingCount,
                            updated = existingCount,
                            skipped = skipped, totalRows = seen.size
                        )
                    }
                    ImportType.OUT -> {
                        val seen = LinkedHashMap<String, com.karursdo.data.db.OutsiderEntity>()
                        var skipped = 0
                        for (f in group) {
                            skipped += f.skippedRows.size
                            for (rec in f.records) {
                                RecordMappers.toOutsider(rec)?.let { seen[it.resourceId] = it }
                                    ?: skipped++
                            }
                        }
                        val existingCount = seen.keys.count { outsiderDao.byId(it) != null }
                        if (replaceWholeSet) outsiderDao.deleteMissing(seen.keys.toList())
                        outsiderDao.upsertAll(seen.values.toList())
                        saveSnapshot("OUT", seen.values.toList())
                        importMetaDao.upsert(ImportMetaEntity("OUT", joinedNames, seen.size, today))
                        results += FileImportResult(
                            joinedNames, type,
                            added = seen.size - existingCount,
                            updated = existingCount,
                            skipped = skipped, totalRows = seen.size
                        )
                    }
                    ImportType.MOBILE -> {
                        var matched = 0
                        var total = 0
                        val rosterRows = group.flatMap { it.records }.map { RecordMappers.toRosterRow(it) }
                        mobileDao.clearRoster()
                        mobileDao.insertRoster(rosterRows)
                        val allStaff = employeeDao.allList()
                        val mappings = mutableListOf<MobileMapEntity>()
                        for (row in rosterRows) {
                            val phone = row.phone?.let { MobileMatcher.normPhone(it) } ?: continue
                            total++
                            val office = row.officeName ?: continue
                            if (row.staffName?.contains("o/s", ignoreCase = true) == true) continue
                            val inOffice = allStaff.filter {
                                MobileMatcher.officeMatches(office, it.officeName ?: "")
                            }
                            val best = inOffice.maxByOrNull {
                                MobileMatcher.nameScore(row.staffName ?: "", it.name)
                            }
                            if (best != null &&
                                MobileMatcher.nameScore(row.staffName ?: "", best.name) > 0
                            ) {
                                mappings += MobileMapEntity(best.employeeId, phone)
                                matched++
                            }
                        }
                        mobileDao.upsertMap(mappings)
                        saveSnapshot("TEL", mappings)
                        importMetaDao.upsert(ImportMetaEntity("TEL", joinedNames, mappings.size, today))
                        results += FileImportResult(
                            joinedNames, type, added = mappings.size, updated = 0,
                            skipped = total - matched, matched = matched, totalRows = total
                        )
                    }
                    ImportType.ARRANGEMENT -> {
                        // Karur Sub Division offices only (match on new_office_id).
                        val karurIds = officeMasterDao.allList()
                            .filter { (it.subDivision ?: "").equals("Karur", true) }
                            .map { it.officeId }.toHashSet()
                        val all = mutableListOf<com.karursdo.data.db.ArrangementEntity>()
                        var skipped = 0
                        for (f in group) {
                            skipped += f.skippedRows.size
                            for (rec in f.records) {
                                RecordMappers.toArrangement(rec)?.let { all += it } ?: skipped++
                            }
                        }
                        // Keep only the three tracked categories that fall in Karur Sub Division.
                        // If the office master isn't loaded yet, don't drop everything — keep all.
                        val list = if (karurIds.isEmpty()) all
                        else all.filter { it.officeId != null && it.officeId in karurIds }
                        // Re-upload always replaces the whole set: the loaded arrangements then
                        // persist untouched until the next upload, and sync to every user.
                        arrangementDao.replaceAll(list)
                        saveSnapshot("ARR", list)
                        importMetaDao.upsert(ImportMetaEntity("ARR", joinedNames, list.size, today))
                        results += FileImportResult(
                            joinedNames, type, added = list.size, updated = 0,
                            skipped = skipped + (all.size - list.size), totalRows = list.size
                        )
                    }
                    null -> {}
                }
            }
            results
        }

    suspend fun setEmployeePhone(employeeId: String, phone: String) = withContext(Dispatchers.IO) {
        if (phone.isBlank()) mobileDao.deleteMap(employeeId)
        else mobileDao.upsert(MobileMapEntity(employeeId, phone))
        recordPhoneEdit(PHONE_EMPLOYEE, employeeId, phone)
    }

    suspend fun setOutsiderPhone(resourceId: String, phone: String) = withContext(Dispatchers.IO) {
        outsiderDao.updateMobile(resourceId, phone)
        recordPhoneEdit(PHONE_OUTSIDER, resourceId, phone)
    }

    /** Stamp a manual phone edit as sync-pending so it propagates to every user. */
    private suspend fun recordPhoneEdit(type: String, id: String, phone: String) {
        phoneEditDao.upsert(
            PhoneEditEntity(type, id, phone, session.authorName(), System.currentTimeMillis(), "P")
        )
    }

    // ---- Office working days/hours (synced overlay on the office master) ----

    /** Reactive office master row (reflects working-hours edits). */
    fun officeFlow(officeId: String) = officeMasterDao.flowById(officeId)

    /**
     * Save an office's working days/hours: apply it onto the office master and stamp a
     * sync-pending override so the change reflects to every user (last-write-wins).
     */
    suspend fun setOfficeHours(officeId: String, days: String?, from: String?, to: String?) =
        withContext(Dispatchers.IO) {
            val d = days?.trim().orEmpty().ifBlank { null }
            val f = from?.trim().orEmpty().ifBlank { null }
            val t = to?.trim().orEmpty().ifBlank { null }
            officeMasterDao.updateWorkingHours(officeId, d, f, t)
            officeEditDao.upsert(
                OfficeEditEntity(officeId, d, f, t, session.authorName(), System.currentTimeMillis(), "P")
            )
        }

    // ---- Manual staff additions + exit details (synced overlay on the directory) ----

    /** Overlay rows (exit marks / manual additions) for an office — drives the staff cards. */
    fun staffEditsForOffice(officeId: String) = staffEditDao.byOffice(officeId)

    /** Materialise the added/exit overlays for one staff type back onto the employees table. */
    private suspend fun reapplyStaffEdits(type: String) {
        staffEditDao.allList().filter { it.type == type }.forEach { e ->
            if (e.added) {
                val existing = employeeDao.byId(e.type, e.employeeId)
                employeeDao.upsert(
                    (existing ?: EmployeeEntity(
                        type = e.type, employeeId = e.employeeId, payrollId = null, ddoOfficeId = null,
                        name = e.name.orEmpty(), gender = e.gender, level = null, postId = null,
                        designation = e.designation, estKey = null, estDescription = null,
                        officeId = e.officeId.orEmpty(), officeName = e.officeName, pan = null,
                        dateOfBirth = e.dateOfBirth, dateOfJoin = e.dateOfJoin, bankAccNo = null,
                        ifsc = null, bankType = null, status = e.status ?: "Working",
                        totalEarnings = null, totalDeductions = null, totalThirdparty = null,
                        netPayable = null, payloadJson = "{}"
                    ))
                )
                if (!e.mobile.isNullOrBlank()) mobileDao.upsert(MobileMapEntity(e.employeeId, e.mobile))
            }
            if (!e.status.isNullOrBlank()) employeeDao.setStatus(e.type, e.employeeId, e.status)
        }
    }

    /** Add a staff member manually; materialise into the directory and stamp a sync-pending overlay. */
    suspend fun addStaff(
        type: String, employeeId: String, name: String, designation: String?,
        officeId: String, officeName: String?, gender: String?,
        dateOfBirth: String?, dateOfJoin: String?, mobile: String?
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val edit = StaffEditEntity(
            type = type, employeeId = employeeId, added = true, name = name, designation = designation,
            officeId = officeId, officeName = officeName, gender = gender, dateOfBirth = dateOfBirth,
            dateOfJoin = dateOfJoin, mobile = mobile?.ifBlank { null }, exitDate = null, exitReason = null,
            status = "Working", updatedBy = session.authorName(), updatedAt = now, syncState = "P"
        )
        staffEditDao.upsert(edit)
        employeeDao.upsert(
            EmployeeEntity(
                type = type, employeeId = employeeId, payrollId = null, ddoOfficeId = null,
                name = name, gender = gender, level = null, postId = null, designation = designation,
                estKey = null, estDescription = null, officeId = officeId, officeName = officeName,
                pan = null, dateOfBirth = dateOfBirth, dateOfJoin = dateOfJoin, bankAccNo = null,
                ifsc = null, bankType = null, status = "Working", totalEarnings = null,
                totalDeductions = null, totalThirdparty = null, netPayable = null, payloadJson = "{}"
            )
        )
        if (!mobile.isNullOrBlank()) {
            mobileDao.upsert(MobileMapEntity(employeeId, mobile))
            recordPhoneEdit(PHONE_EMPLOYEE, employeeId, mobile)
        }
    }

    /**
     * Record exit / retirement details for an existing staff member and reflect the exit status
     * on the directory row. Stamps a sync-pending overlay so it reaches every user.
     */
    suspend fun setStaffExit(
        type: String, employeeId: String, exitDate: String?, exitReason: String?
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val emp = employeeDao.byId(type, employeeId)
        val existing = staffEditDao.byId(type, employeeId)
        val cleared = exitDate.isNullOrBlank() && exitReason.isNullOrBlank()
        val status = if (cleared) "Working" else "Exited" + (exitDate?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
        staffEditDao.upsert(
            StaffEditEntity(
                type = type, employeeId = employeeId, added = existing?.added ?: false,
                name = existing?.name ?: emp?.name, designation = existing?.designation ?: emp?.designation,
                officeId = existing?.officeId ?: emp?.officeId, officeName = existing?.officeName ?: emp?.officeName,
                gender = existing?.gender ?: emp?.gender, dateOfBirth = existing?.dateOfBirth ?: emp?.dateOfBirth,
                dateOfJoin = existing?.dateOfJoin ?: emp?.dateOfJoin, mobile = existing?.mobile,
                exitDate = exitDate?.ifBlank { null }, exitReason = exitReason?.ifBlank { null },
                status = status, updatedBy = session.authorName(), updatedAt = now, syncState = "P"
            )
        )
        employeeDao.setStatus(type, employeeId, status)
    }

    /** Add one ISO visit date (yyyy-MM-dd) to an MO beat office, kept sorted & de-duped. */
    suspend fun addMoVisit(id: Long, isoDate: String) = withContext(Dispatchers.IO) {
        val row = moBeatDao.byId(id) ?: return@withContext
        val dates = (row.visits.split(",").map { it.trim() }.filter { it.isNotEmpty() } + isoDate)
            .distinct().sorted()
        moBeatDao.updateVisitsWithProvenance(
            id, dates.joinToString(","), session.authorName(), System.currentTimeMillis()
        )
    }

    /** Remove one ISO visit date from an MO beat office. */
    suspend fun removeMoVisit(id: Long, isoDate: String) = withContext(Dispatchers.IO) {
        val row = moBeatDao.byId(id) ?: return@withContext
        val dates = row.visits.split(",").map { it.trim() }
            .filter { it.isNotEmpty() && it != isoDate }.distinct().sorted()
        moBeatDao.updateVisitsWithProvenance(
            id, dates.joinToString(","), session.authorName(), System.currentTimeMillis()
        )
    }

    // ---- Mail Overseer tour programmes ----
    /** Upcoming programme entries for a beat (today onward). */
    fun programmesForBeat(beat: String, fromDate: String = LocalDate.now().toString()) =
        moProgrammeDao.upcomingForBeat(beat, fromDate)

    /** All programme entries (both beats) for a given date. */
    fun programmesForDate(date: String) = moProgrammeDao.byDate(date)

    /** Add a programme entry for one or more dates; one row per date, stamped with the author. */
    suspend fun addProgramme(beat: String, dates: List<String>, details: String) =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val author = session.authorName()
            dates.distinct().forEach { d ->
                moProgrammeDao.upsert(
                    MoProgrammeEntity(
                        id = UUID.randomUUID().toString(),
                        beat = beat, date = d, details = details,
                        author = author, createdAt = now, updatedAt = now,
                        deleted = false, syncState = "P"
                    )
                )
            }
        }

    suspend fun deleteProgramme(id: String) = withContext(Dispatchers.IO) {
        moProgrammeDao.softDelete(id, System.currentTimeMillis())
    }

    /** Edit a previously entered programme (date and/or details). */
    suspend fun editProgramme(id: String, date: String, details: String) = withContext(Dispatchers.IO) {
        moProgrammeDao.edit(id, date, details, System.currentTimeMillis())
    }

    /** Recent history + upcoming programmes for a beat (from [fromDate] onward), for editing. */
    fun programmeHistoryForBeat(beat: String, fromDate: String) =
        moProgrammeDao.historyForBeat(beat, fromDate)

    /** Date-wise programmes for a beat within an inclusive [from]..[to] range, for the PDF report. */
    suspend fun programmesInRange(beat: String, from: String, to: String) =
        withContext(Dispatchers.IO) { moProgrammeDao.rangeForBeat(beat, from, to) }

    /** Wipe all data (the "clear data" security action). */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        employeeDao.deleteByType("DS")
        employeeDao.deleteByType("GDS")
        outsiderDao.deleteMissing(emptyList())
        mobileDao.clearRoster()
        officeMasterDao.clear()
        arrangementDao.clear()
    }

    companion object {
        const val PHONE_EMPLOYEE = "EMPLOYEE"
        const val PHONE_OUTSIDER = "OUTSIDER"
    }
}
