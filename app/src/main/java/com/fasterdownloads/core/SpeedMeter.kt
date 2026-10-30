package com.fasterdownloads.core

/**
 * Sliding-window throughput meter.
 *
 * Deltas between two 450 ms ticks make a download look like it is sprinting and stalling:
 * a chunk boundary, a TLS handshake or the OS flushing a write buffer is enough to halve a
 * single sample. Averaging the last few seconds gives a number that reads like a speed
 * instead of noise, while still reacting to real changes within ~3 s.
 *
 * Pure Kotlin and single-threaded by contract (one meter per counter, ticked by the
 * progress thread), so it is easy to unit test.
 */
class SpeedMeter(
    private val windowMs: Long = 3000L,
    private val capacity: Int = 32,
) {
    private val stamps = LongArray(capacity)
    private val totals = LongArray(capacity)
    private var size = 0
    private var head = 0

    fun add(nowMs: Long, totalBytes: Long) {
        stamps[head] = nowMs
        totals[head] = totalBytes
        head = (head + 1) % capacity
        if (size < capacity) size++
    }

    /** Average bytes per second across the samples that fall inside the window. */
    fun rate(): Long {
        if (size < 2) return 0L
        val newest = (head - 1 + capacity) % capacity
        val newestTime = stamps[newest]
        val newestBytes = totals[newest]

        var oldest = newest
        for (step in 1 until size) {
            val index = (newest - step + capacity) % capacity
            if (newestTime - stamps[index] > windowMs) break
            oldest = index
        }
        if (oldest == newest) return 0L

        val elapsed = newestTime - stamps[oldest]
        if (elapsed <= 0L) return 0L

        val delta = newestBytes - totals[oldest]
        // Bytes can go backwards (a retried chunk rolls its partial progress back).
        return if (delta <= 0L) 0L else delta * 1000L / elapsed
    }

    fun reset() {
        size = 0
        head = 0
    }
}
