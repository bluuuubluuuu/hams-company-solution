package com.klk.hams.provisioning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.nio.charset.StandardCharsets

class ProvisioningClientTest {
    @Test fun json_escape_handles_quotes_backslashes_and_controls() {
        assertEquals(
            "fp\\\"A\\\\B\\n\\r\\t",
            ProvisioningClient.escapeJsonString("fp\"A\\B\n\r\t")
        )
    }

    @Test fun manualClaim_parses_200_404_409_401() {
        assertEquals(
            BindResult.Success("HAMS_TEST_003"),
            ProvisioningClient.parseManualClaimResponse(200, """{"unique_id":"HAMS_TEST_003"}"""),
        )
        assertEquals(BindResult.NotFound, ProvisioningClient.parseManualClaimResponse(404, """{"error":"not_found"}"""))
        assertEquals(BindResult.FingerprintInUse(null), ProvisioningClient.parseManualClaimResponse(409, """{"error":"fingerprint_in_use"}"""))
        assertEquals(BindResult.FingerprintInUse("HAMS_TEST_001"), ProvisioningClient.parseManualClaimResponse(409, """{"error":"fingerprint_in_use","on":"HAMS_TEST_001"}"""))
        assertEquals(BindResult.AlreadyBound, ProvisioningClient.parseManualClaimResponse(409, """{"error":"already_bound"}"""))
        assertEquals(BindResult.AdminAuthFailed, ProvisioningClient.parseManualClaimResponse(401, """{"error":"admin_auth_failed"}"""))
        assertEquals(BindResult.Unauthorized, ProvisioningClient.parseManualClaimResponse(401, null))
        assertEquals(BindResult.AdminNotConfigured, ProvisioningClient.parseManualClaimResponse(503, """{"error":"admin_not_configured"}"""))
        assertTrue(ProvisioningClient.parseManualClaimResponse(500, null) is BindResult.Error)
    }

    @Test fun release_parses_200_409_401() {
        assertEquals(ReleaseResult.Success, ProvisioningClient.parseReleaseResponse(200, """{"ok":true}"""))
        assertEquals(ReleaseResult.NotFound, ProvisioningClient.parseReleaseResponse(409, """{"error":"not_owner_or_not_found"}"""))
        assertEquals(ReleaseResult.AdminAuthFailed, ProvisioningClient.parseReleaseResponse(401, """{"error":"admin_auth_failed"}"""))
        assertEquals(ReleaseResult.Unauthorized, ProvisioningClient.parseReleaseResponse(401, null))
        assertEquals(ReleaseResult.AdminNotConfigured, ProvisioningClient.parseReleaseResponse(503, """{"error":"admin_not_configured"}"""))
        assertTrue(ProvisioningClient.parseReleaseResponse(500, null) is ReleaseResult.Error)
    }

    @Test fun manualClaim_posts_admin_passkey_header() {
        val server = ServerSocket(0)
        var seenKey: String? = null
        var seenAdminCode: String? = null
        var seenBody: String? = null
        val thread = Thread {
            server.accept().use { socket ->
                val request = readHttpRequest(socket.getInputStream())
                seenKey = request.headers["x-hams-key"]
                seenAdminCode = request.headers["x-hams-admin-passkey"]
                seenBody = request.body
                writeHttpResponse(socket.getOutputStream(), 200, """{"unique_id":"HAMS_TEST_003"}""")
            }
        }.apply { isDaemon = true; start() }
        val url = "http://127.0.0.1:${server.localPort}/manual-claim"
        try {
            val client = ProvisioningClient(manualClaimUrl = url, secret = "s3cret")
            val r = kotlinx.coroutines.runBlocking {
                client.manualClaim("HAMS_TEST_003", "fpABC", "246810")
            }
            assertEquals(BindResult.Success("HAMS_TEST_003"), r)
            assertEquals("s3cret", seenKey)
            assertEquals("246810", seenAdminCode)
            assertEquals("""{"unique_id":"HAMS_TEST_003","fingerprint":"fpABC"}""", seenBody)
        } finally {
            thread.join(2_000)
            server.close()
        }
    }

    private data class HttpRequest(val headers: Map<String, String>, val body: String)

    private fun readHttpRequest(input: java.io.InputStream): HttpRequest {
        val reader = BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8))
        reader.readLine()
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = reader.readLine()
            if (line.isNullOrEmpty()) break
            val split = line.indexOf(':')
            if (split > 0) {
                headers[line.substring(0, split).lowercase()] = line.substring(split + 1).trim()
            }
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val chars = CharArray(length)
        var read = 0
        while (read < length) {
            val n = reader.read(chars, read, length - read)
            if (n < 0) break
            read += n
        }
        return HttpRequest(headers = headers, body = String(chars, 0, read))
    }

    private fun writeHttpResponse(
        output: java.io.OutputStream,
        status: Int,
        body: String
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        output.write(
            "HTTP/1.1 $status OK\r\nContent-Length: ${bytes.size}\r\n\r\n"
                .toByteArray(StandardCharsets.US_ASCII)
        )
        output.write(bytes)
        output.flush()
    }
}
