package com.klk.hams.provisioning

import com.klk.hams.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class ProvisioningClient(
    private val secret: String = AppConfig.HAMS_CLAIM_SECRET,
    private val manualClaimUrl: String = AppConfig.MANUAL_CLAIM_URL,
    private val releaseUrl: String = AppConfig.RELEASE_URL,
    private val verifyUrl: String = AppConfig.VERIFY_URL,
    private val appVersion: String = AppConfig.APP_VERSION,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 10_000,
    private val opener: (String) -> HttpURLConnection = {
        URL(it).openConnection() as HttpURLConnection
    },
) {
    /** POST { unique_id, fingerprint } to /manual-claim with the shared-secret header. */
    suspend fun manualClaim(uniqueId: String, fingerprint: String, adminCode: String): BindResult = withContext(Dispatchers.IO) {
        if (uniqueId.isBlank() || fingerprint.isBlank()) return@withContext BindResult.Error("blank input")
        val body = """{"unique_id":"${escapeJsonString(uniqueId)}","fingerprint":"${escapeJsonString(fingerprint)}"}"""
        val (code, resp) = post(manualClaimUrl, body, adminCode)
        parseManualClaimResponse(code, resp)
    }

    /** POST { unique_id, fingerprint } to /release with the shared-secret header. */
    suspend fun release(uniqueId: String, fingerprint: String, adminCode: String): ReleaseResult = withContext(Dispatchers.IO) {
        if (uniqueId.isBlank() || fingerprint.isBlank()) return@withContext ReleaseResult.Error("blank input")
        val body = """{"unique_id":"${escapeJsonString(uniqueId)}","fingerprint":"${escapeJsonString(fingerprint)}"}"""
        val (code, resp) = post(releaseUrl, body, adminCode)
        parseReleaseResponse(code, resp)
    }

    /** POST { unique_id, fingerprint, app_version } to /verify with the shared-secret header.
     *  No admin passkey - this is an automatic device-initiated re-check.
     *  app_version rides along on a call that already happens (launch, pre-push,
     *  every ~15 min) so the office can see which build each handset runs. The
     *  registry only records it on a 'bound' answer, so a device that does not
     *  own the unit cannot write to that field. */
    suspend fun verify(uniqueId: String, fingerprint: String): VerifyResult = withContext(Dispatchers.IO) {
        if (uniqueId.isBlank() || fingerprint.isBlank()) return@withContext VerifyResult.Keep("blank input")
        val body = """{"unique_id":"${escapeJsonString(uniqueId)}","fingerprint":"${escapeJsonString(fingerprint)}","app_version":"${escapeJsonString(appVersion)}"}"""
        val (code, resp) = post(verifyUrl, body, adminCode = "")
        parseVerifyResponse(code, resp)
    }

    private fun post(url: String, body: String, adminCode: String): Pair<Int, String?> {
        val conn = opener(url)
        return try {
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            conn.requestMethod = "POST"
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-hams-key", secret)
            conn.setRequestProperty("x-hams-admin-passkey", adminCode)
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            code to stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }
        } catch (e: Exception) {
            -1 to (e.message ?: e::class.java.simpleName)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /** Pull a string field's value out of a flat JSON body. Shared by every
         *  parser so the extraction lives in one place (no per-field regex, no
         *  JSON dependency — keeps the parsers pure-JVM-testable). */
        fun jsonStringField(body: String?, name: String): String? =
            body?.let {
                Regex("\"" + Regex.escape(name) + "\"\\s*:\\s*\"([^\"]+)\"")
                    .find(it)?.groupValues?.get(1)
            }

        fun escapeJsonString(value: String): String = buildString {
            value.forEach { ch ->
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(ch)
                }
            }
        }

        /** Pure: map (HTTP code, body) to a [BindResult] for /manual-claim.
         *  409 is disambiguated by the body's `error` field — the endpoint uses
         *  it for both `fingerprint_in_use` (this device owns another unit) and
         *  `already_bound` (target unit owned by another device). */
        fun parseManualClaimResponse(code: Int, body: String?): BindResult = when (code) {
            200 -> jsonStringField(body, "unique_id")
                ?.let { BindResult.Success(it) }
                ?: BindResult.Error("malformed 200: $body")
            401 -> if (jsonStringField(body, "error") == "admin_auth_failed")
                BindResult.AdminAuthFailed else BindResult.Unauthorized
            404 -> BindResult.NotFound
            409 -> when (jsonStringField(body, "error")) {
                "already_bound" -> BindResult.AlreadyBound
                "draining" -> BindResult.Draining
                else -> BindResult.FingerprintInUse(jsonStringField(body, "on"))
            }
            503 -> if (jsonStringField(body, "error") == "admin_not_configured")
                BindResult.AdminNotConfigured else BindResult.Error("HTTP $code")
            else -> BindResult.Error("HTTP $code")
        }

        /** Pure: map (HTTP code, body) to a [ReleaseResult] for /release. */
        fun parseReleaseResponse(code: Int, body: String?): ReleaseResult = when (code) {
            200 -> ReleaseResult.Success
            401 -> if (jsonStringField(body, "error") == "admin_auth_failed")
                ReleaseResult.AdminAuthFailed else ReleaseResult.Unauthorized
            404, 409 -> ReleaseResult.NotFound  // 409 = not owner / not found (fingerprint-scoped)
            503 -> if (jsonStringField(body, "error") == "admin_not_configured")
                ReleaseResult.AdminNotConfigured else ReleaseResult.Error("HTTP $code")
            else -> ReleaseResult.Error("HTTP $code")
        }

        /** Pure: map (HTTP code, body) to a [VerifyResult]. Only explicit
         *  release/taken statuses trigger action; every other answer is Keep. */
        fun parseVerifyResponse(code: Int, body: String?): VerifyResult = when (code) {
            200 -> when (jsonStringField(body, "status")) {
                "bound" -> VerifyResult.Bound
                "released" -> VerifyResult.Released
                "bound_other" -> VerifyResult.BoundOther
                else -> VerifyResult.Keep(jsonStringField(body, "status") ?: "unknown")
            }
            else -> VerifyResult.Keep("http_$code")
        }
    }
}
