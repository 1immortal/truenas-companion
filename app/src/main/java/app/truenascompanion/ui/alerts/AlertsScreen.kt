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

    private suspend fun load() {
        try {
            _state.value = UiState.Success(c.repository.call { it.alerts() })
        } catch (e: Throwable) {
            if (_state.value !is UiState.Success) _state.value = UiState.Error(e.userMessage(), e) else _messages.trySend(e.userMessage())
        }
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
fun AlertsScreen() {
    val vm = appViewModel { AlertsViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    var showDismissed by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(topBar = { TopAppBar(title = { Text("Alerts") }) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { vm.refresh() }, modifier = Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                UiState.Loading -> SkeletonList(5, 90.dp)
                is UiState.Error -> ScrollableErrorState(s.message, s.isLoginRequired) { vm.refresh() }
                is UiState.Success -> {
                    val active = s.data.filter { !it.dismissed }
                    val list = if (showDismissed) s.data else active
                    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${active.size} active", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).padding(start = 4.dp))
                                FilterChip(selected = showDismissed, onClick = { showDismissed = !showDismissed }, label = { Text("Show dismissed") })
                            }
                        }
                        if (list.isEmpty()) item {
                            EmptyState(Icons.Rounded.DoneAll, "All clear", "No active alerts. Your NAS is happy.")
                        }
                        items(list, key = { it.uuid }) { a -> AlertCard(a, onDismiss = { vm.dismiss(a) }, modifier = Modifier.animateItem()) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlertCard(a: AlertItem, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(a.uuid) { mutableStateOf(false) }
    val color = LocalStatusColors.current.of(a.health)
    Card(
        onClick = { expanded = !expanded },
        modifier = modifier.fillMaxWidth().animateContentSize().alpha(if (a.dismissed) 0.6f else 1f),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(6.dp).fillMaxHeight().background(color, RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp)))
            Column(Modifier.padding(14.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusChip(a.health, a.level.lowercase().replaceFirstChar { it.uppercase() })
                    Spacer(Modifier.weight(1f))
                    Text(Format.relativeTime(a.datetimeMillis), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                Text(a.text, style = MaterialTheme.typography.bodyMedium, maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
                if (!a.dismissed) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
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

