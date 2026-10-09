package app.truenascompanion.ui.protection

import app.truenascompanion.util.runCatchingCancellable

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.truenascompanion.AppContainer
import app.truenascompanion.data.api.ProtectionApi
import app.truenascompanion.data.api.TrueNasApi
import app.truenascompanion.data.api.userMessage
import app.truenascompanion.data.model.AlertItem
import app.truenascompanion.data.model.BackupTask
import app.truenascompanion.data.model.CronSchedule
import app.truenascompanion.data.model.Disk
import app.truenascompanion.data.model.JobState
import app.truenascompanion.data.model.Pool
import app.truenascompanion.data.model.ScrubTask
import app.truenascompanion.data.model.SmartSchedule
import app.truenascompanion.data.model.SmartTestType
import app.truenascompanion.data.model.SnapshotTask
import app.truenascompanion.data.protection.ProtectionInputs
import app.truenascompanion.data.protection.ProtectionSummarizer
import app.truenascompanion.data.protection.ProtectionSummary
import app.truenascompanion.ui.components.UiState
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class ProtectionData(
    val pools: List<Pool>,
    val disks: List<Disk>,
    val snapshotTasks: List<SnapshotTask>?,
    val scrubTasks: List<ScrubTask>?,
    val smart: List<SmartSchedule>?,
    val backups: List<BackupTask>?,
    val alerts: List<AlertItem>?,
    val now: Long = System.currentTimeMillis(),
) {
    val summary: ProtectionSummary get() = ProtectionSummarizer.summarize(ProtectionInputs(snapshotTasks, pools, scrubTasks, smart, alerts, backups), now)
    val smartAlerts: List<AlertItem> get() = alerts.orEmpty().filter { !it.dismissed && it.klass?.startsWith("SMART", true) == true }
    val visibleSmart: List<SmartSchedule>? get() = smart?.filter { it.description != ProtectionApi.ONE_OFF_DESCRIPTION }
    /** Something is in progress that is worth polling while the screen is visible. */
    val anythingRunning: Boolean get() = pools.any { it.scrubRunning } || backups.orEmpty().any { it.running }
}

/** Loads everything the Protection overview needs; each part fails independently (e.g. missing permissions). */
suspend fun loadProtection(c: AppContainer): ProtectionData = coroutineScope {
    suspend fun <T> part(block: suspend (ProtectionApi, TrueNasApi) -> T): T? =
        runCatchingCancellable { c.repository.call { api -> block(ProtectionApi(api), api) } }.getOrNull()
    val pools = async { c.repository.call { it.pools() } }
    val disks = async { part { _, api -> api.disks() } }
    val snaps = async { part { p, _ -> p.snapshotTasks() } }
    val scrubs = async { part { p, _ -> p.scrubTasks() } }
    val smart = async { part { p, _ -> p.smartSchedules() } }
    val backups = async { part { p, _ -> p.backupTasks() } }
    val alerts = async { part { _, api -> api.alerts() } }
    ProtectionData(pools.await(), disks.await().orEmpty(), snaps.await(), scrubs.await(), smart.await(), backups.await(), alerts.await())
}

class ProtectionViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow<UiState<ProtectionData>>(UiState.Loading)
    val state: StateFlow<UiState<ProtectionData>> = _state.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
    /** Keys of actions in flight (e.g. "scrub:tank"), to disable their buttons. */
    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()
    private var cleaned = false

    init {
        viewModelScope.launch { c.repository.reloadKey.collect { if (it != null) { _state.value = UiState.Loading; load() } } }
    }

    fun refresh() = viewModelScope.launch { _refreshing.value = true; load(); _refreshing.value = false }
    /** Reload without the pull-to-refresh indicator. */
    fun reload() = viewModelScope.launch { load() }

    private suspend fun load() {
        try {
            val data = loadProtection(c)
            _state.value = UiState.Success(data)
            if (!cleaned && data.smart != null) {
                cleaned = true
                runCatchingCancellable { c.repository.call { ProtectionApi(it).cleanupOneOffSmartJobs(data.smart) } }
            }
        } catch (e: Throwable) {
            if (_state.value !is UiState.Success) _state.value = UiState.Error(e.userMessage(), e)
        }
    }

    /** Light refresh while a scrub/backup runs (pools + backup tasks only). Called every few seconds while visible. */
    suspend fun poll() {
        val cur = (_state.value as? UiState.Success)?.data ?: return
        val pools = runCatchingCancellable { c.repository.call { it.pools() } }.getOrNull() ?: return
        val backups = if (cur.backups.orEmpty().any { it.running }) runCatchingCancellable { c.repository.call { ProtectionApi(it).backupTasks() } }.getOrNull() else null
        _state.value = UiState.Success(cur.copy(pools = pools, backups = backups ?: cur.backups, now = System.currentTimeMillis()))
    }

    private fun action(key: String, success: String?, reload: Boolean = true, block: suspend (ProtectionApi) -> Unit) {
        if (key in _busy.value) return
        _busy.update { it + key }
        viewModelScope.launch {
            try {
                c.repository.call { block(ProtectionApi(it)) }
                success?.let { _messages.send(it) }
                if (reload) load()
            } catch (e: Throwable) {
                _messages.send(e.userMessage())
            } finally {
                _busy.update { it - key }
            }
        }
    }

    // --- Scrubs ---
    fun startScrub(pool: String) = action("scrub:$pool", "Scrub started on $pool") { it.startScrub(pool); delay(1500) }
    fun stopScrub(pool: String) = action("scrub:$pool", "Scrub stopped on $pool") { it.stopScrub(pool) }
    fun pauseScrub(pool: String) = action("scrub:$pool", "Scrub paused on $pool") { it.pauseScrub(pool) }
    fun saveScrubSchedule(pool: Pool, existing: ScrubTask?, schedule: CronSchedule, threshold: Int, enabled: Boolean) =
        action("scrubtask:${pool.name}", "Scrub schedule saved") {
            if (existing != null) it.updateScrubTask(existing.id, threshold, schedule, enabled) else it.createScrubTask(pool.id, threshold, schedule)
        }

    // --- Snapshot tasks ---
    fun setSnapshotTaskEnabled(t: SnapshotTask, on: Boolean) = action("snaptask:${t.id}", if (on) "Task enabled" else "Task disabled") { it.setSnapshotTaskEnabled(t.id, on) }
    fun runSnapshotTask(t: SnapshotTask) = action("snaptask:${t.id}", "Snapshot task started for ${t.dataset}") { it.runSnapshotTask(t.id); delay(1500) }
    fun deleteSnapshotTask(t: SnapshotTask) = action("snaptask:${t.id}", "Snapshot task deleted") { it.deleteSnapshotTask(t.id) }

    // --- SMART ---
    fun setSmartEnabled(s: SmartSchedule, on: Boolean) = action("smart:${s.cronId}", if (on) "Schedule enabled" else "Schedule disabled") { it.setSmartScheduleEnabled(s.cronId, on) }
    fun runSmartSchedule(s: SmartSchedule) = action("smart:${s.cronId}", "${s.type.label} SMART test started") { it.runSmartSchedule(s.cronId) }
    fun deleteSmartSchedule(s: SmartSchedule) = action("smart:${s.cronId}", "Schedule deleted") { it.deleteSmartSchedule(s.cronId) }
    fun saveSmartSchedule(existing: SmartSchedule?, type: SmartTestType, disks: List<String>, schedule: CronSchedule, enabled: Boolean) =
        action("smart:${existing?.cronId ?: "new"}", "SMART schedule saved") {
            if (existing == null) it.createSmartSchedule(type, disks, schedule, "")
            else it.updateSmartSchedule(existing.copy(type = type, disks = disks, schedule = schedule, enabled = enabled))
        }
    fun runSmartNow(type: SmartTestType, disks: List<String>) = action("smart:now", null, reload = false) { p ->
        p.runSmartTestNow(type, disks) { jobId -> awaitJob(p, jobId) }
        val what = if ("*" in disks) "all disks" else "${disks.size} disk" + if (disks.size == 1) "" else "s"
        _messages.send("${type.label} SMART test started on $what. " + if (type == SmartTestType.LONG) "It can take several hours." else "It takes a few minutes.")
    }

    private suspend fun awaitJob(p: ProtectionApi, id: Long) {
        withTimeoutOrNull(60_000) {
            while (true) {
                val j = p.job(id) ?: return@withTimeoutOrNull
                when (j.state) {
                    JobState.SUCCESS -> return@withTimeoutOrNull
                    JobState.FAILED, JobState.ABORTED -> throw app.truenascompanion.data.api.TrueNasException.JobFailed(j.error ?: "The test couldn't be started")
                    else -> delay(1000)
                }
            }
        }
    }

    // --- Backups ---
    fun setBackupEnabled(t: BackupTask, on: Boolean) = action("backup:${t.kind}:${t.id}", if (on) "${t.name} enabled" else "${t.name} disabled") { it.setBackupEnabled(t, on) }
    fun runBackup(t: BackupTask) = action("backup:${t.kind}:${t.id}", "${t.name} started") { it.runBackup(t); delay(1500) }
}
