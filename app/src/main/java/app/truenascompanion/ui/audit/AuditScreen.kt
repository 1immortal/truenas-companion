package app.truenascompanion.ui.audit

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.truenascompanion.data.api.AuditApi
import app.truenascompanion.data.model.AuditEntry
import app.truenascompanion.data.model.AuditEvents
import app.truenascompanion.data.model.AuditFilter
import app.truenascompanion.data.model.AuditQuick
import app.truenascompanion.data.model.AuditService
import app.truenascompanion.data.model.AuditTimeRange
import app.truenascompanion.data.model.Health
import app.truenascompanion.ui.appViewModel
import app.truenascompanion.ui.components.*
import app.truenascompanion.ui.components.Scaffold
import app.truenascompanion.ui.components.TopAppBar
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Services offered in the picker — the same three as the web UI's System › Audit. */
val AUDIT_SERVICES = listOf(AuditService.MIDDLEWARE, AuditService.SMB, AuditService.SUDO)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuditScreen(onBack: () -> Unit) {
    val vm = appViewModel { AuditViewModel(it) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) {
        vm.exports.collect { csv ->
            val ok = runCatching { shareCsv(context, csv) }.isSuccess
            if (!ok) snackbar.showSnackbar("Couldn't share the export")
        }
    }
    var detail by remember { mutableStateOf<AuditEntry?>(null) }
    var showFilters by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Audit log") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    if (ui.exporting) CircularProgressIndicator(Modifier.size(22.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                    else IconButton(onClick = { vm.export() }, enabled = ui.entries.isNotEmpty()) { Icon(Icons.Rounded.IosShare, "Export CSV") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(isRefreshing = ui.loading && ui.entries.isNotEmpty(), onRefresh = { vm.reload() }, modifier = Modifier.padding(padding).fillMaxSize()) {
            AuditContent(ui, onFilter = vm::setFilter, onOpen = { detail = it }, onMore = vm::loadMore, onRetry = vm::reload, onShowFilters = { showFilters = true })
        }
    }
    if (showFilters) AuditFilterSheet(ui.filter, onApply = { vm.setFilter(it); showFilters = false }, onDismiss = { showFilters = false })
    detail?.let { AuditDetailSheet(it) { detail = null } }
}

private fun shareCsv(context: Context, csv: String) {
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 3_600_000L }?.forEach { it.delete() }
    val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault()).format(Instant.now())
    val file = File(dir, "truenas-audit-$stamp.csv").apply { writeText(csv) }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
    val send = Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, file.name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Export audit log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private val timeFmt = DateTimeFormatter.ofPattern("MMM d, HH:mm:ss").withZone(ZoneId.systemDefault())
private val fullFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss zzz").withZone(ZoneId.systemDefault())

