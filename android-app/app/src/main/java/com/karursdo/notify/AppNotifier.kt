package com.karursdo.notify

import android.content.Context
import com.karursdo.data.db.ChatDao
import com.karursdo.data.db.MoProgrammeDao
import com.karursdo.data.repo.MoBeatSeed
import com.karursdo.data.repo.SessionManager
import com.karursdo.data.security.DbKeyManager
import com.karursdo.data.sync.SyncEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pulls the latest chat + programme data (lightweight, no full sync) and raises a local
 * notification for anything newly created by *another* user since we last checked.
 *
 * Called from two places: a foreground poll (while the app is open, near-real-time) and a
 * periodic WorkManager job (while the app is closed, ~15-min cadence). A per-user "last
 * notified" high-water mark in [DbKeyManager] prevents duplicate alerts across both paths,
 * and is seeded on first run so an existing backlog never triggers a notification storm.
 */
@Singleton
class AppNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val chatDao: ChatDao,
    private val moProgrammeDao: MoProgrammeDao,
    private val session: SessionManager,
    private val keyManager: DbKeyManager,
    private val syncEngine: SyncEngine
) {
    private val running = AtomicBoolean(false)

    /** Pull latest data then notify about new items from others. Safe to call frequently. */
    suspend fun syncAndNotify() {
        if (!syncEngine.enabled) return
        if (!running.compareAndSet(false, true)) return   // skip if a check is already in flight
        try {
            runCatching { syncEngine.syncMessages() }
            runCatching { syncEngine.syncProgrammes() }
            runCatching { syncEngine.syncPhones() }    // keep staff mobile numbers fresh everywhere
            runCatching { syncEngine.syncDatasets() }  // pull monthly-data / arrangements uploads
            checkChat()
            checkProgrammes()
        } finally {
            running.set(false)
        }
    }

    private suspend fun checkChat() {
        // Fall back to the last signed-in username so a background worker running in a fresh
        // process (no in-memory session) can still tell our own messages from others'.
        val me = session.current.value?.username ?: keyManager.lastUsername ?: return
        val marker = keyManager.lastNotifiedChatAt
        if (marker == 0L) {                                  // first run: seed, don't alert on backlog
            keyManager.lastNotifiedChatAt = chatDao.maxCreatedAt() ?: 0L
            return
        }
        val fresh = chatDao.newerFromOthers(marker, me)
        if (fresh.isEmpty()) return
        keyManager.lastNotifiedChatAt = fresh.maxOf { it.createdAt }
        if (Notifications.chatScreenVisible.get()) return    // user is already reading the chat

        val last = fresh.last()
        if (fresh.size == 1) {
            Notifications.notifyChat(context, "New message · ${last.displayName}", last.body)
        } else {
            Notifications.notifyChat(
                context,
                "${fresh.size} new messages",
                "Latest — ${last.displayName}: ${last.body}"
            )
        }
    }

    private suspend fun checkProgrammes() {
        val marker = keyManager.lastNotifiedProgrammeAt
        val myAuthor = session.authorName() ?: keyManager.lastUsername
        if (marker == 0L) {                                  // first run: seed, don't alert on backlog
            keyManager.lastNotifiedProgrammeAt = moProgrammeDao.maxCreatedAt() ?: 0L
            return
        }
        val fresh = moProgrammeDao.createdSince(marker)
        if (fresh.isEmpty()) return
        keyManager.lastNotifiedProgrammeAt = fresh.maxOf { it.createdAt }

        val fromOthers = fresh.filter { it.author == null || it.author != myAuthor }
        if (fromOthers.isEmpty()) return

        val first = fromOthers.first()
        if (fromOthers.size == 1) {
            Notifications.notifyProgramme(
                context,
                "New ${beatLabel(first.beat)} programme",
                "${first.date} — ${first.details}" + (first.author?.let { "  (by $it)" } ?: "")
            )
        } else {
            Notifications.notifyProgramme(
                context,
                "${fromOthers.size} new MO programmes",
                "Latest — ${beatLabel(first.beat)} ${first.date}: ${first.details}"
            )
        }
    }

    private fun beatLabel(beat: String): String = when (beat) {
        MoBeatSeed.MO_I -> "MO I"
        MoBeatSeed.MO_II -> "MO II"
        else -> beat
    }
}
