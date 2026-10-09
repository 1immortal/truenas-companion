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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.truenascompanion.ProtectionSamples
import app.truenascompanion.data.api.KeyPair
import app.truenascompanion.data.api.RemoteEntry
import app.truenascompanion.data.cloud.BwRow
import app.truenascompanion.data.cloud.CloudCredential
import app.truenascompanion.data.cloud.CloudDirection
import app.truenascompanion.data.cloud.CloudProvider
import app.truenascompanion.data.cloud.CloudProviders
import app.truenascompanion.data.cloud.CloudSyncForm
import app.truenascompanion.data.cloud.CloudSyncTask
import app.truenascompanion.data.cloud.TransferMode
import app.truenascompanion.data.model.BackupKind
import app.truenascompanion.data.model.CronSchedule
import app.truenascompanion.data.model.FileEntry
import app.truenascompanion.data.model.JobState
import app.truenascompanion.data.model.LastJob
import app.truenascompanion.data.store.ThemeMode
import app.truenascompanion.ui.cloud.CloudCredentialsContent
import app.truenascompanion.ui.cloud.CloudSyncActions
import app.truenascompanion.ui.cloud.CloudSyncData
import app.truenascompanion.ui.cloud.CloudTabs
import app.truenascompanion.ui.cloud.CloudTaskEditorContent
import app.truenascompanion.ui.cloud.CloudTasksContent
import app.truenascompanion.ui.cloud.CredentialEditorContent
import app.truenascompanion.ui.cloud.CredentialForm
import app.truenascompanion.ui.cloud.ProviderOption
import app.truenascompanion.ui.cloud.RemoteBrowser
import app.truenascompanion.ui.cloud.RemoteBrowserDialog
import app.truenascompanion.ui.cloud.RunCloudSyncDialog
import app.truenascompanion.ui.cloud.TaskEditorRefs
import app.truenascompanion.ui.files.FileActions
import app.truenascompanion.ui.files.FileBrowserContent
import app.truenascompanion.ui.files.FileBrowserUi
import app.truenascompanion.ui.protection.ProtectionActions
import app.truenascompanion.ui.protection.ProtectionContent
import app.truenascompanion.ui.protection.ProtectionData
import app.truenascompanion.ui.theme.TrueNasTheme
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 1.5.0 previews (example data only): cloud sync tasks, credentials, editors and pickers. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi", application = android.app.Application::class)
class V150ScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private val dir = File(System.getProperty("screenshot.dir") ?: "build/screenshots").apply { mkdirs() }
    private fun out(name: String) = File(dir, "$name.png").absolutePath

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

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun Bar(title: String, save: Boolean = false) = TopAppBar(
        title = { Text(title) },
        navigationIcon = { IconButton(onClick = {}) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) } },
        actions = { if (save) TextButton(onClick = {}) { Text("Save") } },
    )

    // ---------- example data ----------

    private val now = ProtectionSamples.NOW
    private val h = 3_600_000L
    private fun a(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private val providers = listOf(
        CloudProvider("B2", "Backblaze B2", null, true, "Bucket", listOf("bucket", "folder", "fast_list", "b2_chunk_size")),
        CloudProvider("GOOGLE_DRIVE", "Google Drive", "https://www.truenas.com/oauth/google_drive", false, "Bucket", listOf("folder", "fast_list", "acknowledge_abuse")),
        CloudProvider("SFTP", "SFTP", null, false, "Bucket", listOf("folder")),
        CloudProvider("S3", "Amazon S3", null, true, "Bucket", listOf("bucket", "folder", "fast_list", "region", "encryption", "storage_class")),
    )
    private val creds = listOf(
        CloudCredential(3, "Backblaze", "B2", a("""{"type":"B2","account":"0012ab","key":"K001example"}""")),
        CloudCredential(4, "Family Drive", "GOOGLE_DRIVE", a("""{"type":"GOOGLE_DRIVE","token":"{}","team_drive":""}""")),
        CloudCredential(5, "Offsite box", "SFTP", a("""{"type":"SFTP","host":"backup.example.com","port":22,"user":"nas","pass":null,"private_key":4}""")),
        CloudCredential(6, "MinIO", "S3", a("""{"type":"S3","access_key_id":"minio","secret_access_key":"x","endpoint":"https://s3.example.com"}""")),
    )
    private val tasks = listOf(
        CloudSyncTask(7, "Photos to B2", "/mnt/tank/photos", 3, "Backblaze", "B2", a("""{"bucket":"photo-backup","folder":"/nas"}"""),
            CronSchedule("0", "2", "*", "*", "*"), snapshot = true, mode = TransferMode.SYNC, encryption = true,
            job = LastJob(JobState.RUNNING, now - h / 4, null, null, null, 42.0, "1.21 GiB / 2.87 GiB, 42%, 12.4 MiB/s, ETA 2m17s")),
        CloudSyncTask(8, "Shared documents", "/mnt/tank/family/docs", 4, "Family Drive", "GOOGLE_DRIVE", a("""{"folder":"/NAS docs"}"""),
            CronSchedule("0", "*/6", "*", "*", "*"), direction = CloudDirection.PULL, mode = TransferMode.COPY,
            job = LastJob(JobState.FAILED, now - 3 * h, now - 3 * h, "couldn't list directory: googleapi: Error 401: Invalid Credentials", null, null, null)),
        CloudSyncTask(9, "Archive to offsite", "/mnt/tank/archive", 5, "Offsite box", "SFTP", a("""{"folder":"/srv/backup/archive"}"""),
            CronSchedule("30", "3", "*", "*", "7"), enabled = false, mode = TransferMode.COPY,
            job = LastJob(JobState.SUCCESS, now - 50 * h, now - 49 * h, null, null, 100.0, null)),
    )
    private val data = CloudSyncData(tasks, creds, providers)

    @Composable private fun ListScreen(tab: Int) = Box(Modifier.fillMaxSize()) {
        Column {
            Bar("Cloud sync")
            CloudTabs(tab) {}
            if (tab == 0) CloudTasksContent(data, emptySet(), now, CloudSyncActions()) else CloudCredentialsContent(data, emptySet(), CloudSyncActions())
        }
        ExtendedFloatingActionButton(onClick = {}, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text(if (tab == 0) "New task" else "New credential") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp))
    }

    @Test fun tasks() = shot("v150_tasks") { ListScreen(0) }
    @Test fun tasksLight() = shot("v150_tasks_light", dark = false) { ListScreen(0) }
    @Test fun credentials() = shot("v150_credentials") { ListScreen(1) }

    private val editForm = CloudSyncForm(
        description = "Photos to B2", direction = CloudDirection.PUSH, mode = TransferMode.SYNC, path = "/mnt/tank/photos", credentialId = 3,
        bucket = "photo-backup", folder = "/nas", schedule = CronSchedule("0", "2", "*", "*", "*"), snapshot = true, transfers = "8",
        bwlimit = listOf(BwRow("08:00", "2048"), BwRow("23:00", "")), exclude = "*.tmp\n/.cache/**", encryption = true,
        encryptionPassword = "example-passphrase", encryptionSalt = "example-salt", fastList = true, chunkSize = "96",
    )

    @Composable private fun TaskEditor() = Column {
        Bar("Edit cloud sync task", save = true)
        CloudTaskEditorContent(editForm, TaskEditorRefs(creds, providers), emptyMap(), null, onChange = {})
    }

    @Test fun taskEditor() = shot("v150_task_editor") { TaskEditor() }
    @Test fun taskEditorOptions() = shot("v150_task_editor_options", before = { rule.mainClock.autoAdvance = true; rule.onNodeWithText("Encrypt in the cloud").performScrollTo(); rule.mainClock.autoAdvance = false }) { TaskEditor() }

    @Test fun credentialS3() = shot("v150_credential_s3", dark = false) {
        Column {
            Bar("New cloud credential", save = true)
            CredentialEditorContent(
                CredentialForm("MinIO", "S3", CloudProviders.defaults("S3") + mapOf("access_key_id" to "minio-example", "secret_access_key" to "example-secret", "endpoint" to "https://s3.example.com")),
                CloudProviders.SPECS.map { ProviderOption(it.type, it.title) }, emptyList(), emptyMap(), null, isNew = true, onChange = {},
            )
        }
    }

    @Test fun credentialOAuth() = shot("v150_credential_oauth") {
        Column {
            Bar("New cloud credential", save = true)
            CredentialEditorContent(
                CredentialForm("Family Drive", "GOOGLE_DRIVE", CloudProviders.defaults("GOOGLE_DRIVE") + ("token" to """{"access_token":"ya29.example","token_type":"Bearer","refresh_token":"1//example","expiry":"2026-10-09T12:00:00Z"}""")),
                CloudProviders.SPECS.map { ProviderOption(it.type, it.title) }, listOf(KeyPair(4, "backup-key")), emptyMap(), null, isNew = true, onChange = {},
            )
        }
    }

    @Test fun runDialog() = shot("v150_run_dialog") {
        ListScreen(0)
        RunCloudSyncDialog(tasks[0], dryRun = false, canNotify = true, onDismiss = {}) {}
    }

    @Test fun remoteBrowser() = shot("v150_remote_browser") {
        TaskEditor()
        RemoteBrowserDialog(
            RemoteBrowser(false, "photo-backup", "nas", listOf(
                RemoteEntry("2025", "2025", true, null), RemoteEntry("2026", "2026", true, null), RemoteEntry("Screenshots", "Screenshots", true, null),
                RemoteEntry("index.txt", "index.txt", false, 2_048),
            ), loading = false),
            "Bucket", onOpen = {}, onUp = {}, onChoose = {}, onDismiss = {},
        )
    }

    @Test fun folderPicker() = shot("v150_folder_picker") {
        FileBrowserContent(
            FileBrowserUi(path = "/mnt/tank/photos", loading = false, entries = listOf(
                FileEntry("2025", "/mnt/tank/photos/2025", "DIRECTORY", 0, mtimeMillis = now - 40 * 24 * h),
                FileEntry("2026", "/mnt/tank/photos/2026", "DIRECTORY", 0, mtimeMillis = now - 2 * h),
                FileEntry("Albums", "/mnt/tank/photos/Albums", "DIRECTORY", 0, mtimeMillis = now - 9 * 24 * h),
                FileEntry("cover.jpg", "/mnt/tank/photos/cover.jpg", "FILE", 2_400_000, mtimeMillis = now - 30 * 24 * h),
            )),
            actions = FileActions(pickMode = true, pickFolder = true),
        )
    }

    @Test fun protection() = shot("v150_protection", before = { rule.mainClock.autoAdvance = true; rule.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(androidx.compose.ui.test.hasTestTag("open-cloud-sync")); rule.mainClock.autoAdvance = false }) {
        Column {
            Bar("Storage")
            ProtectionContent(
                ProtectionData(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
                    listOf(ProtectionSamples.backup(BackupKind.CLOUD_SYNC), ProtectionSamples.backup(BackupKind.REPLICATION, id = 2, name = "tank → offsite")),
                    emptyList(), now),
                emptySet(), ProtectionActions(),
            )
        }
    }
}
