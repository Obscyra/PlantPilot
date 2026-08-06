package com.plantpilot.util

import java.text.SimpleDateFormat
import java.util.*

object TimeUtils {
    fun formatTime(hour: Int, minute: Int, use24Hour: Boolean): String {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
        }
        val pattern = if (use24Hour) "HH:mm" else "hh:mm a"
        val sdf = SimpleDateFormat(pattern, Locale.getDefault())
        return sdf.format(calendar.time)
    }

    fun formatTimestamp(timestamp: Long, use24Hour: Boolean): String {
        val pattern = if (use24Hour) "MMM dd, HH:mm" else "MMM dd, hh:mm a"
        val sdf = SimpleDateFormat(pattern, Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    fun getRelativeTimeString(timestamp: Long): String {
        if (timestamp <= 0) return "Never"
        val diffMs = System.currentTimeMillis() - timestamp
        if (diffMs < 0) return "Recently"
        val diffMin = diffMs / (60 * 1000)
        val diffHour = diffMin / 60
        val diffDay = diffHour / 24
        return when {
            diffMin < 1 -> "Just now"
            diffMin < 60 -> "${diffMin}m ago"
            diffHour < 24 -> "${diffHour}h ago"
            else -> "${diffDay}d ago"
        }
    }
}
