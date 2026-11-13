package com.karursdo.data.repo

import com.karursdo.data.sync.SupabaseClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

// ---- Wire DTOs: field names match the Supabase (PostgREST) column names exactly ----

/** One account carried on a notice — the `accounts` jsonb array of app_sb46. */
@Serializable
data class Sb46AcctDto(
    val acct: String = "",
    val balance: Double? = null,
    val date: String = ""
)

/**
 * One SB-46 notice — a row of app_sb46. The notice is the unit of post: one depositor, one
 * scheme, every unverified account of theirs in it, travelling under one Speed Post article
 * number. Everything below `article_no` is what came back weeks later.
 */
@Serializable
data class Sb46NoticeDto(
    val notice_id: String = "",
    val office_id: String = "",
    val office_name: String? = null,
    val sol_id: String? = null,
    val branch_id: String? = null,
    val office_key: String? = null,
    val scheme: String = "",
    val seq: Int = 0,
    val sl_from: Int? = null,
    val sl_to: Int? = null,
    val depositor: String? = null,
    val address: String? = null,
    val pincode: String? = null,
    val cif: String? = null,
    val accounts: List<Sb46AcctDto> = emptyList(),
    val article_no: String? = null,
    val issued_on: String? = null,
    val ack_received: Boolean = false,
    val ack_at_ms: Long? = null,
    val reply_received: Boolean = false,
    val reply_at_ms: Long? = null,
    val returned: Boolean = false,
    val return_reason: String? = null,
    val returned_at_ms: Long? = null,
    val remarks: String? = null,
    val created_by: String? = null,
    val created_at_ms: Long? = null,
    val updated_by: String? = null,
    val updated_at_ms: Long? = null
)

/**
 * The light projection behind the office list — one small row per notice, just enough to count
 * how far each office's register has got. Identity columns ride along so the list can be built
 * from the register alone, without also reading every app_cpv batch of the division.
 */
@Serializable
data class Sb46ProgressDto(
    val office_id: String = "",
    val office_name: String? = null,
    val sol_id: String? = null,
    val branch_id: String? = null,
    val scheme: String = "",
    val sl_from: Int? = null,
    val sl_to: Int? = null,
    val ack_received: Boolean = false,
    val reply_received: Boolean = false,
    val returned: Boolean = false
) {
    /** Accounts on this cover, from its serial range — each is one SB-46 notice. */
    val accountCount: Int get() {
        val f = sl_from ?: 0; val t = sl_to ?: 0
        return if (f > 0 && t >= f) t - f + 1 else 1
    }
}

/**
 * Every unverified account is, in law, served its own SB-46 notice; clubbing a depositor's
 * accounts into one cover is only a convenience of posting. So notices are counted per account,
 * and what is recorded on a cover (ack., reply, return) counts once for each account it carries
 * — the same rule as the web register and the Final Report.
 */
val Sb46NoticeDto.accountCount: Int get() {
    if (accounts.isNotEmpty()) return accounts.size
    val f = sl_from ?: 0; val t = sl_to ?: 0
    return if (f > 0 && t >= f) t - f + 1 else 1
}

/** One office's register, summarised for the list screen. */
data class Sb46Office(
    val officeId: String,
    val officeName: String,
    val sol: String,
    val branch: String,
    /** Envelopes posted — one per depositor per scheme. */
    val covers: Int,
    /** SB-46 notices issued — one per account; ack/replied/returned below are counted the same way. */
    val issued: Int,
    val ack: Int,
    val replied: Int,
    val returned: Int,
    val schemes: List<String>
) {
    /** A notice is settled once the depositor has replied or the cover has come back. */
    val settled: Int get() = replied + returned
    val awaiting: Int get() = (issued - settled).coerceAtLeast(0)
    val pct: Int get() = if (issued > 0) settled * 100 / issued else 0
}

/**
 * SB-46 notice tracking for the Android app — what came back against each notice.
 *
 * Raising a batch needs the physical sheet of Speed Post labels and the Final Report is a desk
 * job, so both stay in the web module (`/sb-46`). Recording the acknowledgement, the reply or
 * the returned cover is field work, which is why it lives here.
 *
 * Like [CpvRepository] this layer is online — it talks straight to Supabase (PostgREST) through
 * [SupabaseClient] with no local cache, so a live connection is needed to read the register and
 * to record anything against it.
 *
 * Every write is a PATCH of just the columns that change, so the clerk editing one notice in the
 * web app and the Mail Overseer marking another on the phone cannot overwrite each other.
 */
