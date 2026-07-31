package com.karursdo.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

/**
 * Periodic background check (~15-min cadence — Android's minimum) that pulls the latest
 * chat + programme data and raises notifications for anything new, even when the app is
 * closed. Uses a Hilt [EntryPoint] to reach [AppNotifier] (WorkManager builds workers itself).
 */
class SyncNotificationWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface NotifierEntryPoint {
        fun appNotifier(): AppNotifier
    }

    override suspend fun doWork(): Result {
        return try {
            val entryPoint = EntryPointAccessors.fromApplication(
                applicationContext, NotifierEntryPoint::class.java
            )
            entryPoint.appNotifier().syncAndNotify()
            Result.success()
        } catch (t: Throwable) {
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_NAME = "mo_chat_notification_sync"

        /** Schedule the recurring background check (idempotent — keeps any existing schedule). */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncNotificationWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }
    }
}
