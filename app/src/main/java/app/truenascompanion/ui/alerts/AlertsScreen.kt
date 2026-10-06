package app.truenascompanion.ui.alerts

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.truenascompanion.AppContainer
import app.truenascompanion.data.api.userMessage
import app.truenascompanion.data.model.AlertItem
import app.truenascompanion.notify.AlertTarget
import app.truenascompanion.notify.Snooze
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import kotlinx.coroutines.flow.flatMapLatest
import app.truenascompanion.ui.appViewModel
import app.truenascompanion.ui.components.EmptyState
import app.truenascompanion.ui.components.ScrollableErrorState
import app.truenascompanion.ui.components.isLoginRequired
import app.truenascompanion.ui.components.SkeletonList
import app.truenascompanion.ui.components.StatusChip
import app.truenascompanion.ui.components.UiState
import app.truenascompanion.ui.theme.LocalStatusColors
import app.truenascompanion.util.Format
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.stateIn
import app.truenascompanion.ui.notifications.PhoneAlerts
import app.truenascompanion.ui.notifications.PhoneAlertsPromptCard
import app.truenascompanion.ui.notifications.rememberNotificationAccess
import androidx.compose.ui.platform.LocalContext

class AlertsViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow<UiState<List<AlertItem>>>(UiState.Loading)
    val state = _state.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        viewModelScope.launch {
            c.repository.reloadKey.collect { if (it != null) { _state.value = UiState.Loading; load() } }
        }
    }

    fun refresh() = viewModelScope.launch { _refreshing.value = true; load(); _refreshing.value = false }

    val server = c.repository.activeServer
    val notificationPrefs = c.settings.notificationPrefs
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), null)

    fun enablePhoneAlerts(context: android.content.Context) = viewModelScope.launch {
        val id = server.value?.id ?: return@launch
        PhoneAlerts.enable(c, context, id)
        _messages.trySend("Phone alerts are on. Fine-tune them in System › Phone alerts.")
    }

    fun dismissPrompt() = viewModelScope.launch { c.settings.updateNotificationPrefs { it.copy(promptDismissed = true) } }

    private suspend fun load() {
        try {
            _state.value = UiState.Success(c.repository.call { it.alerts() })
        } catch (e: Throwable) {
            if (_state.value !is UiState.Success) _state.value = UiState.Error(e.userMessage(), e) else _messages.trySend(e.userMessage())
        }
    }

    /** Local snoozes of the active server (1.2.0). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val snoozes = server.map { it?.id }.distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) kotlinx.coroutines.flow.flowOf(emptyMap()) else c.settings.snoozesFlow(id) }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyMap())

    fun snooze(alert: AlertItem, option: Snooze.Option) = viewModelScope.launch {
        val id = server.value?.id ?: return@launch
        val until = System.currentTimeMillis() + option.millis
        c.settings.updateSnoozes(id) { it + (alert.uuid to until) }
        c.notifier.removeFromShade(id, setOf(alert.uuid))
        _messages.trySend("Snoozed for ${option.label} on this phone")
    }

    fun unsnooze(alert: AlertItem) = viewModelScope.launch {
        val id = server.value?.id ?: return@launch
        c.settings.updateSnoozes(id) { it - alert.uuid }
    }

    fun dismiss(alert: AlertItem) = viewModelScope.launch {
        // Optimistic update, re-sync afterwards.
        _state.update { s -> if (s is UiState.Success) UiState.Success(s.data.map { if (it.uuid == alert.uuid) it.copy(dismissed = true) else it }) else s }
        try {
            c.repository.call { it.dismissAlert(alert.uuid) }
        } catch (e: Throwable) {
            _messages.trySend("Couldn't dismiss: ${e.userMessage()}")
        }
        load()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsScreen(onOpenTarget: (AlertTarget) -> Unit = {}) {
    val vm = appViewModel { AlertsViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    var showDismissed by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    val server by vm.server.collectAsStateWithLifecycle()
    val prefs by vm.notificationPrefs.collectAsStateWithLifecycle()
    val snoozes by vm.snoozes.collectAsStateWithLifecycle()
    val now = remember(snoozes, state) { System.currentTimeMillis() }
    val context = LocalContext.current
    val enableAlerts = rememberNotificationAccess { vm.enablePhoneAlerts(context) }
    val showPrompt = server != null && prefs?.let { !it.promptDismissed && !it.isEnabled(server?.id) } == true

    Scaffold(topBar = { TopAppBar(title = { Text("Alerts") }) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { vm.refresh() }, modifier = Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                UiState.Loading -> SkeletonList(5, 90.dp)
                is UiState.Error -> ScrollableErrorState(s.message, s.isLoginRequired) { vm.refresh() }
                is UiState.Success -> {
                    val active = s.data.filter { !it.dismissed }
                    val list = if (showDismissed) s.data else active
                    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
                        if (showPrompt) item(key = "phone-alerts-prompt") {
                            PhoneAlertsPromptCard(server?.name ?: "your NAS", onEnable = enableAlerts, onDismiss = { vm.dismissPrompt() }, modifier = Modifier.animateItem())
                        }
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${active.size} active", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).padding(start = 4.dp))
                                FilterChip(selected = showDismissed, onClick = { showDismissed = !showDismissed }, label = { Text("Show dismissed") })
                            }
                        }
                        if (list.isEmpty()) item {
                            EmptyState(Icons.Rounded.DoneAll, "All clear", "No active alerts. Your NAS is happy.")
                        }
                        items(list, key = { it.uuid }) { a ->
                            AlertCard(
                                a, snoozedUntil = snoozes[a.uuid]?.takeIf { it > now },
                                onDismiss = { vm.dismiss(a) }, onSnooze = { vm.snooze(a, it) }, onUnsnooze = { vm.unsnooze(a) },
                                onOpen = onOpenTarget, modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One alert (stateless; screenshot tests render it with example data). */
