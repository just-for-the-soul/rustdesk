package com.carriez.flutter_hbb

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.nio.ByteBuffer

/**
 * УМНОЕ ПЕРЕКЛЮЧЕНИЕ MediaProjection ↔ XML
 * 
 * Работает с ВАШИМ текущим кодом (OptimizedXmlCapture + XmlScreenCapture)
 * 
 * Функции:
 * 1. Детектирует FLAG_SECURE (черный экран)
 * 2. Автоматически переключается на XML
 * 3. Возвращается на MediaProjection когда флаг исчезает
 */
class SmartCaptureSwitch(
    private val onSwitchToXml: () -> Unit,          // Callback: переключиться на XML
    private val onSwitchToMediaProjection: () -> Unit  // Callback: вернуться на MP
) {
    private val TAG = "SmartCaptureSwitch"
    
    enum class CaptureMode {
        MEDIA_PROJECTION,  // Основной режим (быстро)
        XML_FALLBACK       // Запасной режим (при FLAG_SECURE)
    }
    
    @Volatile
    private var currentMode = CaptureMode.MEDIA_PROJECTION
    
    // Детекция черного экрана
    private var consecutiveBlackFrames = 0
    private val BLACK_FRAMES_THRESHOLD = 5  // 5 черных кадров подряд → переключение
    
    private var consecutiveNonBlackFrames = 0
    private val NON_BLACK_FRAMES_THRESHOLD = 10  // 10 нормальных кадров → возврат
    
    private val handler = Handler(Looper.getMainLooper())
    
    /**
     * Анализирует кадр от MediaProjection
     * Вызывайте из ImageReader callback
     */
    fun analyzeFrame(buffer: ByteBuffer, width: Int, height: Int) {
        if (currentMode != CaptureMode.MEDIA_PROJECTION) {
            // В XML режиме не анализируем MP кадры
            return
        }
        
        if (isBlackFrame(buffer, width, height)) {
            consecutiveBlackFrames++
            consecutiveNonBlackFrames = 0
            
            if (consecutiveBlackFrames >= BLACK_FRAMES_THRESHOLD) {
                Log.w(TAG, "Detected FLAG_SECURE ($consecutiveBlackFrames black frames), switching to XML!")
                switchToXml()
            } else {
                Log.d(TAG, "Black frame detected: $consecutiveBlackFrames/$BLACK_FRAMES_THRESHOLD")
            }
        } else {
            // Кадр не черный
            if (consecutiveBlackFrames > 0) {
                Log.d(TAG, "Non-black frame, resetting black counter (was $consecutiveBlackFrames)")
                consecutiveBlackFrames = 0
            }
            consecutiveNonBlackFrames++
        }
    }
    
    /**
     * Анализирует кадр от XML
     * Вызывайте из XmlScreenCapture для проверки можно ли вернуться на MP
     */
    fun analyzeXmlFrame(bitmap: android.graphics.Bitmap) {
        if (currentMode != CaptureMode.XML_FALLBACK) {
            return
        }
        
        // Проверяем что приложение с FLAG_SECURE закрыто
        // Если видим нормальный контент → можно вернуться на MP
        if (!isBitmapMostlyBlack(bitmap)) {
            consecutiveNonBlackFrames++
            
            if (consecutiveNonBlackFrames >= NON_BLACK_FRAMES_THRESHOLD) {
                Log.i(TAG, "FLAG_SECURE removed ($consecutiveNonBlackFrames normal frames), switching back to MediaProjection")
                switchToMediaProjection()
            } else {
                Log.d(TAG, "Normal content in XML: $consecutiveNonBlackFrames/$NON_BLACK_FRAMES_THRESHOLD")
            }
        } else {
            consecutiveNonBlackFrames = 0
        }
    }
    
    /**
     * Проверяет черный ли кадр (ByteBuffer от MediaProjection)
     */
    private fun isBlackFrame(buffer: ByteBuffer, width: Int, height: Int): Boolean {
        try {
            // Семплируем центральную область (20x20 пикселей)
            val centerX = width / 2
            val centerY = height / 2
            val sampleSize = 20
            
            var blackPixels = 0
            var totalPixels = 0
            
            val startX = maxOf(0, centerX - sampleSize / 2)
            val endX = minOf(width - 1, centerX + sampleSize / 2)
            val startY = maxOf(0, centerY - sampleSize / 2)
            val endY = minOf(height - 1, centerY + sampleSize / 2)
            
            for (y in startY until endY) {
                for (x in startX until endX) {
                    val index = (y * width + x) * 4  // RGBA
                    
                    if (index + 3 >= buffer.capacity()) continue
                    
                    buffer.position(index)
                    val r = buffer.get().toInt() and 0xFF
                    val g = buffer.get().toInt() and 0xFF
                    val b = buffer.get().toInt() and 0xFF
                    
                    // Считаем "черным" если все каналы < 15
                    if (r < 15 && g < 15 && b < 15) {
                        blackPixels++
                    }
                    totalPixels++
                }
            }
            
            buffer.rewind()
            
            // Если >85% пикселей черные → это FLAG_SECURE
            val blackRatio = if (totalPixels > 0) {
                blackPixels.toFloat() / totalPixels
            } else {
                0f
            }
            
            return blackRatio > 0.85f
            
        } catch (e: Exception) {
            Log.e(TAG, "Error checking black frame", e)
            return false
        }
    }
    
    /**
     * Проверяет черный ли Bitmap (от XML capture)
     */
    private fun isBitmapMostlyBlack(bitmap: android.graphics.Bitmap): Boolean {
        try {
            val width = bitmap.width
            val height = bitmap.height
            
            // Семплируем центр
            val centerX = width / 2
            val centerY = height / 2
            val sampleSize = 20
            
            var blackPixels = 0
            var totalPixels = 0
            
            val startX = maxOf(0, centerX - sampleSize / 2)
            val endX = minOf(width - 1, centerX + sampleSize / 2)
            val startY = maxOf(0, centerY - sampleSize / 2)
            val endY = minOf(height - 1, centerY + sampleSize / 2)
            
            for (y in startY until endY) {
                for (x in startX until endX) {
                    val pixel = bitmap.getPixel(x, y)
                    val r = android.graphics.Color.red(pixel)
                    val g = android.graphics.Color.green(pixel)
                    val b = android.graphics.Color.blue(pixel)
                    
                    if (r < 15 && g < 15 && b < 15) {
                        blackPixels++
                    }
                    totalPixels++
                }
            }
            
            val blackRatio = if (totalPixels > 0) {
                blackPixels.toFloat() / totalPixels
            } else {
                0f
            }
            
            return blackRatio > 0.85f
            
        } catch (e: Exception) {
            Log.e(TAG, "Error checking bitmap", e)
            return false
        }
    }
    
    /**
     * Переключается на XML (обход FLAG_SECURE)
     */
    private fun switchToXml() {
        if (currentMode == CaptureMode.XML_FALLBACK) {
            return  // Уже в XML режиме
        }
        
        Log.i(TAG, "=== SWITCHING TO XML MODE ===")
        currentMode = CaptureMode.XML_FALLBACK
        consecutiveBlackFrames = 0
        consecutiveNonBlackFrames = 0
        
        // Вызываем callback для MainService
        handler.post {
            onSwitchToXml()
        }
    }
    
    /**
     * Возвращается на MediaProjection (быстрее)
     */
    private fun switchToMediaProjection() {
        if (currentMode == CaptureMode.MEDIA_PROJECTION) {
            return  // Уже в MP режиме
        }
        
        Log.i(TAG, "=== SWITCHING BACK TO MEDIAPROJECTION ===")
        currentMode = CaptureMode.MEDIA_PROJECTION
        consecutiveBlackFrames = 0
        consecutiveNonBlackFrames = 0
        
        // Вызываем callback для MainService
        handler.post {
            onSwitchToMediaProjection()
        }
    }
    
    /**
     * Текущий режим
     */
    fun getCurrentMode(): CaptureMode = currentMode
    
    /**
     * Принудительное переключение (для тестирования)
     */
    fun forceSwitch(mode: CaptureMode) {
        Log.i(TAG, "Force switching to $mode")
        when (mode) {
            CaptureMode.XML_FALLBACK -> switchToXml()
            CaptureMode.MEDIA_PROJECTION -> switchToMediaProjection()
        }
    }
    
    /**
     * Сброс при остановке capture
     */
    fun reset() {
        currentMode = CaptureMode.MEDIA_PROJECTION
        consecutiveBlackFrames = 0
        consecutiveNonBlackFrames = 0
    }
}
