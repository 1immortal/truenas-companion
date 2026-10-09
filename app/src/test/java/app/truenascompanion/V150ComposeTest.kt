package app.truenascompanion

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import app.truenascompanion.data.api.VerifyResult
import app.truenascompanion.data.cloud.CloudCredential
import app.truenascompanion.data.cloud.CloudDirection
import app.truenascompanion.data.cloud.CloudProvider
import app.truenascompanion.data.cloud.CloudProviders
import app.truenascompanion.data.cloud.CloudSyncForm
import app.truenascompanion.data.cloud.CloudSyncLogic
import app.truenascompanion.data.cloud.CloudSyncTask
import app.truenascompanion.data.cloud.TransferMode
import app.truenascompanion.data.model.FileEntry
import app.truenascompanion.data.model.BackupKind
import app.truenascompanion.data.model.CronSchedule
import app.truenascompanion.data.model.JobState
import app.truenascompanion.data.model.LastJob
import app.truenascompanion.data.store.ThemeMode
import app.truenascompanion.ui.cloud.CloudSyncActions
import app.truenascompanion.ui.cloud.CloudSyncData
import app.truenascompanion.ui.cloud.CloudTaskEditorContent
import app.truenascompanion.ui.cloud.CloudTasksContent
import app.truenascompanion.ui.cloud.CredentialEditorContent
import app.truenascompanion.ui.cloud.CredentialForm
import app.truenascompanion.ui.cloud.ProviderOption
import app.truenascompanion.ui.cloud.RemoteBrowser
import app.truenascompanion.ui.cloud.RemoteBrowserDialog
import app.truenascompanion.ui.cloud.RunCloudSyncDialog
import app.truenascompanion.ui.cloud.TaskEditorRefs
import app.truenascompanion.ui.cloud.VerifyDialog
import app.truenascompanion.ui.files.FileActions
import app.truenascompanion.ui.files.FileBrowserContent
import app.truenascompanion.ui.files.FileBrowserUi
import app.truenascompanion.ui.protection.ProtectionActions
import app.truenascompanion.ui.protection.ProtectionContent
import app.truenascompanion.ui.theme.TrueNasTheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 1.5.0 Compose checks: cloud sync confirmations, masked secrets, field errors and the folder picker. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi", application = android.app.Application::class)
class V150ComposeTest {
    @get:Rule val rule = createComposeRule()

    private fun show(content: @Composable () -> Unit) = rule.setContent { TrueNasTheme(themeMode = ThemeMode.DARK, dynamicColor = false) { content() } }

    private val attrs = Json.parseToJsonElement("""{"bucket":"photo-backup","folder":"/nas"}""").jsonObject
    private fun task(id: Int = 7, mode: TransferMode = TransferMode.SYNC, job: LastJob? = null, direction: CloudDirection = CloudDirection.PUSH) = CloudSyncTask(
        id = id, description = "Photos to B2", path = "/mnt/tank/photos", credentialId = 3, credentialName = "Backblaze", providerType = "B2",
        attributes = attrs, schedule = CronSchedule("0", "2", "*", "*", "*"), job = job, mode = mode, direction = direction,
    )
    private val b2 = CloudProvider("B2", "Backblaze B2", null, true, "Bucket", listOf("bucket", "folder", "fast_list", "b2_chunk_size"))
    private val creds = listOf(CloudCredential(3, "Backblaze", "B2"))

