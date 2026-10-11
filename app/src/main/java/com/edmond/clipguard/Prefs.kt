package com.edmond.clipguard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object Prefs {
    const val DEFAULT_MAC = "C0:BB:3E:B1:5C:1B"
    private fun sp(c: Context) = c.getSharedPreferences("clipguard", Context.MODE_PRIVATE)

    fun mac(c: Context): String = sp(c).getString("mac", DEFAULT_MAC) ?: DEFAULT_MAC
    fun graceSec(c: Context): Int = sp(c).getInt("grace", 120)
    fun escalateSec(c: Context): Int = sp(c).getInt("escalate", 60)
    fun contacts(c: Context): String = sp(c).getString("contacts", "") ?: ""
    fun monitoring(c: Context): Boolean = sp(c).getBoolean("monitoring", false)

    fun save(c: Context, mac: String, grace: Int, escalate: Int, contacts: String) {
        sp(c).edit()
            .putString("mac", mac)
            .putInt("grace", grace)
            .putInt("escalate", escalate)
            .putString("contacts", contacts)
            .apply()
    }

    fun setMonitoring(c: Context, on: Boolean) {
        sp(c).edit().putBoolean("monitoring", on).apply()
    }

    fun contactList(c: Context): List<String> =
        contacts(c).split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    fun requiredPermissions(): Array<String> {
        val p = mutableListOf(Manifest.permission.SEND_SMS)
        if (Build.VERSION.SDK_INT >= 31) {
            p += Manifest.permission.BLUETOOTH_CONNECT
            p += Manifest.permission.BLUETOOTH_SCAN
        } else {
            p += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.POST_NOTIFICATIONS
        return p.toTypedArray()
    }

    /** Bluetooth permissions are mandatory; SMS and notifications are checked separately. */
    fun hasBluetoothPerms(c: Context): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= 31)
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        return needed.all { c.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }
}
