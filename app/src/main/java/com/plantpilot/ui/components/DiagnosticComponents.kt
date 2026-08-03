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
                    label = "Free Heap",
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
    val prefix: String
    val message: String
    val color: Color

    when {
        log.startsWith("Error") -> {
            prefix = "ERR"
            message = log.removePrefix("Error: ").removePrefix("Error")
            color = LogError
        }
        log.startsWith("ESP32") -> {
            prefix = "RX"
            message = log.removePrefix("ESP32: ").removePrefix("ESP32")
            color = LogSuccess
        }
        log.startsWith("App") -> {
            prefix = "TX"
            message = log.removePrefix("App: Sent ").removePrefix("App")
            color = LogInfo
        }
        log.startsWith("System") -> {
            prefix = "SYS"
            message = log.removePrefix("System: ").removePrefix("System")
            color = LogMuted
        }
        else -> {
            prefix = ">>>"
            message = log
            color = TerminalText
        }
    }

    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            text = "[$prefix]",
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            ),
            color = color.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace
            ),
            color = color
        )
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
