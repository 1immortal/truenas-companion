package app.truenascompanion.ui.apps

import app.truenascompanion.ui.components.StateContent

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import app.truenascompanion.ui.components.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import app.truenascompanion.ui.components.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.truenascompanion.AppContainer
import app.truenascompanion.data.api.userMessage
import app.truenascompanion.data.model.CatalogApp
import app.truenascompanion.data.model.CatalogAppDetails
import app.truenascompanion.ui.appViewModel
import app.truenascompanion.ui.components.AppIcon
import app.truenascompanion.ui.components.ElevatedSection
import app.truenascompanion.ui.components.EmptyState
import app.truenascompanion.ui.components.GlowButton
import app.truenascompanion.ui.components.ScrollableErrorState
import app.truenascompanion.ui.components.SkeletonList
import app.truenascompanion.ui.components.UiState
import app.truenascompanion.ui.components.isLoginRequired
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CatalogUi(
    val apps: UiState<List<CatalogApp>> = UiState.Loading,
    val query: String = "",
    val category: String? = null,
    val refreshing: Boolean = false,
) {
    val categories: List<String> get() = (apps as? UiState.Success)?.data?.flatMap { it.categories }
        ?.groupingBy { it }?.eachCount()?.entries?.sortedByDescending { it.value }?.map { it.key } ?: emptyList()

    val filtered: List<CatalogApp> get() = (apps as? UiState.Success)?.data?.let { filterCatalog(it, query, category) } ?: emptyList()
}

/** Search matches name/title/description; results sorted by popularity (then name). Pure, unit tested. */
fun filterCatalog(apps: List<CatalogApp>, query: String, category: String?): List<CatalogApp> {
    val q = query.trim().lowercase()
    return apps.asSequence()
        .filter { category == null || category in it.categories }
        .filter { q.isEmpty() || it.name.contains(q) || it.title.lowercase().contains(q) || it.description.lowercase().contains(q) }
        .sortedWith(compareBy<CatalogApp>({ q.isNotEmpty() && !it.title.lowercase().startsWith(q) && !it.name.startsWith(q) }, { it.popularity ?: Int.MAX_VALUE }, { it.title.lowercase() }))
        .distinctBy { it.name to it.train }
        .toList()
}

class CatalogViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(CatalogUi())
    val ui: StateFlow<CatalogUi> = _ui.asStateFlow()

    init { load(false) }

    fun load(force: Boolean) = viewModelScope.launch {
        _ui.update { it.copy(refreshing = force) }
        _ui.update {
            try { it.copy(apps = UiState.Success(c.repository.catalogApps(force)), refreshing = false) }
            catch (e: Throwable) { it.copy(apps = UiState.Error(e.userMessage(), e), refreshing = false) }
        }
    }

    fun query(q: String) = _ui.update { it.copy(query = q) }
    fun category(cat: String?) = _ui.update { it.copy(category = if (it.category == cat) null else cat) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(onBack: () -> Unit, onOpen: (CatalogApp) -> Unit) {
    val vm = appViewModel { CatalogViewModel(it) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Discover apps") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        PullToRefreshBox(isRefreshing = ui.refreshing, onRefresh = { vm.load(true) }, modifier = Modifier.padding(padding).fillMaxSize()) {
            CatalogContent(ui, onQuery = vm::query, onCategory = vm::category, onOpen = onOpen, onRetry = { vm.load(true) })
        }
    }
}

