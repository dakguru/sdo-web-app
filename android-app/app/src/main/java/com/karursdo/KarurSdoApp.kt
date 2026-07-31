package com.karursdo

import android.app.Application
import com.karursdo.notify.Notifications
import com.karursdo.notify.SyncNotificationWorker
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class KarurSdoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        Notifications.createChannels(this)
        SyncNotificationWorker.schedule(this)
    }
}
