package app.truenascompanion.ui.notifications

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.MiscellaneousServices
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.truenascompanion.AppContainer
import app.truenascompanion.data.model.Health
import app.truenascompanion.notify.rules.AlertRule
import app.truenascompanion.notify.rules.BackupTarget
import app.truenascompanion.notify.rules.RuleKind
import app.truenascompanion.notify.rules.RuleSeverity
import app.truenascompanion.notify.rules.RuleState
import app.truenascompanion.ui.appViewModel
import app.truenascompanion.ui.components.ConfirmDialog
import app.truenascompanion.ui.components.ElevatedSection
import app.truenascompanion.ui.components.EmptyContent
import app.truenascompanion.ui.components.IconBadge
import app.truenascompanion.ui.components.InfoBanner
import app.truenascompanion.ui.components.ScreenScaffold
import app.truenascompanion.ui.components.StatusChip
import app.truenascompanion.ui.components.UiState
import app.truenascompanion.util.runCatchingCancellable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

/** What the rules page shows for the active server. */
data class RulesUi(
    val serverId: String,
    val serverName: String,
    val rules: List<AlertRule>,
    /** Rule ids whose condition is true right now (from the last check). */
    val firing: Set<String> = emptySet(),
    val notificationsOn: Boolean = true,
    val intervalMinutes: Int = 15,
)

/** Apps and services offered by the editor's pickers. */
data class RuleChoices(val apps: List<String> = emptyList(), val services: List<Pair<String, String>> = emptyList(), val loading: Boolean = false)

@OptIn(ExperimentalCoroutinesApi::class)
class AlertRulesViewModel(private val c: AppContainer) : ViewModel() {
    private val stateTick = MutableStateFlow(0)
    val ui = c.repository.activeServer.flatMapLatest { s ->
        if (s == null) flowOf<UiState<RulesUi>>(UiState.Error("Add a server first."))
        else combine(c.settings.alertRules(s.id), c.settings.notificationPrefs, stateTick) { rules, prefs, _ ->
            val st = runCatchingCancellable { c.settings.ruleState(s.id) }.getOrNull() ?: RuleState()
            UiState.Success(RulesUi(s.id, s.name, rules, st.firing.keys.map { it.substringBefore('|') }.toSet(), prefs.isEnabled(s.id), prefs.intervalMinutes)) as UiState<RulesUi>
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState.Loading)

    private val _choices = MutableStateFlow(RuleChoices())
    val choices = _choices.asStateFlow()

    private fun serverId() = (ui.value as? UiState.Success)?.data?.serverId

    fun reloadState() { stateTick.value++ }

    fun save(rule: AlertRule) = viewModelScope.launch {
        val id = serverId() ?: return@launch
        c.settings.updateAlertRules(id) { list -> if (list.any { it.id == rule.id }) list.map { if (it.id == rule.id) rule else it } else list + rule }
    }

    fun setEnabled(rule: AlertRule, on: Boolean) = save(rule.copy(enabled = on))

    fun delete(rule: AlertRule) = viewModelScope.launch {
        val id = serverId() ?: return@launch
        c.settings.updateAlertRules(id) { list -> list.filterNot { it.id == rule.id } }
        c.notifier.cancelRuleAll(id, rule.id)
    }

    fun addSuggested() = viewModelScope.launch {
        val id = serverId() ?: return@launch
        c.settings.updateAlertRules(id) { list ->
            val have = list.map { it.kind to it.target }.toSet()
            list + AlertRule.suggested().filter { (it.kind to it.target) !in have }
        }
    }

    /** Loads app and service names once for the pickers (read-only `app.query` / `service.query`). */
    fun loadChoices() {
        if (_choices.value.loading || (_choices.value.apps.isNotEmpty() || _choices.value.services.isNotEmpty())) return
        _choices.value = RuleChoices(loading = true)
        viewModelScope.launch {
            val apps = runCatchingCancellable { c.repository.call { it.apps() }.map { it.name }.sorted() }.getOrDefault(emptyList())
            val services = runCatchingCancellable { c.repository.call { it.services() }.map { it.service to it.displayName }.sortedBy { it.second } }.getOrDefault(emptyList())
            _choices.value = RuleChoices(apps, services, loading = false)
        }
    }
}

@Composable
fun AlertRulesScreen(onBack: () -> Unit) {
    val vm = appViewModel { AlertRulesViewModel(it) }
    val state by vm.ui.collectAsStateWithLifecycle()
    val choices by vm.choices.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<AlertRule?>(null) }
    var picking by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<AlertRule?>(null) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { vm.reloadState(); onPauseOrDispose { } }
    AlertRulesContent(
        state = state, onBack = onBack, onRetry = { vm.reloadState() },
        onAdd = { picking = true }, onSuggested = vm::addSuggested,
        onEdit = { editing = it }, onToggle = vm::setEnabled, onDelete = { deleting = it },
    )
    if (picking) RuleKindPicker(onDismiss = { picking = false }) { kind -> picking = false; editing = AlertRule(kind = kind, enabled = true, target = if (kind == RuleKind.BACKUP_FAILED || kind == RuleKind.BACKUP_STALE) BackupTarget.ANY else null) }
    editing?.let { r ->
        LaunchedEffect(r.kind) { if (r.kind.needsTarget) vm.loadChoices() }
        RuleEditorDialog(r, choices, onDismiss = { editing = null }) { vm.save(it); editing = null }
    }
    deleting?.let { r ->
        ConfirmDialog(title = "Delete rule?", text = r.summary(), confirmLabel = "Delete", icon = Icons.Rounded.Delete,
            onConfirm = { vm.delete(r); deleting = null }, onDismiss = { deleting = null })
    }
}