    @Test fun runningSyncAsksFirstAndExplainsDeletes() {
        var notify: Boolean? = null
        show { RunCloudSyncDialog(task(), dryRun = false, canNotify = true, onDismiss = {}) { notify = it } }
        rule.onNodeWithText("Run Photos to B2 now?").assertIsDisplayed()
        rule.onNodeWithText("Files deleted on the NAS are deleted in the cloud", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Notify me when it finishes").assertIsDisplayed()
        rule.onNodeWithTag("notify-check").performClick() // opt out
        rule.onNodeWithText("Run now").performClick()
        assertEquals(false, notify)
    }

    @Test fun dryRunChangesNothing() {
        var ran = false
        show { RunCloudSyncDialog(task(mode = TransferMode.COPY), dryRun = true, canNotify = false, onDismiss = {}) { ran = true } }
        rule.onNodeWithText("without changing anything", substring = true).assertIsDisplayed()
        rule.onAllNodesWithText("Notify me when it finishes").assertCountEquals(0)
        rule.onNodeWithText("Dry run").performClick()
        assertTrue(ran)
    }

    @Test fun runningTaskOffersAbortAndShowsProgress() {
        val aborted = mutableListOf<Int>()
        val running = task(job = LastJob(JobState.RUNNING, null, null, null, null, 42.0, "1.2 GiB / 2.9 GiB, 12 MiB/s"))
        val data = CloudSyncData(listOf(running, task(id = 8, mode = TransferMode.COPY).copy(description = "Docs")), creds, listOf(b2))
        show { CloudTasksContent(data, emptySet(), 0L, object : CloudSyncActions() { override fun abort(t: CloudSyncTask) { aborted += t.id } }) }
        rule.onNodeWithText("1.2 GiB / 2.9 GiB, 12 MiB/s").assertIsDisplayed()
        rule.onNodeWithText("Abort").performClick()
        assertEquals(listOf(7), aborted)
        rule.onAllNodesWithText("Run now").assertCountEquals(1)
        rule.onNodeWithText("Push · Sync · Backblaze B2").assertIsDisplayed()
    }

    @Test fun deleteAndRestoreAreInTheMenu() {
        val picked = mutableListOf<String>()
        show {
            CloudTasksContent(CloudSyncData(listOf(task()), creds, listOf(b2)), emptySet(), 0L, object : CloudSyncActions() {
                override fun delete(t: CloudSyncTask) { picked += "delete" }
                override fun restore(t: CloudSyncTask) { picked += "restore" }
            })
        }
        rule.onNodeWithContentDescription("More actions for Photos to B2").performClick()
        rule.onNodeWithText("Restore…").performClick()
        rule.onNodeWithContentDescription("More actions for Photos to B2").performClick()
        rule.onNodeWithText("Delete").performClick()
        assertEquals(listOf("restore", "delete"), picked)
    }

    @Test fun credentialSecretsAreMaskedWithShowHide() {
        val form = CredentialForm("Wasabi", "S3", CloudProviders.defaults("S3") + mapOf("access_key_id" to "AKIAEXAMPLE", "secret_access_key" to "example-secret-key"))
        show { CredentialEditorContent(form, listOf(ProviderOption("S3", "Amazon S3")), emptyList(), emptyMap(), null, isNew = true, onChange = {}) }
        val field = rule.onNodeWithTag("secret:Secret access key *")
        field.assert(SemanticsMatcher("masked") { n -> n.config.getOrNull(SemanticsProperties.EditableText)?.text?.contains("example-secret-key") == false })
        rule.onNodeWithContentDescription("Show").performClick()
        field.assert(SemanticsMatcher("revealed") { n -> n.config.getOrNull(SemanticsProperties.EditableText)?.text == "example-secret-key" })
    }

    @Test fun oauthProvidersExplainThePastedToken() {
        val form = CredentialForm("Drive", "GOOGLE_DRIVE", CloudProviders.defaults("GOOGLE_DRIVE") + ("token" to "not json"))
        show {
            CredentialEditorContent(form, listOf(ProviderOption("GOOGLE_DRIVE", "Google Drive")), emptyList(), CloudProviders.errors(form.type, form.values), null,
                isNew = true, onChange = {})
        }
        rule.onNodeWithText("rclone authorize \"drive\"", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Paste the whole token", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Verify connection").performScrollTo().assertIsNotEnabled()
    }

    @Test fun redactedCredentialCantBeEdited() {
        val form = CredentialForm("B2", "B2", mapOf("account" to "********", "key" to "********"))
        show { CredentialEditorContent(form, listOf(ProviderOption("B2", "Backblaze B2")), emptyList(), emptyMap(), null, isNew = false, onChange = {}) }
        rule.onNodeWithText("doesn't show this credential's keys", substring = true).assertIsDisplayed()
    }

    @Test fun verifyResultIsShown() {
        show { VerifyDialog("Backblaze", VerifyResult(false, "x", "401 Unauthorized"), null) {} }
        rule.onNodeWithText("Couldn't connect").assertIsDisplayed()
        rule.onNodeWithText("401 Unauthorized").assertIsDisplayed()
    }

    @Test fun taskEditorShowsFieldErrorsAndMasksTheEncryptionPassword() {
        val form = CloudSyncLogic.form(task()).copy(path = "/home/x", encryption = true, encryptionPassword = "hunter2-example", bucket = "")
        val errors = CloudSyncLogic.errors(form, b2) + ("folder" to "Directory does not exist")
        show { CloudTaskEditorContent(form, TaskEditorRefs(creds, listOf(b2)), errors, "credentials: Invalid credentials", onChange = {}) }
        rule.onNodeWithText("Pick a folder inside a pool (/mnt/…)").assertIsDisplayed()
        rule.onNodeWithText("credentials: Invalid credentials").assertIsDisplayed()
        rule.onNodeWithText("Choose a bucket").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Directory does not exist").performScrollTo().assertIsDisplayed()
        val pw = rule.onNodeWithTag("secret:Encryption password *").performScrollTo()
        pw.assert(SemanticsMatcher("masked") { n -> n.config.getOrNull(SemanticsProperties.EditableText)?.text?.contains("hunter2-example") == false })
    }

    @Test fun pullTasksHideTheSnapshotOption() {
        val form = CloudSyncForm(direction = CloudDirection.PULL, path = "/mnt/tank/in", credentialId = 3, bucket = "b")
        show { CloudTaskEditorContent(form, TaskEditorRefs(creds, listOf(b2)), emptyMap(), null, onChange = {}) }
        rule.onAllNodesWithText("Take a snapshot first").assertCountEquals(0)
        rule.onNodeWithText("Copies new and changed files to the NAS", substring = true).assertIsDisplayed()
    }

    @Test fun remoteBrowserOpensFoldersAndPicksTheCurrentOne() {
        val opened = mutableListOf<String>()
        var chosen = false
        val b = RemoteBrowser(false, "photo-backup", "nas", listOf(
            app.truenascompanion.data.api.RemoteEntry("2026", "2026", true, null),
            app.truenascompanion.data.api.RemoteEntry("notes.txt", "notes.txt", false, 120),
        ), loading = false)
        show { RemoteBrowserDialog(b, "Bucket", onOpen = { opened += it.name }, onUp = {}, onChoose = { chosen = true }, onDismiss = {}) }
        rule.onNodeWithText("photo-backup/nas").assertIsDisplayed()
        rule.onNodeWithText("2026").performClick()
        rule.onNodeWithText("notes.txt").performClick() // files can't be opened
        rule.onNodeWithText("Use this folder").performClick()
        assertEquals(listOf("2026"), opened)
        assertTrue(chosen)
    }

    @Test fun fileBrowserFolderPickerUsesTheOpenFolder() {
        var picked: String? = null
        val ui = FileBrowserUi(path = "/mnt/tank/photos", loading = false,
            entries = listOf(FileEntry(name = "2026", path = "/mnt/tank/photos/2026", type = "DIRECTORY", size = 0)))
        show { FileBrowserContent(ui, actions = FileActions(pickMode = true, pickFolder = true, onPickFolder = { picked = it })) }
        rule.onNodeWithText("Use this folder", useUnmergedTree = true).performClick()
        assertEquals("/mnt/tank/photos", picked)
        rule.onNodeWithText("Open the folder to use", substring = true).assertIsDisplayed()
    }

    @Test fun protectionLinksToCloudSync() {
        var opened = 0
        val d = app.truenascompanion.ui.protection.ProtectionData(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
            listOf(ProtectionSamples.backup(BackupKind.CLOUD_SYNC)), emptyList(), ProtectionSamples.NOW)
        show { ProtectionContent(d, emptySet(), object : ProtectionActions() { override fun openCloudSync() { opened++ } }) }
        rule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("open-cloud-sync"))
        rule.onNodeWithTag("open-cloud-sync").performClick()
        assertEquals(1, opened)
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Photos to B2"))
        rule.onNodeWithText("Photos to B2").performClick()
        assertEquals(2, opened)
        assertFalse(opened == 0)
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEquals(n: Int) = assertEquals(n, fetchSemanticsNodes().size)
