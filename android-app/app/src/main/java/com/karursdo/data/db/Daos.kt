package com.karursdo.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Office summary row derived from the employee table (mirrors the web app's office index). */
data class OfficeSummary(
    val officeId: String,
    val officeName: String?,
    val dsCount: Int,
    val gdsCount: Int
)

@Dao
interface EmployeeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<EmployeeEntity>)

    @Query("DELETE FROM employees WHERE type = :type")
    suspend fun deleteByType(type: String)

    @Query("DELETE FROM employees WHERE type = :type AND employeeId NOT IN (:keepIds)")
    suspend fun deleteMissing(type: String, keepIds: List<String>)

    @Query(
        """SELECT officeId, MAX(officeName) AS officeName,
           SUM(CASE WHEN type = 'DS' THEN 1 ELSE 0 END) AS dsCount,
           SUM(CASE WHEN type = 'GDS' THEN 1 ELSE 0 END) AS gdsCount
           FROM employees GROUP BY officeId ORDER BY officeName COLLATE NOCASE"""
    )
    fun officeSummaries(): Flow<List<OfficeSummary>>

    @Query("SELECT * FROM employees WHERE officeId = :officeId ORDER BY name COLLATE NOCASE")
    fun byOffice(officeId: String): Flow<List<EmployeeEntity>>

    @Query("SELECT * FROM employees WHERE type = :type AND employeeId = :id LIMIT 1")
    suspend fun byId(type: String, id: String): EmployeeEntity?

    @Query(
        """SELECT * FROM employees WHERE name LIKE '%' || :q || '%'
           OR employeeId LIKE '%' || :q || '%'
           OR designation LIKE '%' || :q || '%'
           ORDER BY name COLLATE NOCASE LIMIT 40"""
    )
    suspend fun search(q: String): List<EmployeeEntity>

    @Query("SELECT COUNT(*) FROM employees WHERE type = :type")
    fun countByType(type: String): Flow<Int>

    @Query("SELECT COUNT(DISTINCT officeId) FROM employees")
    fun officeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM employees")
    suspend fun total(): Int

    @Query("SELECT * FROM employees")
    suspend fun allList(): List<EmployeeEntity>

    /** All staff as a reactive stream — drives birthdays/retirements. */
    @Query("SELECT * FROM employees")
    fun allFlow(): Flow<List<EmployeeEntity>>

    /** Distinct office ids that have a staff member whose name matches — for office search-by-staff. */
    @Query("SELECT DISTINCT officeId FROM employees WHERE name LIKE '%' || :q || '%'")
    fun officeIdsByStaffName(q: String): Flow<List<String>>
}

@Dao
interface OutsiderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<OutsiderEntity>)

    @Query("DELETE FROM outsiders WHERE resourceId NOT IN (:keepIds)")
    suspend fun deleteMissing(keepIds: List<String>)

    @Query("DELETE FROM outsiders")
    suspend fun clear()

    @Query(
        """SELECT * FROM outsiders WHERE fullName LIKE '%' || :q || '%'
           OR resourceId LIKE '%' || :q || '%' OR mobile LIKE '%' || :q || '%'
           OR pan LIKE '%' || :q || '%' OR email LIKE '%' || :q || '%'
           OR officeName LIKE '%' || :q || '%'
           ORDER BY fullName COLLATE NOCASE"""
    )
    fun search(q: String): Flow<List<OutsiderEntity>>

    @Query("SELECT * FROM outsiders ORDER BY fullName COLLATE NOCASE")
    fun all(): Flow<List<OutsiderEntity>>

    @Query("SELECT * FROM outsiders WHERE resourceId = :id LIMIT 1")
    suspend fun byId(id: String): OutsiderEntity?

    @Query("SELECT * FROM outsiders WHERE officeId = :officeId ORDER BY fullName COLLATE NOCASE")
    fun byOffice(officeId: String): Flow<List<OutsiderEntity>>

    @Query("SELECT COUNT(*) FROM outsiders")
    fun count(): Flow<Int>

    @Query("UPDATE outsiders SET mobile = :phone WHERE resourceId = :id")
    suspend fun updateMobile(id: String, phone: String)
}

