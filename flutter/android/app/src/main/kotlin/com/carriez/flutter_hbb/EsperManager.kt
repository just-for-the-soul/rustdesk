package com.carriez.flutter_hbb

import android.content.Context
import android.util.Log
import io.esper.devicesdk.EsperDeviceSDK
import io.esper.devicesdk.constants.AppOpsPermissions

/**
 * Esper MDM Manager для RustDesk
 * Упрощенная версия с минимальными методами
 */
object EsperManager {
    private const val TAG = "EsperManager"
    
    private var sdk: EsperDeviceSDK? = null
    private var activated = false
    
    /**
     * Инициализация SDK
     */
    fun initialize(context: Context): Boolean {
        return try {
            sdk = EsperDeviceSDK.getInstance(context.applicationContext)
            
            // Проверяем активацию
            activated = checkActivation()
            
            if (activated) {
                Log.i(TAG, "═══════════════════════════════════")
                Log.i(TAG, "✓ Esper SDK АКТИВИРОВАН!")
                Log.i(TAG, "═══════════════════════════════════")
                getDeviceInfo()
            } else {
                Log.w(TAG, "⚠️  Esper SDK не активирован")
                Log.w(TAG, "Установите APK через Esper Console")
            }
            
            activated
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка инициализации SDK", e)
            activated = false
            false
        }
    }
    
    /**
     * Проверка активации SDK
     */
    private fun checkActivation(): Boolean {
        return try {
            val method = sdk?.javaClass?.getMethod("isActivated")
            val result = method?.invoke(sdk)
            result as? Boolean ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking activation", e)
            false
        }
    }
    
    /**
     * Проверка доступности
     */
    fun isAvailable(): Boolean = activated
    
    /**
     * Получить информацию об устройстве
     */
    private fun getDeviceInfo() {
        try {
            sdk?.getEsperDeviceInfo(object : EsperDeviceSDK.Callback<io.esper.devicesdk.models.EsperDeviceInfo> {
                override fun onResponse(response: io.esper.devicesdk.models.EsperDeviceInfo?) {
                    response?.let {
                        Log.d(TAG, "Device ID: ${it.deviceId}")
                        Log.d(TAG, "Serial: ${it.serialNo}")
                    }
                }
                
                override fun onFailure(t: Throwable) {
                    Log.e(TAG, "Error getting device info", t)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Exception in getDeviceInfo", e)
        }
    }
    
    /**
     * Дать SYSTEM_ALERT_WINDOW разрешение через AppOps
     * (для MaintenanceOverlay)
     */
    fun grantOverlayPermission(callback: (Boolean) -> Unit) {
        if (!activated) {
            callback(false)
            return
        }
        
        try {
            Log.d(TAG, "Выдача SYSTEM_ALERT_WINDOW разрешения...")
            
            sdk?.setAppOpMode(
                AppOpsPermissions.OP_SYSTEM_ALERT_WINDOW,
                true,
                object : EsperDeviceSDK.Callback<Void> {
                    override fun onResponse(response: Void?) {
                        Log.i(TAG, "✓ SYSTEM_ALERT_WINDOW разрешен")
                        callback(true)
                    }
                    
                    override fun onFailure(t: Throwable) {
                        Log.e(TAG, "✗ Ошибка SYSTEM_ALERT_WINDOW: ${t.message}")
                        callback(false)
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception granting overlay", e)
            callback(false)
        }
    }
    
    /**
     * Дать WRITE_SETTINGS разрешение
     */
    fun grantWriteSettingsPermission(callback: (Boolean) -> Unit) {
        if (!activated) {
            callback(false)
            return
        }
        
        try {
            sdk?.setAppOpMode(
                AppOpsPermissions.OP_WRITE_SETTINGS,
                true,
                object : EsperDeviceSDK.Callback<Void> {
                    override fun onResponse(response: Void?) {
                        Log.i(TAG, "✓ WRITE_SETTINGS разрешен")
                        callback(true)
                    }
                    
                    override fun onFailure(t: Throwable) {
                        Log.e(TAG, "✗ Ошибка WRITE_SETTINGS: ${t.message}")
                        callback(false)
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception granting write settings", e)
            callback(false)
        }
    }
    
    /**
     * Дать все AppOps разрешения
     */
    fun grantAllAppOpsPermissions(callback: (Boolean) -> Unit) {
        if (!activated) {
            callback(false)
            return
        }
        
        Log.i(TAG, "Выдача всех AppOps разрешений...")
        
        var completed = 0
        var successful = 0
        val total = 2
        
        fun checkCompletion() {
            if (completed == total) {
                Log.i(TAG, "AppOps: $successful/$total успешно")
                callback(successful == total)
            }
        }
        
        // 1. SYSTEM_ALERT_WINDOW
        grantOverlayPermission { success ->
            completed++
            if (success) successful++
            checkCompletion()
        }
        
        // 2. WRITE_SETTINGS
        grantWriteSettingsPermission { success ->
            completed++
            if (success) successful++
            checkCompletion()
        }
    }
    
    /**
     * Запустить RustDesk
     */
    fun launchRustDesk(context: Context) {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(
                "com.carriez.flutter_hbb"
            )
            intent?.let {
                it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(it)
                Log.i(TAG, "✓ RustDesk запущен")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка запуска", e)
        }
    }
    
    /**
     * Получить статус
     */
    fun getStatus(): Map<String, Any> {
        val status = mutableMapOf<String, Any>()
        
        try {
            status["available"] = activated
            status["activated"] = activated
            
            if (activated) {
                status["device_id"] = "available"
                status["sdk_ready"] = true
            }
        } catch (e: Exception) {
            status["error"] = e.message ?: "unknown"
        }
        
        return status
    }
}
