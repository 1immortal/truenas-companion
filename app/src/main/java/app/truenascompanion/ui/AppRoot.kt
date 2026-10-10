package app.truenascompanion.ui

import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
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
    const val ACCOUNTS = "accounts"
    const val REPORTS = "reports"
    const val AUDIT = "audit"
    const val NETWORK = "network"
    // 1.10.0: phone alert rules (System › Phone alerts › Alert rules)
    const val ALERT_RULES = "alert_rules"
    const val CERTIFICATES = "certificates?highlight={highlight}"
    fun certificates(highlight: String? = null) = if (highlight == null) "certificates" else "certificates?highlight=${enc(highlight)}"
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
    // 1.3.0: file browser and disk screens
    const val FILES = "files?path={path}"
    const val POOL_LAYOUT = "pool_layout/{pool}"
    const val DISK = "disk/{name}"
    const val REPLACE = "replace/{pool}?guid={guid}&disk={disk}"
    fun files(path: String) = "files?path=${enc(path)}"
    fun poolLayout(pool: String) = "pool_layout/${enc(pool)}"
    fun disk(name: String) = "disk/${enc(name)}"
    fun replace(pool: String, guid: String? = null, disk: String? = null): String {
        val q = listOfNotNull(guid?.let { "guid=${enc(it)}" }, disk?.let { "disk=${enc(it)}" })
        return "replace/${enc(pool)}" + if (q.isEmpty()) "" else q.joinToString("&", prefix = "?")
    }
    // 1.4.0: services and scheduled tasks
    const val SERVICES = "services"
    // 1.4.1: System hub pages (updates, power, phone alerts, app lock, connection, appearance, about)
    const val SYSTEM_PAGE = "system_page/{page}"
    fun systemPage(page: app.truenascompanion.ui.system.SystemPage) = "system_page/${page.name.lowercase()}"
    const val SERVICE = "service/{kind}"
    const val TASKS = "scheduled_tasks"
    const val CRON_JOB = "cron_job?id={id}"
    const val INIT_SCRIPT = "init_script?id={id}"
    const val PICK_FILE = "pick_file?path={path}"
    const val PICKED_PATH = "picked_path"
    // 1.5.0: cloud sync (Storage › Protection)
    const val CLOUD_SYNC = "cloud_sync"
    const val CLOUD_TASK = "cloud_task?id={id}"
    const val CLOUD_CRED = "cloud_credential?id={id}"
    const val PICK_FOLDER = "pick_folder?path={path}"
    fun cloudTask(id: Int?) = if (id == null) "cloud_task" else "cloud_task?id=$id"
    fun cloudCred(id: Int?) = if (id == null) "cloud_credential" else "cloud_credential?id=$id"
    // 1.6.0: replication (Storage › Protection)
    const val REPLICATION = "replication"
    const val REPL_TASK = "replication_task?id={id}"
    const val SSH_CONN = "ssh_connection?id={id}"
    const val SSH_KEY = "ssh_keypair?id={id}"
    const val ISCSI = "iscsi"
    const val ISCSI_EDIT = "iscsi_edit/{kind}?id={id}"
    const val ISCSI_SETTINGS = "iscsi_settings"
    const val ISCSI_WIZARD = "iscsi_wizard"
    fun iscsiEdit(kind: app.truenascompanion.ui.iscsi.IscsiKind, id: Int?) = "iscsi_edit/${kind.name}" + (id?.let { "?id=$it" } ?: "")
    fun replTask(id: Int?) = if (id == null) "replication_task" else "replication_task?id=$id"
    fun sshConn(id: Int?) = if (id == null) "ssh_connection" else "ssh_connection?id=$id"
    fun sshKey(id: Int?) = if (id == null) "ssh_keypair" else "ssh_keypair?id=$id"
    fun pickFolder(path: String?) = if (path == null) "pick_folder" else "pick_folder?path=${enc(path)}"
    fun service(kind: app.truenascompanion.data.services.ServiceKind) = "service/${kind.name}"
    fun cronJob(id: Int?) = if (id == null) "cron_job" else "cron_job?id=$id"
    fun initScript(id: Int?) = if (id == null) "init_script" else "init_script?id=$id"
    fun pickFile(path: String?) = if (path == null) "pick_file" else "pick_file?path=${enc(path)}"
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

