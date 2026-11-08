package com.azura.tvlauncher

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom

/**
 * "Set up from your phone": a tiny HTTP server that runs only while the matching dialog is open
 * on the TV, listens only on the TV's own LAN address, and serves one HTML form for the long
 * values (calendar links, API keys, Home Assistant token, …) that are painful to type with a
 * remote. Every save must carry the four-digit code shown on the TV; five wrong codes close the
 * server. Nothing is sent anywhere but between the phone and the TV.
 */
class SetupServer(
    private val port: Int,
    /** Current non-secret values to pre-fill, keyed by field name. */
    private val current: () -> Map<String, String>,
    /** Which secret fields already have a value (shown as "set, leave blank to keep"). */
    private val secretsSet: () -> Set<String>,
    /** Called on the server thread with the submitted fields (blank secrets already removed). */
    private val onSave: (Map<String, String>) -> Unit,
) {
    val code: String = "%04d".format(SecureRandom().nextInt(10_000))
    @Volatile var address: String = ""
        private set

    private var socket: ServerSocket? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private var failures = 0

    fun start(): Boolean {
        val ip = lanAddress() ?: return false
        return runCatching {
            val s = ServerSocket()
            s.reuseAddress = true
            s.bind(InetSocketAddress(ip, port), 4)
            socket = s
            address = "http://${ip.hostAddress}:$port/"
            running = true
            thread = Thread({ loop(s) }, "setup-server").apply { isDaemon = true; start() }
            true
        }.onFailure { Log.w(TAG, "start failed", it) }.getOrDefault(false)
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
    }

    private fun loop(server: ServerSocket) {
        while (running) {
            val client = runCatching { server.accept() }.getOrNull() ?: break
            runCatching { client.soTimeout = 10_000; handle(client) }
                .onFailure { Log.w(TAG, "request failed", it) }
            runCatching { client.close() }
        }
    }

    private fun handle(client: Socket) {
        val remote = client.inetAddress
        if (!remote.isSiteLocalAddress && !remote.isLoopbackAddress) {
            respond(client.getOutputStream(), 403, "text/plain", "Local network only"); return
        }
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.ISO_8859_1))
        val requestLine = reader.readLine() ?: return
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0) ?: return
        val path = parts.getOrNull(1)?.substringBefore('?') ?: "/"
        var contentLength = 0
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
        }
        val out = client.getOutputStream()
        when {
            method == "GET" && path == "/" -> respond(out, 200, "text/html; charset=utf-8", page(null))
            method == "POST" && path == "/" -> {
                if (contentLength > 200_000) { respond(out, 413, "text/plain", "Too large"); return }
                val buf = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = reader.read(buf, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                // The body is ISO-8859-1 bytes of a UTF-8, percent-encoded form.
                val body = String(buf, 0, read)
                val fields = parseForm(body)
                if (fields["code"] != code) {
                    failures++
                    if (failures >= MAX_FAILURES) { respond(out, 403, "text/html; charset=utf-8", page("Too many wrong codes. Close and reopen the screen on the TV.")); stop(); return }
                    respond(out, 403, "text/html; charset=utf-8", page("Wrong code. Look at the TV screen and try again.")); return
                }
                val values = fields.filterKeys { it != "code" }.filter { (k, v) -> k !in SECRETS || v.isNotBlank() }
                onSave(values)
                respond(out, 200, "text/html; charset=utf-8", saved())
            }
            else -> respond(out, 404, "text/plain", "Not found")
        }
    }

    private fun parseForm(body: String): Map<String, String> =
        body.split('&').filter { it.contains('=') }.associate { pair ->
            val k = URLDecoder.decode(pair.substringBefore('='), "UTF-8")
            val v = URLDecoder.decode(pair.substringAfter('='), "UTF-8")
            k to v.trim()
        }

    private fun respond(out: OutputStream, status: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (status) { 200 -> "OK"; 403 -> "Forbidden"; 404 -> "Not Found"; 413 -> "Payload Too Large"; else -> "OK" }
        val head = "HTTP/1.1 $status $reason\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }

    // ---- HTML --------------------------------------------------------------------------------

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun field(name: String, label: String, hint: String, values: Map<String, String>, set: Set<String>, multiline: Boolean = false, password: Boolean = false): String {
        val secret = name in SECRETS
        val value = if (secret) "" else esc(values[name].orEmpty())
        val placeholder = if (secret && name in set) "Already set. Leave blank to keep it." else hint
        val input = if (multiline)
            "<textarea name=\"$name\" rows=\"3\" placeholder=\"${esc(placeholder)}\">$value</textarea>"
        else
            "<input name=\"$name\"${if (password) " type=\"password\"" else ""} value=\"$value\" placeholder=\"${esc(placeholder)}\" autocomplete=\"off\" autocapitalize=\"off\" spellcheck=\"false\">"
        return "<label>$label$input</label>"
    }

    private fun page(error: String?): String {
        val values = current()
        val set = secretsSet()
        val sb = StringBuilder()
        sb.append(HEAD).append("<h1>TV Launcher setup</h1><p class=\"sub\">Paste the long bits here instead of typing them with the remote. Only the fields you fill in change; blank ones stay as they are. Everything goes straight to the TV over your home network.</p>")
        if (error != null) sb.append("<p class=\"err\">").append(esc(error)).append("</p>")
        sb.append("<form method=\"post\" action=\"/\">")
        sb.append("<h2>Basics</h2>")
        sb.append(field("city", "Weather city", "e.g. Denver or Paris", values, set))
        sb.append(field("ics", "Calendar feeds (ICS links, one per line)", "https://calendar.google.com/calendar/ical/…/basic.ics", values, set, multiline = true))
        sb.append(field("news", "News sources (RSS links or YouTube channel links, one per line)", "https://feeds.bbci.co.uk/news/world/rss.xml", values, set, multiline = true))
        sb.append(field("wallpaper_urls", "Background: your own image links (one per line; leave empty to keep the current source)", "https://example.com/photo.jpg", values, set, multiline = true))
        sb.append("<h2>Keys</h2>")
        sb.append(field("gemini_key", "Gemini API key (aistudio.google.com)", "AIza…", values, set))
        sb.append(field("claude_key", "Anthropic API key (console.anthropic.com)", "sk-ant-…", values, set))
        sb.append(field("cielo_key", "Cielo API key (home.cielowigle.com › Home Assistant)", "", values, set))
        sb.append(field("unsplash_key", "Unsplash access key (unsplash.com/developers)", "", values, set))
        sb.append("<h2>Mitsubishi Comfort air conditioners</h2>")
        sb.append("<p class=\"sub\">Sign in once so the TV can fetch each unit's local key; the password is used for this one sign-in and not kept. After that the TV talks to the units directly on your network.</p>")
        sb.append(field("kumo_email", "Mitsubishi Comfort email", "name@example.com", values, set))
        sb.append(field("kumo_password", "Mitsubishi Comfort password", "Only needed to set up or add units", values, set, password = true))
        sb.append("<h2>Home Assistant</h2>")
        sb.append(field("ha_url", "Server address", "http://homeassistant.local:8123", values, set))
        sb.append(field("ha_token", "Long-lived access token (your profile › Security)", "eyJ…", values, set))
        sb.append("<h2>Confirm</h2>")
        sb.append("<label>Code shown on the TV<input name=\"code\" inputmode=\"numeric\" pattern=\"[0-9]*\" maxlength=\"4\" placeholder=\"4 digits\" autocomplete=\"off\" required></label>")
        sb.append("<button type=\"submit\">Save to TV</button></form></main></body></html>")
        return sb.toString()
    }

    private fun saved(): String =
        HEAD + "<h1>Saved to the TV</h1><p class=\"sub\">The home screen is updating. You can close this page, or <a href=\"/\">go back</a> to change something else.</p></main></body></html>"

    private fun lanAddress(): InetAddress? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
    }.getOrNull()

    companion object {
        private const val TAG = "SetupServer"
        private const val MAX_FAILURES = 5
        val SECRETS = setOf("gemini_key", "claude_key", "cielo_key", "unsplash_key", "ha_token", "kumo_password")
        private const val HEAD = "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            "<title>TV Launcher setup</title><style>" +
            "body{margin:0;background:#14161b;color:#f2f1ec;font:16px/1.45 -apple-system,Roboto,Helvetica,Arial,sans-serif}" +
            "main{max-width:560px;margin:0 auto;padding:20px 16px 48px}h1{font-size:24px;margin:8px 0 4px}h2{font-size:14px;letter-spacing:.08em;text-transform:uppercase;color:#9a9a94;margin:28px 0 8px}" +
            ".sub{color:#9a9a94;margin:0 0 8px}.err{background:#5a2323;padding:10px 12px;border-radius:8px}" +
            "label{display:block;margin:12px 0 0;font-size:14px;color:#c9c9c3}input,textarea{display:block;width:100%;box-sizing:border-box;margin-top:6px;padding:10px 12px;border:1px solid #3a3d45;border-radius:8px;background:#262930;color:#f2f1ec;font-size:16px}" +
            "textarea{resize:vertical}button{margin-top:24px;width:100%;padding:14px;border:0;border-radius:10px;background:#85b7eb;color:#0b0c10;font-size:17px;font-weight:600}" +
            "</style></head><body><main>"
    }
}
