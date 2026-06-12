package com.phillipchin.webrtctunnel.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.ui.theme.WebRtcTunnelTheme
import com.phillipchin.webrtctunnel.viewmodel.ForwardsViewModel
import com.phillipchin.webrtctunnel.viewmodel.HomeViewModel
import com.phillipchin.webrtctunnel.viewmodel.ImportExportViewModel
import com.phillipchin.webrtctunnel.viewmodel.LogsViewModel
import com.phillipchin.webrtctunnel.viewmodel.NetworkPolicyViewModel
import com.phillipchin.webrtctunnel.viewmodel.SettingsViewModel
import com.phillipchin.webrtctunnel.viewmodel.SetupViewModel
import com.phillipchin.webrtctunnel.viewmodel.appViewModelFactory

private sealed class Route(val value: String, val title: String) {
    data object Home : Route("home", "WebRTC Tunnel")

    data object Forwards : Route("forwards", "Forwards")

    data object Logs : Route("logs", "Logs")

    data object Settings : Route("settings", "Settings")

    data object Setup : Route("setup", "Setup Wizard")

    data object NetworkPolicy : Route("network_policy", "Network Policy")

    data object ImportExport : Route("import_export", "Import / Export")

    data object ForwardDetails : Route("forwardDetails/{forwardId}", "Forward Details")
}

private data class BottomTab(
    val route: Route,
    val label: String,
    val icon: @Composable () -> Unit,
)

private val mainTabs =
    listOf(
        BottomTab(Route.Home, "Home", { Icon(Icons.Default.Home, "Home tab icon") }),
        BottomTab(Route.Forwards, "Forwards", { Icon(Icons.AutoMirrored.Filled.List, "Forwards tab icon") }),
        BottomTab(Route.Logs, "Logs", { Icon(Icons.Default.Terminal, "Logs tab icon") }),
        BottomTab(Route.Settings, "Settings", { Icon(Icons.Default.Settings, "Settings tab icon") }),
    )

private val secondaryRoutes =
    setOf(
        Route.Setup.value,
        Route.NetworkPolicy.value,
        Route.ImportExport.value,
        "forwardDetails/{forwardId}",
    )

@Composable
fun WebRtcTunnelApp(deps: AppDependencies) {
    val factory = remember(deps) { appViewModelFactory(deps) }
    val models = rememberSharedScreenModels(factory)
    val navController = rememberNavController()

    WebRtcTunnelTheme {
        NotificationPermissionGate()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route
        val showBottomBar = currentRoute in mainTabs.map { it.route.value }
        val showBackArrow = currentRoute != null && currentRoute in secondaryRoutes
        val title = routeTitle(currentRoute)

        Scaffold(
            topBar = {
                TunnelTopAppBar(
                    title = title,
                    navigationIcon =
                        if (showBackArrow) {
                            (
                                {
                                    IconButton(onClick = { navController.navigateUp() }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                                    }
                                }
                            )
                        } else {
                            null
                        },
                )
            },
            bottomBar = {
                if (showBottomBar) {
                    BottomNavBar(navController)
                }
            },
        ) { padding ->
            AppNavHost(navController = navController, padding = padding, models = models, factory = factory)
        }
    }
}

// Shared, Activity-scoped ViewModels. Created through Android's ViewModelProvider via
// viewModel(), so viewModelScope is lifecycle-bound and Home/Forwards observe the same
// instances. ImportExportViewModel is intentionally route-scoped (created in its
// destination) since its state need not outlive that screen.
private class AppScreenModels(
    val home: HomeViewModel,
    val setup: SetupViewModel,
    val forwards: ForwardsViewModel,
    val logs: LogsViewModel,
    val settings: SettingsViewModel,
    val networkPolicy: NetworkPolicyViewModel,
)

