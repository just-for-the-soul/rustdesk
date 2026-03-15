package com.carriez.flutter_hbb

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Персистентный AccessibilityService для:
 * 1. XML-based захвата экрана (через XmlCapture)
 * 2. Keep-alive: не позволяет системе отключить сервис
 * 3. Автоприём диалога MediaProjection (как в InputService)
 *
 * Регистрируется в AndroidManifest.xml с конфигурацией res/xml/accessibility_service_config.xml
 */
class MainAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "MainAccessibilityService"
        private const val KEEP_ALIVE_INTERVAL_MS = 5_000L

        // Синглтон-ссылка для доступа из XmlCapture и MainActivity
        @Volatile
        var instance: MainAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }

    // -----------------------------------------------------------------------
    // Keep-alive
    // -----------------------------------------------------------------------
    private val keepAliveHandler = Handler(Looper.getMainLooper())
    private val keepAliveRunnable = object : Runnable {
        override fun run() {
            // Лёгкое действие — достаточно чтобы Android не усыпил сервис
            rootInActiveWindow?.recycle()
            keepAliveHandler.postDelayed(this, KEEP_ALIVE_INTERVAL_MS)
        }
    }

    // WakeLock чтобы CPU не засыпал пока идёт захват
    private var wakeLock: PowerManager.WakeLock? = null

    // -----------------------------------------------------------------------
    // BroadcastReceiver: принимаем команды из Flutter / MainActivity
    // -----------------------------------------------------------------------
    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_START_XML_CAPTURE -> XmlCapture.start(this@MainAccessibilityService)
                ACTION_STOP_XML_CAPTURE  -> XmlCapture.stop()
            }
        }
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "onServiceConnected")

        configureService()
        acquireWakeLock()
        keepAliveHandler.postDelayed(keepAliveRunnable, KEEP_ALIVE_INTERVAL_MS)

        val filter = IntentFilter().apply {
            addAction(ACTION_START_XML_CAPTURE)
            addAction(ACTION_STOP_XML_CAPTURE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(commandReceiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.w(TAG, "onDestroy — попытка перезапуска")
        instance = null
        keepAliveHandler.removeCallbacks(keepAliveRunnable)
        XmlCapture.stop()
        releaseWakeLock()
        try { unregisterReceiver(commandReceiver) } catch (_: Exception) {}

        // Перезапуск через 1 сек если сервис убили
        Handler(Looper.getMainLooper()).postDelayed({
            val intent = Intent(applicationContext, MainAccessibilityService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(intent)
            } else {
                applicationContext.startService(intent)
            }
        }, 1_000L)
    }

    override fun onInterrupt() {
        Log.w(TAG, "onInterrupt")
    }

    // -----------------------------------------------------------------------
    // AccessibilityEvent: авто-принятие диалога MediaProjection
    // -----------------------------------------------------------------------
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            tryAcceptMediaProjectionDialog(event)
        }
    }

    // -----------------------------------------------------------------------
    // Публичный API для XmlCapture
    // -----------------------------------------------------------------------
    fun getRootNode(): AccessibilityNodeInfo? = rootInActiveWindow

    fun getWindowsList(): List<android.view.accessibility.AccessibilityWindowInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) windows ?: emptyList()
        else emptyList()

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------
    private fun configureService() {
        serviceInfo = serviceInfo?.apply {
            // Получаем все события окон + изменения контента
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 100
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$TAG:WakeLock"
        ).also { it.acquire(10 * 60 * 1000L) } // 10 минут, обновляется keepAlive
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        wakeLock = null
    }

    /**
     * Автоматически нажимает кнопку "Start now" / "Начать" в диалоге
     * подтверждения MediaProjection — аналогично InputService.
     */
    private fun tryAcceptMediaProjectionDialog(event: AccessibilityEvent) {
        val source = event.source ?: return
        try {
            // Ищем кнопку подтверждения по тексту (локализация EN/RU)
            val candidates = listOf("Start now", "Начать", "Allow", "Разрешить")
            findAndClickButton(source, candidates)
        } catch (e: Exception) {
            Log.e(TAG, "tryAcceptMediaProjectionDialog error", e)
        } finally {
            source.recycle()
        }
    }

    private fun findAndClickButton(node: AccessibilityNodeInfo, labels: List<String>): Boolean {
        for (label in labels) {
            val results = node.findAccessibilityNodeInfosByText(label)
            results?.forEach { n ->
                if (n.isClickable) {
                    n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    n.recycle()
                    return true
                }
                n.recycle()
            }
        }
        return false
    }
}

// ---------------------------------------------------------------------------
// Константы broadcast actions (используются из Flutter / MainActivity)
// ---------------------------------------------------------------------------
const val ACTION_START_XML_CAPTURE = "com.carriez.flutter_hbb.START_XML_CAPTURE"
const val ACTION_STOP_XML_CAPTURE  = "com.carriez.flutter_hbb.STOP_XML_CAPTURE"
