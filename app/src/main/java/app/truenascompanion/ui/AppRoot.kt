package app.truenascompanion.ui

import app.truenascompanion.ui.components.glow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.truenascompanion.TrueNasApp
import app.truenascompanion.data.model.WidgetType
import app.truenascompanion.ui.alerts.AlertsScreen
import app.truenascompanion.ui.apps.AppsScreen
import app.truenascompanion.ui.jobs.JobsScreen
import app.truenascompanion.ui.dashboard.DashboardScreen
import app.truenascompanion.ui.servers.ServerEditScreen
import app.truenascompanion.ui.servers.ServerListScreen
import app.truenascompanion.ui.storage.StorageScreen
import app.truenascompanion.ui.system.SystemScreen
import androidx.compose.ui.platform.LocalContext
import app.truenascompanion.notify.DeepLink
import kotlinx.coroutines.flow.first

private enum class Tab(val route: String, val label: String, val selected: ImageVector, val unselected: ImageVector) {
    DASHBOARD("dashboard", "Home", Icons.Rounded.Dashboard, Icons.Outlined.Dashboard),
    STORAGE("storage", "Storage", Icons.Rounded.Storage, Icons.Outlined.Storage),
    APPS("apps", "Apps", Icons.Rounded.Apps, Icons.Outlined.Apps),
    ALERTS("alerts", "Alerts", Icons.Rounded.Notifications, Icons.Outlined.Notifications),
    SYSTEM("system", "System", Icons.Rounded.Settings, Icons.Outlined.Settings),
}

private object Routes {
    const val SERVERS = "servers"
    const val JOBS = "jobs"
    const val EDIT = "server_edit?id={id}"
    fun edit(id: String? = null) = if (id == null) "server_edit" else "server_edit?id=$id"
}

@Composable
fun AppRoot() {
    val container = (LocalContext.current.applicationContext as TrueNasApp).container
    val servers by container.settings.servers.collectAsStateWithLifecycle(initialValue = null)
    val list = servers
    val lockSettings by container.appLock.settings.collectAsStateWithLifecycle()
    if (list == null || lockSettings == null) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
        return
    }
    // Hoisted above the lock gate so the current screen (and its ViewModels) survive locking.
    val nav = rememberNavController()
    val guard = app.truenascompanion.ui.lock.rememberDangerGuard()
    androidx.compose.runtime.CompositionLocalProvider(app.truenascompanion.ui.lock.LocalDangerGuard provides guard) {
        app.truenascompanion.ui.lock.LockGate { AppContent(container, list, nav) }
    }
}

