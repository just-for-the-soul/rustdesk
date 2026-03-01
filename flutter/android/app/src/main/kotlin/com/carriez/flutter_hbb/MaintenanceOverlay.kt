package com.carriez.flutter_hbb

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * ЗАГЛУШКА "Under Maintenance" на экране телефона
 * 
 * Показывается когда идет удаленное управление
 * Скрывает содержимое экрана от пользователя телефона
 */
object MaintenanceOverlay {
    private val TAG = "MaintenanceOverlay"
    
    private var overlayView: LinearLayout? = null
    private var windowManager: WindowManager? = null
    private var isShowing = false
    
    /**
     * Показать заглушку
     */
    fun show(context: Context) {
        if (isShowing) {
            Log.d(TAG, "Already showing")
            return
        }
        
        try {
            windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            
            // Создаем view
            overlayView = createOverlayView(context)
            
            // Параметры window
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            
            params.gravity = Gravity.TOP or Gravity.START
            params.x = 0
            params.y = 0
            
            // Добавляем на экран
            windowManager?.addView(overlayView, params)
            isShowing = true
            
            Log.i(TAG, "Maintenance overlay shown")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show overlay", e)
        }
    }
    
    /**
     * Скрыть заглушку
     */
    fun hide() {
        if (!isShowing) {
            return
        }
        
        try {
            overlayView?.let {
                windowManager?.removeView(it)
            }
            overlayView = null
            windowManager = null
            isShowing = false
            
            Log.i(TAG, "Maintenance overlay hidden")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hide overlay", e)
        }
    }
    
    /**
     * Создать view с заглушкой
     */
    private fun createOverlayView(context: Context): LinearLayout {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#1E3A8A")) // Темно-синий
            setPadding(40, 40, 40, 40)
        }
        
        // Иконка
        val icon = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_dialog_info)
            layoutParams = LinearLayout.LayoutParams(
                200, 200
            ).apply {
                gravity = Gravity.CENTER
                bottomMargin = 40
            }
            setColorFilter(Color.WHITE)
        }
        layout.addView(icon)
        
        // Заголовок
        val title = TextView(context).apply {
            text = "Device Under Remote Administration"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 20
            }
        }
        layout.addView(title)
        
        // Описание
        val description = TextView(context).apply {
            text = "This company device is currently being managed by IT administrator.\n\n" +
                   "Please do not use the device until the session is complete.\n\n" +
                   "Это служебный телефон компании, сейчас проводится администрирование.\n\n" +
                   "Пожалуйста, не пользуйтесь телефоном до завершения сеанса."
            textSize = 16f
            setTextColor(Color.parseColor("#BFDBFE")) // Светло-синий
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 40
            }
        }
        layout.addView(description)
        
        // Анимированная точка (опционально)
        val status = TextView(context).apply {
            text = "⬤ Session Active"
            textSize = 14f
            setTextColor(Color.parseColor("#22C55E")) // Зеленый
            gravity = Gravity.CENTER
        }
        layout.addView(status)
        
        return layout
    }
    
    /**
     * Проверить показан ли overlay
     */
    fun isShowing(): Boolean = isShowing
}


