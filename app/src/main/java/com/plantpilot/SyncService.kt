package com.plantpilot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.plantpilot.data.HardwareConnection
import com.plantpilot.data.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the process alive so the
 * [HardwareConnection] WebSocket keeps streaming telemetry from the ESP32 while
 * the app is backgrounded (at the 3s cadence). Started from MainActivity's
 * ON_START (always permitted while the app is foreground) and stopped when the
 * app is fully closed (onTaskRemoved). The service does not own the socket — it
 * only keeps the process (and therefore the socket) running; [HardwareConnection]
 * is owned by [PlantPilotApp] and outlives both the activity and this service.
 * If the OS kills the process while backgrounded, START_STICKY restarts this
 * service, which then re-establishes the WebSocket itself so the connection
 * survives without the user reopening the app.
 */
class SyncService : Service() {

    companion object {
        private const val CHANNEL_ID = "sync_service"
        private const val NOTIFICATION_ID = 1
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private val hardwareConnection: HardwareConnection
        get() = (applicationContext as PlantPilotApp).hardwareConnection

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        // Partial wake lock keeps the CPU + network awake while the app is
        // backgrounded so Android's Doze can't suspend the WebSocket that
        // streams telemetry from the ESP32. Released on stop.
        acquireWakeLock()
        // Reconnect if the socket isn't already up. Handles the case where the
        // process was killed while backgrounded and this service was restarted
        // by START_STICKY: the ViewModel (and its connectToDevice()) won't run
        // until the user reopens the app, so the service must reconnect itself.
        serviceScope.launch { reconnectIfNeeded() }
        return START_STICKY
    }

    private suspend fun reconnectIfNeeded() {
        if (hardwareConnection.isConnected()) return
        val prefs = SettingsManager(this).deviceStateFlow.first()
        if (prefs.ip.isBlank()) return
        val url = if (prefs.ip.startsWith("ws://")) prefs.ip else "ws://${prefs.ip}/ws"
        hardwareConnection.connect(url)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "PlantPilot:WS"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun startForegroundCompat() {
        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.sync_service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.sync_service_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.sync_service_title))
            .setContentText(getString(R.string.sync_service_text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // App fully closed (swiped from recents): stop background sync and cut
        // the WebSocket so the ESP32 drops to its idle no-client cadence.
        hardwareConnection.disconnect()
        releaseWakeLock()
        stopSelf()
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }
}
