package com.azura.tvlauncher

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal Home Assistant REST client: the entity list (`/api/states`) and service calls
 * (`/api/services/<domain>/<service>`), authenticated with a long-lived access token the user
 * creates in their own Home Assistant profile. Only the user's server is contacted.
 */
class HomeAssistant(baseUrl: String, private val token: String) {

    class AuthException : Exception("Home Assistant rejected the access token")

    data class Entity(val id: String, val name: String, val state: String, val unit: String) {
        val domain: String get() = id.substringBefore('.')

        /** Entities the card can show; the rest of a home (automations, zones, …) is left out. */
        val supported: Boolean get() = domain in SUPPORTED

        /** True when pressing the chip does something (toggle, run, activate). */
        val actionable: Boolean get() = domain in TOGGLE || domain in RUN

        fun stateLabel(): String = when {
            domain in RUN -> "Run"
            state == "on" -> "On"
            state == "off" -> "Off"
            state == "unavailable" -> "Unavailable"
            state == "unknown" -> "—"
            unit.isNotBlank() -> "$state $unit"
            else -> state.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
    }

    private val base = baseUrl.trim().trimEnd('/').let { if (it.contains("://")) it else "http://$it" }
    private val headers = mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json")

    suspend fun states(): List<Entity> = withContext(Dispatchers.IO) {
        val r = Net.request("GET", "$base/api/states", headers)
        if (r.code == 401 || r.code == 403) throw AuthException()
        if (r.code !in 200..299) throw IllegalStateException("HTTP ${r.code}")
        val arr = JSONArray(r.body)
        (0 until arr.length()).map { i -> entity(arr.getJSONObject(i)) }
    }

    /** Toggles, runs or activates [e] as its domain allows; no-op for read-only entities. */
    suspend fun act(e: Entity) = withContext(Dispatchers.IO) {
        val (domain, service) = when (e.domain) {
            in TOGGLE -> e.domain to "toggle"
            "scene" -> "scene" to "turn_on"
            "script" -> "script" to "turn_on"
            else -> return@withContext
        }
        val body = JSONObject().put("entity_id", e.id).toString()
        val r = Net.request("POST", "$base/api/services/$domain/$service", headers, body)
        if (r.code == 401 || r.code == 403) throw AuthException()
        if (r.code !in 200..299) throw IllegalStateException("HTTP ${r.code}: ${r.body.take(200)}")
    }

    private fun entity(o: JSONObject): Entity {
        val id = o.optString("entity_id")
        val attrs = o.optJSONObject("attributes") ?: JSONObject()
        return Entity(
            id = id,
            name = attrs.optString("friendly_name").ifBlank { id.substringAfter('.').replace('_', ' ') },
            state = o.optString("state"),
            unit = attrs.optString("unit_of_measurement"),
        )
    }

    companion object {
        val TOGGLE = setOf("light", "switch", "fan", "input_boolean", "cover", "media_player", "humidifier")
        val RUN = setOf("scene", "script")
        val SUPPORTED = TOGGLE + RUN + setOf("sensor", "binary_sensor", "climate", "weather", "person", "lock", "vacuum")
    }
}
