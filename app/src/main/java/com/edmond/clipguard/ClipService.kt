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

        const val ACTION_DISMISS = "com.edmond.clipguard.DISMISS"
        const val ACTION_TEST_ALARM = "com.edmond.clipguard.TEST_ALARM"
        const val ACTION_TEST_SMS = "com.edmond.clipguard.TEST_SMS"

        val CLIP_SERVICE: UUID = UUID.fromString("44ba1a00-4cf5-46d8-a887-a846d9de712d")
        val CLIP_STATE: UUID = UUID.fromString("44ba4000-4cf5-46d8-a887-a846d9de712d")
        val BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val BATTERY_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val CH_STATUS = "status"
        private const val CH_ALERT = "alert"
        private const val NOTIF_STATUS = 1
        private const val NOTIF_ALERT = 2
        private const val NOTIF_BATTERY = 3
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
            return
        }
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_STATUS, statusNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_STATUS, statusNotification())
        }
        started = true
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
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
            ACTION_TEST_SMS -> sendSms(test = true)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopAlarm()
        stopScan()
        gatt?.close()
        gatt = null
        if (started) {
            try { unregisterReceiver(btStateReceiver) } catch (_: Exception) {}
        }
        statusText = "Monitoring is off"
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

    private val toLoudAlarm = Runnable {
        stage = 2
        startSound()
        alert("CHILD STILL IN THE CAR?",
            "The clip is still closed and you are away from the car. Go back now.",
            withDismiss = true, loud = true)
        handler.postDelayed(toContacts, Prefs.escalateSec(this) * 1000L)
        update()
    }

    private val toContacts = Runnable {
        stage = 3
        sendSms(test = false)
        alert("Emergency contacts alerted",
            "An SMS was sent to your emergency contacts. Go back to the car now.",
            withDismiss = true, loud = true)
        update()
    }

    private fun startWarning() {
        stage = 1
        acquireWakeLock()
        val grace = Prefs.graceSec(this)
        alert("Clip still closed!",
            "You moved away from the car. Loud alarm in ${grace}s unless you take your child out.",
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
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
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

    private fun sendSms(test: Boolean) {
        val contacts = Prefs.contactList(this)
        if (contacts.isEmpty()) { lastEvent = "No emergency contacts set"; update(); return }
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            lastEvent = "SMS permission missing"; update(); return
        }
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val text = if (test)
            "ClipGuard test message ($time). If an alarm ever comes from this number, a child may still be in the car."
        else
            "ClipGuard ALERT $time: the driver's phone moved away from the car while the child seat clip is still closed. A child may still be in the car. Call the driver immediately."
        val sms: SmsManager = if (Build.VERSION.SDK_INT >= 31)
            getSystemService(SmsManager::class.java)
        else @Suppress("DEPRECATION") SmsManager.getDefault()
        var sent = 0
        for (n in contacts) {
            try { sms.sendTextMessage(n, null, text, null, null); sent++ } catch (_: Exception) {}
        }
        lastEvent = "SMS sent to $sent of ${contacts.size} contact(s)"
        update()
    }

    // ---------------------------------------------------------------- notifications & status

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
            if (state == BluetoothAdapter.STATE_ON) handler.postDelayed({ startScan() }, 1_000)
            if (state == BluetoothAdapter.STATE_OFF) {
                scanning = false
                if (!connected) { gatt?.close(); gatt = null; fastConnecting = false }
                gatt?.let { onDisconnected(it) }
            }
        }
    }

    private fun createChannels() {
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, "Monitoring status", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Child alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                setBypassDnd(true)
            }
        )
    }

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
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent())
            .setCategory(if (loud) Notification.CATEGORY_ALARM else Notification.CATEGORY_STATUS)
            .setOnlyAlertOnce(false)
        if (withDismiss) {
            b.addAction(Notification.Action.Builder(null as Icon?, "Child is out", dismissIntent()).build())
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
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
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
        if (started) nm.notify(NOTIF_STATUS, statusNotification())
    }
}
