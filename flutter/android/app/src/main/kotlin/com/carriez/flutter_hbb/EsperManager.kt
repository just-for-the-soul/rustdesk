package com.carriez.flutter_hbb

import android.content.Context
import android.util.Log
import io.esper.devicesdk.EsperDeviceSDK
import io.esper.devicesdk.constants.AppOpsPermissions

/**
 * Esper MDM Manager для RustDesk
 * 
 * Использует актуальные методы Esper Device SDK
 * Версия SDK: 3.3.0101.27+
 */
object EsperManager {
    private const val TAG = "EsperManager"
    
    private var sdk: EsperDeviceSDK? = null
    private var isInitialized = false
    
    /**
     * Инициализация SDK (БЕЗ API ключа для MDM устройств)
     */
    fun initialize(context: Context): Boolean {
        return try {
            sdk = EsperDeviceSDK.getInstance(context.applicationContext)
            
            val activated = sdk?.isActivated ?: false
            isInitialized = activated
            
            if (activated) {
                Log.i(TAG, "═══════════════════════════════════")
                Log.i(TAG, "✓ Esper SDK АКТИВИРОВАН!")
                Log.i(TAG, "  Устройство под управлением MDM")
                Log.i(TAG, "═══════════════════════════════════")
                logDeviceInfo()
            } else {
                Log.w(TAG, "⚠️  Esper SDK НЕ активирован")
                Log.w(TAG, "Установите APK через Esper Console")
            }
            
            activated
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка инициализации Esper SDK", e)
            isInitialized = false
            false
        }
    }
    
    /**
     * Проверка доступности Esper
     */
    fun isAvailable(): Boolean = isInitialized
    
