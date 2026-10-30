package com.fasterdownloads.core

import java.util.Locale

fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024.0 && index < units.size - 1) {
        value /= 1024.0
        index++
    }
    return if (index == 0 || value >= 100.0) {
        String.format(Locale.US, "%.0f %s", value, units[index])
    } else {
        String.format(Locale.US, "%.1f %s", value, units[index])
    }
}

fun formatSpeed(bytesPerSecond: Long): String =
    if (bytesPerSecond <= 0L) "--" else "${formatBytes(bytesPerSecond)}/s"

/**
 * Percent share for each value, rounded so the parts add up to exactly 100.
 *
 * Used for the per-radio split ("Wi-Fi 62% · mobile 38%"): naive rounding would print
 * 62% + 39% = 101%, which looks like a bug in a speed readout.
 */
fun linkShares(values: List<Long>): List<Int> {
    if (values.isEmpty()) return emptyList()
    val total = values.sumOf { value -> value.coerceAtLeast(0L) }
    if (total <= 0L) return values.map { 0 }

    val exact = values.map { value -> value.coerceAtLeast(0L).toDouble() * 100.0 / total }
    val floors = exact.map { value -> value.toInt() }.toMutableList()
    var remainder = 100 - floors.sum()

    val byFraction = exact.indices.sortedByDescending { index -> exact[index] - floors[index] }
    var cursor = 0
    while (remainder > 0 && byFraction.isNotEmpty()) {
        floors[byFraction[cursor % byFraction.size]] += 1
        remainder--
        cursor++
    }
    return floors
}

fun formatDuration(seconds: Long): String {
    if (seconds <= 0L) return "--"
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return when {
        hours > 0 -> String.format(Locale.US, "%dh %02dm", hours, minutes)
        minutes > 0 -> String.format(Locale.US, "%dm %02ds", minutes, secs)
        else -> String.format(Locale.US, "%ds", secs)
    }
}

/** First non-blank line of an error message, so link rows stay readable. */
fun firstLine(message: String?): String? = message
    ?.lineSequence()
    ?.firstOrNull { it.isNotBlank() }
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
