package com.azura.tvlauncher

import android.content.Context
import android.text.Html
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.time.LocalDate

/**
 * Photo sources for the background slideshow and the screensaver.
 *
 * - Wikimedia Commons "Picture of the day": free (public-domain or Creative Commons) images,
 *   no key needed. Each photo keeps its description and a credit line (author and licence),
 *   which the licences require to be shown.
 * - Unsplash: needs the user's own API key (unsplash.com/developers). Photos are hot-linked
 *   from Unsplash as its API terms require, credited "Photo by X on Unsplash", and the
 *   download endpoint is pinged the first time a photo is shown.
 * - A list of the user's own image URLs.
 */
object WorldPhotos {

    data class Photo(
        val url: String,
        val title: String,
        val place: String,
        val credit: String,
        /** Unsplash: endpoint to GET (with [pingHeaders]) the first time the photo is used. */
        val downloadPing: String? = null,
        val pingHeaders: Map<String, String> = emptyMap(),
    )

    private const val TAG = "Photos"
    private const val COMMONS_API = "https://commons.wikimedia.org/w/api.php"
    private const val UNSPLASH_API = "https://api.unsplash.com"

    /** Resolves a background/screensaver source setting into photos. */
    suspend fun forSource(context: Context, source: String, unsplashKey: String, max: Int): List<Photo> = when {
        source.isBlank() -> emptyList()
        source == WallpaperService.COMMONS -> commons(context).take(max)
        source == WallpaperService.UNSPLASH -> unsplash(unsplashKey, max)
        else -> source.split(Regex("\\s+")).filter { it.isNotBlank() }.map { Photo(it, "", "", "") }
    }

    // ---- Wikimedia Commons ----------------------------------------------------------------

    private const val COMMONS_CACHE = "commons_potd.json"
    private const val COMMONS_CACHE_MAX_AGE_MS = 12 * 60 * 60_000L
    private const val RETRY_AFTER_MS = 21_000L

    /**
     * Landscape-format pictures of the day from the last [days] days, newest first. The list
     * changes once a day, so it is kept on disk for 12 hours (and reused for longer if the API
     * is unreachable or rate-limiting): Wikimedia asks clients not to hammer the API, and the
     * launcher restarts more often than that.
     */
    suspend fun commons(context: Context, days: Int = 60): List<Photo> = withContext(Dispatchers.IO) {
        val cache = File(context.cacheDir, COMMONS_CACHE)
        val cached = readCache(cache)
        if (cached.isNotEmpty() && System.currentTimeMillis() - cache.lastModified() < COMMONS_CACHE_MAX_AGE_MS) {
            return@withContext cached
        }
        val fresh = fetchCommons(days)
        if (fresh.isNotEmpty()) writeCache(cache, fresh)
        fresh.ifEmpty { cached }
    }

