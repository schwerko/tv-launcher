package com.azura.tvlauncher

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    var hidden: Set<String>
        get() = sp.getStringSet("hidden", emptySet())?.toSet() ?: emptySet()
        set(value) = sp.edit().putStringSet("hidden", value.toSet()).apply()

    var pinned: Set<String>
        get() = sp.getStringSet("pinned", emptySet())?.toSet() ?: emptySet()
        set(value) = sp.edit().putStringSet("pinned", value.toSet()).apply()

    /** Empty means "use WeatherService.defaultCity()" (derived from the box's time zone). */
    var city: String
        get() = sp.getString("city", "") ?: ""
        set(value) = sp.edit().putString("city", value.trim()).apply()

    var fahrenheit: Boolean
        get() = sp.getBoolean("fahrenheit", false)
        set(value) = sp.edit().putBoolean("fahrenheit", value).apply()

    /** Background: WallpaperService.NONE / COMMONS / UNSPLASH, or image URLs. */
    var wallpaper: String
        get() = (sp.getString("wallpaper", "") ?: "").let {
            // 1.0 stored "bing"; those daily photos now come from Wikimedia Commons instead.
            if (it == WallpaperService.LEGACY_BING) WallpaperService.COMMONS else it
        }
        set(value) = sp.edit().putString("wallpaper", value.trim()).apply()

    /** Unsplash API access key (unsplash.com/developers), for the Unsplash background source. */
    var unsplashKey: String
        get() = sp.getString("unsplash_key", "") ?: ""
        set(value) = sp.edit().putString("unsplash_key", value.trim()).apply()

    /** True once the first-run hint pointing at Settings has been shown. */
    var hintShown: Boolean
        get() = sp.getBoolean("hint_shown", false)
        set(value) = sp.edit().putBoolean("hint_shown", value).apply()

    /** Cielo Connect API key from home.cielowigle.com (menu > Home Assistant). */
    var cieloApiKey: String
        get() = sp.getString("cielo_key", "") ?: ""
        set(value) = sp.edit().putString("cielo_key", value.trim()).apply()

    /** MAC of the Cielo device last shown on the card. */
    var cieloDevice: String
        get() = sp.getString("cielo_device", "") ?: ""
        set(value) = sp.edit().putString("cielo_device", value).apply()

    /** Access token Cielo issued for the key; a key only logs in once, so this must persist. */
    var cieloToken: String
        get() = sp.getString("cielo_token", "") ?: ""
        set(value) = sp.edit().putString("cielo_token", value).apply()

    /** Which model answers the Ask panel: "gemini" (free) or "claude". */
    var assistant: String
        get() = sp.getString("assistant", "gemini") ?: "gemini"
        set(value) = sp.edit().putString("assistant", value).apply()

    /** Google AI Studio key for Gemini. */
    var geminiApiKey: String
        get() = sp.getString("gemini_key", "") ?: ""
        set(value) = sp.edit().putString("gemini_key", value.trim()).apply()

    /** Anthropic API key for the Ask panel. */
    var claudeApiKey: String
        get() = sp.getString("claude_key", "") ?: ""
        set(value) = sp.edit().putString("claude_key", value.trim()).apply()

    /** Private iCal/ICS feed URL (Google Calendar "secret address", Outlook "published ICS"). */
    var icsUrl: String
        get() = sp.getString("ics", "") ?: ""
        set(value) = sp.edit().putString("ics", value.trim()).apply()

    /** RSS/Atom feed URLs for the headlines row, separated by whitespace. */
    var newsUrls: String
        get() = sp.getString("news", "") ?: ""
        set(value) = sp.edit().putString("news", value.trim()).apply()

    /** Home Assistant server, e.g. http://homeassistant.local:8123. */
    var haUrl: String
        get() = sp.getString("ha_url", "") ?: ""
        set(value) = sp.edit().putString("ha_url", value.trim()).apply()

    /** Long-lived access token from the user's Home Assistant profile page. */
    var haToken: String
        get() = sp.getString("ha_token", "") ?: ""
        set(value) = sp.edit().putString("ha_token", value.trim()).apply()

    /** Entity ids shown on the Home Assistant row, in display order, separated by spaces. */
    var haEntities: List<String>
        get() = (sp.getString("ha_entities", "") ?: "").split(' ').filter { it.isNotBlank() }
        set(value) = sp.edit().putString("ha_entities", value.joinToString(" ")).apply()

    /** Whether the "What's on tonight" card is wanted (it also needs an Ask key). */
    var tonightEnabled: Boolean
        get() = sp.getBoolean("tonight_enabled", true)
        set(value) = sp.edit().putBoolean("tonight_enabled", value).apply()

    /** Kinds of things the viewer likes, folded into the "What's on tonight" question. */
    var tonightInterests: List<String>
        get() = (sp.getString("tonight_interests", "") ?: "").split('|').map { it.trim() }.filter { it.isNotBlank() }
        set(value) = sp.edit().putString("tonight_interests", value.joinToString("|")).apply()

    /** The "What's on tonight" lines, cached for the day they were written. */
    var tonightDate: String
        get() = sp.getString("tonight_date", "") ?: ""
        set(value) = sp.edit().putString("tonight_date", value).apply()
    var tonightText: String
        get() = sp.getString("tonight_text", "") ?: ""
        set(value) = sp.edit().putString("tonight_text", value).apply()
    var tonightTitles: List<String>
        get() = (sp.getString("tonight_titles", "") ?: "").split('|').map { it.trim() }.filter { it.isNotBlank() }
        set(value) = sp.edit().putString("tonight_titles", value.joinToString("|")).apply()

    /** Home-screen theme: "photo" (background photos, blue accent) or "dark" (black, grey, white). */
    var theme: String
        get() = sp.getString("theme", "photo") ?: "photo"
        set(value) = sp.edit().putString("theme", value).apply()

    /** Mitsubishi Comfort (Kumo) indoor units with their local-control secrets, as a JSON array. */
    var kumoUnits: List<KumoClient.Unit>
        get() = runCatching {
            val a = org.json.JSONArray(sp.getString("kumo_units", "[]") ?: "[]")
            (0 until a.length()).map { KumoClient.Unit.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        set(value) = sp.edit().putString("kumo_units", org.json.JSONArray().apply { value.forEach { put(it.toJson()) } }.toString()).apply()

    /** Serial of the Mitsubishi unit last shown on the card. */
    var kumoSelected: String
        get() = sp.getString("kumo_selected", "") ?: ""
        set(value) = sp.edit().putString("kumo_selected", value).apply()

    /** Mitsubishi Comfort account email, remembered to pre-fill the next sign-in (the password is never stored). */
    var kumoEmail: String
        get() = sp.getString("kumo_email", "") ?: ""
        set(value) = sp.edit().putString("kumo_email", value.trim()).apply()

    /** Mitsubishi Comfort refresh token, for units controlled through the cloud (renewed on use). */
    var kumoRefresh: String
        get() = sp.getString("kumo_refresh", "") ?: ""
        set(value) = sp.edit().putString("kumo_refresh", value).apply()
}
