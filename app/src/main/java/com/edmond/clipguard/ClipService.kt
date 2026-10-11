package com.edmond.clipguard

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.telephony.SmsManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Keeps a Bluetooth connection to the Easy-Tech clip.
 * Clip characteristic 44ba4000: 0x01 = closed (child buckled), anything else = open.
 * Clip closed + connection lost  ->  warning, then loud alarm, then SMS to contacts.
 */
@SuppressLint("MissingPermission")
class ClipService : Service() {

    companion object {
        @Volatile var statusText: String = "Monitoring is off"

        /** Structured status for the main screen. */
        data class Snapshot(
            val running: Boolean = false,
            val connected: Boolean = false,
            val clipClosed: Boolean? = null,
            val stage: Int = 0,
            val battery: Int? = null,
            val raw: String = "-",
            val lastEvent: String = "",
            val problem: String? = null,
        )
        @Volatile var snapshot = Snapshot()

        const val ACTION_DISMISS = "com.edmond.clipguard.DISMISS"
        const val ACTION_TEST_ALARM = "com.edmond.clipguard.TEST_ALARM"
        const val ACTION_TEST_SMS = "com.edmond.clipguard.TEST_SMS"

        val CLIP_SERVICE: UUID = UUID.fromString("44ba1a00-4cf5-46d8-a887-a846d9de712d")
        val CLIP_STATE: UUID = UUID.fromString("44ba4000-4cf5-46d8-a887-a846d9de712d")
        val BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val BATTERY_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val CH_STATUS = Notifs.CH_STATUS
        private const val CH_ALERT = Notifs.CH_ALERT
        private const val NOTIF_STATUS = Notifs.ID_STATUS
        private const val NOTIF_ALERT = Notifs.ID_ALERT
        private const val NOTIF_BATTERY = Notifs.ID_BATTERY
        const val ACTION_ACK_PROTECTION = "com.edmond.clipguard.ACK_PROTECTION"
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var nm: NotificationManager

    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private var connected = false
    private var clipClosed: Boolean? = null
    private var lastRaw = "-"
    private var dismissed = false
    private var stage = 0          // 0 idle, 1 warning, 2 loud alarm, 3 contacts alerted
    private var battery: Int? = null
    private var batteryWarned = false
    private var lastEvent = "Starting"
    private var started = false

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var savedAlarmVolume = -1

    private val ops = ArrayDeque<() -> Unit>()

    // ---------------------------------------------------------------- lifecycle

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        createChannels()
        if (!Prefs.hasBluetoothPerms(this)) {
            statusText = "Missing Bluetooth permission – open the app and allow it"
            snapshot = Snapshot(problem = "Bluetooth permission missing – allow 'Nearby devices'")
            return
        }
        if (Build.VERSION.SDK_INT >= 29) {
            // Add the "location" type when allowed, so we can read the car's position from the background.
            val withLocation = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            try {
                if (Prefs.hasLocationPerm(this)) startForeground(NOTIF_STATUS, statusNotification(), withLocation)
                else startForeground(NOTIF_STATUS, statusNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } catch (e: Exception) {
                startForeground(NOTIF_STATUS, statusNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            }
        } else {
            startForeground(NOTIF_STATUS, statusNotification())
        }
        started = true
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED).apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(btStateReceiver, filter, Context.RECEIVER_EXPORTED)
        else registerReceiver(btStateReceiver, filter)
        startScan()
        update()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!started) { stopSelf(); return START_NOT_STICKY }
        when (intent?.action) {
            ACTION_DISMISS -> dismiss()
            ACTION_TEST_ALARM -> testAlarm()
            ACTION_TEST_SMS -> sendAlerts(test = true)
            ACTION_ACK_PROTECTION -> {
                handler.removeCallbacks(protectionReminder)
                nm.cancel(Notifs.ID_PROTECTION)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        nm.cancel(Notifs.ID_PROTECTION)
        stopAlarm()
        stopScan()
        gatt?.close()
        gatt = null
        if (started) {
            try { unregisterReceiver(btStateReceiver) } catch (_: Exception) {}
        }
        statusText = "Monitoring is off"
        snapshot = Snapshot()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- scanning / connecting

    private fun adapter(): BluetoothAdapter? =
        getSystemService(BluetoothManager::class.java)?.adapter

    /**
     * Two ways to reach the clip, running side by side:
     *  1. A direct "auto connect" to the known address. Android keeps waiting for the clip
     *     and connects as soon as it is in range, without needing a scan (works with screen off).
     *  2. A scan that looks for the clip's address. When the scan sees the clip first,
     *     we switch to a fast direct connection.
     * The clip's address starts with C0, which is a "random static" Bluetooth address; on
     * Android 13+ a plain address scan filter only matches public addresses, which is why
     * the first version kept searching forever.
     */
    private fun startScan() {
        val a = adapter()
        if (a == null || !a.isEnabled) { lastEvent = "Bluetooth is off"; update(); return }
        if (connected) return
        if (gatt == null) autoConnect(a)
        if (scanning) return
        val scanner = a.bluetoothLeScanner ?: return
        // No address filter: on Android 13+ the public filter only matches public addresses,
        // and the clip uses a random one. We check the address ourselves in the callback.
        // (With the screen off Android pauses unfiltered scans; the auto-connect covers that.)
        val filters = emptyList<ScanFilter>()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(filters, settings, scanCallback)
            scanning = true
            if (!connected) lastEvent = "Searching for clip… (close the clip and keep it nearby)"
        } catch (e: Exception) {
            lastEvent = "Scan failed: ${e.message}"
        }
        update()
    }

    private fun remoteDevice(a: BluetoothAdapter): BluetoothDevice {
        val mac = Prefs.mac(this)
        return if (Build.VERSION.SDK_INT >= 33)
            a.getRemoteLeDevice(mac, BluetoothDevice.ADDRESS_TYPE_RANDOM)
        else a.getRemoteDevice(mac)
    }

    private fun autoConnect(a: BluetoothAdapter) {
        try {
            fastConnecting = false
            gatt = remoteDevice(a).connectGatt(this, true, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            lastEvent = "Connect failed: ${e.message}"
        }
    }

    private fun stopScan() {
        if (!scanning) return
        try { adapter()?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
        scanning = false
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!result.device.address.equals(Prefs.mac(this@ClipService), ignoreCase = true)) return
            handler.post { connect(result.device) }
        }

        override fun onScanFailed(errorCode: Int) {
            handler.post {
                scanning = false
                lastEvent = "Scan error $errorCode, retrying"
                update()
                handler.postDelayed({ startScan() }, 15_000)
            }
        }
    }

    private var fastConnecting = false

    private val connectTimeout = Runnable {
        if (!connected) {
            lastEvent = "Connect timeout, retrying"
            gatt?.close()
            gatt = null
            fastConnecting = false
            startScan()
        }
    }

    /** Scan saw the clip: replace the slow waiting connection with a fast direct one. */
    private fun connect(device: BluetoothDevice) {
        if (connected || fastConnecting) return
        stopScan()
        gatt?.close()
        fastConnecting = true
        gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        lastEvent = "Clip found, connecting…"
        handler.postDelayed(connectTimeout, 20_000)
        update()
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (g != gatt) { g.close(); return@post }
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> onConnected(g)
                    BluetoothProfile.STATE_DISCONNECTED -> onDisconnected(g)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post { setupNotifications(g) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            handler.post { nextOp() }
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            val v = c.value
            handler.post { handleValue(c.uuid, v); nextOp() }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            handler.post { handleValue(c.uuid, value); nextOp() }
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            val v = c.value
            handler.post { handleValue(c.uuid, v) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            handler.post { handleValue(c.uuid, value) }
        }
    }

    private fun onConnected(g: BluetoothGatt) {
        handler.removeCallbacks(connectTimeout)
        stopScan()
        fastConnecting = false
        connected = true
        lastEvent = "Connected to clip"
        Prefs.markSeenClip(this)
        handler.removeCallbacks(carCheck)
        nm.cancel(Notifs.ID_CAR)
        if (stage > 0) {
            // Phone is back near the clip: you are back at the car.
            stopAlarm()
            alert(
                "Back at the car",
                "The clip is still closed. Open it when you take your child out.",
                withDismiss = true, loud = false
            )
        }
        g.discoverServices()
        update()
    }

    private fun onDisconnected(g: BluetoothGatt) {
        // Disconnect caused by Bluetooth switching off: warn "protection off" instead of the child alarm.
        if (adapter()?.state != BluetoothAdapter.STATE_ON) { onBluetoothOff(); return }
        handler.removeCallbacks(connectTimeout)
        val wasConnected = connected
        connected = false
        g.close()
        gatt = null
        fastConnecting = false
        ops.clear()
        lastEvent = "Lost connection to clip"
        if (wasConnected && clipClosed == true && !dismissed && stage == 0) startWarning()
        update()
        handler.postDelayed({ startScan() }, 1_000)
    }

    // ---------------------------------------------------------------- GATT setup (one operation at a time)

    private fun setupNotifications(g: BluetoothGatt) {
        val clip = g.getService(CLIP_SERVICE)?.getCharacteristic(CLIP_STATE)
        if (clip == null) { lastEvent = "Clip sensor not found on device"; update(); return }
        ops.clear()
        ops.addLast { enableNotify(g, clip) }
        ops.addLast { if (!g.readCharacteristic(clip)) handler.post { nextOp() } }
        val batt = g.getService(BATTERY_SERVICE)?.getCharacteristic(BATTERY_LEVEL)
        if (batt != null) {
            ops.addLast { enableNotify(g, batt) }
            ops.addLast { if (!g.readCharacteristic(batt)) handler.post { nextOp() } }
        }
        // Once set up, ask for the slowest connection (about 100-125 ms interval instead of ~30-50 ms).
        // The clip's radio then wakes up far less often, which saves its coin cell. Losing the clip is
        // still detected within seconds, which is plenty for an alarm that waits minutes anyway.
        ops.addLast {
            try { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER) } catch (_: Exception) {}
            handler.post { nextOp() }
        }
        nextOp()
    }

    private fun nextOp() {
        if (gatt == null) { ops.clear(); return }
        ops.removeFirstOrNull()?.invoke()
    }

    @Suppress("DEPRECATION")
    private fun enableNotify(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(CCCD)
        if (d == null) { handler.post { nextOp() }; return }
        d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (!g.writeDescriptor(d)) handler.post { nextOp() }
    }

    private fun handleValue(uuid: UUID, v: ByteArray?) {
        if (v == null || v.isEmpty()) return
        when (uuid) {
            CLIP_STATE -> {
                lastRaw = v.joinToString(" ") { "%02X".format(it) }
                val closed = v[0].toInt() == 1
                clipClosed = closed
                if (closed) {
                    lastEvent = "Clip closed – child buckled in"
                } else {
                    lastEvent = "Clip open"
                    dismissed = false
                    stopAlarm()
                    nm.cancel(NOTIF_ALERT)
                }
            }
            BATTERY_LEVEL -> {
                val level = v[0].toInt() and 0xFF
                battery = level
                Prefs.setLastBattery(this, level)
                if (level < 20 && !batteryWarned) {
                    batteryWarned = true
                    alert("Clip battery low", "Battery at $level%. Replace the coin cell soon.",
                        withDismiss = false, loud = false, id = NOTIF_BATTERY)
                }
            }
        }
        update()
    }

    // ---------------------------------------------------------------- alarm stages

    /** Why the alarm started: the phone lost the clip, or the watch lost the phone (phone left in the car). */
    private var watchReason = false

    private val toLoudAlarm = Runnable {
        stage = 2
        startSound()
        alert("CHILD STILL IN THE CAR?",
            if (watchReason) "Your watch lost contact with this phone while the clip is closed. Go back to the car now."
            else "The clip is still closed and you are away from the car. Go back now.",
            withDismiss = true, loud = true)
        handler.postDelayed(toContacts, Prefs.escalateSec(this) * 1000L)
        update()
    }

    private val toContacts = Runnable {
        stage = 3
        sendAlerts(test = false)
        alert("Emergency contacts alerted",
            "Your emergency contacts were sent a message. Go back to the car now.",
            withDismiss = true, loud = true)
        update()
    }

    private fun startWarning(byWatch: Boolean = false) {
        watchReason = byWatch
        stage = 1
        acquireWakeLock()
        carLocation = null
        // The phone just lost the clip, so it is still close to the car: remember where that is.
        fetchLocation(30_000) { loc -> if (loc != null) carLocation = loc }
        val grace = Prefs.graceSec(this)
        alert(if (byWatch) "Phone left in the car?" else "Clip still closed!",
            if (byWatch) "Your watch lost this phone while the clip is closed. Loud alarm in ${grace}s unless you come back."
            else "You moved away from the car. Loud alarm in ${grace}s unless you take your child out.",
            withDismiss = true, loud = true)
        handler.postDelayed(toLoudAlarm, grace * 1000L)
    }

    private fun stopAlarm() {
        handler.removeCallbacks(toLoudAlarm)
        handler.removeCallbacks(toContacts)
        stopSound()
        stage = 0
        releaseWakeLock()
    }

    private fun dismiss() {
        dismissed = true
        stopAlarm()
        nm.cancel(NOTIF_ALERT)
        lastEvent = "Confirmed: child is out of the car"
        update()
    }

    private fun testAlarm() {
        startSound()
        handler.postDelayed({ if (stage < 2) stopSound() }, 5_000)
    }

    private fun startSound() {
        if (player != null) return
        try {
            val audio = getSystemService(AudioManager::class.java)
            savedAlarmVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        } catch (_: Exception) { savedAlarmVolume = -1 }
        try {
            val chosen = Prefs.alarmSound(this)?.let { android.net.Uri.parse(it) }
            val uri = chosen
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@ClipService, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            lastEvent = "Alarm sound failed: ${e.message}"
            player?.release(); player = null
            if (Prefs.alarmSound(this) != null) {
                try {
                    player = MediaPlayer().apply {
                        setAudioAttributes(AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                        setDataSource(this@ClipService, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
                        isLooping = true; prepare(); start()
                    }
                } catch (_: Exception) {}
            }
        }
        @Suppress("DEPRECATION")
        val vib = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        vibrator = vib
        vib?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 400), 0))
    }

    private fun stopSound() {
        player?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        player = null
        vibrator?.cancel()
        vibrator = null
        if (savedAlarmVolume >= 0) {
            try {
                getSystemService(AudioManager::class.java)
                    .setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVolume, 0)
            } catch (_: Exception) {}
            savedAlarmVolume = -1
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "clipguard:alarm")
            .apply { acquire(60 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun alertText(test: Boolean, loc: Location?): String {
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val time = fmt.format(Date())
        val base = if (test)
            "ClipGuard test message ($time). If an alarm ever comes through this channel, a child may still be in the car."
        else
            if (watchReason)
                "ClipGuard ALERT $time: the driver walked away and left their phone in the car while the child seat clip is still closed. A child may still be in the car. Go there or call the driver immediately (their phone is in the car)."
            else
                "ClipGuard ALERT $time: the driver's phone moved away from the car while the child seat clip is still closed. A child may still be in the car. Call the driver immediately."
        val where = if (loc == null) "Location: not available" else {
            val link = "https://maps.google.com/?q=%.6f,%.6f".format(Locale.US, loc.latitude, loc.longitude)
            val acc = if (loc.hasAccuracy()) " (±${loc.accuracy.toInt()} m, ${fmt.format(Date(loc.time))})" else ""
            (if (test) "Phone location: " else "Car location: ") + link + acc
        }
        return base + "\n" + where
    }

    private var carLocation: Location? = null

    /** Best effort: a fresh fix within the timeout, otherwise the most recent known position. */
    private fun fetchLocation(timeoutMs: Long, cb: (Location?) -> Unit) {
        if (!Prefs.hasLocationPerm(this)) { cb(null); return }
        val lm = getSystemService(LocationManager::class.java)
        var done = false
        fun finish(l: Location?) {
            if (done) return
            done = true
            cb(l ?: lastKnownLocation(lm))
        }
        handler.postDelayed({ finish(null) }, timeoutMs)
        val candidates = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) candidates += LocationManager.FUSED_PROVIDER
        candidates += LocationManager.GPS_PROVIDER
        candidates += LocationManager.NETWORK_PROVIDER
        val provider = candidates.firstOrNull {
            try { lm.allProviders.contains(it) && lm.isProviderEnabled(it) } catch (_: Exception) { false }
        }
        if (provider == null) { finish(null); return }
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                lm.getCurrentLocation(provider, null, mainExecutor) { l -> handler.post { finish(l) } }
            } else {
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(provider, object : LocationListener {
                    override fun onLocationChanged(l: Location) { handler.post { finish(l) } }
                }, Looper.getMainLooper())
            }
        } catch (e: Exception) {
            finish(null)
        }
    }

