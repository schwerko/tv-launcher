package com.azura.tvlauncher

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Google Gemini (AI Studio free tier) over plain HTTP. No web grounding: the free quota does
 * not include Google Search, so answers come from the model's own knowledge.
 */
class GeminiClient(private val apiKey: String, private val locationHint: String) : Assistant {

    private val contents = JSONArray()

    override val name: String get() = "Gemini"
    override val hasLiveWeb: Boolean get() = false

    override suspend fun ask(question: String): Assistant.Answer = withContext(Dispatchers.IO) {
        val mark = contents.length()
        contents.put(turn("user", question))
        val response = try {
            post(JSONObject()
                .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt()))))
                .put("contents", contents)
                .put("generationConfig", JSONObject()
                    .put("maxOutputTokens", 600)
                    .put("thinkingConfig", JSONObject().put("thinkingBudget", 0))))
        } catch (e: Exception) {
            while (contents.length() > mark) contents.remove(contents.length() - 1)
            throw e
        }
        val candidate = response.optJSONArray("candidates")?.optJSONObject(0)
        val raw = candidate?.optJSONObject("content")?.optJSONArray("parts")?.let { parts ->
            (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }
        }?.trim().orEmpty()
        val blocked = candidate?.optString("finishReason") == "SAFETY" ||
            response.optJSONObject("promptFeedback")?.has("blockReason") == true
        contents.put(turn("model", raw))
        val titles = TITLES_LINE.find(raw)?.groupValues?.get(1)
            ?.split('|')?.map { it.trim() }?.filter { it.isNotBlank() }?.take(4) ?: emptyList()
        Assistant.Answer(TITLES_LINE.replace(raw, "").trim(), titles, blocked)
    }

    /** Without search this is a best-effort recall; callers should verify or fall back. */
    override suspend fun resolveTitleUrl(service: String, urlForm: String, hostPattern: Regex, title: String): String? =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text",
                    "Reply with ONLY the URL of the title's page on the named streaming service, for the United States, " +
                        "in the form $urlForm. If you are not certain of the exact page, reply NONE."))))
                .put("contents", JSONArray().put(turn("user", "$service: $title")))
                .put("generationConfig", JSONObject().put("maxOutputTokens", 100).put("thinkingConfig", JSONObject().put("thinkingBudget", 0)))
            val text = runCatching { post(body) }.getOrNull()
                ?.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                ?.let { parts -> (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") } }
                .orEmpty()
            Log.d(TAG, "resolve $service/$title -> ${text.take(200)}")
            hostPattern.find(text)?.value?.trimEnd('.', ',', ')')
        }

    override fun reset() {
        while (contents.length() > 0) contents.remove(contents.length() - 1)
    }

    private fun turn(role: String, text: String) =
        JSONObject().put("role", role).put("parts", JSONArray().put(JSONObject().put("text", text)))

    /** Tries the preferred model first; on a timeout or server error, the lighter fallback. */
    private fun post(body: JSONObject): JSONObject {
        var last: Exception? = null
        for ((i, model) in MODELS.withIndex()) {
            try {
                return postTo(model, body, if (i == 0) PRIMARY_TIMEOUT_MS else FALLBACK_TIMEOUT_MS)
            } catch (e: Assistant.AuthException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "$model failed: ${e.message}")
                last = e
            }
        }
        throw last ?: IllegalStateException("Gemini unavailable")
    }

    private fun postTo(model: String, body: JSONObject, timeoutMs: Int): JSONObject {
        val r = Net.request(
            "POST", "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent",
            mapOf("x-goog-api-key" to apiKey),
            body.toString(),
            readTimeoutMs = timeoutMs,
        )
        if (r.code == 400 || r.code == 401 || r.code == 403) {
            val msg = runCatching { JSONObject(r.body).getJSONObject("error").getString("message") }.getOrNull() ?: ""
            if (r.code != 400 || msg.contains("API key", ignoreCase = true)) throw Assistant.AuthException(msg.ifBlank { "API key rejected" })
        }
        if (r.code !in 200..299) {
            Log.w(TAG, "$model HTTP ${r.code}: ${r.body.take(300)}")
            val msg = runCatching { JSONObject(r.body).getJSONObject("error").getString("message") }.getOrNull()
            throw IllegalStateException(if (r.code == 429) "free quota used up for now; try again in a minute" else msg ?: "HTTP ${r.code}")
        }
        return JSONObject(r.body)
    }

    private fun systemPrompt(): String = """
        You are a helpful assistant on a living-room TV, answering through a launcher app with a remote control.
        The viewer is in $locationHint. Answers are read from a couch, so keep them short: usually under 120 words,
        plain sentences, no markdown, no headings, no bullet symbols. You have no live web access, so for anything
        time-sensitive (what is streaming where, schedules, news, prices) say what you know and note it may have changed.
        When you recommend or mention specific films, series, videos or channels the viewer could watch, finish
        with one extra line exactly in this form, listing up to four titles:
        TITLES: Title One | Title Two
        Leave that line out when nothing watchable was mentioned.
    """.trimIndent()

    private companion object {
        const val TAG = "Gemini"
        val MODELS = listOf("gemini-3.5-flash", "gemini-3.8-flash")
        const val PRIMARY_TIMEOUT_MS = 45_000
        const val FALLBACK_TIMEOUT_MS = 60_000
        val TITLES_LINE = Regex("""(?m)^\s*TITLES:\s*(.+?)\s*$""")
    }
}