/** Stateless page (also rendered by the screenshot tests with example data). */
@Composable
fun AlertRulesContent(
    state: UiState<RulesUi>,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onAdd: () -> Unit,
    onSuggested: () -> Unit,
    onEdit: (AlertRule) -> Unit,
    onToggle: (AlertRule, Boolean) -> Unit,
    onDelete: (AlertRule) -> Unit,
) {
    ScreenScaffold(
        title = "Alert rules", state = state, onRetry = onRetry, onBack = onBack,
        floatingActionButton = {
            if ((state as? UiState.Success)?.data?.rules?.isNotEmpty() == true)
                ExtendedFloatingActionButton(onClick = onAdd, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("New rule") })
        },
        isEmpty = { false },
    ) { ui ->
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(
                    "Your own rules for ${ui.serverName}, checked on this phone during the regular background check (every ${intervalLabel(ui.intervalMinutes)}, " +
                        "no extra wake-ups). You get one notification when a rule starts and a quiet \"Recovered\" one when it ends.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!ui.notificationsOn) item {
                InfoBanner("Alerts on this phone are off for ${ui.serverName}, so rules aren't checked. Turn them on in System › Phone alerts.")
            }
            if (ui.rules.isEmpty()) {
                item {
                    ElevatedSection {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconBadge(Icons.Rounded.AutoAwesome, size = 36.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("No rules yet", style = MaterialTheme.typography.titleMedium)
                                Text("Start with a few useful ones and adjust them, or make your own.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.padding(top = 12.dp))
                        AlertRule.suggested().forEach { Text("• " + it.summary(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Spacer(Modifier.padding(top = 12.dp))
                        FilledTonalButton(onClick = onSuggested, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Add suggested rules")
                        }
                        TextButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("Make my own rule") }
                    }
                }
            } else {
                items(ui.rules, key = { it.id }) { r -> RuleCard(r, r.id in ui.firing, onEdit, onToggle, onDelete) }
                if (ui.rules.none { it.kind == RuleKind.UNREACHABLE } || AlertRule.suggested().any { s -> ui.rules.none { it.kind == s.kind } }) item {
                    TextButton(onClick = onSuggested, modifier = Modifier.fillMaxWidth()) { Text("Add the missing suggested rules") }
                }
            }
        }
    }
}

fun RuleKind.icon(): ImageVector = when (this) {
    RuleKind.POOL_USAGE -> Icons.Rounded.Storage
    RuleKind.DISK_TEMP -> Icons.Rounded.Thermostat
    RuleKind.APP_NOT_RUNNING -> Icons.Rounded.Apps
    RuleKind.SERVICE_STOPPED -> Icons.Rounded.MiscellaneousServices
    RuleKind.BACKUP_FAILED, RuleKind.BACKUP_STALE -> Icons.Rounded.Backup
    RuleKind.SCRUB_AGE -> Icons.Rounded.CleaningServices
    RuleKind.CPU_HIGH -> Icons.Rounded.Speed
    RuleKind.RAM_HIGH -> Icons.Rounded.Memory
    RuleKind.CERT_EXPIRY -> Icons.Rounded.VerifiedUser
    RuleKind.UNREACHABLE -> Icons.Rounded.CloudOff
}

private fun RuleSeverity.health() = when (this) { RuleSeverity.CRITICAL -> Health.CRITICAL; RuleSeverity.WARNING -> Health.WARNING; RuleSeverity.INFO -> Health.INFO }

fun cooldownLabel(minutes: Int): String = when {
    minutes == 0 -> "No cooldown"
    minutes % (24 * 60) == 0 -> "${minutes / (24 * 60)} day" + if (minutes > 24 * 60) "s" else ""
    minutes % 60 == 0 -> "${minutes / 60} h"
    else -> "$minutes min"
}

@Composable
private fun RuleCard(r: AlertRule, firing: Boolean, onEdit: (AlertRule) -> Unit, onToggle: (AlertRule, Boolean) -> Unit, onDelete: (AlertRule) -> Unit) {
    ElevatedSection(onClick = { onEdit(r) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(r.kind.icon(), size = 36.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(r.kind.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(r.summary(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = r.enabled, onCheckedChange = { onToggle(r, it) },
                modifier = Modifier.semantics { contentDescription = "${r.kind.title} rule"; stateDescription = if (r.enabled) "On" else "Off" },
            )
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip(r.severity.health(), r.severity.label)
            if (r.cooldownMinutes > 0) app.truenascompanion.ui.components.Tag("Cooldown ${cooldownLabel(r.cooldownMinutes)}")
            if (firing && r.enabled) StatusChip(Health.CRITICAL, "Active now", showIcon = false)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { onDelete(r) }) { Icon(Icons.Rounded.Delete, "Delete rule", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun RuleKindPicker(onDismiss: () -> Unit, onPick: (RuleKind) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Rule, null) },
        title = { Text("New rule") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                RuleKind.entries.forEach { k ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onPick(k) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(k.icon(), null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text(k.title, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private val COOLDOWNS = listOf(0, 15, 60, 6 * 60, 24 * 60)

private fun num(v: Double) = if (v % 1.0 == 0.0) v.toLong().toString() else String.format(Locale.US, "%.1f", v)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleEditorDialog(initial: AlertRule, choices: RuleChoices, onDismiss: () -> Unit, onSave: (AlertRule) -> Unit) {
    var rule by remember(initial.id) { mutableStateOf(initial) }
    var thresholdText by remember(initial.id) { mutableStateOf(num(initial.threshold)) }
    var minutesText by remember(initial.id) { mutableStateOf(initial.minutes.toString()) }
    val candidate = rule.copy(
        threshold = thresholdText.replace(',', '.').toDoubleOrNull() ?: Double.NaN,
        minutes = minutesText.toIntOrNull() ?: -1,
    ).let { if (it.kind.unit == null || it.kind.unit.isEmpty()) it.copy(threshold = it.kind.defaultThreshold) else it }
        .let { if (!it.kind.usesMinutes) it.copy(minutes = it.kind.defaultMinutes) else it }
    val problem = AlertRule.problem(candidate)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(rule.kind.icon(), null) },
        title = { Text(rule.kind.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (problem == null) candidate.summary() else rule.kind.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val unit = rule.kind.unit
                if (!unit.isNullOrEmpty()) OutlinedTextField(
                    value = thresholdText, onValueChange = { thresholdText = it.take(8) },
                    label = { Text(thresholdLabel(rule.kind)) }, suffix = { Text(unit) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
                )
                if (rule.kind.usesMinutes) OutlinedTextField(
                    value = minutesText, onValueChange = { minutesText = it.filter(Char::isDigit).take(4) },
                    label = { Text(if (rule.kind == RuleKind.UNREACHABLE) "For at least" else "Over the last") }, suffix = { Text("min") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text(minutesHint(rule.kind)) },
                )
                when (rule.kind) {
                    RuleKind.APP_NOT_RUNNING -> Picker("App", rule.target, choices.apps.map { it to it }, choices.loading) { rule = rule.copy(target = it) }
                    RuleKind.SERVICE_STOPPED -> Picker("Service", rule.target, choices.services, choices.loading) { rule = rule.copy(target = it) }
                    RuleKind.BACKUP_FAILED, RuleKind.BACKUP_STALE -> {
                        val opts = listOf(BackupTarget.ANY to "Both", BackupTarget.REPLICATION to "Replication", BackupTarget.CLOUD_SYNC to "Cloud sync")
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            opts.forEachIndexed { i, (v, label) ->
                                SegmentedButton(selected = (rule.target ?: BackupTarget.ANY) == v, onClick = { rule = rule.copy(target = v) },
                                    shape = SegmentedButtonDefaults.itemShape(i, opts.size), icon = {}) { Text(label, maxLines = 1, softWrap = false) }
                            }
                        }
                    }
                    else -> Unit
                }
                Text("Severity", style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    RuleSeverity.entries.forEachIndexed { i, s ->
                        SegmentedButton(selected = rule.severity == s, onClick = { rule = rule.copy(severity = s) },
                            shape = SegmentedButtonDefaults.itemShape(i, RuleSeverity.entries.size), icon = {}) { Text(s.label, maxLines = 1, softWrap = false) }
                    }
                }
                Picker("Cooldown after it recovers", rule.cooldownMinutes.toString(), COOLDOWNS.map { it.toString() to cooldownLabel(it) }, false) {
                    rule = rule.copy(cooldownMinutes = it.toInt())
                }
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(candidate) }, enabled = problem == null) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun thresholdLabel(k: RuleKind) = when (k) {
    RuleKind.POOL_USAGE -> "More than"
    RuleKind.DISK_TEMP -> "Above"
    RuleKind.BACKUP_STALE -> "No success for"
    RuleKind.SCRUB_AGE -> "Last scrub older than"
    RuleKind.CPU_HIGH, RuleKind.RAM_HIGH -> "Average above"
    RuleKind.CERT_EXPIRY -> "Expires within"
    else -> "Value"
}

private fun minutesHint(k: RuleKind) = when (k) {
    RuleKind.DISK_TEMP -> "Uses the NAS's temperature history: every reading in this window must be above the limit."
    RuleKind.CPU_HIGH, RuleKind.RAM_HIGH -> "Uses the NAS's reporting history, so it works between checks."
    RuleKind.UNREACHABLE -> "Counted from the first failed check, so it's at least one check interval."
    else -> ""
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Picker(label: String, selected: String?, options: List<Pair<String, String>>, loading: Boolean, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val shown = options.firstOrNull { it.first == selected }?.second ?: selected ?: if (loading) "Loading…" else "Choose"
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it && options.isNotEmpty() }) {
        OutlinedTextField(
            value = shown, onValueChange = {}, readOnly = true, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (v, l) -> DropdownMenuItem(text = { Text(l) }, onClick = { onPick(v); open = false }) }
        }
    }
}
