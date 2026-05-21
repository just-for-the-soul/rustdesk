package com.carriez.flutter_hbb

/**
 * SpeedTestExecutor — measures download/upload throughput and latency.
 *
 * Used by WarmerCommandExecutor when the bridge dispatches a
 *   { "type": "network_speed" }
 * command. Returns JSON:
 *   {
 *     download_mbps:  Double,
 *     upload_mbps:    Double,
 *     latency_ms:     Double,    // TCP-syn median to 1.1.1.1:443
 *     jitter_ms:      Double,    // stdev of the 5 latency samples
 *     test_server:    String,    // e.g. "Cloudflare LHR"
 *     duration_ms:    Int,       // total wall-clock
 *     error:          String?    // null on success
 *   }
 *
 * No external deps — uses HttpURLConnection + Socket. Blocks the calling
 * thread for ~10-20s; must NOT be invoked on the UI thread (the existing
 * bridge command thread is fine).
 *
 * Endpoints used:
 *   Download: https://speed.cloudflare.com/__down?bytes=10000000  (10 MB)
 *   Upload:   https://speed.cloudflare.com/__up                   (5 MB POST)
 *   Latency:  TCP connect to 1.1.1.1:443 x5
 */

import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import kotlin.math.sqrt

object SpeedTestExecutor {

    private const val DOWNLOAD_URL    = "https://speed.cloudflare.com/__down?bytes=10000000"
    private const val UPLOAD_URL      = "https://speed.cloudflare.com/__up"
    private const val UPLOAD_BYTES    = 5_000_000   // 5 MB
    private const val PING_HOST       = "1.1.1.1"
    private const val PING_PORT       = 443
    private const val PING_SAMPLES    = 5
    private const val PING_TIMEOUT_MS = 2_000
    private const val HTTP_TIMEOUT_MS = 30_000

    fun measure(): JSONObject {
        val out      = JSONObject()
        val started  = System.currentTimeMillis()
        var server   = "Cloudflare"

        try {
            // ── DOWNLOAD ────────────────────────────────────────────────
            val (dlBytes, dlMs, pop) = runCatching { downloadTest() }
                .getOrElse { return abort(out, started, "download failed: ${it.message}") }
            if (pop.isNotEmpty()) server = "Cloudflare $pop"
            val dlMbps = bytesToMbps(dlBytes, dlMs)

            // ── UPLOAD ──────────────────────────────────────────────────
            val (ulBytes, ulMs) = runCatching { uploadTest() }
                .getOrElse { return abort(out, started, "upload failed: ${it.message}") }
            val ulMbps = bytesToMbps(ulBytes, ulMs)

            // ── LATENCY + JITTER ────────────────────────────────────────
            val samples = (1..PING_SAMPLES).map {
                runCatching { tcpPing(PING_HOST, PING_PORT, PING_TIMEOUT_MS) }
                    .getOrDefault(-1.0)
            }.filter { it > 0 }
            if (samples.isEmpty()) {
                return abort(out, started, "all ping attempts failed")
            }
            val latencyMedian = median(samples)
            val jitter        = stdev(samples)

            // ── BUILD RESULT ────────────────────────────────────────────
            out.put("download_mbps", round2(dlMbps))
            out.put("upload_mbps",   round2(ulMbps))
            out.put("latency_ms",    round2(latencyMedian))
            out.put("jitter_ms",     round2(jitter))
            out.put("test_server",   server)
            out.put("duration_ms",   (System.currentTimeMillis() - started).toInt())
            out.put("error",         JSONObject.NULL)
            return out

        } catch (t: Throwable) {
            return abort(out, started, "unexpected: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // ── primitives ──────────────────────────────────────────────────────

    /** Returns Triple(bytesRead, elapsedMs, cfPopHeader). */
    private fun downloadTest(): Triple<Long, Long, String> {
        val conn = (URL(DOWNLOAD_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = HTTP_TIMEOUT_MS
            readTimeout    = HTTP_TIMEOUT_MS
            requestMethod  = "GET"
            setRequestProperty("User-Agent", "DroidDesk-SpeedTest/1.0")
            setRequestProperty("Accept-Encoding", "identity") // disable gzip — skews bytes
        }
        try {
            val t0  = System.nanoTime()
            val ins = conn.inputStream
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                total += n
            }
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000
            val pop = conn.getHeaderField("cf-meta-pop") ?: ""
            return Triple(total, elapsedMs, pop)
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    /** Returns Pair(bytesSent, elapsedMs). */
    private fun uploadTest(): Pair<Long, Long> {
        val conn = (URL(UPLOAD_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = HTTP_TIMEOUT_MS
            readTimeout    = HTTP_TIMEOUT_MS
            requestMethod  = "POST"
            doOutput       = true
            setFixedLengthStreamingMode(UPLOAD_BYTES.toLong())
            setRequestProperty("User-Agent",   "DroidDesk-SpeedTest/1.0")
            setRequestProperty("Content-Type", "application/octet-stream")
        }
        try {
            val t0  = System.nanoTime()
            val out: OutputStream = conn.outputStream
            val buf = ByteArray(64 * 1024)
            // Random-ish payload (just zero bytes — Cloudflare echo doesn't care).
            var remaining = UPLOAD_BYTES
            while (remaining > 0) {
                val n = minOf(buf.size, remaining)
                out.write(buf, 0, n)
                remaining -= n
            }
            out.flush()
            out.close()
            // Force connection completion by reading response code
            val rc = conn.responseCode
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000
            if (rc !in 200..299) throw IOException("upload http $rc")
            return Pair(UPLOAD_BYTES.toLong(), elapsedMs)
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    /** TCP-syn connect time as a stand-in for ICMP ping (which needs root). */
    private fun tcpPing(host: String, port: Int, timeoutMs: Int): Double {
        val sock = Socket()
        return try {
            val t0 = System.nanoTime()
            sock.connect(InetSocketAddress(host, port), timeoutMs)
            (System.nanoTime() - t0) / 1_000_000.0
        } finally {
            try { sock.close() } catch (_: Exception) {}
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun bytesToMbps(bytes: Long, ms: Long): Double =
        if (ms <= 0) 0.0 else (bytes.toDouble() * 8.0 / 1_000_000.0) / (ms.toDouble() / 1000.0)

    private fun median(xs: List<Double>): Double {
        val s = xs.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    private fun stdev(xs: List<Double>): Double {
        if (xs.size < 2) return 0.0
        val mean = xs.average()
        val variance = xs.sumOf { (it - mean) * (it - mean) } / xs.size
        return sqrt(variance)
    }

    private fun round2(x: Double): Double = Math.round(x * 100.0) / 100.0

    private fun abort(out: JSONObject, started: Long, msg: String): JSONObject {
        out.put("download_mbps", 0.0)
        out.put("upload_mbps",   0.0)
        out.put("latency_ms",    0.0)
        out.put("jitter_ms",     0.0)
        out.put("test_server",   "")
        out.put("duration_ms",   (System.currentTimeMillis() - started).toInt())
        out.put("error",         msg)
        return out
    }
}
