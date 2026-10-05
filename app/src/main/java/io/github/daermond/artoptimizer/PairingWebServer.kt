package io.github.daermond.artoptimizer

import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/** A deliberately small, single-purpose HTTP server. It never accepts ADB commands. */
internal class PairingWebServer(
    address: InetAddress,
    private val onCode: (String) -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    private val lifetimeMillis: Long = 180_000,
) : AutoCloseable {
    private val socket = ServerSocket(0, 8, address)
    private val running = AtomicBoolean(true)
    private val expiresAt = now() + lifetimeMillis
    private val token = ByteArray(24).also(SecureRandom()::nextBytes)
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    private val authority = "${address.hostAddress}:${socket.localPort}"
    val url: String = "http://$authority/$token"
    private val worker = Thread(::serve, "pairing-web").apply { isDaemon = true; start() }

    private fun serve() {
        while (running.get()) {
            val client = try { socket.accept() } catch (_: Exception) { break }
            client.use { runCatching { handle(it) } }
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = 3_000
        val input = BufferedInputStream(client.getInputStream())
        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(' ')
        if (parts.size != 3 || parts[2] != "HTTP/1.1") {
            respond(client, 400, "Bad request")
            return
        }
        val headers = mutableMapOf<String, String>()
        var headerCount = 0
        while (headerCount++ < 32) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator <= 0) { respond(client, 400, "Bad request"); return }
            headers[line.substring(0, separator).lowercase()] = line.substring(separator + 1).trim()
        }
        if (headerCount > 32) { respond(client, 400, "Bad request"); return }
        val host = headers["host"]
        if (host != authority || now() >= expiresAt || !running.get()) {
            respond(client, 404, "Unavailable")
            return
        }
        when {
            parts[0] == "GET" && parts[1] == "/$token" -> {
                respond(client, 200, page(), "text/html; charset=utf-8")
            }
            parts[0] == "POST" && parts[1] == "/$token/pair" -> {
                // Some mobile browsers omit Origin for a top-level HTML form POST.
                // The unguessable session path is the CSRF token; reject a foreign Origin when sent.
                val origin = headers["origin"]
                if (origin != null && origin != "null" && origin != "http://$authority") {
                    respond(client, 403, "Forbidden")
                    return
                }
                val length = headers["content-length"]?.toIntOrNull()
                if (length == null || length !in 1..64) {
                    respond(client, 400, "Bad request")
                    return
                }
                val body = ByteArray(length)
                var offset = 0
                while (offset < length) {
                    val read = input.read(body, offset, length - offset)
                    if (read < 0) return
                    offset += read
                }
                val raw = String(body, StandardCharsets.US_ASCII)
                val code = if (raw.startsWith("code="))
                    runCatching { URLDecoder.decode(raw.removePrefix("code="), "UTF-8") }.getOrNull()
                else null
                if (code == null || !CODE.matches(code)) {
                    respond(client, 400, "Enter a six-digit code")
                    return
                }
                if (onCode(code)) respond(client, 200, submittedPage(), "text/html; charset=utf-8")
                else respond(client, 409, "Pairing is busy or this session has ended")
            }
            else -> respond(client, 404, "Unavailable")
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = ArrayList<Byte>(2048)
        while (bytes.size < 2048) {
            val value = input.read()
            if (value < 0) return null
            if (value == 10) return String(bytes.toByteArray(), StandardCharsets.US_ASCII).trimEnd('\r')
            bytes.add(value.toByte())
        }
        return null
    }

    private fun page(): String = """<!doctype html><html lang="en"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="referrer" content="no-referrer"><title>ART Optimizer pairing</title><style>body{font:1.1rem system-ui;max-width:28rem;margin:3rem auto;padding:0 1rem}input,button{font:inherit;padding:.7rem;margin:.4rem 0;width:100%;box-sizing:border-box}input{letter-spacing:.25em}</style><h1>Pair ART Optimizer</h1><p>Enter the six-digit code shown in Wireless Debugging on the Android device. This page is available briefly on your local network.</p><form action="/$token/pair" method="post"><label for="code">Pairing code</label><input id="code" name="code" type="text" inputmode="numeric" pattern="[0-9]{6}" maxlength="6" autocomplete="off" required><button type="submit">Pair</button></form></html>"""

    private fun submittedPage(): String = """<!doctype html><html lang="en"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Code submitted</title><h1>Code submitted</h1><p>Check the Android device for the pairing result. If the code was wrong, return and try again while the pairing dialog is open.</p></html>"""

    private fun respond(client: Socket, status: Int, body: String, type: String = "text/plain; charset=utf-8") {
        val payload = body.toByteArray(StandardCharsets.UTF_8)
        val reason = when (status) { 200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; else -> "Conflict" }
        val headers = "HTTP/1.1 $status $reason\r\nContent-Type: $type\r\nContent-Length: ${payload.size}\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\nConnection: close\r\n\r\n"
        client.getOutputStream().apply {
            write(headers.toByteArray(StandardCharsets.US_ASCII))
            write(payload)
            flush()
        }
    }

    override fun close() {
        running.set(false)
        socket.close()
        if (Thread.currentThread() !== worker) worker.join(1_000)
    }

    companion object { private val CODE = Regex("^[0-9]{6}$") }
}
