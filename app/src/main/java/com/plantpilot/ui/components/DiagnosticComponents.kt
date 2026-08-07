package com.plantpilot.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.plantpilot.ui.theme.*
import com.plantpilot.viewmodel.PumpTestViewModel
import kotlinx.serialization.json.*

@Composable
fun DiagnosticsStatusSection(viewModel: PumpTestViewModel) {
    val telemetryData by viewModel.telemetry.collectAsState()
    val telemetry = telemetryData

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatusItem(
                    icon = Icons.Default.SignalWifiStatusbar4Bar,
                    label = "RSSI",
                    value = "${telemetry?.wifi_rssi ?: "--"} dBm",
                    color = when {
                        (telemetry?.wifi_rssi ?: -100) > -60 -> RssiGood
                        (telemetry?.wifi_rssi ?: -100) > -80 -> RssiMedium
                        else -> RssiBad
                    }
                )
                StatusItem(
                    icon = Icons.Default.Memory,
                    label = "Free RAM",
                    value = if (telemetry?.free_heap != null) "${telemetry.free_heap / 1024} KB" else "--",
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(modifier = Modifier.alpha(0.5f))
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatusItem(
                    icon = Icons.Default.Timer,
                    label = "Uptime",
                    value = formatUptime(telemetry?.uptime_sec ?: 0),
                    color = MaterialTheme.colorScheme.secondary
                )
                StatusItem(
                    icon = Icons.Default.AccessTime,
                    label = "ESP Time",
                    value = formatEpoch(telemetry?.epoch ?: 0),
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
    }
}

@Composable
fun StatusItem(
    icon: ImageVector,
    label: String,
    value: String,
    color: Color
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = color)
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = color)
        }
    }
}

@Composable
fun LogLine(log: String) {
    val timestampRegex = Regex("""^\[(\d{2}:\d{2}:\d{2})\]\s*(.*)$""")
    val match = timestampRegex.find(log)
    val timestamp = match?.groupValues?.get(1)
    val rawContent = match?.groupValues?.get(2) ?: log

    val badge: String
    val friendlyMessage: String
    val color: Color

    when {
        rawContent.startsWith("Error") || rawContent.startsWith("Warn") -> {
            badge = "ERR"
            friendlyMessage = rawContent.removePrefix("Error: ").removePrefix("Warn: ")
            color = LogError
        }
        rawContent.startsWith("ESP32:") -> {
            val text = rawContent.removePrefix("ESP32:").trim()
            if (text.startsWith("{")) {
                val parsed = parseJsonLog(text)
                badge = parsed.first
                friendlyMessage = parsed.second
                color = parsed.third
            } else if (text.startsWith("OK:")) {
                badge = "ACK"
                friendlyMessage = formatPumpAck(text)
                color = LogSuccess
            } else {
                badge = "RX"
                friendlyMessage = text
                color = LogSuccess
            }
        }
        rawContent.startsWith("App:") -> {
            badge = "TX"
            val text = rawContent.removePrefix("App: Sent ").removePrefix("App: ").trim()
            friendlyMessage = formatAppCommand(text)
            color = LogInfo
        }
        rawContent.startsWith("System:") -> {
            badge = "SYS"
            friendlyMessage = rawContent.removePrefix("System:").trim()
            color = LogMuted
        }
        else -> {
            badge = "LOG"
            friendlyMessage = rawContent
            color = TerminalText
        }
    }

    Row(
        modifier = Modifier.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!timestamp.isNullOrEmpty()) {
            Text(
                text = timestamp,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.width(6.dp))
        }

        Surface(
            shape = MaterialTheme.shapes.extraSmall,
            color = color.copy(alpha = 0.15f),
            modifier = Modifier.padding(end = 6.dp)
        ) {
            Text(
                text = badge,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                ),
                color = color
            )
        }

        Text(
            text = friendlyMessage,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace
            ),
            color = color
        )
    }
}

