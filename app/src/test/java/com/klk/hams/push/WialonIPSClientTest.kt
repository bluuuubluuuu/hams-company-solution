package com.klk.hams.push

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.nio.charset.StandardCharsets

/**
 * JVM-only tests for [WialonIPSClient]. Focused on the pure response mappers
 * and result-to-error contracts, which are the parts that don't need a real
 * socket. Live transport behaviour is intentionally not tested here — it
 * belongs in the Phase 2 / Phase 4 integration tests once `PushEngine` and
 * a fake gateway exist.
 */
class WialonIPSClientTest {

    // ---- login response mapping ----

    @Test fun loginAcceptedReturnsSuccess() {
        val r = WialonIPSClient.mapLoginResponse("#AL#1\r\n")
        assertTrue(r.isSuccess)
    }

    @Test fun loginAcceptedTolerantOfMissingCrLf() {
        val r = WialonIPSClient.mapLoginResponse("#AL#1")
        assertTrue(r.isSuccess)
    }

    @Test fun loginRejectedMapsToLoginRejected() {
        val err = WialonIPSClient.mapLoginResponse("#AL#0\r\n").asWialonError()
        assertEquals(WialonError.LoginRejected, err)
    }

    @Test fun loginPasswordErrorMapsToPasswordError() {
        val err = WialonIPSClient.mapLoginResponse("#AL#01\r\n").asWialonError()
        assertEquals(WialonError.LoginPasswordError, err)
    }

    @Test fun unknownLoginAckMapsToUnexpected() {
        val err = WialonIPSClient.mapLoginResponse("#AL#42\r\n").asWialonError()
        assertTrue(err is WialonError.Unexpected)
        assertEquals("#AL#42", (err as WialonError.Unexpected).response)
    }

    @Test fun emptyLoginResponseMapsToUnexpected() {
        val err = WialonIPSClient.mapLoginResponse("").asWialonError()
        assertTrue(err is WialonError.Unexpected)
    }

    // ---- data response mapping ----

    @Test fun dataAcceptedReturnsSuccess() {
        val r = WialonIPSClient.mapDataResponse("#AD#1\r\n")
        assertTrue(r.isSuccess)
    }

    @Test fun dataAcceptedTolerantOfMissingCrLf() {
        val r = WialonIPSClient.mapDataResponse("#AD#1")
        assertTrue(r.isSuccess)
    }

    @Test fun packetStructureErrorMapsToFrameRejected() {
        // #AD#-1 — packet structure error
        val err = WialonIPSClient.mapDataResponse("#AD#-1\r\n").asWialonError()
        assertEquals(WialonError.FrameRejected, err)
    }

    @Test fun timeFieldErrorMapsToFrameRejected() {
        val err = WialonIPSClient.mapDataResponse("#AD#0\r\n").asWialonError()
        assertEquals(WialonError.FrameRejected, err)
    }

    @Test fun coordinateErrorMapsToFrameRejected() {
        val err = WialonIPSClient.mapDataResponse("#AD#10\r\n").asWialonError()
        assertEquals(WialonError.FrameRejected, err)
    }

    @Test fun speedCourseAltErrorMapsToFrameRejected() {
        val err = WialonIPSClient.mapDataResponse("#AD#11\r\n").asWialonError()
        assertEquals(WialonError.FrameRejected, err)
    }

    @Test fun satellitesHdopErrorMapsToFrameRejected() {
        val err = WialonIPSClient.mapDataResponse("#AD#12\r\n").asWialonError()
        assertEquals(WialonError.FrameRejected, err)
    }

    @Test fun inputsOutputsErrorMapsToFrameRejected() {
        val err = WialonIPSClient.mapDataResponse("#AD#13\r\n").asWialonError()
        assertEquals(WialonError.FrameRejected, err)
    }

    @Test fun adcErrorMapsToFrameRejected() {
        val err = WialonIPSClient.mapDataResponse("#AD#14\r\n").asWialonError()
        assertEquals(WialonError.FrameRejected, err)
    }

    @Test fun paramsErrorMapsToParamsRejected() {
        // #AD#15 — params block malformed; called out separately for diagnostics.
        val err = WialonIPSClient.mapDataResponse("#AD#15\r\n").asWialonError()
        assertEquals(WialonError.ParamsRejected, err)
    }

    @Test fun unknownAd2MapsToUnexpected() {
        // #AD#2 is not a documented ack; must NOT be lumped into FrameRejected.
        val err = WialonIPSClient.mapDataResponse("#AD#2\r\n").asWialonError()
        assertTrue(err is WialonError.Unexpected)
        assertEquals("#AD#2", (err as WialonError.Unexpected).response)
    }

    @Test fun unknownAd99MapsToUnexpected() {
        val err = WialonIPSClient.mapDataResponse("#AD#99\r\n").asWialonError()
        assertTrue(err is WialonError.Unexpected)
        assertEquals("#AD#99", (err as WialonError.Unexpected).response)
    }

    @Test fun nonAdResponseMapsToUnexpected() {
        val err = WialonIPSClient.mapDataResponse("#AL#1\r\n").asWialonError()
        assertTrue(err is WialonError.Unexpected)
        assertEquals("#AL#1", (err as WialonError.Unexpected).response)
    }

