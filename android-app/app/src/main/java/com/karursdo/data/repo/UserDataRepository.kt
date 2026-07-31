package com.karursdo.data.repo

import com.karursdo.data.db.ActivityEntity
import com.karursdo.data.db.FavoriteEntity
import com.karursdo.data.db.NoteEntity
import com.karursdo.data.db.UserDataDao
import com.karursdo.data.db.UserPrefEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * All reads/writes for the logged-in user's own layer: favourites, notes,
 * profile/preferences and the activity trail. Writes stamp rows as sync-PENDING
 * so the Phase-3 Supabase engine can push them; the UI never blocks on the network.
 */
@Singleton
class UserDataRepository @Inject constructor(
    private val dao: UserDataDao,
    private val session: SessionManager
) {
    private fun now() = System.currentTimeMillis()

    // ---- Favorites ----
    fun favorites(): Flow<List<FavoriteEntity>> = dao.favorites()
    fun favoriteCount(): Flow<Int> = dao.favoriteCount()
    fun isFavorite(type: String, id: String): Flow<Boolean> = dao.isFavorite(type, id)

    suspend fun toggleFavorite(type: String, id: String, label: String, currentlyOn: Boolean) =
        withContext(Dispatchers.IO) {
            if (currentlyOn) {
                dao.removeFavorite(type, id)
            } else {
                dao.addFavorite(FavoriteEntity(type, id, label, now()))
                log(ActivityType.FAVORITE, type, id, "Starred $label")
            }
        }

    // ---- Notes ----
    fun allNotes(): Flow<List<NoteEntity>> = dao.allNotes()
    fun notesFor(type: String, id: String): Flow<List<NoteEntity>> = dao.notesFor(type, id)

    suspend fun saveNote(
        id: String?,
        targetType: String,
        targetId: String?,
        title: String?,
        body: String
    ): String = withContext(Dispatchers.IO) {
        val ts = now()
        val noteId = id ?: UUID.randomUUID().toString()
        val existing = id != null
        dao.upsertNote(
            NoteEntity(
                id = noteId,
                targetType = targetType,
                targetId = targetId,
                title = title,
                body = body,
                createdAt = ts,
                updatedAt = ts,
                deleted = false,
                authorName = session.authorName(),
                syncState = "P"
            )
        )
        if (!existing) log(ActivityType.ADD_NOTE, targetType, targetId, title ?: "Note added")
        noteId
    }

    suspend fun deleteNote(id: String) = withContext(Dispatchers.IO) {
        dao.softDeleteNote(id, now())
    }

    // ---- Profile / preferences ----
    // Preferences are PER-USER: every key is namespaced with the signed-in username, so
    // one staff member's profile/preferences can never overwrite another's — even though
    // all rows share the single cloud `app_prefs` table. Each user gets a distinct row.
    private fun scoped(key: String): String {
        val u = session.current.value?.username?.trim()?.lowercase()
        return if (u.isNullOrBlank()) "u:_shared:$key" else "u:$u:$key"
    }

    fun prefs(): Flow<List<UserPrefEntity>> = dao.prefs()
    suspend fun getPref(key: String): String? = withContext(Dispatchers.IO) { dao.pref(scoped(key)) }

    suspend fun setPref(key: String, value: String) = withContext(Dispatchers.IO) {
        dao.setPref(UserPrefEntity(scoped(key), value, now()))
    }

    // ---- Global (shared) settings: NOT namespaced, same value for every user. Used for
    //      admin-set Mail Overseer assignments. Syncs via app_prefs by key.
    fun globalPrefFlow(key: String): Flow<UserPrefEntity?> = dao.prefFlow(key)
    suspend fun getGlobalPref(key: String): String? = withContext(Dispatchers.IO) { dao.pref(key) }
    suspend fun setGlobalPref(key: String, value: String) = withContext(Dispatchers.IO) {
        dao.setPref(UserPrefEntity(key, value, now()))
    }

    // ---- Admin-managed per-user profile fields (designation + date of joining) ----
    // Stored as global prefs keyed by username so admin edits sync to every device and
    // each user's Profile tab shows their own admin-fed details.
    fun designationFlow(username: String): Flow<UserPrefEntity?> = globalPrefFlow("prof_desig:$username")
    fun dojFlow(username: String): Flow<UserPrefEntity?> = globalPrefFlow("prof_doj:$username")
    suspend fun getDesignation(username: String) = getGlobalPref("prof_desig:$username")
    suspend fun getDoj(username: String) = getGlobalPref("prof_doj:$username")
    suspend fun setDesignation(username: String, value: String) = setGlobalPref("prof_desig:$username", value)
    suspend fun setDoj(username: String, value: String) = setGlobalPref("prof_doj:$username", value)

    /** Live map of username -> designation for every user who has one set (admin-fed). */
    fun designationsFlow(): Flow<Map<String, String>> =
        dao.prefsWithPrefix("prof_desig:").map { rows ->
            rows.mapNotNull { p ->
                val u = p.key.removePrefix("prof_desig:")
                if (u.isBlank() || p.value.isBlank()) null else u to p.value
            }.toMap()
        }

    // ---- Activity ----
    fun recentActivity(limit: Int = 100): Flow<List<ActivityEntity>> = dao.recentActivity(limit)

    suspend fun log(
        action: ActivityType,
        targetType: String? = null,
        targetId: String? = null,
        summary: String
    ) = withContext(Dispatchers.IO) {
        dao.logActivity(
            ActivityEntity(
                id = UUID.randomUUID().toString(),
                action = action.name,
                targetType = targetType,
                targetId = targetId,
                summary = summary,
                createdAt = now()
            )
        )
    }

    companion object {
        // Well-known preference keys (profile fields + UI prefs).
        const val PREF_DISPLAY_NAME = "display_name"
        const val PREF_ROLE = "role"
        const val PREF_DESIGNATION = "designation"
        const val PREF_OFFICE = "base_office"

        // Global (shared) keys — admin-assigned Mail Overseer usernames.
        const val PREF_MO_I_USER = "mo_i_username"
        const val PREF_MO_II_USER = "mo_ii_username"

        /** The only designations an admin may assign (no free text). Order = display/receipt order. */
        val DESIGNATIONS = listOf("ASPOs", "PA", "MO-I", "MO-II", "Admin")

        // Favorite / note target kinds.
        const val KIND_EMPLOYEE = "EMPLOYEE"
        const val KIND_OUTSIDER = "OUTSIDER"
        const val KIND_OFFICE = "OFFICE"
        const val KIND_GENERAL = "GENERAL"
    }
}

enum class ActivityType { LOGIN, VIEW, EDIT_PHONE, ADD_NOTE, FAVORITE, MO_VISIT, IMPORT, PROFILE }
