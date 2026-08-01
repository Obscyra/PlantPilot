package com.plantpilot.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.plantpilot.ui.components.*
import com.plantpilot.viewmodel.PlantPilotViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: PlantPilotViewModel,
    onPlantClick: (String) -> Unit,
) {
    val plants by viewModel.plants.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val deviceState by viewModel.deviceState.collectAsState()
    val isConnecting by viewModel.isConnecting.collectAsState()
    val isConnected = deviceState.isConnected && viewModel.connectionError == null
    var isRefreshing by remember { mutableStateOf(value = false) }
    var showWateringSnackbar by remember { mutableStateOf(value = false) }
    var wateringPlantName by remember { mutableStateOf(value = "") }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showConnectionDialog by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel.isRefreshingDevice) {
        if (viewModel.isRefreshingDevice) {
            snackbarHostState.showSnackbar(
                message = "Refreshing device status...",
                duration = SnackbarDuration.Short
            )
        }
    }

    LaunchedEffect(viewModel.connectionError) {
        viewModel.connectionError?.let { error ->
            snackbarHostState.showSnackbar(
                message = "Device Error: $error",
                duration = SnackbarDuration.Short
            )
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "PlantPilot",
                        fontWeight = FontWeight.Bold,
                    )
                },
                actions = {
                    DeviceConnectionChip(
                        isConnected = isConnected,
                        isConnecting = isConnecting
                    ) { showConnectionDialog = true }
                    Spacer(modifier = Modifier.width(8.dp))
                },
            )
        },
    ) { paddingValues ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                scope.launch {
                    isRefreshing = true
                    viewModel.refreshData()
                    delay(800.milliseconds)
                    isRefreshing = false
                }
            },
            modifier = Modifier.padding(paddingValues = paddingValues),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(all = 16.dp),
                verticalArrangement = Arrangement.spacedBy(space = 12.dp),
            ) {
                // Water tank card
                item(key = "water_tank") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.extraLarge,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                    ) {
                        WaterTankIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(all = 24.dp),
                            level = deviceState.waterTankLevel,
                            tankCapacityMl = deviceState.tankCapacityMl,
                            sensorValue = deviceState.waterTankSensorValue,
                        )
                    }
                }

                // Plants section header
                item(key = "plants_header") {
                    Text(
                        text = "Plants",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }

                // Plant cards
                if (viewModel.isLoading) {
                    items(count = 4) {
                        ShimmerPlantCard()
                    }
                } else {
                    items(items = plants, key = { it.id }) { plant ->
                        PlantCard(
                            plant = plant,
                            use24HourFormat = settings.use24HourFormat,
                            onWaterNow = {
                                scope.launch {
                                    wateringPlantName = plant.name
                                    showWateringSnackbar = true

                                    // Fire the actual watering in the background
                                    val wateringJob = async { viewModel.waterPlant(plantId = plant.id) }

                                    // Animation lasts exactly 3 seconds regardless of watering duration
                                    delay(3000)
                                    showWateringSnackbar = false

                                    if (wateringJob.await()) {
                                        // Watering complete - no message needed here
                                    } else {
                                        snackbarHostState.showSnackbar(
                                            message = "Failed to reach device",
                                            duration = SnackbarDuration.Short,
                                        )
                                    }
                                }
                            },
                            onClick = { onPlantClick(plant.id) },
                            modifier = Modifier.animateItem()
                        )
                    }
                }
            }
        }

        // Loading overlay
        if (showWateringSnackbar) {
            WateringOverlay(plantName = wateringPlantName)
        }
    }

    // Connection dialog
    if (showConnectionDialog) {
        DeviceConnectionDialog(
            isConnected = isConnected,
            isConnecting = isConnecting,
            deviceIp = deviceState.deviceIp,
            onConnect = { viewModel.connectToDevice() },
            onDisconnect = { viewModel.disconnectFromDevice() },
            onDismiss = { showConnectionDialog = false }
        )
    }
}
