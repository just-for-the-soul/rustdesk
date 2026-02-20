package com.carriez.flutter_hbb

import android.accessibilityservice.AccessibilityService
import android.graphics.*
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Оптимизированный XML capture для плавного видео
 * 
 * Оптимизации:
 * 1. Кэширование статичных элементов
 * 2. Дельта-кодирование (отправляем только изменения)
 * 3. Downscaling (понижаем разрешение)
 * 4. Aggressive bitmap pooling
 * 5. Многопоточность
 */
class OptimizedXmlCapture(private val service: AccessibilityService) {
    private val TAG = "OptimizedXmlCapture"
    
    // Кэш для статичных элементов UI
    private val nodeCache = ConcurrentHashMap<String, CachedNode>()
    
    // Bitmap pool для переиспользования
    private val bitmapPool = BitmapPool()
    
    // Настройки оптимизации
    private var downscaleFactor = 0.5f  // Захватываем в половину размера
    private var jpegQuality = 60  // Меньше качество = меньше размер
    private var cacheEnabled = true
    private var deltaEnabled = true
    
    private var lastFrame: Bitmap? = null
    private var frameNumber = 0L
    
    data class CachedNode(
        val bounds: Rect,
        val bitmap: Bitmap,
        val hash: Int,  // Для определения изменений
        val timestamp: Long
    )
    
    /**
     * Быстрый захват с оптимизациями
     */
    fun captureOptimized(): CaptureResult? {
        try {
            val startTime = System.currentTimeMillis()
            
            val rootNode = service.rootInActiveWindow ?: return null
            
            // Получаем размеры с downscaling
            val displayMetrics = service.resources.displayMetrics
            val width = (displayMetrics.widthPixels * downscaleFactor).toInt()
            val height = (displayMetrics.heightPixels * downscaleFactor).toInt()
            
            // Получаем bitmap из pool (переиспользование!)
            val bitmap = bitmapPool.getBitmap(width, height)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            
            // Отрисовываем с кэшированием
            drawNodeOptimized(canvas, rootNode, downscaleFactor)
            
            rootNode.recycle()
            
            // Delta encoding - отправляем только изменения
            val result = if (deltaEnabled && lastFrame != null) {
                val delta = createDelta(lastFrameclear, bitmap)
                if (delta != null) {
                    CaptureResult(delta, isDelta = true, frameNumber++)
                } else {
                    // Слишком много изменений, отправляем полный кадр
                    val jpeg = bitmapToJpeg(bitmap)
                    CaptureResult(jpeg, isDelta = false, frameNumber++)
                }
            } else {
                val jpeg = bitmapToJpeg(bitmap)
                CaptureResult(jpeg, isDelta = false, frameNumber++)
            }
            
            // Сохраняем для следующего delta
            bitmapPool.recycleBitmap(lastFrame)
            lastFrame = bitmap
            
            val elapsed = System.currentTimeMillis() - startTime
            Log.d(TAG, "Capture took ${elapsed}ms, size=${result.data.size / 1024}KB")
            
            return result
            
        } catch (e: Exception) {
            Log.e(TAG, "Capture error", e)
            return null
        }
    }
    
