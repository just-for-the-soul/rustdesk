package com.carriez.flutter_hbb

import android.accessibilityservice.AccessibilityService
import android.graphics.*
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Оптимизированный XML capture для плавного видео (12-16 FPS)
 * БЕЗ индикации screen recording!
 */
class OptimizedXmlCapture(private val service: AccessibilityService) {
    private val TAG = "OptimizedXmlCapture"

    // Настройки производительности
    // ВАЖНО: Если менять downscaleFactor (например на 0.5f), нужно обязательно 
    // обновлять SCREEN_INFO в MainService, иначе Rust упадет из-за несовпадения размера буфера!
    var downscaleFactor = 1.0f  

    private val paint = Paint().apply {
        isAntiAlias = false  // Быстрее без anti-aliasing
    }

    /**
     * Захват экрана через XML AccessibilityService
     */
    fun captureOptimized(): CaptureResult? {
        try {
            val rootNode = service.rootInActiveWindow ?: return null

            // Размеры с downscaling
            val displayMetrics = service.resources.displayMetrics
            val width = (displayMetrics.widthPixels * downscaleFactor).toInt()
            val height = (displayMetrics.heightPixels * downscaleFactor).toInt()

            // Создаем bitmap для текущего кадра (будет очищен в XmlScreenCapture)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)

            // Отрисовываем UI
            drawNode(canvas, rootNode, downscaleFactor)

            // ОБЯЗАТЕЛЬНО: предотвращаем утечку памяти AccessibilityNodeInfo
            rootNode.recycle()

            // Возвращаем сам Bitmap напрямую, БЕЗ конвертации в JPEG!
            return CaptureResult(bitmap, System.currentTimeMillis())

        } catch (e: Exception) {
            Log.e(TAG, "Capture error", e)
            return null
        }
    }

    private fun drawNode(canvas: Canvas, node: AccessibilityNodeInfo, scale: Float) {
        try {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            // Применяем scale
            bounds.left = (bounds.left * scale).toInt()
            bounds.top = (bounds.top * scale).toInt()
            bounds.right = (bounds.right * scale).toInt()
            bounds.bottom = (bounds.bottom * scale).toInt()

            if (bounds.isEmpty || !node.isVisibleToUser) return

            // Рисуем в зависимости от типа
            val className = node.className?.toString() ?: ""
            val text = node.text?.toString()

            when {
                className.contains("Button") -> {
                    paint.color = Color.LTGRAY
                    paint.style = Paint.Style.FILL
                    canvas.drawRect(bounds, paint)

                    paint.color = Color.DKGRAY
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 2f
                    canvas.drawRect(bounds, paint)

                    text?.let { drawText(canvas, bounds, it) }
                }
                className.contains("TextView") || className.contains("EditText") -> {
                    text?.let { drawText(canvas, bounds, it) }
                }
                className.contains("ImageView") -> {
                    paint.color = Color.LTGRAY
                    paint.style = Paint.Style.FILL
                    canvas.drawRect(bounds, paint)
                }
                else -> {
                    paint.color = Color.rgb(245, 245, 245)
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 0.5f
                    canvas.drawRect(bounds, paint)
                }
            }

            // Рекурсивно для детей
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    drawNode(canvas, child, scale)
                    child.recycle() // Очищаем дочерний узел
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Draw error", e)
        }
    }

    private fun drawText(canvas: Canvas, bounds: Rect, text: String) {
        paint.color = Color.BLACK
        paint.textSize = minOf(bounds.height() * 0.7f, 32f)
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.LEFT

        canvas.drawText(
            text,
            bounds.left + 8f,
            bounds.centerY() + paint.textSize / 3,
            paint
        )
    }

    data class CaptureResult(
        val bitmap: Bitmap,
        val timestamp: Long
    )
}

