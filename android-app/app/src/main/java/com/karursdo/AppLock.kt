package com.karursdo

/**
 * Coordinates the app-lock (biometric/PIN) auto-lock with in-app flows that briefly send the
 * app to the background on purpose — e.g. the system document picker used to upload data files.
 *
 * Without this, launching the file picker fires ProcessLifecycleOwner's ON_STOP, which re-locks
 * the app; the user is bounced to the login gate and the pending upload is lost. Screens set
 * [suppressNextAutoLock] right before launching such an activity; MainActivity consumes it on the
 * next ON_STOP so that one backgrounding does not lock the app.
 */
object AppLock {
    @Volatile
    var suppressNextAutoLock: Boolean = false
}
