package com.edmond.clipguard

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import java.util.Calendar

/**
 * Daily self-check (around 07:30). Warns when protection is silently off:
 * app stopped, clip battery low, clip not seen for days, background running not allowed.
 */
class HealthReceiver : BroadcastReceiver() {

    companion object {
        private const val ACTION = "com.edmond.clipguard.HEALTH_CHECK"

        fun schedule(c: Context) {
            val am = c.getSystemService(AlarmManager::class.java)
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 7); set(Calendar.MINUTE, 30); set(Calendar.SECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
            }
            am.setInexactRepeating(AlarmManager.RTC_WAKEUP, cal.timeInMillis, AlarmManager.INTERVAL_DAY, pending(c))
        }

        private fun pending(c: Context) = PendingIntent.getBroadcast(
            c, 7, Intent(c, HealthReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        /** Problems found right now; empty list = all good. Also used by the main screen. */
        fun problems(c: Context): List<String> {
            val out = mutableListOf<String>()
            if (!Prefs.monitoring(c)) {
                out += "ClipGuard is switched off"
            } else if (!ClipService.snapshot.running) {
                out += "ClipGuard was stopped by the phone – open the app"
            }
            val batt = Prefs.lastBattery(c)
            if (batt in 0..19) out += "Clip battery low ($batt%) – replace the coin cell"
            val seen = Prefs.lastSeenClip(c)
            val days = if (seen == 0L) -1 else ((System.currentTimeMillis() - seen) / 86_400_000L).toInt()
            if (Prefs.monitoring(c) && days >= 3) out += "Clip not seen for $days days – check the clip battery"
            val pm = c.getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(c.packageName)) out += "Background running not allowed – alarms may not work"
            if (Prefs.activeContacts(c).isEmpty()) out += "No emergency contacts set"
            return out
        }
    }

    override fun onReceive(c: Context, intent: Intent) {
        Notifs.ensure(c)
        val list = problems(c)
        val nm = c.getSystemService(NotificationManager::class.java)
        if (list.isEmpty()) { nm.cancel(Notifs.ID_HEALTH); return }
        val text = list.joinToString("\n") { "• $it" }
        val open = PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        nm.notify(Notifs.ID_HEALTH, Notification.Builder(c, Notifs.CH_CHECK)
            .setSmallIcon(R.drawable.ic_warning)
            .setContentTitle("ClipGuard check: needs attention")
            .setContentText(list.first())
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build())
    }
}
