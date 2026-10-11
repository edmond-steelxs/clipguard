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

    // ---- contacts: up to 3, each a phone number plus an optional CallMeBot WhatsApp key
    const val MAX_CONTACTS = 3

    data class Contact(val number: String, val waKey: String)

    fun contactSlots(c: Context): List<Contact> {
        val p = sp(c)
        if (!p.contains("c0_number")) {
            // migrate the old comma separated list
            val old = contactList(c)
            return List(MAX_CONTACTS) { i -> Contact(old.getOrElse(i) { "" }, "") }
        }
        return List(MAX_CONTACTS) { i ->
            Contact(p.getString("c${i}_number", "") ?: "", p.getString("c${i}_wa", "") ?: "")
        }
    }

    fun saveContacts(c: Context, list: List<Contact>) {
        val e = sp(c).edit()
        list.take(MAX_CONTACTS).forEachIndexed { i, ct ->
            e.putString("c${i}_number", ct.number.trim())
            e.putString("c${i}_wa", ct.waKey.trim())
        }
        e.putString("contacts", list.map { it.number.trim() }.filter { it.isNotEmpty() }.joinToString(","))
        e.apply()
    }

    fun activeContacts(c: Context): List<Contact> =
        contactSlots(c).filter { it.number.isNotBlank() }

    fun useSms(c: Context): Boolean = sp(c).getBoolean("use_sms", true)
    fun useWhatsApp(c: Context): Boolean = sp(c).getBoolean("use_wa", true)
    fun setChannels(c: Context, sms: Boolean, wa: Boolean) {
        sp(c).edit().putBoolean("use_sms", sms).putBoolean("use_wa", wa).apply()
    }

    // ---- alarm sound (null = phone's default alarm)
    fun alarmSound(c: Context): String? = sp(c).getString("alarm_uri", null)
    fun setAlarmSound(c: Context, uri: String?) {
        sp(c).edit().putString("alarm_uri", uri).apply()
    }

    // ---- car Bluetooth (null = auto-detect car kits) and health info
    fun carAddress(c: Context): String? = sp(c).getString("car_addr", null)
    fun carName(c: Context): String? = sp(c).getString("car_name", null)
    fun setCar(c: Context, address: String?, name: String?) {
        sp(c).edit().putString("car_addr", address).putString("car_name", name).apply()
    }

    // ---- Garmin (or other) smartwatch paired with the phone; null = not used
    fun watchAddress(c: Context): String? = sp(c).getString("watch_addr", null)
    fun watchName(c: Context): String? = sp(c).getString("watch_name", null)
    fun setWatch(c: Context, address: String?, name: String?) {
        sp(c).edit().putString("watch_addr", address).putString("watch_name", name).apply()
    }

    fun lastSeenClip(c: Context): Long = sp(c).getLong("last_seen", 0L)
    fun markSeenClip(c: Context) { sp(c).edit().putLong("last_seen", System.currentTimeMillis()).apply() }
    fun lastBattery(c: Context): Int = sp(c).getInt("last_battery", -1)
    fun setLastBattery(c: Context, v: Int) { sp(c).edit().putInt("last_battery", v).apply() }

    fun setMonitoring(c: Context, on: Boolean) {
        sp(c).edit().putBoolean("monitoring", on).apply()
    }

    fun contactList(c: Context): List<String> =
        contacts(c).split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    fun requiredPermissions(): Array<String> {
        val p = mutableListOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= 31) {
            p += Manifest.permission.BLUETOOTH_CONNECT
            p += Manifest.permission.BLUETOOTH_SCAN
        }
        if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.POST_NOTIFICATIONS
        return p.toTypedArray()
    }

    fun hasLocationPerm(c: Context): Boolean =
        c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Bluetooth permissions are mandatory; SMS and notifications are checked separately. */
    fun hasBluetoothPerms(c: Context): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= 31)
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        return needed.all { c.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }
}
