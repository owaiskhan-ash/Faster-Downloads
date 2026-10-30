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
