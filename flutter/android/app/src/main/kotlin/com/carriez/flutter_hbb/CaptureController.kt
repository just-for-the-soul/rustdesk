package com.carriez.flutter_hbb

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Log
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel

/**
 * CaptureController — выбор и переключение метода захвата экрана.
 *
 * Интеграция в MainActivity.configureFlutterEngine:
 *   CaptureController.init(this, flutterEngine.dartExecutor.binaryMessenger)
 *
 * Метод сохраняется в SharedPreferences и переживает перезапуск.
 */
object CaptureController {

    private const val TAG = "CaptureController"
    const val CHANNEL = "com.carriez.flutter_hbb/capture"
    private const val PREFS_NAME = "capture_prefs"
    private const val KEY_METHOD = "capture_method"
    const val METHOD_MP  = "mp"
    const val METHOD_XML = "xml"

    var activeMethod: String = METHOD_MP
        private set

    // Ссылка на MainService для управления VirtualDisplay
    var mainService: MainService? = null

    fun init(context: Context, messenger: BinaryMessenger) {
        activeMethod = prefs(context).getString(KEY_METHOD, METHOD_MP) ?: METHOD_MP

        MethodChannel(messenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "setXmlCapture" -> {
                    setMethod(context, METHOD_XML)
                    result.success(null)
                }
                "setMediaProjection" -> {
                    if (XmlCapture.isActive()) XmlCapture.stop()
                    setMethod(context, METHOD_MP)
                    result.success(null)
                }
                "getCaptureMethod" -> result.success(activeMethod)
                "startCapture" -> {
                    startXmlIfNeeded(context)
                    result.success(null)
                }
                "stopCapture" -> {
                    XmlCapture.stop()
                    result.success(null)
                }
                "switchMethod" -> {
                    val method = call.arguments as? String ?: METHOD_MP
                    switchMethodDuringCapture(context, method)
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun setMethod(context: Context, method: String) {
        activeMethod = method
        prefs(context).edit().putString(KEY_METHOD, method).apply()
        Log.i(TAG, "capture method → $method")
    }

    fun startXmlIfNeeded(context: Context) {
        if (activeMethod != METHOD_XML) return
        val service = InputService.ctx
        if (service == null) {
            Log.w(TAG, "InputService не запущен — открываем настройки")
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            )
            return
        }
        XmlCapture.start(service)
    }

    fun stopXml() {
        if (XmlCapture.isActive()) XmlCapture.stop()
    }

    /**
     * Переключение метода во время активного захвата.
     *
     * MP → XML:
     *   1. stopCapture() — убирает VirtualDisplay и красный значок в статус-баре
     *   2. XmlCapture.start() — стартует XML захват с FFI.setFrameRawEnable("video", true)
     *
     * XML → MP:
     *   1. XmlCapture.stop() — останавливает XML захват с FFI.setFrameRawEnable("video", false)
     *   2. startCapture() — поднимает VirtualDisplay снова
     */
    private fun switchMethodDuringCapture(context: Context, method: String) {
        if (activeMethod == method) return

        when (method) {
            METHOD_XML -> {
                // Останавливаем MP: снимаем VirtualDisplay → пропадёт значок записи экрана
                mainService?.stopCapture()
                setMethod(context, METHOD_XML)
                startXmlIfNeeded(context)
            }
            METHOD_MP -> {
                // Останавливаем XML capture
                XmlCapture.stop()
                setMethod(context, METHOD_MP)
                // Поднимаем VirtualDisplay снова
                mainService?.startCapture()
            }
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
