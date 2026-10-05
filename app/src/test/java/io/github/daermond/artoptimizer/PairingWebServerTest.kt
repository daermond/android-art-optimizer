package io.github.daermond.artoptimizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

class PairingWebServerTest {
    @Test fun tokenAndOriginGateCodeSubmission() {
        val submissions = AtomicInteger()
        PairingWebServer(InetAddress.getByName("127.0.0.1"), { code ->
            assertEquals("123456", code)
            submissions.incrementAndGet()
            true
        }).use { server ->
            val uri = URI(server.url)
            val host = "${uri.host}:${uri.port}"
            assertTrue(request(uri, "GET /${uri.path.trimStart('/')} HTTP/1.1\r\nHost: $host\r\n\r\n")
                .startsWith("HTTP/1.1 200"))
            assertTrue(request(uri, "GET /wrong HTTP/1.1\r\nHost: $host\r\n\r\n")
                .startsWith("HTTP/1.1 404"))
            val body = "code=123456"
            val path = "${uri.path}/pair"
            val base = "POST $path HTTP/1.1\r\nHost: $host\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: ${body.length}\r\n"
            assertTrue(request(uri, base + "Origin: http://attacker.invalid\r\n\r\n" + body)
                .startsWith("HTTP/1.1 403"))
            assertTrue(request(uri, base + "\r\n" + body)
                .startsWith("HTTP/1.1 200"))
            assertTrue(request(uri, base + "Origin: null\r\n\r\n" + body)
                .startsWith("HTTP/1.1 200"))
            assertTrue(request(uri, base + "Origin: http://$host\r\n\r\n" + body)
                .startsWith("HTTP/1.1 200"))
            assertEquals(3, submissions.get())
        }
    }

    @Test fun rejectsMalformedCodeAndExpiredSession() {
        var time = 100L
        var called = false
        PairingWebServer(InetAddress.getByName("127.0.0.1"), { called = true; true },
            now = { time }, lifetimeMillis = 1_000).use { server ->
            val uri = URI(server.url)
            val host = "${uri.host}:${uri.port}"
            val body = "code=12345x"
            val payload = "POST ${uri.path}/pair HTTP/1.1\r\nHost: $host\r\n" +
                "Origin: http://$host\r\nContent-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: ${body.length}\r\n\r\n$body"
            assertTrue(request(uri, payload).startsWith("HTTP/1.1 400"))
            assertFalse(called)
            time = 1_100L
            assertTrue(request(uri, "GET ${uri.path} HTTP/1.1\r\nHost: $host\r\n\r\n")
                .startsWith("HTTP/1.1 404"))
        }
    }

    private fun request(uri: URI, payload: String): String =
        Socket(uri.host, uri.port).use { socket ->
            socket.soTimeout = 3_000
            socket.getOutputStream().write(payload.toByteArray(Charsets.US_ASCII))
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
}
