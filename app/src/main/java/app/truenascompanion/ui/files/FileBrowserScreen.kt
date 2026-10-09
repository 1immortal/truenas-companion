package app.truenascompanion.ui.files

import app.truenascompanion.ui.components.GlowButton
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.SaveAs
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import app.truenascompanion.ui.components.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import app.truenascompanion.ui.components.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.truenascompanion.data.files.FilePolicy
import app.truenascompanion.data.files.SaveTargets
import app.truenascompanion.data.model.FileEntry
import app.truenascompanion.data.model.FileKind
import app.truenascompanion.data.model.FileSort
import app.truenascompanion.data.model.FileStat
import app.truenascompanion.data.model.Health
import app.truenascompanion.ui.appViewModel
import app.truenascompanion.ui.components.ConfirmDialog
import app.truenascompanion.ui.components.ElevatedSection
import app.truenascompanion.ui.components.EmptyState
import app.truenascompanion.ui.components.IconBadge
import app.truenascompanion.ui.components.InfoBanner
import app.truenascompanion.ui.components.ScrollableErrorState
import app.truenascompanion.ui.components.SkeletonList
import app.truenascompanion.ui.theme.LocalBrandColors
import app.truenascompanion.ui.theme.LocalStatusColors
import app.truenascompanion.util.Format
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * File browser for pool datasets under /mnt (1.3.0). With [pickFile] (1.4.0) it becomes a file picker: tapping a file
 * returns its path (used to choose an init/shutdown script); uploads and file actions are hidden. With [pickFolder]
 * (1.5.0) it picks a folder instead: open it, then "Use this folder" (cloud sync local paths).
 */
