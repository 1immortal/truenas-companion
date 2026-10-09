package app.truenascompanion.screenshots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.truenascompanion.ProtectionSamples
import app.truenascompanion.ProtectionSamples.D
import app.truenascompanion.ProtectionSamples.H
import app.truenascompanion.ProtectionSamples.NOW
import app.truenascompanion.data.model.ApiFlavor
import app.truenascompanion.data.model.BackupKind
import app.truenascompanion.data.model.CronSchedule
import app.truenascompanion.data.model.JobState
import app.truenascompanion.data.model.SmartTestType
import app.truenascompanion.data.model.WidgetType
import app.truenascompanion.data.store.ThemeMode
import app.truenascompanion.data.update.ReleaseInfo
import app.truenascompanion.ui.AppNavBar
import app.truenascompanion.ui.dashboard.DashboardData
import app.truenascompanion.ui.dashboard.DashboardWidget
import app.truenascompanion.ui.dashboard.LiveStats
import app.truenascompanion.ui.protection.ProtectionActions
import app.truenascompanion.ui.protection.ProtectionContent
import app.truenascompanion.ui.protection.ProtectionData
import app.truenascompanion.ui.protection.RollbackDialog
import app.truenascompanion.ui.protection.SmartRunDialog
import app.truenascompanion.ui.protection.SnapshotSort
import app.truenascompanion.ui.protection.SnapshotTaskForm
import app.truenascompanion.ui.protection.SnapshotsContent
import app.truenascompanion.ui.system.DownloadState
import app.truenascompanion.ui.system.UpdateDialog
import app.truenascompanion.ui.theme.TrueNasTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** v0.5.0 previews: data protection and in-app updates. Example data only. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h1700dp-xxhdpi", application = android.app.Application::class)
class V05ScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private val dir = File(System.getProperty("screenshot.dir") ?: "build/screenshots").apply { mkdirs() }
    private fun out(name: String) = File(dir, "$name.png").absolutePath

    @Composable
    private fun Frame(dark: Boolean, fontScale: Float = 1f, content: @Composable () -> Unit) {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
            TrueNasTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background) { content() }
            }
        }
    }

    private fun shot(name: String, dark: Boolean, font: Float = 1f, height: Int? = null, content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent { Frame(dark, font) { Box(if (height != null) Modifier.fillMaxWidth().height(height.dp) else Modifier.fillMaxWidth()) { content() } } }
        rule.mainClock.advanceTimeBy(1500)
        rule.onRoot().captureRoboImage(out(name))
    }

    private fun dialogShot(name: String, dark: Boolean, font: Float = 1f, content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent { Frame(dark, font) { Box(Modifier.fillMaxSize()) { content() } } }
        rule.mainClock.advanceTimeBy(1000)
        rule.onNode(isDialog()).captureRoboImage(out(name))
    }

    private val pools = listOf(
        ProtectionSamples.pool("tank", state = "SCANNING", pct = 63.0, end = null, start = NOW - 3 * H),
        ProtectionSamples.pool("fast", end = NOW - 12 * D, id = 2),
    )
    private val data = ProtectionData(
        pools = pools,
        disks = ProtectionSamples.disks,
        snapshotTasks = listOf(
            ProtectionSamples.snapTask(),
            ProtectionSamples.snapTask(2, "fast/apps", at = NOW - 9 * H, schedule = ProtectionSamples.daily3).copy(recursive = true, lifetimeValue = 30, lifetimeUnit = "DAY"),
        ),
        scrubTasks = listOf(ProtectionSamples.scrubTask, ProtectionSamples.scrubTask.copy(id = 2, poolId = 2, poolName = "fast")),
        smart = listOf(
            ProtectionSamples.smart(),
            ProtectionSamples.smart(11, SmartTestType.LONG, schedule = CronSchedule(minute = "0", hour = "1", dom = "1")),
        ),
        backups = listOf(
            ProtectionSamples.backup(),
            ProtectionSamples.backup(BackupKind.REPLICATION, 2, "tank/photos → backup-nas", ProtectionSamples.job(finished = NOW - 22 * H))
                .copy(detail = "tank/photos → backup/photos"),
            ProtectionSamples.backup(BackupKind.RSYNC, 3, "Documents to USB", ProtectionSamples.job(JobState.RUNNING, finished = null, pct = 37.0)
                .copy(progressText = "Sending 1,240 of 3,310 files"), schedule = null)
                .copy(detail = "/mnt/tank/docs → usb-backup::docs"),
        ),
        alerts = emptyList(),
        now = NOW,
    )

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun StorageProtection(d: ProtectionData = data, height: Int = 1650) = Column(Modifier.fillMaxWidth().height(height.dp)) {
        TopAppBar(title = { Text("Storage") })
        PrimaryScrollableTabRow(selectedTabIndex = 3, edgePadding = 8.dp) {
            listOf("Pools", "Disks", "Datasets", "Protection").forEachIndexed { i, t -> Tab(selected = i == 3, onClick = {}, text = { Text(t, maxLines = 1) }) }
        }
        Box(Modifier.weight(1f)) { ProtectionContent(d, emptySet(), ProtectionActions()) }
        AppNavBar("storage") {}
    }

    @Test fun protectionDark() = shot("preview-protection-dark", true) { StorageProtection() }
    @Test fun protectionLight() = shot("preview-protection-light", false) { StorageProtection() }
    @Test fun protectionProblems() = shot("preview-protection-problems-dark", true) {
        StorageProtection(data.copy(
            pools = listOf(ProtectionSamples.pool("tank", end = NOW - 50 * D)),
            snapshotTasks = listOf(ProtectionSamples.snapTask(state = "ERROR", error = "cannot create snapshot 'tank/photos@auto-2026-09-26_13-00': out of space")),
            alerts = listOf(ProtectionSamples.alert("SMARTFailedSelftest", "Device: /dev/sdb [SAT], Self-Test Log error count increased from 0 to 1")),
            backups = listOf(ProtectionSamples.backup(job = ProtectionSamples.job(JobState.FAILED, error = "Bucket not found: nas-backup-photos"))),
        ))
    }
    @Test fun protectionFont() = shot("audit-protection-font130", true, 1.3f, height = 1700) { StorageProtection(height = 1700) }

    @Composable private fun Snaps() = Box(Modifier.height(900.dp)) {
        SnapshotsContent(ProtectionSamples.snapshots, "", {}, SnapshotSort.NEWEST, {}, emptySet(), {}, {}, now = NOW)
    }
    @Test fun snapshotsDark() = shot("preview-snapshots-dark", true) { Snaps() }
    @Test fun snapshotsLight() = shot("preview-snapshots-light", false) {
        Box(Modifier.height(900.dp)) {
            SnapshotsContent(ProtectionSamples.snapshots, "", {}, SnapshotSort.LARGEST, {}, setOf(ProtectionSamples.snapshots[1].id, ProtectionSamples.snapshots[3].id), {}, {}, now = NOW)
        }
    }
    @Test fun snapshotsFont() = shot("audit-snapshots-font130", true, 1.3f) { Snaps() }

    private val form = SnapshotTaskForm(dataset = "tank/photos", recursive = true, exclude = "tank/photos/cache")
    private val datasets = listOf("tank", "tank/photos", "tank/photos/cache", "tank/docs", "fast/apps").map {
        app.truenascompanion.data.model.Dataset(it, it.substringBefore('/'), "FILESYSTEM", 1, 1, false, false, "/mnt/$it")
    }
    @Test fun taskEditorDark() = shot("preview-snapshot-task-dark", true, height = 1300) { SnapshotTaskForm(datasets, form) {} }
    @Test fun taskEditorLight() = shot("preview-snapshot-task-light", false, height = 1300) { SnapshotTaskForm(datasets, form.copy(schedule = ProtectionSamples.daily3, recursive = false)) {} }
    @Test fun taskEditorFont() = shot("audit-snapshot-task-font130", true, 1.3f, height = 1500) { SnapshotTaskForm(datasets, form) {} }

    private val dash = DashboardData(loading = false, protection = data.summary)
    @Test fun dashboardCardDark() = shot("preview-dashboard-protection-dark", true) {
        Box(Modifier.padding(16.dp)) { DashboardWidget(WidgetType.PROTECTION, true, dash, LiveStats(), ApiFlavor.WEBSOCKET, onClick = {}) }
    }
    @Test fun dashboardCardLight() = shot("preview-dashboard-protection-light", false) {
        Column(Modifier.padding(16.dp)) { DashboardWidget(WidgetType.PROTECTION, true, dash, LiveStats(), ApiFlavor.WEBSOCKET, onClick = {}) }
    }
    @Test fun dashboardCardFont() = shot("audit-dashboard-protection-font130", true, 1.3f) {
        Box(Modifier.padding(16.dp)) { DashboardWidget(WidgetType.PROTECTION, true, dash, LiveStats(), ApiFlavor.WEBSOCKET, onClick = {}) }
    }

    @Test fun smartRunDark() = dialogShot("preview-smart-run-dark", true) { SmartRunDialog(ProtectionSamples.disks, {}, { _, _ -> }) }
    @Test fun smartRunFont() = dialogShot("audit-smart-run-font130", false, 1.3f) { SmartRunDialog(ProtectionSamples.disks, {}, { _, _ -> }) }
    @Test fun rollbackDark() = dialogShot("preview-rollback-dark", true) {
        RollbackDialog("tank/photos", ProtectionSamples.snapshots[2], ProtectionSamples.snapshots.take(2), {}, {})
    }
    @Test fun rollbackFont() = dialogShot("audit-rollback-font130", false, 1.3f) {
        RollbackDialog("tank/photos", ProtectionSamples.snapshots[2], ProtectionSamples.snapshots.take(2), {}, {})
    }

    private val release = ReleaseInfo(
        "0.5.1", "TrueNAS Companion v0.5.1", "## What's new\n- **Protection:** faster snapshot list\n- Fixed a crash when a pool has no disks\n- Update checker shows download size",
        "https://github.com/1immortal/ytn/releases/tag/v0.5.1", "truenas-companion-v0.5.1-debug.apk",
        "https://github.com/1immortal/ytn/releases/download/v0.5.1/truenas-companion-v0.5.1-debug.apk", 14_200_000, "a".repeat(64), null,
    )
    @Test fun updateDark() = dialogShot("preview-update-dark", true) { UpdateDialog("0.5.0", release, DownloadState.Idle, false, {}, {}, {}, {}) }
    @Test fun updateLight() = dialogShot("preview-update-light", false) { UpdateDialog("0.5.0", release, DownloadState.Downloading(0.62f), false, {}, {}, {}, {}) }
    @Test fun updateFont() = dialogShot("audit-update-font130", true, 1.3f) {
        UpdateDialog("0.5.0", release, DownloadState.Ready(File("x")), true, {}, {}, {}, {})
    }
}