/** Widest a screen gets (tablets, landscape); the rest is page background. */
internal val MAX_CONTENT_WIDTH = 840.dp

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
    // Notification taps, app shortcuts and tiles: switch to the right server, then open the screen the alert is about
    // (or Alerts), the sign-in dialog, or a quick action's confirmation.
    var quick by remember { mutableStateOf<app.truenascompanion.ui.quick.QuickRequest?>(null) }
    val deepLink by container.deepLinks.collectAsStateWithLifecycle()
    LaunchedEffect(deepLink) {
        val link = deepLink ?: return@LaunchedEffect
        container.deepLinks.value = null
        link.serverId?.takeIf { id -> list.any { it.id == id } }?.let { id ->
            container.settings.setActiveServer(id)
            kotlinx.coroutines.withTimeoutOrNull(3_000) { container.repository.activeServer.first { it?.id == id } }
        }
        when (link.destination) {
            DeepLink.DEST_SETTINGS -> {
                // Plain link: the System hub. The instant-alerts notification and app-update notification open their page.
                nav.switchTab(Tab.SYSTEM.route)
                app.truenascompanion.ui.system.SystemPage.forSettingsLink(link.arg)?.let { nav.navigate(Routes.systemPage(it)) }
            }
            DeepLink.DEST_SIGN_IN -> {
                nav.switchTab(Tab.ALERTS.route)
                container.repository.requestSignIn()
            }
            DeepLink.DEST_DASHBOARD -> nav.switchTab(Tab.DASHBOARD.route)
            DeepLink.DEST_STORAGE -> {
                container.storageTabRequest.value = app.truenascompanion.ui.storage.StorageTabs.POOLS
                nav.switchTab(Tab.STORAGE.route)
            }
            DeepLink.DEST_RULES -> { nav.switchTab(Tab.SYSTEM.route); nav.navigate(Routes.ALERT_RULES) }
            DeepLink.DEST_TASKS -> { nav.switchTab(Tab.SYSTEM.route); nav.navigate(Routes.JOBS) }
            DeepLink.DEST_SHELL, DeepLink.DEST_RESTART_APP, DeepLink.DEST_SCRUB_POOL ->
                app.truenascompanion.quick.QuickAction.byDestination(link.destination)?.let { quick = app.truenascompanion.ui.quick.QuickRequest(it, link.arg) }
            else -> nav.openTarget(container, app.truenascompanion.notify.AlertTarget.decode(link.destination, link.arg))
        }
    }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showBar = Tab.entries.any { it.route == route }
    var overlayActive by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (showBar) {
                val badge by container.alertBadge.collectAsStateWithLifecycle()
                val active by container.repository.activeServer.collectAsStateWithLifecycle()
                AppNavBar(route, enabled = !overlayActive, alertCount = badge?.takeIf { it.serverId == active?.id }?.count ?: 0) { nav.switchTab(it) }
            }
        },
    ) { padding ->
        app.truenascompanion.ui.auth.AuthPromptHost()
        app.truenascompanion.ui.servers.HttpsUpgradeHost()
        // 1.8.1: "Connecting…", "Connected via home address", "Connection lost" for TalkBack users.
        app.truenascompanion.ui.connection.ConnectionAnnouncements(container)
        app.truenascompanion.ui.quick.QuickActionHost(container, quick, onDone = { quick = null },
            onOpenShell = { nav.navigate(Routes.shell(app.truenascompanion.data.shell.ShellTarget.Host)) })
        app.truenascompanion.ui.connection.ConnectionOverlayHost(
            container = container,
            hasSavedServers = list.isNotEmpty(),
            currentRoute = route,
            onCheckConfig = { id -> nav.navigate(Routes.edit(id)) },
            onActiveChange = { overlayActive = it },
            onSwitchServer = if (list.size > 1) ({ nav.navigate(Routes.SERVERS) }) else null,
        ) {
        // 1.8.0 (UI review P2-17): on tablets and in landscape, screens stay at a readable width, centred.
        Box(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            NavHost(
                modifier = Modifier.widthIn(max = MAX_CONTENT_WIDTH).fillMaxSize(),
                navController = nav,
                startDestination = Tab.DASHBOARD.route,
                enterTransition = { fadeIn() },
                exitTransition = { fadeOut() },
            ) {
                composable(Tab.DASHBOARD.route) {
                    DashboardScreen(
                        onOpen = { type ->
                            when (type) {
                                WidgetType.POOLS, WidgetType.TEMPERATURE, WidgetType.RUNWAY -> nav.switchTab(Tab.STORAGE.route)
                                WidgetType.APPS -> nav.switchTab(Tab.APPS.route)
                                WidgetType.ALERTS -> nav.switchTab(Tab.ALERTS.route)
                                WidgetType.PROTECTION -> { container.storageTabRequest.value = app.truenascompanion.ui.storage.StorageTabs.PROTECTION; nav.switchTab(Tab.STORAGE.route) }
                                WidgetType.REPORTS, WidgetType.CPU, WidgetType.MEMORY, WidgetType.NETWORK, WidgetType.ARC -> nav.navigate(Routes.REPORTS)
                                else -> Unit
                            }
                        },
                        onServers = { nav.navigate(Routes.SERVERS) },
                        onConnection = { nav.navigate(Routes.systemPage(app.truenascompanion.ui.system.SystemPage.CONNECTION)) },
                    )
                }
                composable(Tab.STORAGE.route) {
                    StorageScreen(
                        onOpenSnapshots = { nav.navigate(Routes.snapshots(it)) },
                        onSnapshotTask = { nav.navigate(Routes.snapTask(it)) },
                        onBrowse = { nav.navigate(Routes.files(it)) },
                        onOpenPool = { nav.navigate(Routes.poolLayout(it)) },
                        onOpenDisk = { nav.navigate(Routes.disk(it)) },
                        onReplace = { nav.navigate(Routes.replace(it)) },
                        onCloudSync = { nav.navigate(Routes.CLOUD_SYNC) },
                        onReplication = { nav.navigate(Routes.REPLICATION) },
                        onIscsi = { nav.navigate(Routes.ISCSI) },
                        onIscsiWizard = { nav.navigate(Routes.ISCSI_WIZARD) },
                        onServices = { nav.navigate(Routes.SERVICES) },
                    )
                }
                pushed(Routes.ISCSI) { _ ->
                    app.truenascompanion.ui.iscsi.IscsiScreen(
                        onBack = { nav.popBackStack() },
                        nav = app.truenascompanion.ui.iscsi.IscsiNav(
                            onEdit = { k, id -> nav.navigate(Routes.iscsiEdit(k, id)) },
                            onWizard = { nav.navigate(Routes.ISCSI_WIZARD) },
                            onSettings = { nav.navigate(Routes.ISCSI_SETTINGS) },
                            onServices = { nav.navigate(Routes.SERVICES) },
                        ),
                    )
                }
                pushed(Routes.ISCSI_EDIT, "kind", "id?") { a ->
                    val kind = app.truenascompanion.ui.iscsi.IscsiKind.entries.firstOrNull { it.name == a["kind"] }
                    if (kind == null) LaunchedEffect(Unit) { nav.popBackStack() }
                    else app.truenascompanion.ui.iscsi.IscsiEditorScreen(kind, a["id"]?.toIntOrNull(), onBack = { nav.popBackStack() })
                }
                pushed(Routes.ISCSI_SETTINGS) { _ ->
                    app.truenascompanion.ui.iscsi.IscsiSettingsScreen(onBack = { nav.popBackStack() }, onServices = { nav.navigate(Routes.SERVICES) })
                }
                pushed(Routes.ISCSI_WIZARD) { _ ->
                    app.truenascompanion.ui.iscsi.IscsiWizardScreen(onBack = { nav.popBackStack() }, onDone = { nav.popBackStack() })
                }
                pushedWithEntry(Routes.CLOUD_SYNC) { _, entry ->
                    val picked by entry.savedStateHandle.getStateFlow<String?>(Routes.PICKED_PATH, null).collectAsStateWithLifecycle()
                    app.truenascompanion.ui.cloud.CloudSyncScreen(
                        onBack = { nav.popBackStack() },
                        onEditTask = { nav.navigate(Routes.cloudTask(it)) },
                        onEditCredential = { nav.navigate(Routes.cloudCred(it)) },
                        onPickFolder = { nav.navigate(Routes.pickFolder(it)) },
                        pickedPath = picked,
                        onPickConsumed = { entry.savedStateHandle[Routes.PICKED_PATH] = null },
                    )
                }
                pushedWithEntry(Routes.CLOUD_TASK, "id?") { a, entry ->
                    val picked by entry.savedStateHandle.getStateFlow<String?>(Routes.PICKED_PATH, null).collectAsStateWithLifecycle()
                    app.truenascompanion.ui.cloud.CloudTaskEditorScreen(
                        a["id"]?.toIntOrNull(), picked,
                        onPickConsumed = { entry.savedStateHandle[Routes.PICKED_PATH] = null },
                        onBrowseLocal = { nav.navigate(Routes.pickFolder(it)) },
                        onAddCredential = { nav.navigate(Routes.cloudCred(null)) },
                        onBack = { nav.popBackStack() },
                    )
                }
                pushed(Routes.CLOUD_CRED, "id?") { a ->
                    app.truenascompanion.ui.cloud.CloudCredentialEditorScreen(a["id"]?.toIntOrNull(), onBack = { nav.popBackStack() })
                }
                pushed(Routes.REPLICATION) { _ ->
                    app.truenascompanion.ui.replication.ReplicationScreen(
                        onBack = { nav.popBackStack() },
                        onEditTask = { nav.navigate(Routes.replTask(it)) },
                        onEditConnection = { nav.navigate(Routes.sshConn(it)) },
                        onEditKeyPair = { nav.navigate(Routes.sshKey(it)) },
                    )
                }
                pushed(Routes.REPL_TASK, "id?") { a ->
                    app.truenascompanion.ui.replication.ReplicationTaskEditorScreen(
                        a["id"]?.toIntOrNull(), onAddConnection = { nav.navigate(Routes.sshConn(null)) }, onBack = { nav.popBackStack() },
                    )
                }
                pushed(Routes.SSH_CONN, "id?") { a ->
                    app.truenascompanion.ui.replication.SshConnectionEditorScreen(a["id"]?.toIntOrNull(), onBack = { nav.popBackStack() })
                }
                pushed(Routes.SSH_KEY, "id?") { a ->
                    app.truenascompanion.ui.replication.KeyPairEditorScreen(a["id"]?.toIntOrNull(), onBack = { nav.popBackStack() })
                }
                pushed(Routes.PICK_FOLDER, "path?") { a ->
                    val start = a["path"]?.takeIf { app.truenascompanion.data.files.FilePolicy.normalize(it)?.startsWith(app.truenascompanion.data.files.FilePolicy.ROOT + "/") == true }
                    app.truenascompanion.ui.files.FileBrowserScreen(
                        start ?: app.truenascompanion.data.files.FilePolicy.ROOT, onBack = { nav.popBackStack() },
                        pickFolder = { path -> nav.previousBackStackEntry?.savedStateHandle?.set(Routes.PICKED_PATH, path); nav.popBackStack() },
                    )
                }
                pushed(Routes.SNAPSHOTS, "dataset") { a ->
                    app.truenascompanion.ui.protection.SnapshotsScreen(a.getValue("dataset"), onBack = { nav.popBackStack() }, onBrowse = { nav.navigate(Routes.files(it)) })
                }
                pushed(Routes.FILES, "path?") { a ->
                    app.truenascompanion.ui.files.FileBrowserScreen(a["path"] ?: app.truenascompanion.data.files.FilePolicy.ROOT, onBack = { nav.popBackStack() })
                }
                pushed(Routes.POOL_LAYOUT, "pool") { a ->
                    app.truenascompanion.ui.disks.PoolLayoutScreen(
                        a.getValue("pool"), onBack = { nav.popBackStack() },
                        onOpenDisk = { nav.navigate(Routes.disk(it)) },
                        onReplace = { guid -> nav.navigate(Routes.replace(a.getValue("pool"), guid = guid)) },
                    )
                }
                pushed(Routes.DISK, "name") { a ->
                    app.truenascompanion.ui.disks.DiskDetailScreen(
                        a.getValue("name"), onBack = { nav.popBackStack() },
                        onReplace = { pool, guid, disk -> nav.navigate(Routes.replace(pool, guid, disk)) },
                        onOpenPool = { nav.navigate(Routes.poolLayout(it)) },
                    )
                }
                pushed(Routes.REPLACE, "pool", "guid?", "disk?") { a ->
                    app.truenascompanion.ui.disks.ReplaceWizardScreen(
                        a.getValue("pool"), a["guid"], a["disk"], onBack = { nav.popBackStack() },
                        onOpenPool = { nav.navigate(Routes.poolLayout(it)) },
                    )
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
                composable(Tab.ALERTS.route) {
                    AlertsScreen(
                        onOpenTarget = { nav.openTarget(container, it) },
                        onPhoneAlertSettings = { nav.navigate(Routes.systemPage(app.truenascompanion.ui.system.SystemPage.ALERTS)) },
                    )
                }
                composable(Tab.SYSTEM.route) { SystemScreen(onServers = { nav.navigate(Routes.SERVERS) }, onOverview = { nav.navigate(Routes.OVERVIEW) }, onJobs = { nav.navigate(Routes.JOBS) },
                    onShell = { nav.navigate(Routes.shell(app.truenascompanion.data.shell.ShellTarget.Host)) },
                    onAccounts = { nav.navigate(Routes.ACCOUNTS) }, onReports = { nav.navigate(Routes.REPORTS) }, onAudit = { nav.navigate(Routes.AUDIT) },
                    onCertificates = { nav.navigate(Routes.certificates()) },
                    onServices = { nav.navigate(Routes.SERVICES) }, onScheduledTasks = { nav.navigate(Routes.TASKS) },
                    onPage = { nav.navigate(Routes.systemPage(it)) }, onNetwork = { nav.navigate(Routes.NETWORK) }) }
                pushed(Routes.SYSTEM_PAGE, "page") { a ->
                    val page = app.truenascompanion.ui.system.SystemPage.parse(a["page"])
                    if (page == null) LaunchedEffect(Unit) { nav.popBackStack() }
                    else app.truenascompanion.ui.system.SystemPageScreen(
                        page, onBack = { nav.popBackStack() },
                        onEditServer = { nav.navigate(Routes.edit(it)) }, onVpn = { nav.navigate(Routes.vpn(it)) },
                        onRules = { nav.navigate(Routes.ALERT_RULES) },
                    )
                }
                pushed(Routes.SERVICES) {
                    app.truenascompanion.ui.services.ServicesScreen(onBack = { nav.popBackStack() }, onOpenSettings = { nav.navigate(Routes.service(it)) })
                }
                pushed(Routes.SERVICE, "kind") { a ->
                    val kind = runCatching { app.truenascompanion.data.services.ServiceKind.valueOf(a.getValue("kind")) }.getOrNull()
                    if (kind == null) LaunchedEffect(Unit) { nav.popBackStack() }
                    else app.truenascompanion.ui.services.ServiceSettingsScreen(kind, onBack = { nav.popBackStack() })
                }
                pushed(Routes.TASKS) {
                    app.truenascompanion.ui.tasks.ScheduledTasksScreen(
                        onBack = { nav.popBackStack() },
                        onEditCron = { nav.navigate(Routes.cronJob(it)) },
                        onEditScript = { nav.navigate(Routes.initScript(it)) },
                    )
                }
                pushed(Routes.CRON_JOB, "id?") { a ->
                    app.truenascompanion.ui.tasks.CronJobEditorScreen(a["id"]?.toIntOrNull(), onBack = { nav.popBackStack() })
                }
                pushedWithEntry(Routes.INIT_SCRIPT, "id?") { a, entry ->
                    val picked by entry.savedStateHandle.getStateFlow<String?>(Routes.PICKED_PATH, null).collectAsStateWithLifecycle()
                    app.truenascompanion.ui.tasks.InitScriptEditorScreen(
                        a["id"]?.toIntOrNull(), picked,
                        onPickConsumed = { entry.savedStateHandle[Routes.PICKED_PATH] = null },
                        onBrowse = { nav.navigate(Routes.pickFile(it)) },
                        onBack = { nav.popBackStack() },
                    )
                }
                pushed(Routes.PICK_FILE, "path?") { a ->
                    val start = a["path"]?.takeIf { app.truenascompanion.data.files.FilePolicy.normalize(it)?.startsWith(app.truenascompanion.data.files.FilePolicy.ROOT + "/") == true }
                    app.truenascompanion.ui.files.FileBrowserScreen(
                        start ?: app.truenascompanion.data.files.FilePolicy.ROOT, onBack = { nav.popBackStack() },
                        pickFile = { path -> nav.previousBackStackEntry?.savedStateHandle?.set(Routes.PICKED_PATH, path); nav.popBackStack() },
                    )
                }
                composable(Routes.JOBS) { JobsScreen(onBack = { nav.popBackStack() }) }
                pushed(Routes.ACCOUNTS) { app.truenascompanion.ui.accounts.AccountsScreen(onBack = { nav.popBackStack() }) }
                pushed(Routes.REPORTS) { app.truenascompanion.ui.reports.ReportsScreen(onBack = { nav.popBackStack() }) }
                pushed(Routes.AUDIT) { app.truenascompanion.ui.audit.AuditScreen(onBack = { nav.popBackStack() }) }
                // 1.9.0: Network settings, view only.
                pushed(Routes.ALERT_RULES) { app.truenascompanion.ui.notifications.AlertRulesScreen(onBack = { nav.popBackStack() }) }
                pushed(Routes.NETWORK) { app.truenascompanion.ui.network.NetworkScreen(onBack = { nav.popBackStack() }, onReports = { nav.navigate(Routes.REPORTS) }) }
                pushed(Routes.CERTIFICATES, "highlight?") { a ->
                    app.truenascompanion.ui.certs.CertificatesScreen(onBack = { nav.popBackStack() }, onReviewServer = { id -> nav.navigate(Routes.edit(id)) }, highlight = a["highlight"])
                }
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
}

/** Opens the screen an alert is about (1.2.0; 1.3.0 opens pool layout, disk detail and the replace wizard). */
private fun NavHostController.openTarget(container: app.truenascompanion.AppContainer, t: app.truenascompanion.notify.AlertTarget) {
    when (t) {
        is app.truenascompanion.notify.AlertTarget.Pool -> {
            container.storageTabRequest.value = app.truenascompanion.ui.storage.StorageTabs.POOLS; switchTab(Tab.STORAGE.route); navigate(Routes.poolLayout(t.name))
        }
        is app.truenascompanion.notify.AlertTarget.Disk -> {
            container.storageTabRequest.value = app.truenascompanion.ui.storage.StorageTabs.DISKS; switchTab(Tab.STORAGE.route)
            t.name?.let { navigate(Routes.disk(it)) }
        }
        is app.truenascompanion.notify.AlertTarget.ReplaceDisk -> {
            container.storageTabRequest.value = app.truenascompanion.ui.storage.StorageTabs.POOLS; switchTab(Tab.STORAGE.route); navigate(Routes.replace(t.pool))
        }
        is app.truenascompanion.notify.AlertTarget.Dataset -> { container.storageTabRequest.value = app.truenascompanion.ui.storage.StorageTabs.DATASETS; switchTab(Tab.STORAGE.route) }
        is app.truenascompanion.notify.AlertTarget.Snapshots -> { switchTab(Tab.STORAGE.route); navigate(Routes.snapshots(t.dataset)) }
        is app.truenascompanion.notify.AlertTarget.App -> { switchTab(Tab.APPS.route); navigate(Routes.app(t.name)) }
        app.truenascompanion.notify.AlertTarget.Apps -> switchTab(Tab.APPS.route)
        app.truenascompanion.notify.AlertTarget.Update -> { switchTab(Tab.SYSTEM.route); navigate(Routes.systemPage(app.truenascompanion.ui.system.SystemPage.UPDATES)) }
        is app.truenascompanion.notify.AlertTarget.Certificate -> { switchTab(Tab.SYSTEM.route); navigate(Routes.certificates(t.name)) }
        app.truenascompanion.notify.AlertTarget.CloudSync -> {
            container.storageTabRequest.value = app.truenascompanion.ui.storage.StorageTabs.PROTECTION; switchTab(Tab.STORAGE.route); navigate(Routes.CLOUD_SYNC)
        }
        app.truenascompanion.notify.AlertTarget.Replication -> {
            container.storageTabRequest.value = app.truenascompanion.ui.storage.StorageTabs.PROTECTION; switchTab(Tab.STORAGE.route); navigate(Routes.REPLICATION)
        }
        app.truenascompanion.notify.AlertTarget.Alerts -> switchTab(Tab.ALERTS.route)
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
private fun androidx.navigation.NavGraphBuilder.pushed(route: String, vararg args: String, content: @Composable (Map<String, String>) -> Unit) =
    pushedWithEntry(route, *args) { values, _ -> content(values) }

/** [pushed] that also hands over the back stack entry (for results such as a picked file). */
private fun androidx.navigation.NavGraphBuilder.pushedWithEntry(
    route: String,
    vararg args: String,
    content: @Composable (Map<String, String>, androidx.navigation.NavBackStackEntry) -> Unit,
) {
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
        content(values, entry)
    }
}

private fun NavHostController.backToDashboard() {
    if (!popBackStack(Tab.DASHBOARD.route, inclusive = false)) switchTab(Tab.DASHBOARD.route)
}

/** Bottom navigation with single-line labels and a glowing selected icon.
 *  When [enabled] is false (connection overlay up), tabs look grayed out and ignore taps. */
@Composable
internal fun AppNavBar(route: String?, enabled: Boolean = true, alertCount: Int = 0, onTab: (String) -> Unit) {
    val brand = app.truenascompanion.ui.theme.LocalBrandColors.current
    val scheme = MaterialTheme.colorScheme
    NavigationBar(
        containerColor = scheme.surfaceContainer,
        modifier = Modifier.then(
            if (!enabled) Modifier.graphicsLayer { alpha = 0.45f } else Modifier
        ),
    ) {
        Tab.entries.forEach { tab ->
            val selected = route == tab.route
            NavigationBarItem(
                selected = selected,
                enabled = enabled,
                onClick = { if (enabled) onTab(tab.route) },
                icon = {
                    val badge = if (tab == Tab.ALERTS) app.truenascompanion.ui.alerts.AlertBadge.label(alertCount) else null
                    androidx.compose.material3.BadgedBox(badge = {
                        if (badge != null) androidx.compose.material3.Badge { Text(badge) }
                    }) {
                        Icon(
                            if (selected) tab.selected else tab.unselected, null,
                            modifier = if (selected && enabled) Modifier.glow(brand.glow, 8.dp, androidx.compose.foundation.shape.CircleShape, alpha = if (brand.dark) 0.7f else 0.35f) else Modifier,
                        )
                    }
                },
                // 1.8.0 (UX review, 200 % font): the label grows to at most 1.3x so five tabs still fit.
                label = {
                    // The item hides its icon (and badge) from TalkBack, so the label carries the count.
                    val spoken = if (tab == Tab.ALERTS && alertCount > 0) "${tab.label}, $alertCount open alerts" else null
                    Text(
                        tab.label, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = cappedSp(12f, 1.3f)),
                        modifier = if (spoken != null) Modifier.semantics { contentDescription = spoken } else Modifier,
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = if (brand.dark) brand.accent else scheme.primary,
                    selectedTextColor = if (brand.dark) brand.accent else scheme.primary,
                    indicatorColor = if (brand.dark) scheme.primary.copy(alpha = 0.22f) else scheme.secondaryContainer,
                    disabledIconColor = scheme.onSurface.copy(alpha = 0.38f),
                    disabledTextColor = scheme.onSurface.copy(alpha = 0.38f),
                ),
            )
        }
    }
}

/** A font size that follows the user's font scale up to [maxScale] (1.8.0: nav labels and other fixed-width text). */
@Composable
internal fun cappedSp(base: Float, maxScale: Float): androidx.compose.ui.unit.TextUnit {
    val scale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    return androidx.compose.ui.unit.TextUnit(base * minOf(scale, maxScale) / scale, androidx.compose.ui.unit.TextUnitType.Sp)
}
