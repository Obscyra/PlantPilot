package com.plantpilot.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Plants : Screen("plants")
    data object PlantDetail : Screen("plant/{plantId}") {
        fun createRoute(plantId: String) = "plant/$plantId"
    }
    data object History : Screen("history")
    data object Settings : Screen("settings")
    data object Onboarding : Screen("onboarding")
    data object PumpTest : Screen("pump_test")
    data object Calibration : Screen("calibration")
}

data class BottomNavItem(
    val label: String,
    val icon: ImageVector,
    val route: String
)

val bottomNavItems = listOf(
    BottomNavItem("Home", Icons.Default.Home, Screen.Home.route),
    BottomNavItem("Plants", Icons.Default.Grass, Screen.Plants.route),
    BottomNavItem("History", Icons.Default.History, Screen.History.route),
    BottomNavItem("Settings", Icons.Default.Settings, Screen.Settings.route)
)
