package com.karursdo.data.repo

import android.content.Context
import com.karursdo.data.db.EmployeeEntity
import com.karursdo.data.db.ImportMetaEntity
import com.karursdo.data.db.MoBeatOfficeEntity
import com.karursdo.data.db.MobileMapEntity
import com.karursdo.data.db.OfficeMasterEntity
import com.karursdo.data.db.OutsiderEntity
import com.karursdo.data.ingest.ImportType
import com.karursdo.data.ingest.MobileMatcher
import com.karursdo.data.ingest.Record
import com.karursdo.data.ingest.RecordMappers
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * First-run loader: pre-integrates the REAL Karur Sub Division datasets bundled in
 * assets/data/ (exports of the web tool's employees.json, office_master.json and
 * mobiles.json). Runs once on an empty database; monthly Excel imports then upsert
 * on top of this baseline exactly as they do in the web app.
 */
@Singleton
class SeedLoader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: DirectoryRepository
) {

    suspend fun seedIfEmpty() = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("seed_prefs", Context.MODE_PRIVATE)
        val storedVersion = prefs.getInt(KEY_SEED_VERSION, 0)
        when {
            repo.employeeDao.total() == 0 -> {
                loadEmployees()
                loadOfficeMaster()
                loadMobiles()
            }
            storedVersion < SEED_VERSION -> {
                // Baseline refresh: the bundled datasets were re-scoped to the Karur
                // Sub Division (label) only — offices AND staff AND mobiles. Rebuild the
                // whole bundled baseline so an install over an older version ends up
                // identical to a clean install (no leftover Aravakurichi records).
                repo.employeeDao.deleteByType("DS")
                repo.employeeDao.deleteByType("GDS")
                repo.outsiderDao.clear()
                repo.mobileDao.clearMap()
                repo.officeMasterDao.clear()
                loadEmployees()
                loadOfficeMaster()
                loadMobiles()
            }
        }
        // Mail Overseer beats: seed once so the visit-tracking history survives. On a
        // baseline refresh, RE-LINK existing rows to the corrected office ids (v1.5.1)
        // without overwriting any visits the user has added.
        when {
            repo.moBeatDao.total() == 0 -> loadMoBeats()
            storedVersion < SEED_VERSION -> relinkMoBeats()
        }
        prefs.edit().putInt(KEY_SEED_VERSION, SEED_VERSION).apply()
    }

    private companion object {
        const val SEED_VERSION = 3
        const val KEY_SEED_VERSION = "seed_version"
    }

    /**
     * Seed the MO I / MO II beat offices. Each seed office carries a verified
     * [MoBeatSeed.SeedOffice.officeId] resolved against the Karur Sub Division office
     * master, so the link is exact. Only if that id is somehow absent from the current
     * master do we fall back to the fuzzy name matcher.
     */
    private suspend fun loadMoBeats() {
        val masters = repo.officeMasterDao.allList()
        val byId = masters.associateBy { it.officeId }
        fun matchId(s: MoBeatSeed.SeedOffice): String? {
            s.officeId?.let { if (byId.containsKey(it)) return it }
            return masters.firstOrNull { MobileMatcher.officeMatches(s.name, it.officeName) }?.officeId
        }

        fun rows(beat: String, seed: List<MoBeatSeed.SeedOffice>) = seed.map { s ->
            MoBeatOfficeEntity(
                beat = beat,
                serialNo = s.serial,
                officeName = s.name,
                matchedOfficeId = matchId(s),
                visits = s.visits.sorted().joinToString(",")
            )
        }
        repo.moBeatDao.upsertAll(rows(MoBeatSeed.MO_I, MoBeatSeed.moI))
        repo.moBeatDao.upsertAll(rows(MoBeatSeed.MO_II, MoBeatSeed.moII))
    }

    /**
     * Correct the office links on already-seeded beat rows (upgrade path). Updates only
     * [MoBeatOfficeEntity.matchedOfficeId] by (beat, serialNo); visit history is untouched.
     */
    private suspend fun relinkMoBeats() {
        val masters = repo.officeMasterDao.allList()
        val byId = masters.associateBy { it.officeId }
        suspend fun relink(beat: String, seed: List<MoBeatSeed.SeedOffice>) {
            seed.forEach { s ->
                val id = s.officeId?.takeIf { byId.containsKey(it) }
                    ?: masters.firstOrNull { MobileMatcher.officeMatches(s.name, it.officeName) }?.officeId
                repo.moBeatDao.updateMatch(beat, s.serial, id)
            }
        }
        relink(MoBeatSeed.MO_I, MoBeatSeed.moI)
        relink(MoBeatSeed.MO_II, MoBeatSeed.moII)
    }

    private fun readAsset(name: String): String =
        context.assets.open(name).bufferedReader().readText()

    /** JSON value -> plain string, matching how Excel imports store cells. */
    private fun valueOf(v: kotlinx.serialization.json.JsonElement): String =
        when (v) {
            is JsonNull -> ""
            is JsonPrimitive -> v.content
            else -> v.toString()
        }

    private fun recordOf(obj: JsonObject, dropKeys: Set<String> = emptySet()): Record {
        val rec = Record()
        obj.entries.forEach { (k, v) -> if (k !in dropKeys) rec[k] = valueOf(v) }
        return rec
    }

    private suspend fun loadEmployees() {
        val root = Json.parseToJsonElement(readAsset("data/employees.json")).jsonObject

        val employees = mutableListOf<EmployeeEntity>()
        val outsiders = mutableListOf<OutsiderEntity>()
        root["employees"]?.jsonArray?.forEach { el ->
            val obj = el.jsonObject
            val type = (obj["_type"] as? JsonPrimitive)?.content
            val rec = recordOf(obj, dropKeys = setOf("_type"))
            when (type) {
                "DS" -> RecordMappers.toEmployee(ImportType.DS, rec)?.let { employees += it }
                "GDS" -> RecordMappers.toEmployee(ImportType.GDS, rec)?.let { employees += it }
                "OUT" -> RecordMappers.toOutsider(rec)?.let { outsiders += it }
            }
        }
        repo.employeeDao.upsertAll(employees)
        repo.outsiderDao.upsertAll(outsiders)

        // Freshness badges from the bundled export's own meta (ds / gs / out).
        val meta = root["meta"]?.jsonObject
        suspend fun metaOf(key: String, badge: String, fallbackCount: Int) {
            val m = meta?.get(key) as? JsonObject
            val fileName = (m?.get("fileName") as? JsonPrimitive)?.content ?: "Bundled data"
            val count = (m?.get("count") as? JsonPrimitive)?.content?.toIntOrNull() ?: fallbackCount
            val date = ((m?.get("uploadedAt") ?: m?.get("updatedAt")) as? JsonPrimitive)
                ?.content?.take(10) ?: ""
            repo.importMetaDao.upsert(ImportMetaEntity(badge, fileName, count, date))
        }
        metaOf("ds", "DS", employees.count { it.type == "DS" })
        metaOf("gs", "GDS", employees.count { it.type == "GDS" })
        metaOf("out", "OUT", outsiders.size)
    }

    private suspend fun loadOfficeMaster() {
        val root = Json.parseToJsonElement(readAsset("data/office_master.json")).jsonObject
        val offices = mutableListOf<OfficeMasterEntity>()
        root["offices"]?.jsonArray?.forEach { el ->
            val o = el.jsonObject
            fun g(k: String): String? = (o[k] as? JsonPrimitive)
                ?.let { if (it is JsonNull) null else it.content }?.takeIf { it.isNotBlank() }
            offices += OfficeMasterEntity(
                officeId = g("office_id") ?: return@forEach,
                officeName = g("office_name") ?: "—",
                officeType = g("office_type"), officeClass = g("office_class"),
                officeStatus = g("office_status"), subDivision = g("sub_division"),
                subDivisionOfficeId = g("sub_division_office_id"), division = g("division"),
                hoName = g("ho_name"), soName = g("so_name"), region = g("region"),
                circle = g("circle"), email = g("email"), contact = g("contact"),
                pincode = g("pincode"), address1 = g("address1"), address2 = g("address2"),
                address3 = g("address3"), village = g("village"), taluk = g("taluk"),
                city = g("city"), state = g("state"), latitude = g("latitude"),
                longitude = g("longitude"), workingDays = g("working_days"),
                workingHoursFrom = g("working_hours_from"), workingHoursTo = g("working_hours_to"),
                solId = g("sol_id"), csiFacilityId = g("csi_facility_id"), pliId = g("pli_id"),
                gstn = g("gstn"), paoCode = g("pao_code"), deliveryOffice = g("delivery_office"),
                reportingOfficeId = g("reporting_office_id"), headOfOfficeId = g("head_of_office_id")
            )
        }
        repo.officeMasterDao.upsertAll(offices)

        val m = root["meta"]?.jsonObject
        repo.importMetaDao.upsert(
            ImportMetaEntity(
                "OFFICES",
                (m?.get("fileName") as? JsonPrimitive)?.content ?: "Bundled data",
                offices.size,
                (m?.get("updatedAt") as? JsonPrimitive)?.content?.take(10) ?: ""
            )
        )
    }

    private suspend fun loadMobiles() {
        val root = Json.parseToJsonElement(readAsset("data/mobiles.json")).jsonObject
        val map = root["map"]?.jsonObject ?: return
        val entries = map.entries.mapNotNull { (id, v) ->
            val phone = (v as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            MobileMapEntity(id, phone)
        }
        repo.mobileDao.upsertMap(entries)

        val m = root["meta"]?.jsonObject
        repo.importMetaDao.upsert(
            ImportMetaEntity(
                "TEL",
                (m?.get("fileName") as? JsonPrimitive)?.content ?: "Bundled data",
                entries.size,
                (m?.get("updatedAt") as? JsonPrimitive)?.content?.take(10) ?: ""
            )
        )
    }
}
