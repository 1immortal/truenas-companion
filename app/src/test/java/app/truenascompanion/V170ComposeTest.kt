package app.truenascompanion

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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
import app.truenascompanion.data.api.StepState
import app.truenascompanion.data.api.WizardResult
import app.truenascompanion.data.api.WizardStep
import app.truenascompanion.data.iscsi.AuthForm
import app.truenascompanion.data.iscsi.IscsiLogic
import app.truenascompanion.data.iscsi.WizardChap
import app.truenascompanion.data.iscsi.WizardForm
import app.truenascompanion.data.store.ThemeMode
import app.truenascompanion.ui.iscsi.AuthContent
import app.truenascompanion.ui.iscsi.DeleteOptions
import app.truenascompanion.ui.iscsi.IscsiContent
import app.truenascompanion.ui.iscsi.IscsiDelete
import app.truenascompanion.ui.iscsi.IscsiNav
import app.truenascompanion.ui.iscsi.IscsiSettingsContent
import app.truenascompanion.ui.iscsi.IscsiSummary
import app.truenascompanion.ui.iscsi.IscsiTab
import app.truenascompanion.ui.iscsi.WizardFormContent
import app.truenascompanion.ui.iscsi.WizardProgress
import app.truenascompanion.ui.iscsi.WizardRun
import app.truenascompanion.ui.storage.SharesData
import app.truenascompanion.ui.storage.SharesPane
import app.truenascompanion.ui.theme.TrueNasTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 1.7.0 Compose checks: iSCSI delete warnings, masked CHAP secrets, the wizard and the Shares sub-tab (no dialogs with text fields). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi", application = android.app.Application::class)
class V170ComposeTest {
    @get:Rule val rule = createComposeRule()

    private fun show(content: @Composable () -> Unit) = rule.setContent { TrueNasTheme(themeMode = ThemeMode.DARK, dynamicColor = false) { content() } }
    private val d = IscsiSamples.data
    private fun masked(secret: String) = SemanticsMatcher("masked") { n -> n.config.getOrNull(SemanticsProperties.EditableText)?.text?.contains(secret) == false }

    @Test fun targetCardsShowIqnLunsAndSessions() {
        show { IscsiContent(d, IscsiTab.TARGETS, {}, false, IscsiNav()) }
        rule.onNodeWithText("iqn.2005-10.org.freenas.ctl:vm-datastore").assertIsDisplayed()
        rule.onNodeWithText("2 connected").assertIsDisplayed()
        rule.onNodeWithTag("lun-vm-datastore-0").assertIsDisplayed()
        rule.onNodeWithText("Targets (3)").assertIsDisplayed()
        rule.onNodeWithText("Sessions (2)").assertIsDisplayed()
    }