@Composable
private fun AppContent(container: app.truenascompanion.AppContainer, list: List<app.truenascompanion.data.model.ServerConfig>, nav: NavHostController) {
    // First run: open the connection setup on top of the (empty) dashboard.
    LaunchedEffect(Unit) { if (list.isEmpty()) nav.navigate(Routes.edit()) }
    // Notification taps: switch to the right server, then open Alerts (and the sign-in dialog if asked).
    val deepLink by container.deepLinks.collectAsStateWithLifecycle()
    LaunchedEffect(deepLink) {
        val link = deepLink ?: return@LaunchedEffect
        container.deepLinks.value = null
        link.serverId?.takeIf { id -> list.any { it.id == id } }?.let { id ->
            container.settings.setActiveServer(id)
            kotlinx.coroutines.withTimeoutOrNull(3_000) { container.repository.activeServer.first { it?.id == id } }
        }
        when (link.destination) {
            DeepLink.DEST_SETTINGS -> nav.switchTab(Tab.SYSTEM.route)
            DeepLink.DEST_SIGN_IN -> {
                nav.switchTab(Tab.ALERTS.route)
                container.repository.requestSignIn()
            }
            else -> nav.switchTab(Tab.ALERTS.route)
        }
    }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showBar = Tab.entries.any { it.route == route }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (showBar) {
                AppNavBar(route) { nav.switchTab(it) }
            }
        },
    ) { padding ->
        app.truenascompanion.ui.auth.AuthPromptHost()
        Box(Modifier.padding(padding).consumeWindowInsets(padding)) {
            NavHost(
                navController = nav,
                startDestination = Tab.DASHBOARD.route,
                enterTransition = { fadeIn() },
                exitTransition = { fadeOut() },
            ) {
                composable(Tab.DASHBOARD.route) {
                    DashboardScreen(
                        onOpen = { type ->
                            when (type) {
                                WidgetType.POOLS, WidgetType.TEMPERATURE -> nav.switchTab(Tab.STORAGE.route)
                                WidgetType.APPS -> nav.switchTab(Tab.APPS.route)
                                WidgetType.ALERTS -> nav.switchTab(Tab.ALERTS.route)
                                else -> Unit
                            }
                        },
                        onServers = { nav.navigate(Routes.SERVERS) },
                    )
                }
                composable(Tab.STORAGE.route) { StorageScreen() }
                composable(Tab.APPS.route) { AppsScreen(onJobs = { nav.navigate(Routes.JOBS) }) }
                composable(Tab.ALERTS.route) { AlertsScreen() }
                composable(Tab.SYSTEM.route) { SystemScreen(onServers = { nav.navigate(Routes.SERVERS) }, onJobs = { nav.navigate(Routes.JOBS) }) }
                composable(Routes.JOBS) { JobsScreen(onBack = { nav.popBackStack() }) }
                composable(
                    Routes.SERVERS,
                    enterTransition = { slideInHorizontally { it } + fadeIn() },
                    popExitTransition = { slideOutHorizontally { it } + fadeOut() },
                ) {
                    ServerListScreen(
                        onAdd = { nav.navigate(Routes.edit()) },
                        onEdit = { nav.navigate(Routes.edit(it)) },
                        onOpen = { nav.backToDashboard() },
                        onBack = if (list.isNotEmpty()) ({ nav.popBackStack() }) else null,
                    )
                }
                composable(
                    Routes.EDIT,
                    arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null }),
                    enterTransition = { slideInHorizontally { it } + fadeIn() },
                    popExitTransition = { slideOutHorizontally { it } + fadeOut() },
                ) { backStack ->
                    val id = backStack.arguments?.getString("id")
                    val canGoBack = nav.previousBackStackEntry != null
                    ServerEditScreen(
                        serverId = id,
                        onDone = {
                            if (nav.previousBackStackEntry?.destination?.route == Routes.SERVERS) nav.popBackStack()
                            else nav.backToDashboard()
                        },
                        onBack = if (canGoBack) ({ nav.popBackStack() }) else null,
                    )
                }
            }
        }
    }
}

private fun NavHostController.switchTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        restoreState = true
        launchSingleTop = true
    }
}

private fun NavHostController.backToDashboard() {
    if (!popBackStack(Tab.DASHBOARD.route, inclusive = false)) switchTab(Tab.DASHBOARD.route)
}

/** Bottom navigation with single-line labels and a glowing selected icon. */
@Composable
internal fun AppNavBar(route: String?, onTab: (String) -> Unit) {
    val brand = app.truenascompanion.ui.theme.LocalBrandColors.current
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Tab.entries.forEach { tab ->
            val selected = route == tab.route
            NavigationBarItem(
                selected = selected,
                onClick = { onTab(tab.route) },
                icon = {
                    Icon(
                        if (selected) tab.selected else tab.unselected, null,
                        modifier = if (selected) Modifier.glow(brand.glow, 8.dp, androidx.compose.foundation.shape.CircleShape, alpha = if (brand.dark) 0.7f else 0.35f) else Modifier,
                    )
                },
                label = { Text(tab.label, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip, style = MaterialTheme.typography.labelMedium) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = if (brand.dark) brand.accent else MaterialTheme.colorScheme.primary,
                    selectedTextColor = if (brand.dark) brand.accent else MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = if (brand.dark) 0.22f else 0.12f),
                ),
            )
        }
    }
}
