package com.carriez.flutter_hbb

import android.graphics.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import ffi.FFI
import java.nio.ByteBuffer

/**
 * XML-based screen capture как альтернатива MediaProjection
 * Интегрируется прозрачно с MainService
 */
class XmlScreenCapture {
    private val TAG = "XmlScreenCapture"
    
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var isRunning = false
    
    private var optimizedCapture: OptimizedXmlCapture? = null
    
    /**
     * Запускает XML захват
     * Использует тот же callback FFI.onVideoFrameUpdate() как MediaProjection!
     */
    fun start() {
        if (isRunning) {
            Log.w(TAG, "Already running")
            return
        }
        
        // Проверяем что InputService активен
        val service = InputService.ctx
        if (service == null) {
            Log.e(TAG, "InputService not available!")
            return
        }
        
        Log.i(TAG, "Starting XML screen capture")
        
        // Создаем OptimizedXmlCapture
        optimizedCapture = OptimizedXmlCapture(service).apply {
            // Настройки производительности
            downscaleFactor = 0.5f  // Половина разрешения
            jpegQuality = 60        // Среднее качество
            cacheEnabled = true     // Кэш статичных элементов
            deltaEnabled = false    // Дельта пока отключена (нужна поддержка в клиенте)
        }
        
        // Создаем отдельный поток для захвата
        captureThread = HandlerThread("XmlCaptureThread").apply {
            start()
            captureHandler = Handler(looper)
        }
        
        isRunning = true
        
        // Запускаем захват кадров
        scheduleNextCapture()
    }
    
    /**
     * Останавливает захват
     */
    fun stop() {
        if (!isRunning) return
        
        Log.i(TAG, "Stopping XML screen capture")
        isRunning = false
        
        captureHandler?.removeCallbacksAndMessages(null)
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
        
        optimizedCapture = null
    }
    
    /**
     * Планирует следующий захват кадра
     */
    private fun scheduleNextCapture() {
        if (!isRunning) return
        
        captureHandler?.post {
            captureFrame()
            
            // Следующий кадр через ~60-80ms (12-16 FPS)
            captureHandler?.postDelayed({
                scheduleNextCapture()
            }, 70)
        }
    }
    
    /**
     * Захватывает один кадр и отправляет в Rust
     */
    private fun captureFrame() {
        try {
            val startTime = System.currentTimeMillis()
            
            // Захватываем через OptimizedXmlCapture
            val result = optimizedCapture?.captureOptimized()
            
            if (result == null) {
                Log.w(TAG, "Capture returned null")
                return
            }
            
            // Конвертируем JPEG в RGB для FFI
            val bitmap = BitmapFactory.decodeByteArray(result.data, 0, result.data.size)
            
            if (bitmap == null) {
                Log.e(TAG, "Failed to decode JPEG")
                return
            }
            
            // Конвертируем в ByteBuffer для FFI.onVideoFrameUpdate()
            val buffer = bitmapToRGBABuffer(bitmap)
            
            // Отправляем в Rust ТАК ЖЕ как MediaProjection!
            // Rust не знает что это XML capture!
            FFI.onVideoFrameUpdate(buffer)
            
            bitmap.recycle()
            
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed > 100) {
                Log.d(TAG, "Slow frame: ${elapsed}ms")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Capture error", e)
        }
    }
    
    /**
     * Конвертирует Bitmap в RGBA ByteBuffer для FFI
     */
    private fun bitmapToRGBABuffer(bitmap: Bitmap): ByteBuffer {
        val width = bitmap.width
        val height = bitmap.height
        
        // Создаем buffer того же формата что ImageReader
        val buffer = ByteBuffer.allocateDirect(width * height * 4)
        
        // Копируем пиксели
        bitmap.copyPixelsToBuffer(buffer)
        buffer.rewind()
        
        return buffer
    }
}
