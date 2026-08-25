package com.karursdo.data.repo

import com.karursdo.data.db.EventDao
import com.karursdo.data.db.EventEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Karur Sub Division events + announcements shown on the dashboard banner. Every write stamps
 * the row sync-pending so [com.karursdo.data.sync.SyncEngine] mirrors it to `app_events` and it
 * reaches every user. Only Admin / ASP / PA users may edit (enforced in the UI via [canManageEvents]).
 */
@Singleton
class EventsRepository @Inject constructor(
    private val dao: EventDao,
    private val session: SessionManager
) {
    private fun now() = System.currentTimeMillis()

    fun events(): Flow<List<EventEntity>> = dao.all()

    /** Create or update an event/announcement. A blank [date] makes it a standing announcement. */
    suspend fun save(id: String?, date: String, title: String, important: Boolean): String =
        withContext(Dispatchers.IO) {
            val ts = now()
            val eventId = id ?: UUID.randomUUID().toString()
            dao.upsert(
                EventEntity(
                    id = eventId,
                    date = date.trim(),
                    title = title.trim(),
                    important = important,
                    author = session.authorName(),
                    createdAt = ts,
                    updatedAt = ts,
                    deleted = false,
                    syncState = "P"
                )
            )
            eventId
        }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) { dao.softDelete(id, now()) }

    companion object {
        /**
         * Whether a signed-in user may manage events/announcements and add/exit staff:
         * an Admin (account role) or a user whose admin-assigned designation is ASPOs / PA / Admin.
         */
        fun canManage(user: SessionUser?, designation: String?): Boolean {
            if (user == null) return false
            if (user.role == ROLE_ADMIN) return true
            return designation?.trim() in setOf("ASPOs", "PA", "Admin")
        }
    }
}
