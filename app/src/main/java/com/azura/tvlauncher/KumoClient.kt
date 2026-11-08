package com.azura.tvlauncher

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.security.MessageDigest

/**
 * Mitsubishi Electric mini-splits with the Kumo Cloud Wi-Fi adapter (the "Mitsubishi Comfort" app).
 *
 * Control is local: every adapter answers HTTP PUTs on the home network at `/api?m=<token>`,
 * where the token is a SHA-256 over the request body, the unit's password and its crypto
 * serial. Those two per-unit secrets live only in Mitsubishi's cloud, so setup signs in to the
 * Comfort (Kumo Cloud V3) account once, reads them, finds each adapter's address on the LAN and
 * keeps just the per-unit secrets on the TV. The account password is not stored.
 *
 * Protocol from pykumo (MIT, https://github.com/dlarrick/pykumo), which Home Assistant's
 * Mitsubishi Comfort integration also uses.
 */
object KumoClient {

    data class Unit(
        val serial: String,
        val label: String,
        val mac: String,
        /** Base64 password from the cloud. */
        val password: String,
        /** Hex crypto serial from the cloud. */
        val cryptoSerial: String,
        /** "host" or "host:port" on the LAN; blank until found. */
        val address: String = "",
    ) {
        fun toJson(): JSONObject = JSONObject().put("serial", serial).put("label", label).put("mac", mac)
            .put("password", password).put("cryptoSerial", cryptoSerial).put("address", address)

        companion object {
            fun fromJson(o: JSONObject) = Unit(
                o.optString("serial"), o.optString("label"), o.optString("mac"),
                o.optString("password"), o.optString("cryptoSerial"), o.optString("address"),
            )
        }
    }

    /** What the card shows; temperatures in °C as the adapter reports them. */
    data class Status(
        val mode: String,            // off, cool, heat, dry, vent, auto
        val spCool: Double?,
        val spHeat: Double?,
        val roomTemp: Double?,
        val fanSpeed: String,
    ) {
        val power: Boolean get() = mode.isNotBlank() && mode != "off"
        /** The set point the current mode uses. */
        fun setPoint(): Double? = when (mode) { "heat" -> spHeat; "cool", "dry" -> spCool; "auto" -> spCool ?: spHeat; else -> null }
    }

    data class Profile(
        val modes: List<String>,       // in cycle order, without "off"
        val fanSpeeds: List<String>,
        val minCool: Double, val maxCool: Double,
        val minHeat: Double, val maxHeat: Double,
    )

    class AuthException(message: String) : Exception(message)

    private const val TAG = "Kumo"
    private val W_PARAM = hex("44c73283b498d432ff25f5c8e06a016aef931e68f0a00ea710e36e6338fb22db")
    private const val V3 = "https://app-prod.kumocloud.com"
    private const val SOCKET = "https://socket-prod.kumocloud.com"
    private const val APP_VERSION = "3.2.4"
    private const val MIN_GAP_MS = 250L

    // ---- local API ---------------------------------------------------------------------------

    /** The adapter's request token for [body]. */
    fun token(passwordB64: String, cryptoSerialHex: String, body: ByteArray): String {
        val password = b64(passwordB64)
        val crypto = hex(cryptoSerialHex)
        require(crypto.size >= 9) { "crypto serial too short" }
        val dataHash = sha256(password + body)
        val buf = ByteArray(88)
        System.arraycopy(W_PARAM, 0, buf, 0, 32)
        System.arraycopy(dataHash, 0, buf, 32, 32)
        buf[64] = 0x08; buf[65] = 0x40; buf[66] = 0
        buf[79] = crypto[8]
        System.arraycopy(crypto, 4, buf, 80, 4)
        System.arraycopy(crypto, 0, buf, 84, 4)
        return sha256(buf).joinToString("") { "%02x".format(it) }
    }

    // One request at a time per adapter, with a short idle gap: the adapters have very few sockets.
    private val gates = HashMap<String, Mutex>()
    private val lastEnd = HashMap<String, Long>()

    private suspend fun request(unit: Unit, body: String, timeoutMs: Int = 8000): JSONObject? = withContext(Dispatchers.IO) {
        if (unit.address.isBlank()) return@withContext null
        val gate = synchronized(gates) { gates.getOrPut(unit.address) { Mutex() } }
        gate.withLock {
            val wait = (lastEnd[unit.address] ?: 0L) + MIN_GAP_MS - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            try {
                put(unit, body.toByteArray(Charsets.UTF_8), timeoutMs)
            } finally {
                lastEnd[unit.address] = System.currentTimeMillis()
            }
        }
    }

