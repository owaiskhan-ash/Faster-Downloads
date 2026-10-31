package com.fasterdownloads.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadStatsTest {

    @Test
    fun readsTheAllField() {
        val json = """
            {
              "updated": "2026-10-31T14:15:37Z",
              "assets": { "v0.9.1": 4, "v0.9.2": 2 },
              "all": 47
            }
        """.trimIndent()
        assertEquals(47L, DownloadStats.parseTotal(json))
    }

    @Test
    fun ignoresBeautyFormatting() {
        // The workflow writes pretty JSON, the app only needs the one field.
        assertEquals(1200L, DownloadStats.parseTotal("{\n  \"all\" : 1200\n}\n"))
    }

    @Test
    fun missingFieldIsNullNotZero() {
        // Zero would claim "nobody downloaded this"; null means "not known", and the header
        // simply shows nothing.
        assertNull(DownloadStats.parseTotal("{\"assets\": {\"v1\": 3}}"))
        assertNull(DownloadStats.parseTotal("<html>rate limited</html>"))
        assertNull(DownloadStats.parseTotal(""))
    }

    @Test
    fun formatsSmallNumbersExactly() {
        assertEquals("0", DownloadStats.format(0))
        assertEquals("47", DownloadStats.format(47))
        assertEquals("999", DownloadStats.format(999))
    }

    @Test
    fun formatsThousandsCompactly() {
        // The chip is tiny, so the number has to be.
        assertEquals("1k", DownloadStats.format(1_000))
        assertEquals("1.2k", DownloadStats.format(1_234))
        assertEquals("12.3k", DownloadStats.format(12_345))
        assertEquals("999.9k", DownloadStats.format(999_949))
        assertEquals("1M", DownloadStats.format(1_000_000))
        assertEquals("2.5M", DownloadStats.format(2_500_000))
    }
}
