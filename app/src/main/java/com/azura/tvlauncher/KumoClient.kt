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
        val humidity: Double? = null,
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

    data class Account(val units: List<Unit>, val refreshToken: String)

    /**
     * Signs in, lists the account's indoor units and tries to fetch their local-control secrets.
     * Units the cloud gives no local key for are controlled through the cloud with [Cloud], using
     * the returned refresh token (the password itself is not kept).
     */
    suspend fun fetchUnits(email: String, password: String): Account = withContext(Dispatchers.IO) {
        val login = http("POST", "$V3/v3/login", null,
            JSONObject().put("username", email).put("password", password).put("appVersion", APP_VERSION).toString())
        if (login.first == 401 || login.first == 403) throw AuthException("Mitsubishi Comfort rejected the email or password")
        if (login.first !in 200..299) throw IllegalStateException("Sign-in failed (HTTP ${login.first})")
        val tokens = JSONObject(login.second).optJSONObject("token")
        val access = tokens?.optString("access").orEmpty()
        val refresh = tokens?.optString("refresh").orEmpty()
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
            val stJson = runCatching { JSONObject(st.second) }.getOrNull()
            if (stJson != null) Log.i(TAG, "device status shape: ${shape(stJson)}")
            val crypto = stJson?.let { findString(it, CRYPTO_KEYS) }.orEmpty()
            units[serial] = units.getValue(serial).copy(cryptoSerial = crypto)
        }
        Log.i(TAG, "crypto serials: ${units.values.count { it.cryptoSerial.isNotBlank() }}/${units.size}")
        val fromSocket = passwordsViaSocket(access, units.keys)
        Log.i(TAG, "adapter keys: ${fromSocket.count { it.value.first.isNotBlank() }}/${units.size}")
        Account(units.values.map { u ->
            val (pw, crypto) = fromSocket[u.serial] ?: ("" to "")
            u.copy(password = pw, cryptoSerial = u.cryptoSerial.ifBlank { crypto })
        }, refresh)
    }

    /** The cloud hands out adapter passwords only as "adapter_update" events over Socket.IO. */
    private fun passwordsViaSocket(access: String, serials: Set<String>): Map<String, Pair<String, String>> {
        val out = HashMap<String, Pair<String, String>>()
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
        while (System.currentTimeMillis() < deadline && !serials.all { out[it]?.first?.isNotBlank() == true }) {
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

    /**
     * Picks each unit's adapter password (and crypto serial, if present) out of any event that
     * mentions the unit. Field names and nesting are searched rather than assumed, because they
     * differ between Kumo Cloud versions; each event's shape (keys, never values) is logged once.
     */
    private fun extract(raw: String, serials: Set<String>, out: MutableMap<String, Pair<String, String>>) {
        for (part in raw.split('\u001e')) {
            var msg = part
            if (msg.contains(':') && msg.substringBefore(':').all { it.isDigit() } && msg.substringBefore(':').isNotEmpty()) msg = msg.substringAfter(':')
            if (!msg.startsWith("42")) continue
            val arr = runCatching { JSONArray(msg.substring(2)) }.getOrNull() ?: continue
            val name = arr.optString(0)
            val body = arr.opt(1) ?: continue
            if (name in setOf("adapter_update", "device_update", "device_status_v2") && loggedShapes.add(name)) Log.i(TAG, "$name shape: ${shape(body)}")
            val serial = findString(body, listOf("deviceSerial", "serial", "serialNumber")).orEmpty()
            if (serial !in serials) continue
            val pw = findString(body, PASSWORD_KEYS).orEmpty()
            val crypto = findString(body, CRYPTO_KEYS).orEmpty()
            val prev = out[serial] ?: ("" to "")
            out[serial] = prev.first.ifBlank { pw } to prev.second.ifBlank { crypto }
        }
    }

    private val loggedShapes = HashSet<String>()
    private val PASSWORD_KEYS = listOf("password", "adapterPassword", "localPassword", "devicePassword")
    private val CRYPTO_KEYS = listOf("cryptoSerial", "crypto_serial", "cryptoSerialNumber")

    /** First non-blank string under any of [keys], searching nested objects and arrays. */
    private fun findString(node: Any?, keys: List<String>, depth: Int = 0): String? {
        if (depth > 6) return null
        when (node) {
            is JSONObject -> {
                for (k in keys) node.optString(k).takeIf { node.has(k) && it.isNotBlank() && it != "null" }?.let { return it }
                for (k in node.keys()) findString(node.opt(k), keys, depth + 1)?.let { return it }
            }
            is JSONArray -> for (i in 0 until node.length()) findString(node.opt(i), keys, depth + 1)?.let { return it }
        }
        return null
    }

    /** Structure only, for the log: keys with value types and string lengths, never the values. */
    private fun shape(node: Any?, depth: Int = 0): String = when {
        depth > 3 -> "…"
        node is JSONObject -> node.keys().asSequence().take(40).joinToString(",", "{", "}") { k -> "$k:" + shape(node.opt(k), depth + 1) }
        node is JSONArray -> "[" + (if (node.length() > 0) shape(node.opt(0), depth + 1) else "") + "]x${node.length()}"
        node is String -> "s${node.length}"
        node is Number -> "n"
        node is Boolean -> "b"
        else -> "null"
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

    // ---- cloud control (units the cloud gives no local key for) ------------------------------

    /**
     * Mitsubishi Comfort cloud session: status from `GET /v3/devices/{serial}`, commands to
     * `POST /v3/devices/send-command`, as documented by github.com/ventz/kumo-cloud-v3-api-comfort-client
     * (MIT). Holds only a refresh token (about 30 days, renewed on every use); [onRefresh]
     * persists each renewed one. Throws [AuthException] when it has expired and a new sign-in
     * is needed.
     */
    class Cloud(private var refresh: String, private val onRefresh: (String) -> kotlin.Unit) {
        private var access: String? = null
        private val lock = Mutex()

        private suspend fun accessToken(force: Boolean): String = lock.withLock {
            access?.takeIf { !force }?.let { return it }
            if (refresh.isBlank()) throw AuthException("Sign in to Mitsubishi Comfort again")
            val r = withContext(Dispatchers.IO) {
                http("POST", "$V3/v3/refresh", refresh, JSONObject().put("refresh", refresh).toString())
            }
            if (r.first == 401 || r.first == 403) throw AuthException("Mitsubishi Comfort sign-in expired; sign in again")
            if (r.first !in 200..299) throw IllegalStateException("Token refresh failed (HTTP ${r.first})")
            val j = JSONObject(r.second)
            val a = j.optString("access").ifBlank { j.optJSONObject("token")?.optString("access").orEmpty() }
            val nr = j.optString("refresh").ifBlank { j.optJSONObject("token")?.optString("refresh").orEmpty() }
            if (a.isBlank()) throw IllegalStateException("Token refresh gave no token")
            access = a
            if (nr.isNotBlank() && nr != refresh) { refresh = nr; onRefresh(nr) }
            a
        }

        private suspend fun call(method: String, path: String, body: String? = null): Pair<Int, String> {
            var r = withContext(Dispatchers.IO) { http(method, "$V3$path", accessToken(false), body) }
            if (r.first == 401) r = withContext(Dispatchers.IO) { http(method, "$V3$path", accessToken(true), body) }
            return r
        }

        suspend fun status(serial: String): Status? {
            val r = runCatching { call("GET", "/v3/devices/$serial") }.getOrElse { if (it is AuthException) throw it; Log.w(TAG, "cloud status: ${it.message}"); return null }
            if (r.first !in 200..299) { Log.w(TAG, "cloud status HTTP ${r.first}"); return null }
            val d = runCatching { JSONObject(r.second) }.getOrNull() ?: return null
            if (d.has("connected") && !d.optBoolean("connected")) return null
            val on = d.optInt("power", 1) != 0
            return Status(
                mode = if (on) d.optString("operationMode", "") else "off",
                spCool = d.optDoubleOrNull("spCool"),
                spHeat = d.optDoubleOrNull("spHeat"),
                roomTemp = d.optDoubleOrNull("roomTemp"),
                fanSpeed = d.optString("fanSpeed", ""),
                humidity = d.optDoubleOrNull("humidity"),
            )
        }

        /** Capabilities, read defensively: the profile reply's layout is not documented. */
        suspend fun profile(serial: String): Profile? {
            val r = runCatching { call("GET", "/v3/devices/$serial/profile") }.getOrNull() ?: return null
            if (r.first !in 200..299) return null
            val node: Any = runCatching { JSONArray(r.second) as Any }.recoverCatching { JSONObject(r.second) }.getOrNull() ?: return null
            Log.i(TAG, "cloud profile shape: ${shape(node)}")
            fun flag(key: String) = findString(node, listOf(key)) == "true"
            val modes = buildList {
                add("cool")
                if (flag("hasModeHeat")) add("heat")
                if (flag("hasModeDry")) add("dry")
                if (flag("hasModeVent")) add("vent")
                if (flag("hasModeAuto")) add("auto")
            }.let { if (it.size == 1) listOf("cool", "heat", "dry", "vent") else it }
            val fans = when (findString(node, listOf("numberOfFanSpeeds"))?.toIntOrNull()) {
                4 -> mutableListOf("quiet", "low", "powerful", "superPowerful")
                else -> mutableListOf("superQuiet", "quiet", "low", "powerful", "superPowerful")
            }
            if (flag("hasFanSpeedAuto")) fans.add(0, "auto")
            return Profile(modes, fans, 16.0, 31.0, 10.0, 31.0)
        }

        suspend fun send(serial: String, commands: JSONObject): Boolean {
            val body = JSONObject().put("deviceSerial", serial).put("commands", commands).toString()
            val r = runCatching { call("POST", "/v3/devices/send-command", body) }.getOrElse { if (it is AuthException) throw it; return false }
            if (r.first !in 200..299) Log.w(TAG, "cloud command HTTP ${r.first}: ${r.second.take(120)}")
            return r.first in 200..299
        }

        /** Mitsubishi asks for operationMode "off" as well as power 0; power alone is ignored on some units. */
        suspend fun setMode(serial: String, mode: String) = send(serial,
            if (mode == "off") JSONObject().put("operationMode", "off").put("power", 0)
            else JSONObject().put("operationMode", mode).put("power", 1))

        suspend fun setFan(serial: String, speed: String) = send(serial, JSONObject().put("fanSpeed", speed))

        /** The cloud accepts set points in 0.5 °C steps only. */
        suspend fun setSetPoint(serial: String, mode: String, celsius: Double) =
            send(serial, JSONObject().put(if (mode == "heat") "spHeat" else "spCool", Math.round(celsius * 2) / 2.0))
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
