package com.azura.tvlauncher

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Claude Messages API over plain HTTP (the official Java SDK isn't built for Android).
 * Keeps a running conversation so follow-up questions have context, and lets Claude use
 * web search so answers about what's on right now are current.
 */
class ClaudeClient(private val apiKey: String, private val locationHint: String) : Assistant {

    override val name: String get() = "Claude"
    override val hasLiveWeb: Boolean get() = true

    private val history = JSONArray()

    override suspend fun ask(question: String): Assistant.Answer = withContext(Dispatchers.IO) {
        val mark = history.length()
        history.put(JSONObject().put("role", "user").put("content", question))
        var content = JSONArray()
        var stopReason = ""
        try {
            for (attempt in 0 until MAX_CONTINUATIONS) {
                val response = request()
                stopReason = response.optString("stop_reason")
                content = response.getJSONArray("content")
                history.put(JSONObject().put("role", "assistant").put("content", content))
                // Server tools can pause the turn; resending the same history lets the server resume.
                if (stopReason != "pause_turn") break
            }
        } catch (e: Exception) {
            while (history.length() > mark) history.remove(history.length() - 1)   // keep history valid
            throw e
        }
        val text = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.getJSONObject(i)
            if (block.optString("type") == "text") text.append(block.optString("text"))
        }
        val raw = text.toString().trim()
        val titles = TITLES_LINE.find(raw)?.groupValues?.get(1)
            ?.split('|')?.map { it.trim() }?.filter { it.isNotBlank() }?.take(4) ?: emptyList()
        val visible = TITLES_LINE.replace(raw, "").trim()
        Assistant.Answer(visible, titles, stopReason == "refusal")
    }

    /**
     * One-off lookup (outside the conversation) of a title's page on a streaming service.
     * Returns the URL without scheme, or null when the title isn't there / can't be found.
     */
    override suspend fun resolveTitleUrl(service: String, urlForm: String, hostPattern: Regex, title: String): String? =
        withContext(Dispatchers.IO) {
            val messages = JSONArray().put(JSONObject().put("role", "user").put("content", "$service: $title"))
            val body = JSONObject()
                .put("model", MODEL)
                .put("max_tokens", 300)
                .put("system", "You find the official page URL of a film or series on a streaming service for a " +
                    "viewer in the United States. Reply with ONLY the URL on one line, in the form $urlForm. " +
                    "If the title is not available on that service or you cannot find its page, reply NONE.")
                .put("output_config", JSONObject().put("effort", "low"))
                .put("fallbacks", "default")
                .put("tools", JSONArray().put(
                    JSONObject().put("type", "web_search_20250305").put("name", "web_search").put("max_uses", 2)
                ))
                .put("messages", messages)
            var text = ""
            for (attempt in 0 until MAX_CONTINUATIONS) {
                val response = post(body)
                val content = response.getJSONArray("content")
                messages.put(JSONObject().put("role", "assistant").put("content", content))
                text = (0 until content.length()).map { content.getJSONObject(it) }
                    .filter { it.optString("type") == "text" }.joinToString("") { it.optString("text") }
                if (response.optString("stop_reason") != "pause_turn") break
            }
            Log.d(TAG, "resolve $service/$title -> ${text.take(200)}")
            hostPattern.find(text)?.value?.trimEnd('.', ',', ')')
        }

    override fun reset() {
        while (history.length() > 0) history.remove(history.length() - 1)
    }

    private fun request(): JSONObject = post(
        JSONObject()
            .put("model", MODEL)
            .put("max_tokens", MAX_TOKENS)
            .put("system", systemPrompt())
            .put("output_config", JSONObject().put("effort", "low"))
            .put("fallbacks", "default")
            .put("tools", JSONArray().put(
                JSONObject().put("type", "web_search_20250305").put("name", "web_search").put("max_uses", 2)
            ))
            .put("messages", history)
    )

    private fun post(body: JSONObject): JSONObject {
        val r = Net.request(
            "POST", "https://api.anthropic.com/v1/messages",
            mapOf(
                "x-api-key" to apiKey,
                "anthropic-version" to "2026-10-21",
                "anthropic-beta" to "server-side-fallback-2026-07-01",
            ),
            body.toString(),
            readTimeoutMs = 120_000,
        )
        if (r.code == 401 || r.code == 403) throw Assistant.AuthException("API key rejected")
        if (r.code !in 200..299) {
            Log.w(TAG, "HTTP ${r.code}: ${r.body.take(400)}")
            val msg = runCatching { JSONObject(r.body).getJSONObject("error").getString("message") }.getOrNull()
            throw IllegalStateException(msg ?: "Claude HTTP ${r.code}")
        }
        return JSONObject(r.body)
    }

    private fun systemPrompt(): String = """
        You are a helpful assistant on a living-room TV, answering through a launcher app with a remote control.
        The viewer is in $locationHint. Answers are read from a couch, so keep them short: usually under 120 words,
        plain sentences, no markdown, no headings, no bullet symbols. Use web search when the question is about
        anything current (what is streaming where, sports schedules, news, weather, prices, opening hours).
        When you recommend or mention specific films, series, videos or channels the viewer could watch, finish
        with one extra line exactly in this form, listing up to four titles:
        TITLES: Title One | Title Two
        Leave that line out when nothing watchable was mentioned.
    """.trimIndent()

    private companion object {
        const val TAG = "Claude"
        const val MODEL = "claude-haiku-5-5"
        const val MAX_TOKENS = 1500
        const val MAX_CONTINUATIONS = 3
        val TITLES_LINE = Regex("""(?m)^\s*TITLES:\s*(.+?)\s*$""")
    }
}
