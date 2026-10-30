package com.fasterdownloads.core

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.IOException

data class PublishResult(val uri: String?, val path: String)

/**
 * Downloads land in app-private storage first (random access writes need a real
 * file), then get published to the public Downloads collection.
 */
object Storage {

    fun workingDir(context: Context): File {
        val dir = context.getExternalFilesDir("in-progress") ?: File(context.filesDir, "in-progress")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Where BitTorrent payloads are assembled before they are published. */
    fun torrentDir(context: Context): File {
        val base = context.getExternalFilesDir("torrents") ?: File(context.filesDir, "torrents")
        val dir = File(base, "active")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun sanitizeFileName(name: String): String {
        val cleaned = name
            .replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_")
            .trim()
            .trim('.')
        return if (cleaned.isBlank()) "download-${System.currentTimeMillis()}" else cleaned.take(120)
    }

    fun guessMimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "zip" -> "application/zip"
        "apk" -> "application/vnd.android.package-archive"
        "pdf" -> "application/pdf"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "txt" -> "text/plain"
        "json" -> "application/json"
        else -> "application/octet-stream"
    }

    fun publish(context: Context, source: File, displayName: String): PublishResult {
        val name = sanitizeFileName(displayName)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishWithMediaStore(context, source, name)
        } else {
            publishLegacy(context, source, name)
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun publishWithMediaStore(context: Context, source: File, name: String): PublishResult {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, guessMimeType(name))
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/FasterDownloads",
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: throw IOException("Could not create an entry in Downloads")

        try {
            val output = resolver.openOutputStream(uri)
                ?: throw IOException("Could not open the destination file")
            FileInputStream(source).use { input ->
                output.use { out -> input.copyTo(out, 256 * 1024) }
            }
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            return PublishResult(
                uri = uri.toString(),
                path = "Download/FasterDownloads/$name",
            )
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }

    private fun publishLegacy(context: Context, source: File, name: String): PublishResult {
        @Suppress("DEPRECATION")
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = File(downloads, "FasterDownloads")
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Could not create ${dir.absolutePath}")
        val target = uniqueFile(dir, name)
        source.copyTo(target, overwrite = false)
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), null, null)
        return PublishResult(uri = Uri.fromFile(target).toString(), path = target.absolutePath)
    }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val base = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "")
        var index = 1
        while (candidate.exists()) {
            val suffix = if (extension.isBlank()) "" else ".$extension"
            candidate = File(dir, "$base ($index)$suffix")
            index++
        }
        return candidate
    }
}
