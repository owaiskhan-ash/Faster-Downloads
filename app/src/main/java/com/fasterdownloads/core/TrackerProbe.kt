package com.fasterdownloads.core

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A peer endpoint, as a tracker announced it. */
data class PeerAddress(val host: String, val port: Int)

/** How one tracker replied to our announce. */
data class TrackerOutcome(val tracker: String, val peers: Int, val error: String?) {
    val host: String
        get() = tracker.substringAfter("://", tracker).substringBefore('/')
}

/**
 * What the HTTP(S) trackers said about this info-hash.
 *
 * The swarm engine announces to trackers by itself, but on a phone that traffic can be
 * blocked: carriers commonly drop UDP (which kills every `udp://` tracker) and `http://`
 * announces need cleartext HTTP to be allowed at all. Probing the HTTP trackers ourselves
 * gives two things the engine cannot: a plain-language reason when nothing answers, and a
 * list of peers we can hand to the engine up front (`x.pe` in the magnet), so one working
 * tracker can rescue a swarm that DHT alone cannot find.
 */
data class TrackerReport(
    val outcomes: List<TrackerOutcome>,
    val skipped: Int,
    val peers: List<PeerAddress>,
) {
    val answered: Int get() = outcomes.count { it.error == null }
    val failed: Int get() = outcomes.count { it.error != null }

    /** One line for the UI: what answered, what refused, and what we left to the engine. */
    val summary: String
        get() {
            if (outcomes.isEmpty() && skipped == 0) return "No trackers in this link - DHT only"
            val parts = mutableListOf<String>()
            if (outcomes.isNotEmpty()) parts += "$answered/${outcomes.size} HTTP trackers answered"
            if (peers.isNotEmpty()) parts += "${peers.size} peers"
            if (failed > 0) {
                val reason = outcomes.first { it.error != null }.error.orEmpty()
                parts += "$failed failed (${reason.take(60)})"
            }
            if (skipped > 0) parts += "$skipped UDP left to the engine"
            return parts.joinToString(" · ")
        }

    /** The most useful single sentence when the swarm looks unreachable. */
    val hint: String?
        get() = when {
            outcomes.isEmpty() && skipped > 0 && peers.isEmpty() ->
                "This link only carries UDP trackers and none could be probed. If you are on " +
                    "mobile data, peer traffic is often blocked - try Wi-Fi."

            outcomes.isNotEmpty() && answered == 0 ->
                "Every HTTP tracker refused or timed out. This is usually the network blocking " +
                    "peer traffic (mobile data) or a dead release - try Wi-Fi."

            outcomes.isNotEmpty() && answered > 0 && peers.isEmpty() ->
                "The trackers answered but know of no peers for this info-hash: the release may " +
                    "have no seeders left."

            else -> null
        }
}

object TrackerProbe {

    private const val CONNECT_TIMEOUT_SECONDS = 6L
    private const val READ_TIMEOUT_SECONDS = 8L
    private const val DEADLINE_MS = 12_000L
    private const val MAX_TRACKERS = 10
    private const val PEER_LIMIT = 80
    private const val USER_AGENT =
        "FasterDownloads/0.5 (+https://github.com/owaiskhan-ash/Faster-Downloads)"

    /** 20 bytes, stable for the session; trackers only care about the shape. */
    private const val PEER_ID = "-FD0001-000000000000"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(READ_TIMEOUT_SECONDS + 2, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private data class Attempt(val outcome: TrackerOutcome, val peers: List<PeerAddress>)

    /** Announces to the HTTP(S) trackers in [trackers]; UDP ones are left to the engine. */
    fun probe(trackers: List<String>, infoHash: ByteArray): TrackerReport {
        val http = trackers
            .filter { it.startsWith("http://", true) || it.startsWith("https://", true) }
            .distinct()
            .take(MAX_TRACKERS)
        val skipped = trackers.size - http.size
        if (http.isEmpty()) return TrackerReport(emptyList(), skipped, emptyList())

        val pool = Executors.newFixedThreadPool(4) { runnable ->
            Thread(runnable, "fd-tracker-probe").apply { isDaemon = true }
        }
        val attempts = mutableListOf<Attempt>()
        try {
            val tasks = http.map { tracker -> Callable { announce(tracker, infoHash) } }
            val results = pool.invokeAll(tasks, DEADLINE_MS, TimeUnit.MILLISECONDS)
            results.forEachIndexed { index, future ->
                val tracker = http[index]
                attempts += if (future.isCancelled || !future.isDone) {
                    Attempt(TrackerOutcome(tracker, 0, "timed out"), emptyList())
                } else {
                    runCatching { future.get() }
                        .getOrElse { failure ->
                            Attempt(
                                TrackerOutcome(tracker, 0, reason(failure)),
                                emptyList(),
                            )
                        }
                }
            }
        } finally {
            pool.shutdownNow()
        }

        val peers = attempts
            .flatMap { attempt -> attempt.peers }
            .distinctBy { peer -> "${peer.host}:${peer.port}" }
            .take(PEER_LIMIT)
        return TrackerReport(attempts.map { it.outcome }.sortedBy { it.tracker }, skipped, peers)
    }

    internal fun announce(tracker: String, infoHash: ByteArray): Attempt {
        val request = Request.Builder()
            .url(announceUrl(tracker, infoHash))
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.bytes() ?: ByteArray(0)
            parseFailure(body)?.let { failure ->
                return Attempt(TrackerOutcome(tracker, 0, failure), emptyList())
            }
            if (!response.isSuccessful) {
                return Attempt(TrackerOutcome(tracker, 0, "HTTP ${response.code}"), emptyList())
            }
            val peers = parsePeers(body)
            return Attempt(TrackerOutcome(tracker, peers.size, null), peers)
        }
    }

