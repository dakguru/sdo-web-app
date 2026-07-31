package com.karursdo.data.sync

import com.karursdo.data.db.ArrangementDao
import com.karursdo.data.db.ArrangementEntity
import com.karursdo.data.db.ChatDao
import com.karursdo.data.db.ChatMessageEntity
import com.karursdo.data.db.ChatReactionDao
import com.karursdo.data.db.ChatReactionEntity
import com.karursdo.data.db.ChatReadDao
import com.karursdo.data.db.ChatReadEntity
import com.karursdo.data.db.DatasetSnapshotDao
import com.karursdo.data.db.DatasetSnapshotEntity
import com.karursdo.data.db.EmployeeDao
import com.karursdo.data.db.EmployeeEntity
import com.karursdo.data.db.FavoriteEntity
import com.karursdo.data.db.ImportMetaDao
import com.karursdo.data.db.ImportMetaEntity
import com.karursdo.data.db.OfficeMasterDao
import com.karursdo.data.db.OfficeMasterEntity
import com.karursdo.data.db.OutsiderEntity
import com.karursdo.data.db.PresenceDao
import com.karursdo.data.db.PresenceEntity
import com.karursdo.data.db.MoBeatDao
import com.karursdo.data.db.MoProgrammeDao
import com.karursdo.data.db.MoProgrammeEntity
import com.karursdo.data.db.MobileDao
import com.karursdo.data.db.MobileMapEntity
import com.karursdo.data.db.NoteEntity
import com.karursdo.data.db.OutsiderDao
import com.karursdo.data.db.PhoneEditDao
import com.karursdo.data.db.PhoneEditEntity
import com.karursdo.data.db.UserAccountDao
import com.karursdo.data.db.UserAccountEntity
import com.karursdo.data.db.UserDataDao
import com.karursdo.data.db.UserPrefEntity
import com.karursdo.data.security.DbKeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

// ---- DTOs: field names match the Supabase (PostgREST) column names exactly ----

@Serializable
private data class FavoriteDto(
    val item_type: String,
    val item_id: String,
    val label: String,
    val created_at: Long,
    val deleted: Boolean
)

@Serializable
private data class NoteDto(
    val id: String,
    val target_type: String,
    val target_id: String?,
    val title: String?,
    val body: String,
    val created_at: Long,
    val updated_at_ms: Long,
    val deleted: Boolean,
    val author: String? = null
)

@Serializable
private data class UserDto(
    val username: String,
    val password_hash: String,
    val salt: String,
    val iterations: Int,
    val display_name: String,
    val role: String,
    val active: Boolean,
    val must_change_password: Boolean,
    val created_at: Long,
    val updated_at_ms: Long
)

@Serializable
private data class MoVisitDto(
    val beat: String,
    val serial_no: Int,
    val visits: String,
    val updated_by: String?,
    val updated_at_ms: Long
)

@Serializable
private data class ChatMsgDto(
    val id: String,
    val username: String,
    val display_name: String,
    val body: String,
    val created_at: Long,
    val updated_at_ms: Long = 0,
    val deleted: Boolean = false
)

@Serializable
private data class ProgrammeDto(
    val id: String,
    val beat: String,
    val date: String,
    val details: String,
    val author: String? = null,
    val created_at: Long,
    val updated_at_ms: Long,
    val deleted: Boolean
)

@Serializable
private data class PhoneEditDto(
    val target_type: String,
    val target_id: String,
    val phone: String,
    val updated_by: String?,
    val updated_at_ms: Long
)

@Serializable
private data class PrefDto(
    val key: String,
    val value: String,
    val updated_at_ms: Long
)

@Serializable
private data class ActivityDto(
    val id: String,
    val action: String,
    val target_type: String?,
    val target_id: String?,
    val summary: String,
    val created_at: Long
)

@Serializable
private data class ChatReadDto(
    val username: String,
    val last_read_at: Long,
    val updated_at_ms: Long
)