/** Stateless catalog list (also rendered by the screenshot tests). */
@Composable
fun CatalogContent(ui: CatalogUi, onQuery: (String) -> Unit, onCategory: (String?) -> Unit, onOpen: (CatalogApp) -> Unit, onRetry: () -> Unit) {
    StateContent(ui.apps, onRetry = onRetry, skeletonCount = 7, skeletonHeight = 76.dp) { data ->
        val list = ui.filtered
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
            item(key = "search") {
                OutlinedTextField(
                    value = ui.query, onValueChange = onQuery, singleLine = true,
                    placeholder = { Text("Search ${data.size} apps") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = if (ui.query.isNotEmpty()) ({ IconButton(onClick = { onQuery("") }) { Icon(Icons.Rounded.Close, "Clear") } }) else null,
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
            item(key = "categories") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = ui.category == null, onClick = { onCategory(null) }, label = { Text("All") })
                    ui.categories.forEach { cat ->
                        FilterChip(selected = ui.category == cat, onClick = { onCategory(cat) }, label = { Text(cat.prettyCategory(), maxLines = 1) })
                    }
                }
            }
            if (list.isEmpty()) item { EmptyState(Icons.Rounded.Storefront, "No matching apps", "Try another search or category.") }
            items(list, key = { it.train + "/" + it.name }) { app -> CatalogRow(app) { onOpen(app) } }
        }

    }
}

@Composable
private fun CatalogRow(app: CatalogApp, onClick: () -> Unit) {
    ElevatedSection(onClick = onClick, contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(app.iconUrl, app.title, 48.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(app.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (app.installed) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Rounded.CheckCircle, "Installed", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    }
                }
                Text(app.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val meta = listOfNotNull(app.categories.firstOrNull()?.prettyCategory(), app.train.takeIf { it != "stable" }, app.latestAppVersion).joinToString(" · ")
                if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

internal fun String.prettyCategory() = replace('-', ' ').replace('_', ' ').replaceFirstChar { it.uppercase() }

// --- detail ---

class CatalogDetailViewModel(private val c: AppContainer, private val name: String, private val train: String) : ViewModel() {
    private val _state = MutableStateFlow<UiState<CatalogAppDetails>>(UiState.Loading)
    val state: StateFlow<UiState<CatalogAppDetails>> = _state.asStateFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        _state.value = UiState.Loading
        _state.value = try { UiState.Success(c.repository.call { it.catalogAppDetails(name, train) }) } catch (e: Throwable) { UiState.Error(e.userMessage(), e) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogDetailScreen(name: String, train: String, onBack: () -> Unit, onInstall: (String, String) -> Unit) {
    val vm = appViewModel(key = "catalog-$train-$name") { CatalogDetailViewModel(it, name, train) }
    val state by vm.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text((state as? UiState.Success)?.data?.app?.title ?: name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            StateContent(state, onRetry = { vm.load() }, skeletonCount = 3, skeletonHeight = 140.dp) { data ->
                    CatalogDetailContent(data) { onInstall(name, train) }
            }
        }
    }
}

/** Stateless app detail page (also rendered by the screenshot tests). */
@Composable
fun CatalogDetailContent(d: CatalogAppDetails, onInstall: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxSize()) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(d.app.iconUrl, d.app.title, 64.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(d.app.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(d.appVersion?.let { "Version $it" }, "Chart ${d.version}", d.app.train).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            GlowButton(onClick = onInstall, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(Icons.Rounded.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(if (d.app.installed) "Install another copy" else "Install", maxLines = 1)
            }
        }
        item { Text(d.app.description, style = MaterialTheme.typography.bodyLarge) }
        if (d.app.categories.isNotEmpty()) item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                d.app.categories.forEach { Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)) {
                    Text(it.prettyCategory(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                } }
            }
        }
        if (d.screenshots.isNotEmpty()) item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(d.screenshots) { url ->
                    coil3.compose.AsyncImage(
                        model = url, contentDescription = "Screenshot", contentScale = ContentScale.Crop,
                        modifier = Modifier.width(240.dp).height(150.dp).clip(RoundedCornerShape(14.dp)),
                    )
                }
            }
        }
        d.readme?.let { r ->
            item {
                ElevatedSection {
                    Text("About", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(r.take(4000), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (d.sources.isNotEmpty()) item {
            Text("Sources: " + d.sources.take(3).joinToString("  "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
