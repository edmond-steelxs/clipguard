package com.edmond.clipguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        HealthReceiver.schedule(context)
        if (Prefs.monitoring(context) && Prefs.hasBluetoothPerms(context)) {
            try {
                context.startForegroundService(Intent(context, ClipService::class.java))
            } catch (_: Exception) {
                // Some phones block this; the morning check will then report that ClipGuard is stopped.
            }
        }
    }
}
