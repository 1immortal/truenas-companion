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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import app.truenascompanion.IscsiSamples
import app.truenascompanion.data.api.StepState
import app.truenascompanion.data.api.WizardResult
import app.truenascompanion.data.api.WizardStep
import app.truenascompanion.data.iscsi.AuthForm
import app.truenascompanion.data.iscsi.IscsiAuthMethod
import app.truenascompanion.data.iscsi.IscsiLogic
import app.truenascompanion.data.iscsi.SharingPlatform
import app.truenascompanion.data.iscsi.WizardChap
import app.truenascompanion.data.iscsi.WizardForm
import app.truenascompanion.data.iscsi.WizardInitiators
import app.truenascompanion.data.store.ThemeMode
import app.truenascompanion.ui.iscsi.AuthContent
import app.truenascompanion.ui.iscsi.ExtentContent
import app.truenascompanion.ui.iscsi.IscsiContent
import app.truenascompanion.ui.iscsi.IscsiDelete
import app.truenascompanion.ui.iscsi.IscsiNav
import app.truenascompanion.ui.iscsi.IscsiSettingsContent
import app.truenascompanion.ui.iscsi.IscsiSummary
import app.truenascompanion.ui.iscsi.IscsiTab
import app.truenascompanion.ui.iscsi.TargetContent
import app.truenascompanion.ui.iscsi.WizardFormContent
import app.truenascompanion.ui.iscsi.WizardProgress
import app.truenascompanion.ui.iscsi.WizardRun
import app.truenascompanion.ui.storage.SharesData
import app.truenascompanion.ui.storage.SharesPane
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