@Serializable
private data class PresenceDto(
    val username: String,
    val last_seen_at: Long,
    val updated_at_ms: Long
)

@Serializable
private data class ReactionDto(
    val message_id: String,
    val username: String,
    val emoji: String,
    val updated_at_ms: Long
)

@Serializable
private data class DatasetDto(
    val type: String,
    val payload: String,
    val count: Int,
    val uploaded_by: String?,
    val uploaded_at_ms: Long
)

@Serializable
private data class PushTokenDto(
    val token: String,
    val username: String?,
    val platform: String,
    val updated_at_ms: Long
)

sealed interface SyncOutcome {
    data object Disabled : SyncOutcome
    data class Success(val pushed: Int, val pulled: Int) : SyncOutcome
    data class Error(val message: String) : SyncOutcome
}

/** Health of the chat's background push/pull, surfaced to the Message screen. */
data class ChatSyncStatus(val state: State, val detail: String? = null) {
    enum class State { OK, ERROR, OFFLINE }
}

/**
 * Two-way sync of the user layer (favorites / notes / prefs / activity) with Supabase.
 * PUSH: every row whose syncState is pending ('P') or delete-pending ('D') is upserted,
 * then marked synced (or purged). PULL: remote rows are merged back last-write-wins
 * (by updatedAt for notes/prefs; remote state mirrored for favorites) without clobbering
 * a locally-pending change. Single shared account => one global dataset across devices.
 *
 * Everything is a no-op when Supabase keys are not configured, so the app is fully usable
 * offline and this never blocks the UI.
 */
