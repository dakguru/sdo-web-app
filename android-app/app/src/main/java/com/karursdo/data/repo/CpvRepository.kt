package com.karursdo.data.repo

import com.karursdo.data.sync.SupabaseClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

// ---- Wire DTOs: field names match the Supabase (PostgREST) column names exactly ----

/** One stored office+scheme batch (metadata only — the account records are not loaded here). */
@Serializable
data class CpvBatchDto(
    val office_key: String = "",
    val office_name: String = "",
    val sol_id: String? = null,
    val branch_id: String? = null,
    val scheme: String = "",
    val scheme_label: String? = null,
    val total_accounts: Int = 0,
    val status_counts: Map<String, Int> = emptyMap(),
    val uploaded_by: String? = null,
    val uploaded_at_ms: Long? = null
)

/**
 * One cleaned row inside a batch's `records` JSON array.
 *
 * Bank schemes (SB/RD/SSA/TD…) use acct/cif/balance/date/status. PLI & RPLI lists
 * reuse `acct` as the policy number (so verification keying is unchanged) and carry
 * the extra insurance fields below; bank rows simply leave them at their defaults.
 */
@Serializable
data class CpvRecordDto(
    val acct: String = "",
    val name: String = "",
    val cif: String = "",
    val address: String = "",
    val type: String = "",
    val balance: Double? = null,
    val date: String = "",
    val dateRaw: String = "",
    val status: String = "—",
    // ── PLI / RPLI only ──
    val policy: String = "",
    val doeIso: String = "",
    val doeRaw: String = "",
    val sumAssured: Double? = null,
    val premium: Double? = null,
    val paidUpto: String = "",       // MMM-YYYY, e.g. "Jun-2026"
    val paidIso: String = "",
    val paidRaw: String = "",
    val monthsPaid: Int? = null
)

/** Full batch row including the account records (loaded when a list is opened). */
@Serializable
private data class CpvFullDto(
    val office_key: String = "",
    val office_name: String = "",
    val sol_id: String? = null,
    val branch_id: String? = null,
    val scheme: String = "",
    val scheme_label: String? = null,
    val total_accounts: Int = 0,
    val status_counts: Map<String, Int> = emptyMap(),
    val records: List<CpvRecordDto> = emptyList()
)

/** One office → Mail Overseer allotment row in app_cpv_allotments. */
@Serializable
data class CpvAllotmentDto(
    val branch_id: String = "",
    val mo_username: String = "",
    val office_name: String? = null,
    val sol_id: String? = null,
    val allotted_by: String? = null,
    val allotted_at_ms: Long? = null
)

/** A user account (subset), used to pick Mail Overseers for allotment. */
@Serializable
data class CpvUserDto(
    val username: String = "",
    val display_name: String = "",
    val role: String = "",
    val active: Boolean = true
)

/** Minimal projection for counting verifications without pulling whole rows. */
@Serializable
private data class OfficeKeyDto(val office_key: String = "")

/** One flattened account row for a consolidated (all-categories) office report. */
data class CpvConsRow(
    val scheme: String,
    val acct: String,
    val name: String,
    val address: String,
    val type: String,
    val balance: Double?,
    val date: String,
    val status: String,
    val verifiedBy: String = "",
    val verifiedAtMs: Long? = null,
    val remarks: String = ""
)

/** Verified + pending rows for one office, across all its scheme categories. */
data class CpvOfficeConsolidated(
    val officeName: String,
    val sol: String,
    val branch: String,
    val verified: List<CpvConsRow>,
    val pending: List<CpvConsRow>
)

/** One per-account verification row in app_cpv_verification. */
@Serializable
data class CpvVerifDto(
    val office_key: String = "",
    val acct: String = "",
    val verified: Boolean = true,
    val remarks: String? = null,
    val verified_by: String? = null,
    val verified_at_ms: Long? = null
)

/**
 * One policy in the division-wide master pool (app_pli_master) — a flat reference of every
 * PLI/RPLI policy, not tied to any office. Searched when a policy isn't in a BO's own list.
 */
