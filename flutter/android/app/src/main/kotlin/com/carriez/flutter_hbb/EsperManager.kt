package com.carriez.flutter_hbb

import android.content.Context
import android.util.Log
import io.esper.devicesdk.EsperDeviceSDK
import io.esper.devicesdk.constants.EsperSDKConstants

/**
 * Менеджер Esper SDK
 * 
 * Для MDM устройств НЕ требует API ключ!
 * SDK автоматически активирован если устройство в Esper Console
 */
object EsperManager {
    private const val TAG = "EsperManager"
    
    private var esperSDK: EsperDeviceSDK? = null
    
    /**
     * Инициализация SDK
     * 
     * Для устройств под Esper MDM ключ НЕ нужен!
     */
    fun initialize(context: Context): Boolean {
        return try {
            esperSDK = EsperDeviceSDK.getInstance(context.applicationContext)
            
            // Проверяем активирован ли SDK
            val isActivated = esperSDK?.isActivated ?: false
            
            if (isActivated) {
                Log.i(TAG, "✓ Esper SDK is ACTIVE (device managed by Esper)")
                logDeviceInfo()
                true
            } else {
                Log.w(TAG, "⚠ Esper SDK not activated (device NOT in Esper Console)")
                Log.w(TAG, "Install app through Esper Console to activate SDK")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Esper SDK", e)
            Log.e(TAG, "This is normal if Esper SDK is not in dependencies")
            false
        }
    }
    
    /**
     * Проверка доступен ли Esper (устройство под MDM?)
     */
    fun isAvailable(): Boolean {
        return try {
            esperSDK?.isActivated == true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Информация об устройстве
     */
    private fun logDeviceInfo() {
        try {
            esperSDK?.getEsperDeviceInfo()?.let { info ->
                Log.d(TAG, "═══════════════════════════════════")
                Log.d(TAG, "Esper Device Info:")
                Log.d(TAG, "  Device ID: ${info.deviceId}")
                Log.d(TAG, "  Serial: ${info.serialNo}")
                Log.d(TAG, "  IMEI: ${info.imei1}")
                Log.d(TAG, "  Android: ${android.os.Build.VERSION.RELEASE}")
                Log.d(TAG, "═══════════════════════════════════")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting device info", e)
        }
    }
    
    /**
     * Дать ВСЕ разрешения автоматически (БЕЗ диалогов!)
     */
    fun grantAllPermissions(packageName: String = "com.carriez.flutter_hbb"): Boolean {
        if (!isAvailable()) {
            Log.w(TAG, "Esper not available")
            return false
        }
        
        val permissions = arrayOf(
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.READ_PHONE_STATE,
            "android.permission.MANAGE_EXTERNAL_STORAGE",
        )
        
        Log.i(TAG, "Granting ${permissions.size} permissions via Esper...")
        
        try {
            permissions.forEach { permission ->
                esperSDK?.grantRuntimePermission(packageName, permission) { success ->
                    Log.d(TAG, "  ${if (success) "✓" else "✗"} $permission")
                }
            }
            
            // Также запрашиваем SYSTEM_ALERT_WINDOW (для overlay)
            grantOverlayPermission(packageName)
            
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error granting permissions", e)
            return false
        }
    }
    
    /**
     * Дать разрешение на Overlay (для MaintenanceOverlay)
     */
    fun grantOverlayPermission(packageName: String = "com.carriez.flutter_hbb") {
        try {
            esperSDK?.grantRuntimePermission(
                packageName,
                android.Manifest.permission.SYSTEM_ALERT_WINDOW
            ) { success ->
                Log.d(TAG, "  ${if (success) "✓" else "✗"} SYSTEM_ALERT_WINDOW")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error granting overlay permission", e)
        }
    }
    
    /**
     * Включить Accessibility Service программно
     */
    fun enableAccessibilityService(): Boolean {
        if (!isAvailable()) return false
        
        try {
            val component = "com.carriez.flutter_hbb/.InputService"
            
            esperSDK?.enableAccessibilityService(component) { success ->
                if (success) {
                    Log.i(TAG, "✓ Accessibility Service enabled via Esper!")
                } else {
                    Log.e(TAG, "Failed to enable Accessibility Service")
                }
            }
            
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error enabling accessibility", e)
            return false
        }
    }
    
    /**
     * Kiosk Mode - держать RustDesk всегда на экране
     */
    fun enableKioskMode(packageName: String = "com.carriez.flutter_hbb") {
        if (!isAvailable()) return
        
        try {
            esperSDK?.setKioskApp(
                packageName,
                EsperSDKConstants.KioskMode.MULTI_APP
            ) { success ->
                if (success) {
                    Log.i(TAG, "✓ Kiosk Mode enabled")
                } else {
                    Log.e(TAG, "Failed to enable Kiosk Mode")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error enabling kiosk", e)
        }
    }
    
    /**
     * Отключить Kiosk Mode
     */
    fun disableKioskMode() {
        try {
            esperSDK?.clearKioskApp { success ->
                Log.i(TAG, if (success) "✓ Kiosk disabled" else "Failed to disable kiosk")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error disabling kiosk", e)
        }
    }
    
    /**
     * Исключить из Battery Optimization через Esper
     */
    fun disableBatteryOptimization(packageName: String = "com.carriez.flutter_hbb") {
        try {
            esperSDK?.addAppToPowerWhitelist(packageName) { success ->
                Log.i(TAG, if (success) 
                    "✓ Battery optimization disabled" else 
                    "Failed to disable battery optimization")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error disabling battery opt", e)
        }
    }
    
    /**
     * Запустить RustDesk программно
     */
    fun launchRustDesk(context: Context) {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(
                "com.carriez.flutter_hbb"
            )
            intent?.let {
                it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(it)
                Log.i(TAG, "✓ RustDesk launched")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error launching app", e)
        }
    }
    
    /**
     * Запустить MainService программно
     */
    fun startCaptureService(context: Context) {
        try {
            val intent = android.content.Intent(
                context,
                Class.forName("com.carriez.flutter_hbb.MainService")
            )
            intent.action = "ACT_INIT_MEDIA_PROJECTION_AND_SERVICE"
            context.startService(intent)
            Log.i(TAG, "✓ Capture service started")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting service", e)
        }
    }
    
    /**
     * Получить статус для Flutter
     */
    fun getStatus(): Map<String, Any> {
        val status = mutableMapOf<String, Any>()
        
        try {
            val available = isAvailable()
            status["available"] = available
            
            if (available) {
                esperSDK?.getEsperDeviceInfo()?.let { info ->
                    status["device_id"] = info.deviceId ?: "unknown"
                    status["serial"] = info.serialNo ?: "unknown"
                    status["imei"] = info.imei1 ?: "unknown"
                }
            }
        } catch (e: Exception) {
            status["error"] = e.message ?: "unknown error"
        }
        
        return status
    }
}
