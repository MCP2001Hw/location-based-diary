package com.diss.location_based_diary.util

import android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

class NotificationHelper(private val context: Context) {

    private val trackingChannelID = "LBS_TRACKING_CHANNEL"
    private val alarmChannelID = "LBS_ALARM_CHANNEL"

    init {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                trackingChannelID,
                "Active Radar",
                NotificationManager.IMPORTANCE_LOW
            )
        )
        manager.createNotificationChannel(
            NotificationChannel(
                alarmChannelID,
                "Location Alarms",
                NotificationManager.IMPORTANCE_HIGH
            )
        )
    }

    fun createForegroundNotification(): Notification {
        return NotificationCompat.Builder(context, trackingChannelID)
            .setContentTitle("LBS Radar Active")
            .setContentText("Scanning for missions...")
            .setSmallIcon(R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    fun showAlarmNotification(category: String, description: String) {
        val builder = NotificationCompat.Builder(context, alarmChannelID)
            .setSmallIcon(R.drawable.ic_dialog_map)
            .setContentTitle("NEARBY: $category!")
            .setContentText(description)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        try { NotificationManagerCompat.from(context).notify(System.currentTimeMillis().toInt(), builder.build()) }
        catch (e: SecurityException) { /* Log error */ }
    }
}