    private fun lastKnownLocation(lm: LocationManager): Location? = try {
        lm.getProviders(true).mapNotNull { p ->
            try { lm.getLastKnownLocation(p) } catch (_: Exception) { null }
        }.maxByOrNull { it.time }
    } catch (_: Exception) { null }

    /** Sends the alert over every enabled channel: SMS and/or WhatsApp (CallMeBot). */
    private fun sendAlerts(test: Boolean) {
        val contacts = Prefs.activeContacts(this)
        if (contacts.isEmpty()) { lastEvent = "No emergency contacts set"; update(); return }
        if (test) {
            lastEvent = "Getting location for test message…"
            update()
            fetchLocation(15_000) { loc -> deliver(contacts, alertText(true, loc)) }
        } else {
            val loc = carLocation
            if (loc != null) deliver(contacts, alertText(false, loc))
            else fetchLocation(10_000) { l -> deliver(contacts, alertText(false, l)) }
        }
    }

    private fun deliver(contacts: List<Prefs.Contact>, text: String) {
        val results = mutableListOf<String>()

        if (Prefs.useSms(this)) {
            if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                results += "SMS permission missing"
            } else {
                val sms: SmsManager = if (Build.VERSION.SDK_INT >= 31)
                    getSystemService(SmsManager::class.java)
                else @Suppress("DEPRECATION") SmsManager.getDefault()
                var sent = 0
                for (ct in contacts) {
                    try {
                        sms.sendMultipartTextMessage(ct.number, null, sms.divideMessage(text), null, null)
                        sent++
                    } catch (_: Exception) {}
                }
                results += "SMS $sent/${contacts.size}"
            }
        }

