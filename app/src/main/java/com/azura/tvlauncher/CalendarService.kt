package com.azura.tvlauncher

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Minimal iCalendar reader: enough to show "what's next" from a Google Calendar or Outlook
 * private ICS feed. Supports DTSTART (date or date-time, with TZID or UTC), SUMMARY, EXDATE and
 * RRULE with FREQ=DAILY/WEEKLY/MONTHLY/YEARLY, INTERVAL, COUNT, UNTIL and BYDAY.
 */
object CalendarService {

    data class Event(val start: ZonedDateTime, val allDay: Boolean, val title: String)

    /** Merges every feed; returns null only if none of them could be read. */
    suspend fun upcoming(urls: List<String>, days: Long = 7, max: Int = 4): List<Event>? =
        withContext(Dispatchers.IO) {
            val now = ZonedDateTime.now()
            val results = urls.map { url -> runCatching { parse(Net.get(url), now, days) } }
            if (results.none { it.isSuccess }) return@withContext null
            results.mapNotNull { it.getOrNull() }.flatten().sortedBy { it.start }.take(max)
        }

    fun parse(text: String, now: ZonedDateTime, days: Long): List<Event> {
        val out = ArrayList<Event>()
        val windowEnd = now.plusDays(days)
        var inEvent = false
        var props = HashMap<String, Pair<Map<String, String>, String>>()
        var exdates = ArrayList<Pair<Map<String, String>, String>>()

        for (line in unfold(text)) {
            when {
                line == "BEGIN:VEVENT" -> {
                    inEvent = true
                    props = HashMap()
                    exdates = ArrayList()
                }
                line == "END:VEVENT" -> {
                    inEvent = false
                    expand(props, exdates, now, windowEnd, out)
                }
                inEvent -> {
                    val idx = line.indexOf(':')
                    if (idx < 0) continue
                    val head = line.substring(0, idx)
                    val value = line.substring(idx + 1)
                    val parts = head.split(';')
                    val name = parts[0].uppercase()
                    val params = parts.drop(1).mapNotNull { p ->
                        val e = p.indexOf('=')
                        if (e < 0) null else p.substring(0, e).uppercase() to p.substring(e + 1).trim('"')
                    }.toMap()
                    if (name == "EXDATE") {
                        value.split(',').forEach { exdates.add(params to it.trim()) }
                    } else {
                        props[name] = params to value
                    }
                }
            }
        }
        return out
    }

    /** RFC 5545 line unfolding: a line starting with a space or tab continues the previous one. */
    private fun unfold(text: String): List<String> {
        val out = ArrayList<String>()
        for (raw in text.split("\r\n", "\n")) {
            if ((raw.startsWith(" ") || raw.startsWith("\t")) && out.isNotEmpty()) {
                out[out.size - 1] = out[out.size - 1] + raw.substring(1)
            } else {
                out.add(raw)
            }
        }
        return out
    }

