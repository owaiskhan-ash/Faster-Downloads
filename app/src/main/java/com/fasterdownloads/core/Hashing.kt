package com.fasterdownloads.core

import java.io.File
import java.security.MessageDigest

object Hashing {

    /** SHA-256 of a file, hex encoded, or null when it cannot be read. */
    fun sha256(file: File): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    } catch (t: Throwable) {
        null
    }
}
