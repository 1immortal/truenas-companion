package app.truenascompanion.ui.system

import android.app.Application
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.truenascompanion.AppContainer
import app.truenascompanion.BuildConfigInfo
import app.truenascompanion.data.update.ReleaseInfo
import app.truenascompanion.data.update.UpdateCheckWorker
import app.truenascompanion.data.update.UpdateChecker
import app.truenascompanion.data.update.UpdateChannel
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SegmentedButton
import app.truenascompanion.data.update.UpdateInstaller
import app.truenascompanion.data.update.UpdateResult
import app.truenascompanion.data.update.UpdateVerificationException
import app.truenascompanion.ui.appViewModel
import app.truenascompanion.ui.components.GlowButton
import app.truenascompanion.ui.theme.LocalStatusColors
import app.truenascompanion.data.model.Health
import app.truenascompanion.util.Format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

sealed interface DownloadState {
    data object Idle : DownloadState
    data class Downloading(val progress: Float) : DownloadState
    data class Ready(val file: File) : DownloadState
    data class Failed(val message: String) : DownloadState
}

class UpdateViewModel(private val c: AppContainer, private val app: Application) : ViewModel() {
    val result: StateFlow<UpdateResult?> = c.updates.latest
    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()
    private val _download = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val download: StateFlow<DownloadState> = _download.asStateFlow()
    val autoCheck: StateFlow<Boolean> = c.settings.autoUpdateCheck.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val channel: StateFlow<UpdateChannel> = c.settings.updateChannel.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), UpdateChannel.defaultForBuild(),
    )
    private val installer = UpdateInstaller(app)
    val currentVersion: String = BuildConfigInfo.versionName(app)

    fun check() {
        if (_checking.value) return
        _checking.value = true
        viewModelScope.launch {
            c.updates.check(currentVersion, channel.value)
            _checking.value = false
        }
    }

    fun setAutoCheck(on: Boolean) = viewModelScope.launch {
        c.settings.setAutoUpdateCheck(on)
        UpdateCheckWorker.sync(app, on)
    }

    fun setChannel(ch: UpdateChannel) = viewModelScope.launch {
        if (ch == channel.value) return@launch
        c.settings.setUpdateChannel(ch)
        // Re-check against the new channel's APK asset / signer.
        _checking.value = true
        c.updates.check(currentVersion, ch)
        _checking.value = false
        resetDownload()
    }

    fun download(release: ReleaseInfo) {
        if (_download.value is DownloadState.Downloading) return
        _download.value = DownloadState.Downloading(0f)
        viewModelScope.launch {
            _download.value = try {
                DownloadState.Ready(installer.download(release) { p -> _download.value = DownloadState.Downloading(p) })
            } catch (e: UpdateVerificationException) {
                DownloadState.Failed(e.message ?: "The download couldn't be verified.")
            } catch (e: Throwable) {
                DownloadState.Failed("Download failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    val canInstall: Boolean get() = installer.canInstall()
    fun askInstallPermission() = installer.openInstallPermissionSettings()
    fun install(file: File) = runCatching { installer.install(file) }.onFailure { _download.value = DownloadState.Failed("Couldn't open the installer: ${it.message}") }
    fun resetDownload() { if (_download.value !is DownloadState.Downloading) _download.value = DownloadState.Idle }
}

/** About › updates: check now, daily auto-check, and the update dialog. */
@Composable
fun UpdateSection() {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val vm = appViewModel { UpdateViewModel(it, app) }
    val result by vm.result.collectAsStateWithLifecycle()
    val checking by vm.checking.collectAsStateWithLifecycle()
    val auto by vm.autoCheck.collectAsStateWithLifecycle()
    val channel by vm.channel.collectAsStateWithLifecycle()
    val download by vm.download.collectAsStateWithLifecycle()
    var showDialog by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        UpdateStatusLine(result, checking)
        val available = result as? UpdateResult.Available
        if (available != null) {
            GlowButton(onClick = { showDialog = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.SystemUpdate, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("View update ${available.release.version}", maxLines = 1)
            }
        } else {
            OutlinedButton(onClick = vm::check, enabled = !checking, modifier = Modifier.fillMaxWidth()) {
                if (checking) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                Text("Check for updates", maxLines = 1)
            }
        }
        UpdateChannelRow(channel = channel, onSelect = vm::setChannel)
        SettingRow(Icons.Rounded.SystemUpdate, "Check for updates daily", "Asks GitHub once a day and notifies you about new versions. Nothing else is sent.") {
            Switch(checked = auto, onCheckedChange = { vm.setAutoCheck(it) })
        }
    }

    val available = result as? UpdateResult.Available
    if (showDialog && available != null) {
        UpdateDialog(
            current = vm.currentVersion, release = available.release, download = download,
            onDownload = { vm.download(available.release) },
            onInstall = { file -> if (vm.canInstall) vm.install(file) else vm.askInstallPermission() },
            needsPermission = !vm.canInstall,
            onOpenPage = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, available.release.pageUrl.toUri())) } },
            onDismiss = { showDialog = false; vm.resetDownload() },
        )
    }
}


