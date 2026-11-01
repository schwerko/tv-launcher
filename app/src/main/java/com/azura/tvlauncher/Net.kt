package com.azura.tvlauncher

import java.net.HttpURLConnection
import java.net.URL

object Net {
    /** Identifies the app to the services it calls (Wikimedia asks for a descriptive agent). */
    const val USER_AGENT =
        "TVLauncher/${BuildConfig.VERSION_NAME} (Android TV launcher; https://play.google.com/store/apps/details?id=com.azura.tvlauncher)"

    data class Response(val code: Int, val body: String)

    fun get(rawUrl: String, headers: Map<String, String> = emptyMap()): String {
        val r = request("GET", rawUrl, headers)
        if (r.code !in 200..299) throw IllegalStateException("HTTP ${r.code} for $rawUrl")
        return r.body
    }

    fun request(
        method: String,
        rawUrl: String,
        headers: Map<String, String> = emptyMap(),
        jsonBody: String? = null,
        readTimeoutMs: Int = 20_000,
    ): Response {
        val url = URL(rawUrl.replaceFirst(Regex("^webcal://", RegexOption.IGNORE_CASE), "https://"))
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 10_000
        conn.readTimeout = readTimeoutMs
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept", "application/json, text/calendar, */*")
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        if (jsonBody != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(jsonBody.toByteArray()) }
        } else if (method == "POST") {
            conn.doOutput = true
            conn.setRequestProperty("Content-Length", "0")
            conn.outputStream.use { }
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return Response(code, body)
        } finally {
            conn.disconnect()
        }
    }
}
