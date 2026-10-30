package com.fasterdownloads.core

/**
 * Pure byte-range planning math, kept free of Android types so it can be unit tested.
 *
 * A file of [totalBytes] is cut into chunks of [chunkSize] bytes; every chunk becomes
 * exactly one HTTP `Range` request. Chunks are deliberately much smaller than the file
 * so a fast radio can steal work from a slow one, but big enough that request
 * overhead stays negligible.
 */
object ChunkPlan {

    const val MIN_CHUNK = 1L * 1024L * 1024L
    const val MAX_CHUNK = 16L * 1024L * 1024L
    const val MAX_CHUNKS = 4000L

    /** Picks a chunk size that keeps every worker busy without flooding the server. */
    fun chunkSize(totalBytes: Long, workers: Int): Long {
        if (totalBytes <= 0L) return MIN_CHUNK
        val budget = workers.coerceAtLeast(2).toLong()
        // ~6 chunks per worker: enough to load-balance, few enough that a TLS handshake
        // per request does not eat a measurable share of the transfer.
        val targetChunks = (budget * 6L).coerceAtLeast(12L)
        val byWorker = totalBytes / targetChunks
        val byCount = totalBytes / MAX_CHUNKS
        return byWorker.coerceAtLeast(byCount).coerceAtLeast(MIN_CHUNK).coerceAtMost(MAX_CHUNK)
    }

    fun chunkCount(totalBytes: Long, chunkSize: Long): Int {
        if (totalBytes <= 0L || chunkSize <= 0L) return 0
        return ((totalBytes + chunkSize - 1L) / chunkSize).toInt()
    }

    /** Inclusive byte range for [index], or null when the index is past the end. */
    fun bounds(totalBytes: Long, chunkSize: Long, index: Int): LongRange? {
        if (totalBytes <= 0L || chunkSize <= 0L || index < 0) return null
        val start = index.toLong() * chunkSize
        if (start >= totalBytes) return null
        val end = minOf(start + chunkSize, totalBytes) - 1L
        return start..end
    }

    /**
     * Splits [totalWorkers] between the two radios, weighting Wi-Fi by
     * [wifiSharePercent] (Plexo-style 75/25, 70/30, ...). Both sides always get at
     * least one worker so the bonded link never sits idle.
     */
    fun workerCounts(totalWorkers: Int, wifiSharePercent: Int): Pair<Int, Int> {
        val workers = totalWorkers.coerceAtLeast(2)
        val share = wifiSharePercent.coerceIn(10, 90)
        val wifi = maxOf(1, Math.round(workers * share / 100f))
        val mobile = maxOf(1, workers - wifi)
        return wifi to mobile
    }
}
