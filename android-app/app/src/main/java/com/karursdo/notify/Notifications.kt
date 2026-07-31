package com.karursdo.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Local (device-side) notifications for new chat messages and Mail Overseer programmes.
 * No Firebase: new items are discovered by the existing Supabase poll (foreground) and a
 * periodic WorkManager job (background), then surfaced here.
 */
object Notifications {

    const val CHANNEL_CHAT = "chat_messages"
    const val CHANNEL_PROGRAMME = "mo_programmes"

    private const val NOTIF_ID_CHAT = 1001
    private const val NOTIF_ID_PROGRAMME = 1002

    /** True while the group-chat screen is on-screen — used to suppress chat notifications. */
    val chatScreenVisible = AtomicBoolean(false)

    /** Create the notification channels. Safe to call repeatedly (idempotent). */
    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_CHAT, "Chat messages", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "New messages in the Karur SDO staff group chat"
            }
        )
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRAMME, "Mail Overseer programmes", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "New Mail Overseer tour programmes"
            }
        )
    }

    fun notifyChat(context: Context, title: String, body: String) =
        post(context, CHANNEL_CHAT, NOTIF_ID_CHAT, title, body)

    fun notifyProgramme(context: Context, title: String, body: String) =
        post(context, CHANNEL_PROGRAMME, NOTIF_ID_PROGRAMME, title, body)

    private fun post(context: Context, channel: String, id: Int, title: String, body: String) {
        if (!hasPermission(context)) return
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = launch?.let {
            PendingIntent.getActivity(
                context, id, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val notif = NotificationCompat.Builder(context, channel)
            .setSmallIcon(com.karursdo.R.drawable.ic_karur_logo)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .apply { pi?.let { setContentIntent(it) } }
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notif) }
    }

    private fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
}
