package com.fasterdownloads.core

/**
 * Extra public HTTP(S) trackers used as a *fallback* when a magnet link cannot reach peers.
 *
 * Why this exists: a huge share of magnet links list only `udp://` trackers, and UDP is the
 * first thing a mobile carrier (or a restrictive Wi-Fi network) drops. If the link's one or
 * two `http://` trackers are dead as well - which is common, public trackers die constantly -
 * there is no remaining path to the swarm except DHT, which is also UDP. That is exactly the
 * "stuck looking for peers" case.
 *
 * These are announced to **once, from the phone, over TCP on ports 80/443** - the traffic no
 * network blocks - and only to fetch a peer list. The peers are handed to the engine through
 * `x.pe`, so the actual transfer still happens peer-to-peer.
 *
 * The list is deliberately short and made of long-lived trackers; it is not written into the
 * magnet, so nothing keeps announcing to them after the download starts.
 */
object DefaultTrackers {

    /** Up to [TrackerProbe]'s budget; extra entries are ignored, so order matters. */
    val http: List<String> = listOf(
        "http://bt1.archive.org:6969/announce",
        "https://tracker.gbitt.info/announce",
        "https://tracker.tamersunion.org:443/announce",
        "https://tr.bangumi.moe:9696/announce",
        "https://tracker.lelux.fi/announce",
        "https://tracker.dler.org/announce",
        "https://tracker.bt4g.com:443/announce",
        "https://open.acgnxtracker.com/announce",
        "https://tracker.nanoha.org:443/announce",
        "https://tracker.kuroy.me:443/announce",
        "http://bt2.archive.org:6969/announce",
        "https://tracker.gbitt.info:443/announce",
    )
}
