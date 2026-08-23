package com.aydin.biyohack.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.aydin.biyohack.ui.dashboard.DashboardScreen
import com.aydin.biyohack.ui.twin.TwinScreen

private const val ROUTE_DASHBOARD = "dashboard"
private const val ROUTE_TWIN = "twin"

/** Oturum açıkken gösterilen ekranlar arası geçiş. Auth ekranı MainActivity'de ayrı tutulur. */
@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = ROUTE_DASHBOARD) {
        composable(ROUTE_DASHBOARD) {
            DashboardScreen(onOpenTwin = { navController.navigate(ROUTE_TWIN) })
        }
        composable(ROUTE_TWIN) {
            TwinScreen(onBack = { navController.popBackStack() })
        }
    }
}
