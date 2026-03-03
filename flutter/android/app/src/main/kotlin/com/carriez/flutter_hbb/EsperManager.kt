package com.carriez.flutter_hbb

import android.content.Context
import android.util.Log
import io.esper.devicesdk.EsperDeviceSDK

/**
 * Esper MDM Manager для RustDesk
 * 
 * С правильными константами AppOps из документации:
 * - OP_SYSTEM_ALERT_WINDOW = 24 (Draw over other apps)
 * - OP_WRITE_SETTINGS = 23 (System settings)
 * - OP_GET_USAGE_STATS = 43 (Usage stats)
 */
object EsperManager {
    private const val TAG = "EsperManager"
    
    // AppOps константы (из документации Esper)
    private const val OP_WRITE_SETTINGS = 23
    private const val OP_SYSTEM_ALERT_WINDOW = 24
    private const val OP_GET_USAGE_STATS = 43
    private const val OP_PROJECT_MEDIA = 46
    
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
                        Log.d(TAG, "Device Info:")
                        Log.d(TAG, "  ID: ${it.deviceId}")
                        Log.d(TAG, "  Serial: ${it.serialNo}")
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
     * Дать SYSTEM_ALERT_WINDOW разрешение (OP_SYSTEM_ALERT_WINDOW = 24)
     * Для MaintenanceOverlay - "Draw over other apps"
     */
    fun grantOverlayPermission(callback: (Boolean) -> Unit) {
        if (!activated) {
            callback(false)
            return
        }
        
        try {
            Log.d(TAG, "Выдача SYSTEM_ALERT_WINDOW (24) разрешения...")
            
            sdk?.setAppOpMode(
                OP_SYSTEM_ALERT_WINDOW,
                true,
                object : EsperDeviceSDK.Callback<Void> {
                    override fun onResponse(response: Void?) {
                        Log.i(TAG, "✓ SYSTEM_ALERT_WINDOW разрешен (Draw over other apps)")
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
     * Дать WRITE_SETTINGS разрешение (OP_WRITE_SETTINGS = 23)
     * Для изменения системных настроек
     */
    fun grantWriteSettingsPermission(callback: (Boolean) -> Unit) {
        if (!activated) {
            callback(false)
            return
        }
        
        try {
            Log.d(TAG, "Выдача WRITE_SETTINGS (23) разрешения...")
            
            sdk?.setAppOpMode(
                OP_WRITE_SETTINGS,
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
     * Дать USAGE_STATS разрешение (OP_GET_USAGE_STATS = 43)
     * Для получения статистики использования
     */
    fun grantUsageStatsPermission(callback: (Boolean) -> Unit) {
        if (!activated) {
            callback(false)
            return
        }
        
        try {
            Log.d(TAG, "Выдача GET_USAGE_STATS (43) разрешения...")
            
            sdk?.setAppOpMode(
                OP_GET_USAGE_STATS,
                true,
                object : EsperDeviceSDK.Callback<Void> {
                    override fun onResponse(response: Void?) {
                        Log.i(TAG, "✓ GET_USAGE_STATS разрешен")
                        callback(true)
                    }
                    
                    override fun onFailure(t: Throwable) {
                        Log.e(TAG, "✗ Ошибка GET_USAGE_STATS: ${t.message}")
                        callback(false)
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception granting usage stats", e)
            callback(false)
        }
    }
    
    /**
     * Дать PROJECT_MEDIA разрешение (OP_PROJECT_MEDIA = 46)
     * Для проецирования экрана
     */
    fun grantProjectMediaPermission(callback: (Boolean) -> Unit) {
        if (!activated) {
            callback(false)
            return
        }
        
        try {
            Log.d(TAG, "Выдача PROJECT_MEDIA (46) разрешения...")
            
            sdk?.setAppOpMode(
                OP_PROJECT_MEDIA,
                true,
                object : EsperDeviceSDK.Callback<Void> {
                    override fun onResponse(response: Void?) {
                        Log.i(TAG, "✓ PROJECT_MEDIA разрешен")
                        callback(true)
                    }
                    
                    override fun onFailure(t: Throwable) {
                        Log.e(TAG, "✗ Ошибка PROJECT_MEDIA: ${t.message}")
                        callback(false)
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception granting project media", e)
            callback(false)
        }
    }
    
    /**
     * Дать ВСЕ AppOps разрешения для RustDesk
     */
    fun grantAllAppOpsPermissions(callback: (Boolean) -> Unit) {
        if (!activated) {
            callback(false)
            return
        }
        
        Log.i(TAG, "═══════════════════════════════════")
        Log.i(TAG, "Выдача всех AppOps разрешений...")
        Log.i(TAG, "═══════════════════════════════════")
        
        var completed = 0
        var successful = 0
        val total = 4 // 4 разрешения
        
        fun checkCompletion() {
            if (completed == total) {
                Log.i(TAG, "═══════════════════════════════════")
                Log.i(TAG, "AppOps: $successful/$total успешно")
                Log.i(TAG, "═══════════════════════════════════")
                callback(successful == total)
            }
        }
        
        // 1. SYSTEM_ALERT_WINDOW (для MaintenanceOverlay!)
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
        
        // 3. GET_USAGE_STATS
        grantUsageStatsPermission { success ->
            completed++
            if (success) successful++
            checkCompletion()
        }
        
        // 4. PROJECT_MEDIA (для screen projection)
        grantProjectMediaPermission { success ->
            completed++
            if (success) successful++
            checkCompletion()
        }
    }
    
    /**
     * Установить системную настройку
     * 
     * @param key - ключ настройки (см. Android System Settings)
     * @param value - значение настройки
     */
    fun setSystemSetting(key: String, value: String, callback: (Boolean) -> Unit) {
        if (!activated) {
            callback(false)
            return
        }
        
        try {
            Log.d(TAG, "Установка системной настройки: $key = $value")
            
            sdk?.setSystemSetting(
                key,
                value,
                object : EsperDeviceSDK.Callback<Boolean> {
                    override fun onResponse(response: Boolean?) {
                        val success = response ?: false
                        Log.i(TAG, "${if (success) "✓" else "✗"} Настройка $key = $value")
                        callback(success)
                    }
                    
                    override fun onFailure(t: Throwable) {
                        Log.e(TAG, "✗ Ошибка установки $key: ${t.message}")
                        callback(false)
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception setting system setting", e)
            callback(false)
        }
    }
    
    /**
     * Отключить Battery Optimization
     */
    fun disableBatteryOptimization(packageName: String = "com.carriez.flutter_hbb") {
        if (!activated) return
        
        try {
            Log.d(TAG, "Отключение Battery Optimization для $packageName...")
            
            // Через system settings можно попробовать
            setSystemSetting("battery_optimization_${packageName}", "0") { success ->
                if (success) {
                    Log.i(TAG, "✓ Battery optimization отключен")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception disabling battery opt", e)
        }
    }
    
    /**
     * Включить Accessibility Service программно (если SDK поддерживает)
     */
    fun enableAccessibilityService(callback: (Boolean) -> Unit) {
        if (!activated) {
            callback(false)
            return
        }
        
        try {
            // Пытаемся через system settings
            val component = "com.carriez.flutter_hbb/.InputService"
            setSystemSetting("enabled_accessibility_services", component) { success ->
                if (success) {
                    Log.i(TAG, "✓ Accessibility Service включен через settings")
                    
                    // Также включаем флаг accessibility
                    setSystemSetting("accessibility_enabled", "1") { _ ->
                        callback(true)
                    }
                } else {
                    callback(false)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception enabling accessibility", e)
            callback(false)
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
                status["appops_supported"] = true
            }
        } catch (e: Exception) {
            status["error"] = e.message ?: "unknown"
        }
        
        return status
    }
}
