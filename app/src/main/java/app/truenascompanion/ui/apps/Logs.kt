package app.truenascompanion.ui.apps

import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.VerticalAlignBottom
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import app.truenascompanion.ui.components.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import app.truenascompanion.ui.components.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.truenascompanion.AppContainer
import app.truenascompanion.data.api.userMessage
import app.truenascompanion.data.model.AppContainerInfo
import app.truenascompanion.data.model.LogLine
import app.truenascompanion.ui.appViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class LogStatus { CONNECTING, STREAMING, ENDED }

data class LogsUi(
    val containers: List<AppContainerInfo> = emptyList(),
    val selected: String? = null,
    val lines: List<LogLine> = emptyList(),
    val status: LogStatus = LogStatus.CONNECTING,
    val error: String? = null,
)

enum class LogLevel { ERROR, WARN, OTHER }

object LogBuffer {
    const val MAX_LINES = 3000
    private val errorPattern = Regex("""\b(ERR|ERROR|FATAL|CRIT|CRITICAL|PANIC|EXCEPTION)\b|\[(E|ERR)]|level=(error|fatal)""", RegexOption.IGNORE_CASE)
    private val warnPattern = Regex("""\b(WRN|WARN|WARNING)\b|\[W]|level=warn""", RegexOption.IGNORE_CASE)

    fun level(text: String): LogLevel = when {
        errorPattern.containsMatchIn(text) -> LogLevel.ERROR
        warnPattern.containsMatchIn(text) -> LogLevel.WARN
        else -> LogLevel.OTHER
    }

    /** Appends and trims to the newest [max] lines. */
    fun append(buffer: ArrayDeque<LogLine>, line: LogLine, max: Int = MAX_LINES) {
        buffer.addLast(line)
        while (buffer.size > max) buffer.removeFirst()
    }

