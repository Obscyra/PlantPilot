package com.plantpilot.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.plantpilot.viewmodel.PumpTestViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PumpTestingScreen(
    viewModel: PumpTestViewModel = viewModel(),
    onBack: () -> Unit
) {
    val isConnected by viewModel.isConnected.collectAsState()
    val isConnecting by viewModel.isConnecting.collectAsState()
    val pumpStates by viewModel.pumpStates.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val initialIp by viewModel.initialIp.collectAsState()
    val telemetryData by viewModel.telemetry.collectAsState()

    var ipAddress by remember(initialIp) { mutableStateOf(initialIp) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hardware Diagnostics", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(top = 8.dp, start = 16.dp, end = 16.dp, bottom = 16.dp)
        ) {
            // Scrollable pump controls — fills available space
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Connection Field
                OutlinedTextField(
                    value = ipAddress,
                    onValueChange = { ipAddress = it },
                    label = { Text("ESP32 IP / Hostname") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isConnected && !isConnecting,
                    colors = OutlinedTextFieldDefaults.colors(
                        disabledTextColor = MaterialTheme.colorScheme.onSurface,
                        disabledBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        disabledLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    trailingIcon = {
                        TextButton(
                            onClick = {
                                if (isConnected) viewModel.disconnect()
                                else if (!isConnecting) viewModel.connect(ipAddress)
                            },
                            enabled = !isConnecting,
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = when {
                                    isConnected -> MaterialTheme.colorScheme.error
                                    isConnecting -> MaterialTheme.colorScheme.onSurfaceVariant
                                    else -> MaterialTheme.colorScheme.primary
                                }
                            )
                        ) {
                            Text(when {
                                isConnected -> "Disconnect"
                                isConnecting -> "Connecting..."
                                else -> "Connect"
                            })
                        }
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = if (isConnected) Icons.Default.SignalWifi4Bar else Icons.Default.WifiOff,
                            contentDescription = null,
                            tint = if (isConnected) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
                        )
                    }
                )

                // Data activity indicator
                DataActivityIndicator(isActive = isConnecting || isConnected, isConnecting = isConnecting)

                // Status Section
                DiagnosticsStatusSection(viewModel)

                // Section Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Pumps",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    FilledTonalIconButton(
                        onClick = { viewModel.refreshStatus() },
                        enabled = isConnected,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(18.dp))
                    }
                }

                // Master All Pumps Switch
                AllPumpsRow(
                    pumpStates = pumpStates,
                    enabled = isConnected,
                    viewModel = viewModel
                )

                // Pump Controls - Compact Rows
                PumpRow(
                    id = 1, icon = Icons.Default.WaterDrop,
                    isOn = pumpStates[1] ?: false, enabled = isConnected,
                    rawMoisture = telemetryData?.raw_soil?.getOrNull(0),
                    viewModel = viewModel
                )
                PumpRow(
                    id = 2, icon = Icons.Default.Spa,
                    isOn = pumpStates[2] ?: false, enabled = isConnected,
                    rawMoisture = telemetryData?.raw_soil?.getOrNull(1),
                    viewModel = viewModel
                )
                PumpRow(
                    id = 3, icon = Icons.Default.Grass,
                    isOn = pumpStates[3] ?: false, enabled = isConnected,
                    rawMoisture = telemetryData?.raw_soil?.getOrNull(2),
                    viewModel = viewModel
                )
                PumpRow(
                    id = 4, icon = Icons.Default.Park,
                    isOn = pumpStates[4] ?: false, enabled = isConnected,
                    rawMoisture = telemetryData?.raw_soil?.getOrNull(3),
                    viewModel = viewModel
                )
            }

            // Communication Log — fills remaining space
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 2.dp)
            ) {
                Text(
                    "Communication Log",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(8.dp))
                PulsingDot(isVisible = isConnected)
            }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                color = Color(0xFF0D1117),
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF21262D))
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp),
                    reverseLayout = false
                ) {
                    items(logs) { log ->
                        LogLine(log)
                    }
                }
            }
        }
    }
}

