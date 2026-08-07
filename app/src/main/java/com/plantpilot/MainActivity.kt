package com.plantpilot

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.plantpilot.navigation.Screen
import com.plantpilot.navigation.bottomNavItems
import com.plantpilot.ui.components.DeviceConnectionDialog
import com.plantpilot.ui.components.WateringOverlay
import com.plantpilot.ui.screens.*
import com.plantpilot.ui.theme.PlantPilotTheme
import com.plantpilot.viewmodel.PlantPilotViewModel
import com.plantpilot.viewmodel.PumpTestViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: PlantPilotViewModel by viewModels()
    private val hardwareConnection get() = (application as PlantPilotApp).hardwareConnection

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition {
            viewModel.isLoadingOnboarding
        }
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
        }

        // Request max refresh rate mode (90Hz / 120Hz / 144Hz) for smooth display scrolling on Android 14, 15 & 16
        try {
            val displayObj = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display
            } else {
                @Suppress("DEPRECATION")
                window.windowManager.defaultDisplay
            }
            val modes = displayObj?.supportedModes
            val maxMode = modes?.maxByOrNull { it.refreshRate }
            if (maxMode != null) {
                val lp = window.attributes
                lp.preferredDisplayModeId = maxMode.modeId
                window.attributes = lp
            }
        } catch (_: Exception) {
            // Safe fallback for custom ROMs / legacy vendor drivers
        }

        // The background sync service posts a notification on Android 13+;
        // request that permission once so the notification is actually visible.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATION_PERMISSION
            )
        }

        try {
            ContextCompat.startForegroundService(this, Intent(this, SyncService::class.java))
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to start SyncService", e)
        }

        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    hardwareConnection.setIsBackgrounded(false)
                    hardwareConnection.setStreamCadence(viewModel.settings.value.sensorCadenceSec)
                }
                Lifecycle.Event.ON_RESUME -> {
                    // Re-check the ESP32 connection every time the app returns to
                    // the foreground (recents switcher, back to app, etc.).
                    viewModel.onAppResumed()
                }
                Lifecycle.Event.ON_STOP -> {
                    hardwareConnection.setIsBackgrounded(true)
                    hardwareConnection.setStreamCadence(30)
                }
                else -> {}
            }
        })
        setContent {
            PlantPilotTheme {
                PlantPilotApp(viewModel)
            }
        }
    }

    companion object {
        private const val REQUEST_NOTIFICATION_PERMISSION = 1
    }
}

