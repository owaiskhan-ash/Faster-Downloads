package com.fasterdownloads.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Redirect resolution is pure string work now (the transport hands `Location` headers
 * back instead of following them itself), so it can be pinned down in a plain JVM test.
 */
class HttpUtilTest {

    private val base = "https://cdn.example.com/files/a.zip"

    @Test
    fun keepsAbsoluteLocation() {
        assertEquals(
            "https://mirror.example.net/x/f.zip",
            HttpUtil.resolve(base, "https://mirror.example.net/x/f.zip"),
        )
    }

    @Test
    fun resolvesAbsolutePath() {
        assertEquals(
            "https://cdn.example.com/other/b.zip",
            HttpUtil.resolve(base, "/other/b.zip"),
        )
    }

    @Test
    fun resolvesRelativePath() {
        assertEquals(
            "https://cdn.example.com/files/b.zip",
            HttpUtil.resolve(base, "b.zip"),
        )
    }

    @Test
    fun resolvesProtocolRelativeLocation() {
        assertEquals(
            "https://mirror.example.net/x.zip",
            HttpUtil.resolve(base, "//mirror.example.net/x.zip"),
        )
    }

    @Test
    fun keepsQueryOnlyLocation() {
        assertEquals(
            "https://cdn.example.com/files/a.zip?token=1",
            HttpUtil.resolve(base, "?token=1"),
        )
    }
}
