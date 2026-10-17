package com.resqgo.app

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

/** A local stand-in for Google Apps Script: POST to /exec answers 302, the redirect target holds the reply. */
class UploaderTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private var received = ""
    private var reply = """{"ok":true}"""
    private val url get() = "http://127.0.0.1:${server.address.port}/exec"

    init {
        server.createContext("/exec") { ex ->
            received = ex.requestBody.readBytes().decodeToString()
            ex.responseHeaders.add("Location", "/echo")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        server.createContext("/echo") { ex ->
            val body = reply.toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @After fun stop() = server.stop(0)

    @Test fun uploadFollowsTheAppsScriptRedirect() {
        val csv = "t_ms,ax\n0,9.81\n20,\"quoted\"\n"
        assertTrue(postUpload(url, "k3y", "R01", "R01-x-bump.csv", csv))
        assertTrue(received, received.contains(""""name":"R01-x-bump.csv""""))
        assertTrue(received, received.contains(""""content":"t_ms,ax\n0,9.81\n20,\"quoted\"\n""""))
    }

    @Test fun aLoginPageOrErrorIsNotSuccess() {
        reply = "<html>Sign in</html>"
        assertFalse(postUpload(url, "k", "R01", "a.csv", "x"))
        reply = """{"ok":false,"error":"wrong key"}"""
        assertFalse(postUpload(url, "k", "R01", "a.csv", "x"))
    }

    @Test fun offlineIsNotSuccess() {
        server.stop(0)
        assertFalse(postUpload(url, "k", "R01", "a.csv", "x"))
    }

    @Test fun jsonStringEscapes() {
        assertEquals("\"a\\\"b\\\\c\\nd\\u0001\"", jsonString("a\"b\\c\nd\u0001"))
    }

    @Test fun testAlertsSayTestAndDontAskFor112() {
        val text = alertText("Ravi", false, "9:42 PM", null, null, test = true)
        assertTrue(text, text.startsWith("RESQGO TEST ALERT"))
        assertFalse(text, "112" in text)
    }
}
