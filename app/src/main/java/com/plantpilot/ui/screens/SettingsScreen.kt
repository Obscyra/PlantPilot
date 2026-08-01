package com.plantpilot.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.plantpilot.R
import com.plantpilot.model.AppSettings
import com.plantpilot.model.DeviceState
import com.plantpilot.ui.components.CalibrationBottomSheet
import com.plantpilot.viewmodel.PlantPilotViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: PlantPilotViewModel,
    onShowOnboarding: () -> Unit,
    onNavigateToPumpTest: () -> Unit,
) {
    val deviceState by viewModel.deviceState.collectAsState()
    val settings by viewModel.settings.collectAsState()

    var deviceName by remember(deviceState.deviceName) { mutableStateOf(value = deviceState.deviceName) }
    var deviceIp by remember(deviceState.deviceIp) { mutableStateOf(value = deviceState.deviceIp) }
    var tankCapacity by remember(deviceState.tankCapacityMl) { mutableStateOf(value = deviceState.tankCapacityMl.toString()) }
    var showConnectDialog by remember { mutableStateOf(value = false) }
    var connectStep by remember { mutableIntStateOf(value = 0) }
    var showDeveloperInfo by remember { mutableStateOf(value = false) }
    var showCalibrationSheet by remember { mutableStateOf(value = false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(viewModel.connectionError) {
        viewModel.connectionError?.let { error ->
            snackbarHostState.showSnackbar(
                message = "Connection Error: $error",
                duration = SnackbarDuration.Long
            )
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings",
                        fontWeight = FontWeight.Bold,
                    )
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues = paddingValues)
                .verticalScroll(state = rememberScrollState())
                .padding(all = 16.dp),
            verticalArrangement = Arrangement.spacedBy(space = 16.dp),
        ) {
            // Device section
            Text(
                text = "Device",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )

            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(all = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(space = 12.dp),
                ) {
                    OutlinedTextField(
                        value = deviceName,
                        onValueChange = {
                            deviceName = it
                            viewModel.updateDeviceState { s -> s.copy(deviceName = it) }
                        },
                        label = { Text("Device Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )

                    OutlinedTextField(
                        value = deviceIp,
                        onValueChange = {
                            deviceIp = it
                            viewModel.updateDeviceState { s -> s.copy(deviceIp = it) }
                        },
                        label = { Text("IP Address / Hostname") },
                        placeholder = { Text("e.g. 192.168.1.50") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Dns, contentDescription = null) }
                    )

                    OutlinedTextField(
                        value = deviceState.wifiSsid,
                        onValueChange = {
                            viewModel.updateDeviceState { s -> s.copy(wifiSsid = it) }
                        },
                        label = { Text("Wi-Fi Network SSID") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Wifi, contentDescription = null) }
                    )

                    if (deviceState.isConnected && viewModel.connectionError == null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Connected", color = MaterialTheme.colorScheme.primary)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                scope.launch {
                                    viewModel.refreshData()
                                    // Wait for the operation to complete
                                    delay(200.milliseconds) // Small buffer
                                    if (viewModel.connectionError == null) {
                                        snackbarHostState.showSnackbar("Connected successfully!")
                                    }
                                }
                            },
                            enabled = !viewModel.isRefreshingDevice,
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.small,
                        ) {
                            if (viewModel.isRefreshingDevice) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(size = 18.dp),
                                )
                            }
                            Spacer(modifier = Modifier.width(width = 8.dp))
                            Text(text = if (viewModel.isRefreshingDevice) "Checking..." else "Check Connection")
                        }
                    }
                }
            }

            // Water tank section
            Text(
                text = "Water Tank",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )

            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = tankCapacity,
                        onValueChange = {
                            tankCapacity = it
                            it.toIntOrNull()?.let { capacity ->
                                viewModel.updateDeviceState { s -> s.copy(tankCapacityMl = capacity) }
                            }
                        },
                        label = { Text("Tank Capacity (ml)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Text(
                        text = "Low water alert at level ${deviceState.lowWaterThreshold}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Slider(
                        value = deviceState.lowWaterThreshold.toFloat(),
                        onValueChange = { threshold ->
                            viewModel.updateDeviceState { s -> s.copy(lowWaterThreshold = threshold.toInt()) }
                        },
                        valueRange = 0f..4f,
                        steps = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Notifications section
            Text(
                text = "Notifications",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )

            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Low water alerts", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                            Text("Get notified when tank is low", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = settings.notificationsLowWater,
                            onCheckedChange = { viewModel.updateSettings { s -> s.copy(notificationsLowWater = it) } }
                        )
                    }
                }
            }

            // App preferences
            Text(
                text = "Preferences",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )

            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Units
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Metric units (ml)", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        Switch(
                            checked = settings.useMetricUnits,
                            onCheckedChange = { viewModel.updateSettings { s -> s.copy(useMetricUnits = it) } }
                        )
                    }

                    HorizontalDivider()

                    // Time Format
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("24-Hour Format", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        Switch(
                            checked = settings.use24HourFormat,
                            onCheckedChange = { viewModel.updateSettings { s -> s.copy(use24HourFormat = it) } }
                        )
                    }
                }
            }

            // Diagnostics section
            Text(
                text = "Diagnostics",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )

            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column {
                    Surface(
                        onClick = onNavigateToPumpTest,
                        color = Color.Transparent,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Hardware,
                                    contentDescription = null,
                                    modifier = Modifier.size(22.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Pump Hardware Testing",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Test individual pumps and monitor the communication log",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    HorizontalDivider()

                    Surface(
                        onClick = { showCalibrationSheet = true },
                        color = Color.Transparent,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.tertiaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Grain,
                                    contentDescription = null,
                                    modifier = Modifier.size(22.dp),
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Sensor Calibration",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Set dry and wet readings for accurate moisture levels",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (showCalibrationSheet) {
                CalibrationBottomSheet(
                    onDismiss = { showCalibrationSheet = false },
                    onSave = { sensorId, dry, wet ->
                        scope.launch {
                            snackbarHostState.showSnackbar("Sensor $sensorId calibrated (Dry: $dry, Wet: $wet)")
                        }
                    }
                )
            }

            // About section
            Text(
                text = "About",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )

            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Version", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        Text("1.0.0", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    HorizontalDivider()

                    TextButton(onClick = { showDeveloperInfo = !showDeveloperInfo }) {
                        Icon(
                            imageVector = if (showDeveloperInfo) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Developer Info")
                    }

                    AnimatedVisibility(visible = showDeveloperInfo) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            DeveloperCard(
                                name = "Fahim Shahriar",
                                githubUrl = "https://github.com/placeholder_fahim",
                                facebookUrl = "https://facebook.com/placeholder_fahim",
                                linkedinUrl = "https://linkedin.com/in/placeholder_fahim",
                                onLinkClick = { uriHandler.openUri(it) }
                            )

                            DeveloperCard(
                                name = "Mahim Chowdhury Miraj",
                                githubUrl = "https://github.com/placeholder_mahim",
                                facebookUrl = "https://facebook.com/placeholder_mahim",
                                linkedinUrl = "https://linkedin.com/in/placeholder_mahim",
                                onLinkClick = { uriHandler.openUri(it) }
                            )
                        }
                    }

                    HorizontalDivider()

                    TextButton(onClick = onShowOnboarding) {
                        Icon(imageVector = Icons.Default.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("View Onboarding")
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // Connect device dialog
    if (showConnectDialog) {
        AlertDialog(
            onDismissRequest = { showConnectDialog = false },
            title = { Text("Connect Device") },
            text = {
                Column {
                    when (connectStep) {
                        0 -> {
                            Text("Scanning for devices...", style = MaterialTheme.typography.bodyMedium)
                            Spacer(modifier = Modifier.height(12.dp))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        1 -> {
                            Text("Found:", style = MaterialTheme.typography.bodyMedium)
                            Spacer(modifier = Modifier.height(8.dp))
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Wifi, contentDescription = null, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("PlantPilot-PilotCore", style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            LinearProgressIndicator(progress = { 0.5f }, modifier = Modifier.fillMaxWidth())
                        }
                        2 -> {
                            Text("Connecting to PlantPilot-PilotCore...", style = MaterialTheme.typography.bodyMedium)
                            Spacer(modifier = Modifier.height(12.dp))
                            LinearProgressIndicator(progress = { 0.8f }, modifier = Modifier.fillMaxWidth())
                        }
                        3 -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Connected!", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                if (connectStep < 3) {
                    TextButton(onClick = { showConnectDialog = false }) {
                        Text("Cancel")
                    }
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    }
}

@Composable
private fun DeveloperCard(
    name: String,
    githubUrl: String,
    facebookUrl: String,
    linkedinUrl: String,
    onLinkClick: (String) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { onLinkClick(githubUrl) }) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_github),
                        contentDescription = "GitHub",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = { onLinkClick(facebookUrl) }) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_facebook),
                        contentDescription = "Facebook",
                        tint = Color.Unspecified,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = { onLinkClick(linkedinUrl) }) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_linkedin),
                        contentDescription = "LinkedIn",
                        tint = Color.Unspecified,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
