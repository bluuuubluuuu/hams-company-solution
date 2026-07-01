package com.klk.hams.push

import com.klk.hams.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets

/**
 * Single Wialon IPS v1.1 TCP session: connect, login, send pre-built data frames, close.
 *
 * Scope (Phase 2 Task 2.6):
 * - Owns the socket lifecycle for one session.
 * - Sends the login frame produced by [IPSFrameBuilder.loginFrame].
 * - Sends already-built `#D#` frames; does not build them.
 * - Parses `#AL#` / `#AD#` responses into typed [WialonError] failures.
 *
 * Out of scope here:
 * - No Room access, no `pushed` flag updates.
 * - No batching, retry, or Wi-Fi monitoring (Tasks 2.7 / 2.8).
 * - No live runtime use yet — nothing wires this into the app process.
 */
class WialonIPSClient(
    private val host: String = AppConfig.IPS_HOST,
    private val port: Int = AppConfig.IPS_PORT,
    private val uniqueId: String,
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS,
    private val socketFactory: () -> Socket = ::Socket,
) : IpsSender {

    private var socket: Socket? = null
    private var output: OutputStream? = null
    private var reader: BufferedReader? = null

    /**
     * Connect to [host]:[port] and send the IPS login frame.
     * Returns success on `#AL#1`. On any failure the socket is closed.
     */
    override suspend fun openAndLogin(): Result<Unit> = withContext(Dispatchers.IO) {
        closeQuietly()
        val s = try {
            socketFactory().also {
                it.connect(InetSocketAddress(host, port), connectTimeoutMs)
                it.soTimeout = readTimeoutMs
            }
        } catch (e: SocketTimeoutException) {
            return@withContext Result.failure(WialonException(WialonError.Timeout))
        } catch (e: IOException) {
            return@withContext Result.failure(WialonException(WialonError.Transport(e)))
        }
        socket = s
        try {
            output = s.getOutputStream()
            reader = BufferedReader(
                InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII),
            )
            writeAscii(IPSFrameBuilder.loginFrame(uniqueId))
            val response = readLineOrNull()
                ?: return@withContext failAndClose(WialonError.Timeout)
            val mapped = mapLoginResponse(response)
            if (mapped.isFailure) closeQuietly()
            mapped
        } catch (e: SocketTimeoutException) {
            failAndClose(WialonError.Timeout)
        } catch (e: IOException) {
            failAndClose(WialonError.Transport(e))
        }
    }

    /**
     * Send a single pre-built `#D#` frame on the active session and read its `#AD#` ack.
     * Returns failure with [WialonError.NotConnected] if [openAndLogin] has not succeeded.
     */
    override suspend fun sendDataFrame(frame: String): Result<Unit> = withContext(Dispatchers.IO) {
        val out = output ?: return@withContext Result.failure(
            WialonException(WialonError.NotConnected),
        )
        try {
            out.write(frame.toByteArray(StandardCharsets.US_ASCII))
            out.flush()
            val response = readLineOrNull()
                ?: return@withContext failAndClose(WialonError.Timeout)
            // Data ack received: keep the session open even on rejection — the
            // gateway is alive and may still accept the next frame in the batch.
            mapDataResponse(response)
        } catch (e: SocketTimeoutException) {
            failAndClose(WialonError.Timeout)
        } catch (e: IOException) {
            failAndClose(WialonError.Transport(e))
        }
    }

    /** Close the socket and clear streams. Safe to call repeatedly. */
    override fun close() {
        closeQuietly()
    }

    private fun writeAscii(text: String) {
        val out = output ?: throw IOException("output stream not open")
        out.write(text.toByteArray(StandardCharsets.US_ASCII))
        out.flush()
    }

    private fun readLineOrNull(): String? = try {
        reader?.readLine()
    } catch (_: SocketTimeoutException) {
        null
    }

    private fun failAndClose(error: WialonError): Result<Unit> {
        closeQuietly()
        return Result.failure(WialonException(error))
    }

    private fun closeQuietly() {
        runCatching { reader?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        reader = null
        output = null
        socket = null
    }

    companion object {
        const val CONNECT_TIMEOUT_MS: Int = 5_000
        const val READ_TIMEOUT_MS: Int = 3_000

        /**
         * Map a raw IPS login response line (with or without trailing CRLF) to a [Result].
         * Pure function; safe to unit-test without a socket.
         */
        fun mapLoginResponse(raw: String): Result<Unit> {
            return when (val r = stripCrLf(raw)) {
                "#AL#1" -> Result.success(Unit)
                "#AL#0" -> Result.failure(WialonException(WialonError.LoginRejected))
                "#AL#01" -> Result.failure(WialonException(WialonError.LoginPasswordError))
                else -> Result.failure(WialonException(WialonError.Unexpected(r)))
            }
        }

        /**
         * Map a raw IPS data response line (with or without trailing CRLF) to a [Result].
         * `#AD#1`                                       → success
         * `#AD#15`                                      → [WialonError.ParamsRejected]
         * `#AD#-1`, `#AD#0`, `#AD#10..14` (documented)  → [WialonError.FrameRejected]
         * any other `#AD#…` value                       → [WialonError.Unexpected]
         * non-`#AD#` line                               → [WialonError.Unexpected]
         */
        fun mapDataResponse(raw: String): Result<Unit> {
            val r = stripCrLf(raw)
            return when (r) {
                "#AD#1" -> Result.success(Unit)
                "#AD#15" -> Result.failure(WialonException(WialonError.ParamsRejected))
                in FRAME_REJECTED_ACKS ->
                    Result.failure(WialonException(WialonError.FrameRejected))
                else -> Result.failure(WialonException(WialonError.Unexpected(r)))
            }
        }

        /** Documented IPS v1.1 data ack values that mean "structural error, mark rejected". */
        private val FRAME_REJECTED_ACKS: Set<String> = setOf(
            "#AD#-1", // packet structure error
            "#AD#0",  // invalid time field
            "#AD#10", // coordinate error
            "#AD#11", // speed/course/altitude error
            "#AD#12", // satellites/HDOP error
            "#AD#13", // inputs/outputs error
            "#AD#14", // ADC error
        )

        private fun stripCrLf(raw: String): String = raw.trimEnd('\r', '\n')
    }
}

/** Typed transport / protocol failure modes for [WialonIPSClient]. */
sealed class WialonError {
    /** Underlying socket / IO failure (connection refused, reset, etc.). */
    data class Transport(val cause: Throwable) : WialonError()

    /** Connect or read timeout exceeded. */
    data object Timeout : WialonError()

    /** `#AL#0` — gateway rejected the login (unit unknown / wrong type). */
    data object LoginRejected : WialonError()

    /** `#AL#01` — password mismatch (we send `NA`, so this is unexpected in HAMS). */
    data object LoginPasswordError : WialonError()

    /** Structural data ack: `#AD#-1`, `#AD#0`, `#AD#10..14`. Per-event fatal; mark rejected. */
    data object FrameRejected : WialonError()

    /** `#AD#15` — params block malformed. Per-event fatal; mark rejected. */
    data object ParamsRejected : WialonError()

    /** Caller invoked `sendDataFrame` before a successful `openAndLogin`. */
    data object NotConnected : WialonError()

    /** Response did not match any known IPS v1.1 ack pattern. */
    data class Unexpected(val response: String) : WialonError()
}

/** Throwable wrapper so [WialonError] flows through Kotlin's `Result` API. */
class WialonException(val error: WialonError) : Exception(error.toString())
