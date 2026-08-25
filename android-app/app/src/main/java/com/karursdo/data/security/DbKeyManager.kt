package com.karursdo.data.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The SQLCipher passphrase is a random 32-byte value generated on first launch and
 * stored inside EncryptedSharedPreferences (AES-256, key held in Android Keystore).
 * It never leaves the device and is not derivable from anything a user types.
 *
 * Hardening: a corrupted Tink keyset (OEM Keystore quirks, restored-backup remnants)
 * historically crashes EncryptedSharedPreferences at construction. We retry once after
 * wiping the pref file; if the secure store still cannot be opened we fall back to
 * plain SharedPreferences so the app keeps working (the DB is then still SQLCipher-
 * encrypted, just with a key stored without hardware backing — logged as a warning).
 */
@Singleton
class DbKeyManager @Inject constructor(private val context: Context) {

    private val prefs: SharedPreferences by lazy { openPrefs() }

    private fun openPrefs(): SharedPreferences {
        repeat(2) { attempt ->
            try {
                return EncryptedSharedPreferences.create(
                    context,
                    SECURE_FILE,
                    MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (t: Throwable) {
                Log.w(TAG, "EncryptedSharedPreferences open failed (attempt ${attempt + 1})", t)
                // Wipe a potentially-corrupt keyset and retry once.
                context.deleteSharedPreferences(SECURE_FILE)
            }
        }
        Log.w(TAG, "Falling back to plain SharedPreferences for the DB key store")
        return context.getSharedPreferences("${SECURE_FILE}_fallback", Context.MODE_PRIVATE)
    }

    fun dbPassphrase(): ByteArray {
        val existing = prefs.getString(KEY_DB, null)
        if (existing != null) return existing.toByteArray(Charsets.ISO_8859_1)
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        // ISO-8859-1 maps bytes 0..255 to chars losslessly.
        val stored = String(bytes, Charsets.ISO_8859_1)
        prefs.edit().putString(KEY_DB, stored).apply()
        return stored.toByteArray(Charsets.ISO_8859_1)
    }

    var appLockEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOCK, true)
        set(value) { prefs.edit().putBoolean(KEY_LOCK, value).apply() }

    /** Epoch millis of the last successful cloud sync (0 = never). Kept locally, never synced. */
    var lastSyncAt: Long
        get() = prefs.getLong(KEY_LAST_SYNC, 0L)
        set(value) { prefs.edit().putLong(KEY_LAST_SYNC, value).apply() }

    /** Username of the last user who signed in on this device — used for biometric quick-unlock. */
    var lastUsername: String?
        get() = prefs.getString(KEY_LAST_USER, null)
        set(value) { prefs.edit().putString(KEY_LAST_USER, value).apply() }

    /**
     * Username of the user who is CURRENTLY signed in (survives app restarts). Set on login and
     * cleared only on explicit logout — so the app stays unlocked across backgrounding/relaunch
     * and never re-prompts for biometric until the user logs out.
     */
    var activeUsername: String?
        get() = prefs.getString(KEY_ACTIVE_USER, null)
        set(value) { prefs.edit().putString(KEY_ACTIVE_USER, value).apply() }

    /** Newest chat-message createdAt we've already notified about (0 = not seeded yet). */
    var lastNotifiedChatAt: Long
        get() = prefs.getLong(KEY_NOTIFIED_CHAT, 0L)
        set(value) { prefs.edit().putLong(KEY_NOTIFIED_CHAT, value).apply() }

    /** Newest programme createdAt we've already notified about (0 = not seeded yet). */
    var lastNotifiedProgrammeAt: Long
        get() = prefs.getLong(KEY_NOTIFIED_PROG, 0L)
        set(value) { prefs.edit().putLong(KEY_NOTIFIED_PROG, value).apply() }

    // ---- Shared login PIN --------------------------------------------------
    // The PIN is the app's login gate. It is never stored in the clear: we keep
    // a random 16-byte salt and the PBKDF2-HMAC-SHA256 hash of (PIN + salt) inside
    // the same EncryptedSharedPreferences that hold the DB key. Verification is
    // constant-time. The PIN itself never leaves the device (Supabase sync only
    // ever carries user *data*, never the credential).

    fun hasPin(): Boolean = prefs.getString(KEY_PIN_HASH, null) != null

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = pbkdf2(pin, salt)
        prefs.edit()
            .putString(KEY_PIN_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_PIN_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            .apply()
    }

    fun verifyPin(pin: String): Boolean {
        val saltB64 = prefs.getString(KEY_PIN_SALT, null) ?: return false
        val hashB64 = prefs.getString(KEY_PIN_HASH, null) ?: return false
        val salt = runCatching { Base64.decode(saltB64, Base64.NO_WRAP) }.getOrNull() ?: return false
        val expected = runCatching { Base64.decode(hashB64, Base64.NO_WRAP) }.getOrNull() ?: return false
        return MessageDigest.isEqual(expected, pbkdf2(pin, salt))
    }

    fun clearPin() {
        prefs.edit().remove(KEY_PIN_SALT).remove(KEY_PIN_HASH).apply()
    }

    private fun pbkdf2(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, PIN_ITERATIONS, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private companion object {
        const val TAG = "DbKeyManager"
        const val SECURE_FILE = "karursdo_secure"
        const val KEY_DB = "db_passphrase_v1"
        const val KEY_LOCK = "app_lock_enabled"
        const val KEY_LAST_SYNC = "last_sync_at"
        const val KEY_LAST_USER = "last_username"
        const val KEY_ACTIVE_USER = "active_username"
        const val KEY_NOTIFIED_CHAT = "last_notified_chat_at"
        const val KEY_NOTIFIED_PROG = "last_notified_prog_at"
        const val KEY_PIN_SALT = "login_pin_salt_v1"
        const val KEY_PIN_HASH = "login_pin_hash_v1"
        const val PIN_ITERATIONS = 120_000
    }
}
