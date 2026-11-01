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
}