private fun parseJsonLog(jsonStr: String): Triple<String, String, Color> {
    return try {
        val root = Json.parseToJsonElement(jsonStr).jsonObject
        val type = root["type"]?.jsonPrimitive?.content ?: ""
        when (type) {
            "telemetry" -> {
                val soilArr = root["soil"]?.jsonArray?.map { it.jsonPrimitive.content }
                val rawSoilArr = root["raw_soil"]?.jsonArray?.map { it.jsonPrimitive.content }
                val waterLevel = root["water_level"]?.jsonPrimitive?.content
                val rssi = root["wifi_rssi"]?.jsonPrimitive?.content
                val heap = root["free_heap"]?.jsonPrimitive?.content?.toIntOrNull()
                val soilStr = if (soilArr != null) "Soil: [${soilArr.joinToString("%")}%]" else "Telemetry Stream"
                val rawStr = if (rawSoilArr != null) " (Raw ADC: [${rawSoilArr.joinToString(", ")}])" else ""
                val tankStr = if (waterLevel != null) " • Tank: ${waterLevel}%" else ""
                val rssiStr = if (rssi != null) " • RSSI: ${rssi} dBm" else ""
                val heapStr = if (heap != null) " • Free RAM: ${heap / 1024} KB" else ""
                Triple("DATA", "$soilStr$rawStr$tankStr$rssiStr$heapStr", LogSuccess)
            }
            "ok" -> {
                val cmd = root["cmd"]?.jsonPrimitive?.content ?: ""
                Triple("ACK", "Acknowledged: ${formatAppCommand(cmd)}", LogSuccess)
            }
            "status" -> {
                val cadence = root["sensor_cadence_sec"]?.jsonPrimitive?.content
                val tankLvl = root["water_level"]?.jsonPrimitive?.content
                val tankStr = if (tankLvl != null) " • Tank Level: ${tankLvl}%" else ""
                Triple("STATUS", "Hardware Status Received (Cadence: ${cadence ?: "3"}s$tankStr)", LogInfo)
            }
            "watering_finished" -> {
                val motor = root["motor"]?.jsonPrimitive?.content
                val amount = root["amount_ml"]?.jsonPrimitive?.content
                Triple("EVENT", "Watering Finished: Pump $motor ($amount ml)", LogSuccess)
            }
            else -> Triple("RX", jsonStr, LogSuccess)
        }
    } catch (_: Exception) {
        Triple("RX", jsonStr, LogSuccess)
    }
}

private fun mapPumpName(raw: String): String {
    return when (raw.trim().uppercase(java.util.Locale.ROOT)) {
        "A", "1" -> "1"
        "B", "2" -> "2"
        "C", "3" -> "3"
        "D", "4" -> "4"
        else -> raw
    }
}

private fun formatPumpAck(text: String): String {
    return when {
        text.contains("PUMP_ALL_OFF") -> "All pumps stopped"
        text.contains("PUMP") -> {
            val part = text.removePrefix("OK: ").removePrefix("PUMP")
            val parts = part.split("_")
            val pumpNum = mapPumpName(parts.getOrNull(0) ?: "")
            val action = parts.getOrNull(1) ?: ""
            if (action.isNotBlank()) "Pump $pumpNum $action confirmed" else "Pump $pumpNum confirmed"
        }
        else -> text
    }
}

private fun formatAppCommand(cmd: String): String {
    return when {
        cmd == "PUMP_ALL_OFF" -> "Stop All Pumps"
        cmd.startsWith("PUMP") -> {
            val parts = cmd.split("_")
            val rawPump = parts.getOrNull(0)?.removePrefix("PUMP") ?: ""
            val pumpNum = mapPumpName(rawPump)
            val action = parts.getOrNull(1) ?: ""
            "Turn Pump $pumpNum $action"
        }
        cmd == "STATUS" -> "Request Device Status"
        cmd == "PING" -> "Ping Keepalive"
        cmd == "READ_SENSORS" -> "Read All Soil Sensors"
        cmd == "RESET_CONFIG" -> "Factory Reset Device Config"
        cmd.startsWith("SYNC_MODE") -> "Set Telemetry Rate to ${cmd.removePrefix("SYNC_MODE ").trim()}s"
        cmd == "BUZZ_TEST" -> "Test Hardware Alarm Buzzer"
        cmd.startsWith("BUZZ_CADENCE") -> {
            val mins = cmd.removePrefix("BUZZ_CADENCE ").trim()
            if (mins == "0") "Disable Alarm Buzzer" else "Set Alarm Cadence to ${mins}m"
        }
        cmd == "DEMO_MODE_ON" -> "Enable Demo Mode"
        cmd == "DEMO_MODE_OFF" -> "Disable Demo Mode"
        cmd == "HW_WATER_SENSOR_ON" -> "Enable Hardware Water Tank Sensor"
        cmd == "HW_WATER_SENSOR_OFF" -> "Disable Hardware Water Tank Sensor"
        cmd == "CAL_STREAM_ON" -> "Start Live Sensor Calibration Stream"
        cmd == "CAL_STREAM_OFF" -> "Stop Live Sensor Calibration Stream"
        else -> cmd
    }
}

@Composable
fun PulsingDot(isVisible: Boolean) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )
    val scale by transition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    if (isVisible) {
        Box(
            modifier = Modifier.size(8.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size((6 * scale).dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha))
            )
        }
    }
}

fun formatUptime(seconds: Long): String {
    if (seconds == 0L) return "--"
    val days = seconds / 86400
    val hours = (seconds % 86400) / 3600
    val mins = (seconds % 3600) / 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${mins}m"
        else -> "${mins}m ${seconds % 60}s"
    }
}

fun formatEpoch(epoch: Long): String {
    if (epoch == 0L) return "--"
    val date = java.util.Date(epoch * 1000)
    val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
    return sdf.format(date)
}
