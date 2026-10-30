package com.fasterdownloads.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultTrackersTest {

    @Test
    fun aWellTrackedLinkGetsNoExtras() {
        val own = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.stealth.si:80/announce",
            "https://tracker.example.org/announce",
        )
        assertTrue(DefaultTrackers.extrasFor(own).isEmpty())
    }

    @Test
    fun aSingleTrackerLinkGetsAFewExtras() {
        val extras = DefaultTrackers.extrasFor(listOf("udp://only.example.org:1337/announce"))
        assertEquals(4, extras.size)
        // UDP first: the list is ordered so the fastest protocol leads.
        assertTrue(extras.first().startsWith("udp://"))
    }

    @Test
    fun trackersAreNeverDuplicated() {
        val own = listOf("udp://tracker.opentrackr.org:1337/announce")
        val extras = DefaultTrackers.extrasFor(own)
        assertTrue(extras.none { it == own.first() })
        assertEquals(extras.size, extras.distinct().size)
    }

    @Test
    fun everyTrackerIsAWellFormedAnnounceUrl() {
        DefaultTrackers.all().forEach { url ->
            assertTrue(url, url.startsWith("udp://") || url.startsWith("http://") || url.startsWith("https://"))
            assertTrue(url, url.contains("/announce"))
        }
    }
}
