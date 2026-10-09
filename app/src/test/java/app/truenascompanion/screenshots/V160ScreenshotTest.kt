package app.truenascompanion.screenshots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.truenascompanion.ProtectionSamples
import app.truenascompanion.ReplicationSamples
import app.truenascompanion.data.model.BackupKind
import app.truenascompanion.data.store.ThemeMode
import app.truenascompanion.ui.protection.ProtectionActions
import app.truenascompanion.ui.protection.ProtectionContent
import app.truenascompanion.ui.protection.ProtectionData
import app.truenascompanion.ui.replication.ConnectionsContent
import app.truenascompanion.ui.replication.DatasetBrowser
import app.truenascompanion.ui.replication.DatasetPickerDialog
import app.truenascompanion.ui.replication.KeyPairContent
import app.truenascompanion.ui.replication.KeyPairForm
import app.truenascompanion.ui.replication.ReplicationActions
import app.truenascompanion.ui.replication.ReplicationEditorContent
import app.truenascompanion.ui.replication.ReplicationTabs
import app.truenascompanion.ui.replication.ReplicationTasksContent
import app.truenascompanion.ui.replication.RunReplicationDialog
import app.truenascompanion.ui.replication.SshConnForm
import app.truenascompanion.ui.replication.SshConnectionContent
import app.truenascompanion.ui.theme.TrueNasTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 1.6.0 previews (example data only): replication tasks, SSH connections, key pairs and editors. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi", application = android.app.Application::class)
class V160ScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private val dir = File(System.getProperty("screenshot.dir") ?: "build/screenshots").apply { mkdirs() }
    private fun out(name: String) = File(dir, "$name.png").absolutePath
    private val s = ReplicationSamples

    @Composable private fun Frame(dark: Boolean, content: @Composable () -> Unit) {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, 1f)) {
            TrueNasTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) { content() }
            }
        }
    }

    private fun settle() {
        repeat(8) { rule.mainClock.advanceTimeBy(250); shadowOf(android.os.Looper.getMainLooper()).idle() }
    }

    private fun shot(name: String, dark: Boolean = true, before: () -> Unit = {}, content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent { Frame(dark, content) }
        settle(); before(); settle()
        rule.onRoot().captureRoboImage(out(name))
    }

    private fun scrollTo(text: String): () -> Unit = { rule.mainClock.autoAdvance = true; rule.onNodeWithText(text).performScrollTo(); rule.mainClock.autoAdvance = false }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun Bar(title: String, save: String? = null) = TopAppBar(
        title = { Text(title) },
        navigationIcon = { IconButton(onClick = {}) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) } },
        actions = { save?.let { TextButton(onClick = {}) { Text(it) } } },
    )

    @Composable private fun ListScreen(tab: Int) = Box(Modifier.fillMaxSize()) {
        Column {
            Bar("Replication")
            ReplicationTabs(tab) {}
            if (tab == 0) ReplicationTasksContent(s.data, emptySet(), s.NOW, ReplicationActions()) else ConnectionsContent(s.data, emptySet(), ReplicationActions())
        }
        ExtendedFloatingActionButton(onClick = {}, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text(if (tab == 0) "New task" else "New connection") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp))
    }

    @Test fun tasks() = shot("v160_tasks") { ListScreen(0) }
    @Test fun tasksLight() = shot("v160_tasks_light", dark = false) { ListScreen(0) }
    @Test fun connections() = shot("v160_connections") { ListScreen(1) }

    @Test fun runDialog() = shot("v160_run_dialog") {
        ListScreen(0)
        RunReplicationDialog(s.tasks[1], canNotify = true, onDismiss = {}) {}
    }

    @Composable private fun Editor() = Column {
        Bar("Edit replication task", "Save")
        ReplicationEditorContent(s.form, s.refs, emptyMap(), null, onChange = {}, runOnceEnabled = true)
    }

    @Test fun taskEditor() = shot("v160_task_editor") { Editor() }
    @Test fun taskEditorDestination() = shot("v160_task_editor_destination", before = scrollTo("Snapshots")) { Editor() }
    @Test fun taskEditorWhen() = shot("v160_task_editor_when", before = {
        rule.mainClock.autoAdvance = true; rule.onNode(hasTestTag("run-once")).performScrollTo(); rule.mainClock.autoAdvance = false
    }) { Editor() }
    @Test fun taskEditorSnapshots() = shot("v160_task_editor_snapshots", before = scrollTo("When")) { Editor() }

    @Test fun datasetPicker() = shot("v160_dataset_picker") {
        Editor()
        DatasetPickerDialog(
            DatasetBrowser(false, true, listOf("backup", "backup/apps", "backup/photos", "backup/photos/2025", "offsite", "offsite/family"), setOf("backup/photos"), loading = false),
            {}, {}, {},
        )
    }

    @Test fun sshSemiAutomatic() = shot("v160_ssh_connection") {
        Column {
            Bar("New SSH connection", "Connect")
            SshConnectionContent(SshConnForm(name = "Backup NAS", newKeyName = "replication-key", url = "https://nas2.example.com", password = "example-password"),
                s.keys, false, emptyMap(), null, false, false, {}, {})
        }
    }

    @Test fun sshManual() = shot("v160_ssh_manual", dark = false) {
        Column {
            Bar("New SSH connection", "Save")
            SshConnectionContent(SshConnForm(name = "Offsite", semiAutomatic = false, generateKey = false, existingKeyId = 6, host = "203.0.113.7", port = "2222",
                username = "zfs", remoteHostKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExampleHostKeyOnly"), s.keys, false, emptyMap(), null, false, false, {}, {})
        }
    }

    @Test fun keyPair() = shot("v160_keypair") {
        val k = s.keys[0]
        Column {
            Bar("Key pair", "Save")
            KeyPairContent(KeyPairForm(k.name, k.privateKey!!, k.publicKey!!), k, emptyMap(), null, false, false, {}, {})
        }
    }

    @Test fun protection() = shot("v160_protection", before = {
        rule.mainClock.autoAdvance = true
        rule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("open-replication"))
        rule.mainClock.autoAdvance = false
    }) {
        Column {
            Bar("Storage")
            ProtectionContent(
                ProtectionData(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
                    listOf(ProtectionSamples.backup(BackupKind.CLOUD_SYNC), ProtectionSamples.backup(BackupKind.REPLICATION, id = 2, name = "Photos to backup NAS")),
                    emptyList(), ProtectionSamples.NOW),
                emptySet(), ProtectionActions(),
            )
        }
    }
}
