package com.fasterdownloads.core

import android.net.Network
import java.io.IOException
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
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

    private const val CONNECT_TIMEOUT_MS = 15000
    private const val READ_TIMEOUT_MS = 30000
    private const val MAX_REDIRECTS = 8
    private const val BODY_SNIPPET_CHARS = 180

    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

    @Volatile
    private var cookieJarInstalled = false

    /**
     * One cookie jar for the probe, every chunk and every redirect.
     *
     * Hosts routinely hand out a session cookie (or an anti-bot token) on the first
     * response and then answer `403` to requests that arrive without it. Sharing the jar
     * across all of our connections - including the ones on the other radio - is what
     * turns those `403`s back into `200`/`206`.
     */
    fun installCookieJar() {
        if (cookieJarInstalled) return
        synchronized(this) {
            if (cookieJarInstalled) return
            cookieJarInstalled = true
            runCatching {
                CookieHandler.setDefault(CookieManager(null, CookiePolicy.ACCEPT_ALL))
            }
        }
    }

    /**
     * Opens a connection whose sockets are pinned to [network].
     *
     * [Network.openConnection] is the Android equivalent of Plexo's
     * `SO_BINDTODEVICE`: every socket the connection creates - including DNS
     * lookups - leaves through that radio and nothing else.
     */
    fun open(network: Network, url: String, method: String = "GET", range: String? = null): HttpURLConnection {
        installCookieJar()
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
            HttpHeaders.forRequest(target, range).forEach { (name, value) ->
                connection.setRequestProperty(name, value)
            }

            val code = connection.responseCode
            if (code in REDIRECT_CODES) {
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
        probeWithPlainGet(network, url)?.let { return it }
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
            // Nothing usable here; the plain GET / HEAD probes below report the reason.
        } finally {
            runCatching { connection?.disconnect() }
        }
        return null
    }

    /**
     * Some hosts allow a normal download but refuse every ranged request (a common
     * anti-"download accelerator" rule). Trying a plain GET before giving up means we
     * still deliver the file over a single stream instead of failing with an error.
     */
    private fun probeWithPlainGet(network: Network, url: String): RemoteInfo? {
        var connection: HttpURLConnection? = null
        try {
            connection = open(network, url, "GET", null)
            val code = connection.responseCode
            if (code < 200 || code > 299) return null
            val length = connection.contentLengthLong
            val name = fileName(connection, url)
            val etag = connection.getHeaderField("ETag")
            return RemoteInfo(length, false, name, etag)
        } catch (t: Throwable) {
            return null
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    private fun probeWithHead(network: Network, url: String): RemoteInfo {
        val connection = open(network, url, "HEAD", null)
        try {
            val code = connection.responseCode
            if (code < 200 || code > 299) throw IOException(describeResponse(connection))
            val acceptsRanges = connection.getHeaderField("Accept-Ranges").orEmpty().contains("bytes", true)
            return RemoteInfo(
                size = connection.contentLengthLong,
                rangeSupported = acceptsRanges,
                suggestedName = fileName(connection, url),
                etag = connection.getHeaderField("ETag"),
            )
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    /**
     * Turns a failed response into something the user can act on: status, server, a
     * short body snippet and a hint about what usually causes that code.
     */
    fun describeResponse(connection: HttpURLConnection, detail: String? = null): String {
        val code = runCatching { connection.responseCode }.getOrDefault(-1)
        val message = runCatching { connection.responseMessage }.getOrNull().orEmpty()
        val server = connection.getHeaderField("Server").orEmpty()
        val body = errorSnippet(connection)

        val builder = StringBuilder("HTTP $code")
        if (message.isNotBlank()) builder.append(' ').append(message)
        if (!detail.isNullOrBlank()) builder.append(" (").append(detail).append(')')
        if (server.isNotBlank()) builder.append(" - server: ").append(server)
        if (body != null) builder.append(" - ").append(body)
        hintFor(code)?.let { builder.append('\n').append(it) }
        return builder.toString()
    }

    private fun hintFor(code: Int): String? = when (code) {
        401, 403 -> "The site refused the request. It may block app or bot downloads, " +
            "require a login cookie, or the link may not be a direct file link. " +
            "Try copying the direct download link from the browser."
        404 -> "The file was not found at this URL."
        429 -> "The server is rate-limiting this device. Wait a moment and try again."
        500, 502, 503, 504 -> "The server is having trouble. Try again later."
        else -> null
    }

    private fun errorSnippet(connection: HttpURLConnection): String? = try {
        val stream = connection.errorStream ?: return null
        val text = stream.bufferedReader().use { reader ->
            val buffer = CharArray(512)
            val read = reader.read(buffer)
            if (read > 0) String(buffer, 0, read) else ""
        }
        text.replace(Regex("\\s+"), " ")
            .trim()
            .take(BODY_SNIPPET_CHARS)
            .ifBlank { null }
    } catch (t: Throwable) {
        null
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
