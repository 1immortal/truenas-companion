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
import app.truenascompanion.ui.apps.AppDetailScreen
import app.truenascompanion.ui.apps.AppFormMode
import app.truenascompanion.ui.apps.AppFormScreen
import app.truenascompanion.ui.apps.CatalogDetailScreen
import app.truenascompanion.ui.apps.CatalogScreen
import app.truenascompanion.ui.apps.LogsScreen
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.truenascompanion.TrueNasApp
import app.truenascompanion.data.model.WidgetType
import app.truenascompanion.ui.alerts.AlertsScreen
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
    const val OVERVIEW = "servers_overview"
    const val JOBS = "jobs"
    const val EDIT = "server_edit?id={id}"
    fun edit(id: String? = null) = if (id == null) "server_edit" else "server_edit?id=$id"
    const val CATALOG = "catalog"
    const val VM = "vm/{id}"
    const val VM_NEW = "vm_new"
    const val CATALOG_APP = "catalog/{train}/{name}"
    const val INSTALL = "install/{train}/{name}"
    const val APP = "app/{name}"
    const val APP_EDIT = "app/{name}/edit"
    const val LOGS = "app/{name}/logs?container={container}"
    const val SNAPSHOTS = "snapshots/{dataset}"
    const val SNAP_TASK = "snapshot_task?id={id}"
    const val VPN = "vpn/{id}"
    const val VPN_SETUP = "vpn_setup/{id}"
    const val SHELL = "shell/{kind}?app={app}&ctr={ctr}&cname={cname}&cmd={cmd}&id={id}"
    fun vpn(id: String) = "vpn/${enc(id)}"
    fun vpnSetup(id: String) = "vpn_setup/${enc(id)}"
    fun snapshots(dataset: String) = "snapshots/${enc(dataset)}"
    fun snapTask(id: Int?) = if (id == null) "snapshot_task" else "snapshot_task?id=$id"
    private fun enc(v: String) = android.net.Uri.encode(v)
    fun catalogApp(train: String, name: String) = "catalog/${enc(train)}/${enc(name)}"
    fun install(train: String, name: String) = "install/${enc(train)}/${enc(name)}"
    fun app(name: String) = "app/${enc(name)}"
    fun appEdit(name: String) = "app/${enc(name)}/edit"
    fun shell(t: app.truenascompanion.data.shell.ShellTarget): String {
        fun q(vararg kv: Pair<String, String?>) = kv.filter { it.second != null }.joinToString("&", prefix = "?") { "${it.first}=${enc(it.second!!)}" }
        return when (t) {
            app.truenascompanion.data.shell.ShellTarget.Host -> "shell/host"
            is app.truenascompanion.data.shell.ShellTarget.App -> "shell/app" + q("app" to t.appName, "ctr" to t.containerId, "cname" to t.containerName, "cmd" to t.command)
            is app.truenascompanion.data.shell.ShellTarget.Instance -> "shell/instance" + q("id" to t.id, "cmd" to t.command)
        }
    }
    fun logs(name: String, container: String?) = "app/${enc(name)}/logs" + (container?.let { "?container=${enc(it)}" } ?: "")
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
    val lockGuard = app.truenascompanion.ui.lock.rememberLockGuard()
    androidx.compose.runtime.CompositionLocalProvider(
        app.truenascompanion.ui.lock.LocalDangerGuard provides guard,
        app.truenascompanion.ui.lock.LocalLockGuard provides lockGuard,
    ) {
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
                                WidgetType.PROTECTION -> { container.storageTabRequest.value = 4; nav.switchTab(Tab.STORAGE.route) }
                                else -> Unit
                            }
                        },
                        onServers = { nav.navigate(Routes.SERVERS) },
                    )
                }
                composable(Tab.STORAGE.route) {
                    StorageScreen(onOpenSnapshots = { nav.navigate(Routes.snapshots(it)) }, onSnapshotTask = { nav.navigate(Routes.snapTask(it)) })
                }
                pushed(Routes.SNAPSHOTS, "dataset") { a ->
                    app.truenascompanion.ui.protection.SnapshotsScreen(a.getValue("dataset"), onBack = { nav.popBackStack() })
                }
                pushed(Routes.SNAP_TASK, "id?") { a ->
                    app.truenascompanion.ui.protection.SnapshotTaskEditorScreen(a["id"]?.toIntOrNull(), onBack = { nav.popBackStack() })
                }
                composable(Tab.APPS.route) {
                    app.truenascompanion.ui.virt.WorkloadsScreen(
                        onJobs = { nav.navigate(Routes.JOBS) },
                        onCatalog = { nav.navigate(Routes.CATALOG) },
                        onOpenApp = { nav.navigate(Routes.app(it)) },
                        onOpenVm = { nav.navigate("vm/$it") },
                        onCreateVm = { nav.navigate(Routes.VM_NEW) },
                        onShell = { nav.navigate(Routes.shell(it)) },
                    )
                }
                pushed(Routes.VPN, "id") { a ->
                    app.truenascompanion.ui.vpn.VpnScreen(a.getValue("id"), onBack = { nav.popBackStack() }, onSetup = { nav.navigate(Routes.vpnSetup(a.getValue("id"))) })
                }
                pushed(Routes.VPN_SETUP, "id") { a ->
                    app.truenascompanion.ui.vpn.VpnSetupScreen(a.getValue("id"), onBack = { nav.popBackStack() })
                }
                pushed(Routes.VM, "id") { a ->
                    app.truenascompanion.ui.virt.VmDetailScreen(a.getValue("id").toInt(), onBack = { nav.popBackStack() })
                }
                pushed(Routes.VM_NEW) {
                    app.truenascompanion.ui.virt.VmCreateScreen(onBack = { nav.popBackStack() }, onCreated = { id ->
                        nav.navigate("vm/$id") { popUpTo(Routes.VM_NEW) { inclusive = true } }
                    })
                }
                pushed(Routes.CATALOG) {
                    CatalogScreen(onBack = { nav.popBackStack() }, onOpen = { nav.navigate(Routes.catalogApp(it.train, it.name)) })
                }
                pushed(Routes.CATALOG_APP, "train", "name") { a ->
                    CatalogDetailScreen(a.getValue("name"), a.getValue("train"), onBack = { nav.popBackStack() },
                        onInstall = { name, train -> nav.navigate(Routes.install(train, name)) })
                }
                pushed(Routes.INSTALL, "train", "name") { a ->
                    AppFormScreen(AppFormMode.Install(a.getValue("name"), a.getValue("train")), onBack = { nav.popBackStack() },
                        onDone = { nav.backToApps() })
                }
                pushed(Routes.APP, "name") { a ->
                    val name = a.getValue("name")
                    AppDetailScreen(name, onBack = { nav.popBackStack() }, onEdit = { nav.navigate(Routes.appEdit(name)) },
                        onLogs = { nav.navigate(Routes.logs(name, it)) }, onFinished = { nav.backToApps() },
                        onShell = { id, cname, cmd -> nav.navigate(Routes.shell(app.truenascompanion.data.shell.ShellTarget.App(name, id, cname, cmd))) })
                }
                pushed(Routes.APP_EDIT, "name") { a ->
                    AppFormScreen(AppFormMode.Edit(a.getValue("name")), onBack = { nav.popBackStack() }, onDone = { nav.backToApps() })
                }
                pushed(Routes.SHELL, "kind", "app?", "ctr?", "cname?", "cmd?", "id?") { a ->
                    val target = when (a["kind"]) {
                        "app" -> app.truenascompanion.data.shell.ShellTarget.App(a["app"].orEmpty(), a["ctr"].orEmpty(), a["cname"] ?: a["ctr"].orEmpty(),
                            a["cmd"] ?: app.truenascompanion.data.shell.WebShellProtocol.DEFAULT_COMMAND)
                        "instance" -> app.truenascompanion.data.shell.ShellTarget.Instance(a["id"].orEmpty(), a["cmd"])
                        else -> app.truenascompanion.data.shell.ShellTarget.Host
                    }
                    app.truenascompanion.ui.shell.ShellScreen(target, key = a.entries.sortedBy { it.key }.joinToString("|"), onBack = { nav.popBackStack() })
                }
                pushed(Routes.LOGS, "name", "container?") { a ->
                    LogsScreen(a.getValue("name"), a["container"], onBack = { nav.popBackStack() })
                }
                composable(Tab.ALERTS.route) { AlertsScreen() }
                composable(Tab.SYSTEM.route) { SystemScreen(onServers = { nav.navigate(Routes.SERVERS) }, onOverview = { nav.navigate(Routes.OVERVIEW) }, onJobs = { nav.navigate(Routes.JOBS) },
                    onShell = { nav.navigate(Routes.shell(app.truenascompanion.data.shell.ShellTarget.Host)) }) }
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
                    Routes.OVERVIEW,
                    enterTransition = { slideInHorizontally { it } + fadeIn() },
                    popExitTransition = { slideOutHorizontally { it } + fadeOut() },
                ) {
                    app.truenascompanion.ui.servers.MultiServerOverviewScreen(
                        onBack = { nav.popBackStack() },
                        onOpenServer = { nav.backToDashboard() },
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
                        onVpn = { sid -> nav.navigate(Routes.vpn(sid)) },
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

/** Install/edit/delete finished: back to the Apps list, whose job watcher reports progress. */
private fun NavHostController.backToApps() {
    if (!popBackStack(Tab.APPS.route, inclusive = false)) switchTab(Tab.APPS.route)
}

/** A pushed (non-tab) screen with string arguments; names ending in `?` are optional. */
private fun androidx.navigation.NavGraphBuilder.pushed(route: String, vararg args: String, content: @Composable (Map<String, String>) -> Unit) {
    composable(
        route,
        arguments = args.map { raw ->
            val optional = raw.endsWith("?")
            navArgument(raw.removeSuffix("?")) { type = NavType.StringType; nullable = optional; if (optional) defaultValue = null }
        },
        enterTransition = { slideInHorizontally { it } + fadeIn() },
        popExitTransition = { slideOutHorizontally { it } + fadeOut() },
    ) { entry ->
        val values = args.map { it.removeSuffix("?") }.mapNotNull { k -> entry.arguments?.getString(k)?.let { k to it } }.toMap()
        content(values)
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
