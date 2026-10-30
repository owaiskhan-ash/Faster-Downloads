package com.fasterdownloads.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerProbeTest {

    private val hash = "1f2aade9b21e75825ffee861e02da73ddfe0dffd"

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    /** Round-trip helper: what a tracker decodes out of the query. */
    private fun decodePercent(value: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var index = 0
        while (index < value.length) {
            if (value[index] == '%') {
                out.write(value.substring(index + 1, index + 3).toInt(16))
                index += 3
            } else {
                out.write(value[index].code)
                index++
            }
        }
        return out.toByteArray()
    }

    @Test
    fun parsesCompactPeers() {
        val body = "d5:peers12:".toByteArray() +
            bytes(1, 2, 3, 4, 0x1A, 0xE1, 5, 6, 7, 8, 0x00, 0x50) +
            "e".toByteArray()

        val peers = TrackerProbe.parsePeers(body)

        assertEquals(listOf("1.2.3.4:6881", "5.6.7.8:80"), peers.map { "${it.host}:${it.port}" })
    }

    @Test
    fun parsesDictionaryPeers() {
        val body = "d5:peersld2:ip8:10.0.0.14:porti51413eeee".toByteArray()

        val peers = TrackerProbe.parsePeers(body)

        assertEquals(1, peers.size)
        assertEquals("10.0.0.1", peers.first().host)
        assertEquals(51413, peers.first().port)
    }

    @Test
    fun readsFailureReason() {
        val body = "d14:failure reason9:not founde".toByteArray()
        assertEquals("not found", TrackerProbe.parseFailure(body))
    }

    @Test
    fun noFailureReasonInAHealthyResponse() {
        val body = "d5:peers0:8:intervali1800ee".toByteArray()
        assertNull(TrackerProbe.parseFailure(body))
        assertTrue(TrackerProbe.parsePeers(body).isEmpty())
    }

    @Test
    fun announceUrlEncodesTheRawInfoHash() {
        val infoHash = MagnetLink.parse("magnet:?xt=urn:btih:$hash").infoHashBytes
        val url = TrackerProbe.announceUrl("http://tracker.example.com:1337/announce", infoHash)

        assertTrue(url.startsWith("http://tracker.example.com:1337/announce?info_hash="))
        // Whatever the encoding, a tracker must decode the original 20 binary bytes back.
        val encoded = url.substringAfter("info_hash=").substringBefore('&')
        assertTrue(decodePercent(encoded).contentEquals(infoHash))
        assertTrue(url.contains("&left=1"))
        assertTrue(url.contains("&compact=1"))
    }

    @Test
    fun announceUrlAppendsToAnExistingQuery() {
        val infoHash = ByteArray(20)
        val url = TrackerProbe.announceUrl("http://t.example/announce?passkey=abc", infoHash)
        assertTrue(url.startsWith("http://t.example/announce?passkey=abc&info_hash="))
    }

    @Test
    fun percentEncodingKeepsOnlyUnreservedCharacters() {
        assertEquals("%1FA", TrackerProbe.percentEncode(bytes(0x1F, 0x41)))
        assertEquals("abc-._~", TrackerProbe.percentEncode("abc-._~".toByteArray()))
    }

    @Test
    fun summaryNamesWhatAnswered() {
        val report = TrackerReport(
            outcomes = listOf(
                TrackerOutcome("http://a.example/announce", 12, null),
                TrackerOutcome("http://b.example/announce", 0, "HTTP 403"),
            ),
            skipped = 5,
            peers = List(12) { PeerAddress("1.2.3.4", 6881) },
        )

        assertTrue(report.summary.contains("1/2 HTTP trackers answered"))
        assertTrue(report.summary.contains("12 peers"))
        assertTrue(report.summary.contains("HTTP 403"))
        assertTrue(report.summary.contains("5 UDP"))
    }

    @Test
    fun hintsPointAtTheNetworkWhenNothingAnswers() {
        val dead = TrackerReport(
            outcomes = listOf(TrackerOutcome("http://a.example/announce", 0, "timed out")),
            skipped = 0,
            peers = emptyList(),
        )
        assertTrue(dead.hint!!.contains("Wi-Fi"))

        val noSeeders = TrackerReport(
            outcomes = listOf(TrackerOutcome("http://a.example/announce", 0, null)),
            skipped = 0,
            peers = emptyList(),
        )
        assertTrue(noSeeders.hint!!.contains("seeders"))
    }
}
