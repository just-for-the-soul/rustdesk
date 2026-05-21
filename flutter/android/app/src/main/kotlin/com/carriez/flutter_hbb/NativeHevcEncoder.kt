package com.carriez.flutter_hbb

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import ffi.FFI
import java.nio.ByteBuffer

/**
 * NativeHevcEncoder — direct VirtualDisplay → MediaCodec H.265 path.
 *
 * The vanilla RustDesk Android pipeline goes:
 *   VirtualDisplay → ImageReader(RGBA) → JNI copy → Rust libyuv (RGBA→I420)
 *   → FFmpeg hevc_mediacodec → MediaCodec
 *
 * That uses an extra ~5-15 ms/frame and one full CPU core on conversion +
 * marshalling, with no benefit on devices that have a hardware HEVC encoder
 * (every Samsung from S8 onward does). This class skips the entire CPU-side
 * conversion: VirtualDisplay writes straight into MediaCodec's input Surface
 * via DMA, and we hand the resulting H.265 NAL units directly to Rust as
 * `VideoFrame::H265s`.
 *
 * Lifecycle (must be on a serviceHandler thread, NOT main):
 *   1. start(width, height, fpsHint, initialBitrateKbps) → Surface for VD
 *   2. VirtualDisplay attaches to surface
 *   3. Callback feeds NAL units to FFI.onEncodedVideoFrame(...)
 *   4. setBitrateKbps(kbps) — pushed from Rust QoS via rustSetByName
 *   5. requestKeyFrame() — pushed from Rust on switch / new subscriber
 *   6. stop() — releases encoder, surface, clears Rust-side flag
 *
 * Thread safety: MediaCodec.Callback is invoked on the codec's internal
 * thread (set via setCallback's Handler). All public methods are designed
 * to be called from MainService's serviceHandler.
 */
class NativeHevcEncoder(private val tag: String = "NativeHevcEncoder") {

    companion object {
        // Negotiated codec on the wire. RustDesk uses Annex-B framing for H.265
        // (set_h265s in proto), so MediaCodec output (which is already Annex-B
        // when the encoder is configured for video/hevc) goes through verbatim.
        const val MIME_HEVC = "video/hevc"

        // KEY_PRIORITY: 0 = realtime, 1 = best-effort. We want realtime.
        private const val KEY_PRIORITY_REALTIME = 0

        // KEY_OPERATING_RATE: hint to the codec that we want to run at "as fast
        // as the hardware allows" — Short.MAX_VALUE is the conventional value.
        private const val OPERATING_RATE_HINT = Short.MAX_VALUE.toInt()

        // Default GOP — effectively infinite. We force keyframes on demand
        // (new subscriber, switch event) via PARAMETER_KEY_REQUEST_SYNC_FRAME.
        // A finite GOP wastes bandwidth on a steady-state remote-control link
        // where most frames are mostly-static UI.
        private const val I_FRAME_INTERVAL_SECONDS = 3600  // 1h

        // Minimum supported API. createInputSurface itself is API 18+, but
        // BITRATE_MODE_CBR + KEY_LATENCY + dynamic setParameters need 30+.
        private const val MIN_API = Build.VERSION_CODES.R
    }

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null

    // Cached codec config (VPS/SPS/PPS) — prepended to every IDR so the
    // viewer doesn't need to be sticky-stateful about parameter sets.
    private var codecConfig: ByteArray = ByteArray(0)

    // Currently configured bitrate, so we don't churn setParameters on
    // identical updates from QoS.
    private var currentBitrateKbps: Int = 0

    /** Whether this device + Android version supports this path at all. */
    fun isSupported(): Boolean {
        if (Build.VERSION.SDK_INT < MIN_API) return false
        // We could probe MediaCodecList for an encoder, but the codec-info
        // table is already shipped to Rust via FFI.setCodecInfo at startup;
        // failure here will surface naturally as a configure() exception and
        // we fall back to the legacy path.
        return true
    }

    /**
     * Configure + start the encoder, return its input Surface.
     * Caller hands that Surface to VirtualDisplay.
     * Returns null on failure → caller must fall back to the legacy RGBA path.
     */
    fun start(width: Int, height: Int, fpsHint: Int, initialBitrateKbps: Int): Surface? {
        if (!isSupported()) {
            Log.w(tag, "start: unsupported SDK ${Build.VERSION.SDK_INT}, falling back")
            return null
        }
        return try {
            val mc = MediaCodec.createEncoderByType(MIME_HEVC)
            val fmt = MediaFormat.createVideoFormat(MIME_HEVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, initialBitrateKbps * 1000)
                setInteger(MediaFormat.KEY_FRAME_RATE, fpsHint)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
                // CBR keeps bursts off the mobile uplink. ABR (Rust-side) is
                // what adjusts to network changes; the encoder doesn't try to
                // be clever about scene complexity.
                setInteger(MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                // Ask the codec for the lowest-latency output it can manage.
                // API 30+; ignored otherwise.
                setInteger(MediaFormat.KEY_LATENCY, 1)
                // Realtime priority + operating-rate hint — encourages the
                // codec to use its fastest configuration on Samsung silicon.
                setInteger(MediaFormat.KEY_PRIORITY, KEY_PRIORITY_REALTIME)
                setInteger(MediaFormat.KEY_OPERATING_RATE, OPERATING_RATE_HINT)
            }
            mc.setCallback(callback)
            mc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = mc.createInputSurface()
            mc.start()
            codec = mc
            currentBitrateKbps = initialBitrateKbps
            FFI.setNativeEncoderActive(true)
            Log.i(tag, "started: ${width}x${height} @ ${fpsHint}fps, ${initialBitrateKbps}kbps")
            inputSurface
        } catch (t: Throwable) {
            Log.e(tag, "start failed: ${t.javaClass.simpleName}: ${t.message}")
            release()
            null
        }
    }