@Dao
interface MobileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMap(items: List<MobileMapEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoster(items: List<MobileRosterEntity>)

    @Query("DELETE FROM mobile_roster")
    suspend fun clearRoster()

    @Query("DELETE FROM mobile_map")
    suspend fun clearMap()

    @Query("SELECT * FROM mobile_map")
    fun map(): Flow<List<MobileMapEntity>>

    @Query("SELECT phone FROM mobile_map WHERE employeeId = :employeeId LIMIT 1")
    suspend fun phoneFor(employeeId: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: MobileMapEntity)

    @Query("SELECT COUNT(*) FROM mobile_map")
    fun count(): Flow<Int>

    @Query("DELETE FROM mobile_map WHERE employeeId = :employeeId")
    suspend fun deleteMap(employeeId: String)
}

/** Manual staff mobile-number edits, synced across all users (last-write-wins). */
@Dao
interface PhoneEditDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(edit: PhoneEditEntity)

    @Query("SELECT * FROM phone_edits WHERE syncState = 'P'")
    suspend fun pending(): List<PhoneEditEntity>

    @Query("UPDATE phone_edits SET syncState = 'S' WHERE targetType = :type AND targetId = :id")
    suspend fun markSynced(type: String, id: String)

    @Query("SELECT syncState FROM phone_edits WHERE targetType = :type AND targetId = :id")
    suspend fun syncStateFor(type: String, id: String): String?

    @Query("SELECT updatedAt FROM phone_edits WHERE targetType = :type AND targetId = :id")
    suspend fun updatedAtFor(type: String, id: String): Long?
}

@Dao
interface OfficeMasterDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<OfficeMasterEntity>)

    @Query("DELETE FROM office_master")
    suspend fun clear()

    @Transaction
    suspend fun replaceAll(items: List<OfficeMasterEntity>) {
        clear(); upsertAll(items)
    }

    @Query("SELECT * FROM office_master ORDER BY officeName COLLATE NOCASE")
    fun all(): Flow<List<OfficeMasterEntity>>

    @Query("SELECT * FROM office_master")
    suspend fun allList(): List<OfficeMasterEntity>

    @Query("SELECT * FROM office_master WHERE officeId = :id LIMIT 1")
    suspend fun byId(id: String): OfficeMasterEntity?

    /**
     * Branch Offices that report to the given parent Sub Office. A BPO's [soName]
     * holds its parent S.O's name, so children of an S.O are the BPOs whose soName
     * equals the parent's office name (excluding the parent row itself).
     */
    @Query(
        """SELECT * FROM office_master
           WHERE soName = :parentName AND officeId <> :selfId AND officeType = 'BPO'
           ORDER BY officeName COLLATE NOCASE"""
    )
    fun childBranchOffices(parentName: String, selfId: String): Flow<List<OfficeMasterEntity>>

    @Query("SELECT COUNT(*) FROM office_master")
    fun count(): Flow<Int>
}

@Dao
interface ArrangementDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<ArrangementEntity>)

    @Query("DELETE FROM arrangements")
    suspend fun clear()

    @Transaction
    suspend fun replaceAll(items: List<ArrangementEntity>) {
        clear(); insertAll(items)
    }

    @Query("SELECT * FROM arrangements ORDER BY officeName COLLATE NOCASE, fromDate")
    fun all(): Flow<List<ArrangementEntity>>

    @Query("SELECT COUNT(*) FROM arrangements")
    fun count(): Flow<Int>
}

@Dao
interface ImportMetaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(meta: ImportMetaEntity)

    @Query("SELECT * FROM import_meta")
    fun all(): Flow<List<ImportMetaEntity>>
}

