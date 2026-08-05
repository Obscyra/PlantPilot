package com.plantpilot.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

import com.plantpilot.data.ConnectionState
import com.plantpilot.util.bounceClick
import com.plantpilot.viewmodel.PlantPilotViewModel

@Composable
fun ConnectionStatusChip(
    connectionState: ConnectionState,
    modifier: Modifier = Modifier,
    isSyncing: Boolean = false,
    isConfigDirty: Boolean = false,
    onClick: () -> Unit
) {
    val label: String
    val icon: ImageVector?
    val containerColor: Color
    val contentColor: Color
    var showSpinner = false
    when (connectionState) {
        is ConnectionState.Connected -> {
            if (isSyncing) {
                label = "Updating..."
                icon = null
                showSpinner = true
                containerColor = MaterialTheme.colorScheme.surfaceVariant
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                label = if (isConfigDirty) "Update" else "Connected"
                icon = if (isConfigDirty) Icons.Default.CloudUpload else Icons.Default.Wifi
                containerColor = MaterialTheme.colorScheme.primaryContainer
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            }
        }
        is ConnectionState.Connecting -> {
            label = "Connecting..."
            icon = null
            showSpinner = true
            containerColor = MaterialTheme.colorScheme.surfaceVariant
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        }
        is ConnectionState.Reconnecting -> {
            label = "Reconnecting..."
            icon = null
            showSpinner = true
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
        }
        is ConnectionState.Failed -> {
            label = "Offline"
            icon = Icons.Default.WifiOff
            containerColor = MaterialTheme.colorScheme.errorContainer
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        }
        is ConnectionState.Disconnected -> {
            label = "Disconnected"
            icon = Icons.Default.WifiOff
            containerColor = MaterialTheme.colorScheme.surfaceVariant
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        }
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        modifier = modifier.bounceClick(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showSpinner) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = contentColor
                )
            } else {
                Icon(
                    imageVector = icon!!,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = contentColor
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = contentColor
            )
        }
    }
}

/** Convenience wrapper that reads the live connection state from the
 *  ViewModel and renders the unified status chip. Used in every tab's TopAppBar. */
@Composable
fun ConnectionStatusChip(
    viewModel: PlantPilotViewModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.displayConnectionState.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val isConfigDirty by viewModel.isConfigDirty.collectAsState()
    ConnectionStatusChip(
        connectionState = state,
        isSyncing = isSyncing,
        isConfigDirty = isConfigDirty,
        modifier = modifier,
        onClick = onClick
    )
}

@Composable
fun DeviceConnectionDialog(
    connectionState: ConnectionState,
    deviceIp: String,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit
) {
    val isConnected = connectionState == ConnectionState.Connected
    val isConnecting = connectionState == ConnectionState.Connecting
    val isReconnecting = connectionState == ConnectionState.Reconnecting
    val isFailed = connectionState == ConnectionState.Failed

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "PilotCore Connection",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when {
                        isConnecting || isReconnecting -> CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        isConnected -> Icon(
                            Icons.Default.Wifi,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        else -> Icon(
                            Icons.Default.WifiOff,
                            contentDescription = null,
                            tint = if (isFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        when {
                            isConnecting -> "Connecting to PilotCore..."
                            isReconnecting -> "Reconnecting to PilotCore..."
                            isConnected -> "Connected to PilotCore"
                            isFailed -> "Connection failed — device unreachable"
                            else -> "Not connected to PilotCore"
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (deviceIp.isNotBlank()) {
                    Text(
                        "Device IP: $deviceIp",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            if (isConnecting || isReconnecting) {
                TextButton(onClick = onDismiss, enabled = false) {
                    Text("Connecting...")
                }
            } else if (isConnected) {
                Button(
                    onClick = {
                        onDisconnect()
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Icon(Icons.Default.WifiOff, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Disconnect")
                }
            } else {
                Button(
                    onClick = {
                        onConnect()
                        onDismiss()
                    }
                ) {
                    Icon(Icons.Default.Wifi, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isFailed) "Retry" else "Connect")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun AlertBanner(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.bounceClick().clickable { onClick() } else Modifier),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.bounceClick()
            ) {
                Text("Dismiss")
            }
        }
    }
}

@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.WaterDrop,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
