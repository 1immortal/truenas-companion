package app.truenascompanion.ui.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.truenascompanion.AppContainer
import app.truenascompanion.data.api.TrueNasException
import app.truenascompanion.data.model.Health
import app.truenascompanion.data.model.Route
import app.truenascompanion.data.model.ServerConfig
import app.truenascompanion.data.net.Keepalive
import app.truenascompanion.data.vpn.TunnelHolder
import app.truenascompanion.ui.appViewModel
import app.truenascompanion.ui.components.ElevatedSection
import app.truenascompanion.ui.components.EmptyState
import app.truenascompanion.ui.components.IconBadge
import app.truenascompanion.ui.components.SkeletonList
import app.truenascompanion.ui.components.StatusChip
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ServerOverviewRow(
    val server: ServerConfig,
    val poolHealth: Health = Health.UNKNOWN,
    val poolSummary: String = "…",
    val alertCount: Int = 0,
    val route: Route? = null,
    val online: Boolean = false,
    val error: String? = null,
    val loading: Boolean = true,
)

class MultiServerViewModel(private val c: AppContainer) : ViewModel() {
    val servers = c.settings.servers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val activeId = c.settings.activeServerId.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val _rows = MutableStateFlow<List<ServerOverviewRow>>(emptyList())
    val rows = _rows.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()

    fun refresh() = viewModelScope.launch {
        val list = c.settings.servers.first()
        _rows.value = list.map { ServerOverviewRow(it) }
        _refreshing.value = true
        try {
            coroutineScope {
                val results = list.map { s ->
                    async { probe(s) }
                }.awaitAll()
                _rows.value = results
            }
        } finally {
            _refreshing.value = false
        }
    }

    private suspend fun probe(server: ServerConfig): ServerOverviewRow {
        var target = server
        return try {
            target = c.routes.acquire(server, TunnelHolder.CHECK)
            val api = c.backgroundConnector.connect(target, Keepalive.NONE)
            try {
                val pools = runCatching { api.pools() }.getOrDefault(emptyList())
                val alerts = runCatching { api.alerts().count { !it.dismissed } }.getOrDefault(0)
                val worst = pools.map { it.health }.minByOrNull {
                    when (it) { Health.CRITICAL -> 0; Health.WARNING -> 1; Health.UNKNOWN -> 2; Health.HEALTHY -> 3 }
                } ?: Health.UNKNOWN
                ServerOverviewRow(
                    server = server,
                    poolHealth = worst,
                    poolSummary = when {
                        pools.isEmpty() -> "No pools"
                        pools.all { it.health == Health.HEALTHY } -> "${pools.size} pools healthy"
                        else -> pools.filter { it.health != Health.HEALTHY }.joinToString { "${it.name} ${it.status}" }.ifBlank { "${pools.size} pools" }
                    },
                    alertCount = alerts,
                    route = target.activeRoute,
                    online = true,
                    loading = false,
                )
            } finally {
                api.close()
            }
        } catch (e: TrueNasException.LoginRequired) {
            ServerOverviewRow(server, error = "Sign in needed", loading = false)
        } catch (e: Throwable) {
            ServerOverviewRow(server, error = "Offline", loading = false)
        } finally {
            runCatching { c.routes.release(target, TunnelHolder.CHECK) }
        }
    }

    fun open(serverId: String, then: () -> Unit) = viewModelScope.launch {
        c.settings.setActiveServer(serverId)
        then()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiServerOverviewScreen(onBack: () -> Unit, onOpenServer: () -> Unit) {
    val vm = appViewModel { MultiServerViewModel(it) }
    val rows by vm.rows.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val activeId by vm.activeId.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("All servers") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { vm.refresh() }) { Icon(Icons.Rounded.Refresh, "Refresh") } },
            )
        },
    ) { padding ->
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { vm.refresh() }, modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                rows.isEmpty() && refreshing -> SkeletonList(3, 96.dp)
                rows.isEmpty() -> EmptyState(Icons.Rounded.Dns, "No servers", "Add a TrueNAS server to see it here.")
                else -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
                    items(rows, key = { it.server.id }) { row ->
                        ElevatedSection(onClick = { vm.open(row.server.id, onOpenServer) }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconBadge(Icons.Rounded.Dns)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(row.server.name.ifBlank { row.server.displayHost }, style = MaterialTheme.typography.titleMedium)
                                        if (row.server.id == activeId) {
                                            Spacer(Modifier.width(8.dp))
                                            StatusChip(Health.HEALTHY, "Active", showIcon = false)
                                        }
                                    }
                                    Text(row.server.displayHost, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.height(6.dp))
                                    when {
                                        row.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                            Spacer(Modifier.width(8.dp))
                                            Text("Checking…", style = MaterialTheme.typography.bodySmall)
                                        }
                                        row.error != null -> StatusChip(Health.CRITICAL, row.error)
                                        else -> {
                                            StatusChip(row.poolHealth, row.poolSummary, showIcon = false)
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                buildString {
                                                    append(if (row.alertCount == 0) "No open alerts" else "${row.alertCount} open alert${if (row.alertCount == 1) "" else "s"}")
                                                    row.route?.let { append(" · ${it.label}") }
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                                Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}
