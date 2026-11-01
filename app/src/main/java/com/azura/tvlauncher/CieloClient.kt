package com.azura.tvlauncher

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cielo Connect cloud API (the official one used by Home Assistant). Authenticates with an API
 * key from the Cielo web portal, lists devices, and posts actions.
 */
class CieloClient(
    private val apiKey: String,
    savedToken: String = "",
    private val onToken: (String) -> Unit = {},
) {

    class AuthException(message: String) : Exception(message)

    data class Device(
        val mac: String,
        val name: String,
        val online: Boolean,
        val unit: String,              // "F" or "C"
        val roomTemp: Double?,
        val humidity: Double?,
        val state: JSONObject,         // current_state as returned by the API
        val supported: JSONObject?,    // supported_features, if present
    ) {
        val power: Boolean get() = state.optString("power", "off").equals("on", ignoreCase = true)
        val mode: String get() = state.optString("mode", "")
        val fan: String get() = state.optString("fan_speed", "")
        val setPoint: Double? get() = state.optString("set_point", "").toDoubleOrNull()

        /** Allowed set points for a mode in this device's unit, per the API; empty if unknown. */
        fun temperaturesFor(mode: String): List<Double> {
            val values = supported?.optJSONObject("modes")?.optJSONObject(mode.lowercase())
                ?.optJSONObject("temperatures")?.optJSONObject(unit)?.optJSONArray("values") ?: return emptyList()
            return (0 until values.length()).mapNotNull { values.optString(it).toDoubleOrNull() }.sorted()
        }

        fun fanLevelsFor(mode: String): List<String> {
            val a = supported?.optJSONObject("modes")?.optJSONObject(mode.lowercase())?.optJSONArray("fan_levels")
                ?: return emptyList()
            return (0 until a.length()).map { a.optString(it).lowercase() }
        }

        /** Modes this device supports, in display order, when the API tells us. */
        val modes: List<String>
            get() {
                val fromApi = supported?.optJSONObject("modes")?.keys()?.asSequence()?.toList()
                    ?: supported?.optJSONArray("modes")?.let { a -> (0 until a.length()).map { a.getString(it) } }
                val order = listOf("cool", "heat", "auto", "dry", "fan")
                val available = fromApi?.map { it.lowercase() }?.filter { it in order } ?: order
                return order.filter { it in available }
            }
    }

    @Volatile private var token: String? = savedToken.ifBlank { null }

    suspend fun devices(): List<Device> = withContext(Dispatchers.IO) {
        val body = authed("GET", "$BASE/devices/?cached_appliance_ids=")
        Log.d(TAG, "devices response: " + body.take(600))
        val data = JSONObject(body).opt("data")
        val items = ArrayList<JSONObject>()
        collectDevices(data, items)
        if (items.isNotEmpty()) Log.d(TAG, "device[0]: " + items[0].toString().take(3000))
        items.map { parse(it) }
    }

    /** Sends changed fields on top of the device's current state; returns the new state. */
    suspend fun send(device: Device, actionType: String, changes: Map<String, Any>): JSONObject? = withContext(Dispatchers.IO) {
        val actions = JSONObject(device.state.toString())
        changes.forEach { (k, v) -> actions.put(k, v) }
        val value = changes[actionType]
        val payload = JSONObject()
            .put("mac_address", device.mac)
            .put("action_type", actionType)
            .put("actions", actions)
        val body = authed("POST", "$BASE/action", payload.toString())
        Log.d(TAG, "action $actionType=$value -> " + body.take(2000))
        JSONObject(body).optJSONObject("data")
    }

    private fun collectDevices(node: Any?, out: MutableList<JSONObject>) {
        when (node) {
            is JSONArray -> for (i in 0 until node.length()) collectDevices(node.opt(i), out)
            is JSONObject -> if (node.has("mac_address")) out.add(node) else node.keys().forEach { collectDevices(node.opt(it), out) }
        }
    }

    private fun parse(d: JSONObject): Device {
        val readings = d.optJSONObject("sensor_readings")
        val status = d.optJSONObject("connection_status")
        return Device(
            mac = d.optString("mac_address"),
            name = d.optString("device_name").ifBlank { "AC" },
            online = status?.optBoolean("is_alive", true) ?: true,
            unit = d.optString("temperature_unit", "F").take(1).uppercase().ifBlank { "F" },
            roomTemp = readings?.optString("temperature", "")?.toDoubleOrNull(),
            humidity = readings?.optString("humidity", "")?.toDoubleOrNull(),
            state = d.optJSONObject("current_state") ?: JSONObject(),
            supported = d.optJSONObject("supported_features"),
        )
    }

    private fun authed(method: String, url: String, body: String? = null): String {
        var attempt = 0
        while (true) {
            val t = token ?: login()
            val r = Net.request(method, url, mapOf("x-api-key" to apiKey, "Authorization" to t), body)
            if ((r.code == 401 || r.code == 403) && attempt++ == 0) {
                token = null
                continue
            }
            if (r.code !in 200..299) {
                Log.w(TAG, "$method $url -> HTTP ${r.code}: ${r.body.take(300)}")
                throw IllegalStateException("Cielo HTTP ${r.code}")
            }
            return r.body
        }
    }

    private fun login(): String {
        val r = Net.request("POST", "$BASE/authenticate", mapOf("x-api-key" to apiKey))
        Log.d(TAG, "login HTTP ${r.code}: " + r.body.replace(Regex("\"access_token\"\\s*:\\s*\"[^\"]*\""), "\"access_token\":\"…\"").take(400))
        if (r.code == 401 || r.code == 403) {
            val msg = runCatching { JSONObject(r.body).getJSONObject("error").getString("message") }.getOrNull()
            throw AuthException(msg ?: "API key rejected")
        }
        if (r.code !in 200..299) throw IllegalStateException("Cielo login HTTP ${r.code}")
        val t = runCatching { JSONObject(r.body).getJSONObject("data").getString("access_token") }.getOrNull()
            ?: throw AuthException("No token in login response")
        token = t
        onToken(t)
        return t
    }

    companion object {
        private const val TAG = "Cielo"
        private const val BASE = "https://api.smartcielo.com/openapi/v1"
        val FAN_ORDER = listOf("auto", "low", "medium", "high")
    }
}