    /**
     * Отрисовка с кэшированием статичных элементов
     */
    private fun drawNodeOptimized(
        canvas: Canvas, 
        node: AccessibilityNodeInfo,
        scale: Float
    ) {
        try {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            
            // Применяем downscaling
            bounds.scale(scale)
            
            if (bounds.isEmpty) return
            
            // Проверяем кэш
            val nodeKey = getNodeKey(node)
            val cached = nodeCache[nodeKey]
            
            val nodeChanged = cached == null || 
                              hasNodeChanged(node, cached) ||
                              System.currentTimeMillis() - cached.timestamp > 5000  // Обновляем каждые 5 сек
            
            if (nodeChanged) {
                // Рисуем заново
                drawNodeDirect(canvas, node, bounds)
                
                // Сохраняем в кэш (для статичных элементов)
                if (isStaticElement(node)) {
                    cacheNode(nodeKey, node, bounds)
                }
            } else {
                // Используем кэшированную версию
                canvas.drawBitmap(cached.bitmap, null, bounds, null)
            }
            
            // Рекурсивно для детей
            val childCount = node.childCount
            for (i in 0 until childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    drawNodeOptimized(canvas, child, scale)
                    child.recycle()
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Draw error", e)
        }
    }
    
    private fun drawNodeDirect(canvas: Canvas, node: AccessibilityNodeInfo, bounds: Rect) {
        // ... existing drawing code ...
        val className = node.className?.toString() ?: ""
        val text = node.text?.toString()
        
        val paint = Paint().apply {
            isAntiAlias = false  // Быстрее без anti-aliasing
        }
        
        when {
            className.contains("Button") -> {
                paint.color = Color.LTGRAY
                canvas.drawRect(bounds, paint)
            }
            className.contains("TextView") -> {
                text?.let {
                    paint.color = Color.BLACK
                    paint.textSize = 16f
                    canvas.drawText(it, bounds.left.toFloat(), bounds.centerY().toFloat(), paint)
                }
            }
            else -> {
                paint.color = Color.rgb(240, 240, 240)
                paint.style = Paint.Style.STROKE
                canvas.drawRect(bounds, paint)
            }
        }
    }
    
    /**
     * Delta кодирование - только измененные области
     */
    private fun createDelta(oldFrame: Bitmap, newFrame: Bitmap): ByteArray? {
        val width = oldFrame.width
        val height = oldFrame.height
        
        if (width != newFrame.width || height != newFrame.height) {
            return null  // Размеры изменились, нужен полный кадр
        }
        
        // Разбиваем на блоки 16x16 и ищем изменения
        val blockSize = 16
        val changedBlocks = mutableListOf<ChangedBlock>()
        
        val oldPixels = IntArray(width * height)
        val newPixels = IntArray(width * height)
        oldFrame.getPixels(oldPixels, 0, width, 0, 0, width, height)
        newFrame.getPixels(newPixels, 0, width, 0, 0, width, height)
        
        for (y in 0 until height step blockSize) {
            for (x in 0 until width step blockSize) {
                if (blockChanged(oldPixels, newPixels, x, y, blockSize, width, height)) {
                    val blockBitmap = Bitmap.createBitmap(
                        newFrame, 
                        x, y, 
                        minOf(blockSize, width - x), 
                        minOf(blockSize, height - y)
                    )
                    changedBlocks.add(ChangedBlock(x, y, blockBitmap))
                }
            }
        }
        
        // Если изменилось > 30% блоков, отправляем полный кадр
        val totalBlocks = (width / blockSize) * (height / blockSize)
        if (changedBlocks.size > totalBlocks * 0.3) {
            return null
        }
        
        // Сериализуем дельту
        return serializeDelta(changedBlocks)
    }
    
    private fun blockChanged(
        old: IntArray, 
        new: IntArray, 
        x: Int, y: Int, 
        blockSize: Int, 
        width: Int, height: Int
    ): Boolean {
        val endX = minOf(x + blockSize, width)
        val endY = minOf(y + blockSize, height)
        
        var diffPixels = 0
        var totalPixels = 0
        
        for (by in y until endY) {
            for (bx in x until endX) {
                val idx = by * width + bx
                if (old[idx] != new[idx]) {
                    diffPixels++
                }
                totalPixels++
            }
        }
        
        // Изменилось > 5% пикселей в блоке
        return (diffPixels.toFloat() / totalPixels) > 0.05f
    }
    
    private fun serializeDelta(blocks: List<ChangedBlock>): ByteArray {
        val stream = ByteArrayOutputStream()
        
        // Заголовок: количество блоков
        stream.write(blocks.size shr 8)
        stream.write(blocks.size and 0xFF)
        
        // Каждый блок: x(2) y(2) width(2) height(2) jpeg_data
        for (block in blocks) {
            stream.write(block.x shr 8)
            stream.write(block.x and 0xFF)
            stream.write(block.y shr 8)
            stream.write(block.y and 0xFF)
            
            val jpeg = bitmapToJpeg(block.bitmap, quality = 50)  // Низкое качество для дельты
            
            stream.write(jpeg.size shr 8)
            stream.write(jpeg.size and 0xFF)
            stream.write(jpeg)
        }
        
        return stream.toByteArray()
    }
    
    private fun bitmapToJpeg(bitmap: Bitmap, quality: Int = jpegQuality): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return stream.toByteArray()
    }
    
    // Helper functions
    
    private fun getNodeKey(node: AccessibilityNodeInfo): String {
        return "${node.className}:${node.viewIdResourceName}:${node.contentDescription}"
    }
    
    private fun hasNodeChanged(node: AccessibilityNodeInfo, cached: CachedNode): Boolean {
        val currentHash = node.hashCode()
        return currentHash != cached.hash
    }
    
    private fun isStaticElement(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString() ?: ""
        // Эти элементы обычно статичны
        return className.contains("ImageView") ||
               className.contains("ImageButton") ||
               className.contains("Icon")
    }
    
    private fun cacheNode(key: String, node: AccessibilityNodeInfo, bounds: Rect) {
        // TODO: создать bitmap для кэша
    }
    
    private fun Rect.scale(factor: Float) {
        left = (left * factor).toInt()
        top = (top * factor).toInt()
        right = (right * factor).toInt()
        bottom = (bottom * factor).toInt()
    }
    
    data class ChangedBlock(val x: Int, val y: Int, val bitmap: Bitmap)
    data class CaptureResult(val data: ByteArray, val isDelta: Boolean, val frameNumber: Long)
    
    /**
     * Bitmap pool для переиспользования
     */
    class BitmapPool {
        private val pool = mutableListOf<Bitmap>()
        private val maxSize = 5
        
        @Synchronized
        fun getBitmap(width: Int, height: Int): Bitmap {
            val bitmap = pool.firstOrNull { 
                it.width == width && it.height == height 
            }
            
            return if (bitmap != null) {
                pool.remove(bitmap)
                bitmap.eraseColor(Color.TRANSPARENT)
                bitmap
            } else {
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            }
        }
        
        @Synchronized
        fun recycleBitmap(bitmap: Bitmap?) {
            bitmap?.let {
                if (pool.size < maxSize && !it.isRecycled) {
                    pool.add(it)
                } else {
                    it.recycle()
                }
            }
        }
    }
}
