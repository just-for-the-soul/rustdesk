package com.carriez.flutter_hbb

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * Helper для работы с Accessibility Settings
 */
object AccessibilityHelper {
    private const val TAG = "AccessibilityHelper"
    
    /**
     * Открывает настройки Accessibility (только настройки, без других запросов)
     */
    fun openAccessibilitySettings(context: Context) {
        Log.d(TAG, "Opening Accessibility Settings...")
        
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open Accessibility Settings", e)
            
            // Fallback: открыть общие настройки
            try {
                val fallbackIntent = Intent(Settings.ACTION_SETTINGS)
                fallbackIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(fallbackIntent)
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to open Settings at all", e2)
            }
        }
    }
    
    /**
     * Проверяет включен ли Accessibility Service
     */
    fun isAccessibilityEnabled(context: Context): Boolean {
        return InputService.isOpen
    }
}

