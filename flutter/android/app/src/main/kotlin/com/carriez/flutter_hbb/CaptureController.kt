package com.carriez.flutter_hbb

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Log
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel

/**
 * CaptureController — выбор метода захвата экрана.
 *
 * Метод сохраняется в SharedPreferences и переживает перезапуск.
 * Интеграция в MainActivity.configureFlutterEngine:
 *   CaptureController.init(this, flutterEngine.dartExecutor.binaryMessenger)
 */
object CaptureController {

    private const val TAG = "CaptureController"
    const val CHANNEL = "com.carriez.flutter_hbb/capture"
    private const val PREFS_NAME = "capture_prefs"
    private const val KEY_METHOD = "capture_method"
    const val METHOD_MP = "mp"
    const val METHOD_XML = "xml"

    var activeMethod: String = METHOD_MP
        private set

    fun init(context: Context, messenger: BinaryMessenger) {
        // Восстанавливаем сохранённый выбор
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
                    if (XmlCapture.isActive()) XmlCapture.stop()
                    result.success(null)
                }
                "switchMethod" -> {
                    // Переключение во время захвата
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

    private fun switchMethodDuringCapture(context: Context, method: String) {
        when {
            method == METHOD_XML && activeMethod != METHOD_XML -> {
                setMethod(context, METHOD_XML)
                startXmlIfNeeded(context)
            }
            method == METHOD_MP && activeMethod != METHOD_MP -> {
                XmlCapture.stop()
                setMethod(context, METHOD_MP)
                // MP продолжает работать через VirtualDisplay — ничего дополнительно не нужно
            }
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