@Dao
interface MoBeatDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<MoBeatOfficeEntity>)

    @Query("SELECT * FROM mo_beat_offices WHERE beat = :beat ORDER BY serialNo")
    fun byBeat(beat: String): Flow<List<MoBeatOfficeEntity>>

    @Query("SELECT * FROM mo_beat_offices WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): MoBeatOfficeEntity?

    @Query("SELECT * FROM mo_beat_offices WHERE id = :id LIMIT 1")
    fun flowById(id: Long): Flow<MoBeatOfficeEntity?>

    @Query("SELECT COUNT(*) FROM mo_beat_offices WHERE beat = :beat")
    fun countByBeat(beat: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM mo_beat_offices")
    suspend fun total(): Int

    @Query("UPDATE mo_beat_offices SET visits = :visits WHERE id = :id")
    suspend fun updateVisits(id: Long, visits: String)

    /** Re-point a beat office to the correct office-master id without touching visits. */
    @Query("UPDATE mo_beat_offices SET matchedOfficeId = :officeId WHERE beat = :beat AND serialNo = :serial")
    suspend fun updateMatch(beat: String, serial: Int, officeId: String?)

    // ---- Visit edits with provenance + cross-user sync (keyed by beat+serialNo) ----
    @Query(
        "UPDATE mo_beat_offices SET visits = :visits, updatedBy = :by, updatedAt = :at, syncState = 'P' WHERE id = :id"
    )
    suspend fun updateVisitsWithProvenance(id: Long, visits: String, by: String?, at: Long)

    @Query("SELECT * FROM mo_beat_offices WHERE syncState = 'P'")
    suspend fun pendingSync(): List<MoBeatOfficeEntity>

    @Query("SELECT * FROM mo_beat_offices WHERE beat = :beat AND serialNo = :serial LIMIT 1")
    suspend fun byBeatSerial(beat: String, serial: Int): MoBeatOfficeEntity?

    @Query(
        "UPDATE mo_beat_offices SET visits = :visits, updatedBy = :by, updatedAt = :at, syncState = 'S' WHERE beat = :beat AND serialNo = :serial"
    )
    suspend fun applyRemoteVisits(beat: String, serial: Int, visits: String, by: String?, at: Long)

    @Query("UPDATE mo_beat_offices SET syncState = 'S' WHERE id = :id")
    suspend fun markSynced(id: Long)
}

/** Favorites, notes, profile/preferences and the activity trail for the logged-in user. */
@Dao
interface UserDataDao {

    // ---- Favorites ----
    @Query("SELECT * FROM user_favorites WHERE syncState <> 'D' ORDER BY createdAt DESC")
    fun favorites(): Flow<List<FavoriteEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM user_favorites WHERE itemType = :type AND itemId = :id AND syncState <> 'D')")
    fun isFavorite(type: String, id: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavorite(fav: FavoriteEntity)

    @Query("UPDATE user_favorites SET syncState = 'D' WHERE itemType = :type AND itemId = :id")
    suspend fun removeFavorite(type: String, id: String)

    @Query("SELECT COUNT(*) FROM user_favorites WHERE syncState <> 'D'")
    fun favoriteCount(): Flow<Int>

    // ---- Notes ----
    @Query("SELECT * FROM user_notes WHERE deleted = 0 ORDER BY updatedAt DESC")
    fun allNotes(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM user_notes WHERE deleted = 0 AND targetType = :type AND targetId = :id ORDER BY updatedAt DESC")
    fun notesFor(type: String, id: String): Flow<List<NoteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNote(note: NoteEntity)

    @Query("UPDATE user_notes SET deleted = 1, updatedAt = :ts, syncState = 'D' WHERE id = :id")
    suspend fun softDeleteNote(id: String, ts: Long)

    // ---- Preferences ----
    @Query("SELECT * FROM user_prefs")
    fun prefs(): Flow<List<UserPrefEntity>>

    @Query("SELECT value FROM user_prefs WHERE key = :key LIMIT 1")
    suspend fun pref(key: String): String?

    @Query("SELECT * FROM user_prefs WHERE key = :key LIMIT 1")
    fun prefFlow(key: String): Flow<UserPrefEntity?>

    /** All prefs whose key starts with [prefix] — used to read every user's designation at once. */
    @Query("SELECT * FROM user_prefs WHERE key LIKE :prefix || '%'")
    fun prefsWithPrefix(prefix: String): Flow<List<UserPrefEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setPref(pref: UserPrefEntity)

    // ---- Activity ----
    @Query("SELECT * FROM activity_log ORDER BY createdAt DESC LIMIT :limit")
    fun recentActivity(limit: Int): Flow<List<ActivityEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun logActivity(entry: ActivityEntity)

    @Query("DELETE FROM activity_log WHERE createdAt < :cutoff")
    suspend fun pruneActivityBefore(cutoff: Long)

    // ---- Sync plumbing (Phase 3) ----
    @Query("SELECT * FROM user_favorites WHERE syncState IN ('P', 'D')")
    suspend fun pendingFavorites(): List<FavoriteEntity>

    @Query("SELECT * FROM user_notes WHERE syncState IN ('P', 'D')")
    suspend fun pendingNotes(): List<NoteEntity>

    @Query("SELECT * FROM user_prefs WHERE syncState = 'P'")
    suspend fun pendingPrefs(): List<UserPrefEntity>

    @Query("SELECT * FROM activity_log WHERE syncState = 'P'")
    suspend fun pendingActivity(): List<ActivityEntity>

    @Query("UPDATE user_favorites SET syncState = 'S' WHERE itemType = :type AND itemId = :id")
    suspend fun markFavoriteSynced(type: String, id: String)

    @Query("DELETE FROM user_favorites WHERE itemType = :type AND itemId = :id AND syncState = 'D'")
    suspend fun purgeDeletedFavorite(type: String, id: String)

    // ---- Pull helpers (apply remote rows, last-write-wins) ----
    @Query("SELECT syncState FROM user_favorites WHERE itemType = :type AND itemId = :id LIMIT 1")
    suspend fun favoriteSyncState(type: String, id: String): String?

    @Query("DELETE FROM user_favorites WHERE itemType = :type AND itemId = :id")
    suspend fun deleteFavoriteHard(type: String, id: String)

    @Query("SELECT updatedAt FROM user_notes WHERE id = :id LIMIT 1")
    suspend fun noteUpdatedAt(id: String): Long?

    @Query("SELECT updatedAt FROM user_prefs WHERE key = :key LIMIT 1")
    suspend fun prefUpdatedAt(key: String): Long?

    @Query("UPDATE user_notes SET syncState = 'S' WHERE id = :id")
    suspend fun markNoteSynced(id: String)

    @Query("UPDATE user_prefs SET syncState = 'S' WHERE key = :key")
    suspend fun markPrefSynced(key: String)

    @Query("UPDATE activity_log SET syncState = 'S' WHERE id = :id")
    suspend fun markActivitySynced(id: String)
}

/** Group-chat messages. */
@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_messages WHERE deleted = 0 ORDER BY createdAt ASC")
    fun messages(): Flow<List<ChatMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: ChatMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(messages: List<ChatMessageEntity>)

    @Query("SELECT * FROM chat_messages WHERE syncState = 'P'")
    suspend fun pending(): List<ChatMessageEntity>

    @Query("UPDATE chat_messages SET syncState = 'S' WHERE id = :id")
    suspend fun markSynced(id: String)

    /** Edit a message body (marks it pending push, stamps the edit time). */
    @Query("UPDATE chat_messages SET body = :body, updatedAt = :at, syncState = 'P' WHERE id = :id")
    suspend fun edit(id: String, body: String, at: Long)

    /** Soft-delete a message (hidden everywhere, still synced so the deletion propagates). */
    @Query("UPDATE chat_messages SET deleted = 1, updatedAt = :at, syncState = 'P' WHERE id = :id")
    suspend fun softDelete(id: String, at: Long)

    @Query("SELECT username FROM chat_messages WHERE id = :id")
    suspend fun authorOf(id: String): String?

    @Query("SELECT syncState FROM chat_messages WHERE id = :id")
    suspend fun pendingStateOf(id: String): String?

    @Query("SELECT MAX(createdAt, updatedAt) FROM chat_messages WHERE id = :id")
    suspend fun updatedAtOf(id: String): Long?

    @Query("SELECT MAX(createdAt) FROM chat_messages")
    suspend fun maxCreatedAt(): Long?

    /** High-water mark for pulling edits/deletes as well as new messages. */
    @Query("SELECT MAX(MAX(createdAt), MAX(updatedAt)) FROM chat_messages")
    suspend fun maxUpdatedAt(): Long?

    /** New (non-deleted) messages from someone other than [me] — drives new-message notifications. */
    @Query("SELECT * FROM chat_messages WHERE createdAt > :since AND username != :me AND deleted = 0 ORDER BY createdAt ASC")
    suspend fun newerFromOthers(since: Long, me: String): List<ChatMessageEntity>
}

/** Mail Overseer tour programmes (per beat, per date). */
@Dao
interface MoProgrammeDao {
    @Query("SELECT * FROM mo_programmes WHERE deleted = 0 AND beat = :beat AND date >= :fromDate ORDER BY date ASC")
    fun upcomingForBeat(beat: String, fromDate: String): Flow<List<MoProgrammeEntity>>

    @Query("SELECT * FROM mo_programmes WHERE deleted = 0 AND date = :date ORDER BY beat ASC")
    fun byDate(date: String): Flow<List<MoProgrammeEntity>>

    /** All non-deleted programmes for a beat from [fromDate] onward (recent history + upcoming). */
    @Query("SELECT * FROM mo_programmes WHERE deleted = 0 AND beat = :beat AND date >= :fromDate ORDER BY date ASC")
    fun historyForBeat(beat: String, fromDate: String): Flow<List<MoProgrammeEntity>>

    /** Date-wise programmes for a beat within an inclusive range — drives the PDF report. */
    @Query("SELECT * FROM mo_programmes WHERE deleted = 0 AND beat = :beat AND date BETWEEN :from AND :to ORDER BY date ASC")
    suspend fun rangeForBeat(beat: String, from: String, to: String): List<MoProgrammeEntity>

    /** Edit an existing programme's date/details in place (keeps id, author, createdAt). */
    @Query("UPDATE mo_programmes SET date = :date, details = :details, updatedAt = :at, syncState = 'P' WHERE id = :id")
    suspend fun edit(id: String, date: String, details: String, at: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: MoProgrammeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<MoProgrammeEntity>)

    @Query("UPDATE mo_programmes SET deleted = 1, updatedAt = :at, syncState = 'P' WHERE id = :id")
    suspend fun softDelete(id: String, at: Long)

    @Query("SELECT * FROM mo_programmes WHERE syncState = 'P'")
    suspend fun pending(): List<MoProgrammeEntity>

    @Query("UPDATE mo_programmes SET syncState = 'S' WHERE id = :id")
    suspend fun markSynced(id: String)

    @Query("SELECT syncState FROM mo_programmes WHERE id = :id")
    suspend fun syncStateOf(id: String): String?

    @Query("SELECT updatedAt FROM mo_programmes WHERE id = :id")
    suspend fun updatedAtOf(id: String): Long?

    @Query("SELECT MAX(createdAt) FROM mo_programmes")
    suspend fun maxCreatedAt(): Long?

    /** Non-deleted programmes created after [since] — drives new-programme notifications (author filtered in code). */
    @Query("SELECT * FROM mo_programmes WHERE deleted = 0 AND createdAt > :since ORDER BY createdAt ASC")
    suspend fun createdSince(since: Long): List<MoProgrammeEntity>
}

/** Per-user chat read watermarks, synced for "Seen by …" receipts. */
@Dao
interface ChatReadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(read: ChatReadEntity)

    @Query("SELECT * FROM chat_reads")
    fun all(): Flow<List<ChatReadEntity>>

    @Query("SELECT * FROM chat_reads WHERE username = :username LIMIT 1")
    suspend fun byUsername(username: String): ChatReadEntity?

    @Query("SELECT * FROM chat_reads WHERE syncState = 'P'")
    suspend fun pending(): List<ChatReadEntity>

    @Query("UPDATE chat_reads SET syncState = 'S' WHERE username = :username")
    suspend fun markSynced(username: String)

    @Query("SELECT syncState FROM chat_reads WHERE username = :username")
    suspend fun syncStateOf(username: String): String?

    @Query("SELECT updatedAt FROM chat_reads WHERE username = :username")
    suspend fun updatedAtOf(username: String): Long?
}

