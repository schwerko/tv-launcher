package com.azura.tvlauncher

/** What the Ask panel needs from a language-model provider. */
interface Assistant {
    class AuthException(message: String) : Exception(message)

    data class Answer(val text: String, val titles: List<String>, val refused: Boolean)

    val name: String
    /** True when answers can draw on a live web search. */
    val hasLiveWeb: Boolean

    suspend fun ask(question: String): Answer

    /** The title's page on a streaming service (no scheme), or null when unknown / not there. */
    suspend fun resolveTitleUrl(service: String, urlForm: String, hostPattern: Regex, title: String): String?

    fun reset()
}
