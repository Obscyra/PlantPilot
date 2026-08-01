package com.plantpilot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.plantpilot.navigation.Screen
import com.plantpilot.navigation.bottomNavItems
import com.plantpilot.ui.screens.*
import com.plantpilot.ui.theme.PlantPilotTheme
import com.plantpilot.viewmodel.PlantPilotViewModel
import com.plantpilot.viewmodel.PumpTestViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
        }
        setContent {
            val viewModel: PlantPilotViewModel = viewModel()

            PlantPilotTheme {
                PlantPilotApp(viewModel)
            }
        }
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
                        onPlantClick = { plantId ->
                            navController.navigate(Screen.PlantDetail.createRoute(plantId))
                        }
                    )
                }

                composable(Screen.Plants.route) {
                    PlantsListScreen(
                        viewModel = viewModel,
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
                    HistoryScreen(viewModel = viewModel)
                }

                composable(Screen.Settings.route) {
                    SettingsScreen(
                        viewModel = viewModel,
                        onShowOnboarding = {
                            viewModel.showOnboardingAgain()
                        },
                        onNavigateToPumpTest = {
                            navController.navigate(Screen.PumpTest.route)
                        }
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
    }
}