@Singleton
class SyncEngine @Inject constructor(
    private val dao: UserDataDao,
    private val userDao: UserAccountDao,
    private val moBeatDao: MoBeatDao,
    private val moProgrammeDao: MoProgrammeDao,
    private val phoneEditDao: PhoneEditDao,
    private val mobileDao: MobileDao,
    private val outsiderDao: OutsiderDao,
    private val chatDao: ChatDao,
    private val chatReadDao: ChatReadDao,
    private val chatReactionDao: ChatReactionDao,
    private val presenceDao: PresenceDao,
    private val datasetDao: DatasetSnapshotDao,
    private val employeeDao: EmployeeDao,
    private val officeMasterDao: OfficeMasterDao,
    private val arrangementDao: ArrangementDao,
    private val importMetaDao: ImportMetaDao,
    private val client: SupabaseClient,
    private val keyManager: DbKeyManager
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val enabled: Boolean get() = client.enabled

    private val _chatSync = MutableStateFlow(
        ChatSyncStatus(if (client.enabled) ChatSyncStatus.State.OK else ChatSyncStatus.State.OFFLINE)
    )
    /** Live status of the chat's 2-second push/pull loop, for the Message screen. */
    val chatSync: StateFlow<ChatSyncStatus> = _chatSync.asStateFlow()

    /**
     * Lightweight chat-only sync used by the 2-second poll: push unsent messages, then
     * pull anything created since our newest local message (with a small overlap so
     * near-simultaneous messages from others aren't missed). No-op if not configured.
     */
    suspend fun syncMessages(): Int = withContext(Dispatchers.IO) {
        if (!client.enabled) {
            _chatSync.value = ChatSyncStatus(ChatSyncStatus.State.OFFLINE)
            return@withContext 0
        }
        var n = 0
        var failed = false

        // PUSH unsent messages/edits/deletes. A false return is a real failure (disabled ruled out).
        val pending = chatDao.pending()
        if (pending.isNotEmpty()) {
            val body = json.encodeToString(
                pending.map {
                    ChatMsgDto(
                        it.id, it.username, it.displayName, it.body, it.createdAt,
                        if (it.updatedAt == 0L) it.createdAt else it.updatedAt, it.deleted
                    )
                }
            )
            if (client.upsert("app_messages", body)) {
                pending.forEach { chatDao.markSynced(it.id) }
                n += pending.size
            } else {
                failed = true
            }
        }

        // PULL anything changed (new / edited / deleted) since our latest local change.
        if (!failed) {
            val since = (chatDao.maxUpdatedAt() ?: 0L) - 4000L
            val txt = client.selectAll("app_messages", "select=*&updated_at_ms=gte.$since&order=updated_at_ms.asc")
            if (txt == null) {
                failed = true
            } else {
                val rows = runCatching { json.decodeFromString<List<ChatMsgDto>>(txt) }.getOrNull().orEmpty()
                for (r in rows) {
                    if (chatDao.pendingStateOf(r.id) == "P") continue          // keep local pending change
                    val localTs = chatDao.updatedAtOf(r.id)
                    val remoteTs = if (r.updated_at_ms == 0L) r.created_at else r.updated_at_ms
                    if (localTs != null && localTs >= remoteTs) continue        // local is newer/same
                    chatDao.upsert(
                        ChatMessageEntity(
                            r.id, r.username, r.display_name, r.body, r.created_at,
                            remoteTs, r.deleted, "S"
                        )
                    )
                    n++
                }
            }
        }

        _chatSync.value = if (failed) {
            ChatSyncStatus(ChatSyncStatus.State.ERROR, client.lastError)
        } else {
            ChatSyncStatus(ChatSyncStatus.State.OK)
        }
        n
    }

    suspend fun syncNow(): SyncOutcome = withContext(Dispatchers.IO) {
        if (!client.enabled) return@withContext SyncOutcome.Disabled
        try {
            val pushed = push()
            val pulled = pull() + syncProgrammes() + syncPhones() +
                syncChatReads() + syncReactions() + syncPresence() + syncDatasets()
            keyManager.lastSyncAt = System.currentTimeMillis()
            SyncOutcome.Success(pushed, pulled)
        } catch (t: Throwable) {
            SyncOutcome.Error(t.message ?: "Sync failed")
        }
    }

    /**
     * Best-effort pull of the accounts table only, run BEFORE login so a device can
     * authenticate a user that was created on another device. No-op if disabled.
     */
    suspend fun refreshUsers() = withContext(Dispatchers.IO) {
        if (!client.enabled) return@withContext
        runCatching { pullUsers() }
    }

    private suspend fun push(): Int {
        var n = 0

        val favs = dao.pendingFavorites()
        if (favs.isNotEmpty()) {
            val body = json.encodeToString(
                favs.map { FavoriteDto(it.itemType, it.itemId, it.label, it.createdAt, it.syncState == "D") }
            )
            if (client.upsert("app_favorites", body)) {
                favs.forEach {
                    if (it.syncState == "D") dao.purgeDeletedFavorite(it.itemType, it.itemId)
                    else dao.markFavoriteSynced(it.itemType, it.itemId)
                }
                n += favs.size
            }
        }

        val notes = dao.pendingNotes()
        if (notes.isNotEmpty()) {
            val body = json.encodeToString(
                notes.map { NoteDto(it.id, it.targetType, it.targetId, it.title, it.body, it.createdAt, it.updatedAt, it.deleted, it.authorName) }
            )
            if (client.upsert("app_notes", body)) {
                notes.forEach { dao.markNoteSynced(it.id) }
                n += notes.size
            }
        }

        val prefs = dao.pendingPrefs()
        if (prefs.isNotEmpty()) {
            val body = json.encodeToString(prefs.map { PrefDto(it.key, it.value, it.updatedAt) })
            if (client.upsert("app_prefs", body)) {
                prefs.forEach { dao.markPrefSynced(it.key) }
                n += prefs.size
            }
        }

        val acts = dao.pendingActivity()
        if (acts.isNotEmpty()) {
            val body = json.encodeToString(
                acts.map { ActivityDto(it.id, it.action, it.targetType, it.targetId, it.summary, it.createdAt) }
            )
            if (client.upsert("app_activity", body)) {
                acts.forEach { dao.markActivitySynced(it.id) }
                n += acts.size
            }
        }

        val users = userDao.pending()
        if (users.isNotEmpty()) {
            val body = json.encodeToString(
                users.map {
                    UserDto(it.username, it.passwordHash, it.salt, it.iterations, it.displayName, it.role, it.active, it.mustChangePassword, it.createdAt, it.updatedAt)
                }
            )
            if (client.upsert("app_users", body)) {
                users.forEach { userDao.markSynced(it.username) }
                n += users.size
            }
        }

        val moPending = moBeatDao.pendingSync()
        if (moPending.isNotEmpty()) {
            val body = json.encodeToString(
                moPending.map { MoVisitDto(it.beat, it.serialNo, it.visits, it.updatedBy, it.updatedAt ?: 0L) }
            )
            if (client.upsert("app_mo_visits", body)) {
                moPending.forEach { moBeatDao.markSynced(it.id) }
                n += moPending.size
            }
        }
        return n
    }

    private suspend fun pull(): Int {
        var n = 0

        client.selectAll("app_favorites")?.let { txt ->
            for (r in json.decodeFromString<List<FavoriteDto>>(txt)) {
                val local = dao.favoriteSyncState(r.item_type, r.item_id)
                if (local == "P" || local == "D") continue   // keep local pending change
                if (r.deleted) dao.deleteFavoriteHard(r.item_type, r.item_id)
                else dao.addFavorite(FavoriteEntity(r.item_type, r.item_id, r.label, r.created_at, "S"))
                n++
            }
        }

        client.selectAll("app_notes")?.let { txt ->
            for (r in json.decodeFromString<List<NoteDto>>(txt)) {
                val localTs = dao.noteUpdatedAt(r.id)
                if (localTs != null && localTs >= r.updated_at_ms) continue
                dao.upsertNote(
                    NoteEntity(
                        id = r.id, targetType = r.target_type, targetId = r.target_id,
                        title = r.title, body = r.body, createdAt = r.created_at,
                        updatedAt = r.updated_at_ms, deleted = r.deleted,
                        authorName = r.author, syncState = "S"
                    )
                )
                n++
            }
        }

        client.selectAll("app_prefs")?.let { txt ->
            for (r in json.decodeFromString<List<PrefDto>>(txt)) {
                val localTs = dao.prefUpdatedAt(r.key)
                if (localTs != null && localTs >= r.updated_at_ms) continue
                dao.setPref(UserPrefEntity(r.key, r.value, r.updated_at_ms, "S"))
                n++
            }
        }

        n += pullUsers()

        client.selectAll("app_mo_visits")?.let { txt ->
            for (r in json.decodeFromString<List<MoVisitDto>>(txt)) {
                val local = moBeatDao.byBeatSerial(r.beat, r.serial_no) ?: continue
                if (local.syncState == "P") continue                       // keep local pending edit
                if ((local.updatedAt ?: 0L) >= r.updated_at_ms) continue    // local is newer/same
                moBeatDao.applyRemoteVisits(r.beat, r.serial_no, r.visits, r.updated_by, r.updated_at_ms)
                n++
            }
        }
        return n
    }

    /**
     * Lightweight programme-only sync (push pending, pull all) — used by the notification
     * poller so it doesn't run a full [syncNow]. Also invoked from [syncNow]. No-op if disabled.
     */
    suspend fun syncProgrammes(): Int = withContext(Dispatchers.IO) {
        if (!client.enabled) return@withContext 0
        var n = 0
        val pending = moProgrammeDao.pending()
        if (pending.isNotEmpty()) {
            val body = json.encodeToString(
                pending.map { ProgrammeDto(it.id, it.beat, it.date, it.details, it.author, it.createdAt, it.updatedAt, it.deleted) }
            )
            if (client.upsert("app_programmes", body)) {
                pending.forEach { moProgrammeDao.markSynced(it.id) }
                n += pending.size
            }
        }
        client.selectAll("app_programmes")?.let { txt ->
            val rows = runCatching { json.decodeFromString<List<ProgrammeDto>>(txt) }.getOrNull().orEmpty()
            for (r in rows) {
                if (moProgrammeDao.syncStateOf(r.id) == "P") continue          // keep local pending edit
                val localTs = moProgrammeDao.updatedAtOf(r.id)
                if (localTs != null && localTs >= r.updated_at_ms) continue    // local is newer/same
                moProgrammeDao.upsert(
                    MoProgrammeEntity(
                        id = r.id, beat = r.beat, date = r.date, details = r.details,
                        author = r.author, createdAt = r.created_at, updatedAt = r.updated_at_ms,
                        deleted = r.deleted, syncState = "S"
                    )
                )
                n++
            }
        }
        n
    }

    /**
     * Lightweight staff phone-edit sync (push pending, pull all) — so any user's mobile-number
     * add/update reflects to everyone. Pulled edits are applied onto the local directory
     * (mobile_map for staff, the outsider row for outsiders) last-write-wins. No-op if disabled.
     */
    suspend fun syncPhones(): Int = withContext(Dispatchers.IO) {
        if (!client.enabled) return@withContext 0
        var n = 0
        val pending = phoneEditDao.pending()
        if (pending.isNotEmpty()) {
            val body = json.encodeToString(
                pending.map { PhoneEditDto(it.targetType, it.targetId, it.phone, it.updatedBy, it.updatedAt) }
            )
            if (client.upsert("app_staff_phones", body)) {
                pending.forEach { phoneEditDao.markSynced(it.targetType, it.targetId) }
                n += pending.size
            }
        }
        client.selectAll("app_staff_phones")?.let { txt ->
            val rows = runCatching { json.decodeFromString<List<PhoneEditDto>>(txt) }.getOrNull().orEmpty()
            for (r in rows) {
                if (phoneEditDao.syncStateFor(r.target_type, r.target_id) == "P") continue     // keep local pending
                val localTs = phoneEditDao.updatedAtFor(r.target_type, r.target_id)
                if (localTs != null && localTs >= r.updated_at_ms) continue                    // local newer/same
                // Record the override, then apply it to the read tables the UI already uses.
                phoneEditDao.upsert(
                    PhoneEditEntity(r.target_type, r.target_id, r.phone, r.updated_by, r.updated_at_ms, "S")
                )
                when (r.target_type) {
                    "EMPLOYEE" -> if (r.phone.isBlank()) mobileDao.deleteMap(r.target_id)
                    else mobileDao.upsert(MobileMapEntity(r.target_id, r.phone))
                    "OUTSIDER" -> outsiderDao.updateMobile(r.target_id, r.phone)
                }
                n++
            }
        }
        n
    }

    /** Merge remote accounts into the local table (last-write-wins), skipping local pending edits. */
    private suspend fun pullUsers(): Int {
        var n = 0
        client.selectAll("app_users")?.let { txt ->
            for (r in json.decodeFromString<List<UserDto>>(txt)) {
                if (userDao.syncStateOf(r.username) == "P") continue
                val local = userDao.byUsername(r.username)
                if (local != null && local.updatedAt >= r.updated_at_ms) continue
                userDao.upsert(
                    UserAccountEntity(
                        username = r.username, passwordHash = r.password_hash, salt = r.salt,
                        iterations = r.iterations, displayName = r.display_name, role = r.role,
                        active = r.active, mustChangePassword = r.must_change_password,
                        createdAt = r.created_at, updatedAt = r.updated_at_ms, syncState = "S"
                    )
                )
                n++
            }
        }
        return n
    }

    // ---- Chat read receipts (per-user "read up to" watermark) ----

    /** Advance my read watermark (never backwards) and push it so others see "seen by me". */
    suspend fun markChatRead(username: String, at: Long) = withContext(Dispatchers.IO) {
        val cur = chatReadDao.byUsername(username)
        if (cur != null && cur.lastReadAt >= at) return@withContext
        chatReadDao.upsert(ChatReadEntity(username, at, System.currentTimeMillis(), "P"))
        runCatching { syncChatReads() }
        Unit
    }

    suspend fun syncChatReads(): Int = withContext(Dispatchers.IO) {
        if (!client.enabled) return@withContext 0
        var n = 0
        val pending = chatReadDao.pending()
        if (pending.isNotEmpty()) {
            val body = json.encodeToString(pending.map { ChatReadDto(it.username, it.lastReadAt, it.updatedAt) })
            if (client.upsert("app_chat_reads", body)) {
                pending.forEach { chatReadDao.markSynced(it.username) }
                n += pending.size
            }
        }
        client.selectAll("app_chat_reads")?.let { txt ->
            val rows = runCatching { json.decodeFromString<List<ChatReadDto>>(txt) }.getOrNull().orEmpty()
            for (r in rows) {
                if (chatReadDao.syncStateOf(r.username) == "P") continue
                val localTs = chatReadDao.updatedAtOf(r.username)
                if (localTs != null && localTs >= r.updated_at_ms) continue
                chatReadDao.upsert(ChatReadEntity(r.username, r.last_read_at, r.updated_at_ms, "S"))
                n++
            }
        }
        n
    }

    // ---- Chat message reactions (WhatsApp-style, one emoji per user per message) ----

    /** Set/toggle my reaction on a message (emoji "" removes it), then push. Last-write-wins. */
    suspend fun reactToMessage(messageId: String, username: String, emoji: String) = withContext(Dispatchers.IO) {
        chatReactionDao.upsert(
            ChatReactionEntity(messageId, username, emoji, System.currentTimeMillis(), "P")
        )
        runCatching { syncReactions() }
        Unit
    }

    suspend fun syncReactions(): Int = withContext(Dispatchers.IO) {
        if (!client.enabled) return@withContext 0
        var n = 0
        val pending = chatReactionDao.pending()
        if (pending.isNotEmpty()) {
            val body = json.encodeToString(
                pending.map { ReactionDto(it.messageId, it.username, it.emoji, it.updatedAt) }
            )
            if (client.upsert("app_chat_reactions", body)) {
                pending.forEach { chatReactionDao.markSynced(it.messageId, it.username) }
                n += pending.size
            }
        }
        client.selectAll("app_chat_reactions")?.let { txt ->
            val rows = runCatching { json.decodeFromString<List<ReactionDto>>(txt) }.getOrNull().orEmpty()
            for (r in rows) {
                if (chatReactionDao.syncStateOf(r.message_id, r.username) == "P") continue
                val localTs = chatReactionDao.updatedAtOf(r.message_id, r.username)
                if (localTs != null && localTs >= r.updated_at_ms) continue
                chatReactionDao.upsert(ChatReactionEntity(r.message_id, r.username, r.emoji, r.updated_at_ms, "S"))
                n++
            }
        }
        n
    }

    // ---- Presence (online / last-seen heartbeat) ----

    /** Record my heartbeat and push it so others see me online. */
    suspend fun heartbeat(username: String) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        presenceDao.upsert(PresenceEntity(username, now, now, "P"))
        runCatching { syncPresence() }
        Unit
    }

    suspend fun syncPresence(): Int = withContext(Dispatchers.IO) {
        if (!client.enabled) return@withContext 0
        var n = 0
        val pending = presenceDao.pending()
        if (pending.isNotEmpty()) {
            val body = json.encodeToString(pending.map { PresenceDto(it.username, it.lastSeenAt, it.updatedAt) })
            if (client.upsert("app_presence", body)) {
                pending.forEach { presenceDao.markSynced(it.username) }
                n += pending.size
            }
        }
        client.selectAll("app_presence")?.let { txt ->
            val rows = runCatching { json.decodeFromString<List<PresenceDto>>(txt) }.getOrNull().orEmpty()
            for (r in rows) {
                if (presenceDao.syncStateOf(r.username) == "P") continue
                val localTs = presenceDao.updatedAtOf(r.username)
                if (localTs != null && localTs >= r.updated_at_ms) continue
                presenceDao.upsert(PresenceEntity(r.username, r.last_seen_at, r.updated_at_ms, "S"))
                n++
            }
        }
        n
    }

    // ---- Dataset snapshots (monthly data + arrangements, whole-set) ----

    /**
     * Push any pending dataset snapshot and pull newer ones, replacing the local set so a
     * monthly-data or arrangements upload on one device reflects to every user. No-op if disabled.
     */
    suspend fun syncDatasets(): Int = withContext(Dispatchers.IO) {
        if (!client.enabled) return@withContext 0
        var n = 0
        val pending = datasetDao.pending()
        if (pending.isNotEmpty()) {
            val body = json.encodeToString(
                pending.map { DatasetDto(it.type, it.payloadJson, it.count, it.uploadedBy, it.uploadedAt) }
            )
            if (client.upsert("app_datasets", body)) {
                pending.forEach { datasetDao.markSynced(it.type) }
                n += pending.size
            }
        }
        client.selectAll("app_datasets")?.let { txt ->
            val rows = runCatching { json.decodeFromString<List<DatasetDto>>(txt) }.getOrNull().orEmpty()
            for (r in rows) {
                if (datasetDao.syncStateOf(r.type) == "P") continue          // keep local pending upload
                val localTs = datasetDao.uploadedAtOf(r.type) ?: 0L
                if (localTs >= r.uploaded_at_ms) continue                    // local is newer/same
                runCatching { applyDataset(r) }
                datasetDao.upsert(
                    DatasetSnapshotEntity(r.type, r.payload, r.count, r.uploaded_by, r.uploaded_at_ms, "S")
                )
                n++
            }
        }
        n
    }

    // ---- FCM push-token registration ----

    /** Register this device's FCM token so the push Edge Function can reach it. No-op if disabled. */
    suspend fun registerPushToken(username: String?, token: String) = withContext(Dispatchers.IO) {
        if (!client.enabled || token.isBlank()) return@withContext
        val body = json.encodeToString(
            listOf(PushTokenDto(token, username, "android", System.currentTimeMillis()))
        )
        runCatching { client.upsert("app_push_tokens", body) }
        Unit
    }

    /** Replace the local directory set for one dataset type from a pulled snapshot payload. */
    private suspend fun applyDataset(r: DatasetDto) {
        when (r.type) {
            "DS", "GDS" -> {
                val list = json.decodeFromString<List<EmployeeEntity>>(r.payload)
                employeeDao.deleteByType(r.type)
                employeeDao.upsertAll(list)
            }
            "OUT" -> {
                val list = json.decodeFromString<List<OutsiderEntity>>(r.payload)
                outsiderDao.deleteMissing(list.map { it.resourceId })
                outsiderDao.upsertAll(list)
            }
            "TEL" -> {
                val list = json.decodeFromString<List<com.karursdo.data.db.MobileMapEntity>>(r.payload)
                mobileDao.clearMap()
                mobileDao.upsertMap(list)
            }
            "OFFICES" -> {
                val list = json.decodeFromString<List<OfficeMasterEntity>>(r.payload)
                officeMasterDao.replaceAll(list)
            }
            "ARR" -> {
                val list = json.decodeFromString<List<ArrangementEntity>>(r.payload)
                arrangementDao.replaceAll(list)
            }
        }
        // Refresh the freshness badge so the UI shows the cloud update.
        importMetaDao.upsert(
            ImportMetaEntity(r.type, r.uploaded_by?.let { "updated by $it" } ?: "cloud sync",
                r.count, java.time.LocalDate.now().toString())
        )
    }
}
