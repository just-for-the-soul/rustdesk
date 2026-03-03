package com.carriez.flutter_hbb

import android.Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
import android.Manifest.permission.SYSTEM_ALERT_WINDOW
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.Toast
import com.hjq.permissions.XXPermissions
import io.flutter.embedding.android.FlutterActivity

const val DEBUG_BOOT_COMPLETED = "com.carriez.flutter_hbb.DEBUG_BOOT_COMPLETED"

class BootReceiver : BroadcastReceiver() {
    private val logTag = "tagBootReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(logTag, "onReceive ${intent.action}")

        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i("BootReceiver", "Device booted - checking Accessibility")

            // Проверяем через 5 секунд после загрузки
            Handler(Looper.getMainLooper()).postDelayed({
                checkAndRestoreAccessibility(context)
            }, 5000)
        }

        if (Intent.ACTION_BOOT_COMPLETED == intent.action || DEBUG_BOOT_COMPLETED == intent.action) {
            // check SharedPreferences config
            val prefs = context.getSharedPreferences(KEY_SHARED_PREFERENCES, FlutterActivity.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_START_ON_BOOT_OPT, false)) {
                Log.d(logTag, "KEY_START_ON_BOOT_OPT is false")
                return
            }
            // check pre-permission
            if (!XXPermissions.isGranted(context, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, SYSTEM_ALERT_WINDOW)){
                Log.d(logTag, "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS or SYSTEM_ALERT_WINDOW is not granted")
                return
            }

            val it = Intent(context, MainService::class.java).apply {
                action = ACT_INIT_MEDIA_PROJECTION_AND_SERVICE
                putExtra(EXT_INIT_FROM_BOOT, true)
            }
            Toast.makeText(context, "RustDesk is Open", Toast.LENGTH_LONG).show()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(it)
            } else {
                context.startService(it)
            }
        }
    }



    private fun checkAndRestoreAccessibility(context: Context) {
        if (!InputService.isOpen) {
            Log.w("BootReceiver", "Accessibility не активен после перезагрузки")

            // Если у нас Esper - включаем программно
            if (EsperManager.isAvailable()) {
                Log.i("BootReceiver", "Включаем Accessibility через Esper...")
                EsperManager.enableAccessibilityService { success ->
                    if (success) {
                        Log.i("BootReceiver", "✓ Accessibility восстановлен!")
                    }
                }
            } else {
                // Открываем настройки (пользователь должен включить)
                Log.i("BootReceiver", "Открываем настройки Accessibility...")
                openAccessibilitySettings(context)
            }
        } else {
            Log.i("BootReceiver", "✓ Accessibility уже активен")
        }
    }

    private fun openAccessibilitySettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("BootReceiver", "Error opening settings", e)
        }
    }
}