@Singleton
class Sb46Repository @Inject constructor(
    private val client: SupabaseClient
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true; coerceInputValues = true }

    val enabled: Boolean get() = client.enabled

    /** The six reasons a cover can come back undelivered — the web module's list, in its order. */
    companion object {
        val RETURN_REASONS = listOf(
            "Deceased", "Unclaimed", "Insufficient address", "No such addressee", "Not known", "Left"
        )
        private const val PAGE = 1000
    }

    // ── Reading ─────────────────────────────────────────────────────────

    /**
     * Every office that has notices raised, with how far its register has got. One light row per
     * notice, paged past PostgREST's 1000-row cap.
     */
    suspend fun listOffices(): List<Sb46Office> = withContext(Dispatchers.IO) {
        val rows = ArrayList<Sb46ProgressDto>()
        var from = 0
        while (true) {
            val txt = client.selectAll(
                "app_sb46",
                "select=office_id,office_name,sol_id,branch_id,scheme,sl_from,sl_to,ack_received,reply_received,returned" +
                    "&limit=$PAGE&offset=$from"
            ) ?: throw Sb46Exception(
                client.lastError?.let { "Could not read the SB-46 register ($it)." }
                    ?: "Could not read the SB-46 register."
            )
            val page = runCatching { json.decodeFromString<List<Sb46ProgressDto>>(txt) }.getOrElse { emptyList() }
            if (page.isEmpty()) break
            rows.addAll(page)
            if (page.size < PAGE) break
            from += PAGE
        }

        rows.groupBy { it.office_id }
            .filter { it.key.isNotBlank() }
            .map { (id, rs) ->
                Sb46Office(
                    officeId = id,
                    officeName = rs.firstNotNullOfOrNull { it.office_name?.takeIf { n -> n.isNotBlank() } } ?: id,
                    sol = rs.firstNotNullOfOrNull { it.sol_id?.takeIf { s -> s.isNotBlank() } } ?: "",
                    branch = rs.firstNotNullOfOrNull { it.branch_id?.takeIf { b -> b.isNotBlank() } } ?: "",
                    covers = rs.size,
                    issued = rs.sumOf { it.accountCount },
                    ack = rs.filter { it.ack_received }.sumOf { it.accountCount },
                    replied = rs.filter { it.reply_received }.sumOf { it.accountCount },
                    returned = rs.filter { it.returned }.sumOf { it.accountCount },
                    schemes = rs.map { it.scheme }.filter { it.isNotBlank() }.distinct().sorted()
                )
            }
            .sortedBy { it.officeName.lowercase() }
    }

    /** One office's notices, in the order the article numbers were allotted. */
    suspend fun listNotices(officeId: String): List<Sb46NoticeDto> = withContext(Dispatchers.IO) {
        val enc = java.net.URLEncoder.encode(officeId, "UTF-8")
        val out = ArrayList<Sb46NoticeDto>()
        var from = 0
        while (true) {
            val txt = client.selectAll(
                "app_sb46",
                "office_id=eq.$enc&select=*&order=seq.asc&limit=$PAGE&offset=$from"
            ) ?: throw Sb46Exception(
                client.lastError?.let { "Could not load the register ($it)." } ?: "Could not load the register."
            )
            val page = runCatching { json.decodeFromString<List<Sb46NoticeDto>>(txt) }.getOrElse { emptyList() }
            if (page.isEmpty()) break
            out.addAll(page)
            if (page.size < PAGE) break
            from += PAGE
        }
        out
    }

    // ── Recording what came back ────────────────────────────────────────
    //
    //  The three outcomes are mutually exclusive in the ways the post allows, exactly as the web
    //  module's register does it (sb46.html — saveNotice and its click handler):
    //
    //    • A reply can only have come back from a cover that was delivered, so marking a reply
    //      clears `returned` and its reason.
    //    • A cover that came back undelivered was never read, so marking it returned clears both
    //      the reply and the acknowledgement.
    //    • An acknowledgement card on its own says only that the cover arrived, so it clears
    //      nothing — "delivered but silent" is a state the Final Report counts in its own right.
    //
    //  Each call returns the updated row so the caller can keep its copy in step without
    //  re-reading the register.

    /** Mark (or unmark) the acknowledgement card as received. */
    suspend fun setAck(n: Sb46NoticeDto, on: Boolean, by: String): Sb46NoticeDto = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        patch(n.notice_id, by, now, mapOf(
            "ack_received" to JsonPrimitive(on),
            "ack_at_ms" to nullable(if (on) now else null)
        ))
        n.copy(ack_received = on, ack_at_ms = if (on) now else null, updated_by = by, updated_at_ms = now)
    }

    /** Mark (or unmark) the depositor's reply as received — a reply undoes a returned cover. */
    suspend fun setReply(n: Sb46NoticeDto, on: Boolean, by: String): Sb46NoticeDto = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val fields = if (on) mapOf(
            "reply_received" to JsonPrimitive(true),
            "reply_at_ms" to JsonPrimitive(now),
            "returned" to JsonPrimitive(false),
            "return_reason" to JsonNull,
            "returned_at_ms" to JsonNull
        ) else mapOf(
            "reply_received" to JsonPrimitive(false),
            "reply_at_ms" to JsonNull
        )
        patch(n.notice_id, by, now, fields)
        if (on) n.copy(
            reply_received = true, reply_at_ms = now,
            returned = false, return_reason = null, returned_at_ms = null,
            updated_by = by, updated_at_ms = now
        ) else n.copy(reply_received = false, reply_at_ms = null, updated_by = by, updated_at_ms = now)
    }

    /** Mark (or unmark) the cover as returned undelivered — which undoes the reply and the ack. */
    suspend fun setReturned(n: Sb46NoticeDto, on: Boolean, by: String): Sb46NoticeDto = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val fields = if (on) mapOf(
            "returned" to JsonPrimitive(true),
            "returned_at_ms" to JsonPrimitive(now),
            "reply_received" to JsonPrimitive(false),
            "reply_at_ms" to JsonNull,
            "ack_received" to JsonPrimitive(false),
            "ack_at_ms" to JsonNull
        ) else mapOf(
            "returned" to JsonPrimitive(false),
            "return_reason" to JsonNull,
            "returned_at_ms" to JsonNull
        )
        patch(n.notice_id, by, now, fields)
        if (on) n.copy(
            returned = true, returned_at_ms = now,
            reply_received = false, reply_at_ms = null,
            ack_received = false, ack_at_ms = null,
            updated_by = by, updated_at_ms = now
        ) else n.copy(
            returned = false, return_reason = null, returned_at_ms = null,
            updated_by = by, updated_at_ms = now
        )
    }

    /** The reason written on a returned cover — one of [RETURN_REASONS], or blank to clear it. */
    suspend fun setReturnReason(n: Sb46NoticeDto, reason: String, by: String): Sb46NoticeDto = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val r = reason.trim().ifBlank { null }
        patch(n.notice_id, by, now, mapOf("return_reason" to nullable(r)))
        n.copy(return_reason = r, updated_by = by, updated_at_ms = now)
    }

    /** A free remark against the notice. */
    suspend fun setRemarks(n: Sb46NoticeDto, remarks: String, by: String): Sb46NoticeDto = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val t = remarks.trim().ifBlank { null }
        patch(n.notice_id, by, now, mapOf("remarks" to nullable(t)))
        n.copy(remarks = t, updated_by = by, updated_at_ms = now)
    }

    // ── Internals ───────────────────────────────────────────────────────

    private fun nullable(v: Long?): JsonElement = v?.let { JsonPrimitive(it) } ?: JsonNull
    private fun nullable(v: String?): JsonElement = v?.let { JsonPrimitive(it) } ?: JsonNull

    /** PATCH one notice with just [fields], stamped with who changed it and when. */
    private suspend fun patch(noticeId: String, by: String, now: Long, fields: Map<String, JsonElement>) {
        if (noticeId.isBlank()) throw Sb46Exception("That notice has no id.")
        val body = JsonObject(
            fields + mapOf(
                "updated_by" to JsonPrimitive(by),
                "updated_at_ms" to JsonPrimitive(now)
            )
        )
        val enc = java.net.URLEncoder.encode(noticeId, "UTF-8")
        val ok = client.patch("app_sb46", "notice_id=eq.$enc", json.encodeToString(JsonObject.serializer(), body))
        if (!ok) throw Sb46Exception(client.lastError ?: "Could not save.")
    }
}

class Sb46Exception(message: String) : Exception(message)