@Composable
fun FileBrowserScreen(initialPath: String, onBack: () -> Unit, pickFile: ((String) -> Unit)? = null, pickFolder: ((String) -> Unit)? = null) {
    val vm = appViewModel(key = "files:$initialPath") { FileBrowserViewModel(it, initialPath) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    // Starting folder for the next "Save as" dialog (Downloads for the Android 8-9 "Save to Downloads" fallback).
    var saveInDownloads by remember { mutableStateOf(false) }

    val createDoc = rememberLauncherForActivityResult(object : ActivityResultContracts.CreateDocument("*/*") {
        override fun createIntent(context: Context, input: String): Intent = super.createIntent(context, input).apply {
            setType(SaveTargets.saveMime(input))
            if (saveInDownloads) putExtra(DocumentsContract.EXTRA_INITIAL_URI, SaveTargets.downloadsInitialUri)
        }
    }) { uri -> vm.onSaveLocation(uri) }
    val pickUpload = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.prepareUpload(uri) }

    LaunchedEffect(vm) {
        // Snackbars run in their own coroutines so a message on screen never delays the next event (e.g. a save dialog).
        val scope = this
        vm.eventFlow.collect { ev ->
            when (ev) {
                is FileEvent.Message -> scope.launch { snackbar.showSnackbar(ev.text, withDismissAction = ev.text.length > 80, duration = if (ev.text.length > 80) SnackbarDuration.Long else SnackbarDuration.Short) }
                is FileEvent.PickSaveLocation -> {
                    saveInDownloads = ev.inDownloads
                    try {
                        createDoc.launch(ev.suggestedName)
                    } catch (_: ActivityNotFoundException) {
                        vm.onSaveLocation(null)
                        scope.launch { snackbar.showSnackbar("This phone has no app to pick a save location.") }
                    }
                }
                is FileEvent.Saved -> scope.launch {
                    if (snackbar.showSnackbar(ev.text, actionLabel = "Open", withDismissAction = true, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
                        val view = Intent(Intent.ACTION_VIEW).setDataAndType(ev.uri, ev.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        try {
                            context.startActivity(Intent.createChooser(view, "Open ${ev.name} with").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        } catch (_: ActivityNotFoundException) {
                            snackbar.showSnackbar("No app on this phone can open this file type.")
                        }
                    }
                }
                is FileEvent.OpenWith -> {
                    val view = Intent(Intent.ACTION_VIEW).setDataAndType(ev.uri, ev.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    try {
                        context.startActivity(Intent.createChooser(view, "Open ${ev.name} with").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    } catch (_: ActivityNotFoundException) {
                        scope.launch { snackbar.showSnackbar("No app on this phone can open this file type.") }
                    }
                }
            }
        }
    }
    BackHandler(enabled = !ui.atRoot && ui.path != FilePolicy.normalize(initialPath)) { vm.up() }

    FileBrowserContent(
        ui = ui,
        snackbar = snackbar,
        backLeavesScreen = ui.atRoot || ui.path == FilePolicy.normalize(initialPath),
        actions = FileActions(
            onBack = { if (ui.atRoot || ui.path == FilePolicy.normalize(initialPath) || !vm.up()) onBack() },
            onOpen = vm::open,
            onEntry = { e ->
                when {
                    e.isDirectory -> vm.open(e.path)
                    e.isSymlink -> vm.openLink(e)
                    pickFile != null -> pickFile(e.path)
                    pickFolder != null -> Unit
                    FilePolicy.previewKind(e) != null -> vm.preview(e)
                    else -> vm.showDetails(e)
                }
            },
            onDetails = vm::showDetails,
            onDownload = vm::saveToDownloads,
            onSaveAs = vm::saveAs,
            onOpenWith = vm::openWith,
            onPreview = vm::preview,
            onSort = vm::setSort,
            onQuery = vm::setQuery,
            onShowSystem = vm::setShowSystem,
            onRefresh = vm::refresh,
            onLoadMore = vm::loadMore,
            onUpload = { pickUpload.launch(arrayOf("*/*")) },
            onMkdir = vm::mkdir,
            onCancelTransfer = vm::cancelTransfer,
            onHideDetails = vm::hideDetails,
            onClosePreview = vm::closePreview,
            onConfirmUpload = vm::upload,
            onDismissUpload = vm::dismissUpload,
            pickMode = pickFile != null || pickFolder != null,
            pickFolder = pickFolder != null,
            onPickFolder = { path -> pickFolder?.invoke(path) },
        ),
    )
}

class FileActions(
    val onBack: () -> Unit = {},
    val onOpen: (String) -> Unit = {},
    val onEntry: (FileEntry) -> Unit = {},
    val onDetails: (FileEntry) -> Unit = {},
    /** "Save to Downloads" (MediaStore on Android 10+, else the "Save as" dialog in Downloads). */
    val onDownload: (FileEntry) -> Unit = {},
    /** "Save as…": system dialog to pick folder and name. */
    val onSaveAs: (FileEntry) -> Unit = {},
    val onOpenWith: (FileEntry) -> Unit = {},
    val onPreview: (FileEntry) -> Unit = {},
    val onSort: (FileSort) -> Unit = {},
    val onQuery: (String) -> Unit = {},
    val onShowSystem: (Boolean) -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onLoadMore: () -> Unit = {},
    val onUpload: () -> Unit = {},
    val onMkdir: (String) -> Unit = {},
    val onCancelTransfer: () -> Unit = {},
    val onHideDetails: () -> Unit = {},
    val onClosePreview: () -> Unit = {},
    val onConfirmUpload: (PendingUpload) -> Unit = {},
    val onDismissUpload: () -> Unit = {},
    /** 1.4.0 picker mode: tapping a file picks it; no uploads or per-file actions. */
    val pickMode: Boolean = false,
    /** 1.5.0 folder picker: "Use this folder" picks the open folder (files can't be picked). */
    val pickFolder: Boolean = false,
    val onPickFolder: (String) -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserContent(
    ui: FileBrowserUi,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    actions: FileActions = FileActions(),
    inlineDetails: Boolean = false,
    /** Back closes the browser (instead of going up a folder): leaving cancels a running transfer. */
    backLeavesScreen: Boolean = true,
) {
    var searching by rememberSaveable { mutableStateOf(ui.query.isNotEmpty()) }
    var menu by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    var mkdirDialog by remember { mutableStateOf(false) }
    // Stopping an upload that is already streaming leaves a partial file on the NAS: ask first. Holds what to do on "Stop".
    var stopUpload by remember { mutableStateOf<(() -> Unit)?>(null) }
    val title = if (ui.atRoot) (if (actions.pickFolder) "Choose a folder" else if (actions.pickMode) "Choose a file" else "Files") else ui.path.substringAfterLast('/')
    val cancelTransfer = {
        if (ui.transfer?.cancelLeavesPartial == true) stopUpload = actions.onCancelTransfer else actions.onCancelTransfer()
    }
    val back = {
        if (backLeavesScreen && ui.transfer?.cancelLeavesPartial == true) stopUpload = { actions.onCancelTransfer(); actions.onBack() } else actions.onBack()
    }
    BackHandler(enabled = backLeavesScreen && ui.transfer?.cancelLeavesPartial == true) { back() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    if (!ui.atRoot) IconButton(onClick = { searching = !searching; if (!searching) actions.onQuery("") }) {
                        Icon(if (searching) Icons.Rounded.Close else Icons.Rounded.Search, if (searching) "Close search" else "Search this folder")
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Show system folders") },
                                leadingIcon = { Checkbox(checked = ui.showSystem, onCheckedChange = null) },
                                onClick = { menu = false; actions.onShowSystem(!ui.showSystem) },
                            )
                            DropdownMenuItem(text = { Text("Refresh") }, onClick = { menu = false; actions.onRefresh() })
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (actions.pickFolder && !ui.atRoot) ExtendedFloatingActionButton(
                onClick = { actions.onPickFolder(ui.path) },
                icon = { Icon(Icons.Rounded.Check, null) },
                text = { Text("Use this folder") },
            )
            if (!ui.atRoot && ui.transfer == null && !actions.pickMode) Box {
                ExtendedFloatingActionButton(
                    onClick = { addMenu = true },
                    icon = { Icon(Icons.Rounded.Add, null) },
                    text = { Text("Add") },
                )
                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                    DropdownMenuItem(text = { Text("Upload a file") }, leadingIcon = { Icon(Icons.Rounded.Upload, null) }, onClick = { addMenu = false; actions.onUpload() })
                    DropdownMenuItem(text = { Text("New folder") }, leadingIcon = { Icon(Icons.Rounded.CreateNewFolder, null) }, onClick = { addMenu = false; mkdirDialog = true })
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Breadcrumbs(ui.path, actions.onOpen)
            if (searching && !ui.atRoot) {
                OutlinedTextField(
                    value = ui.query, onValueChange = actions.onQuery, singleLine = true,
                    placeholder = { Text("Search in ${title}") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (!ui.atRoot) SortRow(ui, actions.onSort)
            ui.transfer?.let { TransferCard(it, cancelTransfer) }
            Box(Modifier.weight(1f)) {
                PullToRefreshBox(isRefreshing = false, onRefresh = actions.onRefresh, modifier = Modifier.fillMaxSize()) {
                    when {
                        ui.loading -> SkeletonList(6, 64.dp)
                        ui.error != null && ui.entries.isEmpty() -> ScrollableErrorState(ui.error) { actions.onRefresh() }
                        else -> EntryList(ui, actions)
                    }
                }
            }
        }
    }

    if (mkdirDialog) MkdirDialog(onDismiss = { mkdirDialog = false }, onCreate = { mkdirDialog = false; actions.onMkdir(it) })
    ui.details?.let { e ->
        if (inlineDetails) Unit else {
            ModalBottomSheet(onDismissRequest = actions.onHideDetails, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                FileDetails(e, ui.detailsStat, ui.detailsError, actions)
            }
        }
    }
    ui.preview?.let { PreviewDialog(it, actions) }
    ui.pendingUpload?.let { p -> UploadDialog(p, ui, actions) }
    val t = ui.transfer
    val stop = stopUpload
    if (stop != null && t != null && t.cancelLeavesPartial) {
        StopUploadDialog(t, onKeep = { stopUpload = null }, onStop = { stopUpload = null; stop() })
    } else if (stop != null && t == null) {
        stopUpload = null
    }
}

/** Shown before stopping an upload that has started: TrueNAS keeps the part that arrived and has no delete API. */
@Composable
fun StopUploadDialog(t: TransferState, onKeep: () -> Unit, onStop: () -> Unit) {
    val where = t.target ?: t.name
    AlertDialog(
        onDismissRequest = onKeep,
        icon = { Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("Stop the upload?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "TrueNAS is already writing $where. If you stop now, an incomplete file " +
                        "(${Format.bytes(t.done)}${if (t.total > 0) " of ${Format.bytes(t.total)}" else ""}) stays on the NAS" +
                        (if (t.replacing) ", and the original file it was replacing is already overwritten." else ".")
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "The app can't delete files (TrueNAS 25.10 has no API for it). Delete it over an SMB/NFS share or the TrueNAS shell, or upload it again to replace it.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onStop) { Text("Stop upload", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onKeep) { Text("Keep uploading") } },
    )
}

@Composable
private fun Breadcrumbs(path: String, onOpen: (String) -> Unit) {
    val crumbs = FilePolicy.breadcrumbs(path)
    val scroll = rememberScrollState()
    LaunchedEffect(path) { scroll.animateScrollTo(scroll.maxValue) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        crumbs.forEachIndexed { i, (label, p) ->
            val last = i == crumbs.lastIndex
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal,
                color = if (last) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = !last) { onOpen(p) }.padding(horizontal = 6.dp, vertical = 6.dp),
            )
            if (!last) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SortRow(ui: FileBrowserUi, onSort: (FileSort) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FileSort.entries.forEach { s ->
            val selected = ui.sort == s
            FilterChip(
                selected = selected, onClick = { onSort(s) },
                label = { Text(s.label) },
                trailingIcon = if (selected) ({ Icon(if (ui.descending) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward, null, Modifier.size(16.dp)) }) else null,
            )
        }
        Spacer(Modifier.weight(1f))
        val count = if (ui.hasMore) ui.total ?: ui.entries.size else ui.visible.size
        if (!ui.loading) Text("$count item" + if (count == 1) "" else "s", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TransferCard(t: TransferState, onCancel: () -> Unit) {
    ElevatedSection(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(if (t.kind == TransferKind.UPLOAD) Icons.Rounded.Upload else Icons.Rounded.Download, size = 36.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (t.finishing) "Saving on TrueNAS…" else "${t.kind.verb} ${t.name}", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val pct = t.fraction?.let { " · %.0f%%".format(it * 100) } ?: ""
                Text("${Format.bytes(t.done)} of ${if (t.total > 0) Format.bytes(t.total) else "?"}$pct", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
        Spacer(Modifier.height(8.dp))
        val f = t.fraction
        if (f != null && !t.finishing) LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
        if (t.proxyHint) {
            Spacer(Modifier.height(8.dp))
            Text(PROXY_HINT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

const val PROXY_HINT = "Large file over the internet: a reverse proxy (e.g. Nginx Proxy Manager or Cloudflare) may cap upload size or cut long downloads. If it fails, try at home or over the VPN."

@Composable
private fun EntryList(ui: FileBrowserUi, actions: FileActions) {
    val list = ui.visible
    if (list.isEmpty()) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                when {
                    ui.atRoot -> EmptyState(Icons.Rounded.Storage, "No pools", "Create a storage pool in TrueNAS to browse its files here.")
                    ui.query.isNotBlank() -> EmptyState(Icons.Rounded.Search, "No matches", "Nothing in this folder matches \"${ui.query}\".")
                    ui.entries.isNotEmpty() -> EmptyState(Icons.Rounded.FolderOff, "Only system items", "This folder only has hidden or system items. Turn on \"Show system folders\" to see them.")
                    else -> EmptyState(Icons.Rounded.Folder, "Empty folder", "Upload a file or create a folder with Add.")
                }
            }
        }
        return
    }
    LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp), modifier = Modifier.fillMaxSize()) {
        // A refresh or "load more" that failed while older entries are still shown.
        ui.error?.let { err -> item(key = "error") { InfoBanner(err, health = Health.WARNING, modifier = Modifier.padding(vertical = 4.dp)) } }
        if (ui.atRoot || actions.pickMode) item {
            Text(if (actions.pickFolder) "Open the folder to use, then tap Use this folder. Only folders in your pools can be picked." else if (actions.pickMode) "Tap the file to use. Only files in your pools can be picked." else "Choose a pool to browse its datasets and files.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
        }
        items(list, key = { it.path }) { e -> EntryRow(e, ui.atRoot, actions, incomplete = e.path in ui.incomplete) }
        if (ui.hasMore) item {
            LaunchedEffect(ui.entries.size) { actions.onLoadMore() }
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                if (ui.loadingMore) LinearProgressIndicator(Modifier.width(120.dp)) else TextButton(onClick = actions.onLoadMore) { Text("Load more") }
            }
        }
    }
}

fun kindIcon(kind: FileKind): ImageVector = when (kind) {
    FileKind.FOLDER -> Icons.Rounded.Folder
    FileKind.IMAGE -> Icons.Rounded.Image
    FileKind.VIDEO -> Icons.Rounded.Movie
    FileKind.AUDIO -> Icons.Rounded.AudioFile
    FileKind.TEXT -> Icons.Rounded.Description
    FileKind.CODE -> Icons.Rounded.Code
    FileKind.ARCHIVE -> Icons.Rounded.FolderZip
    FileKind.PDF -> Icons.Rounded.PictureAsPdf
    FileKind.DOCUMENT -> Icons.Rounded.Description
    FileKind.SPREADSHEET -> Icons.Rounded.TableChart
    FileKind.PRESENTATION -> Icons.Rounded.Slideshow
    FileKind.DISK_IMAGE -> Icons.Rounded.Album
    FileKind.LINK -> Icons.Rounded.Link
    FileKind.OTHER -> Icons.AutoMirrored.Rounded.InsertDriveFile
}

private fun dateText(millis: Long?): String? = millis?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) }

@Composable
private fun EntryRow(e: FileEntry, atRoot: Boolean, actions: FileActions, incomplete: Boolean = false) {
    var menu by remember { mutableStateOf(false) }
    val kind = FilePolicy.kindOf(e)
    val brand = LocalBrandColors.current
    val tint = if (kind == FileKind.FOLDER) MaterialTheme.colorScheme.primary else if (brand.dark) brand.accent else MaterialTheme.colorScheme.tertiary
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { actions.onEntry(e) }.padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(if (atRoot) Icons.Rounded.Storage else kindIcon(kind), tint = tint, size = 40.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(e.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = when {
                atRoot -> "Pool · ${e.path}"
                e.isDirectory -> listOfNotNull(if (e.isMountpoint) "Dataset" else "Folder", dateText(e.mtimeMillis)).joinToString(" · ")
                e.isSymlink -> "Link"
                incomplete -> "Incomplete upload · ${Format.bytes(e.size)}"
                else -> listOfNotNull(Format.bytes(e.size), dateText(e.mtimeMillis)).joinToString(" · ")
            }
            Text(
                sub, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (incomplete) LocalStatusColors.current.of(Health.WARNING) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (FilePolicy.isSystemName(e.name)) Icon(Icons.Rounded.Lock, "System folder", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!atRoot && !actions.pickMode) Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Actions for ${e.name}") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (e.isFile) {
                    if (FilePolicy.previewKind(e) != null) DropdownMenuItem(text = { Text("Preview") }, leadingIcon = { Icon(Icons.Rounded.Visibility, null) }, onClick = { menu = false; actions.onPreview(e) })
                    DropdownMenuItem(text = { Text("Open with…") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null) }, onClick = { menu = false; actions.onOpenWith(e) })
                    DropdownMenuItem(text = { Text("Save to Downloads") }, leadingIcon = { Icon(Icons.Rounded.Download, null) }, onClick = { menu = false; actions.onDownload(e) })
                    DropdownMenuItem(text = { Text("Save as…") }, leadingIcon = { Icon(Icons.Rounded.SaveAs, null) }, onClick = { menu = false; actions.onSaveAs(e) })
                }
                DropdownMenuItem(text = { Text("Details") }, leadingIcon = { Icon(Icons.Rounded.Info, null) }, onClick = { menu = false; actions.onDetails(e) })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FileDetails(e: FileEntry, stat: FileStat?, error: String?, actions: FileActions) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(kindIcon(FilePolicy.kindOf(e)), size = 48.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(e.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(e.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(16.dp))
        if (e.isFile) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (FilePolicy.previewKind(e) != null) FilledTonalButton(onClick = { actions.onHideDetails(); actions.onPreview(e) }) { Text("Preview") }
                FilledTonalButton(onClick = { actions.onHideDetails(); actions.onOpenWith(e) }) { Text("Open with") }
                OutlinedButton(onClick = { actions.onHideDetails(); actions.onDownload(e) }) { Text("Save to Downloads") }
                OutlinedButton(onClick = { actions.onHideDetails(); actions.onSaveAs(e) }) { Text("Save as…") }
            }
            Spacer(Modifier.height(16.dp))
        } else if (e.isDirectory) {
            FilledTonalButton(onClick = { actions.onHideDetails(); actions.onOpen(e.path) }) { Text("Open folder") }
            Spacer(Modifier.height(16.dp))
        }
        val st = stat
        DetailRow("Type", when { e.isDirectory && (st?.isMountpoint ?: e.isMountpoint) -> "Dataset (mount point)"; e.isDirectory -> "Folder"; e.isSymlink -> "Link"; else -> FilePolicy.kindOf(e).name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() } + " file" })
        if (!e.isDirectory) DetailRow("Size", "${Format.bytes(st?.size ?: e.size)} (${"%,d".format(st?.size ?: e.size)} bytes)")
        DetailRow("Modified", st?.mtimeMillis?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) } ?: if (error == null) "…" else "—")
        st?.btimeMillis?.let { DetailRow("Created", DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))) }
        DetailRow("Owner", st?.let { s -> "${s.user ?: "uid ${s.uid}"} (${s.uid})" } ?: (e.uid?.let { "uid $it" } ?: "…"))
        DetailRow("Group", st?.let { s -> "${s.group ?: "gid ${s.gid}"} (${s.gid})" } ?: (e.gid?.let { "gid $it" } ?: "…"))
        val mode = st?.mode ?: e.mode
        DetailRow("Permissions", "${FilePolicy.permissions(mode, st?.type ?: e.type)}  ${FilePolicy.octal(mode)}")
        DetailRow("ACL", if (st?.acl ?: e.acl) "Yes, permissions come from an ACL (the mode bits may not tell the whole story)" else "No")
        if (st != null && st.realpath != e.path) DetailRow("Points to", st.realpath)
        error?.let { Spacer(Modifier.height(10.dp)); InfoBanner(it, health = Health.WARNING) }
        Spacer(Modifier.height(14.dp))
        Text(
            "Renaming, moving and deleting files isn't possible from the app: TrueNAS 25.10 has no API for it. Use an SMB/NFS share or the TrueNAS shell.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(110.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}

@Composable
private fun MkdirDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val problem = if (name.isEmpty()) null else FilePolicy.nameProblem(name)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.CreateNewFolder, null) },
        title = { Text("New folder") },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Folder name") },
                isError = problem != null, supportingText = problem?.let { { Text(it) } },
            )
        },
        confirmButton = { GlowButton(onClick = { onCreate(name) }, enabled = name.isNotBlank() && problem == null) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun UploadDialog(p: PendingUpload, ui: FileBrowserUi, actions: FileActions) {
    val hint = p.size >= FilePolicy.PROXY_HINT_MIN && (ui.route == null || ui.route == app.truenascompanion.data.model.Route.REMOTE)
    val size = if (p.size >= 0) Format.bytes(p.size) else "unknown size"
    if (p.exists) {
        ConfirmDialog(
            title = "Replace ${p.name}?",
            text = "A file with this name already exists in ${p.target.substringBeforeLast('/')}. Uploading ($size) overwrites it. This can't be undone (unless a snapshot has it)." +
                if (hint) "\n\n$PROXY_HINT" else "",
            confirmLabel = "Replace",
            destructive = true,
            strongAuth = true,
            icon = Icons.Rounded.Upload,
            onConfirm = { actions.onConfirmUpload(p) },
            onDismiss = actions.onDismissUpload,
        )
    } else {
        ConfirmDialog(
            title = "Upload ${p.name}?",
            text = "Upload $size to ${p.target.substringBeforeLast('/')}." + if (hint) "\n\n$PROXY_HINT" else "",
            confirmLabel = "Upload",
            icon = Icons.Rounded.Upload,
            onConfirm = { actions.onConfirmUpload(p) },
            onDismiss = actions.onDismissUpload,
        )
    }
}

@Composable
private fun PreviewDialog(p: FilePreview, actions: FileActions) {
    Dialog(onDismissRequest = actions.onClosePreview, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        PreviewContent(p, actions)
    }
}

@Composable
fun PreviewContent(p: FilePreview, actions: FileActions) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions.onClosePreview) { Icon(Icons.Rounded.Close, "Close preview") }
                Column(Modifier.weight(1f)) {
                    Text(p.entry.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(Format.bytes(p.entry.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { actions.onClosePreview(); actions.onOpenWith(p.entry) }) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, "Open with") }
                IconButton(onClick = { actions.onClosePreview(); actions.onDownload(p.entry) }) { Icon(Icons.Rounded.Download, "Save to Downloads") }
            }
            HorizontalDivider()
            when (p) {
                is FilePreview.Text -> SelectionContainer(Modifier.weight(1f)) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(16.dp)) {
                        Text(p.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        if (p.truncated) Text("\n… preview shows the first ${Format.bytes(FilePolicy.TEXT_PREVIEW_MAX)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                is FilePreview.Image -> Box(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLowest), contentAlignment = Alignment.Center) {
                    Image(p.bitmap, p.entry.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().heightIn(min = 100.dp))
                }
            }
        }
    }
}
