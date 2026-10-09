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
import app.truenascompanion.data.model.BackupKind
import app.truenascompanion.data.replication.KeychainUse
import app.truenascompanion.data.replication.ReplDirection
import app.truenascompanion.data.store.ThemeMode
import app.truenascompanion.ui.protection.ProtectionActions
import app.truenascompanion.ui.protection.ProtectionContent
import app.truenascompanion.ui.replication.ConnectionsContent
import app.truenascompanion.ui.replication.DatasetBrowser
import app.truenascompanion.ui.replication.DatasetPickerDialog
import app.truenascompanion.ui.replication.KeyPairContent
import app.truenascompanion.ui.replication.KeyPairForm
import app.truenascompanion.ui.replication.KeychainDelete
import app.truenascompanion.ui.replication.KeychainDeleteDialog
import app.truenascompanion.ui.replication.ReplicationActions
import app.truenascompanion.ui.replication.ReplicationEditorContent
import app.truenascompanion.ui.replication.ReplicationTasksContent
import app.truenascompanion.ui.replication.RunOnceDialog
import app.truenascompanion.ui.replication.RunReplicationDialog
import app.truenascompanion.ui.replication.SshConnForm
import app.truenascompanion.ui.replication.SshConnectionContent
import app.truenascompanion.ui.theme.TrueNasTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 1.6.0 Compose checks: replication confirmations, masked secrets, field errors and links (no dialogs with text fields). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi", application = android.app.Application::class)
class V160ComposeTest {
    @get:Rule val rule = createComposeRule()

    private fun show(content: @Composable () -> Unit) = rule.setContent { TrueNasTheme(themeMode = ThemeMode.DARK, dynamicColor = false) { content() } }
    private val s = ReplicationSamples
    private fun masked(secret: String) = SemanticsMatcher("masked") { n -> n.config.getOrNull(SemanticsProperties.EditableText)?.text?.contains(secret) == false }