        val waTargets = if (Prefs.useWhatsApp(this)) contacts.filter { it.waKey.isNotBlank() } else emptyList()
        if (Prefs.useWhatsApp(this) && waTargets.isEmpty()) results += "WhatsApp: no keys set"
        lastEvent = results.joinToString(" · ").ifEmpty { "No channel enabled" }
        update()

        if (waTargets.isNotEmpty()) {
            Thread {
                var ok = 0
                val errors = mutableListOf<String>()
                for (ct in waTargets) {
                    val err = sendWhatsApp(ct, text)
                    if (err == null) ok++ else errors += err
                }
                handler.post {
                    lastEvent = (results + "WhatsApp $ok/${waTargets.size}" +
                        errors.take(1)).joinToString(" · ")
                    update()
                }
            }.start()
        }
    }

    /** Returns null on success, otherwise a short error. Retries once. */
    private fun sendWhatsApp(ct: Prefs.Contact, text: String): String? {
        val phone = ct.number.filter { it.isDigit() || it == '+' }
        val url = "https://api.callmebot.com/whatsapp.php?phone=" +
            java.net.URLEncoder.encode(phone, "UTF-8") +
            "&text=" + java.net.URLEncoder.encode(text, "UTF-8") +
            "&apikey=" + java.net.URLEncoder.encode(ct.waKey.trim(), "UTF-8")
        var last: String? = null
        repeat(2) { attempt ->
            try {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 20_000
                val code = conn.responseCode
                val body = try {
                    (if (code < 400) conn.inputStream else conn.errorStream)
                        ?.bufferedReader()?.use { it.readText() } ?: ""
                } catch (_: Exception) { "" }
                conn.disconnect()
                if (code in 200..299 && !body.contains("APIKey is invalid", ignoreCase = true)) return null
                last = if (body.contains("APIKey", ignoreCase = true)) "WhatsApp key invalid" else "WhatsApp HTTP $code"
            } catch (e: Exception) {
                last = "WhatsApp: no internet"
            }
            if (attempt == 0) Thread.sleep(3_000)
        }
        return last
    }

    // ---------------------------------------------------------------- notifications & status

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                    if (state == BluetoothAdapter.STATE_ON) {
                        handler.removeCallbacks(protectionReminder)
                        nm.cancel(Notifs.ID_PROTECTION)
                        handler.postDelayed({ startScan() }, 1_000)
                    }
                    if (state == BluetoothAdapter.STATE_TURNING_OFF || state == BluetoothAdapter.STATE_OFF) {
                        onBluetoothOff()
                    }
                }
                BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val d: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33)
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    if (d == null) return
                    val up = intent.action == BluetoothDevice.ACTION_ACL_CONNECTED
                    if (isWatch(d)) { onWatchLink(up); return }
                    if (!isCar(d)) return
                    carConnected = up
                    if (up) {
                        handler.removeCallbacks(carCheck)
                        handler.postDelayed(carCheck, 90_000)
                    } else {
                        handler.removeCallbacks(carCheck)
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- fail-loud checks

    private var bluetoothOffHandled = false

    /** Bluetooth switched off: if the clip was closed, warn loudly that protection is OFF (no SMS). */
    private fun onBluetoothOff() {
        if (bluetoothOffHandled) return
        bluetoothOffHandled = true
        handler.postDelayed({ bluetoothOffHandled = false }, 5_000)
        val wasClosed = clipClosed == true && stage == 0
        stopScan()
        handler.removeCallbacks(connectTimeout)
        gatt?.close()
        gatt = null
        fastConnecting = false
        connected = false
        ops.clear()
        lastEvent = "Bluetooth is off"
        if (wasClosed) {
            protectionReminder.run()
        }
        update()
    }

    private val protectionReminder = object : Runnable {
        override fun run() {
            val ack = PendingIntent.getService(
                this@ClipService, 3, Intent(this@ClipService, ClipService::class.java).setAction(ACTION_ACK_PROTECTION),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            nm.notify(Notifs.ID_PROTECTION, Notification.Builder(this@ClipService, Notifs.CH_CHECK)
                .setSmallIcon(R.drawable.ic_warning)
                .setContentTitle("Protection OFF – Bluetooth is off")
                .setContentText("The clip was closed. Turn Bluetooth back on so ClipGuard can watch your child.")
                .setStyle(Notification.BigTextStyle().bigText(
                    "The clip was closed. Turn Bluetooth back on so ClipGuard can watch your child. " +
                    "This reminder repeats every 5 minutes."))
                .setContentIntent(openAppIntent())
                .setCategory(Notification.CATEGORY_REMINDER)
                .addAction(Notification.Action.Builder(null as Icon?, "Child is out", ack).build())
                .build())
            handler.postDelayed(this, 5 * 60_000L)
        }
    }

    private var carConnected = false

    private fun isWatch(d: BluetoothDevice): Boolean {
        val w = Prefs.watchAddress(this) ?: return false
        return d.address.equals(w, ignoreCase = true)
    }

    /**
     * Watch check. The phone still sees the clip (so it is in the car), the clip is closed,
     * the car's Bluetooth is off, and now the watch drops: you walked away without the phone.
     */
    private fun onWatchLink(up: Boolean) {
        if (!up) {
            if (connected && clipClosed == true && stage == 0 && !dismissed && !carConnected) {
                lastEvent = "Watch lost the phone while clip closed"
                startWarning(byWatch = true)
                update()
            } else {
                lastEvent = "Watch disconnected"
                update()
            }
        } else {
            if (stage > 0 && watchReason) {
                stopAlarm()
                nm.cancel(NOTIF_ALERT)
                alert("Watch back near the phone",
                    "Alarm stopped. Open the clip when you take your child out.",
                    withDismiss = false, loud = false)
            }
            lastEvent = "Watch connected"
            update()
        }
    }

    /** Is this classic Bluetooth device the car? Chosen car, or any car kit / hands-free if none chosen. */
    private fun isCar(d: BluetoothDevice): Boolean {
        val chosen = Prefs.carAddress(this)
        if (chosen != null) return d.address.equals(chosen, ignoreCase = true)
        return try {
            val cls = d.bluetoothClass?.deviceClass
            cls == android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO ||
                cls == android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE
        } catch (_: Exception) { false }
    }

    /** 90 s after the phone joined the car's Bluetooth, the clip should be connected. */
    private val carCheck = Runnable {
        if (connected) return@Runnable
        val a = adapter()
        val why = when {
            a == null || !a.isEnabled -> "Bluetooth is off."
            else -> "The clip may be open, out of battery, or out of range."
        }
        nm.notify(Notifs.ID_CAR, Notification.Builder(this, Notifs.CH_CHECK)
            .setSmallIcon(R.drawable.ic_warning)
            .setContentTitle("ClipGuard can't see the clip")
            .setContentText("If your child is in the seat, protection is OFF. $why")
            .setStyle(Notification.BigTextStyle().bigText(
                "If your child is in the seat, protection is OFF. $why Click the clip shut and check the app."))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build())
        lastEvent = "In the car but clip not found"
        update()
    }

    private fun createChannels() = Notifs.ensure(this)

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun dismissIntent(): PendingIntent = PendingIntent.getService(
        this, 1, Intent(this, ClipService::class.java).setAction(ACTION_DISMISS),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun alert(title: String, text: String, withDismiss: Boolean, loud: Boolean, id: Int = NOTIF_ALERT) {
        val b = Notification.Builder(this, CH_ALERT)
            .setSmallIcon(R.drawable.ic_warning)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent())
            .setCategory(if (loud) Notification.CATEGORY_ALARM else Notification.CATEGORY_STATUS)
            .setOnlyAlertOnce(false)
        if (withDismiss) {
            b.addAction(Notification.Action.Builder(null as Icon?, "Child is out", dismissIntent()).build())
        }
        if (loud) {
            // Shows the app over the lock screen when the phone is locked (heads-up otherwise).
            b.setFullScreenIntent(openAppIntent(), true)
        }
        nm.notify(id, b.build())
    }

    private fun shortStatus(): String {
        val conn = if (connected) "Connected" else "Not connected"
        val clip = when (clipClosed) { true -> "clip closed"; false -> "clip open"; null -> "clip unknown" }
        return "$conn · $clip" + (battery?.let { " · battery $it%" } ?: "")
    }

    private fun statusNotification(): Notification =
        Notification.Builder(this, CH_STATUS)
            .setSmallIcon(R.drawable.ic_shield_check)
            .setContentTitle("ClipGuard is watching")
            .setContentText(shortStatus())
            .setOngoing(true)
            .setContentIntent(openAppIntent())
            .build()

    private fun update() {
        val stageText = when (stage) {
            1 -> "WARNING – waiting for you to return"
            2 -> "LOUD ALARM"
            3 -> "ALARM – contacts alerted"
            else -> "No alarm"
        }
        statusText = buildString {
            appendLine("Monitoring: on")
            appendLine(shortStatus())
            appendLine("Raw clip value: $lastRaw")
            appendLine("State: $stageText")
            append("Last event: $lastEvent")
        }
        snapshot = Snapshot(
            running = true, connected = connected, clipClosed = clipClosed, stage = stage,
            battery = battery, raw = lastRaw, lastEvent = lastEvent,
        )
        if (started) nm.notify(NOTIF_STATUS, statusNotification())
    }
}