    @Test fun garbageResponseMapsToUnexpected() {
        val err = WialonIPSClient.mapDataResponse("hello\r\n").asWialonError()
        assertTrue(err is WialonError.Unexpected)
    }

    // ---- runtime invariants ----

    @Test fun sendDataFrameBeforeLoginFailsWithNotConnected() {
        val client = WialonIPSClient(host = "127.0.0.1", port = 1, uniqueId = "TEST")
        val err = kotlinx.coroutines.runBlocking {
            client.sendDataFrame("#D#anything\r\n").asWialonError()
        }
        assertEquals(WialonError.NotConnected, err)
    }

    @Test fun closeIsIdempotent() {
        val client = WialonIPSClient(host = "127.0.0.1", port = 1, uniqueId = "TEST")
        client.close()
        client.close() // must not throw
    }

    @Test fun loginExceptionWrapsWialonError() {
        // Sanity: unwrapping helper relies on WialonException carrying the typed error.
        val r = WialonIPSClient.mapLoginResponse("#AL#0")
        val cause = r.exceptionOrNull()
        assertNotNull(cause)
        assertTrue(cause is WialonException)
        assertEquals(WialonError.LoginRejected, (cause as WialonException).error)
    }

    // ---- session-close-on-failure (loopback, no real Wialon traffic) ----

    /**
     * Boots a tiny in-process IPS-shaped server on a loopback port. The server
     * answers the login frame with `#AL#1`, reads exactly one data frame, then
     * stays silent so the client's read times out. After the timeout the
     * client should have closed its socket — verified by the next
     * `sendDataFrame` failing with `NotConnected` instead of another `Timeout`.
     */
    @Test fun sendDataFrameTimeoutClosesSession() {
        val server = ServerSocket(0)
        server.soTimeout = 5_000
        val acceptThread = Thread {
            server.accept().use { sock ->
                val reader = BufferedReader(
                    InputStreamReader(sock.getInputStream(), StandardCharsets.US_ASCII),
                )
                val out = sock.getOutputStream()
                reader.readLine() // login frame
                out.write("#AL#1\r\n".toByteArray(StandardCharsets.US_ASCII))
                out.flush()
                reader.readLine() // data frame, then deliberately do not respond
                Thread.sleep(800)
            }
        }.apply { isDaemon = true; start() }

        val client = WialonIPSClient(
            host = "127.0.0.1",
            port = server.localPort,
            uniqueId = "TEST",
            connectTimeoutMs = 1_000,
            readTimeoutMs = 200,
        )
        try {
            runBlocking {
                assertTrue(client.openAndLogin().isSuccess)
                val first = client.sendDataFrame("#D#dummy\r\n").asWialonError()
                assertEquals(WialonError.Timeout, first)
                // Timeout path must close the socket: a second send returns
                // NotConnected, not Timeout.
                val second = client.sendDataFrame("#D#dummy\r\n").asWialonError()
                assertEquals(WialonError.NotConnected, second)
            }
        } finally {
            client.close()
            acceptThread.join(2_000)
            server.close()
        }
    }

    /**
     * Same shape, but the server hangs up the socket immediately after `#AL#1`.
     * The client's next write/read should surface as Transport (or, on some
     * platforms, Timeout from a half-closed read). Either way the session must
     * be closed afterwards — verified via `NotConnected` on the follow-up send.
     */
    @Test fun sendDataFrameTransportFailureClosesSession() {
        val server = ServerSocket(0)
        server.soTimeout = 5_000
        val acceptThread = Thread {
            server.accept().use { sock ->
                val reader = BufferedReader(
                    InputStreamReader(sock.getInputStream(), StandardCharsets.US_ASCII),
                )
                val out = sock.getOutputStream()
                reader.readLine() // login frame
                out.write("#AL#1\r\n".toByteArray(StandardCharsets.US_ASCII))
                out.flush()
                // Drop the connection abruptly.
            }
        }.apply { isDaemon = true; start() }

        val client = WialonIPSClient(
            host = "127.0.0.1",
            port = server.localPort,
            uniqueId = "TEST",
            connectTimeoutMs = 1_000,
            readTimeoutMs = 300,
        )
        try {
            runBlocking {
                assertTrue(client.openAndLogin().isSuccess)
                // Give the OS a moment to register the peer close.
                Thread.sleep(100)
                val first = client.sendDataFrame("#D#dummy\r\n").asWialonError()
                assertTrue(
                    "expected Transport or Timeout, got $first",
                    first is WialonError.Transport || first == WialonError.Timeout,
                )
                val second = client.sendDataFrame("#D#dummy\r\n").asWialonError()
                assertEquals(WialonError.NotConnected, second)
            }
        } finally {
            client.close()
            acceptThread.join(2_000)
            server.close()
        }
    }

    private fun Result<Unit>.asWialonError(): WialonError {
        val ex = exceptionOrNull()
        require(ex is WialonException) { "expected WialonException, got $ex" }
        return ex.error
    }
}
