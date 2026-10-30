package com.fasterdownloads.core

import android.net.Network
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.URL
import java.net.URLDecoder
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** What the server told us about the file before the transfer starts. */
data class RemoteInfo(
    val size: Long,
    val rangeSupported: Boolean,
    val suggestedName: String?,
    val etag: String?,
)

/**
 * One in-flight request/response pair.
 *
 * [close] hands the socket back to the per-radio keep-alive pool instead of tearing it
 * down, which is the whole point of this class: the next range request on the same radio
 * starts sending bytes immediately instead of paying a TCP + TLS handshake first.
 */
class HttpExchange internal constructor(val call: Call, val response: Response) : Closeable {

    val code: Int get() = response.code

    fun header(name: String): String? = response.header(name)

    /** Declared body length, or `-1` when the server does not say. */
    val contentLength: Long get() = response.body?.contentLength() ?: -1L

    fun bodyStream(): InputStream =
        (response.body ?: throw IOException("The server sent no response body")).byteStream()

    /** Aborts the transfer even while bytes are streaming (read throws immediately). */
    fun cancel() {
        runCatching { call.cancel() }
    }

    override fun close() {
        runCatching { response.close() }
    }
}

/**
 * HTTP transport for the downloader.
 *
 * Every radio gets its own [OkHttpClient], which means:
 *
 *  * its own [ConnectionPool], so a socket opened on Wi-Fi is never handed to a cellular
 *    worker (and vice versa) - the binding guarantee Plexo gets from `SO_BINDTODEVICE`
 *    still holds, because the client's `SocketFactory` and DNS are the [Network]'s;
 *  * keep-alive: a finished range request returns its socket to the pool and the next
 *    chunk reuses it. On a 60-100 ms mobile link that handshake is 10-30 % of the time
 *    spent on a medium chunk (the read timeout is 30 s, so an idle pooled socket is
 *    reused many times per download);
 *  * HTTP/1.1 on purpose. Multiplexing several ranges onto one TCP connection would undo
 *    the multi-flow trick that makes bonding faster in the first place: carriers and CDNs
 *    shape *per flow*, so N parallel TCP connections beat N streams inside one connection.
 *
 * Redirects are followed here (not by the client) so a hop can be cancelled and so the
 * same jar of cookies travels along; cookies are shared between both radios because hosts
 * hand out a session cookie on the first response and then answer `403` to requests that
 * arrive without it.
 */
object HttpUtil {

    private const val CONNECT_TIMEOUT_SECONDS = 15L
    private const val READ_TIMEOUT_SECONDS = 30L
    private const val MAX_REDIRECTS = 8
    private const val BODY_SNIPPET_BYTES = 512L
    private const val BODY_SNIPPET_CHARS = 180
    private const val IDLE_CONNECTIONS_PER_RADIO = 16
    private const val KEEP_ALIVE_MINUTES = 5L

    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

    private val clients = ConcurrentHashMap<Network, OkHttpClient>()
    private val cookieJar = SharedCookieJar()

    /** The pooled client bound to [network]; created once per radio and reused. */
    fun clientFor(network: Network): OkHttpClient = clients.computeIfAbsent(network) { radio ->
        buildClient(radio)
    }

