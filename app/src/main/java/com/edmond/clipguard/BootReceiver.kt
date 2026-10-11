package com.edmond.clipguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED &&
            Prefs.monitoring(context) && Prefs.hasBluetoothPerms(context)
        ) {
            context.startForegroundService(Intent(context, ClipService::class.java))
        }
    }
}