@Composable
fun AlertCard(
    a: AlertItem,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    snoozedUntil: Long? = null,
    onSnooze: (Snooze.Option) -> Unit = {},
    onUnsnooze: () -> Unit = {},
    onOpen: (AlertTarget) -> Unit = {},
) {
    var expanded by rememberSaveable(a.uuid) { mutableStateOf(false) }
    var snoozeMenu by remember { mutableStateOf(false) }
    val color = LocalStatusColors.current.of(a.health)
    val target = remember(a.uuid, a.klass) { AlertTarget.of(a) }
    Card(
        onClick = { expanded = !expanded },
        modifier = modifier.fillMaxWidth().animateContentSize().alpha(if (a.dismissed || snoozedUntil != null) 0.6f else 1f),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(6.dp).fillMaxHeight().background(color, RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp)))
            Column(Modifier.padding(14.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusChip(a.health, a.level.lowercase().replaceFirstChar { it.uppercase() })
                    if (snoozedUntil != null) {
                        Spacer(Modifier.width(6.dp))
                        StatusChip(app.truenascompanion.data.model.Health.UNKNOWN, "Snoozed until ${snoozeText(snoozedUntil)}", showIcon = false)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(Format.relativeTime(a.datetimeMillis), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                Text(a.text, style = MaterialTheme.typography.bodyMedium, maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
                if (!a.dismissed) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        if (target !is AlertTarget.Alerts) {
                            TextButton(onClick = { onOpen(target) }) { Text(target.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            Spacer(Modifier.weight(1f))
                        }
                        if (snoozedUntil != null) TextButton(onClick = onUnsnooze) { Text("Unsnooze") }
                        else Box {
                            TextButton(onClick = { snoozeMenu = true }) { Text("Snooze") }
                            DropdownMenu(snoozeMenu, onDismissRequest = { snoozeMenu = false }) {
                                Snooze.OPTIONS.forEach { o -> DropdownMenuItem(text = { Text("For ${o.label}") }, onClick = { snoozeMenu = false; onSnooze(o) }) }
                            }
                        }
                        TextButton(onClick = onDismiss) { Text("Dismiss") }
                    }
                } else {
                    Spacer(Modifier.height(6.dp))
                    Text("Dismissed", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun snoozeText(until: Long): String {
    val sameDay = until - System.currentTimeMillis() < 20 * 3_600_000L
    val f = if (sameDay) java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT) else java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
    return f.format(java.util.Date(until))
}