@Serializable
data class PliMasterDto(
    val policy: String = "",
    val policy_norm: String = "",
    val name: String = "",
    val address: String = "",
    val doe: String = "",
    val sum_assured: Double? = null,
    val premium: Double? = null,
    val paid_to: String = "",
    val months_paid: Int? = null
)

/**
 * One policy pulled from the master pool into a specific office (app_cpv_extra) to verify —
 * kept apart from the office's official app_cpv list and its verification totals.
 */
@Serializable
data class CpvExtraDto(
    val office_key: String = "",
    val policy: String = "",
    val name: String = "",
    val address: String = "",
    val doe: String = "",
    val sum_assured: Double? = null,
    val premium: Double? = null,
    val paid_to: String = "",
    val months_paid: Int? = null,
    val verified: Boolean = false,
    val remarks: String? = null,
    val verified_by: String? = null,
    val verified_at_ms: Long? = null,
    val added_by: String? = null,
    val added_at_ms: Long? = null
)

/** An account merged with its verification state, for display. */
data class CpvAccount(
    val record: CpvRecordDto,
    val verified: Boolean = false,
    val remarks: String = "",
    val verifiedBy: String = "",
    val verifiedAtMs: Long? = null
)

/** A fully-loaded batch: metadata + accounts (records merged with verification). */
data class CpvBatch(
    val meta: CpvBatchDto,
    val accounts: List<CpvAccount>
)

/**
 * Cent Percent Verification data access for the Android app — talks straight to Supabase
 * (PostgREST) via [SupabaseClient], mirroring the web `cpv.html` module. Account lists are
 * stored per office+scheme in `app_cpv`; each account's field verification (done by the Mail
 * Overseer) lives in `app_cpv_verification`, keyed by (office_key, acct).
 *
 * This layer is online — a live connection is needed to load lists and to record a verification
 * (the same as chat / programmes). Every write is an idempotent upsert on the composite key, so
 * single and bulk (same customer / CIF) verification are just one call.
 */