    @Test fun deletingAFileExtentOffersToDeleteTheFile() {
        var got: Pair<IscsiDelete, DeleteOptions>? = null
        show { IscsiContent(d, IscsiTab.EXTENTS, {}, false, IscsiNav(), onDelete = { x, o -> got = x to o }, initialDelete = IscsiDelete.Extent(d.extents[1])) }
        rule.onNodeWithText("Delete extent backup-disk?").assertIsDisplayed()
        rule.onNodeWithText("The file is kept unless you tick the box below.", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Also delete the file /mnt/tank/iscsi/backup.img and all data in it").performClick()
        rule.onNodeWithTag("confirm-delete").performClick()
        rule.waitForIdle()
        assertEquals(DeleteOptions(removeFile = true), got?.second)
    }

    @Test fun deletingAZvolExtentSaysTheZvolStaysAndNeedsForceWithSessions() {
        var got: DeleteOptions? = null
        show { IscsiContent(d, IscsiTab.EXTENTS, {}, false, IscsiNav(), onDelete = { _, o -> got = o }, initialDelete = IscsiDelete.Extent(d.extents[0])) }
        rule.onNodeWithText("The zvol tank/iscsi/vm-datastore and its data are kept", substring = true).assertIsDisplayed()
        rule.onAllNodesWithText("Also delete the file", substring = true).assertCountEquals(0)
        rule.onNodeWithTag("confirm-delete").assertIsNotEnabled()
        rule.onNodeWithTag("opt-force").performClick()
        rule.onNodeWithTag("confirm-delete").assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(DeleteOptions(force = true), got)
    }

    @Test fun targetDeleteCanAlsoRemoveExtents() {
        var got: DeleteOptions? = null
        show { IscsiContent(d, IscsiTab.TARGETS, {}, false, IscsiNav(), onDelete = { _, o -> got = o }, initialDelete = IscsiDelete.Target(d.targets[1])) }
        rule.onNodeWithText("Delete target backup-disk?").assertIsDisplayed()
        rule.onNodeWithTag("opt-extents").performClick()
        rule.onNodeWithTag("confirm-delete").performClick()
        rule.waitForIdle()
        assertEquals(DeleteOptions(deleteExtents = true), got)
    }

    @Test fun inUseInitiatorGroupIsBlocked() {
        var called = false
        show { IscsiContent(d, IscsiTab.ACCESS, {}, false, IscsiNav(), onDelete = { _, _ -> called = true }, initialDelete = IscsiDelete.Initiator(d.initiators[0])) }
        rule.onNodeWithText("Hypervisors is in use").assertIsDisplayed()
        rule.onAllNodesWithText("Delete").assertCountEquals(0)
        rule.onNodeWithText("OK").performClick()
        assertTrue(!called)
    }

    @Test fun accessTabListsPortalsInitiatorsAndChapWithoutSecrets() {
        show { IscsiContent(d, IscsiTab.ACCESS, {}, false, IscsiNav()) }
        rule.onNodeWithText("0.0.0.0:3260").assertIsDisplayed()
        rule.onNodeWithTag("iscsi-list").performScrollToNode(hasText("vmhost"))
        rule.onNodeWithText("Group 1 · mutual (peer truenas)").assertIsDisplayed()
    }

    @Test fun chapSecretsAreMaskedUntilShown() {
        show { AuthContent(AuthForm("3", "pc1", "exampleSecret1", true, "nas", "examplePeerSec2"), d, false, emptyMap(), null, false) {} }
        rule.onNodeWithTag("secret:Secret *").assert(masked("exampleSecret1"))
        rule.onNodeWithTag("secret:Peer secret *").assert(masked("examplePeerSec2"))
    }

    @Test fun editedChapUserKeepsServerSecretHidden() {
        show { AuthContent(IscsiLogic.authForm(d.auths[0]), d, true, emptyMap(), null, false) {} }
        rule.onAllNodesWithText(app.truenascompanion.ui.components.HIDDEN_BY_SERVER).assertCountEquals(2)
    }

    @Test fun wizardMasksTheNewSecretAndShowsWhatItWillCreate() {
        val f = WizardForm(name = "lab", parent = "tank", size = "10", chap = WizardChap.NEW, chapUser = "lab", chapSecret = "exampleSecret1", portalIps = listOf("192.168.1.50"))
        show { WizardFormContent(f, d, IscsiLogic.wizardErrors(f, d), true, {}, {}) }
        rule.onNodeWithTag("page").performScrollToNode(hasTestTag("secret:Secret *"))
        rule.onNodeWithTag("secret:Secret *").assert(masked("exampleSecret1"))
        rule.onNodeWithTag("page").performScrollToNode(hasTestTag("wizard-plan"))
        rule.onNodeWithText("• Create the CHAP user").assertIsDisplayed()
    }

    @Test fun wizardFailureExplainsTheRollback() {
        val run = WizardRun(linkedMapOf(WizardStep.ZVOL to StepState.UNDONE, WizardStep.EXTENT to StepState.UNDO_FAILED, WizardStep.TARGET to StepState.FAILED),
            WizardResult(false, error = "Create the target failed: boom", undoErrors = listOf("Create the extent: busy")))
        show { WizardProgress(run, WizardForm(name = "lab"), d, null, {}, {}, {}) }
        rule.onNodeWithText("remove these by hand", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Create the zvol (undone)").assertIsDisplayed()
        rule.onNodeWithTag("wizard-back").assertIsDisplayed()
    }

    @Test fun wizardSuccessOffersToStartTheService() {
        var started = false
        val run = WizardRun(linkedMapOf(WizardStep.EXTENT to StepState.DONE, WizardStep.TARGET to StepState.DONE, WizardStep.LUN to StepState.DONE),
            WizardResult(true, iqn = "iqn.2005-10.org.freenas.ctl:lab"))
        show { WizardProgress(run, WizardForm(name = "lab", portalIps = listOf("192.168.1.50")), IscsiSamples.empty, null, { started = true }, {}, {}) }
        rule.onNodeWithText("iqn.2005-10.org.freenas.ctl:lab").assertIsDisplayed()
        rule.onNodeWithText("192.168.1.50:3260").assertIsDisplayed()
        rule.onNodeWithText("Start iSCSI service").performScrollTo().performClick()
        rule.onNodeWithText("Start the iSCSI service?").assertIsDisplayed()
        rule.onNodeWithText("Start service").performClick()
        rule.waitForIdle()
        assertTrue(started)
    }

    @Test fun sharesTabSummaryStartsServiceOnlyAfterConfirming() {
        var started = 0; var services = 0
        show { IscsiSummary(IscsiSamples.empty, false, onStart = { started++ }, onOpen = {}, onWizard = {}, onServices = { services++ }) }
        rule.onNodeWithText("Stopped").assertIsDisplayed()
        rule.onNodeWithTag("start-iscsi").performClick()
        assertEquals(0, started)
        rule.onNodeWithText("Start service").performClick()
        rule.waitForIdle()
        assertEquals(1, started)
        rule.onNodeWithTag("open-services").performClick()
        assertEquals(1, services)
    }

    @Test fun sharesPaneHasAnIscsiSubTab() {
        show {
            SharesPane(SharesData(emptyList(), emptyList()), emptyList(), emptySet(), {}, { _, _ -> }, {}, {}, { _, _ -> }, {},
                iscsiContent = { IscsiSummary(d, false, {}, {}, {}, {}) })
        }
        rule.onNodeWithText("iSCSI").performClick()
        rule.onNodeWithText("iSCSI block shares").assertIsDisplayed()
        rule.onNodeWithText("3 targets · 3 extents · 2 connected").assertIsDisplayed()
        rule.onAllNodesWithText("Add share").assertCountEquals(0)
        rule.onNodeWithContentDescription("Add share").assertDoesNotExist()
    }

    @Test fun settingsHideAluaWithoutHaAndLinkToServices() {
        var services = 0
        show { IscsiSettingsContent(IscsiLogic.globalForm(d.global), d, emptyMap(), null, false, {}) { services++ } }
        rule.onNodeWithText("only available on TrueNAS Enterprise", substring = true).performScrollTo().assertIsDisplayed()
        rule.onAllNodesWithText("ALUA").assertCountEquals(0)
        rule.onNodeWithTag("open-services").performScrollTo().performClick()
        assertEquals(1, services)
    }
}