    private fun expand(
        props: Map<String, Pair<Map<String, String>, String>>,
        exdates: List<Pair<Map<String, String>, String>>,
        now: ZonedDateTime,
        windowEnd: ZonedDateTime,
        out: MutableList<Event>,
    ) {
        val (startParams, startValue) = props["DTSTART"] ?: return
        val allDay = startParams["VALUE"] == "DATE" || startValue.length == 8
        val start = parseDate(startValue, startParams["TZID"]) ?: return
        val title = unescape(props["SUMMARY"]?.second ?: "(no title)")
        val excluded = exdates.mapNotNull { (p, v) -> parseDate(v, p["TZID"])?.toInstant() }.toSet()
        val system = ZoneId.systemDefault()

        // Anything that started up to an hour ago still counts as "now".
        val windowStart = if (allDay) now.toLocalDate().atStartOfDay(now.zone) else now.minusHours(1)

        // Candidates stay in the event's own zone while the series is expanded (so a weekly
        // 10:30 stays 10:30 across daylight-saving changes) and are converted only for display.
        fun offer(candidate: ZonedDateTime) {
            if (candidate < windowStart || candidate > windowEnd) return
            if (candidate.toInstant() in excluded) return
            out.add(Event(candidate.withZoneSameInstant(system), allDay, title))
        }

        val rrule = props["RRULE"]?.second
        if (rrule == null) {
            offer(start)
            return
        }

        val rule = rrule.split(';').mapNotNull { kv ->
            val e = kv.indexOf('=')
            if (e < 0) null else kv.substring(0, e).uppercase() to kv.substring(e + 1)
        }.toMap()
        val freq = rule["FREQ"]?.uppercase() ?: return
        val interval = rule["INTERVAL"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val count = rule["COUNT"]?.toIntOrNull()
        val until = rule["UNTIL"]?.let { parseDate(it, startParams["TZID"]) }
        val byDay = rule["BYDAY"]?.split(',')?.mapNotNull { token ->
            when (token.trim().takeLast(2).uppercase()) {
                "MO" -> DayOfWeek.MONDAY; "TU" -> DayOfWeek.TUESDAY; "WE" -> DayOfWeek.WEDNESDAY
                "TH" -> DayOfWeek.THURSDAY; "FR" -> DayOfWeek.FRIDAY; "SA" -> DayOfWeek.SATURDAY
                "SU" -> DayOfWeek.SUNDAY; else -> null
            }
        }?.toSet() ?: emptySet()

        // Walk day by day. When COUNT is set we must start at DTSTART to count correctly;
        // otherwise skip straight to the window.
        var cursor = if (count != null || start > windowStart) start else {
            windowStart.toLocalDate().minusDays(1).atTime(start.toLocalTime()).atZone(start.zone)
        }
        var matched = 0
        var guard = 0
        val startDate = start.toLocalDate()
        while (cursor <= windowEnd && guard++ < 5000) {
            if (until != null && cursor > until) break
            if (count != null && matched >= count) break
            val d = cursor.toLocalDate()
            val matches = when (freq) {
                "DAILY" -> ChronoUnit.DAYS.between(startDate, d) % interval == 0L
                "WEEKLY" -> {
                    val weeks = ChronoUnit.DAYS.between(startDate.with(DayOfWeek.MONDAY), d.with(DayOfWeek.MONDAY)) / 7
                    weeks % interval == 0L && (if (byDay.isEmpty()) d.dayOfWeek == start.dayOfWeek else d.dayOfWeek in byDay)
                }
                "MONTHLY" -> d.dayOfMonth == startDate.dayOfMonth &&
                    ChronoUnit.MONTHS.between(startDate.withDayOfMonth(1), d.withDayOfMonth(1)) % interval == 0L
                "YEARLY" -> d.dayOfMonth == startDate.dayOfMonth && d.month == startDate.month &&
                    (d.year - startDate.year) % interval == 0
                else -> false
            }
            if (matches && cursor >= start) {
                matched++
                offer(cursor)
            }
            cursor = cursor.plusDays(1)
        }
    }

    private val basicDateTime: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    /** Parses an iCalendar date or date-time, keeping it in the zone the feed declared. */
    private fun parseDate(value: String, tzid: String?): ZonedDateTime? = runCatching {
        val system = ZoneId.systemDefault()
        when {
            value.length == 8 -> LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(system)
            value.endsWith("Z") -> LocalDateTime.parse(value.dropLast(1), basicDateTime).atZone(ZoneOffset.UTC)
            else -> LocalDateTime.parse(value, basicDateTime).atZone(tzid?.let { resolveZone(it) } ?: system)
        }
    }.getOrNull()

    /** Outlook feeds use Windows zone names; map the common ones onto IANA ids. */
    private fun resolveZone(tzid: String): ZoneId? {
        runCatching { ZoneId.of(tzid) }.getOrNull()?.let { return it }
        val iana = when (tzid) {
            "Mountain Standard Time" -> "America/Denver"
            "US Mountain Standard Time" -> "America/Phoenix"
            "Pacific Standard Time" -> "America/Los_Angeles"
            "Central Standard Time" -> "America/Chicago"
            "Eastern Standard Time" -> "America/New_York"
            "Alaskan Standard Time" -> "America/Anchorage"
            "Hawaiian Standard Time" -> "Pacific/Honolulu"
            "Atlantic Standard Time" -> "America/Halifax"
            "UTC", "Coordinated Universal Time", "GMT" -> "UTC"
            "GMT Standard Time" -> "Europe/London"
            "W. Europe Standard Time" -> "Europe/Berlin"
            "Central Europe Standard Time", "Central European Standard Time" -> "Europe/Belgrade"
            "Romance Standard Time" -> "Europe/Paris"
            "E. Europe Standard Time", "GTB Standard Time" -> "Europe/Athens"
            "FLE Standard Time" -> "Europe/Helsinki"
            "Turkey Standard Time" -> "Europe/Istanbul"
            "Russian Standard Time" -> "Europe/Moscow"
            "India Standard Time" -> "Asia/Kolkata"
            "China Standard Time" -> "Asia/Shanghai"
            "Tokyo Standard Time" -> "Asia/Tokyo"
            "Singapore Standard Time" -> "Asia/Singapore"
            "AUS Eastern Standard Time" -> "Australia/Sydney"
            "New Zealand Standard Time" -> "Pacific/Auckland"
            "Arabian Standard Time" -> "Asia/Dubai"
            "Israel Standard Time" -> "Asia/Jerusalem"
            "South Africa Standard Time" -> "Africa/Johannesburg"
            "E. South America Standard Time" -> "America/Sao_Paulo"
            "SA Pacific Standard Time" -> "America/Bogota"
            else -> null
        } ?: return null
        return runCatching { ZoneId.of(iana) }.getOrNull()
    }

    private fun unescape(s: String): String =
        s.replace("\\n", " ").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")
}