@Dao
interface ChatReactionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(reaction: ChatReactionEntity)

    /** All current reactions (empty-emoji tombstones filtered out in the UI). */
    @Query("SELECT * FROM chat_reactions")
    fun all(): Flow<List<ChatReactionEntity>>

    @Query("SELECT * FROM chat_reactions WHERE messageId = :messageId AND username = :username LIMIT 1")
    suspend fun byKey(messageId: String, username: String): ChatReactionEntity?

    @Query("SELECT * FROM chat_reactions WHERE syncState = 'P'")
    suspend fun pending(): List<ChatReactionEntity>

    @Query("UPDATE chat_reactions SET syncState = 'S' WHERE messageId = :messageId AND username = :username")
    suspend fun markSynced(messageId: String, username: String)

    @Query("SELECT syncState FROM chat_reactions WHERE messageId = :messageId AND username = :username")
    suspend fun syncStateOf(messageId: String, username: String): String?

    @Query("SELECT updatedAt FROM chat_reactions WHERE messageId = :messageId AND username = :username")
    suspend fun updatedAtOf(messageId: String, username: String): Long?
}

/** Per-user presence heartbeats, synced for Online / last-seen display. */
@Dao
interface PresenceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: PresenceEntity)

    @Query("SELECT * FROM presence")
    fun all(): Flow<List<PresenceEntity>>

    @Query("SELECT * FROM presence WHERE syncState = 'P'")
    suspend fun pending(): List<PresenceEntity>

    @Query("UPDATE presence SET syncState = 'S' WHERE username = :username")
    suspend fun markSynced(username: String)

    @Query("SELECT syncState FROM presence WHERE username = :username")
    suspend fun syncStateOf(username: String): String?

    @Query("SELECT updatedAt FROM presence WHERE username = :username")
    suspend fun updatedAtOf(username: String): Long?
}

