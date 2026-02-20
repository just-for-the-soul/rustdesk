package com.carriez.flutter_hbb

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream

/**
 * XML-based screen capture через AccessibilityService
 * Не требует MediaProjection permission!
 * 
 */
class XmlScreenCapture(private val service: AccessibilityService) {
    private val TAG = "XmlScreenCapture"
    private val paint = Paint()
    
    /**
     * Захватывает экран через XML иерархию
     * 
     * @return Bitmap с отрисованным экраном
     */
    fun captureScreen(): Bitmap? {
        try {
            val rootNode = service.rootInActiveWindow
            if (rootNode == null) {
                Log.w(TAG, "No active window root")
                return null
            }
            
            // Получаем размеры экрана
            val displayMetrics = service.resources.displayMetrics
            val width = displayMetrics.widthPixels
            val height = displayMetrics.heightPixels
            
            Log.d(TAG, "Capturing screen: ${width}x${height}")
            
            // Создаем Bitmap
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            
            // Белый фон
            canvas.drawColor(Color.WHITE)
            
            // Рекурсивно отрисовываем все элементы
            drawNodeRecursive(canvas, rootNode)
            
            rootNode.recycle()
            
            Log.d(TAG, "Screen captured successfully")
            return bitmap
            
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing screen", e)
            return null
        }
    }
    
    /**
     * Рекурсивно отрисовывает AccessibilityNodeInfo
     */
    private fun drawNodeRecursive(canvas: Canvas, node: AccessibilityNodeInfo) {
        try {
            // Получаем границы элемента на экране
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            
            // Рисуем элемент
            drawNode(canvas, node, bounds)
            
            // Рекурсивно отрисовываем детей
            val childCount = node.childCount
            for (i in 0 until childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    drawNodeRecursive(canvas, child)
                    child.recycle()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error drawing node", e)
        }
    }
    
    /**
     * Отрисовывает отдельный UI элемент
     */
    private fun drawNode(canvas: Canvas, node: AccessibilityNodeInfo, bounds: Rect) {
        if (bounds.isEmpty) return
        
        // Получаем свойства элемента
        val className = node.className?.toString() ?: ""
        val text = node.text?.toString()
        val contentDescription = node.contentDescription?.toString()
        val isClickable = node.isClickable
        val isVisible = node.isVisibleToUser
        
        if (!isVisible) return
        
        // Определяем как рисовать в зависимости от типа элемента
        when {
            className.contains("Button") -> drawButton(canvas, bounds, text)
            className.contains("TextView") || className.contains("EditText") -> 
                drawText(canvas, bounds, text ?: contentDescription)
            className.contains("ImageView") || className.contains("ImageButton") -> 
                drawImage(canvas, bounds, contentDescription)
            className.contains("View") -> drawView(canvas, bounds, isClickable)
            else -> drawGenericElement(canvas, bounds)
        }
    }
    
    private fun drawButton(canvas: Canvas, bounds: Rect, text: String?) {
        // Фон кнопки
        paint.color = Color.LTGRAY
        paint.style = Paint.Style.FILL
        canvas.drawRect(bounds, paint)
        
        // Обводка
        paint.color = Color.DKGRAY
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        canvas.drawRect(bounds, paint)
        
        // Текст
        text?.let { drawTextCentered(canvas, bounds, it, Color.BLACK) }
    }
    
    private fun drawText(canvas: Canvas, bounds: Rect, text: String?) {
        text?.let {
            paint.color = Color.BLACK
            paint.textSize = Math.min(bounds.height() * 0.8f, 48f)
            paint.style = Paint.Style.FILL
            paint.textAlign = Paint.Align.LEFT
            
            // Рисуем текст с отступом
            val x = bounds.left + 8f
            val y = bounds.centerY() + paint.textSize / 3
            canvas.drawText(it, x, y, paint)
        }
    }
    
    private fun drawImage(canvas: Canvas, bounds: Rect, description: String?) {
        // Placeholder для изображений
        paint.color = Color.LTGRAY
        paint.style = Paint.Style.FILL
        canvas.drawRect(bounds, paint)
        
        // Обводка
        paint.color = Color.GRAY
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        canvas.drawRect(bounds, paint)
        
        // Текст описания если есть
        description?.let {
            drawTextCentered(canvas, bounds, it, Color.DKGRAY)
        }
    }
    
    private fun drawView(canvas: Canvas, bounds: Rect, isClickable: Boolean) {
        if (isClickable) {
            // Кликабельные элементы выделяем
            paint.color = Color.rgb(230, 230, 250)
            paint.style = Paint.Style.FILL
            canvas.drawRect(bounds, paint)
            
            paint.color = Color.rgb(200, 200, 220)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            canvas.drawRect(bounds, paint)
        } else {
            // Обычные View - просто обводка
            paint.color = Color.rgb(240, 240, 240)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 0.5f
            canvas.drawRect(bounds, paint)
        }
    }
    
    private fun drawGenericElement(canvas: Canvas, bounds: Rect) {
        // Легкая обводка для отладки
        paint.color = Color.rgb(245, 245, 245)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.5f
        canvas.drawRect(bounds, paint)
    }
    
    private fun drawTextCentered(canvas: Canvas, bounds: Rect, text: String, color: Int) {
        paint.color = color
        paint.textSize = Math.min(bounds.height() * 0.6f, 32f)
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        
        val x = bounds.centerX().toFloat()
        val y = bounds.centerY() + paint.textSize / 3
        canvas.drawText(text, x, y, paint)
    }
    
    /**
     * Конвертирует Bitmap в JPEG byte array
     */
    fun bitmapToJpeg(bitmap: Bitmap, quality: Int = 85): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return stream.toByteArray()
    }
    
    /**
     * Получает XML дерево экрана (для дебага)
     */
    fun getScreenXml(): String {
        val rootNode = service.rootInActiveWindow ?: return "<error>No root</error>"
        
        val xml = StringBuilder()
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        buildXmlRecursive(xml, rootNode, 0)
        
        rootNode.recycle()
        return xml.toString()
    }
    
    private fun buildXmlRecursive(xml: StringBuilder, node: AccessibilityNodeInfo, depth: Int) {
        val indent = "  ".repeat(depth)
        val className = node.className?.toString() ?: "Unknown"
        val text = node.text?.toString() ?: ""
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        
        xml.append("$indent<$className")
        if (text.isNotEmpty()) xml.append(" text=\"$text\"")
        xml.append(" bounds=\"$bounds\"")
        
        val childCount = node.childCount
        if (childCount > 0) {
            xml.append(">\n")
            for (i in 0 until childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    buildXmlRecursive(xml, child, depth + 1)
                    child.recycle()
                }
            }
            xml.append("$indent</$className>\n")
        } else {
            xml.append(" />\n")
        }
    }
}