@Composable
private fun rememberSharedScreenModels(factory: ViewModelProvider.Factory): AppScreenModels =
    AppScreenModels(
        home = viewModel(factory = factory),
        // SetupViewModel is Activity-scoped so the wizard draft survives navigation
        // away and back (e.g. opening Network Policy mid-wizard does not reset input).
        setup = viewModel(factory = factory),
        forwards = viewModel(factory = factory),
        logs = viewModel(factory = factory),
        settings = viewModel(factory = factory),
        networkPolicy = viewModel(factory = factory),
    )

private fun homeNavActions(navController: NavHostController) =
    HomeNavActions(
        onOpenSetup = { navController.navigate(Route.Setup.value) },
        onOpenLogs = { navController.navigate(Route.Logs.value) },
        onOpenSettings = { navController.navigate(Route.Settings.value) },
        onOpenForwardDetails = { id -> navController.navigate("forwardDetails/$id") },
    )

private fun settingsNavActions(navController: NavHostController) =
    SettingsNavActions(
        onOpenSetup = { navController.navigate(Route.Setup.value) },
        onOpenLogs = { navController.navigate(Route.Logs.value) },
        onOpenNetworkPolicy = { navController.navigate(Route.NetworkPolicy.value) },
        onOpenImportExport = { navController.navigate(Route.ImportExport.value) },
    )

@Composable
private fun AppNavHost(
    navController: NavHostController,
    padding: PaddingValues,
    models: AppScreenModels,
    factory: ViewModelProvider.Factory,
) {
    NavHost(navController = navController, startDestination = Route.Home.value) {
        composable(Route.Home.value) {
            HomeScreen(
                padding = padding,
                vm = models.home,
                forwardsVm = models.forwards,
                nav = homeNavActions(navController),
            )
        }
        composable(Route.Forwards.value) {
            ForwardsScreen(
                padding = padding,
                vm = models.forwards,
                onOpenDetails = { forwardId ->
                    navController.navigate("forwardDetails/$forwardId")
                },
            )
        }
        composable(Route.Logs.value) { LogsScreen(padding, models.logs, models.networkPolicy) }
        composable(Route.Settings.value) {
            SettingsScreen(padding = padding, vm = models.settings, nav = settingsNavActions(navController))
        }
        composable(Route.Setup.value) {
            SetupWizardScreen(
                padding = padding,
                vm = models.setup,
                onStartSuccess = {
                    navController.navigate(Route.Home.value) {
                        popUpTo(Route.Home.value) { inclusive = false }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(Route.NetworkPolicy.value) { NetworkPolicyScreen(padding, models.networkPolicy) }
        composable(Route.ImportExport.value) {
            val importExport: ImportExportViewModel = viewModel(factory = factory)
            ImportExportScreen(padding, importExport)
        }
        composable(
            route = Route.ForwardDetails.value,
            arguments = listOf(navArgument("forwardId") { type = NavType.StringType }),
        ) { backStack ->
            ForwardDetailsScreen(
                padding = padding,
                vm = models.forwards,
                forwardId = backStack.arguments?.getString("forwardId").orEmpty(),
                onDeleteAndReturn = { navController.navigateUp() },
            )
        }
    }
}

@Composable
private fun BottomNavBar(navController: NavHostController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    NavigationBar {
        mainTabs.forEach { tab ->
            NavigationBarItem(
                selected = currentDestination.isOnRoute(tab.route.value),
                onClick = {
                    navController.navigate(tab.route.value) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = tab.icon,
                label = { Text(tab.label) },
            )
        }
    }
}

private fun NavDestination?.isOnRoute(route: String): Boolean {
    return this?.hierarchy?.any { it.route == route } == true
}

private fun routeTitle(route: String?): String =
    when {
        route == Route.Home.value -> Route.Home.title
        route == Route.Forwards.value -> Route.Forwards.title
        route == Route.Logs.value -> Route.Logs.title
        route == Route.Settings.value -> Route.Settings.title
        route == Route.Setup.value -> Route.Setup.title
        route == Route.NetworkPolicy.value -> Route.NetworkPolicy.title
        route == Route.ImportExport.value -> Route.ImportExport.title
        route?.startsWith("forwardDetails/") == true -> Route.ForwardDetails.title
        else -> "WebRTC Tunnel"
    }
