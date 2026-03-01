package com.carriez.flutter_hbb

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import ffi.FFI
import java.nio.ByteBuffer

/**
 * XML Screen Capture для RustDesk
 * Интегрируется с MainService без изменений Rust кода!
 */
class XmlScreenCapture {
    private val TAG = "XmlScreenCapture"



    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var isRunning = false

    private var optimizedCapture: OptimizedXmlCapture? = null
    
    // Переиспользуемый буфер для передачи данных в Rust (предотвращает OOM)
    private var sharedBuffer: ByteBuffer? = null

    fun start() {
        if (isRunning) {
            Log.w(TAG, "Already running")
            return
        }

        val service = InputService.ctx
        if (service == null) {
            Log.e(TAG, "InputService not available!")
            return
        }

        Log.i(TAG, "Starting XML screen capture (12-16 FPS, NO RECORDING INDICATOR)")

        optimizedCapture = OptimizedXmlCapture(service)

        captureThread = HandlerThread("XmlCapture").apply {
            start()
            captureHandler = Handler(looper)
        }

        isRunning = true
        scheduleNextCapture()
    }

    fun stop() {
        if (!isRunning) return

        Log.i(TAG, "Stopping XML screen capture")
        isRunning = false

        captureHandler?.removeCallbacksAndMessages(null)
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
        optimizedCapture = null
        
        // Очищаем ссылку на буфер
        sharedBuffer = null
    }

    private fun scheduleNextCapture() {
        if (!isRunning) return

        captureHandler?.post {
            captureFrame()

            // ~70ms delay = ~14 FPS
            captureHandler?.postDelayed({
                scheduleNextCapture()
            }, 70)
        }
    }

    private fun captureFrame() {
        try {
            val result = optimizedCapture?.captureOptimized() ?: return
            val bitmap = result.bitmap

            // Рассчитываем необходимый размер буфера (RGBA_8888 = 4 байта на пиксель)
            val requiredCapacity = bitmap.width * bitmap.height * 4

            // Создаем буфер только один раз (или если размер экрана изменился)
            if (sharedBuffer == null || sharedBuffer!!.capacity() < requiredCapacity) {
                Log.d(TAG, "Allocating new ByteBuffer of size: $requiredCapacity")
                sharedBuffer = ByteBuffer.allocateDirect(requiredCapacity)
            }

            val buffer = sharedBuffer!!
            buffer.clear() // Сбрасываем позицию буфера к 0
            
            // Копируем пиксели напрямую из Bitmap в ByteBuffer
            bitmap.copyPixelsToBuffer(buffer)
            buffer.rewind() // Сбрасываем позицию перед чтением в Rust

            // Отправляем в Rust ТАК ЖЕ как MediaProjection!
            FFI.onVideoFrameUpdate(buffer)

            // Освобождаем память Bitmap, так как данные уже в буфере
            bitmap.recycle()

        } catch (e: Exception) {
            Log.e(TAG, "Capture error", e)
        }
    }
}

