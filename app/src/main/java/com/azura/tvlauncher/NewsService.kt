package com.azura.tvlauncher

import android.content.Context
import android.text.Html
import android.util.Log
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.StringReader
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * News for a TV: headlines from RSS/Atom feeds the user chose, each of which opens a YouTube
 * search for that story (nobody reads articles on a TV). A YouTube channel link or id works as a
 * source too: YouTube publishes an Atom feed per channel, whose entries are videos shown with a
 * thumbnail and played directly. That endpoint answers intermittently, so the last good result
 * of every feed is kept on disk and reused when a fetch fails; a channel with nothing cached
 * gets one card that opens the channel itself. Each feed is fetched directly from its host;
 * nothing is sent beyond the request itself.
 */
object NewsService {

    data class Item(
        val title: String,
        val link: String,
        val summary: String,
        val source: String,
        /** Epoch millis, or 0 when the feed gave no usable date. */
        val published: Long,
        /** YouTube video id when the item is a video, else blank. */
        val videoId: String = "",
        /** True for the stand-in card shown when a channel's feed is unavailable. */
        val channelOnly: Boolean = false,
    ) {
        val thumbnailUrl: String get() = if (videoId.isBlank()) "" else "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"
    }

    /** Well-known sources offered in Settings so nothing has to be typed on a remote. */
    data class Preset(val label: String, val url: String)

    val PRESETS = listOf(
        Preset("BBC News", "https://feeds.bbci.co.uk/news/world/rss.xml"),
        Preset("NPR", "https://feeds.npr.org/1001/rss.xml"),
        Preset("The Guardian", "https://www.theguardian.com/world/rss"),
        Preset("The New York Times", "https://rss.nytimes.com/services/xml/rss/nyt/HomePage.xml"),
        Preset("Al Jazeera", "https://www.aljazeera.com/xml/rss/all.xml"),
        Preset("ESPN", "https://www.espn.com/espn/rss/news"),
        Preset("Ars Technica", "https://feeds.arstechnica.com/arstechnica/index"),
    )

    private const val TAG = "News"
    private const val PER_FEED = 12
    private val CHANNEL_ID = Regex("(UC[A-Za-z0-9_-]{22})")

    fun feedUrl(channelId: String) = "https://www.youtube.com/feeds/videos.xml?channel_id=$channelId"

    /** The YouTube channel id in a feed URL, channel URL or bare id; null for other feeds. */
    fun channelIdOf(text: String): String? = CHANNEL_ID.find(text)?.groupValues?.get(1)

    /** Turns whatever the user typed (channel ids, channel URLs, feed URLs) into feed URLs. */
    fun normalise(typed: String): List<String> =
        typed.split(Regex("[\\s,;]+")).filter { it.isNotBlank() }.map { t ->
            val id = channelIdOf(t)
            if (id != null) feedUrl(id) else t
        }

