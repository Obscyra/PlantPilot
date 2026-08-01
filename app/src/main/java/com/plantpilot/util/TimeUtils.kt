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
}
