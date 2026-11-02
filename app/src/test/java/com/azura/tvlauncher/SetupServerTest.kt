package com.azura.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class SetupServerTest {

    private fun request(url: String, method: String, body: String? = null): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
        conn.disconnect()
        return code to text
    }

    @Test
    fun formRoundTrip() {
        var saved: Map<String, String>? = null
        val server = SetupServer(
            port = 18765,
            current = { mapOf("city" to "Denver", "ha_url" to "http://ha.local:8123") },
            secretsSet = { setOf("gemini_key") },
            onSave = { saved = it },
        )
        assertTrue("server should start on a LAN address", server.start())
        try {
            val (getCode, page) = request(server.address, "GET")
            assertEquals(200, getCode)
            assertTrue(page.contains("value=\"Denver\""))
            assertTrue("secrets are never echoed", !page.contains("value=\"AIza"))
            assertTrue(page.contains("Already set. Leave blank to keep it."))

            val (wrong, wrongPage) = request(server.address, "POST", "code=0000&city=Paris")
            assertEquals(403, wrong)
            assertTrue(wrongPage.contains("Wrong code"))
            assertEquals(null, saved)

            val body = "code=${server.code}&city=Paris%20%26%20Lyon&gemini_key=&claude_key=sk-ant-1&ics=https%3A%2F%2Fa.example%2Fx.ics%0Ahttps%3A%2F%2Fb.example%2Fy.ics"
            val (ok, okPage) = request(server.address, "POST", body)
            assertEquals(200, ok)
            assertTrue(okPage.contains("Saved to the TV"))
            val s = saved!!
            assertEquals("Paris & Lyon", s["city"])
            assertEquals("sk-ant-1", s["claude_key"])
            assertTrue("blank secret is dropped so the old key is kept", !s.containsKey("gemini_key"))
            assertEquals("https://a.example/x.ics\nhttps://b.example/y.ics", s["ics"])

            assertEquals(404, request(server.address + "nope", "GET").first)
        } finally {
            server.stop()
        }
    }

    @Test
    fun locksAfterFiveWrongCodes() {
        val server = SetupServer(18766, { emptyMap() }, { emptySet() }, { })
        assertTrue(server.start())
        try {
            repeat(4) { assertEquals(403, request(server.address, "POST", "code=9999").first) }
            val (code, page) = request(server.address, "POST", "code=9999")
            assertEquals(403, code)
            assertTrue(page.contains("Too many wrong codes"))
        } finally {
            server.stop()
        }
    }
}