@Composable
fun PlantPilotApp(viewModel: PlantPilotViewModel) {
    if (viewModel.isLoadingOnboarding) return

    val navController = rememberNavController()
    val showOnboarding = viewModel.showOnboarding

    if (showOnboarding) {
        OnboardingScreen(viewModel = viewModel)
    } else {
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = navBackStackEntry?.destination?.route

        val showBottomBar = currentRoute in bottomNavItems.map { it.route }

        // Shared connection status + dialog so every tab shows the same
        // unified chip (Disconnected / Connecting / Connected / Updating).
        val deviceState by viewModel.deviceState.collectAsState()
        var showConnectionDialog by remember { mutableStateOf(false) }
        val onStatusChipClick: () -> Unit = {
            // Tapping the chip: push any pending local config first when
            // connected. If we start a sync, we don't necessarily need to
            // show the connection dialog immediately.
            if (viewModel.isConfigDirty.value && viewModel.canSendCommands) {
                viewModel.syncConfigWithDevice()
            } else {
                showConnectionDialog = true
            }
        }

        Scaffold(
            bottomBar = {
                AnimatedVisibility(
                    visible = showBottomBar,
                    enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { it },
                    exit = fadeOut(tween(400)) + slideOutVertically(tween(400)) { it }
                ) {
                    NavigationBar {
                        bottomNavItems.forEach { item ->
                            NavigationBarItem(
                                selected = currentRoute == item.route,
                                onClick = {
                                    navController.navigate(item.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = item.icon,
                                        contentDescription = item.label
                                    )
                                },
                                label = { Text(item.label) }
                            )
                        }
                    }
                }
            }
        ) { scaffoldPadding ->
            val bottomPadding by animateDpAsState(
                targetValue = if (showBottomBar) scaffoldPadding.calculateBottomPadding() else 0.dp,
                animationSpec = tween(400),
                label = "nav_host_bottom_padding"
            )

            val isWateringPlantId by viewModel.isWateringPlantId.collectAsState()
            val plants by viewModel.plants.collectAsState()
            val wateringPlantName = plants.find { it.id == isWateringPlantId }?.name ?: ""

            Box(modifier = Modifier.fillMaxSize()) {
                NavHost(
                    navController = navController,
                    startDestination = Screen.Home.route,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            top = 0.dp,
                            bottom = bottomPadding
                        ),
                enterTransition = {
                    val initialRoute = initialState.destination.route
                    val targetRoute = targetState.destination.route
                    val initialIndex = bottomNavItems.indexOfFirst { it.route == initialRoute }
                    val targetIndex = bottomNavItems.indexOfFirst { it.route == targetRoute }

                    val duration = 350
                    val easing = androidx.compose.animation.core.FastOutSlowInEasing

                    if (initialIndex != -1 && targetIndex != -1) {
                        val direction = if (targetIndex > initialIndex) AnimatedContentTransitionScope.SlideDirection.Start
                                        else AnimatedContentTransitionScope.SlideDirection.End
                        fadeIn(animationSpec = tween(duration, easing = easing)) +
                        slideIntoContainer(
                            towards = direction,
                            animationSpec = tween(duration, easing = easing),
                            initialOffset = { (it * 0.25f).toInt() }
                        )
                    } else {
                        fadeIn(animationSpec = tween(duration, easing = easing)) +
                        slideIntoContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.Start,
                            animationSpec = tween(duration, easing = easing)
                        )
                    }
                },
                exitTransition = {
                    val initialRoute = initialState.destination.route
                    val targetRoute = targetState.destination.route
                    val initialIndex = bottomNavItems.indexOfFirst { it.route == initialRoute }
                    val targetIndex = bottomNavItems.indexOfFirst { it.route == targetRoute }

                    val duration = 350
                    val easing = androidx.compose.animation.core.FastOutSlowInEasing

                    if (initialIndex != -1 && targetIndex != -1) {
                        val direction = if (targetIndex > initialIndex) AnimatedContentTransitionScope.SlideDirection.Start
                                        else AnimatedContentTransitionScope.SlideDirection.End
                        fadeOut(animationSpec = tween(duration, easing = easing)) +
                        slideOutOfContainer(
                            towards = direction,
                            animationSpec = tween(duration, easing = easing),
                            targetOffset = { (it * 0.25f).toInt() }
                        )
                    } else {
                        fadeOut(animationSpec = tween(duration, easing = easing)) +
                        scaleOut(targetScale = 0.95f, animationSpec = tween(duration, easing = easing))
                    }
                },
                popEnterTransition = {
                    val initialRoute = initialState.destination.route
                    val targetRoute = targetState.destination.route
                    val initialIndex = bottomNavItems.indexOfFirst { it.route == initialRoute }
                    val targetIndex = bottomNavItems.indexOfFirst { it.route == targetRoute }

                    val duration = 350
                    val easing = androidx.compose.animation.core.FastOutSlowInEasing

                    if (initialIndex != -1 && targetIndex != -1) {
                        val direction = if (targetIndex > initialIndex) AnimatedContentTransitionScope.SlideDirection.Start
                                        else AnimatedContentTransitionScope.SlideDirection.End
                        fadeIn(animationSpec = tween(duration, easing = easing)) +
                        slideIntoContainer(
                            towards = direction,
                            animationSpec = tween(duration, easing = easing),
                            initialOffset = { (it * 0.25f).toInt() }
                        )
                    } else {
                        fadeIn(animationSpec = tween(duration, easing = easing)) +
                        scaleIn(initialScale = 0.95f, animationSpec = tween(duration, easing = easing))
                    }
                },
                popExitTransition = {
                    val initialRoute = initialState.destination.route
                    val targetRoute = targetState.destination.route
                    val initialIndex = bottomNavItems.indexOfFirst { it.route == initialRoute }
                    val targetIndex = bottomNavItems.indexOfFirst { it.route == targetRoute }

                    val duration = 350
                    val easing = androidx.compose.animation.core.FastOutSlowInEasing

                    if (initialIndex != -1 && targetIndex != -1) {
                        val direction = if (targetIndex > initialIndex) AnimatedContentTransitionScope.SlideDirection.Start
                                        else AnimatedContentTransitionScope.SlideDirection.End
                        fadeOut(animationSpec = tween(duration, easing = easing)) +
                        slideOutOfContainer(
                            towards = direction,
                            animationSpec = tween(duration, easing = easing),
                            targetOffset = { (it * 0.25f).toInt() }
                        )
                    } else {
                        fadeOut(animationSpec = tween(duration, easing = easing)) +
                        slideOutOfContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.End,
                            animationSpec = tween(duration, easing = easing)
                        )
                    }
                }
            ) {
                composable(Screen.Home.route) {
                    HomeScreen(
                        viewModel = viewModel,
                        onStatusChipClick = onStatusChipClick,
                        onPlantClick = { plantId ->
                            navController.navigate(Screen.PlantDetail.createRoute(plantId))
                        }
                    )
                }

                composable(Screen.Plants.route) {
                    PlantsListScreen(
                        viewModel = viewModel,
                        onStatusChipClick = onStatusChipClick,
                        onPlantClick = { plantId ->
                            navController.navigate(Screen.PlantDetail.createRoute(plantId))
                        }
                    )
                }

                composable(
                    route = Screen.PlantDetail.route,
                    arguments = listOf(
                        navArgument("plantId") { type = NavType.StringType }
                    )
                ) { backStackEntry ->
                    val plantId = backStackEntry.arguments?.getString("plantId") ?: return@composable
                    PlantDetailScreen(
                        viewModel = viewModel,
                        plantId = plantId,
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(Screen.History.route) {
                    HistoryScreen(
                        viewModel = viewModel,
                        onStatusChipClick = onStatusChipClick
                    )
                }

                composable(Screen.Settings.route) {
                    SettingsScreen(
                        viewModel = viewModel,
                        onStatusChipClick = onStatusChipClick,
                        onShowOnboarding = {
                            viewModel.showOnboardingAgain()
                        },
                        onNavigateToPumpTest = {
                            navController.navigate(Screen.PumpTest.route)
                        },
                        onNavigateToCalibration = {
                            navController.navigate(Screen.Calibration.route)
                        },
                        onNavigateToSerialOutput = {
                            navController.navigate(Screen.SerialOutput.route)
                        },
                        onNavigateToHardwareSettings = {
                            navController.navigate(Screen.HardwareSettings.route)
                        }
                    )
                }

                composable(Screen.HardwareSettings.route) {
                    HardwareSettingsScreen(
                        viewModel = viewModel,
                        onStatusChipClick = onStatusChipClick,
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(Screen.Calibration.route) {
                    CalibrationScreen(
                        viewModel = viewModel,
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(Screen.SerialOutput.route) {
                    val pumpViewModel: PumpTestViewModel = viewModel()
                    SerialOutputScreen(
                        viewModel = pumpViewModel,
                        onBack = { navController.popBackStack() },
                        onStatusChipClick = onStatusChipClick
                    )
                }

                composable(Screen.PumpTest.route) {
                    val pumpViewModel: PumpTestViewModel = viewModel()
                    PumpTestingScreen(
                        viewModel = pumpViewModel,
                        onBack = { navController.popBackStack() },
                        onStatusChipClick = onStatusChipClick
                    )
                }
            }

            val currentPlantName = plants.find { it.id == isWateringPlantId }?.name
            var lastWateringPlantName by remember { mutableStateOf("") }
            if (!currentPlantName.isNullOrEmpty()) {
                lastWateringPlantName = currentPlantName
            }

            AnimatedVisibility(
                visible = isWateringPlantId != null,
                enter = fadeIn(animationSpec = tween(400)) + scaleIn(initialScale = 0.92f, animationSpec = tween(400)),
                exit = fadeOut(animationSpec = tween(400)) + scaleOut(targetScale = 0.92f, animationSpec = tween(400))
            ) {
                WateringOverlay(plantName = lastWateringPlantName)
            }
        }
    }

    // Shared connection dialog, reachable from the status chip on any tab.
    if (showConnectionDialog) {
        val dialogConnState by viewModel.displayConnectionState.collectAsState()
        DeviceConnectionDialog(
            connectionState = dialogConnState,
            deviceIp = deviceState.deviceIp,
            onConnect = { viewModel.connectToDevice() },
            onDisconnect = { viewModel.disconnectFromDevice() },
            onDismiss = { showConnectionDialog = false }
        )
    }
}
}
