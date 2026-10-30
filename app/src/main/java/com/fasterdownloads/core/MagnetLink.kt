package com.fasterdownloads.core

import java.io.ByteArrayOutputStream

/** Raised when a magnet link cannot be turned into something the swarm engine can use. */
class MagnetFormatException(message: String) : IllegalArgumentException(message)

/**
 * A magnet link, parsed, decoded and repaired.
 *
 * This exists because of a real bug in the `bt` library (1.10): it never percent-decodes
 * the `tr` parameters. A *standard* magnet carries trackers as
 * `tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337`, and `MagnetUriParser` hands that string
 * to `TrackerService.getProtocol()`, which looks for a literal `://`, does not find one and
 * throws `IllegalArgumentException: Invalid URL: udp%3A%2F%2F...`. The whole download then
 * fails with an error, even though the swarm is perfectly reachable — and since almost
 * every public tracker is `udp://`, that is most real magnet links.
 *
 * So we do the parsing ourselves (percent-decoding `dn` and every `tr`, keeping only the
 * schemes `bt` can actually announce to) and hand the engine a `MagnetUri` *object*, which
 * skips its string parser entirely.
 *
 * The sanitiser also repairs what reality does to magnets in the wild: chat apps escape
 * ampersands (`&amp;`), link autodetection mangles them
 * (`http%3A%2F%[2Ftracker...](http://2Ftracker...)`), and mail/QR clients add quotes and
 * line breaks. All of those are stripped before parsing.
 */
data class MagnetLink(
    /** 40-character lowercase hex info-hash. */
    val infoHashHex: String,
    /** `dn` — the display name, useful before any metadata arrives. */
    val displayName: String?,
    /** Decoded, de-duplicated announce URLs with a scheme `bt` supports. */
    val trackers: List<String>,
) {

    val infoHashBytes: ByteArray
        get() {
            val bytes = ByteArray(infoHashHex.length / 2)
            for (index in bytes.indices) {
                bytes[index] = infoHashHex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
            return bytes
        }

    /** What to show in the UI: the real name if the link carried one. */
    val label: String
        get() = displayName?.takeIf { it.isNotBlank() } ?: infoHashHex.take(8).uppercase()

    companion object {

        private const val SCHEME = "magnet:"
        private const val BTIH = "urn:btih:"
        private const val HEX_HASH_LENGTH = 40
        private const val BASE32_HASH_LENGTH = 32

        private val MARKDOWN_TARGET = Regex("""\]\([^)]*\)""")
        private val ILLEGAL_CHARS = Regex("""[\s\[\]<>"']""")
        private val SUPPORTED_TRACKER_SCHEMES = listOf("udp://", "http://", "https://")

        fun parse(raw: String): MagnetLink {
            val cleaned = sanitize(raw)
            val schemeAt = cleaned.indexOf(SCHEME, ignoreCase = true)
            if (schemeAt < 0) throw MagnetFormatException("This is not a magnet link: it has no 'magnet:?' prefix.")

            val query = cleaned.substring(schemeAt + SCHEME.length).removePrefix("?")
            if (query.isBlank()) {
                throw MagnetFormatException("The magnet link is empty - it carries no info-hash.")
            }

            val params = collectParams(query)

            val rawHash = params["xt"].orEmpty()
                .firstOrNull { value -> value.startsWith(BTIH, ignoreCase = true) }
                ?.substring(BTIH.length)
                ?.trim()
                ?: throw MagnetFormatException(
                    "The link has no BitTorrent info-hash (xt=urn:btih:...). Magnet links that " +
                        "only carry a v2 (urn:btmh:) hash are not supported by this engine.",
                )

            val hex = toHexHash(rawHash)
            val name = params["dn"]?.firstOrNull { it.isNotBlank() }?.trim()
            val trackers = params["tr"].orEmpty()
                .map { it.trim() }
                .filter { isSupportedTracker(it) }
                .distinct()

            return MagnetLink(infoHashHex = hex, displayName = name, trackers = trackers)
        }

        /** `&amp;` → `&`, markdown autolinks removed, brackets/quotes/whitespace dropped. */
        internal fun sanitize(raw: String): String {
            var text = raw.trim()
            // Some clients double-escape, so run the amp repair until it stops changing.
            repeat(2) {
                text = text
                    .replace("&amp;", "&", ignoreCase = true)
                    .replace("&#38;", "&")
                    .replace("&#x26;", "&", ignoreCase = true)
            }
            text = MARKDOWN_TARGET.replace(text, "")
            return ILLEGAL_CHARS.replace(text, "")
        }

        private fun collectParams(query: String): Map<String, List<String>> {
            val params = LinkedHashMap<String, MutableList<String>>()
            query.split('&').forEach { pair ->
                if (pair.isBlank()) return@forEach
                val name = pair.substringBefore('=').lowercase()
                if (name.isBlank()) return@forEach
                val value = decode(pair.substringAfter('=', ""))
                params.getOrPut(name) { mutableListOf() }.add(value)
            }
            return params
        }

        private fun toHexHash(rawHash: String): String = when {
            rawHash.length == HEX_HASH_LENGTH && rawHash.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' } ->
                rawHash.lowercase()

            rawHash.length == BASE32_HASH_LENGTH -> {
                val bytes = base32ToBytes(rawHash)
                    ?: throw MagnetFormatException("The info-hash in this link is not valid base32.")
                bytes.joinToString("") { byte -> "%02x".format(byte) }
            }

            else -> throw MagnetFormatException(
                "The info-hash in this link is '${rawHash.take(16)}...', which is neither " +
                    "40 hex characters nor 32 base32 characters.",
            )
        }

        /**
         * Forgiving percent-decoding: works on bytes so UTF-8 names survive, and leaves a
         * malformed escape (like the `%[` an autolinker leaves behind) alone instead of
         * throwing the way `URLDecoder` would.
         */
        internal fun decode(value: String): String {
            if (!value.contains('%')) return value
            val bytes = ByteArrayOutputStream(value.length)
            var index = 0
            while (index < value.length) {
                val char = value[index]
                if (char == '%' && index + 2 < value.length) {
                    val code = value.substring(index + 1, index + 3).toIntOrNull(16)
                    if (code != null) {
                        bytes.write(code)
                        index += 3
                        continue
                    }
                }
                bytes.write(char.toString().toByteArray(Charsets.UTF_8))
                index++
            }
            return String(bytes.toByteArray(), Charsets.UTF_8)
        }

        /** RFC 4648 base32 → bytes; used by base32 info-hashes. */
        internal fun base32ToBytes(text: String): ByteArray? {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
            val out = ByteArrayOutputStream(20)
            var buffer = 0
            var bits = 0
            for (raw in text) {
                val char = raw.uppercaseChar()
                if (char == '=') break
                val value = alphabet.indexOf(char)
                if (value < 0) return null
                buffer = (buffer shl 5) or value
                bits += 5
                if (bits >= 8) {
                    bits -= 8
                    out.write((buffer shr bits) and 0xFF)
                }
            }
            val bytes = out.toByteArray()
            return if (bytes.size == 20) bytes else null
        }

        private fun isSupportedTracker(url: String): Boolean {
            val lower = url.lowercase()
            if (url.length <= lower.indexOf("://") + 3) return false
            return SUPPORTED_TRACKER_SCHEMES.any { scheme -> lower.startsWith(scheme) }
        }
    }
}
