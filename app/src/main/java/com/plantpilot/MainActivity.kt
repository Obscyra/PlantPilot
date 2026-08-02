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
import androidx.compose.animation.*
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
import com.plantpilot.data.HardwareRepository
import com.plantpilot.navigation.Screen
import com.plantpilot.navigation.bottomNavItems
import com.plantpilot.ui.components.DeviceConnectionDialog
import com.plantpilot.ui.screens.*
import com.plantpilot.ui.theme.PlantPilotTheme
import com.plantpilot.viewmodel.PlantPilotViewModel
import com.plantpilot.viewmodel.PumpTestViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: PlantPilotViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
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

        // Drive telemetry cadence and the background sync service from the app's
        // lifecycle so the ESP32 streams at 1s in the foreground and 3s while
        // the app is backgrounded but not closed. The foreground service is
        // started here (app is foreground, always permitted) and only stopped
        // when the app is fully closed (onTaskRemoved), keeping the process —
        // and thus the WebSocket — alive in the background.
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    startForegroundService(Intent(this, SyncService::class.java))
                    HardwareRepository.setStreamCadence(FOREGROUND_CADENCE_SEC)
                }
                Lifecycle.Event.ON_RESUME -> {
                    // Re-check the ESP32 connection every time the app returns to
                    // the foreground (recents switcher, back to app, etc.).
                    viewModel.onAppResumed()
                }
                Lifecycle.Event.ON_STOP -> {
                    HardwareRepository.setStreamCadence(BACKGROUND_CADENCE_SEC)
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
        private const val FOREGROUND_CADENCE_SEC = 1
        private const val BACKGROUND_CADENCE_SEC = 3
        private const val REQUEST_NOTIFICATION_PERMISSION = 1
    }
}

@Composable
fun PlantPilotApp(viewModel: PlantPilotViewModel) {
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
            // connected, then surface the shared connect/disconnect dialog.
            if (viewModel.isConfigDirty && viewModel.isConnected.value) {
                viewModel.syncConfigWithDevice()
            }
            showConnectionDialog = true
        }

        Scaffold(
            bottomBar = {
                if (showBottomBar) {
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
        ) { paddingValues ->
                NavHost(
                navController = navController,
                startDestination = Screen.Home.route,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        top = 0.dp,
                        bottom = paddingValues.calculateBottomPadding()
                    ),
                enterTransition = {
                    val initialRoute = initialState.destination.route
                    val targetRoute = targetState.destination.route

                    val initialIndex = bottomNavItems.indexOfFirst { it.route == initialRoute }
                    val targetIndex = bottomNavItems.indexOfFirst { it.route == targetRoute }

                    val direction = when {
                        initialIndex != -1 && targetIndex != -1 -> {
                            if (targetIndex > initialIndex) AnimatedContentTransitionScope.SlideDirection.Start
                            else AnimatedContentTransitionScope.SlideDirection.End
                        }
                        else -> AnimatedContentTransitionScope.SlideDirection.Start
                    }

                    fadeIn(animationSpec = tween(400)) + slideIntoContainer(
                        towards = direction,
                        animationSpec = tween(400)
                    )
                },
                exitTransition = {
                    val initialRoute = initialState.destination.route
                    val targetRoute = targetState.destination.route

                    val initialIndex = bottomNavItems.indexOfFirst { it.route == initialRoute }
                    val targetIndex = bottomNavItems.indexOfFirst { it.route == targetRoute }

                    val direction = when {
                        initialIndex != -1 && targetIndex != -1 -> {
                            if (targetIndex > initialIndex) AnimatedContentTransitionScope.SlideDirection.Start
                            else AnimatedContentTransitionScope.SlideDirection.End
                        }
                        else -> AnimatedContentTransitionScope.SlideDirection.Start
                    }

                    fadeOut(animationSpec = tween(400)) + slideOutOfContainer(
                        towards = direction,
                        animationSpec = tween(400)
                    )
                },
                popEnterTransition = {
                    val initialRoute = initialState.destination.route
                    val targetRoute = targetState.destination.route

                    val initialIndex = bottomNavItems.indexOfFirst { it.route == initialRoute }
                    val targetIndex = bottomNavItems.indexOfFirst { it.route == targetRoute }

                    val direction = when {
                        initialIndex != -1 && targetIndex != -1 -> {
                            if (targetIndex > initialIndex) AnimatedContentTransitionScope.SlideDirection.Start
                            else AnimatedContentTransitionScope.SlideDirection.End
                        }
                        else -> AnimatedContentTransitionScope.SlideDirection.End
                    }

                    fadeIn(animationSpec = tween(400)) + slideIntoContainer(
                        towards = direction,
                        animationSpec = tween(400)
                    )
                },
                popExitTransition = {
                    val initialRoute = initialState.destination.route
                    val targetRoute = targetState.destination.route

                    val initialIndex = bottomNavItems.indexOfFirst { it.route == initialRoute }
                    val targetIndex = bottomNavItems.indexOfFirst { it.route == targetRoute }

                    val direction = when {
                        initialIndex != -1 && targetIndex != -1 -> {
                            if (targetIndex > initialIndex) AnimatedContentTransitionScope.SlideDirection.Start
                            else AnimatedContentTransitionScope.SlideDirection.End
                        }
                        else -> AnimatedContentTransitionScope.SlideDirection.End
                    }

                    fadeOut(animationSpec = tween(400)) + slideOutOfContainer(
                        towards = direction,
                        animationSpec = tween(400)
                    )
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
                        }
                    )
                }

                composable(Screen.Calibration.route) {
                    CalibrationScreen(
                        viewModel = viewModel,
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(Screen.PumpTest.route) {
                    val pumpViewModel: PumpTestViewModel = viewModel()
                    PumpTestingScreen(
                        viewModel = pumpViewModel,
                        onBack = { navController.popBackStack() }
                    )
                }
            }
        }

        // Shared connection dialog, reachable from the status chip on any tab.
        if (showConnectionDialog) {
            DeviceConnectionDialog(
                isConnected = viewModel.isConnected.collectAsState().value,
                isConnecting = viewModel.isConnecting.collectAsState().value,
                deviceIp = deviceState.deviceIp,
                onConnect = { viewModel.connectToDevice() },
                onDisconnect = { viewModel.disconnectFromDevice() },
                onDismiss = { showConnectionDialog = false }
            )
        }
    }
}
