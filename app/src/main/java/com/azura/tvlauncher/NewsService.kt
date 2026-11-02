package com.azura.tvlauncher

import android.text.Html
import android.util.Log
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Headlines from RSS 2.0 and Atom feeds the user chose. Each feed is fetched directly from its
 * publisher; nothing is sent beyond the request itself.
 */
object NewsService {

    data class Item(
        val title: String,
        val link: String,
        val summary: String,
        val source: String,
        /** Epoch millis, or 0 when the feed gave no usable date. */
        val published: Long,
    )

    /** A few well-known feeds offered in Settings so nothing has to be typed on a remote. */
    data class Preset(val label: String, val url: String)

    val PRESETS = listOf(
        Preset("BBC News · World", "https://feeds.bbci.co.uk/news/world/rss.xml"),
        Preset("NPR News", "https://feeds.npr.org/1001/rss.xml"),
        Preset("The Guardian · World", "https://www.theguardian.com/world/rss"),
        Preset("The New York Times", "https://rss.nytimes.com/services/xml/rss/nyt/HomePage.xml"),
        Preset("Al Jazeera", "https://www.aljazeera.com/xml/rss/all.xml"),
        Preset("ESPN", "https://www.espn.com/espn/rss/news"),
        Preset("Ars Technica", "https://feeds.arstechnica.com/arstechnica/index"),
    )

    private const val TAG = "News"
    private const val PER_FEED = 15

    /** Newest first across all feeds; null when every feed failed. */
    suspend fun fetch(urls: List<String>, max: Int): List<Item>? = withContext(Dispatchers.IO) {
        val results = urls.map { url ->
            runCatching {
                val items = parse(Net.get(url)).take(PER_FEED)
                // Feeds' own channel titles can be clumsy ("www.espn.com - TOP"); prefer the preset's name.
                val preset = PRESETS.firstOrNull { it.url == url }?.label
                if (preset == null) items else items.map { it.copy(source = preset) }
            }.onFailure { Log.w(TAG, "feed failed: $url", it) }
        }
        if (results.none { it.isSuccess }) return@withContext null
        val all = results.mapNotNull { it.getOrNull() }.flatten()
        // Dated items newest first; undated ones keep their feed order after them.
        all.withIndex().sortedWith(compareByDescending<IndexedValue<Item>> { it.value.published }.thenBy { it.index })
            .map { it.value }.distinctBy { it.title }.take(max)
    }

    fun parse(xml: String): List<Item> {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(StringReader(xml.trimStart('\uFEFF')))
        }
        val items = ArrayList<Item>()
        var feedTitle = ""
        var inEntry = false
        var title = ""; var link = ""; var summary = ""; var date = ""; var content = ""
        var depthOfEntry = 0
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name.substringAfter(':')
                    when {
                        !inEntry && (name == "item" || name == "entry") -> {
                            inEntry = true; depthOfEntry = parser.depth
                            title = ""; link = ""; summary = ""; date = ""; content = ""
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
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val name = parser.name.substringAfter(':')
                    if (inEntry && (name == "item" || name == "entry") && parser.depth == depthOfEntry) {
                        inEntry = false
                        val t = plain(title)
                        if (t.isNotBlank()) {
                            items.add(Item(t, link, plain(summary.ifBlank { content }).take(1200), plain(feedTitle), parseDate(date)))
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
