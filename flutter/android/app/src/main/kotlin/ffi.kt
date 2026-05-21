// ffi.kt

package ffi

import android.content.Context
import java.nio.ByteBuffer

import com.carriez.flutter_hbb.RdClipboardManager

object FFI {
    init {
        System.loadLibrary("rustdesk")
    }

    external fun init(ctx: Context)
    external fun onAppStart(ctx: Context)
    external fun setClipboardManager(clipboardManager: RdClipboardManager)
    external fun startServer(app_dir: String, custom_client_config: String)
    external fun startService()
    external fun onVideoFrameUpdate(buf: ByteBuffer)
    external fun onAudioFrameUpdate(buf: ByteBuffer)

    // Native-encoder path (HEVC via MediaCodec Surface-input). When this path
    // is active, raw RGBA frames are NOT delivered via onVideoFrameUpdate;
    // instead onEncodedVideoFrame ships already-encoded H.265 access units.
    // buf must be a direct ByteBuffer; pos/limit demarcate the NAL bytes
    // (Annex-B framing — for keyframes, VPS/SPS/PPS prepended).
    external fun onEncodedVideoFrame(buf: ByteBuffer, ptsMs: Long, isKeyframe: Boolean)
    external fun setNativeEncoderActive(active: Boolean)
    external fun translateLocale(localeName: String, input: String): String
    external fun refreshScreen()
    external fun setFrameRawEnable(name: String, value: Boolean)
    external fun setCodecInfo(info: String)
    external fun getLocalOption(key: String): String
    external fun onClipboardUpdate(clips: ByteBuffer)
    external fun isServiceClipboardEnabled(): Boolean
}