    /** `announce?info_hash=%..&peer_id=%..&…`, appended to any existing query. */
    internal fun announceUrl(
        tracker: String,
        infoHash: ByteArray,
        peerId: String = PEER_ID,
    ): String {
        val separator = if (tracker.contains('?')) '&' else '?'
        val query = listOf(
            "info_hash=${percentEncode(infoHash)}",
            "peer_id=${percentEncode(peerId.toByteArray(Charsets.US_ASCII))}",
            "port=6881",
            "uploaded=0",
            "downloaded=0",
            // Announce as a leecher: as a seeder some trackers return no peers at all, and
            // fetching metadata is a leecher's job anyway.
            "left=1",
            "compact=1",
            "no_peer_id=1",
            "numwant=80",
            "event=started",
        ).joinToString("&")
        return tracker.trimEnd('/', '?', '&') + separator + query
    }

    /** `failure reason` from a bencoded announce response, if the tracker refused us. */
    internal fun parseFailure(body: ByteArray): String? {
        val dict = Bencode.parse(body) as? Map<*, *> ?: return null
        val failure = (dict["failure reason"] as? ByteArray)?.toString(Charsets.UTF_8)
        return failure?.trim()?.takeIf { it.isNotBlank() }
    }

    /** Compact (6 bytes per peer) or dictionary form. */
    internal fun parsePeers(body: ByteArray): List<PeerAddress> {
        val dict = Bencode.parse(body) as? Map<*, *> ?: return emptyList()
        return when (val peers = dict["peers"]) {
            is ByteArray -> compactPeers(peers)
            is List<*> -> peers.mapNotNull { entry ->
                val peer = entry as? Map<*, *> ?: return@mapNotNull null
                val host = (peer["ip"] as? ByteArray)?.toString(Charsets.UTF_8)
                    ?: return@mapNotNull null
                val port = (peer["port"] as? Long)?.toInt() ?: return@mapNotNull null
                if (host.isBlank() || port !in 1..65535) null else PeerAddress(host, port)
            }

            else -> emptyList()
        }
    }

    private fun compactPeers(bytes: ByteArray): List<PeerAddress> {
        if (bytes.size < 6 || bytes.size % 6 != 0) return emptyList()
        val peers = ArrayList<PeerAddress>(bytes.size / 6)
        var index = 0
        while (index + 6 <= bytes.size) {
            val host = (0..3).joinToString(".") { offset ->
                (bytes[index + offset].toInt() and 0xFF).toString()
            }
            val port = ((bytes[index + 4].toInt() and 0xFF) shl 8) or
                (bytes[index + 5].toInt() and 0xFF)
            if (port in 1..65535 && !host.startsWith("0.")) peers += PeerAddress(host, port)
            index += 6
        }
        return peers
    }

    /** Raw-bytes percent encoding: `info_hash` is binary, so URLEncoder is not usable. */
    internal fun percentEncode(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 3)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            val char = value.toChar()
            val unreserved = (value < 128 && char.isLetterOrDigit()) ||
                char == '-' || char == '.' || char == '_' || char == '~'
            if (unreserved) {
                out.append(char)
            } else {
                out.append('%').append("%02X".format(value))
            }
        }
        return out.toString()
    }

    private fun reason(failure: Throwable): String =
        firstLine(failure.message) ?: failure.javaClass.simpleName
}

/**
 * Just enough bencode to read an announce response: integers, byte strings, lists and
 * dictionaries. Byte strings stay [ByteArray] because `peers` is binary.
 */
internal object Bencode {

    fun parse(bytes: ByteArray): Any? = runCatching { Reader(bytes).readValue() }.getOrNull()

    private class Reader(private val bytes: ByteArray) {

        private var index = 0

        fun readValue(): Any? {
            if (index >= bytes.size) return null
            return when (val marker = bytes[index].toInt().toChar()) {
                'i' -> readInteger()
                'l' -> readList()
                'd' -> readDictionary()
                in '0'..'9' -> readString()
                else -> null
            }
        }

        private fun readInteger(): Long {
            index++ // 'i'
            val end = indexOf('e')
            val value = String(bytes, index, end - index).toLongOrNull() ?: 0L
            index = end + 1
            return value
        }

        private fun readString(): ByteArray {
            val colon = indexOf(':')
            val length = String(bytes, index, colon - index).toIntOrNull() ?: 0
            val start = colon + 1
            val end = minOf(bytes.size, start + length)
            index = end
            return bytes.copyOfRange(start, end)
        }

        private fun readList(): List<Any?> {
            index++ // 'l'
            val items = mutableListOf<Any?>()
            while (index < bytes.size && bytes[index].toInt().toChar() != 'e') {
                items += readValue()
            }
            index++ // 'e'
            return items
        }

        private fun readDictionary(): Map<String, Any?> {
            index++ // 'd'
            val map = LinkedHashMap<String, Any?>()
            while (index < bytes.size && bytes[index].toInt().toChar() != 'e') {
                val key = readString().toString(Charsets.UTF_8)
                map[key] = readValue()
            }
            index++ // 'e'
            return map
        }

        private fun indexOf(char: Char): Int {
            var cursor = index
            while (cursor < bytes.size && bytes[cursor].toInt().toChar() != char) cursor++
            return cursor
        }
    }
}
