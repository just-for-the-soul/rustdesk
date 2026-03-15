package com.carriez.flutter_hbb

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * XmlCapture — альтернативный метод захвата экрана через AccessibilityService.
 *
 * Принцип работы:
 *   1. Получаем список окон из MainAccessibilityService
 *   2. Для каждого окна обходим дерево AccessibilityNodeInfo
 *   3. Рендерим узлы (bounds + цвет + текст) на Canvas → Bitmap
 *   4. Сжимаем в JPEG и передаём через тот же колбэк что использует MediaProjection
 *
 * Формат вывода идентичен MediaProjection: ByteArray (JPEG), ширина, высота —
 * поэтому клиентский код не требует изменений.
 */
object XmlCapture {

    private const val TAG = "XmlCapture"
    private const val JPEG_QUALITY = 80
    private const val TARGET_FPS = 15
    private val FRAME_INTERVAL_MS = (1000L / TARGET_FPS)

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------
    private val isRunning = AtomicBoolean(false)
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    // Колбэк: такой же тип как в MediaProjection capture
    // (data: ByteArray, width: Int, height: Int)
    private var frameCallback: ((ByteArray, Int, Int) -> Unit)? = null

    // Размеры экрана (выставляются при старте из MainActivity/FlutterActivity)
    private var screenWidth: Int = 1080
    private var screenHeight: Int = 1920

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Стартуем захват.
     * @param service  — запущенный MainAccessibilityService
     * @param width    — ширина экрана (px)
     * @param height   — высота экрана (px)
     * @param callback — вызывается на каждый кадр: (jpegBytes, width, height)
     */
    fun start(
        service: MainAccessibilityService,
        width: Int = screenWidth,
        height: Int = screenHeight,
        callback: ((ByteArray, Int, Int) -> Unit)? = null
    ) {
        if (isRunning.getAndSet(true)) {
            Log.w(TAG, "already running")
            return
        }
        screenWidth = width
        screenHeight = height
        if (callback != null) frameCallback = callback

        captureThread = HandlerThread("XmlCaptureThread").also { it.start() }
        captureHandler = Handler(captureThread!!.looper)

        scheduleNextFrame(service)
        Log.i(TAG, "started ${width}x${height} @ ${TARGET_FPS}fps")
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        captureHandler?.removeCallbacksAndMessages(null)
        captureThread?.quitSafely()
        captureHandler = null
        captureThread = null
        Log.i(TAG, "stopped")
    }

    fun isActive(): Boolean = isRunning.get()

    /**
     * Установить колбэк отдельно (если не передан при старте).
     * Используется из MainActivity/FlutterMethodChannel.
     */
    fun setFrameCallback(cb: (ByteArray, Int, Int) -> Unit) {
        frameCallback = cb
    }

    // -----------------------------------------------------------------------
    // Capture loop
    // -----------------------------------------------------------------------
    private fun scheduleNextFrame(service: MainAccessibilityService) {
        if (!isRunning.get()) return
        captureHandler?.postDelayed({
            captureFrame(service)
            scheduleNextFrame(service)
        }, FRAME_INTERVAL_MS)
    }

    private fun captureFrame(service: MainAccessibilityService) {
        try {
            val bitmap = Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.BLACK) // фон

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                // Рендерим все окна по Z-order (снизу вверх)
                val windows = service.getWindowsList()
                    .sortedBy { it.layer }
                for (window in windows) {
                    val rootNode = window.root ?: continue
                    renderNode(canvas, rootNode)
                    rootNode.recycle()
                }
            } else {
                // Fallback: только активное окно
                val root = service.getRootNode()
                if (root != null) {
                    renderNode(canvas, root)
                    root.recycle()
                }
            }

            // Compress → JPEG ByteArray (идентично MediaProjection output)
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            bitmap.recycle()

            frameCallback?.invoke(out.toByteArray(), screenWidth, screenHeight)

        } catch (e: Exception) {
            Log.e(TAG, "captureFrame error", e)
        }
    }

    // -----------------------------------------------------------------------
    // Render tree
    // -----------------------------------------------------------------------
    private val bgPaint = Paint().apply { style = Paint.Style.FILL }
    private val borderPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.argb(60, 200, 200, 200) // тонкая граница UI элементов
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 28f
        isAntiAlias = true
    }
    private val clickPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.argb(120, 0, 200, 255) // кликабельные — голубая рамка
    }

    /**
     * Рекурсивно обходим дерево AccessibilityNodeInfo и рисуем каждый узел.
     */
    private fun renderNode(canvas: Canvas, node: AccessibilityNodeInfo, depth: Int = 0) {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        if (bounds.isEmpty || bounds.width() <= 0 || bounds.height() <= 0) {
            // Всё равно обходим детей
            iterateChildren(canvas, node, depth)
            return
        }

        // Фон: листовые узлы с контентом получают полупрозрачный прямоугольник
        val isLeaf = node.childCount == 0
        if (isLeaf) {
            bgPaint.color = pickNodeColor(node, depth)
            canvas.drawRect(bounds.toRectF(), bgPaint)
        }

        // Кликабельные элементы — отдельная рамка
        if (node.isClickable) {
            canvas.drawRect(bounds.toRectF(), clickPaint)
        } else {
            canvas.drawRect(bounds.toRectF(), borderPaint)
        }

        // Текст
        val text = node.text?.toString() ?: node.contentDescription?.toString()
        if (!text.isNullOrBlank()) {
            val maxWidth = bounds.width().toFloat() - 8f
            val truncated = truncateText(text, textPaint, maxWidth)
            canvas.drawText(
                truncated,
                bounds.left.toFloat() + 4f,
                bounds.top.toFloat() + textPaint.textSize + 4f,
                textPaint
            )
        }

        iterateChildren(canvas, node, depth + 1)
    }

    private fun iterateChildren(canvas: Canvas, node: AccessibilityNodeInfo, depth: Int) {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            renderNode(canvas, child, depth)
            child.recycle()
        }
    }

    /**
     * Цвет фона узла зависит от его типа / состояния.
     * Имитирует «скелетон» реального экрана.
     */
    private fun pickNodeColor(node: AccessibilityNodeInfo, depth: Int): Int {
        return when {
            node.isClickable && node.isFocused -> Color.argb(200, 30, 120, 200)  // фокус
            node.isClickable                   -> Color.argb(180, 40, 40, 60)    // кнопка
            node.isEditable                    -> Color.argb(200, 20, 60, 20)    // поле ввода
            node.isCheckable                   -> Color.argb(180, 60, 40, 80)    // чекбокс
            depth % 2 == 0                     -> Color.argb(60,  30, 30, 40)    // чётная глубина
            else                               -> Color.argb(40,  50, 50, 70)    // нечётная
        }
    }

    private fun truncateText(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 0 && paint.measureText(text.substring(0, end) + "…") > maxWidth) end--
        return if (end <= 0) "…" else text.substring(0, end) + "…"
    }
}

// Extension: Rect → RectF без аллокации нового объекта каждый раз
private fun Rect.toRectF() = android.graphics.RectF(
    left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat()
)