    private fun readCache(file: File): List<Photo> = runCatching {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Photo(o.getString("url"), o.optString("title"), o.optString("place"), o.optString("credit"))
        }
    }.getOrDefault(emptyList())

    private fun writeCache(file: File, photos: List<Photo>) = runCatching {
        val arr = JSONArray()
        photos.forEach {
            arr.put(JSONObject().put("url", it.url).put("title", it.title).put("place", it.place).put("credit", it.credit))
        }
        file.writeText(arr.toString())
    }.onFailure { Log.w(TAG, "Commons cache write failed", it) }

    private fun fetchCommons(days: Int): List<Photo> =
        runCatching {
            val today = LocalDate.now()
            val dates = (0 until days).map { today.minusDays(it.toLong()) }
            // 1. Which file is each day's picture? (50 titles per request is the API's limit.)
            val files = ArrayList<String>()
            for (chunk in dates.chunked(50)) {
                val titles = chunk.joinToString("|") { "Template:Potd/$it" }
                val pages = commonsQuery("prop=images&imlimit=max&titles=${enc(titles)}")
                // Keep the date order: the API returns pages in its own order.
                val byTitle = HashMap<String, String>()
                for (i in 0 until pages.length()) {
                    val page = pages.getJSONObject(i)
                    val first = page.optJSONArray("images")?.optJSONObject(0)?.optString("title") ?: continue
                    byTitle[page.optString("title")] = first
                }
                chunk.forEach { d -> byTitle["Template:Potd/$d"]?.let { files.add(it) } }
            }
            // 2. A 1920px rendition plus description, author and licence for each file.
            val photos = ArrayList<Photo>()
            for (chunk in files.distinct().chunked(50)) {
                val pages = commonsQuery(
                    "prop=imageinfo&iiprop=url|size|mime|extmetadata&iiurlwidth=1920" +
                        "&iiextmetadatafilter=ImageDescription|Artist|LicenseShortName|ObjectName" +
                        "&titles=${enc(chunk.joinToString("|"))}"
                )
                val byTitle = HashMap<String, Photo>()
                for (i in 0 until pages.length()) {
                    val page = pages.getJSONObject(i)
                    val info = page.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
                    val w = info.optInt("width")
                    val h = info.optInt("height")
                    // Photos only, in landscape, big enough for a TV; skips posters, maps, SVGs.
                    if (info.optString("mime") != "image/jpeg" || w < 1280 || w < h * 1.2) continue
                    val url = info.optString("thumburl").ifBlank { info.optString("url") }
                    if (url.isBlank()) continue
                    val meta = info.optJSONObject("extmetadata") ?: JSONObject()
                    val description = firstSentence(plain(meta.value("ImageDescription")), 150)
                    val artist = cleanArtist(plain(meta.value("Artist")))
                    val licence = plain(meta.value("LicenseShortName"))
                    val credit = listOf(artist, licence, "Wikimedia Commons").filter { it.isNotBlank() }
                        .joinToString(" · ")
                    byTitle[page.optString("title")] = Photo(url, description, "", credit)
                }
                chunk.forEach { t -> byTitle[t]?.let { photos.add(it) } }
            }
            photos
        }.onFailure { Log.w(TAG, "Commons fetch failed", it) }.getOrDefault(emptyList())

    private fun commonsQuery(params: String): JSONArray {
        val url = "$COMMONS_API?action=query&format=json&formatversion=2&$params"
        var r = Net.request("GET", url)
        if (r.code == 429) {
            // Rate-limited (Wikimedia answers 429 with a Retry-After of about 20 s): wait once.
            Thread.sleep(RETRY_AFTER_MS)
            r = Net.request("GET", url)
        }
        if (r.code !in 200..299) throw IllegalStateException("HTTP ${r.code} for $url")
        return JSONObject(r.body).optJSONObject("query")?.optJSONArray("pages") ?: JSONArray()
    }

    private fun JSONObject.value(key: String): String = optJSONObject(key)?.optString("value") ?: ""

    /** Strips the HTML the Commons metadata is written in and squashes whitespace. */
    @Suppress("DEPRECATION")
    private fun plain(html: String): String =
        Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT).toString().replace(Regex("\\s+"), " ").trim()

    /** Author fields on Commons range from a name to a paragraph of notes; keep just the name. */
    private fun cleanArtist(raw: String): String {
        var a = raw.substringBefore("\n").trim()
        if (a.startsWith("NOTE", ignoreCase = true) || a.startsWith("This ", ignoreCase = true)) return ""
        a = a.substringBefore(" (").substringBefore(", ").trim()
        return if (a.length > 40) a.take(39).trimEnd() + "…" else a
    }

    private fun firstSentence(text: String, max: Int): String {
        val cut = Regex("(?<=[.!?])\\s").find(text)?.range?.first ?: text.length
        val s = text.substring(0, cut).trim()
        return if (s.length <= max) s else s.take(max - 1).trimEnd() + "…"
    }

    // ---- Unsplash -------------------------------------------------------------------------

    /** Random landscape photos; empty (and logged) when the key is missing or rejected. */
    suspend fun unsplash(key: String, count: Int): List<Photo> = withContext(Dispatchers.IO) {
        if (key.isBlank()) return@withContext emptyList()
        val headers = mapOf("Authorization" to "Client-ID $key", "Accept-Version" to "v1")
        runCatching {
            val r = Net.request(
                "GET",
                "$UNSPLASH_API/photos/random?count=${count.coerceIn(1, 30)}&orientation=landscape" +
                    "&query=${enc("nature landscape")}&content_filter=high",
                headers,
            )
            if (r.code !in 200..299) throw IllegalStateException("Unsplash HTTP ${r.code}: ${r.body.take(200)}")
            val arr = JSONArray(r.body)
            (0 until arr.length()).mapNotNull { i ->
                val p = arr.getJSONObject(i)
                val raw = p.optJSONObject("urls")?.optString("raw") ?: return@mapNotNull null
                val sep = if (raw.contains('?')) "&" else "?"
                val url = "$raw${sep}w=1920&h=1080&fit=crop&crop=entropy&q=80&fm=jpg"
                val user = p.optJSONObject("user")
                val name = user?.optString("name").orEmpty().ifBlank { "Unknown" }
                val title = p.optString("description").ifBlank { p.optString("alt_description") }
                    .replaceFirstChar { it.uppercase() }
                Photo(
                    url = url,
                    title = firstSentence(title, 120),
                    place = p.optJSONObject("location")?.optString("name").orEmpty(),
                    credit = "Photo by $name on Unsplash",
                    downloadPing = p.optJSONObject("links")?.optString("download_location")?.ifBlank { null },
                    pingHeaders = headers,
                )
            }
        }.onFailure { Log.w(TAG, "Unsplash fetch failed", it) }.getOrDefault(emptyList())
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
