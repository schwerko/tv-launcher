package com.azura.tvlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Background slideshow sources: NONE, COMMONS (Wikimedia Commons pictures of the day),
 * UNSPLASH (needs the user's key) or one or more image URLs separated by whitespace.
 * Images are cached in the app's cache dir and decoded down to the screen size.
 */
object WallpaperService {

    const val NONE = ""
    const val COMMONS = "commons"
    const val UNSPLASH = "unsplash"
    /** Pre-1.1 setting value (Bing's daily photos); Prefs maps it onto COMMONS. */
    const val LEGACY_BING = "bing"

    /** Photos to rotate through for a source setting. */
    suspend fun sources(context: Context, source: String, unsplashKey: String, max: Int = 12): List<WorldPhotos.Photo> =
        WorldPhotos.forSource(context, source, unsplashKey, max)

    suspend fun load(context: Context, photo: WorldPhotos.Photo, width: Int, height: Int, cache: String = "wallpapers"): Bitmap? =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.cacheDir, cache).apply { mkdirs() }
                val file = File(dir, sha1(photo.url))
                if (!file.exists() || file.length() == 0L) {
                    download(photo.url, file)
                    // Unsplash asks apps to report when a photo is actually used; best effort.
                    photo.downloadPing?.let { ping ->
                        runCatching { Net.request("GET", ping, photo.pingHeaders) }
                            .onFailure { Log.d("Wallpaper", "download ping failed: $it") }
                    }
                }
                decodeScaled(file, width, height)
            }.getOrNull()
        }

    /** Drops cached images that are not in the current rotation. */
    fun prune(context: Context, keep: List<String>, cache: String = "wallpapers") {
        val dir = File(context.cacheDir, cache)
        val names = keep.map { sha1(it) }.toSet()
        dir.listFiles()?.filter { it.name !in names }?.forEach { it.delete() }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun download(rawUrl: String, dest: File) {
        val conn = URL(rawUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", Net.USER_AGENT)
        try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
            val tmp = File(dest.path + ".part")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(dest)) throw IllegalStateException("rename failed")
        } finally {
            conn.disconnect()
        }
    }

    /** Decodes with subsampling, then centre-crops and scales to exactly width x height. */
    private fun decodeScaled(file: File, width: Int, height: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            file.delete()
            throw IllegalStateException("not an image")
        }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width && bounds.outHeight / (sample * 2) >= height) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val src = BitmapFactory.decodeFile(file.path, opts) ?: throw IllegalStateException("decode failed")
        val scale = maxOf(width.toFloat() / src.width, height.toFloat() / src.height)
        val scaledW = (src.width * scale).toInt().coerceAtLeast(width)
        val scaledH = (src.height * scale).toInt().coerceAtLeast(height)
        val scaled = Bitmap.createScaledBitmap(src, scaledW, scaledH, true)
        if (scaled !== src) src.recycle()
        val cropped = Bitmap.createBitmap(scaled, (scaledW - width) / 2, (scaledH - height) / 2, width, height)
        if (cropped !== scaled) scaled.recycle()
        return cropped
    }
}
