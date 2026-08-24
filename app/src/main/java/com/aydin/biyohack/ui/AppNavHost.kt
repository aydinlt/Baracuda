package com.aydin.biyohack.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.aydin.biyohack.ui.dashboard.DashboardScreen
import com.aydin.biyohack.ui.lab.LabScreen
import com.aydin.biyohack.ui.log.LogScreen
import com.aydin.biyohack.ui.settings.SettingsScreen
import com.aydin.biyohack.ui.twin.TwinScreen

private const val ROUTE_DASHBOARD = "dashboard"
private const val ROUTE_TWIN = "twin"
private const val ROUTE_LAB = "lab"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_LOG = "log"

/** Oturum açıkken gösterilen ekranlar arası geçiş. Auth ekranı MainActivity'de ayrı tutulur. */
@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = ROUTE_DASHBOARD) {
        composable(ROUTE_DASHBOARD) {
            DashboardScreen(
                onOpenTwin = { navController.navigate(ROUTE_TWIN) },
                onOpenLab = { navController.navigate(ROUTE_LAB) },
                onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
                onOpenLog = { navController.navigate(ROUTE_LOG) }
            )
        }
        composable(ROUTE_TWIN) {
            TwinScreen(onBack = { navController.popBackStack() })
        }
        composable(ROUTE_LAB) {
            LabScreen(onBack = { navController.popBackStack() })
        }
        composable(ROUTE_SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(ROUTE_LOG) {
            LogScreen(onBack = { navController.popBackStack() })
        }
    }
}