    /**
     * Информация об устройстве
     */
    private fun logDeviceInfo() {
        try {
            sdk?.getEsperDeviceInfo(object : EsperDeviceSDK.Callback<io.esper.devicesdk.models.EsperDeviceInfo> {
                override fun onResponse(response: io.esper.devicesdk.models.EsperDeviceInfo?) {
                    response?.let { info ->
                        Log.d(TAG, "Device Info:")
                        Log.d(TAG, "  ID: ${info.deviceId}")
                        Log.d(TAG, "  Serial: ${info.serialNo}")
                        Log.d(TAG, "  IMEI: ${info.imei1}")
                    }
                }
                
                override fun onFailure(t: Throwable) {
                    Log.e(TAG, "Error getting device info", t)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Exception in logDeviceInfo", e)
        }
    }
    
    /**
     * Дать Runtime разрешения (обычные)
     */
    fun grantRuntimePermissions(packageName: String = "com.carriez.flutter_hbb", callback: (Boolean) -> Unit) {
        if (!isAvailable()) {
            Log.w(TAG, "SDK недоступен")
            callback(false)
            return
        }
        
        val permissions = arrayOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
        )
        
        Log.d(TAG, "Выдача ${permissions.size} runtime разрешений...")
        
        var granted = 0
        permissions.forEach { permission ->
            try {
                sdk?.grantRuntimePermission(
                    packageName,
                    permission,
                    object : EsperDeviceSDK.Callback<Void> {
                        override fun onResponse(response: Void?) {
                            granted++
                            Log.d(TAG, "  ✓ $permission")
                            if (granted == permissions.size) {
                                callback(true)
                            }
                        }
                        
                        override fun onFailure(t: Throwable) {
                            Log.e(TAG, "  ✗ $permission: ${t.message}")
                            granted++
                            if (granted == permissions.size) {
                                callback(false)
                            }
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Exception granting $permission", e)
                granted++
            }
        }
    }
    
    /**
     * Дать AppOps разрешения (специальные)
     */
    fun grantAppOpsPermissions(callback: (Boolean) -> Unit) {
        if (!isAvailable()) {
            callback(false)
            return
        }
        
        Log.d(TAG, "Выдача AppOps разрешений...")
        
        var completed = 0
        var successful = 0
        val totalOps = 2 // SYSTEM_ALERT_WINDOW + WRITE_SETTINGS
        
        // 1. SYSTEM_ALERT_WINDOW (для MaintenanceOverlay!)
        try {
            sdk?.setAppOpMode(
                AppOpsPermissions.OP_SYSTEM_ALERT_WINDOW,
                true,
                object : EsperDeviceSDK.Callback<Void> {
                    override fun onResponse(response: Void?) {
                        completed++
                        successful++
                        Log.d(TAG, "  ✓ SYSTEM_ALERT_WINDOW (overlay)")
                        checkCompletion()
                    }
                    
                    override fun onFailure(t: Throwable) {
                        completed++
                        Log.e(TAG, "  ✗ SYSTEM_ALERT_WINDOW: ${t.message}")
                        checkCompletion()
                    }
                }
            )
        } catch (e: Exception) {
            completed++
            Log.e(TAG, "Exception setting SYSTEM_ALERT_WINDOW", e)
        }
        
        // 2. WRITE_SETTINGS
        try {
            sdk?.setAppOpMode(
                AppOpsPermissions.OP_WRITE_SETTINGS,
                true,
                object : EsperDeviceSDK.Callback<Void> {
                    override fun onResponse(response: Void?) {
                        completed++
                        successful++
                        Log.d(TAG, "  ✓ WRITE_SETTINGS")
                        checkCompletion()
                    }
                    
                    override fun onFailure(t: Throwable) {
                        completed++
                        Log.e(TAG, "  ✗ WRITE_SETTINGS: ${t.message}")
                        checkCompletion()
                    }
                }
            )
        } catch (e: Exception) {
            completed++
            Log.e(TAG, "Exception setting WRITE_SETTINGS", e)
        }
        
        fun checkCompletion() {
            if (completed == totalOps) {
                Log.i(TAG, "AppOps: $successful/$totalOps успешно")
                callback(successful == totalOps)
            }
        }
    }
    
    /**
     * Дать ВСЕ разрешения (Runtime + AppOps)
     */
    fun grantAllPermissions(callback: (Boolean) -> Unit) {
        if (!isAvailable()) {
            callback(false)
            return
        }
        
        Log.i(TAG, "═══════════════════════════════════")
        Log.i(TAG, "Выдача ВСЕХ разрешений...")
        Log.i(TAG, "═══════════════════════════════════")
        
        // Сначала Runtime
        grantRuntimePermissions { runtimeSuccess ->
            // Затем AppOps
            grantAppOpsPermissions { appOpsSuccess ->
                val allSuccess = runtimeSuccess && appOpsSuccess
                Log.i(TAG, "═══════════════════════════════════")
                Log.i(TAG, if (allSuccess) "✓ ВСЕ РАЗРЕШЕНИЯ ВЫДАНЫ else "⚠️  Не все разрешения выданы")
                Log.i(TAG, "═══════════════════════════════════")
                callback(allSuccess)
            }
        }
    }
    
    /**
     * Включить Accessibility Service
     */
    fun enableAccessibilityService(callback: (Boolean) -> Unit) {
        if (!isAvailable()) {
            callback(false)
            return
        }
        
        try {
            val component = "com.carriez.flutter_hbb/.InputService"
            
            sdk?.enableAccessibilityService(
                component,
                object : EsperDeviceSDK.Callback<Void> {
                    override fun onResponse(response: Void?) {
                        Log.i(TAG, "✓ Accessibility Service включен!")
                        callback(true)
                    }
                    
                    override fun onFailure(t: Throwable) {
                        Log.e(TAG, "Ошибка включения Accessibility: ${t.message}")
                        callback(false)
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception enabling accessibility", e)
            callback(false)
        }
    }
    
    /**
     * Исключить из Battery Optimization
     */
    fun disableBatteryOptimization(packageName: String = "com.carriez.flutter_hbb") {
        if (!isAvailable()) return
        
        try {
            sdk?.addAppToPowerWhitelist(
                packageName,
                object : EsperDeviceSDK.Callback<Void> {
                    override fun onResponse(response: Void?) {
                        Log.i(TAG, "✓ Battery optimization отключен")
                    }
                    
                    override fun onFailure(t: Throwable) {
                        Log.e(TAG, "Ошибка battery optimization: ${t.message}")
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception disabling battery opt", e)
        }
    }
    
    /**
     * Включить Kiosk Mode (держать RustDesk всегда запущенным)
     */
    fun enableKioskMode(packageName: String = "com.carriez.flutter_hbb") {
        if (!isAvailable()) return
        
        try {
            sdk?.setKioskApp(
                packageName,
                null, // null = multi-app kiosk mode
                object : EsperDeviceSDK.Callback<Void> {
                    override fun onResponse(response: Void?) {
                        Log.i(TAG, "✓ Kiosk Mode включен")
                    }
                    
                    override fun onFailure(t: Throwable) {
                        Log.e(TAG, "Ошибка Kiosk Mode: ${t.message}")
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception enabling kiosk", e)
        }
    }
    
    /**
     * Выключить Kiosk Mode
     */
    fun disableKioskMode() {
        if (!isAvailable()) return
        
        try {
            sdk?.clearKioskApp(object : EsperDeviceSDK.Callback<Void> {
                override fun onResponse(response: Void?) {
                    Log.i(TAG, "✓ Kiosk Mode выключен")
                }
                
                override fun onFailure(t: Throwable) {
                    Log.e(TAG, "Ошибка выключения Kiosk: ${t.message}")
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Exception disabling kiosk", e)
        }
    }
    
    /**
     * Перезагрузить устройство
     */
    fun rebootDevice() {
        if (!isAvailable()) return
        
        try {
            sdk?.reboot(object : EsperDeviceSDK.Callback<Void> {
                override fun onResponse(response: Void?) {
                    Log.i(TAG, "Устройство перезагружается...")
                }
                
                override fun onFailure(t: Throwable) {
                    Log.e(TAG, "Ошибка перезагрузки: ${t.message}")
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Exception rebooting", e)
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
            Log.e(TAG, "Ошибка запуска RustDesk", e)
        }
    }
    
    /**
     * Получить статус для Flutter
     */
    fun getStatus(): Map<String, Any> {
        val status = mutableMapOf<String, Any>()
        
        try {
            status["available"] = isAvailable()
            status["activated"] = sdk?.isActivated ?: false
            
            if (isAvailable()) {
                // Асинхронно получаем device info, пока возвращаем базовый статус
                status["device_id"] = "pending..."
            }
        } catch (e: Exception) {
            status["error"] = e.message ?: "unknown"
        }
        
        return status
    }
}
