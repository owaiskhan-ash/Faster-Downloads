package com.fasterdownloads.core

/**
 * Well-known open trackers, used as *extra* announce targets for magnets that list very few.
 *
 * Why: a magnet with one `udp://` tracker and nothing else has a single point of failure for
 * peer discovery - if that tracker is down, or does not know the torrent yet, the metadata
 * fetch waits on DHT alone, which is slow. Announcing to a handful of long-lived open
 * trackers in parallel turns that wait into seconds.
 *
 * They are only appended when the link carries fewer than [MAX_LINK_TRACKERS] of its own, so
 * a torrent that already lists plenty of trackers is left exactly as it is.
 */
object DefaultTrackers {

    /** A link with at least this many trackers gets no extras. */
    const val MAX_LINK_TRACKERS = 3

    /** At most this many extras are added. */
    private const val EXTRA_COUNT = 4

    /** UDP first: one round trip, and libtorrent handles them natively. */
    private val swarm: List<String> = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://tracker.openbittorrent.com:6969/announce",
        "https://tracker.gbitt.info/announce",
        "https://tracker.tamersunion.org:443/announce",
        "https://open.acgnxtracker.com/announce",
    )

    /** Extras for a link that already lists [linkTrackers]. */
    fun extrasFor(linkTrackers: List<String>): List<String> {
        if (linkTrackers.size >= MAX_LINK_TRACKERS) return emptyList()
        return swarm
            .filterNot { extra -> linkTrackers.any { it.equals(extra, ignoreCase = true) } }
            .take(EXTRA_COUNT)
    }

    /** Every known tracker, for tests and diagnostics. */
    fun all(): List<String> = swarm
}