@Composable
fun AllPumpsRow(
    pumpStates: Map<Int, Boolean>,
    enabled: Boolean,
    viewModel: PumpTestViewModel
) {
    val allOn = pumpStates.values.all { it }
    val anyOn = pumpStates.values.any { it }
    val onCount = pumpStates.values.count { it }
    val activeColor = Color(0xFF2E7D32)

    Surface(
        color = if (allOn) activeColor.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(12.dp),
        border = if (allOn) androidx.compose.foundation.BorderStroke(1.dp, activeColor.copy(alpha = 0.5f)) else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(
                        if (anyOn) activeColor.copy(alpha = 0.15f)
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Power,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (anyOn) activeColor else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "All Pumps",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (anyOn) activeColor else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    if (allOn) "All pumps ON"
                    else if (anyOn) "$onCount of 4 pumps ON"
                    else "All pumps OFF",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = allOn,
                onCheckedChange = { viewModel.turnAllPumps(it) },
                enabled = enabled
            )
        }
    }
}

@Composable
fun PumpRow(
    id: Int,
    icon: ImageVector,
    isOn: Boolean,
    enabled: Boolean,
    rawMoisture: Int?,
    viewModel: PumpTestViewModel
) {
    val activeColor = Color(0xFF2E7D32)

    Surface(
        color = if (isOn) activeColor.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (isOn) activeColor else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                "Pump $id",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(52.dp)
            )
            if (rawMoisture != null) {
                Text(
                    text = "Raw: $rawMoisture",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
            Text(
                if (isOn) "ON" else "OFF",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (isOn) activeColor else Color.Gray,
                modifier = Modifier.padding(end = 6.dp)
            )
            Switch(
                checked = isOn,
                onCheckedChange = { viewModel.togglePump(id, it) },
                enabled = enabled
            )
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
            color = Color(0xFFFF6B6B)
        }
        log.startsWith("ESP32") -> {
            prefix = "RX"
            message = log.removePrefix("ESP32: ").removePrefix("ESP32")
            color = Color(0xFF7EE787)
        }
        log.startsWith("App") -> {
            prefix = "TX"
            message = log.removePrefix("App: Sent ").removePrefix("App")
            color = Color(0xFF79C0FF)
        }
        log.startsWith("System") -> {
            prefix = "SYS"
            message = log.removePrefix("System: ").removePrefix("System")
            color = Color(0xFF8B949E)
        }
        else -> {
            prefix = ">>>"
            message = log
            color = Color(0xFFC9D1D9)
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
                    .background(Color(0xFF2E7D32).copy(alpha = alpha))
            )
        }
    }
}

@Composable
fun DataActivityIndicator(isActive: Boolean, isConnecting: Boolean = false) {
    val transition = rememberInfiniteTransition(label = "data_activity")
    val dotCount = 3
    val dots = List(dotCount) { index ->
        val delay = index * 200
        transition.animateFloat(
            initialValue = 0.2f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(600, delayMillis = delay, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "dot_$index"
        )
    }

    if (isActive) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            dots.forEach { dotAlpha ->
                val alpha by dotAlpha
                Box(
                    modifier = Modifier
                        .padding(horizontal = 2.dp)
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha))
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                if (isConnecting) "Connecting..." else "Data active",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun DiagnosticsStatusSection(viewModel: PumpTestViewModel) {
    val telemetryData by viewModel.telemetry.collectAsState()
    val telemetry = telemetryData

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatusItem(
                    icon = Icons.Default.SignalWifiStatusbar4Bar,
                    label = "RSSI",
                    value = "${telemetry?.wifi_rssi ?: "--"} dBm",
                    color = when {
                        (telemetry?.wifi_rssi ?: -100) > -60 -> Color(0xFF2E7D32)
                        (telemetry?.wifi_rssi ?: -100) > -80 -> Color(0xFFFBC02D)
                        else -> Color(0xFFD32F2F)
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
private fun StatusItem(
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

private fun formatUptime(seconds: Long): String {
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

private fun formatEpoch(epoch: Long): String {
    if (epoch == 0L) return "--"
    val date = java.util.Date(epoch * 1000)
    val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
    return sdf.format(date)
}
