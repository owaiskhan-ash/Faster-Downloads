package com.fasterdownloads.core

import android.net.Network
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/** What the server told us about the file before the transfer starts. */
data class RemoteInfo(
    val size: Long,
    val rangeSupported: Boolean,
    val suggestedName: String?,
    val etag: String?,
)

object HttpUtil {

    private const val USER_AGENT = "FasterDownloads/0.1 (Android)"
    private const val CONNECT_TIMEOUT_MS = 15000
    private const val READ_TIMEOUT_MS = 30000
    private const val MAX_REDIRECTS = 8

    /**
     * Opens a connection whose sockets are pinned to [network].
     *
     * [Network.openConnection] is the Android equivalent of Plexo's
     * `SO_BINDTODEVICE`: every socket the connection creates — including DNS
     * lookups — leaves through that radio and nothing else.
     */
    fun open(network: Network, url: String, method: String = "GET", range: String? = null): HttpURLConnection {
        var target = url
        var hops = 0
        while (true) {
            val connection = network.openConnection(URL(target)) as? HttpURLConnection
                ?: throw IOException("Unsupported URL: $target")
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", USER_AGENT)
            // Ranges must address bytes on the wire, so never accept an encoded body.
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (range != null) connection.setRequestProperty("Range", range)

            val code = connection.responseCode
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) throw IOException("Redirect without a Location header")
                target = URL(URL(target), location).toString()
                hops++
                if (hops > MAX_REDIRECTS) throw IOException("Too many redirects")
                continue
            }
            return connection
        }
    }

    /** Detects the file size and whether byte ranges are supported. */
    fun probe(network: Network, url: String): RemoteInfo {
        probeWithRange(network, url)?.let { return it }
        return probeWithHead(network, url)
    }

    /**
     * A one-byte ranged GET is far more reliable than HEAD: most CDNs answer
     * `206` with `Content-Range: bytes 0-0/<total>`, even when HEAD is blocked.
     */
    private fun probeWithRange(network: Network, url: String): RemoteInfo? {
        var connection: HttpURLConnection? = null
        try {
            connection = open(network, url, "GET", "bytes=0-0")
            val code = connection.responseCode
            if (code == 206) {
                val contentRange = connection.getHeaderField("Content-Range").orEmpty()
                val total = contentRange.substringAfterLast('/', "-").trim().toLongOrNull() ?: -1L
                val name = fileName(connection, url)
                val etag = connection.getHeaderField("ETag")
                return RemoteInfo(total, total > 0, name, etag)
            }
            if (code == 200) {
                val length = connection.contentLengthLong
                val name = fileName(connection, url)
                val etag = connection.getHeaderField("ETag")
                return RemoteInfo(length, false, name, etag)
            }
        } catch (t: Throwable) {
            // fall through to HEAD
        } finally {
            runCatching { connection?.disconnect() }
        }
        return null
    }

    private fun probeWithHead(network: Network, url: String): RemoteInfo {
        var connection: HttpURLConnection? = null
        try {
            connection = open(network, url, "HEAD", null)
            val code = connection.responseCode
            if (code < 200 || code > 299) throw IOException("HTTP $code")
            val acceptsRanges = connection.getHeaderField("Accept-Ranges").orEmpty().contains("bytes", true)
            return RemoteInfo(
                size = connection.contentLengthLong,
                rangeSupported = acceptsRanges,
                suggestedName = fileName(connection, url),
                etag = connection.getHeaderField("ETag"),
            )
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    private fun fileName(connection: HttpURLConnection, url: String): String? {
        val disposition = connection.getHeaderField("Content-Disposition").orEmpty()
        if (disposition.isNotBlank()) {
            val extended = extractDispositionValue(disposition, "filename*=")
            if (extended != null) return extended
            val plain = extractDispositionValue(disposition, "filename=")
            if (plain != null) return plain
        }
        val path = runCatching { URL(url).path }.getOrNull().orEmpty()
        val last = path.substringAfterLast('/', "")
        return if (last.isBlank()) null else runCatching { URLDecoder.decode(last, "UTF-8") }.getOrNull()
    }

    private fun extractDispositionValue(disposition: String, marker: String): String? {
        val lower = disposition.lowercase()
        val index = lower.indexOf(marker)
        if (index < 0) return null
        var value = disposition.substring(index + marker.length).trim()
        if (marker == "filename*=") {
            // filename*=UTF-8''name.ext
            val quoted = value.indexOf("''")
            if (quoted >= 0) value = value.substring(quoted + 2)
        }
        value = if (value.startsWith("\"")) {
            value.removePrefix("\"").substringBefore("\"")
        } else {
            value.substringBefore(";").trim()
        }
        if (value.isBlank()) return null
        return runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull() ?: value
    }
}
