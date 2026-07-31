package com.karursdo.notify

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.karursdo.data.repo.SessionManager
import com.karursdo.data.security.DbKeyManager
import com.karursdo.data.sync.SyncEngine
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Firebase Cloud Messaging receiver. Registers this device's token (so the push Edge Function
 * can target it) and turns incoming pushes into local notifications. Firebase only initialises
 * when a google-services.json is present; otherwise this service is simply never invoked and the
 * app falls back to the existing foreground/background poll.
 */
class KsdMessagingService : FirebaseMessagingService() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface FcmEntryPoint {
        fun syncEngine(): SyncEngine
        fun session(): SessionManager
        fun keyManager(): DbKeyManager
    }

    private fun entry(): FcmEntryPoint =
        EntryPointAccessors.fromApplication(applicationContext, FcmEntryPoint::class.java)

    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onNewToken(token: String) {
        val e = entry()
        val username = e.session().current.value?.username ?: e.keyManager().lastUsername
        scope.launch { runCatching { e.syncEngine().registerPushToken(username, token) } }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val type = data["type"] ?: message.notification?.let { "chat" } ?: return
        val title = data["title"] ?: message.notification?.title ?: "Karur SDO"
        val body = data["body"] ?: message.notification?.body ?: ""
        when (type) {
            "programme" -> Notifications.notifyProgramme(applicationContext, title, body)
            else -> {
                // Don't buzz while the user is already reading the chat.
                if (!Notifications.chatScreenVisible.get()) {
                    Notifications.notifyChat(applicationContext, title, body)
                }
            }
        }
        // Pull the actual rows in the background so the local DB stays in step with the push.
        val e = entry()
        scope.launch {
            runCatching { e.syncEngine().syncMessages() }
            runCatching { e.syncEngine().syncProgrammes() }
        }
    }
}
