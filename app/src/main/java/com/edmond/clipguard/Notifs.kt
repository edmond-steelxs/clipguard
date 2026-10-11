package com.edmond.clipguard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/** Notification channels shared by the service and the daily self-check. */
object Notifs {
    const val CH_STATUS = "status"
    const val CH_ALERT = "alert"
    const val CH_CHECK = "check"

    const val ID_STATUS = 1
    const val ID_ALERT = 2
    const val ID_BATTERY = 3
    const val ID_PROTECTION = 4
    const val ID_CAR = 5
    const val ID_HEALTH = 6

    fun ensure(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, "Monitoring status", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Child alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                setBypassDnd(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_CHECK, "Safety checks", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
            }
        )
    }
}