@Composable
fun UpdateChannelRow(channel: UpdateChannel, onSelect: (UpdateChannel) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Update channel", style = MaterialTheme.typography.titleSmall)
        Text(
            "Release is the signed production APK. Debug is for developers (different package id and signing key). " +
                "Switching channels usually requires uninstalling the other build first — Android will not replace an app with a different signature or package name.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            UpdateChannel.entries.forEachIndexed { index, ch ->
                SegmentedButton(
                    selected = channel == ch,
                    onClick = { onSelect(ch) },
                    shape = SegmentedButtonDefaults.itemShape(index, UpdateChannel.entries.size),
                ) { Text(ch.label, maxLines = 1) }
            }
        }
    }
}

@Composable
private fun UpdateStatusLine(result: UpdateResult?, checking: Boolean) {
    val (health, text) = when {
        checking -> Health.UNKNOWN to "Checking GitHub for a newer version…"
        result is UpdateResult.Available -> Health.WARNING to "Version ${result.release.version} is available"
        result is UpdateResult.UpToDate -> Health.HEALTHY to "You have the latest version"
        result is UpdateResult.Unavailable -> Health.UNKNOWN to result.message
        else -> return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (health == Health.HEALTHY) { Icon(Icons.Rounded.CheckCircle, null, Modifier.size(18.dp), tint = LocalStatusColors.current.of(health)); Spacer(Modifier.width(8.dp)) }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = if (health == Health.WARNING) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Changelog + download/verify/install. No text fields, so it renders in previews. */
@Composable
fun UpdateDialog(
    current: String, release: ReleaseInfo, download: DownloadState, needsPermission: Boolean,
    onDownload: () -> Unit, onInstall: (File) -> Unit, onOpenPage: () -> Unit, onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (download !is DownloadState.Downloading) onDismiss() },
        icon = { Icon(Icons.Rounded.SystemUpdate, null) },
        title = { Text("Update to ${release.version}") },
        text = {
            Column {
                Text("You have $current." + (release.apkSize?.let { " Download size ${Format.bytes(it)}." } ?: ""),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    Text("What's new", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(UpdateChecker.plainNotes(release.notes).ifBlank { "See the release page for details." }, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(12.dp))
                when (download) {
                    DownloadState.Idle -> Text("The app is downloaded from GitHub, checked against its published SHA-256 and signature, then installed by Android.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    is DownloadState.Downloading -> {
                        LinearProgressIndicator(progress = { download.progress }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(4.dp))
                        Text("Downloading… ${(download.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                    }
                    is DownloadState.Ready -> Text(
                        if (needsPermission) "Downloaded and verified. Android asks you once to allow TrueNAS Companion to install updates; turn it on, come back and tap Install."
                        else "Downloaded and verified. Android asks you to confirm the update.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    is DownloadState.Failed -> Text(download.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            when (download) {
                DownloadState.Idle, is DownloadState.Failed -> GlowButton(onClick = onDownload) { Text(if (download is DownloadState.Failed) "Try again" else "Download & install", maxLines = 1) }
                is DownloadState.Downloading -> GlowButton(onClick = {}, enabled = false) { Text("Downloading…", maxLines = 1) }
                is DownloadState.Ready -> GlowButton(onClick = { onInstall(download.file) }) { Text(if (needsPermission) "Allow & install" else "Install", maxLines = 1) }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onOpenPage) { Text("Release page", maxLines = 1) }
                if (download !is DownloadState.Downloading) TextButton(onClick = onDismiss) { Text("Later", maxLines = 1) }
            }
        },
    )
}
