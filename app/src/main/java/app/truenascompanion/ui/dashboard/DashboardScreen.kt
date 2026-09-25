package app.truenascompanion.ui.dashboard

import app.truenascompanion.ui.theme.LocalStatusColors
import app.truenascompanion.ui.theme.LocalBrandColors
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.truenascompanion.data.model.Health
import app.truenascompanion.data.model.WidgetConfig
import app.truenascompanion.data.model.WidgetSize
import app.truenascompanion.data.model.WidgetType
import app.truenascompanion.data.repository.ConnectionState
import app.truenascompanion.ui.appViewModel
import app.truenascompanion.ui.components.ConfirmDialog
import app.truenascompanion.ui.components.IconBadge
import app.truenascompanion.ui.components.InfoBanner
import app.truenascompanion.ui.components.ScrollableErrorState
import app.truenascompanion.ui.components.SkeletonCard
import app.truenascompanion.ui.components.StatusChip
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(onOpen: (WidgetType) -> Unit, onServers: () -> Unit) {
    val vm = appViewModel { DashboardViewModel(it) }
    val server by vm.server.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val route by vm.route.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val layout by vm.layout.collectAsStateWithLifecycle()
    val editing by vm.editing.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    val flavor = (connection as? ConnectionState.Connected)?.flavor

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).dashboardHeaderGlow()) {
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.94f),
                ),
                title = {
                    if (editing) Text("Edit dashboard", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    else {
                        val (label, health) = connectionLabel(connection, live.latest != null)
                        DashboardTitle(data.system?.hostname ?: server?.name ?: "Dashboard", label, health, live.latest != null,
                            route.takeIf { connection is ConnectionState.Connected })
                    }
                },
                actions = {
                    if (editing) {
                        IconButton(onClick = { confirmReset = true }) { Icon(Icons.Rounded.RestartAlt, "Reset to default layout") }
                        IconButton(onClick = { vm.setEditing(false) }) { Icon(Icons.Rounded.Check, "Done") }
                    } else {
                        IconButton(onClick = { vm.setEditing(true) }) { Icon(Icons.Rounded.Tune, "Edit dashboard") }
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Edit dashboard") }, leadingIcon = { Icon(Icons.Rounded.Dashboard, null) },
                                onClick = { menu = false; vm.setEditing(true) })
                            DropdownMenuItem(text = { Text("Switch server") }, leadingIcon = { Icon(Icons.Rounded.Dns, null) },
                                onClick = { menu = false; onServers() })
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (server == null) {
                app.truenascompanion.ui.components.EmptyState(
                    icon = Icons.Rounded.Dns, title = "No server yet",
                    message = "Connect your TrueNAS SCALE server to get started.",
                    action = { androidx.compose.material3.Button(onClick = onServers) { Text("Add a server") } },
                    modifier = Modifier.padding(top = 48.dp),
                )
            } else if (editing) {
                EditGrid(layout.widgets, vm)
            } else {
                PullToRefreshBox(isRefreshing = data.refreshing, onRefresh = { vm.refresh() }, modifier = Modifier.fillMaxSize()) {
                    when {
                        data.error != null && data.system == null -> ScrollableErrorState(data.error!!, data.loginRequired) { vm.refresh() }
                        data.loading && data.system == null -> SkeletonGrid()
                        else -> WidgetGrid(layout.visibleWidgets, data, live, flavor, onOpen, onEdit = { vm.setEditing(true) })
                    }
                }
            }
        }
    }

    }

    if (confirmReset) {
        ConfirmDialog(
            title = "Reset dashboard?", text = "All blocks will be shown again in the default order and size.",
            confirmLabel = "Reset", icon = Icons.Rounded.RestartAlt,
            onConfirm = { vm.resetLayout(); confirmReset = false }, onDismiss = { confirmReset = false },
        )
    }
}

/** Soft royal-blue glow behind the top of the dashboard. */
@Composable
fun Modifier.dashboardHeaderGlow(): Modifier {
    val brand = LocalBrandColors.current
    val c = brand.headerGlow.copy(alpha = if (brand.dark) 0.30f else 0.14f)
    val c2 = brand.accent.copy(alpha = if (brand.dark) 0.10f else 0.06f)
    return this.drawBehind {
        val h = 280.dp.toPx().coerceAtMost(size.height)
        drawRect(Brush.verticalGradient(listOf(c, c2, Color.Transparent), endY = h), size = androidx.compose.ui.geometry.Size(size.width, h))
    }
}

@Composable
fun DashboardTitle(title: String, label: String, health: Health, live: Boolean, route: app.truenascompanion.data.model.Route? = null) {
    Column {
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (live) { LiveDot(); Spacer(Modifier.width(4.dp)) }
            else {
                Box(Modifier.size(7.dp).clip(androidx.compose.foundation.shape.CircleShape).background(LocalStatusColors.current.of(health)))
                Spacer(Modifier.width(6.dp))
            }
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            route?.let { Spacer(Modifier.width(8.dp)); app.truenascompanion.ui.components.RouteChip(it) }
        }
    }
}

