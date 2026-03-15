package com.carriez.flutter_hbb

import android.content.Context
import android.content.Intent
import android.util.Log
import io.flutter.plugin.common.MethodChannel

/**
 * CaptureController — управляет выбором метода захвата экрана.
 *
 * Интегрируется в MainActivity.kt:
 *   CaptureController.init(context, flutterEngine.dartExecutor.binaryMessenger)
 *
 * Flutter вызывает:
 *   - "setXmlCapture"        — переключиться на XML capture
 *   - "setMediaProjection"   — переключиться на MP capture
 *   - "getCaptureMethod"     — текущий метод ("xml" | "mp")
 *   - "startCapture"         — запустить активный метод
 *   - "stopCapture"          — остановить
 */
object CaptureController {

    private const val TAG = "CaptureController"
    private const val CHANNEL = "com.carriez.flutter_hbb/capture"

    enum class Method { XML, MEDIA_PROJECTION }

    @Volatile
    var activeMethod: Method = Method.MEDIA_PROJECTION
        private set

    fun init(context: Context, messenger: io.flutter.plugin.common.BinaryMessenger) {
        MethodChannel(messenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "setXmlCapture" -> {
                    activeMethod = Method.XML
                    Log.i(TAG, "capture method → XML")
                    result.success(null)
                }
                "setMediaProjection" -> {
                    activeMethod = Method.MEDIA_PROJECTION
                    Log.i(TAG, "capture method → MediaProjection")
                    result.success(null)
                }
                "getCaptureMethod" -> {
                    result.success(if (activeMethod == Method.XML) "xml" else "mp")
                }
                "startCapture" -> {
                    startActive(context)
                    result.success(null)
                }
                "stopCapture" -> {
                    stopActive(context)
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }
    }

    // -----------------------------------------------------------------------
    // Start / Stop helpers
    // -----------------------------------------------------------------------
    fun startActive(context: Context) {
        when (activeMethod) {
            Method.XML -> startXmlCapture(context)
            Method.MEDIA_PROJECTION -> { /* MP запускается своим flow */ }
        }
    }

    fun stopActive(context: Context) {
        when (activeMethod) {
            Method.XML -> stopXmlCapture(context)
            Method.MEDIA_PROJECTION -> { /* MP останавливается своим flow */ }
        }
    }

    private fun startXmlCapture(context: Context) {
        val service = MainAccessibilityService.instance
        if (service == null) {
            Log.w(TAG, "AccessibilityService не запущен — открываем настройки")
            // Открываем настройки Accessibility чтобы пользователь включил сервис
            val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            return
        }

        // Передаём колбэк — он вызовет тот же VideoEncoder/FFI что и MP
        XmlCapture.setFrameCallback { jpegBytes, width, height ->
            // Здесь передаём кадр в тот же pipeline что MediaProjection
            // Замените на реальный вызов вашего native bridge:
            MainService.onVideoFrame(jpegBytes, width, height)
        }

        val dm = context.resources.displayMetrics
        XmlCapture.start(
            service = service,
            width = dm.widthPixels,
            height = dm.heightPixels
        )
    }

    private fun stopXmlCapture(context: Context) {
        XmlCapture.stop()
    }
}
