package com.plantpilot.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.plantpilot.R

class NotificationHelper(private val context: Context) {

    companion object {
        private const val CHANNEL_ID = "watering_notifications"
        private const val CHANNEL_NAME = "Watering Status"
        private const val CHANNEL_LOW_WATER_ID = "low_water_alerts"
        private const val CHANNEL_LOW_WATER_NAME = "Low Water Alerts"
        private const val NOTIFICATION_ID_START = 1001
        private const val NOTIFICATION_ID_FINISH = 1002
        private const val NOTIFICATION_ID_LOW_WATER = 1003
    }

    init {
        createNotificationChannel()
        createLowWaterChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, importance).apply {
                description = "Notifications for watering status"
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createLowWaterChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_LOW_WATER_ID, CHANNEL_LOW_WATER_NAME, importance).apply {
                description = "Reminders to refill the water tank"
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun showLowWaterAlert() {
        val builder = NotificationCompat.Builder(context, CHANNEL_LOW_WATER_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Water Tank Low")
            .setContentText("Please refill the water tank.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID_LOW_WATER, builder.build())
    }

    fun showWateringStarted(plantName: String) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Watering Started")
            .setContentText("Now watering $plantName...")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID_START, builder.build())
    }

    fun showWateringFinished(plantName: String) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Watering Finished")
            .setContentText("$plantName has been watered successfully.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Cancel the "started" notification if it exists
        notificationManager.cancel(NOTIFICATION_ID_START)
        notificationManager.notify(NOTIFICATION_ID_FINISH, builder.build())
    }
}
