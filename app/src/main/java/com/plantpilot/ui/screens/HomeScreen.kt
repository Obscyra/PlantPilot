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
    onStatusChipClick: () -> Unit,
    onPlantClick: (String) -> Unit,
) {
    val plants by viewModel.plants.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val deviceState by viewModel.deviceState.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val isRefreshing = viewModel.isRefreshingDevice
    val canSendCommands = com.plantpilot.data.ConnectionStateHelper.canSendCommands(connectionState)
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.commandBlockedEvents.collect { message ->
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
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
                    // Unified live status chip; connection is silent/automatic,
                    // so no error snackbars are shown for offline/retry.
                    ConnectionStatusChip(
                        viewModel = viewModel,
                        onClick = onStatusChipClick
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                },
            )
        },
    ) { paddingValues ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                scope.launch {
                    viewModel.refreshData()
                }
            },
            modifier = Modifier.padding(paddingValues = paddingValues),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(all = 16.dp),
                verticalArrangement = Arrangement.spacedBy(space = 12.dp),
            ) {
                if (settings.demoMode) {
                    item(key = "demo_mode_banner") {
                        com.plantpilot.ui.components.DemoModeBanner(
                            onTurnOff = { viewModel.setDemoMode(false) }
                        )
                    }
                }

                // Water tank card
                item(key = "water_tank") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.extraLarge,
                        border = androidx.compose.foundation.BorderStroke(1.dp, com.plantpilot.ui.theme.CardGlassBorderMuted),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                    ) {
                        WaterTankIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(all = 16.dp),
                            tankCapacityMl = deviceState.tankCapacityMl,
                            estimatedWaterMl = deviceState.estimatedWaterMl,
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
                            waterEnabled = canSendCommands,
                            onWaterNow = {
                                scope.launch {
                                    if (viewModel.waterPlant(plantId = plant.id)) {
                                        // Watering complete
                                    } else {
                                        snackbarHostState.showSnackbar(
                                            message = "Failed to reach device",
                                            duration = SnackbarDuration.Short,
                                        )
                                    }
                                }
                            },
                            onClick = { onPlantClick(plant.id) }
                        )
                    }
                }
            }
        }
    }
}
