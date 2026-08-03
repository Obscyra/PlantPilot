package com.plantpilot.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.plantpilot.viewmodel.PumpTestViewModel
import com.plantpilot.ui.components.*
import com.plantpilot.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SerialOutputScreen(
    viewModel: PumpTestViewModel = viewModel(),
    onBack: () -> Unit,
    onStatusChipClick: () -> Unit = {}
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val canSendCommands = connectionState == com.plantpilot.data.ConnectionState.Connected
    val canDisplayLastKnownData = connectionState == com.plantpilot.data.ConnectionState.Connected || connectionState == com.plantpilot.data.ConnectionState.Reconnecting
    val displayConnectionState by viewModel.displayConnectionState.collectAsState()
    val logs by viewModel.logs.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PilotCore Serial Output", fontWeight = FontWeight.Bold) },
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
                .padding(16.dp)
        ) {
            // System Telemetry Section
            Text(
                "System Telemetry",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            DiagnosticsStatusSection(viewModel)

            Spacer(modifier = Modifier.height(24.dp))

            // Communication Log
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Live Serial Output",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(8.dp))
                PulsingDot(isVisible = canDisplayLastKnownData)
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = { viewModel.refreshStatus() }, enabled = canSendCommands) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Refresh Status", style = MaterialTheme.typography.labelSmall)
                }
            }
            
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .animateContentSize(),
                color = TerminalBackground,
                shape = MaterialTheme.shapes.medium,
                border = androidx.compose.foundation.BorderStroke(1.dp, TerminalBorder)
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
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