/** Whole-set dataset snapshots (monthly data + arrangements), synced to all users. */
@Dao
interface DatasetSnapshotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(snap: DatasetSnapshotEntity)

    @Query("SELECT * FROM dataset_snapshots WHERE type = :type LIMIT 1")
    suspend fun byType(type: String): DatasetSnapshotEntity?

    @Query("SELECT * FROM dataset_snapshots WHERE syncState = 'P'")
    suspend fun pending(): List<DatasetSnapshotEntity>

    @Query("UPDATE dataset_snapshots SET syncState = 'S' WHERE type = :type")
    suspend fun markSynced(type: String)

    @Query("SELECT uploadedAt FROM dataset_snapshots WHERE type = :type")
    suspend fun uploadedAtOf(type: String): Long?

    @Query("SELECT syncState FROM dataset_snapshots WHERE type = :type")
    suspend fun syncStateOf(type: String): String?
}

/** Login accounts (admin-provisioned). */
@Dao
interface UserAccountDao {
    @Query("SELECT * FROM users WHERE username = :username LIMIT 1")
    suspend fun byUsername(username: String): UserAccountEntity?

    @Query("SELECT * FROM users ORDER BY role, displayName COLLATE NOCASE")
    fun all(): Flow<List<UserAccountEntity>>

    @Query("SELECT COUNT(*) FROM users")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(user: UserAccountEntity)

    @Query("UPDATE users SET active = :active, updatedAt = :ts, syncState = 'P' WHERE username = :username")
    suspend fun setActive(username: String, active: Boolean, ts: Long)

    @Query("UPDATE users SET displayName = :name, updatedAt = :ts, syncState = 'P' WHERE username = :username")
    suspend fun setDisplayName(username: String, name: String, ts: Long)

    // ---- Sync plumbing ----
    @Query("SELECT * FROM users WHERE syncState = 'P'")
    suspend fun pending(): List<UserAccountEntity>

    @Query("SELECT syncState FROM users WHERE username = :username LIMIT 1")
    suspend fun syncStateOf(username: String): String?

    @Query("UPDATE users SET syncState = 'S' WHERE username = :username")
    suspend fun markSynced(username: String)
}
