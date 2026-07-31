package com.karursdo.data.repo

import android.util.Base64
import com.karursdo.data.db.UserAccountDao
import com.karursdo.data.db.UserAccountEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

const val ROLE_ADMIN = "ADMIN"
const val ROLE_USER = "USER"

sealed interface LoginResult {
    data class Success(val user: UserAccountEntity) : LoginResult
    data object BadCredentials : LoginResult
    data object Disabled : LoginResult
}

sealed interface CreateResult {
    data object Ok : CreateResult
    data object Duplicate : CreateResult
    data class Invalid(val reason: String) : CreateResult
}

/**
 * Login-account management. Passwords are stored ONLY as a salted PBKDF2-HMAC-SHA256
 * hash (120k iterations). Verification is constant-time. Accounts are marked sync-pending
 * on every change so [com.karursdo.data.sync.SyncEngine] mirrors them to Supabase.
 */
@Singleton
class AuthRepository @Inject constructor(
    private val dao: UserAccountDao
) {
    fun users(): Flow<List<UserAccountEntity>> = dao.all()

    suspend fun userCount(): Int = withContext(Dispatchers.IO) { dao.count() }

    suspend fun accountByUsername(username: String): UserAccountEntity? =
        withContext(Dispatchers.IO) { dao.byUsername(username) }

    /** Seed the default administrator the first time the app runs with no accounts. */
    suspend fun seedAdminIfEmpty() = withContext(Dispatchers.IO) {
        if (dao.count() == 0) {
            dao.upsert(
                newUser(
                    DEFAULT_ADMIN_USERNAME, "Administrator", DEFAULT_ADMIN_PASSWORD,
                    ROLE_ADMIN, mustChange = true, System.currentTimeMillis()
                )
            )
        }
    }

    suspend fun login(username: String, password: String): LoginResult = withContext(Dispatchers.IO) {
        val u = dao.byUsername(username.trim()) ?: return@withContext LoginResult.BadCredentials
        if (!u.active) return@withContext LoginResult.Disabled
        if (verify(password, u.salt, u.iterations, u.passwordHash)) LoginResult.Success(u)
        else LoginResult.BadCredentials
    }

    suspend fun createUser(
        username: String, displayName: String, password: String, role: String
    ): CreateResult = withContext(Dispatchers.IO) {
        val uname = username.trim().lowercase()
        when {
            uname.length < 3 -> CreateResult.Invalid("Username must be at least 3 characters")
            !uname.matches(Regex("[a-z0-9_.]+")) -> CreateResult.Invalid("Use letters, digits, . or _ only")
            password.length < 6 -> CreateResult.Invalid("Password must be at least 6 characters")
            dao.byUsername(uname) != null -> CreateResult.Duplicate
            else -> {
                dao.upsert(
                    newUser(uname, displayName.trim().ifBlank { uname }, password, role, mustChange = true, System.currentTimeMillis())
                )
                CreateResult.Ok
            }
        }
    }

    suspend fun setActive(username: String, active: Boolean) = withContext(Dispatchers.IO) {
        dao.setActive(username, active, System.currentTimeMillis())
    }

    /** Admin edit of a user's display name; syncs to every device. */
    suspend fun setDisplayName(username: String, name: String) = withContext(Dispatchers.IO) {
        val n = name.trim()
        if (n.isNotEmpty()) dao.setDisplayName(username, n, System.currentTimeMillis())
    }

    /** Set/reset a password (admin reset or a user changing their own). Clears must-change. */
    suspend fun setPassword(username: String, newPassword: String): Boolean = withContext(Dispatchers.IO) {
        if (newPassword.length < 6) return@withContext false
        val u = dao.byUsername(username) ?: return@withContext false
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        dao.upsert(
            u.copy(
                passwordHash = b64(pbkdf2(newPassword, salt, ITER)),
                salt = b64(salt),
                iterations = ITER,
                mustChangePassword = false,
                updatedAt = System.currentTimeMillis(),
                syncState = "P"
            )
        )
        true
    }

    private fun newUser(
        username: String, displayName: String, password: String,
        role: String, mustChange: Boolean, now: Long
    ): UserAccountEntity {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return UserAccountEntity(
            username = username,
            passwordHash = b64(pbkdf2(password, salt, ITER)),
            salt = b64(salt),
            iterations = ITER,
            displayName = displayName,
            role = role,
            active = true,
            mustChangePassword = mustChange,
            createdAt = now,
            updatedAt = now,
            syncState = "P"
        )
    }

    private fun verify(password: String, saltB64: String, iterations: Int, expectedB64: String): Boolean {
        val salt = runCatching { Base64.decode(saltB64, Base64.NO_WRAP) }.getOrNull() ?: return false
        val expected = runCatching { Base64.decode(expectedB64, Base64.NO_WRAP) }.getOrNull() ?: return false
        return MessageDigest.isEqual(expected, pbkdf2(password, salt, iterations))
    }

    private fun pbkdf2(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)

    companion object {
        private const val ITER = 120_000
        const val DEFAULT_ADMIN_USERNAME = "admin"
        const val DEFAULT_ADMIN_PASSWORD = "Karur@2026"
    }
}