@Singleton
class CpvRepository @Inject constructor(
    private val client: SupabaseClient
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true; coerceInputValues = true }

    val enabled: Boolean get() = client.enabled

    /** All stored office+scheme batches (metadata only), office- then scheme-sorted. */
    suspend fun listBatches(): List<CpvBatchDto> = withContext(Dispatchers.IO) {
        val q = "select=office_key,office_name,sol_id,branch_id,scheme,scheme_label," +
            "total_accounts,status_counts,uploaded_by,uploaded_at_ms&order=office_name.asc,scheme.asc"
        val txt = client.selectAll("app_cpv", q) ?: throw CpvException(client.lastError ?: "Could not reach the server.")
        runCatching { json.decodeFromString<List<CpvBatchDto>>(txt) }.getOrElse { emptyList() }
    }

    /** Load one batch's accounts, merged with any recorded verifications. */
    suspend fun loadBatch(officeKey: String): CpvBatch = withContext(Dispatchers.IO) {
        val enc = java.net.URLEncoder.encode(officeKey, "UTF-8")
        val fullTxt = client.selectAll("app_cpv", "office_key=eq.$enc&select=*&limit=1")
            ?: throw CpvException(client.lastError ?: "Could not reach the server.")
        val full = runCatching { json.decodeFromString<List<CpvFullDto>>(fullTxt) }.getOrNull()?.firstOrNull()
            ?: throw CpvException("List not found.")

        // Verifications are optional — a missing table just means nothing is verified yet.
        val verifs: Map<String, CpvVerifDto> = runCatching {
            val vTxt = client.selectAll(
                "app_cpv_verification",
                "office_key=eq.$enc&select=acct,verified,remarks,verified_by,verified_at_ms"
            )
            if (vTxt == null) emptyMap()
            else json.decodeFromString<List<CpvVerifDto>>(vTxt).associateBy { it.acct }
        }.getOrDefault(emptyMap())

        val accounts = full.records.map { rec ->
            val v = verifs[rec.acct]
            CpvAccount(
                record = rec,
                verified = v?.verified ?: false,
                remarks = v?.remarks ?: "",
                verifiedBy = v?.verified_by ?: "",
                verifiedAtMs = v?.verified_at_ms
            )
        }
        CpvBatch(
            meta = CpvBatchDto(
                office_key = full.office_key, office_name = full.office_name, sol_id = full.sol_id,
                branch_id = full.branch_id, scheme = full.scheme, scheme_label = full.scheme_label,
                total_accounts = full.total_accounts, status_counts = full.status_counts
            ),
            accounts = accounts
        )
    }

    /**
     * Record a verification for one or more accounts of a batch. `remarks == null` keeps each
     * account's existing remark (only the verified flag changes); a non-null value is applied
     * to all. Returns true on success.
     */
    suspend fun saveVerifications(
        officeKey: String,
        accounts: List<CpvAccount>,
        verified: Boolean,
        remarks: String?,
        verifiedBy: String
    ): Boolean = withContext(Dispatchers.IO) {
        if (accounts.isEmpty()) return@withContext true
        val now = System.currentTimeMillis()
        val rows = accounts.map { a ->
            CpvVerifDto(
                office_key = officeKey,
                acct = a.record.acct,
                verified = verified,
                remarks = (remarks ?: a.remarks).ifBlank { null },
                verified_by = verifiedBy,
                verified_at_ms = now
            )
        }
        val body = json.encodeToString(rows)
        val ok = client.upsert("app_cpv_verification", body)
        if (!ok) throw CpvException(client.lastError ?: "Could not save verification.")
        true
    }

    // ── Office → Mail Overseer allotment ────────────────────────────────

    /** All office→MO allotments. Empty (not an error) if the table isn't set up yet. */
    suspend fun listAllotments(): List<CpvAllotmentDto> = withContext(Dispatchers.IO) {
        val txt = client.selectAll(
            "app_cpv_allotments",
            "select=branch_id,mo_username,office_name,sol_id"
        ) ?: return@withContext emptyList()
        runCatching { json.decodeFromString<List<CpvAllotmentDto>>(txt) }.getOrElse { emptyList() }
    }

    /** User accounts whose role is MO — candidates for allotment. */
    suspend fun listMoUsers(): List<CpvUserDto> = withContext(Dispatchers.IO) {
        val txt = client.selectAll(
            "app_users",
            "select=username,display_name,role,active&order=display_name.asc"
        ) ?: return@withContext emptyList()
        val all = runCatching { json.decodeFromString<List<CpvUserDto>>(txt) }.getOrElse { emptyList() }
        all.filter { it.role.trim().uppercase().replace(Regex("[^A-Z]"), "") == "MO" }
    }

    /**
     * Set the Mail Overseers allotted to one office (branch). Adds newly-selected MOs and removes
     * de-selected ones — never touches app_cpv or app_cpv_verification, so verified data is safe.
     */
    suspend fun setAllotments(
        branch: String, officeName: String?, sol: String?,
        selected: Set<String>, previous: Set<String>, by: String
    ): Boolean = withContext(Dispatchers.IO) {
        val add = selected - previous
        val remove = previous - selected
        if (add.isEmpty() && remove.isEmpty()) return@withContext true
        val now = System.currentTimeMillis()
        if (add.isNotEmpty()) {
            val rows = add.map { CpvAllotmentDto(branch, it, officeName, sol, by, now) }
            if (!client.upsert("app_cpv_allotments", json.encodeToString(rows)))
                throw CpvException(client.lastError ?: "Could not save allotment.")
        }
        val encBranch = java.net.URLEncoder.encode(branch, "UTF-8")
        for (mo in remove) {
            val encMo = java.net.URLEncoder.encode(mo, "UTF-8")
            if (!client.delete("app_cpv_allotments", "branch_id=eq.$encBranch&mo_username=eq.$encMo"))
                throw CpvException(client.lastError ?: "Could not update allotment.")
        }
        true
    }

    // ── Dashboard overview: verified counts per office_key (paginated) ───

    /** Number of VERIFIED accounts per office_key. Pages past PostgREST's 1000-row cap. */
    suspend fun verifiedCountsByOffice(): Map<String, Int> = withContext(Dispatchers.IO) {
        val counts = HashMap<String, Int>()
        val page = 1000; var from = 0
        while (true) {
            val txt = client.selectAll(
                "app_cpv_verification",
                "verified=eq.true&select=office_key&limit=$page&offset=$from"
            ) ?: break
            val rows = runCatching { json.decodeFromString<List<OfficeKeyDto>>(txt) }.getOrElse { emptyList() }
            if (rows.isEmpty()) break
            rows.forEach { counts[it.office_key] = (counts[it.office_key] ?: 0) + 1 }
            if (rows.size < page) break
            from += page
        }
        counts
    }

    // ── Per-office consolidated download (all categories) ───────────────

    /** Build verified & pending rows for one office (branch) across all its scheme categories. */
    suspend fun loadOfficeConsolidated(branch: String): CpvOfficeConsolidated = withContext(Dispatchers.IO) {
        val enc = java.net.URLEncoder.encode(branch, "UTF-8")
        val fullTxt = client.selectAll(
            "app_cpv",
            "branch_id=eq.$enc&select=office_key,office_name,sol_id,branch_id,scheme,scheme_label,records&order=scheme.asc"
        ) ?: throw CpvException(client.lastError ?: "Could not reach the server.")
        val batches = runCatching { json.decodeFromString<List<CpvFullDto>>(fullTxt) }.getOrElse { emptyList() }
        if (batches.isEmpty()) return@withContext CpvOfficeConsolidated("", "", branch, emptyList(), emptyList())

        // Verifications for all of this office's scheme lists (paged, keyed by office_key+acct).
        val vmap = HashMap<String, MutableMap<String, CpvVerifDto>>()
        val page = 1000; var from = 0
        val keysFilter = batches.joinToString(",") { "\"" + it.office_key.replace("\"", "\"\"") + "\"" }
        val keysEnc = java.net.URLEncoder.encode(keysFilter, "UTF-8")
        while (true) {
            val vTxt = client.selectAll(
                "app_cpv_verification",
                "office_key=in.($keysEnc)&select=office_key,acct,verified,remarks,verified_by,verified_at_ms&limit=$page&offset=$from"
            ) ?: break
            val vr = runCatching { json.decodeFromString<List<CpvVerifDto>>(vTxt) }.getOrElse { emptyList() }
            if (vr.isEmpty()) break
            vr.forEach { vmap.getOrPut(it.office_key) { HashMap() }[it.acct] = it }
            if (vr.size < page) break
            from += page
        }

        val verified = ArrayList<CpvConsRow>()
        val pending = ArrayList<CpvConsRow>()
        var officeName = ""; var sol = ""
        for (b in batches) {
            officeName = b.office_name.ifBlank { officeName }
            sol = (b.sol_id ?: "").ifBlank { sol }
            val vm = vmap[b.office_key] ?: emptyMap()
            for (r in b.records) {
                val acct = r.acct.ifBlank { r.policy }
                val v = vm[acct]
                val bal = r.balance ?: r.sumAssured
                val dt = r.date.ifBlank { r.dateRaw }.ifBlank { r.doeIso }.ifBlank { r.doeRaw }
                if (v != null && v.verified) {
                    verified.add(CpvConsRow(b.scheme, acct, r.name, r.address, r.type, bal, dt, r.status,
                        v.verified_by ?: "", v.verified_at_ms, v.remarks ?: ""))
                } else {
                    pending.add(CpvConsRow(b.scheme, acct, r.name, r.address, r.type, bal, dt, r.status))
                }
            }
        }
        CpvOfficeConsolidated(officeName, sol, branch, verified, pending)
    }

    // ── Master policy pool (app_pli_master) + per-office extras (app_cpv_extra) ──

    /** Number of policies in the division-wide master pool. -1 if unavailable / not set up. */
    suspend fun masterCount(): Int = withContext(Dispatchers.IO) {
        client.count("app_pli_master", "select=policy")
    }

    /**
     * Search the master pool by policy number or insured name (case-insensitive, leading zeros
     * ignored for numbers). Empty list if the pool isn't set up or nothing matches.
     */
    suspend fun searchMaster(term: String): List<PliMasterDto> = withContext(Dispatchers.IO) {
        val t = term.trim()
        if (t.length < 2) return@withContext emptyList()
        val enc = java.net.URLEncoder.encode(t, "UTF-8")
        val norm = t.trimStart('0').ifEmpty { t }
        val encN = java.net.URLEncoder.encode(norm, "UTF-8")
        val q = "or=(policy.ilike.*$enc*,policy_norm.ilike.*$encN*,name.ilike.*$enc*)" +
            "&select=*&order=name.asc&limit=60"
        val txt = client.selectAll("app_pli_master", q) ?: return@withContext emptyList()
        runCatching { json.decodeFromString<List<PliMasterDto>>(txt) }.getOrElse { emptyList() }
    }

    /** Per-office extra-policy counts (total, verified) across all offices, for the dashboard. */
    suspend fun extraCountsByOffice(): Pair<Map<String, Int>, Map<String, Int>> = withContext(Dispatchers.IO) {
        val tot = HashMap<String, Int>(); val ver = HashMap<String, Int>()
        val page = 1000; var from = 0
        while (true) {
            val txt = client.selectAll("app_cpv_extra", "select=office_key,verified&limit=$page&offset=$from") ?: break
            val rows = runCatching { json.decodeFromString<List<CpvExtraDto>>(txt) }.getOrElse { emptyList() }
            if (rows.isEmpty()) break
            rows.forEach { tot[it.office_key] = (tot[it.office_key] ?: 0) + 1; if (it.verified) ver[it.office_key] = (ver[it.office_key] ?: 0) + 1 }
            if (rows.size < page) break
            from += page
        }
        tot to ver
    }

    /** Extra policies attached to an office from the master pool. */
    suspend fun listExtras(officeKey: String): List<CpvExtraDto> = withContext(Dispatchers.IO) {
        val enc = java.net.URLEncoder.encode(officeKey, "UTF-8")
        val txt = client.selectAll("app_cpv_extra", "office_key=eq.$enc&select=*&order=added_at_ms.asc")
            ?: return@withContext emptyList()
        runCatching { json.decodeFromString<List<CpvExtraDto>>(txt) }.getOrElse { emptyList() }
    }

    /** Add a master policy to an office as an (unverified) extra. Returns true on success. */
    suspend fun addExtra(officeKey: String, m: PliMasterDto, by: String): Boolean = withContext(Dispatchers.IO) {
        val row = CpvExtraDto(
            office_key = officeKey, policy = m.policy, name = m.name, address = m.address,
            doe = m.doe, sum_assured = m.sum_assured, premium = m.premium, paid_to = m.paid_to,
            months_paid = m.months_paid, verified = false, remarks = null,
            added_by = by, added_at_ms = System.currentTimeMillis()
        )
        val ok = client.upsert("app_cpv_extra", json.encodeToString(listOf(row)))
        if (!ok) throw CpvException(client.lastError ?: "Could not add the policy.")
        true
    }

    /** Save (upsert) the verify state / remark of an extra policy. Returns true on success. */
    suspend fun saveExtra(row: CpvExtraDto): Boolean = withContext(Dispatchers.IO) {
        val ok = client.upsert("app_cpv_extra", json.encodeToString(listOf(row)))
        if (!ok) throw CpvException(client.lastError ?: "Could not save.")
        true
    }

    /** Remove an extra policy from an office. Returns true on success. */
    suspend fun removeExtra(officeKey: String, policy: String): Boolean = withContext(Dispatchers.IO) {
        val encK = java.net.URLEncoder.encode(officeKey, "UTF-8")
        val encP = java.net.URLEncoder.encode(policy, "UTF-8")
        val ok = client.delete("app_cpv_extra", "office_key=eq.$encK&policy=eq.$encP")
        if (!ok) throw CpvException(client.lastError ?: "Could not remove.")
        true
    }
}

class CpvException(message: String) : Exception(message)