private fun connectionLabel(c: ConnectionState, live: Boolean): Pair<String, Health> = when (c) {
    is ConnectionState.Connected -> (if (live) "Live" else "Connected") to Health.HEALTHY
    ConnectionState.Connecting -> "Connecting…" to Health.UNKNOWN
    is ConnectionState.Failed -> "Offline" to Health.CRITICAL
    ConnectionState.Idle -> "…" to Health.UNKNOWN
    ConnectionState.NoServer -> "No server" to Health.UNKNOWN
}

@Composable
private fun SkeletonGrid() {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(2) }) { SkeletonCard(height = 140.dp) }
        items(4) { SkeletonCard(height = 132.dp) }
        item(span = { GridItemSpan(2) }) { SkeletonCard(height = 160.dp) }
    }
}

@Composable
internal fun WidgetGrid(
    widgets: List<WidgetConfig>,
    data: DashboardData,
    live: LiveStats,
    flavor: app.truenascompanion.data.model.ApiFlavor?,
    onOpen: (WidgetType) -> Unit,
    onEdit: () -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (data.error != null) {
            item(span = { GridItemSpan(2) }) { InfoBanner("Showing last known data: ${data.error}", health = Health.WARNING) }
        }
        if (widgets.isEmpty()) {
            item(span = { GridItemSpan(2) }) {
                app.truenascompanion.ui.components.EmptyState(
                    icon = Icons.Rounded.Dashboard, title = "Your dashboard is empty",
                    message = "All blocks are hidden. Tap edit to choose what to show.",
                    action = { androidx.compose.material3.FilledTonalButton(onClick = onEdit) { Text("Edit dashboard") } },
                )
            }
        }
        items(widgets, key = { it.type.name }, span = { GridItemSpan(if (it.size == WidgetSize.FULL) 2 else 1) }) { w ->
            val target: (() -> Unit)? = when (w.type) {
                WidgetType.POOLS, WidgetType.APPS, WidgetType.ALERTS, WidgetType.TEMPERATURE -> ({ onOpen(w.type) })
                else -> null
            }
            DashboardWidget(
                type = w.type, full = w.size == WidgetSize.FULL, data = data, live = live, flavor = flavor,
                onClick = target, modifier = Modifier.animateItem().animateContentSize(),
            )
        }
    }
}

/** Edit mode: long-press (or drag the handle) to reorder, toggle visibility and size per block. */
@Composable
private fun EditGrid(widgets: List<WidgetConfig>, vm: DashboardViewModel) {
    val haptics = LocalHapticFeedback.current
    val gridState = rememberLazyGridState()
    val reorderState = rememberReorderableLazyGridState(gridState) { from, to ->
        vm.moveByKey(from.key as? String, to.key as? String)
        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = gridState,
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(2) }, key = "hint") {
            Text(
                "Long-press and drag to reorder. Tap the eye to show or hide a block and the arrows to switch between half and full width.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        items(widgets, key = { it.type.name }, span = { GridItemSpan(if (it.size == WidgetSize.FULL) 2 else 1) }) { w ->
            ReorderableItem(reorderState, key = w.type.name) { dragging ->
                val elevation by animateDpAsState(if (dragging) 10.dp else 0.dp, label = "elev")
                val alpha by animateFloatAsState(if (w.visible) 1f else 0.5f, label = "alpha")
                Card(
                    modifier = Modifier.fillMaxWidth().longPressDraggableHandle(
                        onDragStarted = { haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) },
                        onDragStopped = { haptics.performHapticFeedback(HapticFeedbackType.GestureEnd) },
                    ),
                    shape = MaterialTheme.shapes.large,
                    elevation = CardDefaults.cardElevation(defaultElevation = elevation),
                    colors = CardDefaults.cardColors(
                        containerColor = if (dragging) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, LocalBrandColors.current.cardBorder.copy(alpha = 0.7f)),
                ) {
                    Column(Modifier.padding(12.dp).alpha(alpha)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.DragIndicator, "Drag to reorder", Modifier.draggableHandle(), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(6.dp))
                            IconBadge(w.type.icon(), size = 30.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(if (w.size == WidgetSize.FULL) w.type.title else w.type.shortTitle, style = MaterialTheme.typography.titleSmall, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AnimatedVisibility(!w.visible) { StatusChip(Health.UNKNOWN, "Hidden", showIcon = false) }
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { vm.toggleSize(w.type) }) {
                                Icon(
                                    if (w.size == WidgetSize.FULL) Icons.Rounded.CloseFullscreen else Icons.Rounded.OpenInFull,
                                    if (w.size == WidgetSize.FULL) "Make half width" else "Make full width",
                                    Modifier.size(20.dp),
                                )
                            }
                            IconButton(onClick = { vm.toggleVisible(w.type) }) {
                                Icon(if (w.visible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, if (w.visible) "Hide" else "Show", Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
