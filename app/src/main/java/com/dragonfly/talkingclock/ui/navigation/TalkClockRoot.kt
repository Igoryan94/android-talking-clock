package com.dragonfly.talkingclock.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.dragonfly.talkingclock.ui.home.HomeScreen
import com.dragonfly.talkingclock.ui.rules.RulesScreen
import com.dragonfly.talkingclock.ui.settings.TtsSettingsScreen

private data class TopDestination(val route: String, val label: String, val icon: ImageVector)

private val destinations = listOf(
    TopDestination("home", "Главная", Icons.Filled.Schedule),
    TopDestination("rules", "Правила", Icons.AutoMirrored.Filled.Rule),
    TopDestination("tts", "Голос", Icons.Filled.GraphicEq),
)

/** Single navigation entry point: every transition to a top destination behaves identically. */
private fun NavHostController.navigateToTopDestination(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
fun TalkClockRoot() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                destinations.forEach { dest ->
                    NavigationBarItem(
                        selected = currentRoute == dest.route,
                        onClick = { navController.navigateToTopDestination(dest.route) },
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(padding),
        ) {
            composable("home") {
                HomeScreen(onOpenRules = { navController.navigateToTopDestination("rules") })
            }
            composable("rules") { RulesScreen() }
            composable("tts") { TtsSettingsScreen() }
        }
    }
}