/** Stateless content (rendered by screenshot tests with example data). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuditContent(
    ui: AuditUi,
    onFilter: (AuditFilter) -> Unit,
    onOpen: (AuditEntry) -> Unit,
    onMore: () -> Unit,
    onRetry: () -> Unit,
    onShowFilters: () -> Unit,
) {
    val f = ui.filter
    var search by rememberSaveable(f.service) { mutableStateOf(f.search) }
    val list = rememberLazyListState()
    val nearEnd by remember { derivedStateOf { val last = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0; last >= list.layoutInfo.totalItemsCount - 4 } }
    LaunchedEffect(nearEnd, ui.entries.size) { if (nearEnd && ui.entries.isNotEmpty()) onMore() }

    LazyColumn(state = list, contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                AUDIT_SERVICES.forEachIndexed { i, s ->
                    SegmentedButton(
                        selected = f.service == s, shape = SegmentedButtonDefaults.itemShape(i, AUDIT_SERVICES.size),
                        onClick = { onFilter(f.copy(service = s, event = f.event?.takeIf { it in AuditEvents.BY_SERVICE[s].orEmpty() }, quick = f.quick.filter { it.appliesTo(s) }.toSet())) },
                    ) { Text(s.label) }
                }
            }
        }
        item {
            OutlinedTextField(
                value = search, onValueChange = { search = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Rounded.Search, null) }, placeholder = { Text("Search event or username") },
                trailingIcon = {
                    if (search.isNotEmpty()) IconButton(onClick = { search = ""; onFilter(f.copy(search = "")) }) { Icon(Icons.Rounded.Close, "Clear") }
                },
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onFilter(f.copy(search = search)) }),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                shape = MaterialTheme.shapes.extraLarge,
            )
        }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = f.activeCount > f.quick.size, onClick = onShowFilters,
                    label = { Text(if (f.activeCount > f.quick.size) "Filters · ${f.activeCount - f.quick.size}" else "Filters") },
                    leadingIcon = { Icon(Icons.Rounded.Tune, null, Modifier.size(18.dp)) },
                )
                AuditQuick.entries.filter { it.appliesTo(f.service) }.forEach { q ->
                    FilterChip(
                        selected = q in f.quick, onClick = { onFilter(f.copy(quick = if (q in f.quick) f.quick - q else f.quick + q)) },
                        label = { Text(q.label) },
                        leadingIcon = if (q in f.quick) ({ Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) }) else null,
                    )
                }
            }
        }
        item {
            val count = ui.total?.let { "%,d event%s".format(it, if (it == 1) "" else "s") } ?: "${ui.entries.size} events"
            Text("$count · ${f.time.label.lowercase()}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
        }
        when {
            ui.loading && ui.entries.isEmpty() -> items(6) { SkeletonCard(height = 72.dp) }
            ui.error != null && ui.entries.isEmpty() -> item { ErrorState(ui.error, onRetry, loginRequired = ui.loginRequired) }
            ui.entries.isEmpty() -> item {
                EmptyState(Icons.Rounded.ManageSearch, "No events", "Nothing matches these filters. Try a longer time range.")
            }
            else -> {
                itemsIndexed(ui.entries, key = { i, e -> "$i:${e.auditId}" }) { _, e -> AuditRow(e) { onOpen(e) } }
                if (ui.loadingMore) item { Box(Modifier.fillMaxWidth().padding(12.dp), Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) } }
                else if (!ui.endReached) item { TextButton(onClick = onMore, modifier = Modifier.fillMaxWidth()) { Text("Load more") } }
                else item { Text("End of results", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
            }
        }
    }
}

private fun eventIcon(e: String) = when (e) {
    "AUTHENTICATION", "LOGIN", "CONNECT" -> Icons.Rounded.Login
    "LOGOUT", "DISCONNECT" -> Icons.Rounded.Logout
    "METHOD_CALL" -> Icons.Rounded.Terminal
    "REBOOT" -> Icons.Rounded.RestartAlt
    "ACCEPT", "REJECT", "ESCALATION", "PRIVILEGED" -> Icons.Rounded.AdminPanelSettings
    "READ", "OFFLOAD_READ" -> Icons.Rounded.FileOpen
    "WRITE", "OFFLOAD_WRITE", "CREATE" -> Icons.Rounded.EditNote
    "UNLINK" -> Icons.Rounded.DeleteOutline
    "RENAME" -> Icons.Rounded.DriveFileRenameOutline
    "SET_ACL", "SET_ATTR", "SET_QUOTA" -> Icons.Rounded.Security
    else -> Icons.Rounded.EventNote
}

@Composable
private fun AuditRow(e: AuditEntry, onClick: () -> Unit) {
    ElevatedSection(onClick = onClick, contentPadding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(eventIcon(e.event), tint = if (e.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, size = 36.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(AuditEvents.label(e.event), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!e.success) { Spacer(Modifier.width(6.dp)); StatusChip(Health.CRITICAL, "Failed", showIcon = false) }
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(timeFmt.format(Instant.ofEpochSecond(e.timestamp)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    listOf(e.username.ifBlank { "—" }, e.address).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (e.summary.isNotBlank()) Text(e.summary, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AuditFilterSheet(filter: AuditFilter, onApply: (AuditFilter) -> Unit, onDismiss: () -> Unit) {
    var event by remember { mutableStateOf(filter.event) }
    var username by remember { mutableStateOf(filter.username) }
    var address by remember { mutableStateOf(filter.address) }
    var time by remember { mutableStateOf(filter.time) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Filter ${filter.service.label} events", style = MaterialTheme.typography.titleLarge)
            Text("Time range", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AuditTimeRange.entries.forEach { t -> FilterChip(selected = time == t, onClick = { time = t }, label = { Text(t.label) }) }
            }
            Text("Event", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = event == null, onClick = { event = null }, label = { Text("Any") })
                AuditEvents.BY_SERVICE[filter.service].orEmpty().forEach { e -> FilterChip(selected = event == e, onClick = { event = e }, label = { Text(AuditEvents.label(e)) }) }
            }
            OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Partial match; * is a wildcard") })
            OutlinedTextField(address, { address = it }, label = { Text("Address") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("e.g. 203.0.113.7") })
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { onApply(filter.copy(event = null, username = "", address = "", time = AuditTimeRange.DAY)) }, modifier = Modifier.weight(1f)) { Text("Reset") }
                Button(onClick = { onApply(filter.copy(event = event, username = username.trim(), address = address.trim(), time = time)) }, modifier = Modifier.weight(1f)) { Text("Apply") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuditDetailSheet(e: AuditEntry, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val json = remember(e) { AuditApi.prettyJson(e.raw) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        AuditDetailContent(e, json, onCopy = { clipboard.setText(AnnotatedString(json)) })
    }
}

@Composable
fun AuditDetailContent(e: AuditEntry, json: String, onCopy: () -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(eventIcon(e.event), tint = if (e.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(AuditEvents.label(e.event), style = MaterialTheme.typography.titleLarge)
                Text(fullFmt.format(Instant.ofEpochSecond(e.timestamp)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatusChip(if (e.success) Health.HEALTHY else Health.CRITICAL, if (e.success) "Success" else "Failed")
        }
        LabeledValue("Service", e.service)
        LabeledValue("Username", e.username.ifBlank { "—" })
        LabeledValue("Address", e.address.ifBlank { "—" })
        if (e.summary.isNotBlank()) LabeledValue("Summary", e.summary)
        e.session?.let { LabeledValue("Session", it) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Full record", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onCopy) { Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Copy JSON") }
        }
        SelectionContainer {
            Text(
                json, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .horizontalScroll(rememberScrollState()).padding(12.dp),
            )
        }
    }
}