    @Test fun runNowAsksFirstWithNotifyOption() {
        var notify: Boolean? = null
        show { RunReplicationDialog(s.tasks[2], canNotify = true, onDismiss = {}) { notify = it } }
        rule.onNodeWithText("Run Pull media now?").assertIsDisplayed()
        rule.onNodeWithText("pulled from the other system", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("notify-check").performClick()
        rule.onNodeWithText("Run now").performClick()
        assertEquals(false, notify)
    }

    @Test fun runOnceAsksFirst() {
        var notify: Boolean? = null
        show { RunOnceDialog(s.form, canNotify = true, onDismiss = {}) { notify = it } }
        rule.onNodeWithText("Replicate once now?").assertIsDisplayed()
        rule.onNodeWithText("without saving a task", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Run once").performClick()
        assertEquals(true, notify)
    }

    @Test fun taskCardsShowStateAndRunNeedsAnEnabledTask() {
        val ran = mutableListOf<Int>()
        show { ReplicationTasksContent(s.data, emptySet(), s.NOW, object : ReplicationActions() { override fun run(t: app.truenascompanion.data.replication.ReplicationTask) { ran += t.id } }) }
        rule.onNodeWithText("Photos to backup NAS").assertIsDisplayed()
        rule.onNodeWithText("(2 of 4)", substring = true).assertIsDisplayed()
        rule.onNodeWithText("After the snapshot task").assertIsDisplayed()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("No incremental base", substring = true))
        rule.onNodeWithText("No incremental base", substring = true).assertIsDisplayed()
        rule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("repl-task-14"))
        rule.onAllNodesWithText("Run now")[0].assertIsDisplayed()
        rule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("repl-task-15"))
        rule.onNodeWithText("Off · runs only when started here").assertIsDisplayed()
        assertEquals(emptyList<Int>(), ran)
    }

    @Test fun deletingAKeyInUseIsBlocked() {
        var confirmed = 0
        show { KeychainDeleteDialog(KeychainDelete(4, "replication-key", true, listOf(KeychainUse("SSH connection \"Backup NAS\"", "delete"))), {}, { confirmed++ }) }
        rule.onNodeWithText("Key pair in use").assertIsDisplayed()
        rule.onNodeWithText("• SSH connection \"Backup NAS\"").assertIsDisplayed()
        rule.onNodeWithText("OK").performClick()
        assertEquals(0, confirmed)
    }

    @Test fun deletingAnUnusedConnectionConfirms() {
        var confirmed = 0
        show { KeychainDeleteDialog(KeychainDelete(7, "Offsite", false, emptyList()), {}, { confirmed++ }) }
        rule.onNodeWithText("Delete", substring = false).performClick()
        assertEquals(1, confirmed)
    }

    @Test fun connectionsListShowsKeysAndAddressesOnly() {
        show { ConnectionsContent(s.data, emptySet(), ReplicationActions()) }
        rule.onNodeWithText("root@nas2.example.com").assertIsDisplayed()
        rule.onNodeWithText("zfs@203.0.113.7:2222").assertIsDisplayed()
        rule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("key-4"))
        rule.onAllNodesWithText("BEGIN", substring = true).assertCountEquals(0)
    }

    @Test fun editorShowsFieldErrorsAndDangerBanner() {
        show {
            ReplicationEditorContent(s.form.copy(allowFromScratch = true, targetDataset = ""), s.refs, mapOf("target_dataset" to "Choose where the snapshots go"), null,
                onChange = {})
        }
        rule.onNodeWithText("Choose where the snapshots go").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Allow from scratch can destroy all snapshots on the target dataset.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("run-once").performScrollTo().assertIsNotEnabled()
    }

    @Test fun pullEditorHasNoSnapshotTaskTiming() {
        show { ReplicationEditorContent(s.form.copy(direction = ReplDirection.PULL), s.refs, emptyMap(), null, onChange = {}) }
        rule.onAllNodesWithText("After snapshot task").assertCountEquals(0)
        rule.onNodeWithText("On a schedule").performScrollTo().assertIsDisplayed()
    }

    @Test fun datasetPickerTicksDatasets() {
        val toggled = mutableListOf<String>()
        show { DatasetPickerDialog(DatasetBrowser(true, false, listOf("tank", "tank/apps", "tank/photos"), setOf("tank/photos"), loading = false), { toggled += it }, {}, {}) }
        rule.onNodeWithTag("dataset-list").assertIsDisplayed()
        rule.onNodeWithText("apps").performClick()
        assertEquals(listOf("tank/apps"), toggled)
    }

    @Test fun semiAutomaticSetupMasksThePassword() {
        show { SshConnectionContent(SshConnForm(name = "Backup NAS", password = "hunter22", url = "https://nas2.example.com"), s.keys, false, emptyMap(), null, false, false, {}, {}) }
        rule.onNodeWithTag("secret:Admin password *").assert(masked("hunter22"))
        rule.onNodeWithText("Another TrueNAS").assertIsDisplayed()
    }

    @Test fun keyPairHidesThePrivateKey() {
        val k = s.keys[0]
        show { KeyPairContent(KeyPairForm(k.name, k.privateKey!!, k.publicKey!!), k, emptyMap(), null, false, false, {}, {}) }
        val field = rule.onNodeWithTag("secret:Private key").performScrollTo()
        field.assert(masked("BEGIN OPENSSH"))
        rule.onNodeWithText("Copy public key").performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("Show").performScrollTo().performClick()
        field.assert(SemanticsMatcher("revealed") { n -> n.config.getOrNull(SemanticsProperties.EditableText)?.text?.contains("BEGIN OPENSSH") == true })
    }

    @Test fun protectionLinksToReplication() {
        var opened = 0
        val d = app.truenascompanion.ui.protection.ProtectionData(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
            listOf(ProtectionSamples.backup(BackupKind.REPLICATION, id = 2, name = "tank → offsite")), emptyList(), ProtectionSamples.NOW)
        show { ProtectionContent(d, emptySet(), object : ProtectionActions() { override fun openReplication() { opened++ } }) }
        rule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("open-replication"))
        rule.onNodeWithTag("open-replication").performClick()
        assertEquals(1, opened)
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("tank → offsite"))
        rule.onNodeWithText("tank → offsite").performClick()
        assertEquals(2, opened)
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEquals(n: Int) = assertEquals(n, fetchSemanticsNodes().size)
