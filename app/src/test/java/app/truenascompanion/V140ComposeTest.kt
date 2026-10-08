package app.truenascompanion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.truenascompanion.data.api.ServiceVerb
import app.truenascompanion.data.model.CronJob
import app.truenascompanion.data.model.CronSchedule
import app.truenascompanion.data.model.JobState
import app.truenascompanion.data.model.LastJob
import app.truenascompanion.data.model.ServiceInfo
import app.truenascompanion.data.services.ServiceForms
import app.truenascompanion.data.services.ServiceKind
import app.truenascompanion.data.store.ThemeMode
import app.truenascompanion.ui.components.SecretTextField
import app.truenascompanion.ui.services.ServiceActionDialog
import app.truenascompanion.ui.services.ServiceSettingsContent
import app.truenascompanion.ui.services.ServiceSettingsData
import app.truenascompanion.ui.services.ServicesContent
import app.truenascompanion.ui.tasks.CronRun
import app.truenascompanion.ui.tasks.CronRunDialog
import app.truenascompanion.ui.tasks.CronSchedulePicker
import app.truenascompanion.ui.tasks.DeleteTaskDialog
import app.truenascompanion.ui.tasks.RunCronConfirmDialog
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

/** 1.4.0 Compose checks: confirmations for disruptive actions and masked secret fields. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi", application = android.app.Application::class)
class V140ComposeTest {
    @get:Rule val rule = createComposeRule()

    private fun show(content: @Composable () -> Unit) = rule.setContent { TrueNasTheme(themeMode = ThemeMode.DARK, dynamicColor = false) { content() } }

    private val ssh = ServiceInfo(id = 1, service = "ssh", running = true, enabledOnBoot = true)
    private val ups = ServiceInfo(id = 2, service = "ups", running = true, enabledOnBoot = false)
    private val job = CronJob(3, "Nightly cleanup", "/mnt/tank/scripts/cleanup.sh", "root", CronSchedule("0", "3", "*", "*", "*"), true, true, false)

    @Test fun stoppingSshWarnsAboutLosingAccess() {
        var confirmed = false
        show { ServiceActionDialog(ssh, ServiceVerb.STOP, onConfirm = { confirmed = true }, onDismiss = {}) }
        rule.onNodeWithText("Stop SSH?").assertIsDisplayed()
        rule.onNodeWithText("This may cut off access to the NAS.", substring = true).assertIsDisplayed()
        assertFalse(confirmed)
        rule.onNodeWithText("Stop").performClick()
        assertTrue(confirmed)
    }

    @Test fun stoppingSmbWarnsToo() {
        show { ServiceActionDialog(ServiceInfo(id = 5, service = "cifs", running = true, enabledOnBoot = true), ServiceVerb.STOP, onConfirm = {}, onDismiss = {}) }
        rule.onNodeWithText("Stop SMB?").assertIsDisplayed()
        rule.onNodeWithText("may cut off access", substring = true).assertIsDisplayed()
    }

    @Test fun stoppingUpsHasItsOwnWarningAndCancelDoesNothing() {
        var confirmed = false
        var dismissed = false
        show { ServiceActionDialog(ups, ServiceVerb.STOP, onConfirm = { confirmed = true }, onDismiss = { dismissed = true }) }
        rule.onNodeWithText("won't shut down safely", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()
        assertTrue(dismissed)
        assertFalse(confirmed)
    }

    @Test fun serviceCardsOfferStopRestartAndStart() {
        val actions = mutableListOf<Pair<String, ServiceVerb>>()
        val stopped = ServiceInfo(id = 3, service = "nfs", running = false, enabledOnBoot = false)
        show { ServicesContent(listOf(ssh, stopped), emptyMap(), onAction = { s, v -> actions += s.service to v }, onAutostart = { _, _ -> }, onOpen = {}) }
        rule.onNodeWithText("Restart").performClick()
        rule.onNodeWithText("Start").performClick()
        assertEquals(listOf("ssh" to ServiceVerb.RESTART, "nfs" to ServiceVerb.START), actions)
    }

    @Test fun secretFieldIsMaskedUntilShown() {
        var value by mutableStateOf("example-pass")
        show { SecretTextField(value, { value = it }, "Monitor password") }
        val field = rule.onNodeWithTag("secret:Monitor password")
        field.assert(SemanticsMatcher("masked") { n -> n.config.getOrNull(SemanticsProperties.EditableText)?.text?.contains("example-pass") == false })
        rule.onNodeWithContentDescription("Show").performClick()
        field.assert(SemanticsMatcher("revealed") { n -> n.config.getOrNull(SemanticsProperties.EditableText)?.text == "example-pass" })
        rule.onNodeWithContentDescription("Hide").performClick()
        field.assert(SemanticsMatcher("masked again") { n -> n.config.getOrNull(SemanticsProperties.EditableText)?.text?.contains("example-pass") == false })
    }

    @Test fun secretsHiddenByTrueNasShowAPlaceholder() {
        val cfg = Json.parseToJsonElement("""{"id":1,"location":"","contact":"","traps":false,"v3":true,"community":"public",
            "v3_username":"monitor","v3_authtype":"SHA","v3_password":"********","v3_privproto":null,"v3_privpassphrase":null,
            "options":"","zilstat":false,"loglevel":3}""").jsonObject
        val fields = ServiceForms.fields(ServiceKind.SNMP)
        show { ServiceSettingsContent(ServiceKind.SNMP, fields, ServiceForms.toDraft(fields, cfg), ServiceSettingsData(cfg, emptyMap(), null), emptyMap(), onChange = { _, _ -> }) }
        rule.onNodeWithText("Hidden by TrueNAS. Leave empty to keep it unchanged.").performScrollTo().assertIsDisplayed()
    }

    @Test fun runNowAsksFirstAndShowsTheCommand() {
        var ran = false
        show { RunCronConfirmDialog(job, onConfirm = { ran = true }, onDismiss = {}) }
        rule.onNodeWithText("Run “Nightly cleanup” now?").assertIsDisplayed()
        rule.onNodeWithText("/mnt/tank/scripts/cleanup.sh").assertIsDisplayed()
        assertFalse(ran)
        rule.onNodeWithText("Run now").performClick()
        assertTrue(ran)
    }

    @Test fun deletingATaskNeedsConfirmation() {
        var deleted = false
        show { DeleteTaskDialog("Delete cron job?", job.title, onConfirm = { deleted = true }, onDismiss = {}) }
        rule.onNodeWithText("can't be undone", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Delete").performClick()
        assertTrue(deleted)
    }

    @Test fun runDialogShowsTheResultAndOutput() {
        val run = CronRun(job, LastJob(JobState.SUCCESS, null, null, null, "cleaned 12 files", 100.0, null))
        show { CronRunDialog(run, onClose = {}) }
        rule.onNodeWithText("Finished").assertIsDisplayed()
        rule.onNodeWithText("cleaned 12 files").assertIsDisplayed()
        rule.onNodeWithText("Close").assertIsDisplayed()
    }

    @Test fun schedulePickerPresetsUpdateTheSummary() {
        var schedule by mutableStateOf(CronSchedule("0", "3", "*", "*", "*"))
        show { CronSchedulePicker(schedule, { schedule = it }) }
        rule.onNodeWithText("Every day at 03:00").assertIsDisplayed()
        rule.onNodeWithText("Weekly").performClick()
        rule.onNodeWithText("Every Sunday at 03:00").assertIsDisplayed()
        rule.onNodeWithText("Wed").performClick()
        rule.onNodeWithText("Every Wednesday at 03:00").assertIsDisplayed()
        assertEquals("3", schedule.dow)
        rule.onNodeWithText("Custom").performClick()
        rule.onNodeWithText("Day of week").assertIsDisplayed()
    }
}
