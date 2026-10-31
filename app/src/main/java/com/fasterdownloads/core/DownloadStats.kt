package com.fasterdownloads.core

import android.os.SystemClock
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The app's all-time download counter.
 *
 * The number is not fetched from a live endpoint: it is the `all` field of
 * `stats/downloads.json`, a file committed to the repository by the *Downloads counter*
 * workflow. That file only ever grows (per version it keeps the highest count ever seen), so
 * the number shown here never goes backwards - not when a release is deleted, not when an APK
 * is re-uploaded, not when this request fails.
 *
 * Failures are silent by design: a phone with no connectivity shows no counter rather than an
 * error, because a vanity metric must never get in the way of a download.
 */
object DownloadStats {

    /** Where the committed snapshot lives. Pinned to `main`: that is the branch we publish. */
    private const val URL =
        "https://raw.githubusercontent.com/owaiskhan-ash/Faster-Downloads/main/stats/downloads.json"

    /** One fetch per app run is plenty; the file changes once a day. */
    private const val CACHE_TTL_MS = 6L * 60L * 60L * 1000L

    private val totalPattern = Regex("\"all\"\\s*:\\s*(\\d+)")

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var cached: Long? = null

    @Volatile
    private var cachedAt = 0L

    /**
     * The counter, or null when it has never been readable.
     *
     * Blocking: call it off the main thread ([fetch] wraps this in a background dispatcher).
     */
    fun total(): Long? {
        val known = cached
        if (known != null && SystemClock.elapsedRealtime() - cachedAt < CACHE_TTL_MS) return known
        val fetched = fetch()
        if (fetched != null) {
            cached = fetched
            cachedAt = SystemClock.elapsedRealtime()
        }
        return fetched ?: known
    }

    private fun fetch(): Long? = runCatching {
        val request = Request.Builder().url(URL).header("Accept", "application/json").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@runCatching null
            val body = response.body?.string() ?: return@runCatching null
            parseTotal(body)
        }
    }.getOrNull()

    /** Reads the `"all": <n>` field out of the snapshot; pure, so it is unit-tested. */
    fun parseTotal(json: String): Long? {
        val match = totalPattern.find(json) ?: return null
        return match.groupValues[1].toLongOrNull()
    }

    /** 999 -> "999", 1_234 -> "1.2k", 2_500_000 -> "2.5M". Pure, so it is unit-tested. */
    fun format(total: Long): String = when {
        total < 1_000L -> total.toString()
        total < 1_000_000L -> oneDecimal(total / 1_000.0) + "k"
        else -> oneDecimal(total / 1_000_000.0) + "M"
    }

    private fun oneDecimal(value: Double): String {
        val rounded = kotlin.math.round(value * 10.0) / 10.0
        return String.format(Locale.US, "%.1f", rounded).removeSuffix(".0")
    }
}
