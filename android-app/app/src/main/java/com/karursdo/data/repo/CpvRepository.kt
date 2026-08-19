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

/** One cleaned account row inside a batch's `records` JSON array. */
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
    val status: String = "—"
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
}

class CpvException(message: String) : Exception(message)