    private fun buildClient(network: Network): OkHttpClient {
        val socketFactory = network.socketFactory
        // Explicit object rather than a SAM lambda: `Dns` is only a fun-interface in
        // newer OkHttp releases, and this has to compile on 4.12 as well.
        val networkDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val addresses = network.getAllByName(hostname)
                if (addresses.isEmpty()) throw UnknownHostException(hostname)
                return addresses.toList()
            }
        }
        return OkHttpClient.Builder()
            // Both of these are what keep the sockets on this radio and nowhere else.
            .socketFactory(socketFactory)
            .dns(networkDns)
            .cookieJar(cookieJar)
            .followRedirects(false)
            .followSslRedirects(false)
            // A pooled socket the server already closed is retried on a fresh one.
            .retryOnConnectionFailure(true)
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectionPool(
                ConnectionPool(IDLE_CONNECTIONS_PER_RADIO, KEEP_ALIVE_MINUTES, TimeUnit.MINUTES),
            )
            .build()
    }

    /** Drops the pooled client for [network]; its idle sockets are closed immediately. */
    fun release(network: Network) {
        val client = clients.remove(network) ?: return
        runCatching { client.connectionPool.evictAll() }
        runCatching { client.dispatcher.executorService.shutdown() }
    }

    /**
     * Executes one request, following redirects, and returns the open exchange.
     * The caller owns it and must [HttpExchange.close] it: closing is what puts the
     * socket back in the pool for the next chunk.
     */
    fun execute(
        network: Network,
        url: String,
        method: String = "GET",
        range: String? = null,
        isCancelled: () -> Boolean = { false },
    ): HttpExchange {
        var target = url
        var hops = 0
        while (true) {
            val request = buildRequest(target, method, range)
            val call = clientFor(network).newCall(request)
            val response = call.execute()
            if (response.code in REDIRECT_CODES) {
                val location = response.header("Location")
                runCatching { response.close() }
                if (location.isNullOrBlank()) throw IOException("Redirect without a Location header")
                target = resolve(target, location)
                hops++
                if (hops > MAX_REDIRECTS) throw IOException("Too many redirects")
                if (isCancelled()) throw CancelledException()
                continue
            }
            return HttpExchange(call, response)
        }
    }

    /** Resolves a `Location` header against the URL that produced it. */
    fun resolve(base: String, location: String): String {
        base.toHttpUrlOrNull()?.resolve(location)?.let { return it.toString() }
        return runCatching { URL(URL(base), location).toString() }.getOrDefault(location)
    }

    private fun buildRequest(url: String, method: String, range: String?): Request {
        val builder = Request.Builder().url(url)
        HttpHeaders.forRequest(url, range).forEach { (name, value) -> builder.header(name, value) }
        when (method) {
            "GET" -> builder.get()
            "HEAD" -> builder.head()
            else -> builder.method(method, null)
        }
        return builder.build()
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
        var exchange: HttpExchange? = null
        try {
            exchange = execute(network, url, "GET", "bytes=0-0")
            if (exchange.code == 206) {
                val contentRange = exchange.header("Content-Range").orEmpty()
                val total = contentRange.substringAfterLast('/', "-").trim().toLongOrNull() ?: -1L
                return RemoteInfo(total, total > 0, fileName(exchange, url), exchange.header("ETag"))
            }
            if (exchange.code == 200) {
                val length = exchange.contentLength
                return RemoteInfo(length, false, fileName(exchange, url), exchange.header("ETag"))
            }
        } catch (cancelled: CancelledException) {
            throw cancelled
        } catch (t: Throwable) {
            // Nothing usable here; the plain GET / HEAD probes below report the reason.
        } finally {
            exchange?.close()
        }
        return null
    }

    /**
     * Some hosts allow a normal download but refuse every ranged request (a common
     * anti-"download accelerator" rule). Trying a plain GET before giving up means we
     * still deliver the file over a single stream instead of failing with an error.
     */
    private fun probeWithPlainGet(network: Network, url: String): RemoteInfo? {
        var exchange: HttpExchange? = null
        try {
            exchange = execute(network, url, "GET")
            if (exchange.code !in 200..299) return null
            return RemoteInfo(
                size = exchange.contentLength,
                rangeSupported = false,
                suggestedName = fileName(exchange, url),
                etag = exchange.header("ETag"),
            )
        } catch (cancelled: CancelledException) {
            throw cancelled
        } catch (t: Throwable) {
            return null
        } finally {
            exchange?.close()
        }
    }

    private fun probeWithHead(network: Network, url: String): RemoteInfo {
        val exchange = execute(network, url, "HEAD")
        try {
            if (exchange.code !in 200..299) throw IOException(describeResponse(exchange))
            val acceptsRanges = exchange.header("Accept-Ranges").orEmpty().contains("bytes", true)
            return RemoteInfo(
                size = exchange.contentLength,
                rangeSupported = acceptsRanges,
                suggestedName = fileName(exchange, url),
                etag = exchange.header("ETag"),
            )
        } finally {
            exchange.close()
        }
    }

    /**
     * Turns a failed response into something the user can act on: status, server, a
     * short body snippet and a hint about what usually causes that code.
     */
    fun describeResponse(exchange: HttpExchange, detail: String? = null): String {
        val code = runCatching { exchange.code }.getOrDefault(-1)
        val message = runCatching { exchange.response.message }.getOrNull().orEmpty()
        val server = exchange.header("Server").orEmpty()
        val body = errorSnippet(exchange)

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

    private fun errorSnippet(exchange: HttpExchange): String? {
        // `peekBody` copies without stealing the stream, so the body stays closed later.
        if (exchange.response.body == null) return null
        return try {
            exchange.response.peekBody(BODY_SNIPPET_BYTES).string()
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(BODY_SNIPPET_CHARS)
                .ifBlank { null }
        } catch (t: Throwable) {
            null
        }
    }

    private fun fileName(exchange: HttpExchange, url: String): String? {
        val disposition = exchange.header("Content-Disposition").orEmpty()
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

    /**
     * One cookie jar shared by both radios.
     *
     * A session cookie picked up on the Wi-Fi request must also be sent on the cellular
     * one, otherwise the second radio starts collecting `403`s halfway through a file.
     */
    private class SharedCookieJar : CookieJar {

        private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isEmpty()) return
            val hostCookies = store.computeIfAbsent(url.host) { mutableListOf() }
            synchronized(hostCookies) {
                val now = System.currentTimeMillis()
                hostCookies.removeAll { cookie -> cookie.expiresAt < now }
                cookies.forEach { incoming ->
                    hostCookies.removeAll { existing ->
                        existing.name == incoming.name && existing.path == incoming.path
                    }
                    hostCookies.add(incoming)
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val hostCookies = store[url.host] ?: return emptyList()
            val now = System.currentTimeMillis()
            return synchronized(hostCookies) {
                hostCookies.removeAll { cookie -> cookie.expiresAt < now }
                hostCookies.filter { cookie -> cookie.matches(url) }
            }
        }
    }
}
