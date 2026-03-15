package com.carriez.flutter_hbb

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel

/**
 * CaptureController — управляет выбором метода захвата экрана.
 *
 * Интеграция в MainActivity.kt (в configureFlutterEngine):
 *   CaptureController.init(this, flutterEngine.dartExecutor.binaryMessenger)
 *
 * Flutter вызывает через MethodChannel "com.carriez.flutter_hbb/capture":
 *   "setXmlCapture"      — переключиться на XML capture
 *   "setMediaProjection" — переключиться на MP capture
 *   "getCaptureMethod"   — возвращает "xml" | "mp"
 *   "startCapture"       — запустить активный метод
 *   "stopCapture"        — остановить активный метод
 */
object CaptureController {

    private const val TAG = "CaptureController"
    const val CHANNEL = "com.carriez.flutter_hbb/capture"

    enum class Method { XML, MEDIA_PROJECTION }

    @Volatile
    var activeMethod: Method = Method.MEDIA_PROJECTION
        private set

    fun init(context: Context, messenger: BinaryMessenger) {
        MethodChannel(messenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "setXmlCapture" -> {
                    activeMethod = Method.XML
                    Log.i(TAG, "capture method → XML")
                    result.success(null)
                }
                "setMediaProjection" -> {
                    // Останавливаем XML если он был активен
                    if (XmlCapture.isActive()) XmlCapture.stop()
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
                    stopActive()
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }
    }

    // -----------------------------------------------------------------------
    // Start / Stop
    // -----------------------------------------------------------------------

    fun startActive(context: Context) {
        when (activeMethod) {
            Method.XML -> startXmlCapture(context)
            Method.MEDIA_PROJECTION -> { /* MP запускается своим flow через MainService */ }
        }
    }

    fun stopActive() {
        when (activeMethod) {
            Method.XML -> XmlCapture.stop()
            Method.MEDIA_PROJECTION -> { /* MP останавливается через MainService */ }
        }
    }

    private fun startXmlCapture(context: Context) {
        val service = MainAccessibilityService.instance
        if (service == null) {
            Log.w(TAG, "MainAccessibilityService не запущен — открываем настройки")
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            )
            return
        }
        // XmlCapture использует SCREEN_INFO напрямую — размеры не передаём
        XmlCapture.start(service)
    }
}