    /** Newest first across all feeds; null when every feed failed and nothing is cached. */
    suspend fun fetch(context: Context, urls: List<String>, max: Int): List<Item>? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "news").apply { mkdirs() }
        val results = urls.map { url ->
            val cache = File(dir, sha1(url) + ".json")
            val fresh = runCatching { parse(Net.get(url)).take(PER_FEED) }
                .onFailure { Log.w(TAG, "feed failed: $url: ${it.message}") }.getOrNull()
            val items = when {
                fresh != null && fresh.isNotEmpty() -> fresh.also { writeCache(cache, it) }
                else -> readCache(cache)
            }
            val label = PRESETS.firstOrNull { it.url == url }?.label
            when {
                items.isNotEmpty() -> items.map { if (label != null) it.copy(source = label) else it }
                else -> channelIdOf(url)?.let { id ->
                    // Feed unavailable and nothing cached: one card that opens the channel itself.
                    listOf(Item(label ?: "YouTube channel", "https://www.youtube.com/channel/$id/videos", "", label ?: "", 0, "", true))
                } ?: emptyList()
            }
        }
        val all = results.flatten()
        if (all.isEmpty()) return@withContext null
        // Dated items newest first; undated ones keep their feed order after them.
        all.withIndex().sortedWith(compareByDescending<IndexedValue<Item>> { it.value.published }.thenBy { it.index })
            .map { it.value }.distinctBy { it.title }.take(max)
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun readCache(file: File): List<Item> = runCatching {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Item(o.getString("title"), o.optString("link"), o.optString("summary"), o.optString("source"),
                o.optLong("published"), o.optString("videoId"))
        }
    }.getOrDefault(emptyList())

    private fun writeCache(file: File, items: List<Item>) = runCatching {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().put("title", it.title).put("link", it.link).put("summary", it.summary)
                .put("source", it.source).put("published", it.published).put("videoId", it.videoId))
        }
        file.writeText(arr.toString())
    }.onFailure { Log.w(TAG, "cache write failed", it) }

    fun parse(xml: String): List<Item> {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(StringReader(xml.trimStart('\uFEFF')))
        }
        val items = ArrayList<Item>()
        var feedTitle = ""
        var inEntry = false
        var title = ""; var link = ""; var summary = ""; var date = ""; var content = ""; var videoId = ""
        var depthOfEntry = 0
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name.substringAfter(':')
                    when {
                        !inEntry && (name == "item" || name == "entry") -> {
                            inEntry = true; depthOfEntry = parser.depth
                            title = ""; link = ""; summary = ""; date = ""; content = ""; videoId = ""
                        }
                        !inEntry && name == "title" && feedTitle.isBlank() && parser.depth <= 3 -> feedTitle = text(parser)
                        inEntry && parser.depth == depthOfEntry + 1 -> when (name) {
                            "title" -> title = text(parser)
                            "link" -> {
                                val href = parser.getAttributeValue(null, "href")
                                val rel = parser.getAttributeValue(null, "rel")
                                if (href != null) { if (rel == null || rel == "alternate") link = href.trim() }
                                else link = text(parser)
                            }
                            "description", "summary" -> summary = text(parser)
                            "encoded", "content" -> content = text(parser)
                            "pubDate", "published", "updated", "date" -> if (date.isBlank()) date = text(parser)
                            "videoId" -> videoId = text(parser)
                        }
                        // YouTube puts the description one level down, in <media:group>.
                        inEntry && parser.depth == depthOfEntry + 2 && name == "description" && summary.isBlank() ->
                            summary = text(parser)
                    }
                }
                XmlPullParser.END_TAG -> {
                    val name = parser.name.substringAfter(':')
                    if (inEntry && (name == "item" || name == "entry") && parser.depth == depthOfEntry) {
                        inEntry = false
                        val t = plain(title)
                        if (t.isNotBlank()) {
                            items.add(Item(t, link, plain(summary.ifBlank { content }).take(1200), plain(feedTitle), parseDate(date), videoId))
                        }
                    }
                }
            }
            event = parser.next()
        }
        return items
    }

    /** The text content of the current element, with any nested markup flattened. */
    private fun text(parser: XmlPullParser): String {
        val depth = parser.depth
        val sb = StringBuilder()
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (event == XmlPullParser.TEXT || event == XmlPullParser.CDSECT) sb.append(parser.text)
            else if (event == XmlPullParser.END_DOCUMENT) break
            event = parser.next()
        }
        return sb.toString().trim()
    }

    @Suppress("DEPRECATION")
    private fun plain(s: String): String {
        if (s.isBlank()) return ""
        val once = Html.fromHtml(s, Html.FROM_HTML_MODE_COMPACT).toString()
        // Feeds often double-escape their HTML; a second pass clears what the first revealed.
        val twice = if (once.contains('<') || once.contains("&")) Html.fromHtml(once, Html.FROM_HTML_MODE_COMPACT).toString() else once
        return twice.replace(' ', ' ').replace(Regex("\\s+"), " ").trim()
    }

    private fun parseDate(s: String): Long {
        val d = s.trim()
        if (d.isBlank()) return 0
        runCatching { return ZonedDateTime.parse(d, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
        runCatching { return OffsetDateTime.parse(d).toInstant().toEpochMilli() }
        runCatching { return ZonedDateTime.parse(d).toInstant().toEpochMilli() }
        // RFC 1123 with a single-digit day or a zone name such as "GMT"/"EST".
        runCatching {
            return ZonedDateTime.parse(d, DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss z", java.util.Locale.ENGLISH))
                .toInstant().toEpochMilli()
        }
        return 0
    }
}