    fun stop() {
        Log.i(tag, "stop")
        FFI.setNativeEncoderActive(false)
        release()
    }

    private fun release() {
        try { codec?.stop() } catch (_: Throwable) {}
        try { codec?.release() } catch (_: Throwable) {}
        try { inputSurface?.release() } catch (_: Throwable) {}
        codec = null
        inputSurface = null
        codecConfig = ByteArray(0)
        currentBitrateKbps = 0
    }

    /** Push a new target bitrate (called from Rust via rustSetByName). */
    fun setBitrateKbps(kbps: Int) {
        if (kbps <= 0 || kbps == currentBitrateKbps) return
        val mc = codec ?: return
        try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, kbps * 1000)
            }
            mc.setParameters(params)
            currentBitrateKbps = kbps
        } catch (t: Throwable) {
            Log.w(tag, "setBitrateKbps($kbps) failed: ${t.message}")
        }
    }

    /** Force a sync (IDR) frame on the next output (called from Rust). */
    fun requestKeyFrame() {
        val mc = codec ?: return
        try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            mc.setParameters(params)
        } catch (t: Throwable) {
            Log.w(tag, "requestKeyFrame failed: ${t.message}")
        }
    }

    // ── MediaCodec.Callback ────────────────────────────────────────────────

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(c: MediaCodec, index: Int) {
            // No-op: we use Surface-input, MediaCodec pulls frames from
            // VirtualDisplay itself; we never queue input buffers manually.
        }

        override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) {
            // Output format changes happen at start (CSD-0 carries the codec
            // config) and rarely after. We already capture CSD via the
            // BUFFER_FLAG_CODEC_CONFIG path below, which is simpler.
            Log.d(tag, "onOutputFormatChanged: $format")
        }

        override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(tag, "MediaCodec error: isTransient=${e.isTransient} ${e.diagnosticInfo}")
            // Surface to Rust by toggling the active flag off. video_service.rs
            // will then fall back to its capture+encoder loop on the next tick.
            FFI.setNativeEncoderActive(false)
        }

        override fun onOutputBufferAvailable(
            c: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo,
        ) {
            val buf = try {
                c.getOutputBuffer(index)
            } catch (_: Throwable) {
                try { c.releaseOutputBuffer(index, false) } catch (_: Throwable) {}
                return
            } ?: run {
                try { c.releaseOutputBuffer(index, false) } catch (_: Throwable) {}
                return
            }

            val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
            val isKeyFrame = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
            val isEos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0

            if (info.size > 0 && !isEos) {
                buf.position(info.offset)
                buf.limit(info.offset + info.size)

                if (isConfig) {
                    // Cache VPS/SPS/PPS — every later keyframe gets it prepended
                    // so the decoder on the renter side never needs to "remember"
                    // parameter sets from earlier in the stream.
                    val cfg = ByteArray(info.size)
                    buf.get(cfg)
                    codecConfig = cfg
                    Log.d(tag, "codec config captured: ${cfg.size} bytes")
                } else {
                    // Ship the access unit to Rust. For keyframes, prepend the
                    // cached VPS/SPS/PPS so each IDR is self-contained.
                    val payload: ByteBuffer = if (isKeyFrame && codecConfig.isNotEmpty()) {
                        val merged = ByteBuffer.allocateDirect(codecConfig.size + info.size)
                        merged.put(codecConfig)
                        // re-pin position after the codecConfig put
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        merged.put(buf)
                        merged.flip()
                        merged
                    } else {
                        // Direct hand-off — Rust copies out of the direct buffer
                        // before this output buffer is released below.
                        val direct = ByteBuffer.allocateDirect(info.size)
                        direct.put(buf)
                        direct.flip()
                        direct
                    }

                    // info.presentationTimeUs → ms for the wire protocol.
                    val ptsMs = info.presentationTimeUs / 1000L
                    FFI.onEncodedVideoFrame(payload, ptsMs, isKeyFrame)
                }
            }

            try { c.releaseOutputBuffer(index, false) } catch (_: Throwable) {}
        }
    }
}
