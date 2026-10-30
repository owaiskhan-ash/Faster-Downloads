package com.fasterdownloads.core

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

    @Test
    fun formatsHumanReadableSizes() {
        assertEquals("0 B", formatBytes(0L))
        assertEquals("512 B", formatBytes(512L))
        assertEquals("1.0 KB", formatBytes(1024L))
        assertEquals("1.5 MB", formatBytes(1536L * 1024L))
        assertEquals("2.0 GB", formatBytes(2048L * 1024L * 1024L))
    }

    @Test
    fun formatsSpeedsAndDurations() {
        assertEquals("--", formatSpeed(0L))
        assertEquals("1.0 MB/s", formatSpeed(1024L * 1024L))
        assertEquals("--", formatDuration(0L))
        assertEquals("45s", formatDuration(45L))
        assertEquals("2m 05s", formatDuration(125L))
        assertEquals("1h 01m", formatDuration(3660L))
    }
}
