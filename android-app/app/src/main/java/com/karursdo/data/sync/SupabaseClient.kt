package com.karursdo.data.sync

import android.util.Log
import com.karursdo.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Minimal Supabase (PostgREST) client built on HttpURLConnection — no third-party
 * networking dependency. Talks to the project's REST endpoint using the anon key.
 * Cloud sync is DISABLED unless both SUPABASE_URL and SUPABASE_ANON_KEY were provided
 * at build time (in local.properties); when disabled every call is a cheap no-op.
 */
@Singleton
class SupabaseClient @Inject constructor() {

    private val baseUrl = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val anonKey = BuildConfig.SUPABASE_ANON_KEY

    val enabled: Boolean get() = baseUrl.isNotBlank() && anonKey.isNotBlank()

    /** Short reason for the most recent request failure (HTTP status or exception), or null after a success. Best-effort, for diagnostics/UI. */
    @Volatile
    var lastError: String? = null
        private set

    /** Upsert a JSON array of rows into [table]. Returns true on 2xx. */
    suspend fun upsert(table: String, jsonArrayBody: String): Boolean = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext false
        request(
            method = "POST",
            path = "/rest/v1/$table",
            body = jsonArrayBody,
            extraHeaders = mapOf(
                "Content-Type" to "application/json",
                // Upsert on the primary key and don't echo rows back.
                "Prefer" to "resolution=merge-duplicates,return=minimal"
            )
        ) != null
    }

    /** GET all rows of [table] (optionally filtered by a raw PostgREST query). Returns the JSON array text, or null on failure. */
    suspend fun selectAll(table: String, query: String = "select=*"): String? = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext null
        request(
            method = "GET",
            path = "/rest/v1/$table?$query",
            body = null,
            extraHeaders = mapOf("Accept" to "application/json")
        )
    }

    /** Returns the response body on 2xx, or null on any error / non-2xx. */
    private fun request(
        method: String,
        path: String,
        body: String?,
        extraHeaders: Map<String, String>
    ): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("apikey", anonKey)
                setRequestProperty("Authorization", "Bearer $anonKey")
                extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
                if (body != null) {
                    doOutput = true
                    outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
            }
            val code = conn.responseCode
            if (code in 200..299) {
                lastError = null
                conn.inputStream.bufferedReader().use(BufferedReader::readText)
            } else {
                val err = runCatching { conn.errorStream?.bufferedReader()?.use(BufferedReader::readText) }.getOrNull()
                lastError = "HTTP $code"
                Log.w(TAG, "$method $path -> HTTP $code ${err?.take(200)}")
                null
            }
        } catch (t: Throwable) {
            lastError = t.message ?: "network error"
            Log.w(TAG, "$method $path failed: ${t.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private companion object {
        const val TAG = "SupabaseClient"
    }
}