/** 1.7.0 previews (example data only): iSCSI in Storage › Shares, targets, extents, access, editors and the wizard. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi", application = android.app.Application::class)
class V170ScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private val dir = File(System.getProperty("screenshot.dir") ?: "build/screenshots").apply { mkdirs() }
    private fun out(name: String) = File(dir, "$name.png").absolutePath
    private val d = IscsiSamples.data

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


    private fun tap(tag: String): () -> Unit = { rule.mainClock.autoAdvance = true; rule.onNodeWithTag(tag).performClick(); rule.mainClock.autoAdvance = false }
    private fun scrollTag(container: String, tag: String): () -> Unit = {
        rule.mainClock.autoAdvance = true; rule.onNodeWithTag(container).performScrollToNode(hasTestTag(tag)); rule.mainClock.autoAdvance = false
    }

    @Composable private fun Iscsi(tab: IscsiTab, delete: IscsiDelete? = null) = Column {
        Bar("iSCSI")
        IscsiContent(d, tab, {}, false, IscsiNav(), initialDelete = delete)
    }

    @Test fun shares() = shot("v170_shares_iscsi", before = { rule.mainClock.autoAdvance = true; rule.onNodeWithText("iSCSI").performClick(); rule.mainClock.autoAdvance = false }) {
        Column {
            Bar("Storage")
            SharesPane(SharesData(emptyList(), emptyList()), emptyList(), emptySet(), {}, { _, _ -> }, {}, {}, { _, _ -> }, {},
                iscsiContent = { IscsiSummary(d, false, {}, {}, {}, {}) })
        }
    }
    @Test fun sharesStopped() = shot("v170_shares_iscsi_stopped", dark = false, before = { rule.mainClock.autoAdvance = true; rule.onNodeWithText("iSCSI").performClick(); rule.mainClock.autoAdvance = false }) {
        Column {
            Bar("Storage")
            SharesPane(SharesData(emptyList(), emptyList()), emptyList(), emptySet(), {}, { _, _ -> }, {}, {}, { _, _ -> }, {},
                iscsiContent = { IscsiSummary(IscsiSamples.empty, false, {}, {}, {}, {}) })
        }
    }
    @Test fun targets() = shot("v170_targets") { Iscsi(IscsiTab.TARGETS) }
    @Test fun targetsLight() = shot("v170_targets_light", dark = false) { Iscsi(IscsiTab.TARGETS) }
    @Test fun extents() = shot("v170_extents") { Iscsi(IscsiTab.EXTENTS) }
    @Test fun access() = shot("v170_access") { Iscsi(IscsiTab.ACCESS) }
    @Test fun sessions() = shot("v170_sessions") { Iscsi(IscsiTab.SESSIONS) }
    @Test fun deleteFileExtent() = shot("v170_delete_extent", before = tap("opt-file")) { Iscsi(IscsiTab.EXTENTS, IscsiDelete.Extent(d.extents[1])) }
    @Test fun deleteTarget() = shot("v170_delete_target") { Iscsi(IscsiTab.TARGETS, IscsiDelete.Target(d.targets[0])) }

    @Test fun targetEditor() = shot("v170_target_editor") {
        Column { Bar("Edit target", "Save"); TargetContent(IscsiLogic.targetForm(d.targets[0]).copy(groups = d.targets[0].groups + d.targets[1].groups.map { it.copy(portal = 2, authmethod = IscsiAuthMethod.NONE) }), d, emptyMap(), null, false) {} }
    }
    @Test fun extentEditor() = shot("v170_extent_editor") {
        Column { Bar("Edit extent", "Save"); ExtentContent(IscsiLogic.extentForm(d.extents[1]), d, 2, emptyMap(), null, false) {} }
    }
    @Test fun authEditor() = shot("v170_chap_editor") {
        Column { Bar("New CHAP user", "Save"); AuthContent(AuthForm("3", "lab", "exampleSecret1", true, "nas", "examplePeerSec2"), d, false, emptyMap(), null, false) {} }
    }
    @Test fun settings() = shot("v170_settings") {
        Column { Bar("iSCSI settings", "Save"); IscsiSettingsContent(IscsiLogic.globalForm(d.global), d, emptyMap(), null, false, {}) {} }
    }

    private val wiz = WizardForm(name = "lab-disk", parent = "tank/iscsi", size = "50", platform = SharingPlatform.MODERN, newPortal = false, portalId = 1,
        initiators = WizardInitiators.LIST, initiatorList = "iqn.1991-05.com.microsoft:lab-pc", chap = WizardChap.NEW, chapUser = "lab", chapSecret = "exampleSecret1")

    @Composable private fun Wizard() = Column { Bar("Share a block device"); WizardFormContent(wiz, d, IscsiLogic.wizardErrors(wiz, d), false, {}, {}) }
    @Test fun wizard() = shot("v170_wizard") { Wizard() }
    @Test fun wizardPortal() = shot("v170_wizard_portal", before = scrollTo("3 · Who may connect")) { Wizard() }
    @Test fun wizardAccess() = shot("v170_wizard_access", before = scrollTag("page", "wizard-create")) { Wizard() }
    @Test fun wizardDone() = shot("v170_wizard_done") {
        Column {
            Bar("Share a block device")
            WizardProgress(WizardRun(linkedMapOf(WizardStep.ZVOL to StepState.DONE, WizardStep.EXTENT to StepState.DONE, WizardStep.INITIATORS to StepState.DONE,
                WizardStep.CHAP to StepState.DONE, WizardStep.TARGET to StepState.DONE, WizardStep.LUN to StepState.DONE), WizardResult(true, "${IscsiSamples.BASE}:lab-disk")),
                wiz, d.copy(serviceRunning = false), null, {}, {}, {})
        }
    }
    @Test fun wizardRollback() = shot("v170_wizard_rollback") {
        Column {
            Bar("Share a block device")
            WizardProgress(WizardRun(linkedMapOf(WizardStep.ZVOL to StepState.UNDONE, WizardStep.EXTENT to StepState.UNDONE, WizardStep.INITIATORS to StepState.UNDONE,
                WizardStep.CHAP to StepState.UNDONE, WizardStep.TARGET to StepState.FAILED),
                WizardResult(false, error = "Create the target failed: Target name lab-disk already exists. Everything else is fine.".substringBefore(" Everything"))), wiz, d, null, {}, {}, {})
        }
    }
}
