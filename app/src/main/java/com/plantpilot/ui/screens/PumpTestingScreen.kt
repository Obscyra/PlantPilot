package com.plantpilot.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.plantpilot.viewmodel.PumpTestViewModel
import com.plantpilot.ui.components.ConnectionStatusChip
import com.plantpilot.ui.theme.*
import com.plantpilot.util.bounceClick
import com.plantpilot.ui.components.PulsingDot

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PumpTestingScreen(
    viewModel: PumpTestViewModel = viewModel(),
    onBack: () -> Unit,
    onStatusChipClick: () -> Unit = {}
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val canSendCommands = com.plantpilot.data.ConnectionStateHelper.canSendCommands(connectionState)
    val canDisplayLastKnownData = com.plantpilot.data.ConnectionStateHelper.canDisplayLastKnownData(connectionState)
    val displayConnectionState by viewModel.displayConnectionState.collectAsState()
    val pumpStates by viewModel.pumpStates.collectAsState()
    val logs by viewModel.logs.collectAsState()

    val terminalLogs = remember(logs) {
        logs.filter { log ->
            val isTelemetry = log.contains("\"type\":\"telemetry\"")
            !isTelemetry && (
                    log.contains("PUMP", ignoreCase = true) ||
                            log.contains("watering", ignoreCase = true) ||
                            log.contains("system", ignoreCase = true) ||
                            log.contains("error", ignoreCase = true) ||
                            log.contains("\"type\":\"ok\"") ||
                            log.contains("Pump", ignoreCase = true)
                    )
        }
    }

    val terminalScroll = rememberScrollState()
    LaunchedEffect(terminalLogs.size) {
        terminalScroll.animateScrollTo(Int.MAX_VALUE)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pump Testing", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    ConnectionStatusChip(
                        connectionState = displayConnectionState,
                        onClick = onStatusChipClick
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(top = 16.dp, start = 16.dp, end = 16.dp, bottom = 16.dp)
        ) {
            // Scrollable pump controls — fills available space
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Data activity indicator
                DataActivityIndicator(isActive = canDisplayLastKnownData || displayConnectionState == com.plantpilot.data.ConnectionState.Connecting, isConnecting = displayConnectionState == com.plantpilot.data.ConnectionState.Connecting, isReconnecting = displayConnectionState == com.plantpilot.data.ConnectionState.Reconnecting)

                Spacer(modifier = Modifier.height(8.dp))

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
                        enabled = canSendCommands,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(18.dp))
                    }
                }

                // Pump Controls - Compact Rows
                PumpRow(
                    id = 1, icon = Icons.Default.WaterDrop,
                    isOn = pumpStates[1] ?: false, enabled = canSendCommands,
                    viewModel = viewModel
                )
                PumpRow(
                    id = 2, icon = Icons.Default.Spa,
                    isOn = pumpStates[2] ?: false, enabled = canSendCommands,
                    viewModel = viewModel
                )
                PumpRow(
                    id = 3, icon = Icons.Default.Grass,
                    isOn = pumpStates[3] ?: false, enabled = canSendCommands,
                    viewModel = viewModel
                )
                PumpRow(
                    id = 4, icon = Icons.Default.Park,
                    isOn = pumpStates[4] ?: false, enabled = canSendCommands,
                    viewModel = viewModel
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Output Terminal
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Terminal,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Pump Logs",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.7f)
                    .animateContentSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(TerminalBackground)
                    .verticalScroll(state = terminalScroll)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                if (terminalLogs.isEmpty()) {
                    Text(
                        text = if (canDisplayLastKnownData) "Waiting for pump events..." else "Device offline",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = TerminalPlaceholder
                    )
                } else {
                    terminalLogs.asReversed().forEach { line ->
                        Text(
                            text = line,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = if (line.contains("error", ignoreCase = true)) LogError else TerminalGreen
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PumpRow(
    id: Int,
    icon: ImageVector,
    isOn: Boolean,
    enabled: Boolean,
    viewModel: PumpTestViewModel
) {
    val activeColor = MaterialTheme.colorScheme.primary

    Surface(
        color = if (isOn) activeColor.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.bounceClick(),
        onClick = { if (enabled) viewModel.togglePump(id, !isOn) }
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
            
            Spacer(modifier = Modifier.weight(1f))
            
            Text(
                if (isOn) "ON" else "OFF",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (isOn) activeColor else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 6.dp)
            )
            Switch(
                checked = isOn,
                onCheckedChange = null,
                enabled = enabled
            )
        }
    }
}


@Composable
fun DataActivityIndicator(isActive: Boolean, isConnecting: Boolean = false, isReconnecting: Boolean = false) {
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
                when {
                    isConnecting -> "Connecting..."
                    isReconnecting -> "Reconnecting..."
                    else -> "Data active"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

