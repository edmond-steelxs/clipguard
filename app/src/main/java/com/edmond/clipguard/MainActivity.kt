package com.edmond.clipguard

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var macField: EditText
    private lateinit var graceField: EditText
    private lateinit var escalateField: EditText
    private lateinit var contactsField: EditText
    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            status.text = ClipService.statusText
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        status = TextView(this).apply { textSize = 17f }
        root.addView(status)

        fun label(t: String) {
            root.addView(TextView(this).apply { text = t; setPadding(0, pad, 0, 0) })
        }
        fun field(value: String, type: Int): EditText =
            EditText(this).apply { setText(value); inputType = type; setSingleLine() }
                .also { root.addView(it) }
        fun button(t: String, action: () -> Unit) {
            root.addView(Button(this).apply { text = t; setOnClickListener { action() } })
        }

        label("Clip Bluetooth address")
        macField = field(Prefs.mac(this), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
        label("Grace period before loud alarm (seconds)")
        graceField = field(Prefs.graceSec(this).toString(), InputType.TYPE_CLASS_NUMBER)
        label("Extra time before SMS to contacts (seconds)")
        escalateField = field(Prefs.escalateSec(this).toString(), InputType.TYPE_CLASS_NUMBER)
        label("Emergency contacts (phone numbers, comma separated, e.g. +32499...)")
        contactsField = field(Prefs.contacts(this), InputType.TYPE_CLASS_PHONE)

        button("Save settings") { if (save()) toast("Saved") }
        button("Start monitoring") { startMonitoring() }
        button("Stop monitoring") {
            Prefs.setMonitoring(this, false)
            stopService(Intent(this, ClipService::class.java))
        }
        button("Allow running in background (battery)") { askBatteryExemption() }
        button("Test alarm sound (5 s)") { sendToService(ClipService.ACTION_TEST_ALARM) }
        button("Send test SMS to contacts") { sendToService(ClipService.ACTION_TEST_SMS) }
        button("Child is out – stop alarm") { sendToService(ClipService.ACTION_DISMISS) }

        setContentView(ScrollView(this).apply { addView(root) })
        requestPermissions(Prefs.requiredPermissions(), 1)
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    private fun save(): Boolean {
        val mac = macField.text.toString().trim().uppercase()
        if (!BluetoothAdapter.checkBluetoothAddress(mac)) {
            toast("Invalid Bluetooth address"); return false
        }
        val grace = graceField.text.toString().toIntOrNull()?.coerceIn(10, 1800) ?: 120
        val esc = escalateField.text.toString().toIntOrNull()?.coerceIn(10, 1800) ?: 60
        Prefs.save(this, mac, grace, esc, contactsField.text.toString().trim())
        return true
    }

    private fun startMonitoring() {
        if (!save()) return
        if (!Prefs.hasBluetoothPerms(this)) {
            toast("Bluetooth permission needed")
            requestPermissions(Prefs.requiredPermissions(), 1)
            return
        }
        Prefs.setMonitoring(this, true)
        startForegroundService(Intent(this, ClipService::class.java))
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("Tip: tap 'Allow running in background' for reliable alarms")
        }
    }

    private fun sendToService(action: String) {
        if (!Prefs.hasBluetoothPerms(this)) {
            toast("Bluetooth permission needed"); return
        }
        save()
        startForegroundService(Intent(this, ClipService::class.java).setAction(action))
    }

    private fun askBatteryExemption() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()
}
