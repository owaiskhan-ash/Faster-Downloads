package com.fasterdownloads.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpHeadersTest {

    @Test
    fun sendsBrowserUserAgentAndIdentityEncoding() {
        val headers = HttpHeaders.forRequest("https://example.com/file.iso", null)
        assertTrue(headers["User-Agent"]!!.contains("Mozilla/5.0"))
        assertEquals("identity", headers["Accept-Encoding"])
        assertEquals("*/*", headers["Accept"])
        assertFalse(headers.containsKey("Range"))
    }

    @Test
    fun addsRangeHeaderWhenRequested() {
        val headers = HttpHeaders.forRequest("https://example.com/file.iso", "bytes=0-1023")
        assertEquals("bytes=0-1023", headers["Range"])
    }

    @Test
    fun refererIsTheOriginOfTheFile() {
        assertEquals("https://example.com/", HttpHeaders.refererFor("https://example.com/a/b/file.iso"))
        assertEquals("http://example.com/", HttpHeaders.refererFor("http://example.com/file.iso?token=abc"))
    }

    @Test
    fun refererKeepsNonDefaultPorts() {
        assertEquals("https://example.com:8443/", HttpHeaders.refererFor("https://example.com:8443/dl/file.iso"))
        assertEquals("https://example.com/", HttpHeaders.refererFor("https://example.com:443/dl/file.iso"))
    }

    @Test
    fun refererIsNullForUnusableUrls() {
        assertNull(HttpHeaders.refererFor("not a url"))
        assertNull(HttpHeaders.refererFor("ftp://example.com/file.iso"))
    }

    @Test
    fun refererIsAttachedToRequests() {
        val headers = HttpHeaders.forRequest("https://files.example.com/x.zip", null)
        assertEquals("https://files.example.com/", headers["Referer"])
    }
}