    fun filter(lines: List<LogLine>, query: String): List<LogLine> =
        if (query.isBlank()) lines else lines.filter { it.text.contains(query, ignoreCase = true) }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LogsViewModel(private val c: AppContainer, val appName: String, initialContainer: String?) : ViewModel() {
    private val containers = MutableStateFlow<List<AppContainerInfo>>(emptyList())
    private val selected = MutableStateFlow(initialContainer)
    private val restart = MutableStateFlow(0)
    private val loadError = MutableStateFlow<String?>(null)

    private data class Stream(val lines: List<LogLine>, val status: LogStatus, val error: String? = null)

    /**
     * The log stream only runs while the screen is visible (WhileSubscribed + lifecycle collection); coming back
     * re-reads the tail rather than keeping a subscription alive in the background. Lines are batched to one UI update
     * per 200 ms so a chatty container can't flood recomposition.
     */
    val ui: StateFlow<LogsUi> = combine(containers, selected, restart, loadError) { list, sel, n, err -> Triple(list, sel, n) to err }
        .flatMapLatest { (key, err) ->
            val (list, sel, _) = key
            val stream: Flow<Stream> = if (sel == null) flowOf(Stream(emptyList(), if (err != null) LogStatus.ENDED else LogStatus.CONNECTING, err)) else follow(sel)
            stream.let { s -> combine(s, flowOf(list)) { st, l -> LogsUi(l, sel, st.lines, st.status, st.error) } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LogsUi(selected = initialContainer))

    init { loadContainers() }

    private fun loadContainers() = viewModelScope.launch {
        try {
            val app = c.repository.call { it.apps() }.firstOrNull { it.name == appName }
            val list = app?.containerDetails.orEmpty()
            containers.value = list
            if (selected.value == null || list.none { it.id == selected.value }) selected.value = list.firstOrNull()?.id
            if (list.isEmpty()) loadError.value = "$appName has no running containers"
        } catch (e: Throwable) { loadError.value = e.userMessage() }
    }

    private fun follow(containerId: String): Flow<Stream> = channelFlow {
        val buffer = ArrayDeque<LogLine>()
        val lock = Mutex()
        var dirty = true
        var status = LogStatus.CONNECTING
        var error: String? = null
        val ticker = launch {
            while (true) {
                val snapshot = lock.withLock { if (dirty) { dirty = false; Stream(buffer.toList(), status, error) } else null }
                snapshot?.let { send(it) }
                delay(200)
            }
        }
        try {
            c.repository.appLogs(appName, containerId).collect { line ->
                lock.withLock { LogBuffer.append(buffer, line); status = LogStatus.STREAMING; dirty = true }
            }
            lock.withLock { status = LogStatus.ENDED; dirty = true }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Throwable) { lock.withLock { status = LogStatus.ENDED; error = e.userMessage(); dirty = true } }
        delay(250) // let the ticker publish the final state
        ticker.cancel()
        send(lock.withLock { Stream(buffer.toList(), status, error) })
    }

    fun select(id: String) { selected.value = id }
    fun resume() { if (containers.value.isEmpty()) { loadError.value = null; loadContainers() } else restart.value++ }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(appName: String, containerId: String?, onBack: () -> Unit) {
    val vm = appViewModel(key = "logs-$appName") { LogsViewModel(it, appName, containerId) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var follow by rememberSaveable { mutableStateOf(true) }
    var paused by rememberSaveable { mutableStateOf(false) }
    var frozen by remember { mutableStateOf<List<LogLine>?>(null) }
    val shownSource = if (paused) frozen ?: ui.lines else ui.lines
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Logs", maxLines = 1)
                        val service = ui.containers.firstOrNull { it.id == ui.selected }?.service
                        Text(listOfNotNull(appName, service).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    IconToggleButton(checked = paused, onCheckedChange = { paused = it; frozen = if (it) ui.lines else null }) {
                        Icon(if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, if (paused) "Resume display" else "Pause display")
                    }
                    IconToggleButton(checked = follow, onCheckedChange = { follow = it }) {
                        Icon(Icons.Rounded.VerticalAlignBottom, if (follow) "Stop following" else "Follow new lines",
                            tint = if (follow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
            )
        },
    ) { padding ->
        LogsContent(
            ui = ui.copy(lines = shownSource), query = query, follow = follow,
            pendingLines = if (paused) (ui.lines.size - (frozen?.size ?: 0)).coerceAtLeast(0) else 0,
            onQuery = { query = it }, onSelect = vm::select, onResume = vm::resume,
            onUserScrolledUp = { follow = false },
            modifier = Modifier.padding(padding),
        )
    }
}

/** Stateless log view (also rendered by the screenshot tests). */
@Composable
fun LogsContent(
    ui: LogsUi, query: String, follow: Boolean, pendingLines: Int,
    onQuery: (String) -> Unit, onSelect: (String) -> Unit, onResume: () -> Unit, onUserScrolledUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shown = remember(ui.lines, query) { LogBuffer.filter(ui.lines, query) }
    val listState = rememberLazyListState()
    // Keyed on the newest line, not the count: the buffer stays at MAX_LINES once full (code review P1-9).
    LaunchedEffect(shown.lastOrNull()?.seq, follow) { if (follow && shown.isNotEmpty()) listState.scrollToItem(shown.lastIndex) }
    LaunchedEffect(listState) {
        var last = 0
        snapshotFlow { listState.isScrollInProgress to listState.firstVisibleItemIndex }.collect { (scrolling, idx) ->
            if (scrolling && idx < last && listState.canScrollForward) onUserScrolledUp()
            last = idx
        }
    }
    Column(modifier.fillMaxSize()) {
        if (ui.containers.size > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ui.containers.forEach { ctr -> FilterChip(selected = ctr.id == ui.selected, onClick = { onSelect(ctr.id) }, label = { Text(ctr.service, maxLines = 1) }) }
            }
        }
        OutlinedTextField(
            value = query, onValueChange = onQuery, singleLine = true,
            placeholder = { Text("Filter lines") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Rounded.Clear, "Clear filter") } },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            val statusText = when (ui.status) {
                LogStatus.CONNECTING -> "Connecting…"
                LogStatus.STREAMING -> if (query.isBlank()) "Live · ${ui.lines.size} lines" else "Live · ${shown.size} of ${ui.lines.size} lines"
                LogStatus.ENDED -> ui.error ?: "Stream ended"
            }
            Text(statusText, style = MaterialTheme.typography.labelMedium,
                color = if (ui.status == LogStatus.ENDED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f), maxLines = 2)
            if (pendingLines > 0) Text("Paused · $pendingLines new", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            if (ui.status == LogStatus.ENDED) TextButton(onClick = onResume) { Text("Resume") }
        }
        Box(
            Modifier.fillMaxSize().padding(12.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(14.dp)),
        ) {
            LazyColumn(state = listState, contentPadding = PaddingValues(12.dp), modifier = Modifier.fillMaxSize()) {
                items(shown, key = { it.seq }, contentType = { 0 }) { line ->
                    val level = remember(line.seq) { LogBuffer.level(line.text) }
                    val color = when (level) {
                        LogLevel.ERROR -> MaterialTheme.colorScheme.error
                        LogLevel.WARN -> app.truenascompanion.ui.theme.LocalStatusColors.current.of(app.truenascompanion.data.model.Health.WARNING)
                        LogLevel.OTHER -> MaterialTheme.colorScheme.onSurface
                    }
                    Row(Modifier.padding(vertical = 2.dp)) {
                        androidx.compose.foundation.layout.Box(
                            Modifier.padding(top = 3.dp, end = 8.dp).size(width = 2.dp, height = 12.dp)
                                .background(if (level == LogLevel.OTHER) MaterialTheme.colorScheme.outlineVariant else color, RoundedCornerShape(1.dp)),
                        )
                        Text(
                            highlight(line.text, query, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f)),
                            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = color,
                        )
                    }
                }
            }
        }
    }
}

private fun highlight(text: String, query: String, color: androidx.compose.ui.graphics.Color): AnnotatedString {
    if (query.isBlank()) return AnnotatedString(text)
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            val hit = text.indexOf(query, i, ignoreCase = true)
            if (hit < 0) { append(text.substring(i)); break }
            append(text.substring(i, hit))
            withStyle(SpanStyle(background = color)) { append(text.substring(hit, hit + query.length)) }
            i = hit + query.length
        }
    }
}
