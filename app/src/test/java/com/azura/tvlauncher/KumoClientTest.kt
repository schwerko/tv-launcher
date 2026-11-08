package com.azura.tvlauncher

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket

class KumoClientTest {

    private val pw = "c2VjcmV0LXBhc3N3b3JkLTEyMw=="          // base64("secret-password-123")
    private val crypto = "0123456789abcdef0123"

    /** Expected values computed with pykumo's PyKumoBase._token for the same inputs. */
    @Test
    fun tokenMatchesPykumo() {
        assertEquals(
            "63a96847f30ec3790e0e6192f50ffbd917bf57ea4dd28d3f57393c15e8fff577",
            KumoClient.token(pw, crypto, """{"c":{"indoorUnit":{"status":{}}}}""".toByteArray()),
        )
        assertEquals(
            "3f97e05c47a1c8b47ff95ccfd3dc0638418a161f6a39f980a9cdfdc2a81efbbe",
            KumoClient.token(pw, crypto, """{"c":{"indoorUnit":{"status":{"mode":"cool"}}}}""".toByteArray()),
        )
    }

    @Test
    fun temperatureConversions() {
        assertEquals(22.0, KumoClient.fToC(72.0), 0.0)
        assertEquals(23.0, KumoClient.fToC(73.0), 0.0)
        assertEquals(72, KumoClient.cToF(22.0))
        assertEquals(73, KumoClient.cToF(22.5))
        // Every whole °F from 60 to 86 survives the trip to the unit's half-degree °C and back.
        for (f in 60..86) assertEquals(f, KumoClient.cToF(KumoClient.fToC(f.toDouble())))
    }

    /** A fake adapter that, like the real one, only answers correctly signed requests. */
    @Test
    fun talksToAFakeAdapter() {
        val state = JSONObject("""{"mode":"heat","spHeat":21.5,"spCool":24.0,"roomTemp":20.0,"fanSpeed":"quiet"}""")
        fun answer(method: String, query: String, body: ByteArray): String {
            val m = query.substringAfter("m=")
            if (method != "PUT" || m != KumoClient.token(pw, crypto, body)) return """{"_api_error": "device_authentication_error"}"""
            val c = JSONObject(String(body)).getJSONObject("c").getJSONObject("indoorUnit")
            if (c.has("profile")) return """{"r":{"indoorUnit":{"profile":{"hasModeHeat":true,"hasModeDry":true,"hasModeVent":true,"hasModeAuto":false,"numberOfFanSpeeds":5,"hasFanSpeedAuto":true,"minimumSetPoints":{"cool":16,"heat":10},"maximumSetPoints":{"cool":31,"heat":31}}}}}"""
            val changes = c.getJSONObject("status")
            changes.keys().forEach { state.put(it, changes.get(it)) }
            return JSONObject().put("r", JSONObject().put("indoorUnit", JSONObject().put("status", state))).toString()
        }
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val thread = Thread {
            while (!server.isClosed) {
                val sock = runCatching { server.accept() }.getOrNull() ?: break
                sock.use { s ->
                    val input = s.getInputStream()
                    val reader = BufferedReader(InputStreamReader(input, Charsets.ISO_8859_1))
                    val request = reader.readLine() ?: return@use
                    var length = 0
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                    }
                    val buf = CharArray(length)
                    var read = 0
                    while (read < length) { val n = reader.read(buf, read, length - read); if (n < 0) break; read += n }
                    val (method, target) = request.split(' ').let { it[0] to it[1] }
                    val reply = answer(method, target.substringAfter('?', ""), String(buf, 0, read).toByteArray(Charsets.ISO_8859_1)).toByteArray()
                    s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${reply.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    s.getOutputStream().write(reply)
                    s.getOutputStream().flush()
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            val unit = KumoClient.Unit("S1", "Living room", "", pw, crypto, "127.0.0.1:${server.localPort}")
            runBlocking {
                val st = KumoClient.status(unit)
                assertNotNull(st)
                assertEquals("heat", st!!.mode)
                assertEquals(21.5, st.setPoint()!!, 0.0)
                assertTrue(st.power)

                val p = KumoClient.profile(unit)!!
                assertEquals(listOf("cool", "heat", "dry", "vent"), p.modes)
                assertEquals("auto", p.fanSpeeds.first())

                assertTrue(KumoClient.setSetPoint(unit, "heat", 22.0))
                assertTrue(KumoClient.setMode(unit, "off"))
                val after = KumoClient.status(unit)!!
                assertEquals(22.0, after.spHeat!!, 0.0)
                assertEquals("off", after.mode)

                // Wrong key: the adapter refuses and the client reports nothing.
                assertNull(KumoClient.status(unit.copy(password = "d3JvbmctcGFzc3dvcmQ=")))
            }
        } finally {
            server.close()
            thread.join(1000)
        }
    }
}
