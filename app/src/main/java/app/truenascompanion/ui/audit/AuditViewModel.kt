package app.truenascompanion.ui.audit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.truenascompanion.AppContainer
import app.truenascompanion.data.api.AuditApi
import app.truenascompanion.data.api.userMessage
import app.truenascompanion.data.model.AuditEntry
import app.truenascompanion.data.model.AuditFilter
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AuditUi(
    val filter: AuditFilter = AuditFilter(),
    val entries: List<AuditEntry> = emptyList(),
    val total: Int? = null,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
    val error: String? = null,
    val loginRequired: Boolean = false,
    val exporting: Boolean = false,
)

class AuditViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(AuditUi())
    val ui = _ui.asStateFlow()
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()
    /** CSV text ready to be written and shared by the screen (it owns the Context). */
    private val _exports = Channel<String>(Channel.BUFFERED)
    val exports = _exports.receiveAsFlow()
    private var job: Job? = null

    init {
        viewModelScope.launch { c.repository.reloadKey.collect { if (it != null) reload() } }
    }

    fun setFilter(f: AuditFilter) {
        if (f == _ui.value.filter) return
        _ui.update { it.copy(filter = f) }
        reload()
    }

    fun reload() {
        job?.cancel()
        val filter = _ui.value.filter
        _ui.update { it.copy(loading = true, error = null, loadingMore = false) }
        job = viewModelScope.launch {
            try {
                val (rows, total) = c.repository.call { api ->
                    val a = AuditApi(api)
                    a.query(filter, 0) to runCatching { a.count(filter) }.getOrNull()
                }
                _ui.update { it.copy(entries = rows, total = total, loading = false, endReached = rows.size < AuditApi.PAGE_SIZE) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                _ui.update { it.copy(loading = false, error = e.userMessage(), loginRequired = e is app.truenascompanion.data.api.TrueNasException.LoginRequired) }
            }
        }
    }

    fun loadMore() {
        val cur = _ui.value
        if (cur.loading || cur.loadingMore || cur.endReached || cur.error != null) return
        _ui.update { it.copy(loadingMore = true) }
        job = viewModelScope.launch {
            try {
                val rows = c.repository.call { AuditApi(it).query(cur.filter, cur.entries.size) }
                _ui.update { s ->
                    val seen = s.entries.mapNotNullTo(HashSet()) { it.auditId }
                    s.copy(entries = s.entries + rows.filter { it.auditId == null || it.auditId !in seen }, loadingMore = false, endReached = rows.size < AuditApi.PAGE_SIZE)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                _ui.update { it.copy(loadingMore = false) }
                _messages.trySend(e.userMessage())
            }
        }
    }

    /** Fetches up to [AuditApi.EXPORT_LIMIT] rows with the current filter and hands the CSV to the screen. */
    fun export() {
        if (_ui.value.exporting) return
        val filter = _ui.value.filter
        _ui.update { it.copy(exporting = true) }
        viewModelScope.launch {
            try {
                val rows = c.repository.call { api ->
                    val a = AuditApi(api)
                    val out = ArrayList<AuditEntry>()
                    while (out.size < AuditApi.EXPORT_LIMIT) {
                        val page = a.query(filter, out.size, EXPORT_PAGE)
                        out += page
                        if (page.size < EXPORT_PAGE) break
                    }
                    out.take(AuditApi.EXPORT_LIMIT)
                }
                if (rows.isEmpty()) _messages.trySend("Nothing to export") else _exports.trySend(AuditApi.toCsv(rows))
                if (rows.size >= AuditApi.EXPORT_LIMIT) _messages.trySend("Exported the newest ${AuditApi.EXPORT_LIMIT} events")
            } catch (e: Throwable) {
                _messages.trySend(e.userMessage())
            } finally {
                _ui.update { it.copy(exporting = false) }
            }
        }
    }

    companion object { const val EXPORT_PAGE = 500 }
}