    private fun put(unit: Unit, body: ByteArray, timeoutMs: Int): JSONObject? {
        val url = URL("http://${unit.address}/api?m=" + token(unit.password, unit.cryptoSerial, body))
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "PUT"
            conn.connectTimeout = 1500
            conn.readTimeout = timeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Accept", "application/json, text/plain, */*")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body) }
            val text = (if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: return null
            val json = JSONObject(text)
            if (json.has("_api_error")) {
                Log.w(TAG, "${unit.label}: ${json.optString("_api_error")}")
                return null
            }
            json
        } catch (e: Exception) {
            Log.w(TAG, "${unit.label} @ ${unit.address}: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    suspend fun status(unit: Unit): Status? {
        val r = request(unit, """{"c":{"indoorUnit":{"status":{}}}}""") ?: return null
        val s = r.optJSONObject("r")?.optJSONObject("indoorUnit")?.optJSONObject("status") ?: return null
        return Status(
            mode = s.optString("mode", ""),
            spCool = s.optDoubleOrNull("spCool"),
            spHeat = s.optDoubleOrNull("spHeat"),
            roomTemp = s.optDoubleOrNull("roomTemp"),
            fanSpeed = s.optString("fanSpeed", ""),
        )
    }

    suspend fun profile(unit: Unit): Profile? {
        val r = request(unit, """{"c":{"indoorUnit":{"profile":{}}}}""") ?: return null
        val p = r.optJSONObject("r")?.optJSONObject("indoorUnit")?.optJSONObject("profile") ?: return null
        val modes = buildList {
            add("cool")
            if (p.optBoolean("hasModeHeat")) add("heat")
            if (p.optBoolean("hasModeDry")) add("dry")
            if (p.optBoolean("hasModeVent")) add("vent")
            if (p.optBoolean("hasModeAuto")) add("auto")
        }
        val fans = when (p.optInt("numberOfFanSpeeds", 5)) {
            4 -> mutableListOf("quiet", "low", "powerful", "superPowerful")
            else -> mutableListOf("superQuiet", "quiet", "low", "powerful", "superPowerful")
        }
        if (p.optBoolean("hasFanSpeedAuto")) fans.add(0, "auto")
        val min = p.optJSONObject("minimumSetPoints")
        val max = p.optJSONObject("maximumSetPoints")
        return Profile(
            modes, fans,
            minCool = min?.optDoubleOrNull("cool") ?: 16.0, maxCool = max?.optDoubleOrNull("cool") ?: 31.0,
            minHeat = min?.optDoubleOrNull("heat") ?: 10.0, maxHeat = max?.optDoubleOrNull("heat") ?: 31.0,
        )
    }

    suspend fun setMode(unit: Unit, mode: String) =
        request(unit, """{"c":{"indoorUnit":{"status":{"mode":"$mode"}}}}""") != null

    suspend fun setFan(unit: Unit, speed: String) =
        request(unit, """{"c":{"indoorUnit":{"status":{"fanSpeed":"$speed"}}}}""") != null

    /** Sets the set point the given mode uses ("heat" -> spHeat, otherwise spCool). */
    suspend fun setSetPoint(unit: Unit, mode: String, celsius: Double): Boolean {
        val key = if (mode == "heat") "spHeat" else "spCool"
        return request(unit, """{"c":{"indoorUnit":{"status":{"$key":${"%.1f".format(java.util.Locale.US, celsius)}}}}}""") != null
    }

    // ---- finding adapters on the LAN -------------------------------------------------------

    /**
     * Finds each unit's address: lists hosts with port 80 open on the TV's /24 network and
     * asks each one for status with each unit's credentials; the one that answers is that unit.
     */
    suspend fun locate(units: List<Unit>, prefer: List<String> = emptyList()): List<Unit> = coroutineScope {
        val pending = units.filter { it.password.isNotBlank() && it.cryptoSerial.isNotBlank() }.toMutableList()
        val found = HashMap<String, String>()
        suspend fun tryHost(host: String) {
            for (u in pending.toList()) {
                if (u.serial in found) continue
                if (status(u.copy(address = host)) != null) { synchronized(found) { found[u.serial] = host }; break }
            }
        }
        // Last known addresses first: usually nothing has moved.
        for (h in prefer.filter { it.isNotBlank() }.distinct()) tryHost(h)
        pending.removeAll { it.serial in found }
        if (pending.isNotEmpty()) {
            val hosts = openHosts(80)
            Log.d(TAG, "port 80 open on ${hosts.size} hosts")
            for (h in hosts) { if (pending.all { it.serial in found }) break; tryHost(h) }
        }
        units.map { u -> found[u.serial]?.let { u.copy(address = it) } ?: u }
    }

    private suspend fun openHosts(port: Int): List<String> = withContext(Dispatchers.IO) {
        val prefix = lanPrefix() ?: return@withContext emptyList()
        val limit = Semaphore(48)
        coroutineScope {
            (1..254).map { i ->
                async {
                    limit.withPermit {
                        val host = "$prefix.$i"
                        runCatching { Socket().use { it.connect(InetSocketAddress(host, port), 400) }; host }.getOrNull()
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    private fun lanPrefix(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress?.substringBeforeLast('.')
    }.getOrNull()

    // ---- one-time cloud setup (Mitsubishi Comfort / Kumo Cloud V3) ------------------------

    /** Signs in, lists the account's indoor units and fetches their local-control secrets. */
    suspend fun fetchUnits(email: String, password: String): List<Unit> = withContext(Dispatchers.IO) {
        val login = http("POST", "$V3/v3/login", null,
            JSONObject().put("username", email).put("password", password).put("appVersion", APP_VERSION).toString())
        if (login.first == 401 || login.first == 403) throw AuthException("Mitsubishi Comfort rejected the email or password")
        if (login.first !in 200..299) throw IllegalStateException("Sign-in failed (HTTP ${login.first})")
        val access = JSONObject(login.second).optJSONObject("token")?.optString("access").orEmpty()
        if (access.isBlank()) throw IllegalStateException("Sign-in gave no token")

        val units = LinkedHashMap<String, Unit>()
        val sites = JSONArray(http("GET", "$V3/v3/sites/", access).second.ifBlank { "[]" })
        for (i in 0 until sites.length()) {
            val siteId = sites.optJSONObject(i)?.optString("id").orEmpty().ifBlank { continue }
            val zones = JSONArray(http("GET", "$V3/v3/sites/$siteId/zones", access).second.ifBlank { "[]" })
            for (j in 0 until zones.length()) {
                val z = zones.optJSONObject(j) ?: continue
                val a = z.optJSONObject("adapter") ?: continue
                val serial = a.optString("deviceSerial").ifBlank { continue }
                units[serial] = Unit(serial, z.optString("name", serial), a.optString("macAddress"), "", "")
            }
        }
        if (units.isEmpty()) throw IllegalStateException("No indoor units on this Mitsubishi Comfort account")
        for (serial in units.keys.toList()) {
            val st = http("GET", "$V3/v3/devices/$serial/status", access)
            if (st.first !in 200..299) Log.w(TAG, "device status HTTP ${st.first}")
            val crypto = runCatching { JSONObject(st.second).optString("cryptoSerial") }.getOrDefault("")
            units[serial] = units.getValue(serial).copy(cryptoSerial = crypto)
        }
        Log.i(TAG, "crypto serials: ${units.values.count { it.cryptoSerial.isNotBlank() }}/${units.size}")
        val passwords = passwordsViaSocket(access, units.keys)
        Log.i(TAG, "adapter keys: ${passwords.size}/${units.size}")
        units.values.map { it.copy(password = passwords[it.serial].orEmpty()) }
    }

    /** The cloud hands out adapter passwords only as "adapter_update" events over Socket.IO. */
    private fun passwordsViaSocket(access: String, serials: Set<String>): Map<String, String> {
        val out = HashMap<String, String>()
        val cookies = HashMap<String, String>()
        val hs = sio("GET", null, access, null, cookies)
        if (!hs.startsWith("0")) { Log.w(TAG, "socket handshake failed: ${hs.take(80)}"); return out }
        Log.i(TAG, "socket handshake ok, cookies: ${cookies.keys}")
        val sid = JSONObject(hs.substring(1)).optString("sid")
        fun post(data: String) = sio("POST", sid, access, data, cookies)
        fun poll() = sio("GET", sid, access, null, cookies).also { extract(it, serials, out); Log.i(TAG, "socket poll: ${describe(it)}") }
        post("40"); poll()
        userId(access)?.let { post("""42["subscribe","","$it"]"""); poll() }
        post(serials.joinToString("\u001e") { """42["subscribe","$it"]""" }); poll()
        post(serials.joinToString("\u001e") { """42["force_adapter_request","$it","adapterStatus"]""" })
        post((listOf("""42["device_status_v2",""]""") + serials.map { """42["device_status_v2","$it"]""" }).joinToString("\u001e"))
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline && !out.keys.containsAll(serials)) {
            val text = runCatching { poll() }.getOrNull() ?: break
            if (text.split('\u001e').any { it == "2" }) runCatching { post("3") }
        }
        return out
    }

    /** Packet types and event names only (never payloads), for the log. */
    private fun describe(raw: String): String = raw.split('\u001e').joinToString(" ") { p ->
        val m = p.substringAfter(':').let { if (p.substringBefore(':').all(Char::isDigit) && p.contains(':')) it else p }
        when {
            m.startsWith("42") -> "event:" + (runCatching { JSONArray(m.substring(2)).optString(0) }.getOrNull() ?: "?")
            m.startsWith("44") -> "connect_error:" + m.drop(2).take(60)
            m.startsWith("40") -> "connected"
            else -> m.take(12)
        }
    }

    private fun extract(raw: String, serials: Set<String>, out: MutableMap<String, String>) {
        for (part in raw.split('\u001e')) {
            var msg = part
            if (msg.contains(':') && msg.substringBefore(':').all { it.isDigit() } && msg.substringBefore(':').isNotEmpty()) msg = msg.substringAfter(':')
            if (!msg.startsWith("42")) continue
            val arr = runCatching { JSONArray(msg.substring(2)) }.getOrNull() ?: continue
            if (arr.optString(0) != "adapter_update") continue
            val o = arr.optJSONObject(1) ?: continue
            val serial = o.optString("deviceSerial"); val pw = o.optString("password")
            if (serial in serials && pw.isNotBlank()) out[serial] = pw
        }
    }

    private fun userId(access: String): String? = runCatching {
        val payload = access.split('.')[1]
        val json = String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
        JSONObject(json).opt("id")?.toString()
    }.getOrNull()

    private fun sio(method: String, sid: String?, access: String, body: String?, cookies: MutableMap<String, String>): String {
        val q = "EIO=4&transport=polling" + (sid?.let { "&sid=$it" } ?: "")
        val conn = URL("$SOCKET/socket.io/?$q").openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000; conn.readTimeout = 30_000
            conn.setRequestProperty("Authorization", "Bearer $access")
            conn.setRequestProperty("Accept", "*/*")
            if (cookies.isNotEmpty()) conn.setRequestProperty("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "text/plain;charset=UTF-8")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            // Load-balancer stickiness: later polls must reach the same server, so keep its cookies.
            conn.headerFields.filterKeys { it != null && it.equals("Set-Cookie", ignoreCase = true) }.values.flatten().forEach { c ->
                c.substringBefore(';').split('=', limit = 2).takeIf { it.size == 2 }?.let { cookies[it[0].trim()] = it[1] }
            }
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) Log.w(TAG, "socket $method HTTP $code: ${text.take(80)}")
            text
        } finally {
            conn.disconnect()
        }
    }

    private fun http(method: String, url: String, access: String?, body: String? = null): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = method
            conn.connectTimeout = 10_000; conn.readTimeout = 30_000
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("x-app-version", APP_VERSION)
            conn.setRequestProperty("Content-Type", "application/json")
            if (access != null) conn.setRequestProperty("Authorization", "Bearer $access")
            if (body != null) { conn.doOutput = true; conn.outputStream.use { it.write(body.toByteArray()) } }
            val code = conn.responseCode
            code to ((if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally {
            conn.disconnect()
        }
    }

    // ---- helpers -----------------------------------------------------------------------------

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key).takeUnless { it.isNaN() } else null

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    // java.util.Base64 rather than android.util.Base64 so the token is testable on the JVM.
    private fun b64(s: String): ByteArray = java.util.Base64.getDecoder().decode(s)

    /** °C set point for a °F value, on the half-degree grid the units use. */
    fun fToC(f: Double): Double = Math.round((f - 32) * 5 / 9 * 2) / 2.0
    fun cToF(c: Double): Int = Math.round(c * 9 / 5 + 32).toInt()

    fun modeLabel(mode: String) = when (mode) { "vent" -> "Fan"; "dry" -> "Dry"; "auto" -> "Auto"; "heat" -> "Heat"; "cool" -> "Cool"; "off" -> "Off"; else -> mode }
    fun fanLabel(speed: String) = when (speed) {
        "superQuiet" -> "Quiet+"; "quiet" -> "Quiet"; "low" -> "Low"; "powerful" -> "High"; "superPowerful" -> "Max"; "auto" -> "Auto"; else -> speed
    }
}
