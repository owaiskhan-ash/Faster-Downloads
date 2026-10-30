package com.fasterdownloads.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Regression cover for the real-world magnet link that failed on device: a standard link
 * with percent-encoded `udp://` trackers, which `bt` 1.10 cannot decode on its own.
 */
class MagnetLinkTest {

    private val hash = "1f2aade9b21e75825ffee861e02da73ddfe0dffd"
    private val name = "Hope.2026.1080p.WEBRip.Korean.10Bit.DDP5.1.x265-NeoNoir"

    private val clean =
        "magnet:?xt=urn:btih:1F2AADE9B21E75825FFEE861E02DA73DDFE0DFFD" +
            "&dn=$name" +
            "&tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337%2Fannounce" +
            "&tr=http%3A%2F%2Fp4p.arenabg.com%3A1337%2Fannounce" +
            "&tr=udp%3A%2F%2Fopen.stealth.si%3A80%2Fannounce"

    @Test
    fun decodesPercentEncodedTrackers() {
        val link = MagnetLink.parse(clean)

        assertEquals(hash, link.infoHashHex)
        assertEquals(name, link.displayName)
        // The whole bug: bt never decodes these, so the host part must contain a real "://".
        assertTrue(link.trackers.contains("udp://tracker.opentrackr.org:1337/announce"))
        assertTrue(link.trackers.contains("http://p4p.arenabg.com:1337/announce"))
        assertTrue(link.trackers.contains("udp://open.stealth.si:80/announce"))
    }

    @Test
    fun repairsHtmlEscapedAmpersands() {
        val escaped = clean.replace("&", "&amp;")
        val link = MagnetLink.parse(escaped)

        assertEquals(hash, link.infoHashHex)
        assertEquals(name, link.displayName)
        assertEquals(3, link.trackers.size)
    }

    @Test
    fun repairsMarkdownAutolinkedTrackers() {
        val mangled =
            "magnet:?xt=urn:btih:1F2AADE9B21E75825FFEE861E02DA73DDFE0DFFD" +
                "&amp;dn=$name" +
                "&amp;tr=http%3A%2F%[2Fp4p.arenabg.com](http://2Fp4p.arenabg.com)%3A1337%2Fannounce" +
                "&amp;tr=udp%3A%2F%[2F9.rarbg.me](http://2F9.rarbg.me)%3A2780%2Fannounce"

        val link = MagnetLink.parse(mangled)

        assertEquals(hash, link.infoHashHex)
        assertEquals(listOf("http://p4p.arenabg.com:1337/announce", "udp://9.rarbg.me:2780/announce"), link.trackers)
    }

    @Test
    fun acceptsBase32InfoHashes() {
        val base32 = "D4VK32NSDZ2YEX765BQ6ALNHHXP6BX75"
        val link = MagnetLink.parse("magnet:?xt=urn:btih:$base32")
        assertEquals(hash, link.infoHashHex)
    }

    @Test
    fun keepsInfoHashBytesForTheEngine() {
        val bytes = MagnetLink.parse(clean).infoHashBytes
        assertEquals(20, bytes.size)
        assertEquals(0x1F.toByte(), bytes.first())
        assertEquals(0xFD.toByte(), bytes.last())
    }

    @Test
    fun dropsTrackersTheEngineCannotAnnounceTo() {
        val link = MagnetLink.parse(
            clean + "&tr=wss%3A%2F%2Ftracker.example.com%3A443" +
                "&tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337%2Fannounce",
        )
        assertFalse(link.trackers.any { it.startsWith("wss://") })
        // Duplicates are collapsed, not repeated.
        assertEquals(1, link.trackers.count { it.startsWith("udp://tracker.opentrackr.org") })
    }

    @Test
    fun decodesUtf8DisplayNames() {
        val link = MagnetLink.parse("magnet:?xt=urn:btih:$hash&dn=%E2%9C%93+Show%20S01")
        assertEquals("✓+Show S01", link.displayName)
    }

    @Test
    fun leavesMalformedEscapesAlone() {
        assertEquals("a%[b", MagnetLink.decode("a%[b"))
    }

    @Test
    fun rejectsLinksWithoutAnInfoHash() {
        try {
            MagnetLink.parse("https://example.com/file.zip")
            fail("expected MagnetFormatException")
        } catch (expected: MagnetFormatException) {
            assertTrue(expected.message!!.contains("magnet"))
        }
    }

    @Test
    fun rejectsMagnetsWithoutAHash() {
        try {
            MagnetLink.parse("magnet:?dn=name-only")
            fail("expected MagnetFormatException")
        } catch (expected: MagnetFormatException) {
            assertTrue(expected.message!!.contains("info-hash"))
        }
    }

    @Test
    fun rebuildsACleanMagnetUriForTheEngine() {
        val link = MagnetLink.parse(
            "magnet:?xt=urn:btih:$hash&dn=My%20Big%20File.mkv" +
                "&tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337%2Fannounce",
        )
        val uri = link.toMagnetUri()
        assertTrue(uri.startsWith("magnet:?xt=urn:btih:$hash"))
        assertTrue(uri.contains("dn=My%20Big%20File.mkv"))
        // Percent-encoding is applied again, so libtorrent's parser sees a valid URL.
        assertTrue(uri.contains("tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337%2Fannounce"))
        // Exactly three parameters: the info-hash, the name and the tracker.
        assertEquals(2, uri.count { it == '&' })
    }

    @Test
    fun percentEncodingKeepsUnreservedCharacters() {
        assertEquals("abc-._~", MagnetLink.percentEncode("abc-._~"))
        assertEquals("a%20b", MagnetLink.percentEncode("a b"))
        assertEquals("%E2%9C%93", MagnetLink.percentEncode("\u2713"))
        // A tracker's own query string survives: `?` and `=` are encoded, not dropped.
        assertEquals(
            "http%3A%2F%2Ft.example%2Fannounce%3Fpasskey%3Dabc",
            MagnetLink.percentEncode("http://t.example/announce?passkey=abc"),
        )
    }

    @Test
    fun repairedLinkRoundTripsThroughTheEngineUri() {
        // The kind of link a chat app mangles: HTML-escaped ampersands and a line break.
        val raw = "magnet:?xt=urn:btih:$hash&amp;dn=Show%20S01&amp;tr=udp%3A%2F%2Fopen.stealth.si%3A80%2Fannounce\n"
        val rebuilt = MagnetLink.parse(raw).toMagnetUri()
        val reparsed = MagnetLink.parse(rebuilt)
        assertEquals(hash, reparsed.infoHashHex)
        assertEquals("Show S01", reparsed.displayName)
        assertEquals(listOf("udp://open.stealth.si:80/announce"), reparsed.trackers)
    }
}
