package com.fasterdownloads.core

import java.net.URL

/**
 * Browser-shaped request headers.
 *
 * A lot of file hosts answer `403 Forbidden` to requests that do not look like a browser
 * (anti-bot rules, "no download managers" rules, hotlink protection that wants a
 * `Referer`). Sending what a Chrome tab on Android would send keeps those hosts happy,
 * while `Accept-Encoding: identity` is still forced because byte ranges address bytes on
 * the wire and must never be compared against a compressed body length.
 */
object HttpHeaders {

    const val USER_AGENT: String =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Mobile Safari/537.36"

    private const val ACCEPT = "*/*"
    private const val ACCEPT_LANGUAGE = "en-US,en;q=0.9"
    private const val ACCEPT_ENCODING = "identity"

    /**
     * `https://example.com:8443/some/file.iso` -> `https://example.com:8443/`
     *
     * Hotlink protection compares the `Referer` origin, so sending the file's own origin
     * is the safest guess for a direct link.
     */
    fun refererFor(url: String): String? {
        val parsed = runCatching { URL(url) }.getOrNull() ?: return null
        val protocol = parsed.protocol ?: return null
        val host = parsed.host ?: return null
        if (host.isBlank() || !protocol.startsWith("http")) return null
        val port = parsed.port
        val portSuffix = if (port > 0 && port != parsed.defaultPort) ":$port" else ""
        return "$protocol://$host$portSuffix/"
    }

    /** Headers applied to every request; [range] is the `bytes=a-b` value, if any. */
    fun forRequest(url: String, range: String?): LinkedHashMap<String, String> {
        val headers = LinkedHashMap<String, String>()
        headers["User-Agent"] = USER_AGENT
        headers["Accept"] = ACCEPT
        headers["Accept-Language"] = ACCEPT_LANGUAGE
        headers["Accept-Encoding"] = ACCEPT_ENCODING
        refererFor(url)?.let { headers["Referer"] = it }
        if (range != null) headers["Range"] = range
        return headers
    }
}
